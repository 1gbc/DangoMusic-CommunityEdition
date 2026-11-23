package dangomusicce.platform

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.VertexSorting
import dangomusicce.DangoClient
import dangomusicce.DangoLogger
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.PanoramaRenderer
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.settings.KeyModifier
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import java.lang.reflect.Modifier

internal typealias GlStateManager = com.mojang.blaze3d.platform.GlStateManager

/** 同步原版 GlStateManager 缓存的混合与颜色写入状态。 */
internal object GlCache {
    fun blend(on: Boolean) {
        if (on) GlStateManager._enableBlend() else GlStateManager._disableBlend()
    }

    fun colorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) {
        GlStateManager._colorMask(r, g, b, a)
    }
}

/** Minecraft 1.21.1 专用平台适配。 */
internal object McCompat {
    val offscreenGl: Boolean get() = false
    val glAvailable: Boolean get() = true
    val batchesText: Boolean get() = true

    fun gameLoaded(mc: Minecraft): Boolean = mc.overlay == null

    fun currentScreen(): Screen? = Minecraft.getInstance().screen

    fun setScreen(screen: Screen?) {
        Minecraft.getInstance().setScreen(screen)
    }

    fun windowHandle(): Long = Minecraft.getInstance().window.window

    fun isMouseDown(button: Int): Boolean =
        button in 0..GLFW.GLFW_MOUSE_BUTTON_LAST &&
            GLFW.glfwGetMouseButton(windowHandle(), button) == GLFW.GLFW_PRESS

    fun isGlfwKeyDown(key: Int): Boolean =
        GLFW.glfwGetKey(windowHandle(), key) == GLFW.GLFW_PRESS

    fun createPlayerKey(action: PlayerAction): KeyMapping {
        val key = gameBindingKey(action.defaultKey)
        return KeyMapping(action.translationKey, key.type, key.value, "key.categories.dangomusicce")
    }

    fun gameBindingKey(code: Int): InputConstants.Key = when {
        code == Keys.NONE || code < -100 -> InputConstants.UNKNOWN
        code < 0 -> InputConstants.Type.MOUSE.getOrCreate(code + 100)
        else -> InputConstants.Type.KEYSYM.getOrCreate(KeyMap.toGlfw(code))
    }

    fun commonBindingKey(key: InputConstants.Key): Int = when (key.type) {
        InputConstants.Type.MOUSE -> Keys.mouse(key.value)
        InputConstants.Type.KEYSYM -> KeyMap.fromGlfw(key.value)
        else -> Keys.NONE
    }

    fun supportedKeyModifier(name: String?): String? = when (name?.trim()?.uppercase()) {
        "CONTROL", "SHIFT", "ALT" -> name.trim().uppercase()
        else -> null
    }

    private fun toNeoModifier(name: String?): KeyModifier = when (supportedKeyModifier(name)) {
        "CONTROL" -> KeyModifier.CONTROL
        "SHIFT" -> KeyModifier.SHIFT
        "ALT" -> KeyModifier.ALT
        else -> KeyModifier.NONE
    }

    fun keyStateToken(mapping: KeyMapping): Pair<InputConstants.Key, KeyModifier> =
        mapping.key to mapping.keyModifier

    fun keyStateToken(native: NativePlayerKey): Pair<InputConstants.Key, KeyModifier> =
        gameBindingKey(native.code) to if (native.code == Keys.NONE) KeyModifier.NONE else toNeoModifier(native.modifier)

    fun readNativeKey(mapping: KeyMapping): NativePlayerKey {
        val code = commonBindingKey(mapping.key)
        if (code == Keys.NONE) return NativePlayerKey(Keys.NONE)
        return NativePlayerKey(code, supportedKeyModifier(mapping.keyModifier.name))
    }

    fun writeNativeKey(mapping: KeyMapping, native: NativePlayerKey) {
        val key = gameBindingKey(native.code)
        val mod = if (native.code == Keys.NONE) KeyModifier.NONE else toNeoModifier(native.modifier)
        mapping.setKeyModifierAndCode(mod, key)
    }

    fun isKeyCodeModifier(code: Int): Boolean {
        val key = gameBindingKey(code)
        return key != InputConstants.UNKNOWN && KeyModifier.isKeyCodeModifier(key)
    }

    fun activeKeyModifier(): String? =
        KeyModifier.getActiveModifiers().firstNotNullOfOrNull { supportedKeyModifier(it.name) }

    fun isKeyModifierActive(mapping: KeyMapping, allMappings: Iterable<KeyMapping>): Boolean {
        if (mapping.keyModifier != KeyModifier.NONE) {
            return mapping.keyModifier.isActive(KeyConflictContext.UNIVERSAL)
        }
        val key = mapping.key
        if (key == InputConstants.UNKNOWN || KeyModifier.isKeyCodeModifier(key)) return true
        // 共用主键时优先识别当前按下的组合键。
        return allMappings.none { other ->
            other !== mapping && other.key == key && other.keyModifier != KeyModifier.NONE &&
                other.keyModifier.isActive(KeyConflictContext.UNIVERSAL)
        }
    }

    fun modifiedKeyDisplayName(mapping: KeyMapping): String? =
        if (mapping.keyModifier == KeyModifier.NONE) null else mapping.translatedKeyMessage.string

    private fun copyableChatMessage(message: String, copyText: String, copyHint: String): Component {
        val at = message.indexOf(copyText)
        if (copyText.isEmpty() || at < 0) return Component.literal(message)
        val link = Component.literal(copyText).withStyle { style ->
            val clickable = style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyText))
            if (copyHint.isEmpty()) clickable
            else clickable.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(copyHint)))
        }
        return Component.literal(message.substring(0, at))
            .append(link)
            .append(message.substring(at + copyText.length))
    }

    fun showChatMessage(message: String, copyText: String, copyHint: String): Boolean {
        val client = Minecraft.getInstance()
        if (client.player == null) return false
        client.gui.chat.addMessage(copyableChatMessage(message, copyText, copyHint))
        return true
    }

    fun beginWorld() {}

    fun endWorld() {
        BufferUploader.invalidate()
    }

    fun captureWorldProjection(projection: Matrix4fc) {}

    inline fun renderHudLayer(block: () -> Unit) {}

    fun hudOverlay(graphics: Any, block: () -> Unit) {
        (graphics as GuiGraphics).flush()
        try {
            block()
        } finally {
            BufferUploader.invalidate()
        }
    }

    fun mainMenuNowPlaying(graphics: Any) {
        (graphics as GuiGraphics).flush()
        try {
            DangoClient.renderMainMenuNowPlaying()
        } finally {
            BufferUploader.invalidate()
        }
    }

    private val panoramaField by lazy {
        runCatching {
            Screen::class.java.declaredFields.firstOrNull {
                it.type == PanoramaRenderer::class.java && Modifier.isStatic(it.modifiers)
            }?.apply { isAccessible = true }
        }.getOrNull()
    }

    fun renderMenuBackdrop(parent: Screen?): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.level != null || parent !is TitleScreen) return false
        val panorama = panoramaField?.get(null) as? PanoramaRenderer ?: return false
        val state = GlState()
        try {
            mc.mainRenderTarget.bindWrite(true)
            Gl.viewport(0, 0, mc.window.width, mc.window.height)
            Gl.scissor(false)
            Gl.colorMask(true, true, true, true)
            Gl.depthTest(false)
            BufferUploader.invalidate()
            val graphics = GuiGraphics(mc, mc.renderBuffers().bufferSource())
            panorama.render(graphics, mc.window.guiScaledWidth, mc.window.guiScaledHeight, 1f, mc.timer.realtimeDeltaTicks)
            graphics.flush()
        } finally {
            state.restore()
            BufferUploader.invalidate()
        }
        return true
    }

    fun pushIdentityModelView() {
        val mv = RenderSystem.getModelViewStack()
        mv.pushMatrix()
        mv.identity()
        RenderSystem.applyModelViewMatrix()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popMatrix()
        RenderSystem.applyModelViewMatrix()
    }

    fun projection(): Matrix4f = RenderSystem.getProjectionMatrix()

    fun pushScreenProjection(m: Matrix4f) {
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(m, VertexSorting.ORTHOGRAPHIC_Z)
    }

    fun popScreenProjection() {
        RenderSystem.restoreProjectionMatrix()
    }

    fun modelView(): Matrix4fc = RenderSystem.getModelViewMatrix()

    fun bindFramebuffer(target: Int, id: Int) {
        Imm.flush()
        GL30.glBindFramebuffer(target, id)
    }

    inline fun vanillaDraw(block: () -> Unit) = block()

    fun beforeDraw() {}

    fun beforeGui() {}

    fun notifyNoGl(again: Boolean = false) {}

    private fun camera() = Minecraft.getInstance().gameRenderer.mainCamera

    fun cameraXRot(): Float = camera().xRot

    fun cameraYRot(): Float = camera().yRot

    fun cameraPosition(): Vec3 = camera().position

    fun drawText(text: String, x: Float, y: Float, argb: Int, pose: Matrix4f) {
        val mc = Minecraft.getInstance()
        val buffers = mc.renderBuffers().bufferSource()
        mc.font.drawInBatch(
            text, x, y, argb, false, pose, buffers,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT,
        )
        BufferUploader.invalidate()
        vanillaDraw { buffers.endBatch() }
    }

    fun drawTexts(items: List<VanillaText>) {
        val mc = Minecraft.getInstance()
        val buffers = mc.renderBuffers().bufferSource()
        for (t in items) {
            mc.font.drawInBatch(
                t.text, t.x, t.y, t.argb, false, t.pose, buffers,
                Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT,
            )
        }
        BufferUploader.invalidate()
        vanillaDraw { buffers.endBatch() }
    }

    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray, linear: Boolean = false): ModernTexture {
        Imm.flush()
        val prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        Gl.activeTexture(GL13.GL_TEXTURE0)
        val prevTex = Gl.currentTexture()
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixelRGBA(x, y, abgr[y * w + x])
        val texture = Gl.withDefaultUnpack { DynamicTexture(native) }
        try {
            Gl.forget()
            Gl.activeTexture(GL13.GL_TEXTURE0)
            if (linear) {
                Gl.bindTexture(texture.id)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            }
            val manager = Minecraft.getInstance().textureManager
            val location = manager.register(name, texture)
            return ModernTexture({ texture.id }, { manager.release(location) })
        } finally {
            Gl.forget()
            Gl.activeTexture(GL13.GL_TEXTURE0)
            Gl.bindTexture(prevTex)
            if (prevUnit != GL13.GL_TEXTURE0) Gl.activeTexture(prevUnit)
        }
    }
}

/** 界面基类：把原版界面的各种事件统一成平台抽象接口，并管理隐藏原版输入框焦点以配合中文输入法（#19）。 */
abstract class CompatScreen(title: Component) : Screen(title) {
    private var imeSink: EditBox? = null

    override fun removed() {
        if (focused === imeSink) focused = null
        super.removed()
    }

    fun setImeFocus(active: Boolean) {
        val sink = imeSink ?: EditBox(font, 1, 1, Component.empty()).also { imeSink = it }
        if (active) {
            if (sink !in children()) addWidget(sink)
            if (focused !== sink) {
                focused = sink
                DangoLogger.info("Dango Music: text input on (system IME allowed)")
            }
            // IMBlocker 只认"最近绘制过"的输入框，每帧查询一次 isFocused 保持兼容心跳。
            sink.isFocused
        } else if (focused === sink) {
            focused = null
            DangoLogger.info("Dango Music: text input off")
        }
    }

    abstract fun onRender()
    abstract fun onMouseClicked(button: Int)
    abstract fun onMouseDragged(button: Int)
    abstract fun onMouseReleased(button: Int)
    abstract fun onScroll(delta: Double)
    abstract fun onKeyPressed(glfwKey: Int)
    abstract fun onKeyReleased(glfwKey: Int)
    abstract fun onCharTyped(c: Char)

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.flush()
        try {
            onRender()
        } finally {
            BufferUploader.invalidate()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseClicked(button)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        onMouseDragged(button)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseReleased(button)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyPressed(keyCode)
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyReleased(keyCode)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        onCharTyped(c)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        onScroll(scrollY)
        return true
    }
}
