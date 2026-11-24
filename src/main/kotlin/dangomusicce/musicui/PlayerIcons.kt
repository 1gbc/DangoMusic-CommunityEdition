package dangomusicce.musicui

import dangomusicce.music.MusicPlayer
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.RenderContext
import dangomusicce.ui.Theme
import dangomusicce.ui.VectorIcon
import dangomusicce.utils.Color

/** Original 24px paths; one consistent stroke and one coverage mask per icon. */
internal object PlayerIcons {
    val repeat = VectorIcon.path {
        move(4.0,10.0); line(4.0,9.0); curve(4.0,6.8,5.8,5.0,8.0,5.0); line(20.0,5.0)
        move(16.5,1.5); line(20.0,5.0); line(16.5,8.5)
        move(20.0,14.0); line(20.0,15.0); curve(20.0,17.2,18.2,19.0,16.0,19.0); line(4.0,19.0)
        move(7.5,15.5); line(4.0,19.0); line(7.5,22.5)
    }
    val repeatOne = VectorIcon.path {
        move(4.0,10.0); line(4.0,9.0); curve(4.0,6.8,5.8,5.0,8.0,5.0); line(20.0,5.0)
        move(16.5,1.5); line(20.0,5.0); line(16.5,8.5)
        move(20.0,14.0); line(20.0,15.0); curve(20.0,17.2,18.2,19.0,16.0,19.0); line(4.0,19.0)
        move(7.5,15.5); line(4.0,19.0); line(7.5,22.5)
        move(10.5,10.0); line(12.5,8.5); line(12.5,15.5)
    }
    val shuffle = VectorIcon.path {
        move(3.0,6.0); line(5.0,6.0); curve(9.0,6.0,15.0,18.0,19.0,18.0); line(21.0,18.0)
        move(17.5,14.5); line(21.0,18.0); line(17.5,21.5)
        move(3.0,18.0); line(5.0,18.0); curve(6.8,18.0,8.2,15.8,9.5,14.0)
        move(14.5,10.0); curve(16.0,7.8,17.5,6.0,19.0,6.0); line(21.0,6.0)
        move(17.5,2.5); line(21.0,6.0); line(17.5,9.5)
    }
    val play = VectorIcon.path(filled = true) { move(7.0,3.5); line(21.0,12.0); line(7.0,20.5); close() }
    val previous = VectorIcon.path(filled = true) {
        move(12.0,4.0); line(2.0,12.0); line(12.0,20.0); close()
        move(22.0,4.0); line(12.0,12.0); line(22.0,20.0); close()
    }
    val next = VectorIcon.path(filled = true) {
        move(2.0,4.0); line(12.0,12.0); line(2.0,20.0); close()
        move(12.0,4.0); line(22.0,12.0); line(12.0,20.0); close()
    }
    val cardPlay = VectorIcon.path(filled = true) {
        move(6.0, 4.8)
        curve(6.0, 3.5, 7.3, 2.9, 8.4, 3.6)
        line(20.2, 10.6)
        curve(21.3, 11.3, 21.3, 12.7, 20.2, 13.4)
        line(8.4, 20.4)
        curve(7.3, 21.1, 6.0, 20.5, 6.0, 19.2)
        close()
    }
    val cardPause = VectorIcon.path(filled = true) {
        roundRect(5.0, 3.5, 5.2, 17.0, 1.7)
        roundRect(13.8, 3.5, 5.2, 17.0, 1.7)
    }

    private fun cardSkip(previous: Boolean) = VectorIcon.path(filled = true) {
        fun mirror(x: Double) = if (previous) 24.0 - x else x
        move(mirror(4.0), 5.6)
        curve(mirror(4.0), 4.4, mirror(5.0), 4.0, mirror(6.0), 4.6)
        line(mirror(16.0), 10.7)
        curve(mirror(17.0), 11.3, mirror(17.0), 12.7, mirror(16.0), 13.3)
        line(mirror(6.0), 19.4)
        curve(mirror(5.0), 20.0, mirror(4.0), 19.6, mirror(4.0), 18.4)
        close()
        roundRect(if (previous) 2.5 else 18.0, 4.4, 3.5, 15.2, 1.3)
    }
    val cardPrevious = cardSkip(previous = true)
    val cardNext = cardSkip(previous = false)
    val stop = VectorIcon.path(filled = true) { rect(5.0,5.0,14.0,14.0) }
    val queue = VectorIcon.path {
        circle(3.5,5.0,0.8); move(8.0,5.0); line(21.0,5.0)
        circle(3.5,12.0,0.8); move(8.0,12.0); line(21.0,12.0)
        circle(3.5,19.0,0.8); move(8.0,19.0); line(21.0,19.0)
    }
    private fun speakerPath(waves: Int, muted: Boolean) = VectorIcon.path {
        move(3.0,9.0); line(7.0,9.0); line(12.0,5.0); line(12.0,19.0); line(7.0,15.0); line(3.0,15.0); close()
        if (muted) {
            move(16.0,9.0); line(21.0,15.0); move(16.0,15.0); line(21.0,9.0)
        } else {
            if (waves >= 1) { move(16.0,8.0); curve(18.5,10.0,18.5,14.0,16.0,16.0) }
            if (waves >= 2) { move(19.0,4.5); curve(23.5,8.5,23.5,15.5,19.0,19.5) }
        }
    }
    private val speakers = arrayOf(speakerPath(0,false),speakerPath(1,false),speakerPath(2,false),speakerPath(0,true))

    private val appleSpeakerBody = VectorIcon.path {
        move(2.5,9.0); line(6.0,9.0); line(10.7,5.0); line(10.7,19.0); line(6.0,15.0); line(2.5,15.0); close()
    }
    private val appleSpeakerMute = VectorIcon.path {
        move(15.0,9.0); line(20.0,15.0); move(15.0,15.0); line(20.0,9.0)
    }
    private val appleSpeakerArcs = listOf(
        VectorIcon.path(strokeWidth = 1.7f) { move(13.0,8.6); curve(15.6,10.4,15.6,13.6,13.0,15.4) },
        VectorIcon.path(strokeWidth = 1.7f) { move(15.8,5.9); curve(19.5,8.9,19.5,15.1,15.8,18.1) },
        VectorIcon.path(strokeWidth = 1.7f) { move(18.6,3.3); curve(23.6,7.5,23.6,16.5,18.6,20.7) },
    )

    fun volumePercent(volume: Float): Int =
        if (volume <= 0f) 0 else kotlin.math.round(volume * 100f).toInt().coerceIn(1, 100)

    internal class VolumeSpeaker {
        private val opacity = FloatArray(4)
        private var initialized = false

        fun draw(ui: Ui, x: Float, y: Float, volume: Float, color: Color, scale: Float) =
            draw(ui.ctx, x, y, volume, color, scale) { value, target -> ui.approach(value, target, 48f) }

        fun draw(ctx: RenderContext, x: Float, y: Float, volume: Float, color: Color, scale: Float, dt: Float) =
            draw(ctx, x, y, volume, color, scale) { value, target ->
                value + (target - value) * (1f - kotlin.math.exp(-dt * 48f))
            }

        private inline fun draw(
            ctx: RenderContext, x: Float, y: Float, volume: Float, color: Color, scale: Float,
            approach: (Float, Float) -> Float,
        ) {
            val percent = volumePercent(volume)
            val size = 24f * scale
            draw(ctx, appleSpeakerBody, x, y, size, color)
            for (i in opacity.indices) {
                val on = when (i) {
                    0 -> percent > 0
                    1 -> percent >= 34
                    2 -> percent >= 67
                    else -> percent == 0
                }
                val target = if (on) 1f else 0f
                opacity[i] = if (initialized) approach(opacity[i], target) else target
                if (opacity[i] > 0.01f) {
                    val icon = if (i == 3) appleSpeakerMute else appleSpeakerArcs[i]
                    draw(ctx, icon, x, y, size, color.copy(a = color.a * opacity[i]))
                }
            }
            initialized = true
        }
    }
    /** 红心（和侧栏“我喜欢”同一个形状，宽一点，小字号下也饱满） */
    private fun heartPath(filled: Boolean, strokeWidth: Float = VectorIcon.STROKE) = VectorIcon.path(filled, strokeWidth) {
        move(12.0,21.0); curve(9.5,18.0,2.0,13.8,2.0,8.2)
        curve(2.0,4.6,4.6,2.5,7.4,2.5); curve(9.4,2.5,11.0,3.7,12.0,5.4)
        curve(13.0,3.7,14.6,2.5,16.6,2.5); curve(19.4,2.5,22.0,4.6,22.0,8.2)
        curve(22.0,13.8,14.5,18.0,12.0,21.0); close()
    }
    val heart = heartPath(true)
    val heartOutline = heartPath(false)
    val heartBoldOutline = heartPath(false, 2.8f)
    val comments = VectorIcon.path {
        move(6.0,18.5); line(3.0,21.0); line(4.0,15.8)
        curve(-0.5,8.0,6.0,2.2,12.0,2.5)
        curve(25.0,2.5,25.0,19.5,12.0,19.5)
        curve(10.0,19.5,8.0,19.2,6.0,18.5); close()
        move(8.0,9.0); line(16.0,9.0)
        move(8.0,13.0); line(14.0,13.0)
    }
    /** 小箭头（设置里的 ◀ ▶、下拉按钮），描边和其它图标一样粗 */
    val chevronLeft = VectorIcon.path { move(15.0,5.0); line(8.0,12.0); line(15.0,19.0) }
    val chevronRight = VectorIcon.path { move(9.0,5.0); line(16.0,12.0); line(9.0,19.0) }
    val chevronDown = VectorIcon.path { move(5.5,9.0); line(12.0,15.5); line(18.5,9.0) }
    val chevronUp = VectorIcon.path { move(5.5,15.0); line(12.0,8.5); line(18.5,15.0) }
    /** 对勾（选中项） */
    val check = VectorIcon.path { move(4.5,12.5); line(9.5,17.5); line(19.5,6.5) }
    /** 刷新：缺口在右上的圆圈 + 箭头 */
    val refresh = VectorIcon.path {
        move(19.5,12.0); curve(19.5,16.1,16.1,19.5,12.0,19.5); curve(7.9,19.5,4.5,16.1,4.5,12.0)
        curve(4.5,7.9,7.9,4.5,12.0,4.5); curve(14.3,4.5,16.3,5.5,17.7,7.1)
        move(18.2,2.8); line(18.2,7.6); line(13.4,7.6)
    }
    private fun ringArc(percent: Int) = VectorIcon.path {
        val steps = ((percent * 40 + 99) / 100).coerceAtLeast(1)
        for (index in 0..steps) {
            val angle = (index.toDouble() / steps) * (Math.PI * 2 * percent / 100) - Math.PI / 2
            val x = 12 + kotlin.math.cos(angle) * 8
            val y = 12 + kotlin.math.sin(angle) * 8
            if (index == 0) move(x, y) else line(x, y)
        }
    }
    private val loadingArcs = Array(101) { ringArc(it) }

    fun loadingRing(ctx: RenderContext, x: Float, y: Float, percent: Int?, size: Float = 18f) {
        draw(ctx, loadingArcs[100], x, y, size, Theme.TEXT_TERTIARY)
        if (percent != null) {
            val step = percent.coerceIn(0, 100)
            if (step > 0) draw(ctx, loadingArcs[step], x, y, size, Theme.TEXT_PRIMARY)
        } else {
            ctx.pushTranslate(x, y, 0f)
            ctx.pushRotate((System.nanoTime() % 900_000_000L) / 2_500_000f, 0f, 0f, 1f)
            draw(ctx, loadingArcs[30], 0f, 0f, size, Theme.TEXT_PRIMARY)
            ctx.popTransform()
            ctx.popTransform()
        }
    }

    fun draw(ctx: RenderContext, icon: VectorIcon, cx: Float, cy: Float, size: Float, color: Color) =
        ctx.drawIcon(LayoutBounds(cx-size/2f,cy-size/2f,size,size),icon,color)
    fun mode(ctx: RenderContext, mode: MusicPlayer.Mode, cx: Float, cy: Float, size: Float, color: Color) =
        draw(ctx,when(mode) { MusicPlayer.Mode.SEQUENTIAL -> repeat; MusicPlayer.Mode.SHUFFLE -> shuffle; MusicPlayer.Mode.REPEAT_ONE -> repeatOne },cx,cy,size,color)
    fun speaker(ctx: RenderContext, cx: Float, cy: Float, color: Color, scale: Float = 1f, waves: Int = 2, muted: Boolean = false) =
        draw(ctx,speakers[if(muted) 3 else waves.coerceIn(0,2)],cx,cy,24f*scale,color)
}
