package com.codenzi.snapnote

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// ADIM 1: Versiyon numarasını 2'ye yükseltin.
@Database(entities = [Note::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null

        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    "note_database"
                )
                    // ADIM 2: Bu satırı kaldırın. Artık veri kaybı yaşanmayacak.
                    // .fallbackToDestructiveMigration()

                    // ADIM 3: Migration kuralını ekleyin.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Veritabanını versiyon 1'den 2'ye nasıl taşıyacağımızı tanımlar.
         * Şimdilik veritabanı yapısında bir değişiklik yapmadığımız için içi boş kalacak.
         * Bu, Room'a "Versiyonu güncelle ama mevcut verileri silme" demektir.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Gelecekte tabloya yeni bir sütun eklemek isterseniz,
                // SQL komutlarınızı buraya yazacaksınız. Örneğin:
                // database.execSQL("ALTER TABLE notes ADD COLUMN new_column_name TEXT")
            }
        }
    }
}