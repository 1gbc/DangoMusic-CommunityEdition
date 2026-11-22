package dangomusicce.musicui

import dangomusicce.ui.LayoutBounds

/** Reversible, frame-time driven spring: opening, closing and reopening share the same state. */
internal class PopupMotion {
    private val spring = Spring(420f, 36f)
    val progress: Float get() = spring.x.coerceIn(0f, 1.015f)
    val visible: Boolean get() = progress > 0.002f
    val contentOpacity: Float get() = ((progress - 0.15f) / 0.65f).coerceIn(0f, 1f)
    fun step(open: Boolean, dt: Float) = spring.step(if (open) 1f else 0f, dt.coerceIn(0f,0.05f))
    fun snap(open: Boolean) { spring.x=if(open) 1f else 0f; spring.v=0f }
    fun bounds(anchor: LayoutBounds, target: LayoutBounds): LayoutBounds {
        val p = progress
        return LayoutBounds(anchor.x+(target.x-anchor.x)*p, anchor.y+(target.y-anchor.y)*p,
            anchor.width+(target.width-anchor.width)*p, anchor.height+(target.height-anchor.height)*p)
    }
}

/** Draw the panel and reveal its contents from the control that opened it. */
internal fun PopupMotion.draw(
    ui: Ui, anchor: LayoutBounds, target: LayoutBounds, open: Boolean,
    panel: (LayoutBounds) -> Unit,
    content: (LayoutBounds, Boolean, LayoutBounds) -> Unit,
): LayoutBounds {
    val bounds = bounds(anchor, target)
    val amount = progress.coerceIn(0f, 1f)
    if (!visible) return bounds

    ui.ctx.pushOpacity(amount)
    try { panel(bounds) } finally { ui.ctx.popOpacity() }
    ui.beginClip(bounds)
    ui.ctx.pushOpacity(contentOpacity)
    try {
        ui.popup(bounds) { content(target.copy(y = target.y + 10f * (1f - amount)), open && amount > 0.6f, bounds) }
    } finally {
        ui.ctx.popOpacity()
        ui.endClip()
    }
    return bounds
}

internal const val CONTROL_POPUP_GAP = 8f
