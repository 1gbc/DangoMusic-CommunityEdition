package dangomusicce.lyrics

import dangomusicce.DangoLogger
import dangomusicce.gfx.GlassTextPass
import dangomusicce.gfx.GlassTextStyle
import dangomusicce.gfx.GlassTextView
import dangomusicce.platform.Fixed
import dangomusicce.platform.Gl
import dangomusicce.platform.GlState
import dangomusicce.platform.Imm
import dangomusicce.platform.McCompat
import dangomusicce.platform.ModernGfx
import dangomusicce.platform.ModernText
import dangomusicce.platform.VanillaTextBatch
import dangomusicce.ui.glass.GlassBackdrop
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
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
 * 驾驶舱玻璃字（OpenGL 3.2 core，GLSL 150）：支持透视倾斜、折射、内部发光、光晕及近似色自动反色。
 */
class GlassText : GlassTextPass {

    private companion object {
        const val LEVELS = 6
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

        const val COPY = """
#version 150
in vec2 v_UV;
out vec4 fragColor;
uniform sampler2D tex;
void main() {
    fragColor = texture(tex, v_UV);
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
out vec4 fragColor;
uniform sampler2D mask;
uniform sampler2D d0;
uniform sampler2D d1;
uniform sampler2D halo;
uniform sampler2D sharp;
uniform sampler2D soft;
uniform vec2 screen;
uniform vec2 t0;
uniform vec2 t1;
uniform float opacity;
uniform float refraction;
uniform float emission;
uniform float haloAmt;
uniform float frost;
uniform float solid;
uniform float tintText;
uniform float brightness;
uniform float contrast;
uniform float premul;
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
vec3 separate(vec3 col, vec3 bg) {
    float d = max(length(col - bg) * 0.7, abs(luma(col) - luma(bg)) * 1.6);
    float sim = 1.0 - smoothstep(0.18, 0.42, d);
    vec3 target = luma(bg) > 0.45 ? col * 0.22 : mix(col, vec3(1.0), 0.75);
    return mix(col, target, sim);
}
void main() {
    vec2 uv = gl_FragCoord.xy / screen;
    vec4 m = texture(mask, uv);
    float cov = m.a;
    vec3 energy = texture(halo, uv).rgb * haloAmt;
    if (cov < 0.003 && energy.r + energy.g + energy.b < 0.003) discard;
    vec3 g = vec3(1.0) - exp(-energy);
    g = floor(g * 255.0 + rand(gl_FragCoord.xy)) / 255.0;
    float gMax = max(g.r, max(g.g, g.b));
    if (cov < 0.003) {
        fragColor = vec4(g, premul > 0.5 ? gMax : 0.0);
        return;
    }

    float hx = (texture(d0, uv + vec2(t0.x, 0.0)).a - texture(d0, uv - vec2(t0.x, 0.0)).a) * 0.6
             + (texture(d1, uv + vec2(t1.x, 0.0)).a - texture(d1, uv - vec2(t1.x, 0.0)).a) * 0.4;
    float hy = (texture(d0, uv + vec2(0.0, t0.y)).a - texture(d0, uv - vec2(0.0, t0.y)).a) * 0.6
             + (texture(d1, uv + vec2(0.0, t1.y)).a - texture(d1, uv - vec2(0.0, t1.y)).a) * 0.4;
    vec3 n = normalize(vec3(-hx * 2.4, -hy * 2.4, 1.0));
    vec3 c = clamp(m.rgb / max(cov, 0.02), 0.0, 1.0);
    float lit = max(c.r, max(c.g, c.b));
    vec3 hue = c / max(lit, 0.001);

    vec2 off = -n.xy * refraction / screen;
    vec3 sharpC = vec3(texture(sharp, uv + off * 1.25).r, texture(sharp, uv + off).g, texture(sharp, uv + off * 0.75).b);
    vec3 bg = mix(sharpC, texture(soft, uv + off).rgb, frost);
    float fres = pow(1.0 - n.z, 1.3);
    float spec = pow(max(dot(n, normalize(vec3(-0.5, 0.55, 0.68))), 0.0), 22.0);
    vec3 rim = mix(vec3(1.0), hue, 0.35) * (fres * 1.1 + spec * 0.7) * (0.45 + 0.55 * lit);
    vec3 inner = hue * emission * (0.2 + 0.8 * lit);
    vec3 lightC = (inner + rim) * brightness;
    float bgDim = 0.78;
    if (contrast > 0.5) {
        vec3 behind = texture(soft, uv).rgb;
        lightC = separate(lightC + bg * 0.3, behind) - bg * 0.3;
        bgDim = mix(0.78, 0.5, smoothstep(0.55, 0.85, luma(behind)));
    }
    vec3 glassC = bg * bgDim + max(lightC, vec3(0.0));
    vec3 solidC = mix(vec3(1.0), hue, tintText) * (0.35 + 0.65 * lit) * brightness + inner * 0.4;
    vec3 body = mix(glassC, solidC, solid);
    if (contrast > 0.5 && solid > 0.5) body = separate(body, texture(soft, uv).rgb);
    float a = cov * opacity;
    fragColor = vec4(body * a + g * (1.0 - a), premul > 0.5 ? a + (1.0 - a) * gMax : a);
}
"""
    }

    private var progDown = 0
    private var progCopy = 0
    private var progUp = 0
    private var progComp = 0
    private var failed = false

    private val backdrop by lazy { GlassBackdrop() }

    private var width = 0
    private var height = 0
    private var maskTex = 0
    private var maskFbo = 0
    private val downTex = IntArray(LEVELS)
    private val downFbo = IntArray(LEVELS)
    private val upTex = IntArray(LEVELS - 1)
    private val upFbo = IntArray(LEVELS - 1)
    private val levelW = IntArray(LEVELS)
    private val levelH = IntArray(LEVELS)

    private val uniforms = HashMap<String, Int>()

    private var savedFbo = 0
    private var savedReadFbo = 0
    private val savedViewport = BufferUtils.createIntBuffer(16)
    private val savedClear = BufferUtils.createFloatBuffer(16)
    private var state: GlState? = null
    private val savedPose = Matrix4f()
    private val savedColor = FloatArray(4)
    private var savedReapply: () -> Unit = {}
    private var savedBatch = false
    private var projected = false
    private var premultiplied = false

    /** 0 = 没在画，1 = 画进遮罩，2 = 直接画到屏幕回退 */
    private var mode = 0

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(id, 4096)
            GL20.glDeleteShader(id)
            error("Glass text shader compile failed: $log")
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
            error("Glass text shader link failed: $log")
        }
        return p
    }

    private fun usable(): Boolean {
        if (failed) return false
        if (progComp != 0) return true
        return try {
            progDown = program(DOWN)
            progCopy = program(COPY)
            progUp = program(UP)
            progComp = program(COMPOSITE)
            true
        } catch (e: Throwable) {
            DangoLogger.warn("Glass text disabled: {}", e.toString())
            failed = true
            false
        }
    }

    private fun disable(reason: Throwable) {
        if (!failed) DangoLogger.warn("Glass text disabled after error: {}", reason.toString())
        failed = true
    }

    private fun newTexture(w: Int, h: Int, hdr: Boolean): Int {
        val id = Gl.genTexture()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        Gl.withDefaultUnpack {
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                if (hdr) GL_RGBA16F else GL11.GL_RGBA8,
                w,
                h,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                null as ByteBuffer?,
            )
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
            error("GlassText framebuffer incomplete: 0x${status.toString(16)}")
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
        maskTex = 0
        maskFbo = 0
        for (i in 0 until LEVELS) {
            if (downTex[i] != 0) Gl.deleteTexture(downTex[i])
            if (downFbo[i] != 0) GL30.glDeleteFramebuffers(downFbo[i])
            downTex[i] = 0
            downFbo[i] = 0
        }
        for (i in 0 until LEVELS - 1) {
            if (upTex[i] != 0) Gl.deleteTexture(upTex[i])
            if (upFbo[i] != 0) GL30.glDeleteFramebuffers(upFbo[i])
            upTex[i] = 0
            upFbo[i] = 0
        }
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
                downTex[i] = newTexture(lw, lh, true)
                downFbo[i] = newFbo(downTex[i])
                if (i < LEVELS - 1) {
                    upTex[i] = newTexture(lw, lh, true)
                    upFbo[i] = newFbo(upTex[i])
                }
            }
        } catch (t: Throwable) {
            release()
            width = 0
            height = 0
            throw t
        }
    }

    override fun begin(view: GlassTextView): Boolean {
        if (mode != 0) return false
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return false
        Imm.flush()
        Gl.forget()
        state = GlState()
        savedPose.set(Fixed.pose)
        Fixed.color.copyInto(savedColor)
        savedReapply = ModernText.reapply
        savedBatch = VanillaTextBatch.enabled
        savedFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        savedReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        savedViewport.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport)
        premultiplied = Gl.premultipliedTarget
        mode = 2
        if (usable()) {
            try {
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
                mode = 1
            } catch (t: Throwable) {
                disable(t)
                restoreFramebuffers(savedFbo, savedReadFbo)
                restoreViewport()
            }
        }

        val d = h * GlassTextView.FOCAL
        val near = d * 0.25f
        val sx = w / 2f * near / d
        val sy = h / 2f * near / d
        McCompat.pushScreenProjection(Matrix4f().frustum(-sx, sx, -sy, sy, near, d * 4f))
        McCompat.pushIdentityModelView()
        projected = true
        Fixed.reset(
            Matrix4f()
                .translation(0f, 0f, -d)
                .scale(1f, -1f, 1f)
                .translate(-w / 2f, -h / 2f, 0f)
                .translate(view.pivotX, view.pivotY, 0f)
                .rotateY(Math.toRadians(view.yaw.toDouble()).toFloat())
                .rotateX(Math.toRadians(view.pitch.toDouble()).toFloat())
                .translate(-view.pivotX, -view.pivotY, 0f),
        )
        VanillaTextBatch.enabled = false
        ModernText.reapply = { ModernGfx.textState() }
        ModernGfx.textState()
        return true
    }

    override fun bar(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, alphaTop: Float, alphaBottom: Float) {
        if (mode == 0 || w <= 0f || h <= 0f) return
        val steps = 16
        val cr = r.coerceIn(0f, 1f)
        val cg = g.coerceIn(0f, 1f)
        val cb = b.coerceIn(0f, 1f)
        for (i in 0 until steps) {
            val t0 = i / steps.toFloat()
            val t1 = (i + 1) / steps.toFloat()
            val a = (alphaTop + (alphaBottom - alphaTop) * (t0 + t1) / 2f).coerceIn(0f, 1f)
            if (a <= 0.002f) continue
            Fixed.color(cr, cg, cb, a)
            Imm.begin(textured = false)
            Imm.vertex(x, y + h * t0)
            Imm.vertex(x + w, y + h * t0)
            Imm.vertex(x + w, y + h * t1)
            Imm.vertex(x, y + h * t1)
            Imm.endFixed()
        }
    }

    override fun end(style: GlassTextStyle) {
        val m = mode
        if (m == 0) return
        mode = 0
        try {
            try {
                Imm.flush()
            } finally {
                if (projected) {
                    projected = false
                    McCompat.popModelView()
                    McCompat.popScreenProjection()
                }
            }
            Fixed.reset(savedPose)
            Fixed.color(savedColor[0], savedColor[1], savedColor[2], savedColor[3])
            if (m == 1) composite(style)
        } catch (t: Throwable) {
            disable(t)
        } finally {
            Imm.flush()
            ModernText.reapply = savedReapply
            VanillaTextBatch.enabled = savedBatch
            if (m == 1) restoreFramebuffers(savedFbo, savedReadFbo)
            restoreViewport()
            state?.restore()
            state = null
            Gl.forget()
        }
    }

    private fun restoreViewport() {
        Gl.viewport(savedViewport.get(0), savedViewport.get(1), savedViewport.get(2), savedViewport.get(3))
    }

    private fun composite(s: GlassTextStyle) {
        val solid = s.solid || McCompat.offscreenGl
        val needBackdrop = (!solid || s.contrast) && !McCompat.offscreenGl
        if (needBackdrop) {
            restoreFramebuffers(savedFbo, savedReadFbo)
            restoreViewport()
            backdrop.capture(frost = false)
        }

        val savedUnits = IntArray(6)
        for (i in 1 until savedUnits.size) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
            savedUnits[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        Gl.forget()
        Gl.activeTexture(GL13.GL_TEXTURE0)
        try {
            Gl.depthTest(false)
            Gl.depthMask(false)
            Gl.cull(false)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
            Gl.blend(false)
            GL20.glUseProgram(progDown)
            GL20.glUniform1i(loc(progDown, "tex"), 0)
            var srcTex = maskTex
            var srcW = width
            var srcH = height
            for (i in 0 until LEVELS) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, downFbo[i])
                Gl.viewport(0, 0, levelW[i], levelH[i])
                Gl.bindTexture(srcTex)
                GL20.glUniform2f(loc(progDown, "texel"), 1f / srcW, 1f / srcH)
                quad()
                srcTex = downTex[i]
                srcW = levelW[i]
                srcH = levelH[i]
            }

            for (i in LEVELS - 2 downTo 0) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upFbo[i])
                Gl.viewport(0, 0, levelW[i], levelH[i])
                Gl.blend(false)
                GL20.glUseProgram(progCopy)
                GL20.glUniform1i(loc(progCopy, "tex"), 0)
                Gl.bindTexture(downTex[i])
                quad()

                val fromTex = if (i == LEVELS - 2) downTex[LEVELS - 1] else upTex[i + 1]
                Gl.blend(true)
                Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE)
                GL20.glUseProgram(progUp)
                GL20.glUniform1i(loc(progUp, "tex"), 0)
                GL20.glUniform2f(loc(progUp, "texel"), 1f / levelW[i + 1], 1f / levelH[i + 1])
                GL20.glUniform1f(loc(progUp, "intensity"), 0.8f)
                Gl.bindTexture(fromTex)
                quad()
            }

            restoreFramebuffers(savedFbo, savedReadFbo)
            restoreViewport()
            val p = progComp
            GL20.glUseProgram(p)
            fun u(name: String) = loc(p, name)
            GL20.glUniform1i(u("mask"), 0)
            GL20.glUniform1i(u("d0"), 1)
            GL20.glUniform1i(u("d1"), 2)
            GL20.glUniform1i(u("halo"), 3)
            GL20.glUniform1i(u("sharp"), 4)
            GL20.glUniform1i(u("soft"), 5)
            GL20.glUniform2f(u("screen"), width.toFloat(), height.toFloat())
            GL20.glUniform2f(u("t0"), 1f / levelW[0], 1f / levelH[0])
            GL20.glUniform2f(u("t1"), 1f / levelW[1], 1f / levelH[1])
            GL20.glUniform1f(u("tintText"), s.tintText.coerceIn(0f, 1f))
            GL20.glUniform1f(u("brightness"), s.brightness.coerceIn(0f, 2f))
            GL20.glUniform1f(u("opacity"), s.opacity.coerceIn(0f, 1f))
            GL20.glUniform1f(u("refraction"), s.refraction.coerceAtLeast(0f))
            GL20.glUniform1f(u("emission"), s.emission.coerceAtLeast(0f))
            GL20.glUniform1f(u("haloAmt"), s.halo.coerceAtLeast(0f))
            GL20.glUniform1f(u("frost"), s.frost.coerceIn(0f, 1f))
            GL20.glUniform1f(u("solid"), if (solid) 1f else 0f)
            GL20.glUniform1f(u("contrast"), if (s.contrast && needBackdrop) 1f else 0f)
            GL20.glUniform1f(u("premul"), if (premultiplied) 1f else 0f)
            bindUnit(1, downTex[0])
            bindUnit(2, downTex[1])
            bindUnit(3, upTex[0])
            bindUnit(4, if (solid) maskTex else backdrop.sharpTexture)
            bindUnit(5, if (needBackdrop) backdrop.softTexture else maskTex)
            bindUnit(0, maskTex)
            Gl.forget()
            Gl.depthTest(false)
            Gl.depthMask(false)
            Gl.cull(false)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
            Gl.blend(true)
            if (premultiplied) Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
            else Gl.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE)
            quad()
        } finally {
            Imm.flush()
            for (i in 1 until savedUnits.size) bindUnit(i, savedUnits[i])
            Gl.activeTexture(GL13.GL_TEXTURE0)
            Gl.forget()
        }
    }

    private fun loc(program: Int, name: String): Int =
        uniforms.getOrPut("$program:$name") { GL20.glGetUniformLocation(program, name) }

    private fun bindUnit(unit: Int, tex: Int) {
        Gl.activeTexture(GL13.GL_TEXTURE0 + unit)
        Gl.bindTexture(tex)
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
