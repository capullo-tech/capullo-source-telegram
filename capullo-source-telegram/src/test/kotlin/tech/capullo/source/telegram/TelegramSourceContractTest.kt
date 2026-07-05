package tech.capullo.source.telegram

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.source.telegram.data.db.MediaMessageEntity
import tech.capullo.source.telegram.data.telegram.TelegramCredentials
import tech.capullo.source.telegram.data.telegram.TelegramRepository
import tech.capullo.source.telegram.player.DownloadManager
import tech.capullo.source.telegram.player.MetadataExtractor
import tech.capullo.source.telegram.player.TrackMetadata
import java.io.File

/**
 * The contract-stability deliverable: drives `TelegramSource` through the SPI against a
 * `FakeTelegramClient` (simulated suspending downloads) so the **behaviour**, not just the types,
 * validates `capullo-audio-contracts`. Pure JVM - runs under `:capullo-source-telegram:testDebugUnitTest`
 * with no emulator, so it runs on the build host and in CI.
 *
 * Each test corresponds to a claim the mapping table could only assert; here they're executed:
 *  1. `mediaRequestFor` suspends on a real download, returns a `file://` URI, enriches now-playing.
 *  2. `onQueueAdvanced` runs the 2-track lookahead (N+1/N+2, never N+3).
 *  3. an already-prefetched track resolves instantly (the `getCachedPath` fast path).
 *  4. a re-advance cancels the stale prefetch (the `prefetchJob?.cancel()` shape).
 *  5. an unresolvable (deleted) track throws - the seam's only "skip me" signal - and self-purges.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelegramSourceContractTest {

    private lateinit var tmp: File
    private val chatId = 42L

    private val id3 = TrackMetadata(title = "ID3 Title", artist = "ID3 Artist", artworkBase64 = "QVJU")
    private val metadataExtractor = MetadataExtractor { id3 }

    @Before fun setUp() { tmp = createTempDir("tg-src-test") }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun track(id: Long) = MediaMessageEntity(
        messageId = id, chatId = chatId, date = "0", senderId = null, senderUsername = null,
        caption = null, fileName = "track$id.mp3", fileUniqueId = "u$id", fileId = id.toInt(),
        duration = 180, performer = "DB Artist", title = "DB Title $id", fileSize = 1_000L,
        mimeType = "audio/mpeg", station = "Capullo", groupType = "GROUP", reactions = null,
    )

    private fun stack(client: FakeTelegramClient, dao: FakeMediaMessageDao): DownloadManager =
        DownloadManager(
            repository = TelegramRepository(client, dao),
            dao = dao,
            bufferSizeGb = { 100f }, // large: never evict during the test
        )

    @Test fun `mediaRequestFor suspends on download, returns file URI, and enriches now-playing`() = runTest {
        val client = FakeTelegramClient(tmp)
        val dao = FakeMediaMessageDao().apply { seed((1L..4L).map { track(it) }) }
        // Pass the TestScope itself so prefetch launched in onQueueAdvanced is driven by
        // advanceUntilIdle() (backgroundScope's launched work was not advanced here).
        val source = TelegramSource(this, stack(client, dao), metadataExtractor)
        source.loadStation((1L..4L).map { track(it) }, chatId, "Capullo")

        // Before download, the preview now-playing carries the DB title.
        assertEquals("DB Title 1", source.nowPlaying.value.title)

        val req = source.mediaRequestFor("1")

        // A real (fake) network download ran and produced an on-disk file behind a file:// URI.
        assertEquals(1, client.downloadCounts[1L])
        assertTrue("uri should be a file URI: ${req.uri}", req.uri.startsWith("file:///"))
        assertTrue("resolved file must exist", File(java.net.URI(req.uri)).exists())

        // Now-playing was enriched from the downloaded file's ID3 tags + cover art.
        val np = source.nowPlaying.value
        assertEquals("ID3 Title", np.title)
        assertEquals("ID3 Artist", np.artist)
        assertEquals("QVJU", np.artworkBase64)
        assertNull("file-based playback has no streamUrl", np.streamUrl)
        assertTrue(np.canGoNext)
        assertFalse(np.canGoPrevious)
    }

    @Test fun `onQueueAdvanced drives the 2-track lookahead`() = runTest {
        val client = FakeTelegramClient(tmp)
        val dao = FakeMediaMessageDao().apply { seed((1L..5L).map { track(it) }) }
        // Pass the TestScope itself so prefetch launched in onQueueAdvanced is driven by
        // advanceUntilIdle() (backgroundScope's launched work was not advanced here).
        val source = TelegramSource(this, stack(client, dao), metadataExtractor)
        source.loadStation((1L..5L).map { track(it) }, chatId, "Capullo")

        source.onQueueAdvanced(0) // playing index 0 → prefetch indices 1 and 2 (ids 2, 3)
        advanceUntilIdle()

        assertEquals("exactly N+1 and N+2 prefetched", setOf(2L, 3L), client.downloadCounts.keys)
        assertFalse("N+3 must not be prefetched", client.downloadCounts.containsKey(4L))
    }

    @Test fun `an already-prefetched track resolves instantly without re-downloading`() = runTest {
        val client = FakeTelegramClient(tmp)
        val dao = FakeMediaMessageDao().apply { seed((1L..4L).map { track(it) }) }
        // Pass the TestScope itself so prefetch launched in onQueueAdvanced is driven by
        // advanceUntilIdle() (backgroundScope's launched work was not advanced here).
        val source = TelegramSource(this, stack(client, dao), metadataExtractor)
        source.loadStation((1L..4L).map { track(it) }, chatId, "Capullo")

        source.onQueueAdvanced(0) // prefetches ids 2, 3
        advanceUntilIdle()
        assertEquals(1, client.downloadCounts[2L])

        // The engine now advances onto track 2 and resolves it: must hit the cache, not re-download.
        val req = source.mediaRequestFor("2")
        assertEquals("cache hit - no second download", 1, client.downloadCounts[2L])
        assertTrue(File(java.net.URI(req.uri)).exists())
    }

    @Test fun `re-advancing cancels the stale prefetch job`() = runTest {
        val client = FakeTelegramClient(tmp)
        val dao = FakeMediaMessageDao().apply { seed((1L..6L).map { track(it) }) }
        // Pass the TestScope itself so prefetch launched in onQueueAdvanced is driven by
        // advanceUntilIdle() (backgroundScope's launched work was not advanced here).
        val source = TelegramSource(this, stack(client, dao), metadataExtractor)
        source.loadStation((1L..6L).map { track(it) }, chatId, "Capullo")

        // Queue the prefetch of {2,3}, then immediately re-advance before the dispatcher runs it.
        // The out-of-range target prefetches nothing, so if the first job was cancelled, {2,3} never
        // download. (Under StandardTestDispatcher the launched job hasn't executed yet at cancel time.)
        source.onQueueAdvanced(0)
        source.onQueueAdvanced(10) // beyond the end → empty prefetch window
        advanceUntilIdle()

        assertTrue("stale prefetch must be cancelled - nothing downloaded", client.downloadCounts.isEmpty())
    }

    @Test fun `a deleted track throws UnresolvableTrackException and self-purges`() = runTest {
        val client = FakeTelegramClient(tmp).apply { notFoundIds += 3L }
        val dao = FakeMediaMessageDao().apply { seed((1L..4L).map { track(it) }) }
        // Pass the TestScope itself so prefetch launched in onQueueAdvanced is driven by
        // advanceUntilIdle() (backgroundScope's launched work was not advanced here).
        val source = TelegramSource(this, stack(client, dao), metadataExtractor)
        source.loadStation((1L..4L).map { track(it) }, chatId, "Capullo")

        val thrown = runCatching { source.mediaRequestFor("3") }.exceptionOrNull()
        assertTrue(
            "expected UnresolvableTrackException, got $thrown",
            thrown is UnresolvableTrackException,
        )
        // DownloadManager's "not found" fallback removed the row so the engine can skip it.
        assertEquals("deleted track purged from the index", 0, dao.exists(3L))
    }

    @Test fun `newDatabaseEncryptionKey yields a fresh 256-bit key each call`() {
        val a = TelegramCredentials.newDatabaseEncryptionKey()
        val b = TelegramCredentials.newDatabaseEncryptionKey()
        assertEquals("256-bit key", 32, a.size)
        assertEquals("256-bit key", 32, b.size)
        assertFalse("each key must be unique", a.contentEquals(b))
    }
}
