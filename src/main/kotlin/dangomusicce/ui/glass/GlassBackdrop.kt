package dangomusicce.ui.glass

import dangomusicce.platform.Gl
import dangomusicce.platform.GlState
import dangomusicce.platform.Imm
import dangomusicce.platform.ModernGl
import dangomusicce.platform.Perf
import dangomusicce.shader.impls.QuadShader
import dangomusicce.shader.impls.ui.GlassBlurShader
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

/**
 * 液态玻璃的"背后世界"：每帧把当前画面拷贝下来，生成两级模糊纹理。
 *  - soft ：1/4 分辨率轻度模糊（清透玻璃、折射边缘）
 *  - frost：1/8 分辨率重度模糊（磨砂玻璃）
 */
class GlassBackdrop {
    private val quadShader = ModernGl.shaderManager.get<QuadShader>()
    private val blurShader = ModernGl.shaderManager.get<GlassBlurShader>()

    private var captureTexture = -1
    private var width = 0
    private var height = 0

    private val half = GlassTarget()
    private val softA = GlassTarget()
    private val softB = GlassTarget()
    private val frostA = GlassTarget()
    private val frostB = GlassTarget()

    val softTexture: Int get() = softA.texture
    val frostTexture: Int get() = frostA.texture
    val sharpTexture: Int get() = captureTexture

    var ready = false
        private set

    fun capture(fromDrawTarget: Boolean = false, frost: Boolean = true) {
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        Imm.flush()
        Perf.begin("glass")
        try {
            captureNow(w, h, fromDrawTarget, frost)
        } finally {
            Perf.end("glass")
        }
    }

    private fun captureNow(w: Int, h: Int, fromDrawTarget: Boolean, frost: Boolean) {
        val state = GlState()
        val sourceFbo = when {
            fromDrawTarget -> GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
            mainSource > 0 -> mainSource
            else -> GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        }
        try {
            Gl.activeTexture(GL13.GL_TEXTURE0)
            if (w != width || h != height || captureTexture == -1) resize(w, h)

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo)

            Gl.activeTexture(GL13.GL_TEXTURE0)
            Gl.bindTexture(captureTexture)
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)

            Gl.blend(false)
            Gl.depthTest(false)
            Gl.scissor(false)
            Gl.cull(false)
            Gl.colorMask(true, true, true, true)

            blit(captureTexture, half)
            blit(half.texture, softA)
            blur(softA, softB, 1f, 2)

            if (frost) {
                blit(softA.texture, frostA)
                blur(frostA, frostB, 1.6f, 3)
            }

            ready = true
        } finally {
            state.restore()
        }
    }

    private fun resize(w: Int, h: Int) {
        width = w
        height = h

        if (captureTexture != -1) Gl.deleteTexture(captureTexture)
        captureTexture = GlassTarget.createTexture(w, h)

        half.ensure(w / 2, h / 2)
        softA.ensure(w / 4, h / 4)
        softB.ensure(w / 4, h / 4)
        frostA.ensure(w / 8, h / 8)
        frostB.ensure(w / 8, h / 8)
    }

    private fun blit(source: Int, target: GlassTarget) {
        target.bind()
        quadShader.bind()
        quadShader.setup()
        Gl.bindTexture(source)
        fullscreenQuad()
        quadShader.unbind()
    }

    private fun blur(a: GlassTarget, b: GlassTarget, spread: Float, iterations: Int) {
        blurShader.bind()
        repeat(iterations) {
            b.bind()
            blurShader.setup(spread / a.width, 0f)
            Gl.bindTexture(a.texture)
            fullscreenQuad()

            a.bind()
            blurShader.setup(0f, spread / b.height)
            Gl.bindTexture(b.texture)
            fullscreenQuad()
        }
        blurShader.unbind()
    }

    private fun fullscreenQuad() {
        Imm.begin()
        Imm.tex(0f, 0f); Imm.vertex(-1f, -1f)
        Imm.tex(1f, 0f); Imm.vertex(1f, -1f)
        Imm.tex(1f, 1f); Imm.vertex(1f, 1f)
        Imm.tex(0f, 1f); Imm.vertex(-1f, 1f)
        Imm.end()
    }

    fun delete() {
        if (captureTexture != -1) Gl.deleteTexture(captureTexture)
        captureTexture = -1
        listOf(half, softA, softB, frostA, frostB).forEach { it.delete() }
        width = 0
        height = 0
        ready = false
    }

    companion object {
        @JvmField
        var mainSource = 0
    }
}

/** 线性过滤 + 边缘钳制的颜色 FBO。 */
class GlassTarget(private val internalFormat: Int = GL11.GL_RGBA8) {
    var fbo = -1
        private set
    var texture = -1
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(w: Int, h: Int) {
        val nw = w.coerceAtLeast(1)
        val nh = h.coerceAtLeast(1)
        if (nw == width && nh == height && fbo != -1) return
        delete()
        width = nw
        height = nh

        texture = createTexture(nw, nh, internalFormat)
        fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            texture,
            0,
        )
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            delete()
            throw RuntimeException("Glass framebuffer create failed: $status")
        }
    }

    fun bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        Gl.viewport(0, 0, width, height)
    }

    fun delete() {
        if (fbo != -1) GL30.glDeleteFramebuffers(fbo)
        if (texture != -1) Gl.deleteTexture(texture)
        fbo = -1
        texture = -1
        width = 0
        height = 0
    }

    companion object {
        fun createTexture(w: Int, h: Int, internalFormat: Int = GL11.GL_RGBA8): Int {
            val id = Gl.genTexture()
            Gl.withDefaultUnpack {
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    internalFormat,
                    w,
                    h,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    null as ByteBuffer?,
                )
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            return id
        }
    }
}
