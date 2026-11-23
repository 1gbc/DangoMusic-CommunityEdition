package dangomusicce.font

import dangomusicce.DangoLogger
import dangomusicce.gfx.CjkStyle
import dangomusicce.platform.Gl
import dangomusicce.platform.Imm
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 中日韩文字 / 符号字体（OpenGL 3.2 core + DMCE 多槽位字体与热重载）：
 * 后台预加载系统字体，渲染线程按需光栅化进 1024×1024 多级纹理页并通过 [Imm] 合批绘制。
 */
object CjkFont {

    const val PLAIN = 0
    const val BOLD = 1
    const val DISPLAY = 2
    const val LYRIC = 3
    const val SIDE = 4
    private const val STYLES = CjkStyle.REQUEST_HUD_BOLD + 1

    private fun baseOf(style: Int) = if (style == DISPLAY) 96f else 48f
    private const val PAGE = 1024
    private const val PAD = 4

    class Glyph(
        val page: Int,
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val w: Float,
        val h: Float,
        val left: Float,
        val top: Float,
        val adv: Float,
        val base: Float,
    )

    private val displayFamilies = listOf("Impact", "Anton", "Bebas Neue", "Arial Black", "Segoe UI Black")

    private val families = listOf(
        "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
        "Yu Gothic UI", "Meiryo UI", "Meiryo",
        "Malgun Gothic",
        "Noto Sans CJK SC", "Source Han Sans SC", "SimHei", "MS Gothic",
        "Segoe UI Symbol", "Segoe UI", "Arial Unicode MS",
    )

    private val frc = FontRenderContext(null, true, true)
    private val fontLists = AtomicReferenceArray<List<Font>>(STYLES)

    private val glyphs = HashMap<Int, Glyph?>()
    private val pages = ArrayList<Int>()
    private val dirty = ArrayList<Boolean>()
    private var px = PAD
    private var py = PAD
    private var rowH = 0

    private val available: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ENGLISH).map { it.lowercase() }.toHashSet()
        } catch (_: Exception) {
            hashSetOf()
        }
    }

    internal fun preloadSystemFonts() {
        Thread({
            fonts(CjkStyle.NOTO_SC)
            fonts(CjkStyle.NOTO_SC_BOLD)
        }, "Dango-CjkFonts").apply {
            isDaemon = true
            start()
        }
    }

    private fun fonts(style: Int): List<Font> {
        fontLists.get(style)?.let { return it }
        val resolved = resolveFonts(style)
        fontLists.compareAndSet(style, null, resolved)
        return fontLists.get(style) ?: resolved
    }

    private fun resolveFonts(style: Int): List<Font> {
        if (style == CjkStyle.NOTO_SC || style == CjkStyle.NOTO_SC_BOLD) {
            val bold = style == CjkStyle.NOTO_SC_BOLD
            val awtStyle = if (bold) Font.BOLD else Font.PLAIN
            val preferred = listOf("Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC")
                .filter { it.lowercase(Locale.ROOT) in available }
                .map { Font(it, awtStyle, 1).deriveFont(baseOf(style)) }
            return (preferred + fonts(if (bold) BOLD else PLAIN)).distinct()
        }
        if (hasCustomFont(style)) {
            val bold = style == CjkStyle.MENU_BOLD || style == CjkStyle.REQUEST_HUD_BOLD
            val weight = if (bold) Font.BOLD else Font.PLAIN
            val custom = customFonts[style]?.let { listOf(it.deriveFont(weight, baseOf(style))) }.orEmpty()
            val fallback = when (style) {
                CjkStyle.REQUEST_HUD -> CjkStyle.NOTO_SC
                CjkStyle.REQUEST_HUD_BOLD -> CjkStyle.NOTO_SC_BOLD
                else -> if (style == SIDE || bold) BOLD else PLAIN
            }
            return custom + fonts(fallback)
        }
        val base = baseOf(style)
        val awtStyle = if (style == PLAIN) Font.PLAIN else Font.BOLD
        val list = mutableListOf<Font>()
        if (style == DISPLAY) {
            list += displayFamilies.filter { it.lowercase() in available }.map { Font(it, Font.PLAIN, 1).deriveFont(base) }
        }
        list += families.filter { it.lowercase() in available }.map { Font(it, awtStyle, 1).deriveFont(base) }
        if (list.none { it.canDisplay('中') }) list += fileFonts.map { it.deriveFont(awtStyle, base) }
        list += Font(Font.DIALOG, awtStyle, 1).deriveFont(base)
        return list
    }

    private val fileHints = listOf(
        "notosanscjk", "notosanssc", "notosanshans", "sourcehansans", "misans", "harmonyos_sans_sc",
        "hyqihei", "droidsansfallback", "wqy", "cjk", "fallback",
    )

    private val fontDirs = listOf(
        "/system/fonts", "/system/product/fonts", "/product/fonts", "/system_ext/fonts", "/data/fonts/files",
        "/usr/share/fonts", "/usr/local/share/fonts", System.getProperty("user.home") + "/.local/share/fonts",
        System.getProperty("user.home") + "/.fonts",
    )

    private val fileFonts: List<Font> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val files = ArrayList<File>()
        fun scan(dir: File, depth: Int) {
            val list = try { dir.listFiles() } catch (_: Exception) { null } ?: return
            for (f in list) {
                val n = f.name.lowercase()
                if (f.isDirectory) {
                    if (depth < 3) scan(f, depth + 1)
                } else if ((n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")) && fileHints.any { it in n }) {
                    files += f
                }
            }
        }
        for (d in fontDirs) scan(File(d), 0)
        files.sortWith(
            compareBy<File>(
                { f -> fileHints.indexOfFirst { it in f.name.lowercase() } },
                { if ("regular" in it.name.lowercase()) 0 else 1 },
                { it.name },
            ),
        )
        val out = ArrayList<Font>()
        for (f in files) {
            if (out.size >= 2) break
            val font = try { Font.createFont(Font.TRUETYPE_FONT, f) } catch (_: Throwable) { null } ?: continue
            if (font.canDisplay('中')) out += font
        }
        if (out.isEmpty()) {
            DangoLogger.warn("Dango Music: no CJK font found on this system; Chinese / Japanese / Korean text cannot be shown")
        } else {
            DangoLogger.info("Dango Music: loaded CJK font from file: {}", out.joinToString { it.fontName })
        }
        out
    }

    fun glyph(c: Char, style: Int): Glyph? {
        val key = c.code * STYLES + style
        if (glyphs.containsKey(key)) return glyphs[key]
        val g = try {
            render(c, style)
        } catch (_: Exception) {
            null
        }
        glyphs[key] = g
        return g
    }

    private val customFonts = arrayOfNulls<Font>(STYLES)
    private val customKeys = arrayOfNulls<String>(STYLES)

    private fun hasCustomFont(style: Int) =
        style == LYRIC || style == SIDE || style in CjkStyle.MENU..CjkStyle.REQUEST_HUD_BOLD

    fun setFont(style: Int, font: Font?, key: String?) {
        if (!hasCustomFont(style)) return
        val k = if (font == null) null else key
        if (k == customKeys[style] && customFonts[style] === font) return
        customKeys[style] = k
        customFonts[style] = font
        fontLists.set(style, null)
        glyphs.keys.removeAll { it % STYLES == style }
    }

    fun width(s: String, size: Float, style: Int): Float {
        var w = 0f
        for (c in s) {
            val g = glyph(c, style) ?: continue
            w += g.adv * size / g.base
        }
        return w
    }

    private fun render(c: Char, style: Int): Glyph? {
        val base = baseOf(style)
        val font = fonts(style).firstOrNull { it.canDisplay(c) } ?: return null
        val gv = font.createGlyphVector(frc, charArrayOf(c))
        val adv = gv.getGlyphMetrics(0).advanceX
        val vb = gv.visualBounds
        if (vb.width <= 0.0 || vb.height <= 0.0) {
            return Glyph(-1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, adv, base)
        }
        val left = floor(vb.x).toInt() - 1
        val top = floor(vb.y).toInt() - 1
        val w = ceil(vb.x + vb.width).toInt() + 1 - left
        val h = ceil(vb.y + vb.height).toInt() + 1 - top
        if (w > PAGE - PAD * 2 || h > PAGE - PAD * 2) return null

        if (pages.isEmpty()) newPage()
        if (px + w + PAD > PAGE) {
            px = PAD
            py += rowH + PAD
            rowH = 0
        }
        if (py + h + PAD > PAGE) newPage()

        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color.WHITE
        g.drawGlyphVector(gv, -left.toFloat(), -top.toFloat())
        g.dispose()

        val page = pages.size - 1
        upload(pages[page], px, py, w, h, (img.raster.dataBuffer as DataBufferByte).data)
        dirty[page] = true

        val glyph = Glyph(
            page,
            px.toFloat() / PAGE,
            py.toFloat() / PAGE,
            (px + w).toFloat() / PAGE,
            (py + h).toFloat() / PAGE,
            w.toFloat(),
            h.toFloat(),
            left.toFloat(),
            top.toFloat(),
            adv,
            base,
        )
        px += w + PAD
        rowH = max(rowH, h)
        return glyph
    }

    private const val canMipmap = true

    private fun genMipmap() {
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
    }

    private fun newPage() {
        val prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        val prev = Gl.currentTexture()
        val id = try {
            val generated = Gl.genTexture()
            val zero = BufferUtils.createByteBuffer(PAGE * PAGE * 4)
            Gl.withDefaultUnpack {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, PAGE, PAGE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zero)
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            if (canMipmap) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 2)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
                genMipmap()
            } else {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            }
            generated
        } finally {
            Gl.bindTexture(prev)
            if (prevUnit != GL13.GL_TEXTURE0) Gl.activeTexture(prevUnit)
        }
        pages += id
        dirty += false
        px = PAD
        py = PAD
        rowH = 0
    }

    private fun upload(tex: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray) {
        val buf = BufferUtils.createByteBuffer(w * h * 4)
        for (i in 0 until w * h) {
            buf.put(0xFF.toByte()).put(0xFF.toByte()).put(0xFF.toByte()).put(data[i])
        }
        buf.flip()
        val prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        val prevTex = Gl.currentTexture()
        try {
            Gl.bindTexture(tex)
            Gl.withDefaultUnpack {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf)
            }
        } finally {
            Gl.bindTexture(prevTex)
            if (prevUnit != GL13.GL_TEXTURE0) Gl.activeTexture(prevUnit)
        }
    }

    private fun bindPage(i: Int) {
        Gl.bindTexture(pages[i])
        if (dirty[i]) {
            if (canMipmap) genMipmap()
            dirty[i] = false
        }
    }

    fun draw(s: String, x: Float, baseline: Float, size: Float, style: Int) {
        val list = s.map { glyph(it, style) }
        var pen = x
        var bound = -1
        var begun = false
        for (g in list) {
            if (g == null) continue
            val scale = size / g.base
            if (g.page >= 0) {
                if (g.page != bound) {
                    if (begun) Imm.endFixed()
                    bindPage(g.page)
                    bound = g.page
                    Imm.begin()
                    begun = true
                }
                quad(g, pen + g.left * scale, baseline + g.top * scale, scale)
            }
            pen += g.adv * scale
        }
        if (begun) Imm.endFixed()
    }

    fun drawCentered(s: String, cx: Float, cy: Float, size: Float, style: Int) {
        val list = s.mapNotNull { glyph(it, style) }
        if (list.isEmpty()) return
        val scale = size / list[0].base
        var total = 0f
        list.forEachIndexed { i, g -> total += if (i == list.size - 1) g.w else g.adv }
        var pen = cx - total * scale / 2f
        for (g in list) {
            if (g.page >= 0) {
                bindPage(g.page)
                Imm.begin()
                quad(g, pen, cy - g.h * scale / 2f, scale)
                Imm.endFixed()
            }
            pen += g.adv * scale
        }
    }

    private fun quad(g: Glyph, x: Float, y: Float, scale: Float) {
        val w = g.w * scale
        val h = g.h * scale
        Imm.tex(g.u0, g.v0); Imm.vertex(x, y)
        Imm.tex(g.u0, g.v1); Imm.vertex(x, y + h)
        Imm.tex(g.u1, g.v1); Imm.vertex(x + w, y + h)
        Imm.tex(g.u1, g.v0); Imm.vertex(x + w, y)
    }
}
