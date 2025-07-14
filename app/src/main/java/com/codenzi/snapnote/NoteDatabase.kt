package com.codenzi.snapnote

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// KRİTİK DÜZELTME: Veritabanı versiyonu 8'e yükseltildi.
@Database(entities = [Note::class], version = 8, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN showOnWidget INTEGER NOT NULL DEFAULT 0")
            }
        }

        // KRİTİK DÜZELTME: Yeni versiyon için migrasyon kodu eklendi.
        // Bu kod, 'notes' tablosuna yeni indeksi ekler.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Notlar tablosundaki isDeleted ve showOnWidget sütunları için bir indeks oluştur.
                // Bu, bu sütunları kullanan sorguları önemli ölçüde hızlandırır.
                db.execSQL("CREATE INDEX `index_notes_isDeleted_showOnWidget` ON `notes` (`isDeleted`, `showOnWidget`)")
            }
        }

        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    "note_database"
                )
                    // KRİTİK DÜZELTME: Yeni migrasyon veritabanına eklendi.
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}