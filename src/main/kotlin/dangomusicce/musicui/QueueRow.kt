package dangomusicce.musicui

import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShadowStyle
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.Theme
import dangomusicce.ui.Vec2
import dangomusicce.utils.Color

private val appleDragRow = ShapeStyle(
    fillColor = Color(0.19f, 0.21f, 0.26f, 0.97f),
    radius = 7f,
    borderWidth = 0.75f,
    borderColor = Color(0.54f, 0.62f, 0.74f, 0.66f),
    shadow = ShadowStyle(Color.BLACK.copy(a = 0.28f), 5f, Vec2(0f, 2f), outside = true),
)

private val classicDragRow = ShapeStyle(
    fillColor = Color(0.29f, 0.18f, 0.22f, 0.98f),
    fillEndColor = Color(0.20f, 0.12f, 0.16f, 0.98f),
    radius = 12f,
    borderWidth = 1f,
    borderColor = RED.copy(a = 0.46f),
    shadow = ShadowStyle(Color.BLACK.copy(a = 0.34f), 7f, Vec2(0f, 3f), outside = true),
)

internal fun Ui.drawLiftedQueueRow(row: LayoutBounds, classic: Boolean) {
    ctx.drawShape(row, if (classic) classicDragRow else appleDragRow)
}

internal fun Ui.drawTrackCoverControl(cover: LayoutBounds, radius: Float, size: Float, playing: Boolean) {
    card(cover, Color.BLACK.copy(a = 0.45f), radius)
    PlayerIcons.draw(ctx, if (playing) AppleLyricsIcons.pause else AppleLyricsIcons.play,
        cover.x + cover.width / 2f, cover.y + cover.height / 2f, size, Theme.TEXT_PRIMARY)
}
