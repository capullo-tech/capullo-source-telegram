package tech.capullo.source.telegram.harness

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import tech.capullo.source.telegram.TelegramSource
import tech.capullo.source.telegram.data.db.MediaMessageEntity
import tech.capullo.source.telegram.player.DownloadManager
import tech.capullo.source.telegram.player.MetadataExtractor
import tech.capullo.source.telegram.player.TrackMetadata

/**
 * Minimal harness proving `capullo-source-telegram` is consumable against the SPI: construct
 * [TelegramSource], load a station playlist, and read back the [tech.capullo.audio.contracts.PlaybackQueue]
 * + [tech.capullo.audio.contracts.NowPlaying] it exposes.
 *
 * The behavioural half of the contract (the suspending `mediaRequestFor` + the `onQueueAdvanced`
 * 2-track lookahead against a real TDLib download) is exercised by the fake-driven **unit test**
 * (`src/test/.../TelegramSourceContractTest`), not here - that's the contract deliverable. This harness
 * only proves the public API links and assembles into an APK.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(Dispatchers.Main)

        // The download path needs a client; the harness doesn't connect to Telegram, so it wires a
        // DownloadManager whose repository is never invoked (queue()/nowPlaying don't download).
        val source = TelegramSource(
            scope = scope,
            downloadManager = DownloadManager(
                repository = throwawayRepository(),
                dao = InMemoryDao,
            ),
            metadataExtractor = MetadataExtractor { TrackMetadata(null, null, null) },
        )
        source.loadStation(
            tracks = listOf(
                demoTrack(1, "Intro", "Capullo"),
                demoTrack(2, "Second Track", "Capullo"),
            ),
            chatId = 42L,
            chatTitle = "Capullo Test Chat",
        )
        val queue = source.queue()
        val np = source.nowPlaying.value
        setContentView(
            TextView(this).apply {
                text = buildString {
                    appendLine("capullo-source-telegram harness")
                    appendLine("source: ${source.javaClass.simpleName}")
                    appendLine("queue: size=${queue.size} rotating=${queue.isRotating} id0=${queue.idAt(0)}")
                    appendLine("nowPlaying: title=${np.title} album=${np.album} extras=${np.extras}")
                }
            },
        )
    }

    private fun demoTrack(id: Long, title: String, station: String) = MediaMessageEntity(
        messageId = id, chatId = 42L, date = "0", senderId = null, senderUsername = null,
        caption = null, fileName = "$title.mp3", fileUniqueId = "u$id", fileId = id.toInt(),
        duration = 180, performer = "Demo Artist", title = title, fileSize = 1_000_000L,
        mimeType = "audio/mpeg", station = station, groupType = "GROUP", reactions = null,
    )
}
