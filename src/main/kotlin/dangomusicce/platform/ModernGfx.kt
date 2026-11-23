package dangomusicce.platform

import dangomusicce.font.CjkFont
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
import dangomusicce.gfx.WorldLyricCamera
import dangomusicce.gfx.WorldView
import dangomusicce.lyrics.ContrastLayer
import dangomusicce.lyrics.DeepGlow
import dangomusicce.lyrics.GlassText
import dangomusicce.ui.GlassRenderContext
import dangomusicce.ui.LayoutBounds
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import java.awt.Font
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 高版本的绘图实现：OpenGL 3.2 core（着色器 + [Imm]）+ 原版 Font / TextureManager。 */
object ModernGfx : Gfx {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** ContrastLayer 开启期间，世界绘制的 alpha 改用叠加避免覆盖。 */
    @Volatile
    internal var accumulateAlpha = false

    override val textures: Textures = ModernTextures

    override fun newCanvas(): GuiCanvas = GlassRenderContext()

    override fun uiTextWidth(text: String, size: Float, font: String): Float =
        ModernGl.font(font).getTextWidth(text, size)

    override fun cjkWidth(text: String, size: Float, style: Int): Float = CjkFont.width(text, size, style)

    override fun cjkPreload(c: Char, style: Int) {
        CjkFont.glyph(c, style)
    }

    override fun drawCjkRuns(runs: List<CjkRun>, scale: Float) {
        if (runs.isEmpty()) return
        val k = scale
        val window = mc.window
        val state = GlState()
        val projection = Projection.screen(window.width.toFloat(), window.height.toFloat())
        try {
            Fixed.reset(Matrix4f().translation(0f, 0f, -2000f).scale(k, k, 1f))
            textState()
            for (r in runs) {
                val c = r.clip
                if (c != null) {
                    Gl.scissor(true)
                    Gl.scissorBox(
                        (c.x * k).toInt(),
                        (window.height - c.bottom * k).toInt(),
                        max(0, (c.width * k).toInt()),
                        max(0, (c.height * k).toInt()),
                    )
                } else {
                    Gl.scissor(false)
                }
                Fixed.color(r.r, r.g, r.b, r.a.coerceIn(0f, 1f))
                if (r.centered) CjkFont.drawCentered(r.s, r.x, r.y, r.size, r.style)
                else CjkFont.draw(r.s, r.x, r.y, r.size, r.style)
            }
        } finally {
            Fixed.color(1f, 1f, 1f, 1f)
            projection.restore()
            state.restore()
        }
    }

    override fun guarded(block: () -> Unit) {
        val state = GlState()
        Fixed.reset()
        Fixed.color(1f, 1f, 1f, 1f)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        try {
            block()
        } finally {
            VanillaTextBatch.flush()
            VanillaTextBatch.enabled = false
            Fixed.reset()
            state.restore()
        }
    }

    override val text: TextCanvas = ModernText
    override val world: WorldView = ModernWorld
    override val hud: HudCanvas = ModernHud

    override val glow: GlowPass = DeepGlow()
    override val glassText: GlassTextPass = GlassText()
    override val contrast: ContrastPass = ContrastLayer()

    internal fun textState() {
        Gl.blend(true)
        Gl.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        Gl.depthTest(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    internal fun vanillaText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean, pose: Matrix4f) {
        if (shadow) {
            val shadowColor = ((argb and 0xFCFCFC) shr 2) or (argb and 0xFF000000.toInt())
            drawVanilla(text, x + 1f, y + 1f, shadowColor, pose)
        }
        drawVanilla(text, x, y, argb, pose)
    }

    private fun drawVanilla(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        McCompat.drawText(text, x, y, argb, pose)
    }
}

/** 临时正交投影包装。 */
internal class Projection private constructor() {
    fun restore() {
        McCompat.popModelView()
        McCompat.popScreenProjection()
    }

    companion object {
        fun screen(w: Float, h: Float): Projection {
            McCompat.pushScreenProjection(Matrix4f().setOrtho(0f, w, h, 0f, 1000f, 3000f))
            McCompat.pushIdentityModelView()
            return Projection()
        }
    }
}

class ModernTexture(private val glId: () -> Int, internal val release: () -> Unit) : Texture {
    fun bind() {
        Gl.bindTexture(glId())
    }
}

object ModernTextures : Textures {
    override fun upload(name: String, image: BufferedImage, linear: Boolean): Texture {
        val w = image.width
        val h = image.height
        val abgr = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                abgr[y * w + x] = (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)
            }
        }
        return McCompat.createTexture(name, w, h, abgr, linear)
    }

    override fun delete(texture: Texture) {
        (texture as ModernTexture).release()
    }
}

object ModernText : TextCanvas {
    override fun push() = Fixed.push()
    override fun pop() = Fixed.pop()
    override fun translate(x: Float, y: Float) {
        Fixed.pose.translate(x, y, 0f)
    }

    override fun rotate(degrees: Float) {
        Fixed.pose.rotateZ(Math.toRadians(degrees.toDouble()).toFloat())
    }

    override fun scale(x: Float, y: Float) {
        Fixed.pose.scale(x, y, 1f)
    }

    override fun skewX(t: Float) {
        Fixed.pose.mul(Matrix4f().m10(-t))
    }

    override fun color(r: Float, g: Float, b: Float, a: Float) = Fixed.color(r, g, b, a)

    override fun font(style: Int, font: Font?, key: String?) = CjkFont.setFont(style, font, key)

    override fun smoothText(text: String, x: Float, baseline: Float, size: Float, style: Int) =
        CjkFont.draw(text, x, baseline, size, style)

    override fun smoothWidth(text: String, size: Float, style: Int): Float = CjkFont.width(text, size, style)

    override fun beginSmooth() {
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    override fun endSmooth() {
        Fixed.color(1f, 1f, 1f, 1f)
    }

    override fun pixelText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {
        if (Imm.hasBatch()) Imm.flushBatch()
        if (shadow) {
            val shadowColor = ((argb and 0xFCFCFC) shr 2) or (argb and 0xFF000000.toInt())
            val offset = if (text.all { it.code < 0x2E80 }) 1f else 0.5f
            if (VanillaTextBatch.enabled) VanillaTextBatch.add(text, x + offset, y + offset, shadowColor, Fixed.pose, shadow = true)
            else ModernGfx.vanillaText(text, x + offset, y + offset, shadowColor, false, Fixed.pose)
        }
        if (VanillaTextBatch.enabled) {
            VanillaTextBatch.add(text, x, y, argb, Fixed.pose, shadow = false)
            return
        }
        ModernGfx.vanillaText(text, x, y, argb, false, Fixed.pose)
        Gl.forget()
        reapply()
    }

    override fun pixelWidth(text: String): Float = Minecraft.getInstance().font.width(text).toFloat()

    override fun pixelCharWidth(c: Char): Float = Minecraft.getInstance().font.width(c.toString()).toFloat()

    internal var reapply: () -> Unit = {}

    private var screenState: GlState? = null
    private var screenProjection: Projection? = null

    override fun beginScreen() {
        VanillaTextBatch.flush()
        VanillaTextBatch.enabled = false
        screenState = GlState()
        val window = Minecraft.getInstance().window
        screenProjection = Projection.screen(window.width.toFloat(), window.height.toFloat())
        Fixed.push()
        Fixed.pose.identity().translate(0f, 0f, -2000f)
        ModernGfx.textState()
        reapply = { ModernGfx.textState() }
    }

    override fun endScreen() {
        Fixed.pop()
        Fixed.color(1f, 1f, 1f, 1f)
        screenProjection?.restore()
        screenProjection = null
        screenState?.restore()
        screenState = null
    }
}

internal class VanillaText(val text: String, val x: Float, val y: Float, val argb: Int, val pose: Matrix4f)

internal object VanillaTextBatch {
    var enabled = false

    private val shadows = ArrayList<VanillaText>()
    private val texts = ArrayList<VanillaText>()
    private var flushing = false

    fun hasPending(): Boolean = shadows.isNotEmpty() || texts.isNotEmpty()

    fun add(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f, shadow: Boolean) {
        if (text.isEmpty()) return
        (if (shadow) shadows else texts) += VanillaText(text, x, y, argb, Matrix4f(pose))
    }

    fun flush() {
        if (flushing || (shadows.isEmpty() && texts.isEmpty())) return
        flushing = true
        try {
            if (shadows.isNotEmpty()) McCompat.drawTexts(shadows)
            if (texts.isNotEmpty()) McCompat.drawTexts(texts)
        } finally {
            shadows.clear()
            texts.clear()
            flushing = false
        }
        Gl.forget()
        ModernText.reapply()
    }
}

object ModernWorld : WorldView {
    internal val basePose = Matrix4f()

    private val camera get() = McCompat.cameraPosition()
    override val cameraX: Double get() = camera.x
    override val cameraY: Double get() = camera.y
    override val cameraZ: Double get() = camera.z

    override fun cameraAngles(): ViewAngles = ViewAngles(McCompat.cameraYRot(), McCompat.cameraXRot())

    override fun lyricCamera(): WorldLyricCamera {
        // MC 1.21.1 bakes bobHurt/bobView/CONFUSION into projection (M = P * B); strip B back into view so place() gets diagonal P.
        val raw = McCompat.projection()
        val p00 = sqrt(raw.m00() * raw.m00() + raw.m10() * raw.m10() + raw.m20() * raw.m20())
        val p11 = sqrt(raw.m01() * raw.m01() + raw.m11() * raw.m11() + raw.m21() * raw.m21())
        val r2x = -raw.m03()
        val r2y = -raw.m13()
        val r2z = -raw.m23()
        val tz = -raw.m33()
        val r2Sq = r2x * r2x + r2y * r2y + r2z * r2z
        if (p00 > 1e-4f && p11 > 1e-4f && r2Sq > 1e-4f) {
            val a = (raw.m02() * r2x + raw.m12() * r2y + raw.m22() * r2z) / r2Sq
            val c = raw.m32() - a * tz
            val res = maxOf(
                abs(raw.m02() - a * r2x),
                abs(raw.m12() - a * r2y),
                abs(raw.m22() - a * r2z),
            )
            if (a.isFinite() && c.isFinite() && res <= 1e-3f && abs(c) > 1e-5f) {
                val p = Matrix4f().zero().m00(p00).m11(p11).m22(a).m32(c).m23(-1f)
                val b = Matrix4f()
                    .m00(raw.m00() / p00).m10(raw.m10() / p00).m20(raw.m20() / p00).m30(raw.m30() / p00)
                    .m01(raw.m01() / p11).m11(raw.m11() / p11).m21(raw.m21() / p11).m31(raw.m31() / p11)
                    .m02(r2x).m12(r2y).m22(r2z).m32(tz)
                    .m03(0f).m13(0f).m23(0f).m33(1f)
                val det = b.determinant()
                if (det.isFinite() && abs(det) > 1e-6f) {
                    return WorldLyricCamera(b.mul(basePose), p)
                }
            }
        }
        return WorldLyricCamera(Matrix4f(basePose), Matrix4f(raw))
    }

    private fun worldState() {
        Gl.blend(true)
        Gl.blendFunc(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            if (ModernGfx.accumulateAlpha) GL11.GL_ONE_MINUS_SRC_ALPHA else GL11.GL_ZERO,
        )
        Gl.depthTest(false)
        Gl.depthMask(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    override fun begin() {
        Fixed.reset(basePose)
        worldState()
        ModernText.reapply = { worldState() }
        VanillaTextBatch.enabled = McCompat.batchesText
    }

    override fun push(rx: Double, ry: Double, rz: Double, yaw: Float, pitch: Float, scale: Float) {
        Fixed.push()
        Fixed.pose.translate(rx.toFloat(), ry.toFloat(), rz.toFloat())
        Fixed.pose.rotateY(Math.toRadians(-yaw.toDouble()).toFloat())
        Fixed.pose.rotateX(Math.toRadians(pitch.toDouble()).toFloat())
        Fixed.pose.scale(-scale, -scale, scale)
    }

    override fun push(transform: Matrix4f) {
        Fixed.push()
        Fixed.pose.mul(transform)
    }

    override fun pop() = Fixed.pop()

    override fun resync() {
        Fixed.color(1f, 1f, 1f, 1f)
        worldState()
    }
}

object ModernHud : HudCanvas {
    private var state: GlState? = null
    private var projection: Projection? = null

    private fun hudState() {
        Gl.blend(true)
        Gl.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        Gl.depthTest(false)
        Gl.cull(false)
        Gl.activeTexture(GL13.GL_TEXTURE0)
    }

    override fun begin() {
        VanillaTextBatch.flush()
        VanillaTextBatch.enabled = false
        state = GlState()
        val window = Minecraft.getInstance().window
        projection = Projection.screen(window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat())
        Fixed.push()
        Fixed.pose.identity().translate(0f, 0f, -2000f)
        hudState()
        ModernText.reapply = { hudState() }
    }

    override fun end() {
        Fixed.pop()
        Fixed.color(1f, 1f, 1f, 1f)
        projection?.restore()
        projection = null
        state?.restore()
        state = null
    }

    override fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        val a = (argb ushr 24 and 255) / 255f
        val r = (argb shr 16 and 255) / 255f
        val g = (argb shr 8 and 255) / 255f
        val b = (argb and 255) / 255f
        Fixed.color(r, g, b, a)
        val l = minOf(x1, x2).toFloat()
        val t = minOf(y1, y2).toFloat()
        val rr = maxOf(x1, x2).toFloat()
        val bb = maxOf(y1, y2).toFloat()
        Imm.begin(textured = false)
        Imm.vertex(l, t)
        Imm.vertex(l, bb)
        Imm.vertex(rr, bb)
        Imm.vertex(rr, t)
        Imm.endFixed()
        Fixed.color(1f, 1f, 1f, 1f)
    }

    override fun texture(texture: Texture, x: Int, y: Int, width: Int, height: Int, alpha: Float) {
        (texture as ModernTexture).bind()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        Fixed.color(1f, 1f, 1f, alpha)
        val l = x.toFloat()
        val t = y.toFloat()
        val r = (x + width).toFloat()
        val b = (y + height).toFloat()
        Imm.begin()
        Imm.tex(0f, 0f); Imm.vertex(l, t)
        Imm.tex(0f, 1f); Imm.vertex(l, b)
        Imm.tex(1f, 1f); Imm.vertex(r, b)
        Imm.tex(1f, 0f); Imm.vertex(r, t)
        Imm.endFixed()
        Fixed.color(1f, 1f, 1f, 1f)
    }

    override fun text(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {
        if (Imm.hasBatch()) Imm.flushBatch()
        ModernGfx.vanillaText(text, x, y, argb, shadow, Fixed.pose)
        Gl.forget()
        hudState()
    }
}
