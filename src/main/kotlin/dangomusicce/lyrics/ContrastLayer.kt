package dangomusicce.lyrics

import dangomusicce.DangoLogger
import dangomusicce.gfx.ContrastPass
import dangomusicce.platform.Gl
import dangomusicce.platform.GlState
import dangomusicce.platform.Imm
import dangomusicce.platform.McCompat
import dangomusicce.platform.ModernGfx
import dangomusicce.ui.glass.GlassBackdrop
import dangomusicce.ui.glass.GlassTarget
import net.minecraft.client.Minecraft
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

/**
 * 3D 世界歌词近似色自动反色（OpenGL 3.2 core，GLSL 150）。
 */
class ContrastLayer : ContrastPass {

    private companion object {
        const val VERT = """
#version 150
in vec2 a_Pos;
in vec2 a_UV;
void main() {
    gl_Position = vec4(a_Pos, 0.0, 1.0);
}
"""

        const val COMPOSITE = """
#version 150
out vec4 fragColor;
uniform sampler2D mask;
uniform sampler2D scene;
uniform vec2 screen;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec2 uv = gl_FragCoord.xy / screen;
    vec4 m = texture(mask, uv);
    if (m.a < 0.003) discard;
    vec3 c = clamp(m.rgb / m.a, 0.0, 1.0);
    vec3 bg = texture(scene, uv).rgb;
    float lc = luma(c);
    float d = max(length(c - bg) * 0.7, abs(lc - luma(bg)) * 1.6);
    float sim = 1.0 - smoothstep(0.18, 0.42, d);
    sim *= smoothstep(0.08, 0.16, lc);
    vec3 target = luma(bg) > 0.45 ? c * 0.22 : mix(c, vec3(1.0), 0.75);
    vec3 outc = mix(c, target, sim);
    fragColor = vec4(outc * m.a, m.a);
}
"""
    }

    private var program = 0
    private var locMask = -1
    private var locScene = -1
    private var locScreen = -1
    private var failed = false

    private val backdrop by lazy { GlassBackdrop() }
    private val mask = GlassTarget()

    private var savedDrawFbo = 0
    private var savedReadFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var premultiplied = false
    private var active = false

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(id, 4096)
            GL20.glDeleteShader(id)
            error("Contrast shader compile failed: $log")
        }
        return id
    }

    private fun usable(): Boolean {
        if (failed) return false
        if (program != 0) return true
        return try {
            val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
            val fs = compile(GL20.GL_FRAGMENT_SHADER, COMPOSITE)
            val p = GL20.glCreateProgram()
            GL20.glAttachShader(p, vs)
            GL20.glAttachShader(p, fs)
            GL20.glBindAttribLocation(p, Imm.ATTR_POS, "a_Pos")
            GL20.glBindAttribLocation(p, Imm.ATTR_UV, "a_UV")
            GL20.glLinkProgram(p)
            GL20.glDeleteShader(vs)
            GL20.glDeleteShader(fs)
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                val log = GL20.glGetProgramInfoLog(p, 4096)
                GL20.glDeleteProgram(p)
                error("Contrast shader link failed: $log")
            }
            program = p
            locMask = GL20.glGetUniformLocation(p, "mask")
            locScene = GL20.glGetUniformLocation(p, "scene")
            locScreen = GL20.glGetUniformLocation(p, "screen")
            true
        } catch (e: Throwable) {
            DangoLogger.warn("Auto contrast disabled: {}", e.toString())
            failed = true
            false
        }
    }

    override fun begin(): Boolean {
        if (active || !usable()) return false
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return false
        Imm.flush()
        Gl.forget()
        savedDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        savedViewport.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport)
        premultiplied = Gl.premultipliedTarget
        return try {
            backdrop.capture(frost = false)
            val prevTex = Gl.currentTexture()
            mask.ensure(w, h)
            Gl.bindTexture(prevTex)
            McCompat.bindFramebuffer(GL30.GL_FRAMEBUFFER, mask.fbo)
            Gl.viewport(0, 0, mask.width, mask.height)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            savedClear.clear()
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClear)
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3))
            ModernGfx.accumulateAlpha = true
            active = true
            true
        } catch (t: Throwable) {
            DangoLogger.warn("Auto contrast disabled after error: {}", t.toString())
            failed = true
            ModernGfx.accumulateAlpha = false
            Gl.forget()
            McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFbo)
            McCompat.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo)
            restoreViewport()
            false
        }
    }

    override fun end() {
        if (!active) return
        active = false
        // 切回原帧缓冲之前先把攒着的字形和原版像素字刷进 mask FBO
        Imm.flush()
        ModernGfx.accumulateAlpha = false
        McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFbo)
        McCompat.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedReadFbo)
        restoreViewport()
        val state = GlState()
        try {
            Gl.depthTest(false)
            Gl.depthMask(false)
            Gl.cull(false)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
            Gl.blend(true)
            if (premultiplied) {
                Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
            } else {
                Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
            }
            GL20.glUseProgram(program)
            GL20.glUniform1i(locMask, 0)
            GL20.glUniform1i(locScene, 1)
            GL20.glUniform2f(locScreen, mask.width.toFloat(), mask.height.toFloat())
            Gl.activeTexture(GL13.GL_TEXTURE1)
            Gl.bindTexture(backdrop.softTexture)
            Gl.activeTexture(GL13.GL_TEXTURE0)
            Gl.bindTexture(mask.texture)

            Imm.begin()
            Imm.tex(0f, 0f); Imm.vertex(-1f, -1f)
            Imm.tex(1f, 0f); Imm.vertex(1f, -1f)
            Imm.tex(1f, 1f); Imm.vertex(1f, 1f)
            Imm.tex(0f, 1f); Imm.vertex(-1f, 1f)
            Imm.end()
        } catch (t: Throwable) {
            DangoLogger.warn("Auto contrast disabled after error: {}", t.toString())
            failed = true
        } finally {
            Imm.flush()
            state.restore()
            Gl.forget()
        }
    }

    private fun restoreViewport() {
        Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }
}
