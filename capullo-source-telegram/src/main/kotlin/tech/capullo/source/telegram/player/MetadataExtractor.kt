package tech.capullo.source.telegram.player

import android.media.MediaMetadataRetriever
import android.util.Base64

/** Tags + embedded artwork read off a downloaded audio file, used to enrich [tech.capullo.audio.contracts.NowPlaying]. */
data class TrackMetadata(
    val title: String?,
    val artist: String?,
    /** Embedded cover art, Base64-encoded (the form the Snapcast control plugin publishes as `artData`). */
    val artworkBase64: String?,
)

/**
 * Reads ID3/container tags from a local file. Behind an interface so the contract-validation driver
 * test can feed canned metadata without touching `MediaMetadataRetriever` (an Android-only class that
 * is a no-op stub under a JVM unit test).
 */
fun interface MetadataExtractor {
    fun read(path: String): TrackMetadata
}

/** Real Android implementation over [MediaMetadataRetriever] (no Media3 dependency). */
class AndroidMetadataExtractor : MetadataExtractor {
    override fun read(path: String): TrackMetadata = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(path)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            val art = mmr.embeddedPicture?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            TrackMetadata(title, artist, art)
        }
    }.getOrElse { TrackMetadata(null, null, null) }
}
