package com.flashcardreader.app.data.parser

import android.content.Context
import android.net.Uri

/**
 * Not implemented yet. MOBI's PalmDOC/KF8 container can only be parsed for
 * files with no Kindle DRM - a real parser needs its own binary-format reader
 * (there is no reliable JVM library for it). Legitimately DRM-locked AZW/KFX
 * files can never be supported, DRM-free ones are the intended follow-up.
 * We surface a clear error instead of silently producing garbled text.
 */
class MobiParser : FileDocumentParser {
    override suspend fun parse(context: Context, uri: Uri, displayName: String): ParsedDocument {
        throw UnsupportedOperationException(
            "MOBI/AZW import isn't supported yet. If this file has Kindle DRM it never " +
                "can be; a DRM-free .mobi will be supported in a future update.",
        )
    }
}
