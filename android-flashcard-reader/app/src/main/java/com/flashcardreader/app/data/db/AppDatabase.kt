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
    // Exported so SchemaGuardTest can compare what Room expects against MigrationSql.
    exportSchema = true,
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

        /**
         * Room needs a Migration object per step; the SQL itself lives in [MigrationSql] so a JVM
         * test can read it. Built from the map rather than written out one by one, so adding a
         * migration means adding its SQL in one place and nothing else.
         */
        private val migrations: Array<Migration> =
            MigrationSql.BY_FROM_VERSION.entries.sortedBy { it.key }.map { (from, statements) ->
                object : Migration(from, from + 1) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        statements.forEach(db::execSQL)
                    }
                }
            }.toTypedArray()

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcard-reader.db",
                ).addMigrations(*migrations).build().also { instance = it }
            }
    }
}
