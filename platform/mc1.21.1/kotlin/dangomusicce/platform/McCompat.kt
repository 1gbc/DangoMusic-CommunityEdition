package dangomusicce.platform

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.client.settings.KeyModifier
import org.lwjgl.glfw.GLFW

/** Minecraft 1.21.1 专用平台适配。 */
internal object McCompat {
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
}
