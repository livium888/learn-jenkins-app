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
import com.flashcardreader.app.data.db.dao.SourceDao
import com.flashcardreader.app.data.db.dao.TermDao
import com.flashcardreader.app.data.db.entities.CardState
import com.flashcardreader.app.data.db.entities.Occurrence
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
    entities = [Term::class, Source::class, Occurrence::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun termDao(): TermDao
    abstract fun sourceDao(): SourceDao
    abstract fun occurrenceDao(): OccurrenceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds the curiosity flag and self-reference note columns to terms. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE terms ADD COLUMN curious INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE terms ADD COLUMN selfNote TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flashcard-reader.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
