package tech.capullo.source.telegram.data.playlist

import tech.capullo.source.telegram.data.db.MediaMessageDao
import tech.capullo.source.telegram.data.db.MediaMessageEntity

/** Reads playlists off the track index. DI-free - the app constructs it with the DAO. */
class PlaylistRepository(private val dao: MediaMessageDao) {
    suspend fun loadShuffledPlaylist(chatId: Long): List<MediaMessageEntity> =
        dao.getPlaylist(chatId).shuffled()

    suspend fun loadPlaylist(chatId: Long): List<MediaMessageEntity> =
        dao.getPlaylistNewestFirst(chatId)

    suspend fun loadLocalPlaylist(chatId: Long): List<MediaMessageEntity> =
        dao.getLocalTracks(chatId)

    suspend fun getTotalSize(chatId: Long): Long = dao.getTotalSize(chatId) ?: 0L

    suspend fun exists(messageId: Long): Boolean = dao.exists(messageId) > 0

    suspend fun getStations() = dao.getStations()
}
