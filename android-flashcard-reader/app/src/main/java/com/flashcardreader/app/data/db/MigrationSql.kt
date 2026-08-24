package com.flashcardreader.app.data.db

/**
 * Every schema migration, as plain SQL, in a file with no Android imports.
 *
 * It lives apart from [AppDatabase] so a JVM test can read it. Room migrations are otherwise
 * untestable here: schema comparison happens inside a real SQLite database on a real device, and
 * this project has no emulator in CI. That is not a theoretical gap - v5 declared an index in its
 * migration that the entity never declared, Room refused to open the database, and the app
 * crash-looped on launch for everyone who upgraded. A clean install never runs a migration, so
 * nothing in the build could have caught it.
 *
 * With the SQL as inspectable strings and `exportSchema = true` writing what Room actually expects,
 * SchemaGuardTest can compare the two on an ordinary JVM. See that test for what is enforced.
 */
object MigrationSql {

    /** v2 adds the curiosity flag and self-reference note columns to terms. */
    val SQL_1_2 = listOf(
        "ALTER TABLE terms ADD COLUMN curious INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE terms ADD COLUMN selfNote TEXT NOT NULL DEFAULT ''",
    )

    /** v3 adds the high-confidence-miss (hypercorrection) flag. */
    val SQL_2_3 = listOf(
        "ALTER TABLE terms ADD COLUMN hyperMiss INTEGER NOT NULL DEFAULT 0",
    )

    /**
     * v4 adds comprehension questions written from passages you actually read. A new table rather
     * than new columns: unlike the ALTER TABLE migrations above, this touches nothing that already
     * exists, so an upgrade cannot disturb a single saved flashcard.
     */
    val SQL_3_4 = listOf(
        """
        CREATE TABLE IF NOT EXISTS reading_checks (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            question TEXT NOT NULL,
            correctAnswer TEXT NOT NULL,
            distractors TEXT NOT NULL,
            evidence TEXT NOT NULL,
            sourceId INTEGER NOT NULL,
            charOffset INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            difficulty REAL NOT NULL,
            stability REAL NOT NULL,
            due INTEGER,
            lastReviewedAt INTEGER,
            reps INTEGER NOT NULL,
            lapses INTEGER NOT NULL,
            state TEXT NOT NULL,
            hyperMiss INTEGER NOT NULL
        )
        """.trimIndent(),
    )

    /**
     * v5 starts keeping a review history, so the scheduler can eventually be fitted to how this
     * particular person forgets rather than to FSRS's published averages. Another CREATE TABLE:
     * nothing existing is touched, and no saved card can be disturbed by the upgrade.
     *
     * The index here is the one that crashed the app. It is fine now because ReviewLog declares
     * `@Index(name = "index_review_logs_card", ...)` with exactly this name - and SchemaGuardTest
     * now fails the build if that ever stops being true.
     */
    val SQL_4_5 = listOf(
        """
        CREATE TABLE IF NOT EXISTS review_logs (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            cardId INTEGER NOT NULL,
            cardKind TEXT NOT NULL,
            rating INTEGER NOT NULL,
            elapsedDays REAL NOT NULL,
            stabilityBefore REAL NOT NULL,
            difficultyBefore REAL NOT NULL,
            wasFirstReview INTEGER NOT NULL,
            reviewedAt INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_review_logs_card ON review_logs (cardId, cardKind)",
    )

    /**
     * v6 records *where* each answer was given, so understanding something can be told apart from
     * retaining it - see ReviewContext. Existing rows predate the distinction and honestly say so
     * rather than having a context guessed for them.
     */
    val SQL_5_6 = listOf(
        "ALTER TABLE review_logs ADD COLUMN context TEXT NOT NULL DEFAULT 'UNKNOWN'",
    )

    /**
     * v7 gives a word its wrong answers, so a card can be answered by tapping one of four
     * definitions rather than revealed and self-graded. Cards that predate this have no wrong
     * answers, and the empty column is exactly the signal the reader uses to fall back.
     */
    val SQL_6_7 = listOf(
        "ALTER TABLE terms ADD COLUMN distractors TEXT NOT NULL DEFAULT ''",
    )

    /**
     * v8 adds the chapter pass: what a chapter claimed, and the order it claimed it in.
     *
     * A CREATE TABLE, so nothing that already exists is touched and no saved card can be disturbed
     * by the upgrade. The index name and the hyperMiss default are both written here and declared
     * identically on the entity - SchemaGuardTest fails the build if they ever drift, which is the
     * mistake that crash-looped v5.
     */
    val SQL_7_8 = listOf(
        """
        CREATE TABLE IF NOT EXISTS chapter_recalls (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            sourceId INTEGER NOT NULL,
            chapterIndex INTEGER NOT NULL,
            chapterTitle TEXT NOT NULL,
            startChar INTEGER NOT NULL,
            endChar INTEGER NOT NULL,
            propositions TEXT NOT NULL,
            hints TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            difficulty REAL NOT NULL,
            stability REAL NOT NULL,
            due INTEGER,
            lastReviewedAt INTEGER,
            reps INTEGER NOT NULL,
            lapses INTEGER NOT NULL,
            state TEXT NOT NULL,
            hyperMiss INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_chapter_recalls_source ON chapter_recalls (sourceId, chapterIndex)",
    )

    /** Every migration, keyed by the version it upgrades from. */
    val BY_FROM_VERSION: Map<Int, List<String>> = mapOf(
        1 to SQL_1_2,
        2 to SQL_2_3,
        3 to SQL_3_4,
        4 to SQL_4_5,
        5 to SQL_5_6,
        6 to SQL_6_7,
        7 to SQL_7_8,
    )

    /** The version [BY_FROM_VERSION] can carry a database up to. Must equal the @Database version. */
    val LATEST_VERSION = 8
}
