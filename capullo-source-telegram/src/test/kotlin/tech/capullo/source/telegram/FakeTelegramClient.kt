package tech.capullo.source.telegram

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import tech.capullo.source.telegram.data.db.MediaMessageDao
import tech.capullo.source.telegram.data.db.MediaMessageEntity
import tech.capullo.source.telegram.data.db.StationInfo
import tech.capullo.source.telegram.data.telegram.AuthState
import tech.capullo.source.telegram.data.telegram.HistoryPage
import tech.capullo.source.telegram.data.telegram.MessageReactionsInfo
import tech.capullo.source.telegram.data.telegram.TelegramChat
import tech.capullo.source.telegram.data.telegram.TelegramClient
import tech.capullo.source.telegram.data.telegram.TelegramException
import tech.capullo.source.telegram.data.telegram.TelegramMessage
import java.io.File

/**
 * A [TelegramClient] whose `downloadFile` simulates a real TDLib download: it *suspends* (a virtual
 * `delay`), reports progress, writes an actual file to disk (so [DownloadManager]'s
 * `File(path).exists()` cache checks pass), and records what it downloaded - the behaviours the
 * radiobrowser type-level harness could not exercise. Designated ids throw "not found" to drive the
 * deleted-message skip path.
 */
class FakeTelegramClient(
    private val downloadDir: File,
    private val downloadDelayMs: Long = 100,
) : TelegramClient {

    override val authState = MutableStateFlow<AuthState>(AuthState.Ready)
    override val newAudioMessages = MutableSharedFlow<TelegramMessage>()
    override val deletedMessages = MutableSharedFlow<Pair<Long, List<Long>>>()

    /** messageId → how many times a *network* download actually ran (cache hits don't count). */
    val downloadCounts = LinkedHashMap<Long, Int>()

    /** messageIds, in the order downloads *started* - for the lookahead-ordering assertions. */
    val downloadOrder = mutableListOf<Long>()

    /** messageIds that should fail as "deleted from chat" (TDLib "not found"). */
    val notFoundIds = mutableSetOf<Long>()

    override suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit): String {
        downloadOrder += messageId
        downloadCounts[messageId] = (downloadCounts[messageId] ?: 0) + 1
        onProgress(0f)
        delay(downloadDelayMs) // suspends like a real network fetch
        if (messageId in notFoundIds) throw TelegramException(404, "message not found")
        onProgress(1f)
        val f = File(downloadDir, "$messageId.mp3").apply { writeText("audio-bytes-$messageId") }
        return f.absolutePath
    }

    // --- unused by the contract-validation path; inert ---
    override fun setupParameters() {}
    override suspend fun setPhoneNumber(phone: String) {}
    override suspend fun checkCode(code: String) {}
    override suspend fun checkPassword(password: String) {}
    override suspend fun getChats(limit: Int): List<TelegramChat> = emptyList()
    override suspend fun getMessageReactions(chatId: Long, messageId: Long): String? = null
    override suspend fun getReactionsInfo(chatId: Long, messageId: Long): MessageReactionsInfo =
        MessageReactionsInfo(null, null, false, emptyList(), emptyList())
    override suspend fun setOwnReaction(chatId: Long, messageId: Long, emoji: String?) {}
    override suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage =
        HistoryPage(emptyList(), 0)
    override suspend fun getChatDocumentHistory(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage =
        HistoryPage(emptyList(), 0)
    override fun close() {}
}

/** In-memory [MediaMessageDao]: enough of the surface that [DownloadManager] + [TelegramRepository] touch. */
class FakeMediaMessageDao : MediaMessageDao {
    private val rows = LinkedHashMap<Long, MediaMessageEntity>()

    fun seed(entities: List<MediaMessageEntity>) { entities.forEach { rows[it.messageId] = it } }

    override suspend fun insertAll(messages: List<MediaMessageEntity>) {
        messages.forEach { rows.putIfAbsent(it.messageId, it) }
    }
    override suspend fun getLocalPath(messageId: Long): String? = rows[messageId]?.localPath
    override suspend fun updateLocalPath(messageId: Long, path: String?) {
        rows[messageId]?.let { rows[messageId] = it.copy(localPath = path) }
    }
    override suspend fun exists(messageId: Long): Int = if (rows.containsKey(messageId)) 1 else 0
    override suspend fun getByIds(ids: List<Long>): List<MediaMessageEntity> = ids.mapNotNull { rows[it] }
    override suspend fun deleteByIds(ids: List<Long>) { ids.forEach { rows.remove(it) } }
    override suspend fun getAllLocalPaths(): List<String> = rows.values.mapNotNull { it.localPath }
    override suspend fun getLocalPathsForChat(chatId: Long): List<String> =
        rows.values.filter { it.chatId == chatId }.mapNotNull { it.localPath }
    override suspend fun getMessageIdsForChat(chatId: Long): List<Long> =
        rows.values.filter { it.chatId == chatId }.map { it.messageId }
    override suspend fun deleteByChat(chatId: Long) { rows.values.filter { it.chatId == chatId }.forEach { rows.remove(it.messageId) } }
    override suspend fun getPlaylist(chatId: Long): List<MediaMessageEntity> =
        rows.values.filter { it.chatId == chatId && it.station != null }
    override suspend fun getPlaylistNewestFirst(chatId: Long): List<MediaMessageEntity> =
        getPlaylist(chatId).sortedByDescending { it.messageId }
    override suspend fun getLocalTracks(chatId: Long): List<MediaMessageEntity> =
        getPlaylist(chatId).filter { it.localPath != null }
    override suspend fun getLatestMessageId(chatId: Long): Long? =
        rows.values.filter { it.chatId == chatId }.maxOfOrNull { it.messageId }
    override suspend fun getAnyForChat(chatId: Long): MediaMessageEntity? =
        rows.values.firstOrNull { it.chatId == chatId && it.station != null }
    override suspend fun getTrackCount(chatId: Long): Int = getPlaylist(chatId).size
    override suspend fun getTotalSize(chatId: Long): Long? = getPlaylist(chatId).sumOf { it.fileSize ?: 0 }
    override suspend fun updateReactions(messageId: Long, reactions: String?) {}
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun getStations(): List<StationInfo> =
        rows.values.filter { it.station != null }.map { StationInfo(it.chatId, it.station!!) }.distinct()
}
