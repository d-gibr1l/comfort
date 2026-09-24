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

// Adds the duplicate_attempts table — see DuplicateAttempt's own doc comment for what it tracks
// and why it's a separate table rather than another DownloadStatus value on the main downloads
// table (a skipped duplicate never actually enters the download pipeline at all, so it isn't a
// lifecycle state of a real download row the way RUNNING/QUEUED/etc. are).
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS duplicate_attempts (" +
                "id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL, " +
                "thumbnailPath TEXT, originalDownloadId TEXT NOT NULL, dateAdded INTEGER NOT NULL)"
        )
    }
}

// Added erroredAt (when a download most recently entered ERRORED) — getQueueFlow now sorts the
// errored group by this, newest first, instead of by queueOrder/dateAdded (when it was *added*,
// not when it actually failed).
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN erroredAt INTEGER")
    }
}

// Adds the (status, erroredAt) index DownloadEntity.kt now declares — see its own doc comment on
// why. Should have shipped in the same migration as the erroredAt column itself (MIGRATION_13_14
// above); a separate version bump is the only way to add it after the fact without breaking
// anyone already on schema 14.
private val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_status_erroredAt` ON `downloads` (`status`, `erroredAt`)")
    }
}

// Adds mediaUri (see DownloadEntity's own doc comment on it) — needed once thumbnailPath stopped
// being a dual-purpose "display image and open/play target" Uri for audio downloads specifically.
private val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN mediaUri TEXT")
    }
}

// Adds isAudio (set once the final saved file's extension is a known audio format — see
// DownloadWorker's AUDIO_EXTENSIONS check) plus artist/album/track (real metadata captured from
// yt-dlp's info_dict or Spotify's own scraped metadata when available — see yt_dlp_wrapper.py's
// progress_hook, spotify_wrapper.py, and DownloadEntity's own doc comments on each). All
// nullable/defaulted so gallery-dl and non-audio yt-dlp downloads are simply never touched.
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN isAudio INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN artist TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN album TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN track TEXT")
    }
}

// Adds overrideTitle/overrideArtist — the song preview sheet's own editable title/artist fields
// (SongPreviewCard), persisted per-download the same way filenameTemplate/saveThumbnail already
// are, so a retry/resume re-applies the same edit instead of silently reverting to the source's
// raw values.
private val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN overrideTitle TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN overrideArtist TEXT")
    }
}

// Adds downloadingAudioTrack — whether the file currently transferring is a video+audio merge's
// separate audio track (yt_dlp_wrapper.py's "[phase]" signal), so the queue card can say so.
// Introduced with the schema bump to 19 (commit c94a34b) but shipped without a migration, which
// left 18 -> 19 to fallbackToDestructiveMigration() — wiping history/queue on upgrade. Same
// NOT NULL DEFAULT 0 shape as isAudio above, matching the entity's @ColumnInfo(defaultValue = "0").
private val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN downloadingAudioTrack INTEGER NOT NULL DEFAULT 0")
    }
}

// Adds formatTags — the queue card's "1080p | MP4"-style pills (yt_dlp_wrapper.py's "[format]"
// signal). Introduced with the schema bump to 20 (commit ecb95b4), likewise without a migration
// until now. Nullable, no default — same shape as overrideTitle/overrideArtist above.
private val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN formatTags TEXT")
    }
}

// Adds errorDetails — each engine's own error for a failed download (DownloadEntity.errorDetails).
private val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN errorDetails TEXT")
    }
}

@Database(
    entities = [DownloadEntity::class, DownloadedFileRecord::class, DuplicateAttempt::class],
    version = 21,
    exportSchema = false,
)
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
                        MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                        MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
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
