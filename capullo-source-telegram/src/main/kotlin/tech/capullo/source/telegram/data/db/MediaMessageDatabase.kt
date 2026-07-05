package tech.capullo.source.telegram.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Fresh at **version 1** - this is a new library with no install base, so Telecloud's v5 schema and
 * its four migrations (plus the app-only `audio_analysis` table) are intentionally NOT carried over.
 * Just the one `media_messages` table the source needs.
 */
@Database(
    entities = [MediaMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MediaMessageDatabase : RoomDatabase() {
    abstract fun mediaMessageDao(): MediaMessageDao

    companion object {
        /** Convenience builder; the app may equally wire this through its own DI. */
        fun create(context: Context): MediaMessageDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MediaMessageDatabase::class.java,
                "capullo_telegram.db",
            ).build()
    }
}
