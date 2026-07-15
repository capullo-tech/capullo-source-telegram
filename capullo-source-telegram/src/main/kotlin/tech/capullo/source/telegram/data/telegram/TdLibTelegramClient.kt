package tech.capullo.source.telegram.data.telegram

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Requires the :tdlib module (Java sources + native libs).
// Run scripts/setup_tdlib.sh once to populate it.
//
// DI-free (platform rule: source libs bind no DI framework). The app constructs this with an
// application Context and its own [TelegramCredentials] impl; there is no @Inject/@Singleton here.
class TdLibTelegramClient(
    private val context: Context,
    private val credentials: TelegramCredentials,
) : TelegramClient {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // TDLib network connection state (true == ConnectionStateReady). Downloads gate on this: right
    // after a FRESH login TDLib reaches AuthorizationStateReady while its file-download subsystem is
    // still settling (ConnectionStateUpdating - the post-login sync). A synchronous DownloadFile
    // issued in that window makes zero progress and the timeout-less send() hangs forever (the
    // player sits in BUFFERING; only an app restart clears it). awaitConnected() waits past that.
    private val _connected = MutableStateFlow(false)

    private val _newAudioMessages = MutableSharedFlow<TelegramMessage>(extraBufferCapacity = 16)
    override val newAudioMessages: SharedFlow<TelegramMessage> = _newAudioMessages.asSharedFlow()

    private val _deletedMessages = MutableSharedFlow<Pair<Long, List<Long>>>(extraBufferCapacity = 16)
    override val deletedMessages: SharedFlow<Pair<Long, List<Long>>> = _deletedMessages.asSharedFlow()

    // For async work spawned from TDLib's update callback (which must not block)
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: Client = Client.create(::handleUpdate, null, null)

    // fileId → progress callback, active only while a download is in flight
    private val progressCallbacks = ConcurrentHashMap<Int, (Float) -> Unit>()

    // senderId → username ("@name") or null when the user has none; avoids repeated GetUser calls
    private val usernameCache = ConcurrentHashMap<Long, String>()
    private val NO_USERNAME = ""

    private val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "aiff", "aif", "alac", "ape",
    )

    init {
        Client.execute(TdApi.SetLogVerbosityLevel(0))
    }

    override fun setupParameters() {
        if (!credentials.hasCredentials()) return
        val params = TdApi.SetTdlibParameters()
        params.databaseDirectory = context.filesDir.absolutePath + "/tdlib"
        params.filesDirectory = context.filesDir.absolutePath + "/tdlib/files"
        // Encrypt the local TDLib database (session + cached chats) at rest. The app-generated key
        // lives in the Keystore/EncryptedSharedPreferences; this is transparent to the user.
        params.databaseEncryptionKey = credentials.databaseEncryptionKey
        params.useFileDatabase = true
        params.useChatInfoDatabase = true
        params.useMessageDatabase = true
        params.useSecretChats = false
        params.apiId = credentials.apiId
        params.apiHash = credentials.apiHash
        params.systemLanguageCode = "en"
        params.deviceModel = Build.MODEL
        params.systemVersion = Build.VERSION.RELEASE
        params.applicationVersion = "0.1.0"
        client.send(params) {}
    }

    private fun handleUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateNewMessage) {
            val telegramMessage = update.message.toTelegramMessage() ?: return
            Log.d("TeleCloud", "UpdateNewMessage audio chat=${telegramMessage.chatId} id=${telegramMessage.id}")
            clientScope.launch {
                val withUsername = telegramMessage.senderId
                    ?.let { telegramMessage.copy(senderUsername = resolveUsername(it)) }
                    ?: telegramMessage
                _newAudioMessages.emit(withUsername)
            }
            return
        }
        if (update is TdApi.UpdateDeleteMessages) {
            // fromCache deletions are TDLib housekeeping, not real chat deletions
            if (update.isPermanent && !update.fromCache) {
                Log.d("TeleCloud", "UpdateDeleteMessages chat=${update.chatId} ids=${update.messageIds.size}")
                _deletedMessages.tryEmit(update.chatId to update.messageIds.toList())
            }
            return
        }
        if (update is TdApi.UpdateConnectionState) {
            val ready = update.state is TdApi.ConnectionStateReady
            _connected.value = ready
            Log.d("TeleCloud", "UpdateConnectionState ready=$ready (${update.state::class.simpleName})")
            return
        }
        if (update is TdApi.UpdateFile) {
            progressCallbacks[update.file.id]?.let { callback ->
                val expected = update.file.expectedSize.takeIf { it > 0 } ?: update.file.size
                if (expected > 0) {
                    callback((update.file.local.downloadedSize.toFloat() / expected).coerceIn(0f, 1f))
                }
            }
            return
        }
        if (update !is TdApi.UpdateAuthorizationState) return
        _authState.value = when (update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                // Auto-setup if credentials are already saved (subsequent launches)
                if (credentials.hasCredentials()) setupParameters()
                AuthState.WaitParameters
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword
            is TdApi.AuthorizationStateReady -> AuthState.Ready
            is TdApi.AuthorizationStateClosed -> AuthState.Unknown
            else -> AuthState.Unknown
        }
    }

    override suspend fun setPhoneNumber(phone: String) {
        send<TdApi.Ok>(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    override suspend fun checkCode(code: String) {
        send<TdApi.Ok>(TdApi.CheckAuthenticationCode(code))
    }

    override suspend fun checkPassword(password: String) {
        send<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun getMessageReactions(chatId: Long, messageId: Long): String? =
        runCatching { send<TdApi.Message>(TdApi.GetMessage(chatId, messageId)).extractReactions() }
            .getOrNull()

    override suspend fun getChats(limit: Int): List<TelegramChat> {
        val chats = send<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), limit))
        val result = mutableListOf<TelegramChat>()
        for (chatId in chats.chatIds) {
            runCatching { result.add(send<TdApi.Chat>(TdApi.GetChat(chatId)).toTelegramChat()) }
        }
        return result
    }

    override suspend fun getChatHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): HistoryPage = searchChat(chatId, fromMessageId, limit, TdApi.SearchMessagesFilterAudio())

    override suspend fun getChatDocumentHistory(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): HistoryPage = searchChat(chatId, fromMessageId, limit, TdApi.SearchMessagesFilterDocument())

    // SearchChatMessages fetches directly from the server, bypassing the
    // local-cache-only limitation of GetChatHistory. Non-audio results are
    // dropped by the converter (documents pass only with audio mime/extension).
    private suspend fun searchChat(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        filter: TdApi.SearchMessagesFilter,
    ): HistoryPage {
        val result = send<TdApi.FoundChatMessages>(
            TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                fromMessageId,
                0,
                limit,
                filter,
            ),
        )
        Log.d("TeleCloud", "search($filter) chat=$chatId from=$fromMessageId → ${result.messages.size} messages nextFrom=${result.nextFromMessageId}")
        val messages = result.messages.mapNotNull { it.toTelegramMessage() }
            .map { msg ->
                msg.senderId?.let { msg.copy(senderUsername = resolveUsername(it)) } ?: msg
            }
        return HistoryPage(messages, result.nextFromMessageId)
    }

    override suspend fun downloadFile(chatId: Long, messageId: Long, onProgress: (Float) -> Unit): String {
        // Don't issue a download until TDLib is actually connected - see [_connected]. Bounded so the
        // UI can never spin forever waiting to connect.
        awaitConnected()
        // Re-fetch the message to get a fresh file reference (Telegram refs can expire).
        val message = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
        val fileId = when (val c = message.content) {
            is TdApi.MessageAudio -> c.audio.audio.id
            is TdApi.MessageDocument -> c.document.document.id
            else -> throw TelegramException(0, "Message $messageId is not audio")
        }
        return downloadWithStallGuard(fileId, onProgress)
    }

    override suspend fun downloadChatPhoto(fileId: Int): String? =
        runCatching {
            // Synchronous DownloadFile completes when the file is on disk (no progress reporting
            // needed for a tiny avatar). A chat-photo file ref is stable, so no GetMessage re-fetch.
            // Same connection gate + stall guard as downloadFile so a first-run avatar fetch can't
            // leak a coroutine stuck forever in the timeout-less send().
            awaitConnected()
            downloadWithStallGuard(fileId) {}.takeIf { it.isNotEmpty() }
        }.getOrNull()

    // Waits for TDLib to reach ConnectionStateReady before returning, up to [AWAIT_CONNECTED_TIMEOUT_MS].
    // On timeout it proceeds anyway (logging a warning) - the download stall guard is the real backstop,
    // and connection state can legitimately flap; we never want to block a download forever on it.
    private suspend fun awaitConnected() {
        if (_connected.value) return
        val connected = withTimeoutOrNull(AWAIT_CONNECTED_TIMEOUT_MS) { _connected.first { it } }
        if (connected == null) {
            Log.w("TeleCloud", "awaitConnected timed out after ${AWAIT_CONNECTED_TIMEOUT_MS}ms; proceeding (stall guard backstops)")
        }
    }

    // Runs a synchronous DownloadFile with a *stall* watchdog: the synchronous send() only resumes
    // once the whole file is on disk, so a never-progressing download would suspend forever. We reset
    // a timer on every UpdateFile progress tick and abort with a TelegramException if no progress
    // arrives for [DOWNLOAD_STALL_TIMEOUT_MS] - turning the infinite BUFFERING hang into a surfaced
    // error the UI can recover from. This is a no-progress timeout, NOT a total-time budget, so a
    // legitimately slow large download on a poor connection is never falsely killed.
    private suspend fun downloadWithStallGuard(fileId: Int, onProgress: (Float) -> Unit): String {
        val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())
        // Reset the stall clock only on FORWARD progress - during the first-run hang TDLib can keep
        // emitting zero-progress UpdateFile events, which would otherwise perpetually feed the clock.
        val lastFraction = AtomicReference(0f)
        progressCallbacks[fileId] = { fraction ->
            if (fraction > lastFraction.get()) {
                lastFraction.set(fraction)
                lastProgressAt.set(SystemClock.elapsedRealtime())
            }
            onProgress(fraction)
        }
        try {
            return coroutineScope {
                val download = async {
                    send<TdApi.File>(TdApi.DownloadFile(fileId, 1, 0, 0, true)).local.path
                }
                val watchdog = launch {
                    while (isActive) {
                        delay(STALL_POLL_MS)
                        if (SystemClock.elapsedRealtime() - lastProgressAt.get() > DOWNLOAD_STALL_TIMEOUT_MS) {
                            Log.w("TeleCloud", "download of file $fileId stalled (no progress for ${DOWNLOAD_STALL_TIMEOUT_MS}ms); aborting")
                            download.cancel()
                            break
                        }
                    }
                }
                try {
                    download.await()
                } catch (e: CancellationException) {
                    // Rethrows if the *caller* cancelled us (e.g. playback abandoned); otherwise this
                    // is our watchdog firing, which we surface as a real error instead of a hang.
                    coroutineContext.ensureActive()
                    throw TelegramException(0, "Download of file $fileId stalled")
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            progressCallbacks.remove(fileId)
        }
    }

    override suspend fun getReactionsInfo(chatId: Long, messageId: Long): MessageReactionsInfo {
        val message = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
        val reactions = message.interactionInfo?.reactions
        val summary = message.extractReactions()
        val ownEmoji = reactions?.reactions
            ?.firstOrNull { it.isChosen }
            ?.let { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }

        val available = runCatching {
            send<TdApi.AvailableReactions>(TdApi.GetMessageAvailableReactions(chatId, messageId, 25))
        }.getOrNull()?.let { avail ->
            (avail.topReactions + avail.recentReactions + avail.popularReactions)
                .mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
                .distinct()
        } ?: emptyList()

        val canList = reactions?.canGetAddedReactions == true
        val reactors = if (canList) {
            runCatching {
                send<TdApi.AddedReactions>(
                    TdApi.GetMessageAddedReactions(chatId, messageId, null, "", 100),
                ).reactions.mapNotNull { added ->
                    val emoji = (added.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
                    ReactorEntry(emoji, senderName(added.senderId), added.isOutgoing)
                }
            }.getOrDefault(emptyList())
        } else emptyList()

        return MessageReactionsInfo(
            summary = summary,
            ownEmoji = ownEmoji,
            canListReactors = canList,
            reactors = reactors,
            available = available,
        )
    }

    override suspend fun setOwnReaction(chatId: Long, messageId: Long, emoji: String?) {
        // AddMessageReaction/RemoveMessageReaction are the user-facing calls. SetMessageReactions
        // is documented "for bots only" and errors for a normal account - that error was swallowed
        // upstream, so tapping a reaction silently did nothing.
        // A non-premium account holds a single reaction, so clear whatever is currently chosen
        // (best-effort) before adding, to keep the "set to exactly this reaction" semantics.
        val chosen = send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
            .interactionInfo?.reactions?.reactions
            ?.filter { it.isChosen }
            ?.map { it.type }
            .orEmpty()
        for (type in chosen) {
            // Keep the one we're about to (re)add; drop the rest.
            if (emoji != null && (type as? TdApi.ReactionTypeEmoji)?.emoji == emoji) continue
            runCatching { send<TdApi.Ok>(TdApi.RemoveMessageReaction(chatId, messageId, type)) }
        }
        if (emoji != null && chosen.none { (it as? TdApi.ReactionTypeEmoji)?.emoji == emoji }) {
            send<TdApi.Ok>(
                TdApi.AddMessageReaction(
                    chatId,
                    messageId,
                    TdApi.ReactionTypeEmoji(emoji),
                    false, // isBig
                    true, // updateRecentReactions
                ),
            )
        }
    }

    private suspend fun senderName(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> runCatching {
            val user = send<TdApi.User>(TdApi.GetUser(sender.userId))
            listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "User ${sender.userId}" }
        }.getOrDefault("User ${sender.userId}")
        is TdApi.MessageSenderChat -> runCatching {
            send<TdApi.Chat>(TdApi.GetChat(sender.chatId)).title
        }.getOrDefault("Chat ${sender.chatId}")
        else -> "Unknown"
    }

    private suspend fun resolveUsername(userId: Long): String? {
        usernameCache[userId]?.let { return it.takeIf { u -> u != NO_USERNAME } }
        val username = runCatching {
            send<TdApi.User>(TdApi.GetUser(userId)).usernames?.activeUsernames?.firstOrNull()
        }.getOrNull()
        usernameCache[userId] = username ?: NO_USERNAME
        return username
    }

    override fun close() {
        client.send(TdApi.Close()) {}
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : TdApi.Object> send(function: TdApi.Function<out TdApi.Object>): T =
        suspendCancellableCoroutine { cont ->
            client.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TelegramException(result.code, result.message))
                } else {
                    cont.resume(result as T)
                }
            }
        }

    private companion object {
        // Max wait for ConnectionStateReady before a download proceeds anyway (see awaitConnected).
        const val AWAIT_CONNECTED_TIMEOUT_MS = 20_000L
        // A download reporting no progress for this long is treated as stalled and aborted. Generous
        // so a slow-to-start-but-healthy download (first UpdateFile can lag) isn't falsely killed.
        const val DOWNLOAD_STALL_TIMEOUT_MS = 20_000L
        // How often the stall watchdog checks the no-progress clock.
        const val STALL_POLL_MS = 2_000L
    }

    private fun TdApi.Chat.toTelegramChat() = TelegramChat(
        id = id,
        title = title,
        type = when (val t = type) {
            is TdApi.ChatTypeBasicGroup -> ChatType.GROUP
            is TdApi.ChatTypeSupergroup -> if (t.isChannel) ChatType.CHANNEL else ChatType.SUPERGROUP
            else -> ChatType.OTHER
        },
        photo = photo?.minithumbnail?.data,
        photoFileId = photo?.small?.id,
    )

    private fun TdApi.Message.toTelegramMessage(): TelegramMessage? {
        val userId = (senderId as? TdApi.MessageSenderUser)?.userId
        val audio: TelegramAudio
        val captionText: String?
        when (val c = content) {
            is TdApi.MessageAudio -> {
                audio = TelegramAudio(
                    fileId = c.audio.audio.id,
                    fileName = c.audio.fileName.takeIf { it.isNotBlank() },
                    mimeType = c.audio.mimeType,
                    duration = c.audio.duration,
                    performer = c.audio.performer.takeIf { it.isNotBlank() },
                    title = c.audio.title.takeIf { it.isNotBlank() },
                    fileSize = c.audio.audio.size,
                    fileUniqueId = c.audio.audio.remote.uniqueId,
                )
                captionText = c.caption.text.takeIf { it.isNotBlank() }
            }
            // Audio sent "as file" arrives as a document - accept it when the
            // mime/extension is audio, without music metadata (no duration/tags)
            is TdApi.MessageDocument -> {
                if (!isAudioDocument(c.document.mimeType, c.document.fileName)) return null
                audio = TelegramAudio(
                    fileId = c.document.document.id,
                    fileName = c.document.fileName.takeIf { it.isNotBlank() },
                    mimeType = c.document.mimeType.takeIf { it.isNotBlank() },
                    duration = 0,
                    performer = null,
                    title = null,
                    fileSize = c.document.document.size,
                    fileUniqueId = c.document.document.remote.uniqueId,
                )
                captionText = c.caption.text.takeIf { it.isNotBlank() }
            }
            else -> return null
        }
        return TelegramMessage(
            id = id,
            chatId = chatId,
            date = date.toLong(),
            senderId = userId,
            senderUsername = null,
            caption = captionText,
            audio = audio,
            reactions = extractReactions(),
        )
    }

    private fun isAudioDocument(mimeType: String?, fileName: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in audioExtensions
    }

    private fun TdApi.Message.extractReactions(): String? {
        val list = interactionInfo?.reactions?.reactions?.takeIf { it.isNotEmpty() } ?: return null
        return list.mapNotNull { r ->
            (r.type as? TdApi.ReactionTypeEmoji)?.emoji
        }.joinToString("").takeIf { it.isNotBlank() }
    }
}
