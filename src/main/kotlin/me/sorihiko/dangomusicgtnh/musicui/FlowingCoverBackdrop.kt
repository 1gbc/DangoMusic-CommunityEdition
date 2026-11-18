package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.gfx.Texture
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.utils.Color

/** Uses the same cover palette and flow phase in the full lyrics view and the Apple player window. */
internal class FlowingCoverBackdrop(private val ui: Ui) {
    private val startedAt = System.nanoTime()
    private var previous: NeteaseTrack? = null
    private var current: NeteaseTrack? = null
    private var transitionStartedAt = 0L
    private var menuFrame = Frame(null, null, 1f, 0f)

    private data class Frame(val from: Texture?, val to: Texture?, val mix: Float, val phase: Float)

    fun drawLyrics(bounds: LayoutBounds, song: NeteaseTrack?, radius: Float = 0f) {
        val texture = song?.let(CoverCache::trackBackdrop)
        if (texture == null) ui.card(bounds, Color.fromHex("#382122"), radius)
        else ui.ctx.drawFlowingBackdrop(bounds, texture, phase(System.nanoTime()), radius)
    }

    fun prepareMenuFrame(song: NeteaseTrack?) {
        val now = System.nanoTime()
        val texture = song?.let(CoverCache::trackBackdrop)
        val ready = song.takeIf { texture != null }
        val transitioning = transitionStartedAt != 0L && now - transitionStartedAt < TRANSITION_NANOS
        if (!transitioning) {
            previous = current
            transitionStartedAt = if (ready?.key != current?.key) now else 0L
            current = ready
        }
        val progress = if (transitionStartedAt == 0L) 1f else
            ((now - transitionStartedAt).toDouble() / TRANSITION_NANOS).toFloat()
        menuFrame = Frame(
            previous?.takeIf { transitioning || transitionStartedAt == now }?.let(CoverCache::cachedTrackBackdrop),
            if (current?.key == ready?.key) texture else current?.let(CoverCache::cachedTrackBackdrop),
            progress * progress * (3f - 2f * progress), phase(now),
        )
    }

    fun drawMenuPanel(bounds: LayoutBounds, radius: Float, shade: Color, alpha: Float = 1f) {
        val (from, to, mix, angle) = menuFrame
        val top = alpha * mix
        if (mix < 1f) {
            // Keep the content fully opaque at rest and respect the whole window's open/close fade.
            val lower = (alpha - top) / (1f - top * ui.ctx.getOpacity())
            drawWindowLayer(bounds, from, radius, angle, lower)
        }
        if (top > 0f) drawWindowLayer(bounds, to, radius, angle, top)
        ui.card(bounds, shade, radius)
    }

    private fun drawWindowLayer(bounds: LayoutBounds, texture: Texture?, radius: Float, phase: Float, alpha: Float) {
        if (texture == null) {
            ui.card(bounds, FALLBACK.copy(a = alpha), radius)
            return
        }
        ui.ctx.pushOpacity(alpha)
        try {
            ui.ctx.drawFlowingBackdrop(bounds, texture, phase, radius)
        } finally {
            ui.ctx.popOpacity()
        }
    }

    private fun phase(now: Long): Float =
        ((now - startedAt) % FLOW_NANOS * (Math.PI * 2.0 / FLOW_NANOS)).toFloat()

    private companion object {
        const val FLOW_NANOS = 14_000_000_000L
        const val TRANSITION_NANOS = 420_000_000L
        val FALLBACK = Color(0.11f, 0.11f, 0.13f, 1f)
    }
}
