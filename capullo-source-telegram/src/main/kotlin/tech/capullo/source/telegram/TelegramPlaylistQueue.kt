package tech.capullo.source.telegram

import tech.capullo.audio.contracts.PlaybackQueue

/**
 * A finite playlist queue: next/previous depend on position ([isRotating] = false), unlike
 * radiobrowser's endless station rotation. The engine reads [idAt] to get a Telegram messageId,
 * which [TelegramSource.mediaRequestFor] resolves - via an on-demand download - to a local file URI.
 */
internal class TelegramPlaylistQueue(
    private val order: List<String>,
    override val currentIndex: Int,
) : PlaybackQueue {
    override val size: Int get() = order.size
    override val isRotating: Boolean get() = false
    override fun idAt(index: Int): String? = order.getOrNull(index)
}
