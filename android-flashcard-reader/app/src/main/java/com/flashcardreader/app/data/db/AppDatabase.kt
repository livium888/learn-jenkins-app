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
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.dao.TermDao
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Occurrence
import com.flashcardreader.app.data.db.entities.ReadingCheckCard
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
}

@Database(
    entities = [Term::class, Source::class, Occurrence::class, ReadingCheckCard::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
    abstract fun sourceDao(): SourceDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun readingCheckDao(): ReadingCheckDao

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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcard-reader.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
