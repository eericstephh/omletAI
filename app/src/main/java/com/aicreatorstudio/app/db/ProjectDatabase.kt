package com.aicreatorstudio.app.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [VideoProjectEntity::class, VideoClipEntity::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        /**
         * v4 -> v5 (project restoration): adds the per-project editor
         * state columns (canvas preset/background, captions JSON,
         * media-overlay JSON, last playhead position). Purely additive
         * `ALTER TABLE ... ADD COLUMN` with defaults, so existing rows
         * migrate in place and no project data is lost.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN canvasPreset TEXT NOT NULL DEFAULT 'WIDE_16_9'")
                db.execSQL("ALTER TABLE projects ADD COLUMN canvasBackground TEXT NOT NULL DEFAULT 'BLACK'")
                db.execSQL("ALTER TABLE projects ADD COLUMN textOverlaysJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE projects ADD COLUMN mediaOverlaysJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 -> v6 (AI tools persistence): adds a single JSON column
         * holding the per-clip AI tool settings (matting, autoFrame,
         * cropKeyframes, voiceClean). Stored on the project row so it
         * round-trips with the rest of the editor state and survives
         * save / reopen — matching how text and media overlays are
         * persisted. Defaults to `"{}"` so legacy rows load unchanged.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN aiToolsJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /**
         * v6 -> v7 (synchronized video-original audio timeline, PART 13-19
         * of the brief): adds a single JSON column holding the
         * per-project [com.aicreatorstudio.app.model.AudioClip] list.
         * Default is `"[]"` so legacy rows load with no audio track
         * (the editor still works; the user simply has no
         * video-original audio paired to existing video clips until
         * they re-import or use a future "extract audio" action).
         * The new column is purely additive; no existing data is
         * migrated.
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN audioClipsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v7 -> v8 (video progress bar): adds a single JSON column
         * holding the project's progress-bar settings (enabled, edge,
         * height, fill/track colour, corner radius, margin).
         *
         * Default is `"{}"`, which the decoder reads as
         * `ProgressBarSettings.Disabled` — so an existing project opens
         * with the bar off and exports exactly as it did before, and no
         * data is rewritten. Purely additive; nothing is migrated.
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN progressBarSettingsJson TEXT NOT NULL DEFAULT '{}'")
            }
        }
    }
}