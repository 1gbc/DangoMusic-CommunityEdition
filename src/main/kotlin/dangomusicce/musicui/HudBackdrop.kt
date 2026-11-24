package dangomusicce.musicui

import dangomusicce.gfx.GuiCanvas
import dangomusicce.music.NeteaseTrack
import dangomusicce.ui.GlassStyle
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.utils.Color

internal enum class HudBackground(val label: String) {
    LIQUID("Liquid glass"),
    DARK("Dark glass"),
    COVER("Flowing cover colors");

    val capturesWorld get() = this != COVER
}

internal class HudBackdrop(private val canvas: () -> GuiCanvas) {
    private val flowing = FlowingCoverBackdrop(canvas)

    fun draw(bounds: LayoutBounds, glass: GlassStyle, background: HudBackground, track: NeteaseTrack?, alpha: Float = 1f) {
        val ctx = canvas()
        if (background == HudBackground.COVER) {
            flowing.prepareMenuFrame(track)
            flowing.drawControlPanel(bounds, glass.radius)
            ctx.drawShape(bounds, ShapeStyle(Color.BLACK.copy(a = 0.17f), glass.radius))
            return
        }
        val material = if (background == HudBackground.DARK) glass.copy(
            tint = Color(0.045f, 0.05f, 0.06f, 0.76f), maxLuma = 0.18f,
            saturation = 0.7f, frost = 0.38f, dispersion = 0f, highlight = 0.48f,
            fallback = Color(0.07f, 0.075f, 0.09f, 0.96f),
        ) else glass.copy(frost = maxOf(glass.frost, 0.64f), maxLuma = minOf(glass.maxLuma, 0.25f),
            fallback = glass.fallback.copy(a = maxOf(glass.fallback.a, 0.86f)))
        ctx.pushOpacity(alpha)
        try {
            ctx.drawGlass(bounds, material)
        } finally {
            ctx.popOpacity()
        }
    }
}
