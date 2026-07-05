package tech.capullo.source.telegram.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.source.telegram.data.db.MediaMessageDao
import tech.capullo.source.telegram.data.telegram.TelegramException
import tech.capullo.source.telegram.data.telegram.TelegramRepository
import java.io.File

/**
 * On-demand TDLib download + on-disk LRU cache, the blocking half of the source seam.
 *
 * [ensureDownloaded] is what [tech.capullo.source.telegram.TelegramSource.mediaRequestFor] awaits:
 * in-memory map → DB `localPath` (survives restart) → network download. After each new download the
 * GB buffer is enforced (FIFO/LRU eviction by insertion order), never evicting the playing track.
 *
 * DI-free and trimmed vs. Telecloud: the app-specific `AudioAnalysisDao`/`AlbumArtFetcher`/spectrogram
 * coupling and the `SettingsRepository` dependency are gone; the on-disk ceiling is supplied as a
 * plain `bufferSizeGb` provider so the app owns the setting.
 */
class DownloadManager(
    private val repository: TelegramRepository,
    private val dao: MediaMessageDao,
    private val bufferSizeGb: () -> Float = { 1.0f },
) {
    private val downloadedFiles = LinkedHashMap<Long, String>()

    // messageId → 0f..1f for downloads currently in flight
    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, Float>> = _downloadProgress.asStateFlow()

    // The track currently playing - never evicted by enforceBuffer
    @Volatile var activeMessageId: Long? = null

    suspend fun ensureDownloaded(chatId: Long, messageId: Long): String? {
        downloadedFiles[messageId]?.let { path ->
            if (File(path).exists()) {
                touch(messageId, path)
                return path
            }
            downloadedFiles.remove(messageId)
        }
        dao.getLocalPath(messageId)?.let { path ->
            if (File(path).exists()) {
                downloadedFiles[messageId] = path
                return path
            }
            dao.updateLocalPath(messageId, null)
        }
        _downloadProgress.value = _downloadProgress.value + (messageId to 0f)
        val path = try {
            repository.downloadFile(chatId, messageId) { progress ->
                _downloadProgress.value = _downloadProgress.value + (messageId to progress)
            }
        } catch (e: TelegramException) {
            // Lazy deletion fallback: the message was deleted from the chat -
            // purge it so playback can skip it instead of stalling
            if (e.message.contains("not found", ignoreCase = true)) {
                runCatching { repository.removeTracks(chatId, listOf(messageId)) }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            _downloadProgress.value = _downloadProgress.value - messageId
        }
        if (path == null) return null

        downloadedFiles[messageId] = path
        dao.updateLocalPath(messageId, path)
        enforceBuffer(currentMessageId = messageId)
        return path
    }

    fun getCachedPath(messageId: Long): String? =
        downloadedFiles[messageId]?.takeIf { File(it).exists() }

    suspend fun isDownloaded(messageId: Long): Boolean =
        getCachedPath(messageId) != null ||
            dao.getLocalPath(messageId)?.let { File(it).exists() } == true

    suspend fun evict(messageId: Long) {
        val path = downloadedFiles.remove(messageId) ?: return
        File(path).delete()
        dao.updateLocalPath(messageId, null)
    }

    // Re-insert so recently accessed tracks move to the back of the eviction queue (LRU)
    private fun touch(messageId: Long, path: String) {
        downloadedFiles.remove(messageId)
        downloadedFiles[messageId] = path
    }

    // Library reset: deletes downloaded files and the track index - for one station (chatId) or
    // everything (null). The next station open re-syncs the full history.
    suspend fun wipeLibrary(chatId: Long? = null) {
        if (chatId == null) {
            dao.getAllLocalPaths().forEach { File(it).delete() }
            downloadedFiles.clear()
            dao.deleteAll()
        } else {
            dao.getLocalPathsForChat(chatId).forEach { File(it).delete() }
            dao.getMessageIdsForChat(chatId).forEach { id -> downloadedFiles.remove(id) }
            dao.deleteByChat(chatId)
        }
    }

    private suspend fun enforceBuffer(currentMessageId: Long) {
        val limitBytes = (bufferSizeGb() * 1024 * 1024 * 1024).toLong()
        while (totalDownloadedBytes() > limitBytes) {
            val oldest = downloadedFiles.keys
                .firstOrNull { it != currentMessageId && it != activeMessageId } ?: break
            evict(oldest)
        }
    }

    private fun totalDownloadedBytes(): Long =
        downloadedFiles.values.sumOf { path -> File(path).length() }
}
