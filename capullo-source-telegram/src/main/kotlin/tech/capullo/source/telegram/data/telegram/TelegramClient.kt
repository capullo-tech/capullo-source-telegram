package tech.capullo.source.telegram.data.telegram

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Swappable Telegram backend. [TdLibTelegramClient] is the one real impl (over the `:tdlib` module);
 * the rest of the source never imports TDLib types directly, which keeps the layer testable - the
 * contract-validation driver test drives [TelegramSource] through a `FakeTelegramClient`.
 */
interface TelegramClient {
    val authState: StateFlow<AuthState>

    // Audio messages pushed by Telegram in real time (any chat)
    val newAudioMessages: SharedFlow<TelegramMessage>

    // Permanently deleted messages pushed by Telegram: chatId to messageIds
    val deletedMessages: SharedFlow<Pair<Long, List<Long>>>

    // Sends SetTdlibParameters using saved credentials; call after credentials are stored.
    fun setupParameters()
    suspend fun setPhoneNumber(phone: String)
    suspend fun checkCode(code: String)
    suspend fun checkPassword(password: String)
    suspend fun getChats(limit: Int): List<TelegramChat>
    suspend fun getMessageReactions(chatId: Long, messageId: Long): String?
    suspend fun getReactionsInfo(chatId: Long, messageId: Long): MessageReactionsInfo
    // emoji = null clears the current user's reaction
    suspend fun setOwnReaction(chatId: Long, messageId: Long, emoji: String?)
    // Audio (music) messages
    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage
    // Documents whose mime/extension is audio (files sent "as file")
    suspend fun getChatDocumentHistory(chatId: Long, fromMessageId: Long, limit: Int): HistoryPage
    suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit = {}): String
    // Downloads a chat avatar by its file id (TelegramChat.photoFileId) and returns the local file
    // path, or null if it could not be materialized. A chat-photo file ref is stable, so unlike
    // downloadFile there is no GetMessage re-fetch. Use for the crisp avatar over the minithumbnail.
    suspend fun downloadChatPhoto(fileId: Int): String?
    fun close()
}

// One page of chat history; nextFromMessageId is 0 when there are no more results.
// messages may be empty while more history remains (page had no audio in it).
data class HistoryPage(
    val messages: List<TelegramMessage>,
    val nextFromMessageId: Long,
)

data class ReactorEntry(
    val emoji: String,
    val name: String,
    val isSelf: Boolean,
)

data class MessageReactionsInfo(
    val summary: String?,          // concatenated emoji, same format as MediaMessageEntity.reactions
    val ownEmoji: String?,         // reaction chosen by the current user, if any
    val canListReactors: Boolean,  // false in channels (anonymous reactions)
    val reactors: List<ReactorEntry>,
    val available: List<String>,   // emoji the current user may react with
)

sealed class AuthState {
    data object Unknown : AuthState()
    data object WaitParameters : AuthState()
    data object WaitPhone : AuthState()
    data object WaitCode : AuthState()
    data object WaitPassword : AuthState()
    data object Ready : AuthState()
    data class Error(val message: String) : AuthState()
}

data class TelegramChat(
    val id: Long,
    val title: String,
    val type: ChatType,
    // Chat avatar as inline minithumbnail JPEG bytes (TDLib ChatPhotoInfo.minithumbnail) - a tiny
    // blurred preview that ships with the chat, so no DownloadFile round-trip is needed for a small
    // leading thumbnail. Null when the chat has no photo.
    val photo: ByteArray? = null,
    // File id of the full-resolution "small" chat avatar (TDLib ChatPhotoInfo.small). Pass to
    // [TelegramClient.downloadChatPhoto] to fetch a crisp ~160px avatar; use [photo] as the instant
    // placeholder while it downloads. Null when the chat has no photo.
    val photoFileId: Int? = null,
)

data class TelegramMessage(
    val id: Long,
    val chatId: Long,
    val date: Long,
    val senderId: Long?,
    val senderUsername: String?,
    val caption: String?,
    val audio: TelegramAudio?,
    val reactions: String?,
)

data class TelegramAudio(
    val fileId: Int,
    val fileName: String?,
    val mimeType: String?,
    val duration: Int,
    val performer: String?,
    val title: String?,
    val fileSize: Long,
    val fileUniqueId: String,
)

enum class ChatType { GROUP, SUPERGROUP, CHANNEL, OTHER }

class TelegramException(val code: Int, override val message: String) : Exception(message)

/**
 * Telegram API credentials the app supplies - e.g. from `EncryptedSharedPreferences` - so this
 * source library stays free of any storage/DI opinion.
 *
 * [databaseEncryptionKey] is **required, not optional**: TDLib's local database holds the login
 * session (a stolen copy = logged-in account access) plus cached chat data, so it is encrypted at
 * rest by default here - there is no cleartext path. It is **not** a user-facing password: the app
 * generates it once (see [newDatabaseEncryptionKey]), keeps it in the Android Keystore /
 * `EncryptedSharedPreferences`, and passes the same bytes on every launch. Losing the key means the
 * local DB can't be opened and the user must re-authenticate, so persist it alongside the DB.
 */
interface TelegramCredentials {
    val apiId: Int
    val apiHash: String

    /** 32 random bytes, generated once and stored securely by the app. Transparent to the user. */
    val databaseEncryptionKey: ByteArray

    fun hasCredentials(): Boolean = apiId != 0 && apiHash.isNotEmpty()

    companion object {
        /** Generate a fresh 256-bit local-DB encryption key. Store it in Keystore/EncryptedSharedPreferences. */
        fun newDatabaseEncryptionKey(): ByteArray =
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    }
}
