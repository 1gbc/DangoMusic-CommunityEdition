package dangomusicce.platform

import dangomusicce.gfx.CjkRun
import dangomusicce.gfx.ContrastPass
import dangomusicce.gfx.Gfx
import dangomusicce.gfx.GlassTextPass
import dangomusicce.gfx.GlassTextStyle
import dangomusicce.gfx.GlassTextView
import dangomusicce.gfx.GlowPass
import dangomusicce.gfx.GuiCanvas
import dangomusicce.gfx.HudCanvas
import dangomusicce.gfx.TextCanvas
import dangomusicce.gfx.Texture
import dangomusicce.gfx.Textures
import dangomusicce.gfx.ViewAngles
import dangomusicce.gfx.WorldView
import dangomusicce.ui.FontStyle
import dangomusicce.ui.GlassStyle
import dangomusicce.ui.LayoutBounds
import dangomusicce.ui.ShapeStyle
import dangomusicce.ui.VectorIcon
import dangomusicce.utils.Color
import org.joml.Matrix4f
import java.awt.image.BufferedImage

/**
 * M0 占位图形实现：在 M1/M2/M3 接入高版本 OpenGL 渲染前保证共享代码可无异常编译与运行。
 */
internal object StubGfx : Gfx {
    private object StubTexture : Texture

    override val textures: Textures = object : Textures {
        override fun upload(name: String, image: BufferedImage, linear: Boolean): Texture = StubTexture
        override fun delete(texture: Texture) {}
    }

    override fun newCanvas(): GuiCanvas = StubGuiCanvas()

    override fun uiTextWidth(text: String, size: Float, font: String): Float =
        text.length * size * 0.55f

    override fun cjkWidth(text: String, size: Float, style: Int): Float =
        text.length * size * 0.85f

    override fun cjkPreload(c: Char, style: Int) {}

    override fun drawCjkRuns(runs: List<CjkRun>, scale: Float) {}

    override fun guarded(block: () -> Unit) {
        block()
    }

    override val text: TextCanvas = object : TextCanvas {
        override fun push() {}
        override fun pop() {}
        override fun translate(x: Float, y: Float) {}
        override fun rotate(degrees: Float) {}
        override fun scale(x: Float, y: Float) {}
        override fun color(r: Float, g: Float, b: Float, a: Float) {}
        override fun smoothText(text: String, x: Float, baseline: Float, size: Float, style: Int) {}
        override fun smoothWidth(text: String, size: Float, style: Int): Float = text.length * size * 0.85f
        override fun beginSmooth() {}
        override fun endSmooth() {}
        override fun pixelText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {}
        override fun pixelWidth(text: String): Float = text.length * 6f
        override fun pixelCharWidth(c: Char): Float = 6f
        override fun beginScreen() {}
        override fun endScreen() {}
    }

    override val world: WorldView = object : WorldView {
        override val cameraX: Double = 0.0
        override val cameraY: Double = 0.0
        override val cameraZ: Double = 0.0
        override fun cameraAngles(): ViewAngles = ViewAngles(0f, 0f)
        override fun begin() {}
        override fun push(rx: Double, ry: Double, rz: Double, yaw: Float, pitch: Float, scale: Float) {}
        override fun push(transform: Matrix4f) {}
        override fun pop() {}
        override fun resync() {}
    }

    override val glow: GlowPass = object : GlowPass {
        override val available: Boolean = false
        override fun begin(): Boolean = false
        override fun end(r: Float, g: Float, b: Float, exposure: Float, spread: Float) {}
        override fun disable(reason: Throwable) {}
    }

    override val hud: HudCanvas = object : HudCanvas {
        override fun begin() {}
        override fun end() {}
        override fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {}
        override fun texture(texture: Texture, x: Int, y: Int, width: Int, height: Int, alpha: Float) {}
        override fun text(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {}
    }

    override val glassText: GlassTextPass = object : GlassTextPass {
        override fun begin(view: GlassTextView): Boolean = false
        override fun bar(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, alphaTop: Float, alphaBottom: Float) {}
        override fun end(style: GlassTextStyle) {}
    }

    override val contrast: ContrastPass = object : ContrastPass {
        override fun begin(): Boolean = false
        override fun end() {}
    }
}

private class StubGuiCanvas : GuiCanvas {
    private var mouseX = 0
    private var mouseY = 0
    private val opacityStack = ArrayDeque<Float>().apply { addLast(1f) }

    override fun getMouseX(): Int = mouseX
    override fun getMouseY(): Int = mouseY
    override fun isMouseOver(bounds: LayoutBounds): Boolean = bounds.contains(mouseX, mouseY)

    override fun drawShape(bounds: LayoutBounds, style: ShapeStyle) {}
    override fun drawText(text: String, x: Float, y: Float, style: FontStyle) {}
    override fun drawImage(bounds: LayoutBounds, image: Texture) {}
    override fun drawRoundedImage(bounds: LayoutBounds, image: Texture, radius: Float) {}
    override fun drawIcon(bounds: LayoutBounds, icon: VectorIcon, color: Color) {}
    override fun drawGlass(bounds: LayoutBounds, style: GlassStyle) {}

    override fun drawOccludedImage(
        bounds: LayoutBounds,
        image: Texture?,
        radius: Float,
        occluders: List<LayoutBounds>,
        brightness: Float,
        blurPixels: Float,
    ) {}

    override fun drawFlowingBackdrop(bounds: LayoutBounds, image: Texture, phase: Float, radius: Float) {}
    override fun drawCroppedSquareImage(bounds: LayoutBounds, image: Texture, radius: Float) {}

    override fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int) {
        this.mouseX = mouseX
        this.mouseY = mouseY
    }

    override fun end() {}
    override fun scissor(x: Int, y: Int, width: Int, height: Int) {}
    override fun noScissor() {}
    override fun pushClip(bounds: LayoutBounds) {}
    override fun popClip() {}

    override fun pushOpacity(value: Float) {
        opacityStack.addLast((getOpacity() * value).coerceIn(0f, 1f))
    }

    override fun popOpacity() {
        if (opacityStack.size > 1) opacityStack.removeLast()
    }

    override fun getOpacity(): Float = opacityStack.last()

    override fun popTransform() {}
    override fun pushTranslate(x: Float, y: Float, z: Float) {}
    override fun pushScale(x: Float, y: Float, z: Float) {}
    override fun pushRotate(angle: Float, x: Float, y: Float, z: Float) {}
}
