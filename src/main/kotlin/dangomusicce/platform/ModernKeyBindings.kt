package dangomusicce.platform

import com.mojang.blaze3d.platform.InputConstants
import dangomusicce.DangoMusic
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import java.util.function.Consumer

/**
 * 通过加载器正式入口注册原版控制设置按键；只在加载完成且我们的按键真实变化时保存 options.txt（防 issue #18）。
 */
object ModernKeyBindings {
    private val mappings by lazy {
        PlayerAction.entries.associateWith { McCompat.createPlayerKey(it) }
    }
    private var registered = false
    private var links: List<KeyBindingLink>? = null

    @JvmStatic
    fun register(register: Consumer<KeyMapping>) {
        if (registered) return
        mappings.values.forEach { register.accept(it) }
        registered = true
    }

    fun sync(bindings: Collection<PlayerKeyBinding>) {
        if (!registered) return
        val mc = Minecraft.getInstance()
        val options = mc.options ?: return
        if (!McCompat.gameLoaded(mc)) return
        var dirty = false
        var refresh = false
        if (links == null) {
            val saved = savedPlayerKeys(mc.gameDirectory.toPath().resolve("options.txt"))
            val imported = DangoMusic.instance.configManager.importedPlayerKeys
            links = bindings.map { binding ->
                val mapping = mappings.getValue(binding.action)
                val before = McCompat.keyStateToken(mapping)
                val savedKey = saved[binding.action.translationKey]?.let(::parseNativeKey)
                val importedKey = imported[binding.action.translationKey]?.let(::parseNativeKey)
                KeyBindingLink(
                    binding,
                    { McCompat.readNativeKey(mapping) },
                    { McCompat.writeNativeKey(mapping, it) },
                    importedKey ?: savedKey,
                ).also {
                    val after = McCompat.keyStateToken(mapping)
                    if (after != before) refresh = true
                    if (after != (savedKey?.let(McCompat::keyStateToken) ?: before)) {
                        dirty = true
                    }
                }
            }
        }
        links!!.forEach { if (it.sync()) dirty = true }
        if (refresh || dirty) KeyMapping.resetMapping()
        if (dirty) options.save()
    }

    fun exportSnapshot(): Map<String, String> =
        if (!registered) emptyMap()
        else mappings.entries.associate { (action, mapping) ->
            val native = McCompat.readNativeKey(mapping)
            val value = native.modifier?.let { "${native.code}:$it" } ?: native.code.toString()
            action.translationKey to value
        }

    fun isModifierKey(keyCode: Int): Boolean = McCompat.isKeyCodeModifier(keyCode)

    fun activeModifier(): String? = McCompat.activeKeyModifier()

    fun modifierActive(action: PlayerAction): Boolean {
        if (!registered) return true
        val mapping = mappings.getValue(action)
        val all = Minecraft.getInstance().options?.keyMappings?.asIterable() ?: mappings.values
        return McCompat.isKeyModifierActive(mapping, all)
    }

    fun displayName(action: PlayerAction): String? =
        if (!registered) null else McCompat.modifiedKeyDisplayName(mappings.getValue(action))

    private fun parseNativeKey(serialized: String): NativePlayerKey? {
        val keyPart = serialized.substringBefore(':').trim()
        if (keyPart.isEmpty()) return null
        val rawCode = keyPart.toIntOrNull()
            ?: runCatching { McCompat.commonBindingKey(InputConstants.getKey(keyPart)) }.getOrNull()
            ?: return null
        val code = allowedPlayerKey(rawCode)
        if (code == Keys.NONE) return NativePlayerKey(Keys.NONE)
        val modifier = McCompat.supportedKeyModifier(serialized.substringAfter(':', ""))
        return NativePlayerKey(code, modifier)
    }
}
