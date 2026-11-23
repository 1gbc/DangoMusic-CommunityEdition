package dangomusicce.ui

import dangomusicce.gfx.GuiCanvas
import dangomusicce.gfx.Texture
import dangomusicce.platform.Gl
import dangomusicce.platform.GlState
import dangomusicce.platform.Imm
import dangomusicce.platform.ModernGl
import dangomusicce.platform.ModernTexture
import dangomusicce.shader.impls.ui.FontShader
import dangomusicce.shader.impls.ui.GlassShader
import dangomusicce.shader.impls.ui.IconShader
import dangomusicce.shader.impls.ui.ImageShader
import dangomusicce.shader.impls.ui.ShapeShader
import dangomusicce.ui.glass.GlassBackdrop
import dangomusicce.utils.Color
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import kotlin.math.abs

/** 高版本的液态玻璃画布：OpenGL 3.2 core + 着色器 + [Imm]。 */
class GlassRenderContext : GuiCanvas {
    private val shapeShader = ModernGl.shaderManager.get<ShapeShader>()
    private val fontShader = ModernGl.shaderManager.get<FontShader>()
    private val imageShader = ModernGl.shaderManager.get<ImageShader>()
    private val glassShader = ModernGl.shaderManager.get<GlassShader>()
    private val iconShader = ModernGl.shaderManager.get<IconShader>()

    private var backdrop: GlassBackdrop? = null
    private var snapshot: GlState? = null

    private val clipStack = ArrayDeque<LayoutBounds>()
    private val opacityStack = ArrayDeque<Float>()
    private val transformStack = ArrayDeque<Matrix4f>()

    private var _mouseX: Int = 0
    private var _mouseY: Int = 0

    override fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int) {
        clipStack.clear()
        opacityStack.clear()
        transformStack.clear()
        snapshot = GlState()
        val b = backdrop ?: GlassBackdrop().also { backdrop = it }
        if (captureBackdrop) b.capture()

        Gl.alphaBlend()
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
        Gl.depthTest(false)
        Gl.depthMask(false)
        Gl.cull(false)
        Gl.scissor(false)
        Gl.colorMask(true, true, true, true)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        _mouseX = mouseX
        _mouseY = mouseY
    }

    override fun recaptureBackdrop() {
        backdrop?.capture(fromDrawTarget = true)
    }

    override fun end() {
        clipStack.clear()
        opacityStack.clear()
        transformStack.clear()
        Gl.scissor(false)
        snapshot?.restore()
        snapshot = null
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        Gl.scissor(true)
        Gl.scissorBox(x, y, width, height)
    }

    override fun noScissor() {
        Gl.scissor(false)
    }

    private fun screenSize(): Size {
        val w = Minecraft.getInstance().window
        return Size(w.width.toFloat(), w.height.toFloat())
    }

    override fun getMouseX(): Int = _mouseX

    override fun getMouseY(): Int = _mouseY

    override fun isMouseOver(bounds: LayoutBounds): Boolean = bounds.contains(_mouseX, _mouseY)

    override fun drawShape(bounds: LayoutBounds, style: ShapeStyle) {
        shapeShader.bind()
        shapeShader.setup(
            getTransform(),
            screenSize(),
            bounds,
            style,
            getOpacity(),
        )
        val renderPadding =
            style.borderWidth + style.shadow.size + maxOf(abs(style.shadow.offset.x), abs(style.shadow.offset.y))

        val x1 = bounds.x - renderPadding
        val y1 = bounds.y - renderPadding
        val x2 = bounds.x + bounds.width + renderPadding
        val y2 = bounds.y + bounds.height + renderPadding

        Imm.begin()
        Imm.vertex(x1, y1)
        Imm.vertex(x1, y2)
        Imm.vertex(x2, y2)
        Imm.vertex(x2, y1)
        Imm.end()

        shapeShader.unbind()
    }

    override fun drawText(text: String, x: Float, y: Float, style: FontStyle) {
        val uiFont = ModernGl.font(style.font)

        fontShader.bind()
        fontShader.setup(
            getTransform(),
            screenSize(),
            style.color,
            getOpacity(),
        )
        Gl.activeTexture(GL13.GL_TEXTURE0)
        Gl.bindTexture(uiFont.textureId)

        val scale = style.fontSize / uiFont.fontSize
        var curX = x
        Imm.begin()
        for (c in text) {
            val glyph = uiFont.getGlyph(c) ?: continue

            val x0 = curX + glyph.bearingX * scale
            val y0 = y - glyph.bearingY * scale
            val x1 = x0 + glyph.width * scale
            val y1 = y0 + glyph.height * scale

            Imm.tex(glyph.u0, glyph.v0); Imm.vertex(x0, y0)
            Imm.tex(glyph.u0, glyph.v1); Imm.vertex(x0, y1)
            Imm.tex(glyph.u1, glyph.v1); Imm.vertex(x1, y1)
            Imm.tex(glyph.u1, glyph.v0); Imm.vertex(x1, y0)

            curX += glyph.advance * scale
        }
        Imm.end()
        fontShader.unbind()
    }

    override fun drawOccludedImage(
        bounds: LayoutBounds,
        image: Texture?,
        radius: Float,
        occluders: List<LayoutBounds>,
        brightness: Float,
        blurPixels: Float,
    ) {
        drawTexturedImage(
            bounds,
            image,
            radius,
            occluders = occluders,
            brightness = brightness,
            horizontalBlur = blurPixels / bounds.width.coerceAtLeast(1f),
        )
    }

    override fun drawImage(bounds: LayoutBounds, image: Texture) {
        drawTexturedImage(bounds, image, 0f)
    }

    override fun drawRoundedImage(bounds: LayoutBounds, image: Texture, radius: Float) {
        drawTexturedImage(bounds, image, radius)
    }

    override fun drawFlowingBackdrop(bounds: LayoutBounds, image: Texture, phase: Float, radius: Float) {
        drawTexturedImage(bounds, image, radius, phase)
    }

    override fun drawCroppedSquareImage(bounds: LayoutBounds, image: Texture, radius: Float) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val uInset = (1f - minOf(1f, bounds.width / bounds.height)) / 2f
        val vInset = (1f - minOf(1f, bounds.height / bounds.width)) / 2f
        drawTexturedImage(bounds, image, radius, uInset = uInset, vInset = vInset, captionShade = 0.78f)
    }

    private fun drawTexturedImage(
        bounds: LayoutBounds,
        image: Texture?,
        radius: Float,
        phase: Float = -1f,
        uInset: Float = 0f,
        vInset: Float = 0f,
        captionShade: Float = 0f,
        occluders: List<LayoutBounds> = emptyList(),
        brightness: Float = 1f,
        horizontalBlur: Float = 0f,
    ) {
        imageShader.bind()
        imageShader.setup(
            getTransform(),
            screenSize(),
            getOpacity(),
            bounds,
            radius,
            phase,
            captionShade,
            occluders,
            brightness,
            image == null,
            horizontalBlur,
        )
        Gl.activeTexture(GL13.GL_TEXTURE0)
        if (image != null) (image as ModernTexture).bind()

        Imm.begin()
        Imm.tex(uInset, vInset); Imm.vertex(bounds.x, bounds.y)
        Imm.tex(uInset, 1f - vInset); Imm.vertex(bounds.x, bounds.bottom)
        Imm.tex(1f - uInset, 1f - vInset); Imm.vertex(bounds.right, bounds.bottom)
        Imm.tex(1f - uInset, vInset); Imm.vertex(bounds.right, bounds.y)
        Imm.end()

        imageShader.unbind()
    }

    override fun drawIcon(bounds: LayoutBounds, icon: VectorIcon, color: Color) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        iconShader.bind()
        iconShader.setup(getTransform(), screenSize(), bounds, icon, color, getOpacity())
        Imm.begin()
        Imm.vertex(bounds.x - 1f, bounds.y - 1f)
        Imm.vertex(bounds.x - 1f, bounds.bottom + 1f)
        Imm.vertex(bounds.right + 1f, bounds.bottom + 1f)
        Imm.vertex(bounds.right + 1f, bounds.y - 1f)
        Imm.end()
        iconShader.unbind()
    }

    override fun drawGlass(bounds: LayoutBounds, style: GlassStyle) {
        if (style.shadow.size > 0f) {
            drawShape(bounds, ShapeStyle(Color.TRANSPARENT, style.radius, shadow = style.shadow))
        }

        val backdrop = this.backdrop
        if (backdrop == null || !backdrop.ready) {
            val fill = if (style.edgeWidth > 0f) Color.TRANSPARENT else style.fallback
            drawShape(bounds, ShapeStyle(fill, style.radius, 1f, Color(1f, 1f, 1f, 0.10f)))
            return
        }

        glassShader.bind()
        glassShader.setup(
            getTransform(),
            screenSize(),
            bounds,
            style,
            getOpacity(),
        )

        Gl.activeTexture(GL13.GL_TEXTURE1)
        Gl.bindTexture(backdrop.frostTexture)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        Gl.bindTexture(backdrop.softTexture)

        val x1 = bounds.x - 1f
        val y1 = bounds.y - 1f
        val x2 = bounds.right + 1f
        val y2 = bounds.bottom + 1f

        Imm.begin()
        Imm.vertex(x1, y1)
        Imm.vertex(x1, y2)
        Imm.vertex(x2, y2)
        Imm.vertex(x2, y1)
        Imm.end()

        Gl.activeTexture(GL13.GL_TEXTURE1)
        Gl.bindTexture(0)
        Gl.activeTexture(GL13.GL_TEXTURE0)

        glassShader.unbind()
    }

    override fun pushClip(bounds: LayoutBounds) {
        val transformed = transformBounds(bounds)
        val clip = if (clipStack.isEmpty()) transformed else clipStack.last().intersect(transformed)
        clipStack.addLast(clip)
        Gl.scissor(true)
        applyScissor(clip)
    }

    override fun popClip() {
        if (clipStack.isEmpty()) return
        clipStack.removeLast()
        if (clipStack.isEmpty()) {
            Gl.scissor(false)
        } else {
            applyScissor(clipStack.last())
        }
    }

    override fun pushOpacity(value: Float) {
        opacityStack.addLast(getOpacity() * value.coerceIn(0f, 1f))
    }

    override fun popOpacity() {
        opacityStack.removeLastOrNull()
    }

    override fun getOpacity(): Float = opacityStack.lastOrNull() ?: 1f

    private fun pushTransform(transform: Matrix4f) {
        transformStack.addLast(Matrix4f(getTransform()).mul(transform))
    }

    override fun popTransform() {
        transformStack.removeLastOrNull()
    }

    private fun getTransform(): Matrix4f = transformStack.lastOrNull() ?: Matrix4f()

    override fun pushTranslate(x: Float, y: Float, z: Float) {
        pushTransform(Matrix4f().translation(x, y, z))
    }

    override fun pushScale(x: Float, y: Float, z: Float) {
        pushTransform(Matrix4f().scaling(x, y, z))
    }

    override fun pushRotate(angle: Float, x: Float, y: Float, z: Float) {
        pushTransform(Matrix4f().rotation(Math.toRadians(angle.toDouble()).toFloat(), x, y, z))
    }

    private fun applyScissor(bounds: LayoutBounds) {
        Gl.scissorBox(
            bounds.x.toInt(),
            (Minecraft.getInstance().window.height - bounds.y - bounds.height).toInt(),
            bounds.width.toInt(),
            bounds.height.toInt(),
        )
    }

    private fun transformBounds(bounds: LayoutBounds): LayoutBounds {
        val matrix = getTransform()
        val a = Vector4f(bounds.x, bounds.y, 0f, 1f)
        val b = Vector4f(bounds.right, bounds.bottom, 0f, 1f)
        matrix.transform(a)
        matrix.transform(b)
        val left = minOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        return LayoutBounds(left, top, maxOf(a.x, b.x) - left, maxOf(a.y, b.y) - top)
    }
}
