package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.gfx.Texture
import me.sorihiko.dangomusicgtnh.music.CoverCache
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds
import me.sorihiko.dangomusicgtnh.ui.Theme
import me.sorihiko.dangomusicgtnh.utils.Color

/** Uses the same cover palette and flow phase in the full lyrics view and the Apple player window. */
internal class FlowingCoverBackdrop(private val ui: Ui) {
    private val startedAt = System.nanoTime()
    private var previous: NeteaseTrack? = null
    private var current: NeteaseTrack? = null
    private var transitionStartedAt = 0L

    fun drawLyrics(bounds: LayoutBounds, song: NeteaseTrack?, radius: Float = 0f) {
        val texture = song?.let(CoverCache::trackBackdrop)
        if (texture == null) ui.card(bounds, Color.fromHex("#382122"), radius)
        else ui.ctx.drawFlowingBackdrop(bounds, texture, phase(System.nanoTime()), radius)
    }

    fun drawWindow(bounds: LayoutBounds, song: NeteaseTrack?) {
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
        val mix = progress * progress * (3f - 2f * progress)
        val top = WINDOW_ALPHA * mix
        val angle = phase(now)
        val radius = Theme.GLASS_WINDOW.radius
        if (mix < 1f) {
            // Source-over compositing with a constant total alpha, even while the window is fading in.
            val lower = (WINDOW_ALPHA - top) / (1f - top * ui.ctx.getOpacity())
            drawWindowLayer(bounds, previous?.let(CoverCache::cachedTrackBackdrop), radius, angle, lower)
        }
        if (top > 0f) {
            val currentTexture = if (current?.key == ready?.key) texture else current?.let(CoverCache::cachedTrackBackdrop)
            drawWindowLayer(bounds, currentTexture, radius, angle, top)
        }
        ui.card(bounds, WINDOW_SHADE, radius)
    }

    private fun drawWindowLayer(bounds: LayoutBounds, texture: Texture?, radius: Float, phase: Float, alpha: Float) {
        if (texture == null) {
            ui.card(bounds, Theme.GLASS_WINDOW.fallback.copy(a = alpha), radius)
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
        const val WINDOW_ALPHA = 0.85f
        val WINDOW_SHADE = Color(0.03f, 0.035f, 0.045f, 0.25f)
    }
}
