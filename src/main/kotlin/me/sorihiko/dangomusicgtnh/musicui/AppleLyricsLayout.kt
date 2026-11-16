package me.sorihiko.dangomusicgtnh.musicui

import me.sorihiko.dangomusicgtnh.ui.LayoutBounds

/** Positions are measured in the 2464 × 1292 reference viewport. */
internal class AppleLyricsLayout(val viewport: LayoutBounds) {
    val scale = minOf(viewport.width / 2464f, viewport.height / 1292f)
    private val left = viewport.x + (viewport.width - 2464f * scale) / 2f
    private val top = viewport.y + (viewport.height - 1292f * scale) / 2f

    fun x(value: Float) = left + value * scale
    fun y(value: Float) = top + value * scale
    fun bounds(x: Float, y: Float, width: Float, height: Float) =
        LayoutBounds(this.x(x), this.y(y), width * scale, height * scale)

    val artwork = bounds(396f, 265f, 600f, 600f)
    val lyrics = bounds(1220f, 265f, 938f, 780f)
    val moreButton = bounds(968f, 890f, 28f, 28f)
    val progress = bounds(396f, 946f, 600f, 4f)
    val volume = bounds(416f, 1051f, 580f, 4f)
}
