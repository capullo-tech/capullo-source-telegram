package tech.capullo.source.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.NowPlayingSource
import tech.capullo.audio.contracts.PlaybackQueue
import tech.capullo.source.telegram.data.db.MediaMessageEntity
import tech.capullo.source.telegram.player.DownloadManager
import tech.capullo.source.telegram.player.MetadataExtractor
import java.io.File

/**
 * The Telegram integrator - the *second* real implementation of the platform SPI
 * ([MediaSourceProvider] + [NowPlayingSource]), and the **contract stability anchor** (platform plan
 * ). Where `capullo-source-radiobrowser` exercised the seam only at the type level, this
 * one exercises the two behaviours the contract was designed for and radiobrowser never touched:
 *
 *  1. **`mediaRequestFor` genuinely SUSPENDS.** Radio hands back a stream URL immediately; here it
 *     awaits a real TDLib download ([DownloadManager.ensureDownloaded]) and only then returns the
 *     local `file://` URI. It also *throws* when the track can't be fetched (deleted message / offline)
 *     - the seam's only "skip this one" signal, which the engine turns into a skip.
 *  2. **`onQueueAdvanced` drives the 2-track lookahead.** Each advance cancels the previous prefetch
 *     and pulls N+1 / N+2 ahead - the exact loop Telecloud's `PlayerViewModel.prefetchAhead` runs.
 *     The hook being *non-suspend / fire-and-forget* is validated as **correct**: prefetch must not
 *     block the engine's advance and must be cancellable per advance, so the source owns a
 *     [CoroutineScope] + a cancellable [Job] (same shape as radiobrowser's Shazam loop).
 *
 * **The one behavioural difference from radiobrowser, worth a contract doc note (not a signature
 * change):** now-playing for a fetch-based source can only be *fully* assembled after the file is on
 * disk (ID3 title/artist + embedded art), so the now-playing side effect lives in [mediaRequestFor],
 * not in [onQueueAdvanced]/[loadStation] as it does for radio. This imposes an ordering the engine
 * must honour: **call `mediaRequestFor(idAt(i))` BEFORE `onQueueAdvanced(i)`** (resolve+play, then
 * report-advance-for-prefetch). See the driver test for the executable proof.
 *
 * DI-free: the app constructs it with a scope + the (also DI-free) [DownloadManager] and a
 * [MetadataExtractor]. Live radio's position/isPlaying overlay is the engine's job - the source
 * reports metadata; the engine merges its own playback clock.
 */
public class TelegramSource(
    private val scope: CoroutineScope,
    private val downloadManager: DownloadManager,
    private val metadataExtractor: MetadataExtractor,
    private val lookahead: Int = 2,
) : MediaSourceProvider, NowPlayingSource {

    private val _nowPlaying = MutableStateFlow(NowPlaying.EMPTY)
    override val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    // The loaded station's finite playlist, addressed by the engine via messageId (the queue's id).
    private var playlist: List<MediaMessageEntity> = emptyList()
    private var chatId: Long = 0
    private var chatTitle: String = ""
    @Volatile private var currentIndex: Int = 0

    private var prefetchJob: Job? = null

    // The messageId of the most recent mediaRequestFor call; a later request supersedes an
    // earlier one's slow tag/art enrichment (the user skipped before the read landed).
    @Volatile private var pendingRequestId: Long? = null

    /**
     * Load a station's playlist (already ordered/shuffled by the app, e.g. via
     * [tech.capullo.source.telegram.data.playlist.PlaylistRepository]) and mark [startIndex] current.
     * Mutability (shuffle, reorder, offline swap) lives here in the source; [queue] is a read snapshot.
     */
    public fun loadStation(
        tracks: List<MediaMessageEntity>,
        chatId: Long,
        chatTitle: String,
        startIndex: Int = 0,
    ) {
        this.playlist = tracks
        this.chatId = chatId
        this.chatTitle = chatTitle
        this.currentIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        // Publish a preview now-playing (DB tags only, no art) for the start track, mirroring
        // Telecloud's early `activeTrackRepository.set(track)`. mediaRequestFor later re-publishes the
        // base and enriches it with the downloaded file's ID3 tags + cover art (the authoritative pass).
        val start = tracks.getOrNull(this.currentIndex)
        _nowPlaying.value = if (start != null) baseNowPlaying(start, this.currentIndex) else NowPlaying.EMPTY
    }

    // --- MediaSourceProvider ---

    override suspend fun mediaRequestFor(id: String): MediaRequest {
        val messageId = id.toLongOrNull() ?: error("Not a telegram messageId: $id")
        val index = playlist.indexOfFirst { it.messageId == messageId }
        require(index >= 0) { "messageId $messageId not in the loaded playlist" }
        val track = playlist[index]
        pendingRequestId = messageId

        // Immediate now-playing from the DB tags (title/artist/station), art not yet available.
        publishNowPlaying(baseNowPlaying(track, index))

        // The blocking fetch: suspend until the file is on disk (cache → DB localPath → network).
        downloadManager.activeMessageId = messageId
        val path = downloadManager.ensureDownloaded(track.chatId, messageId)
            ?: throw UnresolvableTrackException(messageId)

        // Enrich now-playing with what only the downloaded file reveals: ID3 title/artist + cover art.
        // Applied only if no later request superseded this one (a slow read must not clobber a track
        // the user already skipped to).
        val meta = metadataExtractor.read(path)
        if (pendingRequestId == messageId) {
            _nowPlaying.update { np ->
                np.copy(
                    title = meta.title ?: np.title,
                    artist = meta.artist ?: np.artist,
                    artworkBase64 = meta.artworkBase64 ?: np.artworkBase64,
                )
            }
        }

        // Canonical file:// URI built without android.net.Uri so this stays JVM-testable.
        // File.toURI().rawPath gives the leading-slash, URL-encoded absolute path ("/data/x.mp3" on
        // Android, "/C:/…" on a Windows test host), so "file://" + it yields the well-formed
        // triple-slash form on every platform.
        return MediaRequest(uri = "file://" + File(path).toURI().rawPath)
    }

    override fun queue(): PlaybackQueue =
        TelegramPlaylistQueue(playlist.map { it.messageId.toString() }, currentIndex)

    override fun onQueueAdvanced(currentIndex: Int) {
        this.currentIndex = currentIndex
        // Cancel the stale prefetch and pull the next `lookahead` tracks. Fire-and-forget on the
        // source's own scope - must not block the engine's advance (validated contract shape).
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val last = minOf(currentIndex + lookahead, playlist.size - 1)
            for (i in (currentIndex + 1)..last) {
                val t = playlist[i]
                // getCachedPath first so an already-downloaded track is a no-op (the instant path
                // mediaRequestFor also benefits from). Only a miss triggers a suspending download.
                downloadManager.getCachedPath(t.messageId)
                    ?: downloadManager.ensureDownloaded(t.chatId, t.messageId)
            }
        }
    }

    /** Stop prefetch and clear now-playing (call from the app's onDestroy/stop). */
    public fun stop() {
        prefetchJob?.cancel()
        prefetchJob = null
        _nowPlaying.value = NowPlaying.EMPTY
    }

    // --- helpers ---

    private fun publishNowPlaying(np: NowPlaying) { _nowPlaying.value = np }

    private fun baseNowPlaying(track: MediaMessageEntity, index: Int): NowPlaying = NowPlaying(
        title = track.title ?: track.fileName ?: UNKNOWN_TITLE,
        artist = track.performer ?: "",
        album = track.station ?: chatTitle,
        artworkBase64 = null,
        durationMs = (track.duration ?: 0).toLong() * 1000L,
        positionMs = 0,
        isPlaying = true,
        canGoNext = index < playlist.size - 1,
        canGoPrevious = index > 0,
        streamUrl = null, // file-based playback
        extras = buildMap {
            if (chatTitle.isNotBlank()) put("chat", chatTitle)
            track.mimeType?.takeIf { it.isNotBlank() }?.let { put("mimeType", it) }
        },
    )

    private companion object {
        const val UNKNOWN_TITLE = "Unknown"
    }
}

/** Thrown by [TelegramSource.mediaRequestFor] when a track can't be resolved (deleted / offline). */
public class UnresolvableTrackException(public val messageId: Long) :
    Exception("Could not resolve telegram track $messageId (deleted or unavailable)")
