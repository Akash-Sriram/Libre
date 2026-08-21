package app.libre.db

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.libre.LibreTubeApp

object DatabaseHolder {
    private const val DATABASE_NAME = "LibreTubeDatabase"

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'localPlaylist' ADD COLUMN 'description' TEXT DEFAULT NULL")
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE 'playlistBookmark' ADD COLUMN 'videos' INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE 'subscriptionGroups' ADD COLUMN 'index' INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'downloadItem' ADD COLUMN 'language' TEXT DEFAULT NULL")
        }
    }

    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE 'watchHistoryItem' ADD COLUMN 'isShort' INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE 'downloadChapters' (" +
                    "id INTEGER PRIMARY KEY NOT NULL, " +
                    "videoId TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "start INTEGER NOT NULL, " +
                    "thumbnailUrl TEXT NOT NULL" +
                    ")")
        }
    }

    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE 'downloadSponsorBlockSegment' (" +
                    "uuid TEXT PRIMARY KEY NOT NULL, " +
                    "videoId TEXT NOT NULL, " +
                    "actionType TEXT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "description TEXT, " +
                    "locked INTEGER NOT NULL, " +
                    "startTime REAL NOT NULL, " +
                    "endTime REAL NOT NULL, " +
                    "videoDuration REAL NOT NULL, " +
                    "votes INTEGER NOT NULL, " +
                    "CONSTRAINT parentDownload FOREIGN KEY (videoId) REFERENCES download (videoId) ON DELETE CASCADE" +
                    ")")
        }
    }

    private val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'download' ADD COLUMN 'uploaderUrl' TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE 'download' ADD COLUMN 'views' INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE 'download' ADD COLUMN 'likes' INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE 'download' ADD COLUMN 'dislikes' INTEGER NOT NULL DEFAULT -1")
        }
    }

    private val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add stream category cache columns to local playlist items.
            // Null = not yet scanned. Populated by the playlist scan feature or on first play.
            db.execSQL("ALTER TABLE 'localPlaylistItem' ADD COLUMN 'category' TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE 'localPlaylistItem' ADD COLUMN 'hasVideoStreams' INTEGER DEFAULT NULL")
        }
    }

    private val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `customInstance`")
        }
    }

    private val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'localPlaylistItem' ADD COLUMN 'albumName' TEXT DEFAULT NULL")
        }
    }

    private val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `localAudioMetadataCache` (
                    `videoId` TEXT NOT NULL PRIMARY KEY,
                    `title` TEXT NOT NULL,
                    `uploader` TEXT NOT NULL,
                    `thumbnailUrl` TEXT NOT NULL,
                    `duration` INTEGER NOT NULL,
                    `cachedAtMs` INTEGER NOT NULL
                )"""
            )
        }
    }

    private val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add localFilePath so the matched path survives app restarts (no re-scan needed)
            db.execSQL("ALTER TABLE `localAudioMetadataCache` ADD COLUMN `localFilePath` TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Recreate localPlaylistItem without legacy dead columns
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `localPlaylistItem_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `playlistId` INTEGER NOT NULL,
                    `videoId` TEXT NOT NULL,
                    `title` TEXT,
                    `uploader` TEXT,
                    `thumbnailUrl` TEXT,
                    `duration` INTEGER,
                    `albumName` TEXT
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO `localPlaylistItem_new` (`id`, `playlistId`, `videoId`, `title`, `uploader`, `thumbnailUrl`, `duration`, `albumName`)
                SELECT `id`, `playlistId`, `videoId`, `title`, `uploader`, `thumbnailUrl`, `duration`, `albumName`
                FROM `localPlaylistItem`
            """.trimIndent())
            db.execSQL("DROP TABLE `localPlaylistItem`")
            db.execSQL("ALTER TABLE `localPlaylistItem_new` RENAME TO `localPlaylistItem`")
            // Wipe accumulated watch history table to reclaim storage
            db.execSQL("DELETE FROM `watchHistoryItem`")
        }
    }

    private val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_LocalPlaylistItem_playlistId` ON `LocalPlaylistItem` (`playlistId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_LocalPlaylistItem_videoId` ON `LocalPlaylistItem` (`videoId`)")
        }
    }

    val Database by lazy {
        Room.databaseBuilder(LibreTubeApp.instance, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_17_18,
                MIGRATION_21_22,
                MIGRATION_22_23,
                MIGRATION_24_25,
                MIGRATION_25_26,
                MIGRATION_26_27,
                MIGRATION_27_28,
                MIGRATION_28_29,
                MIGRATION_29_30,
                MIGRATION_30_31
            )
            .fallbackToDestructiveMigration(false)
            .build()
    }
}
