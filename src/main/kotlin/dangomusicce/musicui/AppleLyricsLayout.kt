package dangomusicce.musicui

import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.Theme

/** Positions are measured in the 2464 × 1292 reference viewport. */
internal class AppleLyricsLayout private constructor(
    val viewport: LayoutBounds,
    val showLyrics: Boolean,
    private val shiftX: Float,
    private val shiftY: Float,
) {
    constructor(viewport: LayoutBounds, showLyrics: Boolean = true) : this(viewport, showLyrics, 0f, 0f)

    val scale = viewportScale(viewport.width, viewport.height)
    val titleSize = titleSizeFor(viewport.width, viewport.height)
    private val left = viewport.x + (viewport.width - REFERENCE_WIDTH * scale) / 2f
    private val top = viewport.y + (viewport.height - REFERENCE_HEIGHT * scale) / 2f

    private val controlOffset = if (showLyrics) 0f else 536f
    fun x(value: Float) = left + (value + controlOffset) * scale + shiftX
    fun y(value: Float) = top + value * scale + shiftY
    fun bounds(x: Float, y: Float, width: Float, height: Float) =
        LayoutBounds(this.x(x), this.y(y), width * scale, height * scale)

    val artwork = bounds(396f, 265f, 600f, 600f)
    // Continuous corners extend the nominal radius by 1.5, so both styles turn at about 3%.
    val artworkRadius get() = appleCoverRadius(artwork)
    // Keep the reference empty-state center while moving the left-aligned lyric text to the right.
    val lyrics = if (showLyrics) bounds(1332f, 265f, 714f, 780f) else LayoutBounds()
    val close = LayoutBounds(viewport.x + 7f * scale, viewport.y, 44f * scale, 42f * scale)
    val lyricsToggle = LayoutBounds(viewport.right - 54f * scale, viewport.bottom - 54f * scale, 44f * scale, 44f * scale)
    val moreButton = bounds(968f, 890f, 28f, 28f)
    val progress = bounds(396f, 946f, 600f, 4f)
    val volume = bounds(416f, 1051f, 580f, 4f)

    fun commentControls(): AppleLyricsLayout {
        val dx = viewport.x + viewport.width / 2f - x(696f)
        val dy = viewport.bottom - maxOf(14f, 54f * scale) - y(1067f)
        return AppleLyricsLayout(viewport, showLyrics, dx, dy)
    }

    companion object {
        const val TITLE_FONT = Theme.FONT_SEMIBOLD
        private const val REFERENCE_WIDTH = 2464f
        private const val REFERENCE_HEIGHT = 1292f

        private fun viewportScale(width: Float, height: Float): Float =
            minOf(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT)

        fun titleSizeFor(width: Float, height: Float): Float = 18f * viewportScale(width, height)
    }
}
