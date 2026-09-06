package com.comfort.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Added the downloaded_files table (tracks which filenames have already been moved into the
// gallery per download, so gallery-dl re-announcing an already-handled file on resume doesn't
// get double-counted or double-saved).
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS downloaded_files (" +
                "downloadId TEXT NOT NULL, filename TEXT NOT NULL, " +
                "PRIMARY KEY(downloadId, filename))"
        )
    }
}

// Added expectedBytes (a file's known-upfront total size, from yt-dlp) and liveBytes (bytes
// downloaded so far into the file currently in flight) — both drive the byte-accurate progress
// bar/speed for single-item video downloads instead of the old all-or-nothing item-count jump.
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN expectedBytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN liveBytes INTEGER NOT NULL DEFAULT 0")
    }
}

// Added videoQuality (a VideoQuality enum name) — a per-download quality choice the share-sheet
// picker can now set, overriding the global Settings default for just that one download.
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN videoQuality TEXT")
    }
}

// Adds the two indices DownloadEntity now declares (see its own doc comment on why) — CREATE
// INDEX, not ALTER TABLE, and named to match Room's own default index-naming convention
// (index_<table>_<col1>_<col2>...) exactly, so Room's schema validation at startup sees the
// resulting table matching what the annotated entity expects instead of flagging a mismatch.
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_status_dateAdded` ON `downloads` (`status`, `dateAdded`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_status_queueOrder_dateAdded` ON `downloads` (`status`, `queueOrder`, `dateAdded`)")
    }
}

// Added clipRange ("start-end" timestamps, e.g. "00:10-01:30") — an optional per-download trim
// range set on the Home screen's paste-a-link field before starting a yt-dlp-routed download.
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN clipRange TEXT")
    }
}

// Added the rest of the download preview sheet's per-download overrides alongside the clipRange
// column above — extra yt-dlp commands, output container, filename template and the save-thumbnail
// flag. All nullable: null means "use the global Settings value at download time", which is what
// every download did before the sheet existed.
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN extraCommands TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN outputFormat TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN filenameTemplate TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN saveThumbnail INTEGER")
    }
}

@Database(entities = [DownloadEntity::class, DownloadedFileRecord::class], version = 12, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var Instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "gallerydl_database")
                    .addMigrations(
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                        MIGRATION_10_11, MIGRATION_11_12,
                    )
                    // Only a safety net for a schema bump nobody wrote an explicit migration
                    // for — every version change from here on should get a real Migration
                    // above instead, so this never actually triggers and wipes the user's
                    // download history/queue again the way the 6->7 destructive fallback did.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { Instance = it }
            }
        }
    }
}
