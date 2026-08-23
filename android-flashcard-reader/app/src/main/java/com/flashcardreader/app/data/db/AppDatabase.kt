package com.flashcardreader.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flashcardreader.app.data.db.dao.OccurrenceDao
import com.flashcardreader.app.data.db.dao.ReadingCheckDao
import com.flashcardreader.app.data.db.dao.ReviewLogDao
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.dao.TermDao
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Occurrence
import com.flashcardreader.app.data.db.entities.CardKind
import com.flashcardreader.app.data.db.entities.ReadingCheckCard
import com.flashcardreader.app.data.db.entities.ReviewContext
import com.flashcardreader.app.data.db.entities.ReviewLog
import com.flashcardreader.app.data.db.entities.Source
import com.flashcardreader.app.data.db.entities.SourceType
import com.flashcardreader.app.data.db.entities.Term

class Converters {
    @TypeConverter
    fun cardStateToString(value: CardState): String = value.name

    @TypeConverter
    fun stringToCardState(value: String): CardState = CardState.valueOf(value)

    @TypeConverter
    fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType = SourceType.valueOf(value)

    @TypeConverter
    fun cardKindToString(value: CardKind): String = value.name

    @TypeConverter
    fun stringToCardKind(value: String): CardKind = CardKind.valueOf(value)

    @TypeConverter
    fun reviewContextToString(value: ReviewContext): String = value.name

    // Tolerant on the way back: a row written by a newer build than this one should not crash the
    // reader, and an unrecognised context is exactly as informative as no context.
    @TypeConverter
    fun stringToReviewContext(value: String): ReviewContext =
        runCatching { ReviewContext.valueOf(value) }.getOrDefault(ReviewContext.UNKNOWN)
}

@Database(
    entities = [
        Term::class,
        Source::class,
        Occurrence::class,
        ReadingCheckCard::class,
        ReviewLog::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
    abstract fun sourceDao(): SourceDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun readingCheckDao(): ReadingCheckDao
    abstract fun reviewLogDao(): ReviewLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds the curiosity flag and self-reference note columns to terms. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE terms ADD COLUMN curious INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE terms ADD COLUMN selfNote TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 adds the high-confidence-miss (hypercorrection) flag. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE terms ADD COLUMN hyperMiss INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 adds comprehension questions written from passages you actually read. A new table
         * rather than new columns: unlike the ALTER TABLE migrations above, this touches nothing
         * that already exists, so an upgrade cannot disturb a single saved flashcard.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
            }
        }

        /**
         * v5 starts keeping a review history, so the scheduler can eventually be fitted to how this
         * particular person forgets rather than to FSRS's published averages. Another CREATE TABLE:
         * nothing existing is touched, and no saved card can be disturbed by the upgrade.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                )
                // Every read of this table is "all reviews for one card", so it is worth an index
                // from the start rather than after it gets slow.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_review_logs_card ON review_logs (cardId, cardKind)",
                )
            }
        }

        /**
         * v6 records *where* each answer was given, so understanding something can be told apart
         * from retaining it - see ReviewContext.
         *
         * The default is written here and declared on the entity with the same literal. Room
         * compares the two after every upgrade, and a mismatch is not a warning: it refuses to
         * open the database, which is precisely how v5 crashed the app on launch. Existing rows
         * predate the distinction and honestly say so rather than guessing a context for them.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE review_logs ADD COLUMN context TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
            }
        }

        /**
         * v7 gives a word its wrong answers, so a card can be answered by tapping one of four
         * definitions rather than revealed and self-graded.
         *
         * The default is written here and declared on the entity with `@ColumnInfo(defaultValue)`
         * using the identical literal. Room compares them after every upgrade and refuses to open
         * the database on any difference - which is how v5 crashed the app on launch. Cards that
         * predate this have no wrong answers, and an empty column is exactly the signal the reader
         * uses to fall back to the older flow.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE terms ADD COLUMN distractors TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcard-reader.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
            }
    }
}
