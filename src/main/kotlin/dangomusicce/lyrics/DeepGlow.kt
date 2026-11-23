package dangomusicce.lyrics

import dangomusicce.DangoLogger
import dangomusicce.gfx.GlowPass
import dangomusicce.platform.Gl
import dangomusicce.platform.GlState
import dangomusicce.platform.Imm
import dangomusicce.platform.McCompat
import dangomusicce.platform.Perf
import net.minecraft.client.Minecraft
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * AE Deep Glow 风格的多级离屏发光通道（OpenGL 3.2 core，GLSL 150）。
 */
class DeepGlow : GlowPass {

    private companion object {
        const val LEVELS = 7
        const val GL_RGBA16F = 0x881A

        const val VERT = """
#version 150
in vec2 a_Pos;
in vec2 a_UV;
out vec2 v_UV;
void main() {
    v_UV = a_UV;
    gl_Position = vec4(a_Pos, 0.0, 1.0);
}
"""

        const val DOWN = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
uniform vec2 texel;
void main() {
    vec2 uv = v_UV;
    vec4 c = texture(tex, uv) * 4.0;
    c += texture(tex, uv - texel);
    c += texture(tex, uv + texel);
    c += texture(tex, uv + vec2(texel.x, -texel.y));
    c += texture(tex, uv + vec2(-texel.x, texel.y));
    fragColor = c / 8.0;
}
"""

        const val UP = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
uniform vec2 texel;
uniform float intensity;
void main() {
    vec2 uv = v_UV;
    vec4 s = texture(tex, uv + vec2(-2.0 * texel.x, 0.0));
    s += texture(tex, uv + vec2(2.0 * texel.x, 0.0));
    s += texture(tex, uv + vec2(0.0, -2.0 * texel.y));
    s += texture(tex, uv + vec2(0.0, 2.0 * texel.y));
    s += texture(tex, uv + vec2(-texel.x, texel.y)) * 2.0;
    s += texture(tex, uv + vec2(texel.x, texel.y)) * 2.0;
    s += texture(tex, uv + vec2(texel.x, -texel.y)) * 2.0;
    s += texture(tex, uv + vec2(-texel.x, -texel.y)) * 2.0;
    fragColor = s / 12.0 * intensity;
}
"""

        const val COMPOSITE = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
uniform vec3 tint;
uniform float exposure;
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec4 g = texture(tex, v_UV);
    vec3 energy = g.rgb * tint * exposure;
    vec3 c = vec3(1.0) - exp(-energy);
    c = floor(c * 255.0 + rand(gl_FragCoord.xy)) / 255.0;
    fragColor = vec4(c, max(c.r, max(c.g, c.b)));
}
"""
    }

    private var progDown = 0
    private var progUp = 0
    private var progComp = 0
    private var failed = false

    private var width = 0
    private var height = 0
    private var maskTex = 0
    private var maskFbo = 0
    private val levelTex = IntArray(LEVELS)
    private val levelFbo = IntArray(LEVELS)
    private val levelW = IntArray(LEVELS)
    private val levelH = IntArray(LEVELS)

    private var savedFbo = 0
    private var savedReadFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var active = false

    override val available: Boolean get() = !failed

    override fun disable(reason: Throwable) {
        if (!failed) DangoLogger.warn("DeepGlow disabled after error: {}", reason.toString())
        failed = true
        abort()
    }

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(id, 4096)
            GL20.glDeleteShader(id)
            error("DeepGlow shader compile failed: $log")
        }
        return id
    }

    private fun program(frag: String): Int {
        val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GL20.GL_FRAGMENT_SHADER, frag)
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
            error("DeepGlow shader link failed: $log")
        }
        return p
    }

    private fun ensurePrograms(): Boolean {
        if (failed) return false
        if (progComp != 0) return true
        return try {
            progDown = program(DOWN)
            progUp = program(UP)
            progComp = program(COMPOSITE)
            true
        } catch (e: Throwable) {
            DangoLogger.warn("DeepGlow disabled: {}", e.toString())
            failed = true
            false
        }
    }

    private fun newTexture(w: Int, h: Int, hdr: Boolean): Int {
        val id = Gl.genTexture()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        val internal = if (hdr) GL_RGBA16F else GL11.GL_RGBA8
        Gl.withDefaultUnpack {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        }
        return id
    }

    private fun newFbo(tex: Int): Int {
        val prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0)
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        restoreFramebuffers(prevDraw, prevRead)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(fbo)
            error("DeepGlow framebuffer incomplete: 0x${status.toString(16)}")
        }
        return fbo
    }

    private fun restoreFramebuffers(draw: Int, read: Int) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
    }

    private fun release() {
        if (maskTex != 0) Gl.deleteTexture(maskTex)
        if (maskFbo != 0) GL30.glDeleteFramebuffers(maskFbo)
        for (i in 0 until LEVELS) {
            if (levelTex[i] != 0) Gl.deleteTexture(levelTex[i])
            if (levelFbo[i] != 0) GL30.glDeleteFramebuffers(levelFbo[i])
            levelTex[i] = 0
            levelFbo[i] = 0
        }
        maskTex = 0
        maskFbo = 0
        width = 0
        height = 0
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (w == width && h == height && maskFbo != 0) return
        release()
        try {
            width = w
            height = h
            maskTex = newTexture(w, h, false)
            maskFbo = newFbo(maskTex)
            var lw = w
            var lh = h
            for (i in 0 until LEVELS) {
                lw = max(1, lw / 2)
                lh = max(1, lh / 2)
                levelW[i] = lw
                levelH[i] = lh
                levelTex[i] = newTexture(lw, lh, true)
                levelFbo[i] = newFbo(levelTex[i])
            }
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    override fun begin(): Boolean {
        Imm.flush()
        Gl.forget()
        if (!ensurePrograms()) return false
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return false

        savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        savedViewport.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport)

        val prevTex = Gl.currentTexture()
        ensureTargets(w, h)
        Gl.bindTexture(prevTex)

        McCompat.bindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo)
        Gl.viewport(0, 0, w, h)
        Gl.scissor(false)
        Gl.colorMask(true, true, true, true)
        savedClear.clear()
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClear)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glClearColor(savedClear.get(0), savedClear.get(1), savedClear.get(2), savedClear.get(3))
        active = true
        return true
    }

    override fun end(r: Float, g: Float, b: Float, exposure: Float, spread: Float) {
        if (!active) return
        Perf.begin("glow")
        try {
            endNow(r, g, b, exposure, spread)
        } finally {
            Perf.end("glow")
        }
    }

    private fun endNow(r: Float, g: Float, b: Float, exposure: Float, spread: Float) {
        Imm.flush()
        active = false

        restoreFramebuffers(savedFbo, savedReadFbo)
        Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
        val state = GlState()
        try {
            Gl.depthTest(false)
            Gl.depthMask(false)
            Gl.cull(false)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
            Gl.activeTexture(GL13.GL_TEXTURE0)

            Gl.blend(false)
            GL20.glUseProgram(progDown)
            GL20.glUniform1i(GL20.glGetUniformLocation(progDown, "tex"), 0)
            val texelDown = GL20.glGetUniformLocation(progDown, "texel")
            var srcTex = maskTex
            var srcW = width
            var srcH = height
            for (i in 0 until LEVELS) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levelFbo[i])
                Gl.viewport(0, 0, levelW[i], levelH[i])
                Gl.bindTexture(srcTex)
                GL20.glUniform2f(texelDown, 1f / srcW, 1f / srcH)
                quad()
                srcTex = levelTex[i]
                srcW = levelW[i]
                srcH = levelH[i]
            }

            Gl.blend(true)
            Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE)
            GL20.glUseProgram(progUp)
            GL20.glUniform1i(GL20.glGetUniformLocation(progUp, "tex"), 0)
            val texelUp = GL20.glGetUniformLocation(progUp, "texel")
            GL20.glUniform1f(GL20.glGetUniformLocation(progUp, "intensity"), 0.55f + spread.coerceIn(0f, 1f) * 0.6f)
            for (i in LEVELS - 2 downTo 0) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levelFbo[i])
                Gl.viewport(0, 0, levelW[i], levelH[i])
                Gl.bindTexture(levelTex[i + 1])
                GL20.glUniform2f(texelUp, 1f / levelW[i + 1], 1f / levelH[i + 1])
                quad()
            }

            McCompat.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedFbo)
            Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
            GL20.glUseProgram(progComp)
            GL20.glUniform1i(GL20.glGetUniformLocation(progComp, "tex"), 0)
            GL20.glUniform3f(GL20.glGetUniformLocation(progComp, "tint"), r, g, b)
            GL20.glUniform1f(GL20.glGetUniformLocation(progComp, "exposure"), exposure)
            Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE)
            Gl.bindTexture(levelTex[0])
            quad()
        } finally {
            state.restore()
        }
    }

    fun abort() {
        Imm.flush()
        Gl.forget()
        if (!active) return
        active = false
        restoreFramebuffers(savedFbo, savedReadFbo)
        Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }

    private fun quad() {
        Imm.begin()
        Imm.tex(0f, 0f); Imm.vertex(-1f, -1f)
        Imm.tex(1f, 0f); Imm.vertex(1f, -1f)
        Imm.tex(1f, 1f); Imm.vertex(1f, 1f)
        Imm.tex(0f, 1f); Imm.vertex(-1f, 1f)
        Imm.end()
    }
}
