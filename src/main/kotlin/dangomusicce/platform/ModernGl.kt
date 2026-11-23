package dangomusicce.platform

import dangomusicce.DangoLogger
import dangomusicce.DangoMusic
import dangomusicce.font.FontManager
import dangomusicce.font.UiFont
import dangomusicce.shader.ShaderManager
import dangomusicce.shader.impls.QuadShader
import dangomusicce.shader.impls.ui.FontShader
import dangomusicce.shader.impls.ui.GlassBlurShader
import dangomusicce.shader.impls.ui.GlassShader
import dangomusicce.shader.impls.ui.IconShader
import dangomusicce.shader.impls.ui.ImageShader
import dangomusicce.shader.impls.ui.ShapeShader

/** 高版本平台层自己的 GL 资源：着色器和菜单字体（首次可用时初始化）。 */
object ModernGl {
    val shaderManager by lazy { ShaderManager(DangoMusic.instance.eventManager) }
    val fontManager = FontManager()

    private val fontCache = mutableMapOf<String, UiFont>()
    fun font(path: String): UiFont = fontCache.getOrPut(path) { fontManager.get(path) }

    private var registered = false
    private var glReady = false
    private var glFailed = false

    fun init() {
        if (glReady || glFailed) return

        if (!registered) {
            shaderManager.register(QuadShader())
            shaderManager.register(ShapeShader())
            shaderManager.register(FontShader())
            shaderManager.register(ImageShader())
            shaderManager.register(GlassShader())
            shaderManager.register(GlassBlurShader())
            shaderManager.register(IconShader())

            fontManager.register("Inter-Regular-cut.ttf")
            fontManager.register("Inter-Medium-cut.ttf")
            fontManager.register("Inter-SemiBold-cut.ttf")
            fontManager.register("InterDisplay-Bold-cut.ttf")
            registered = true
        }
        shaderManager.load()
        fontManager.load()
        glReady = true
    }

    fun ensureReady(): Boolean {
        if (glReady) return true
        if (glFailed) return false
        return try {
            init()
            glReady
        } catch (t: Throwable) {
            glFailed = true
            DangoLogger.error("Dango Music GL init failed: {}", t.toString())
            false
        }
    }
}
