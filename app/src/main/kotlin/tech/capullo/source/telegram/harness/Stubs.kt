package tech.capullo.source.telegram.harness

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
import tech.capullo.source.telegram.data.telegram.TelegramMessage
import tech.capullo.source.telegram.data.telegram.TelegramRepository

/**
 * Inert stubs so the harness can construct the real `TelegramSource` → `DownloadManager` →
 * `TelegramRepository` chain without connecting to Telegram. queue()/nowPlaying/loadStation never
 * touch the download path, so these are never actually invoked - they exist only to satisfy the
 * (deliberately non-optional) constructor wiring and prove it links.
 */
internal object InMemoryDao : MediaMessageDao {
    override suspend fun insertAll(messages: List<MediaMessageEntity>) {}
    override suspend fun getPlaylist(chatId: Long): List<MediaMessageEntity> = emptyList()
    override suspend fun getPlaylistNewestFirst(chatId: Long): List<MediaMessageEntity> = emptyList()
    override suspend fun getLocalTracks(chatId: Long): List<MediaMessageEntity> = emptyList()
    override suspend fun getLatestMessageId(chatId: Long): Long? = null
    override suspend fun exists(messageId: Long): Int = 0
    override suspend fun getAnyForChat(chatId: Long): MediaMessageEntity? = null
    override suspend fun getTrackCount(chatId: Long): Int = 0
    override suspend fun getTotalSize(chatId: Long): Long? = null
    override suspend fun getLocalPath(messageId: Long): String? = null
    override suspend fun updateLocalPath(messageId: Long, path: String?) {}
    override suspend fun updateReactions(messageId: Long, reactions: String?) {}
    override suspend fun getAllLocalPaths(): List<String> = emptyList()
    override suspend fun getByIds(ids: List<Long>): List<MediaMessageEntity> = emptyList()
    override suspend fun deleteByIds(ids: List<Long>) {}
    override suspend fun deleteAll() {}
    override suspend fun getStations(): List<StationInfo> = emptyList()
    override suspend fun getLocalPathsForChat(chatId: Long): List<String> = emptyList()
    override suspend fun getMessageIdsForChat(chatId: Long): List<Long> = emptyList()
    override suspend fun deleteByChat(chatId: Long) {}
}

private object NoopTelegramClient : TelegramClient {
    override val authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val newAudioMessages = MutableSharedFlow<TelegramMessage>()
    override val deletedMessages = MutableSharedFlow<Pair<Long, List<Long>>>()
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
    override suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit): String =
        error("harness never downloads")
    override fun close() {}
}

internal fun MainActivity.throwawayRepository(): TelegramRepository =
    TelegramRepository(NoopTelegramClient, InMemoryDao)
