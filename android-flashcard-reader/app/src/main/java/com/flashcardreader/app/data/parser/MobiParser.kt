package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.nio.charset.Charset

/**
 * Parses classic MOBI (PalmDOC/"MOBI7") ebook files - the format behind plain
 * `.mobi` exports and, as a fallback compatibility copy, most `.azw3`/KF8
 * files too (Kindle keeps a legacy MOBI7 rendition alongside the newer KF8
 * data for older devices, and this parser reads that rendition).
 *
 * There is no maintained Java/Kotlin library that extracts MOBI *text*
 * (the one Maven-published option, lib-mobi, only reads header metadata),
 * and the one real content library - libmobi - is C/LGPL, which means an NDK
 * build and real licensing questions for very little payoff here. The format
 * itself is small and fully publicly documented (MobileRead's MOBI wiki
 * page), so this implements it directly: PDB container -> PalmDOC header ->
 * LZ77 decompression -> HTML text (Mobipocket books are literally embedded
 * HTML, so Jsoup - already a dependency for EPUB - strips it to plain text).
 *
 * Deliberately NOT supported, with a clear error instead of garbled output:
 * - DRM'd files (encryption type != 0) - can never be supported, same as
 *   Kindle AZW/KFX.
 * - HUFF/CDIC-compressed files (compression type 17480) - an older, more
 *   complex Huffman scheme mostly seen in DRM'd/international titles; a
 *   real gap, not a fake one.
 * - True KF8-only files with no MOBI7 fallback rendition.
 */
class MobiParser : FileDocumentParser {

    private class UnsupportedMobiException(message: String) : Exception(message)

    override suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not open $displayName")
            try {
                parseBytes(bytes, displayName)
            } catch (e: UnsupportedMobiException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Could not parse $displayName as MOBI: ${e.message}", e)
            }
        }

    private fun parseBytes(bytes: ByteArray, displayName: String): ParsedDocument {
        val recordCount = readUInt16BE(bytes, 76)
        val recordOffsets = IntArray(recordCount) { i -> readUInt32BE(bytes, 78 + i * 8).toInt() }

        fun recordRange(index: Int): ByteArray {
            val start = recordOffsets[index]
            val end = if (index + 1 < recordCount) recordOffsets[index + 1] else bytes.size
            return bytes.copyOfRange(start, end)
        }

        val record0 = recordRange(0)
        val compression = readUInt16BE(record0, 0)
        val textRecordCount = readUInt16BE(record0, 8)
        val encryptionType = readUInt16BE(record0, 12)

        if (encryptionType != 0) {
            throw UnsupportedMobiException(
                "This file has Kindle DRM and can never be supported (same as AZW/KFX).",
            )
        }
        if (compression != 1 && compression != 2) {
            throw UnsupportedMobiException(
                "This file uses an older Huffman/CDIC compression scheme that isn't supported yet.",
            )
        }

        val hasMobiHeader = record0.size >= 32 &&
            String(record0, 16, 4, Charsets.US_ASCII) == "MOBI"
        val textEncoding = if (hasMobiHeader) readUInt32BE(record0, 28).toInt() else 1252
        val charset = if (textEncoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val extraFlags = if (hasMobiHeader && record0.size >= 246) readUInt32BE(record0, 242).toInt() else 0

        val textBytes = java.io.ByteArrayOutputStream()
        for (i in 1..textRecordCount) {
            if (i >= recordCount) break
            var raw = recordRange(i)
            if (extraFlags != 0) raw = trimTrailingExtra(raw, extraFlags)
            val decoded = if (compression == 2) decompressPalmDoc(raw) else raw
            textBytes.write(decoded)
        }

        val html = textBytes.toByteArray().toString(charset)
        val (plainText, chapters) = Jsoup.parse(html).let { doc ->
            doc.select("script, style").remove()
            blockTextWithChapters(doc) // paragraph breaks + heading-derived chapters
        }

        val title = record0Title(bytes).ifBlank { displayName.substringBeforeLast('.') }
        return ParsedDocument(title = title, text = plainText, chapters = chapters)
    }

    /** The PDB header's 32-byte database name, null-padded - almost always the book title. */
    private fun record0Title(bytes: ByteArray): String {
        val nameBytes = bytes.copyOfRange(0, minOf(32, bytes.size))
        val nullIndex = nameBytes.indexOf(0.toByte()).let { if (it < 0) nameBytes.size else it }
        return String(nameBytes, 0, nullIndex, Charsets.US_ASCII).trim()
    }

    /**
     * Standard PalmDOC LZ77-style decompression (compression type 2). Each
     * byte in the compressed stream is one of: a literal run marker, a raw
     * ASCII byte, a length/distance back-reference, or a space+char pair -
     * this exact scheme is documented on the MobileRead MOBI wiki page and
     * used by essentially every open-source PalmDOC/MOBI reader.
     */
    private fun decompressPalmDoc(input: ByteArray): ByteArray {
        // Built as a growable list (not ByteArrayOutputStream) because back-references
        // below need direct indexed reads into bytes this same call already produced,
        // including ones written earlier in the very same back-reference (overlapping
        // copies are normal in LZ77 and must see the freshly-written bytes).
        val out = ArrayList<Byte>(input.size * 3)
        var i = 0
        while (i < input.size) {
            val c = input[i].toInt() and 0xFF
            i++
            when {
                c == 0x00 -> out.add(c.toByte())
                c in 0x01..0x08 -> {
                    repeat(c) {
                        if (i < input.size) out.add(input[i])
                        i++
                    }
                }
                c in 0x09..0x7F -> out.add(c.toByte())
                c in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val c2 = input[i].toInt() and 0xFF
                    i++
                    val combined = ((c and 0x3F) shl 8) or c2
                    val distance = combined shr 3
                    val length = (combined and 0x07) + 3
                    var start = out.size - distance
                    repeat(length) {
                        out.add(if (start in out.indices) out[start] else 0x20.toByte())
                        start++
                    }
                }
                else -> { // 0xC0..0xFF
                    out.add(0x20.toByte()) // space
                    out.add((c xor 0x80).toByte())
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Strips MOBI's optional trailing per-record "extra data" (multibyte
     * continuation / indexing info appended after the compressed text,
     * before decompression) so it doesn't leak into the extracted prose.
     */
    private fun trimTrailingExtra(record: ByteArray, extraFlags: Int): ByteArray {
        var data = record
        var flags = extraFlags shr 1
        while (flags != 0) {
            if (flags and 1 != 0) data = trimBackwardSizedBlock(data)
            flags = flags shr 1
        }
        if (extraFlags and 1 != 0 && data.isNotEmpty()) {
            val numExtra = (data.last().toInt() and 0x03) + 1
            if (numExtra <= data.size) data = data.copyOf(data.size - numExtra)
        }
        return data
    }

    private fun trimBackwardSizedBlock(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        var pos = data.size
        var size = 0
        var shift = 0
        while (pos > 0) {
            pos--
            val v = data[pos].toInt() and 0xFF
            size = size or ((v and 0x7F) shl shift)
            if (v and 0x80 != 0) break
            shift += 7
        }
        val newSize = (data.size - size).coerceAtLeast(0)
        return data.copyOf(newSize)
    }

    private fun readUInt16BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }
}
