package com.flashcardreader.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SourceType { TXT, EPUB, PDF, MOBI, URL }

/**
 * An imported piece of reading content - a book file the user uploaded, or an
 * article ingested from a URL. Its parsed plain text is cached on disk (see
 * [textFilePath]) so re-opening a book doesn't re-parse the original file.
 */
@Entity(tableName = "sources")
data class Source(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: SourceType,
    /** Original content URI (file picker) or the source URL. Kept for reference/re-import. */
    val originUri: String,
    /** Path to the extracted plain-text cache used by the reader + term scanner. */
    val textFilePath: String,
    val addedAt: Long,
    /** Character offset the user last read up to, for resume-reading. */
    val lastPositionChar: Int = 0,
)
