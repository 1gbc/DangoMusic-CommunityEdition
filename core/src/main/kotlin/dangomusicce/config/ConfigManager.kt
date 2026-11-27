package dangomusicce.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dangomusicce.DangoLogger
import dangomusicce.event.EventHandler
import dangomusicce.event.Listener
import dangomusicce.events.TickEvent
import dangomusicce.module.Module
import dangomusicce.module.ModuleManager
import dangomusicce.module.values.BooleanValue
import dangomusicce.module.values.ColorValue
import dangomusicce.module.values.TextValue
import dangomusicce.module.values.FloatValue
import dangomusicce.module.values.IntValue
import dangomusicce.module.values.ModuleValue

import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * 把每个模块的开关状态和 @ModuleValue 设置存到 config/dangomusic/config.json，启动时自动读回。
 *
 * - 设置值在启动时立刻读回；
 * - 模块开关要等着色器、字体都加载完（第 3 个 tick）再恢复，避免 onEnable 时资源还没准备好；
 * - 之后每 2 秒检查一次，有变化才写文件；游戏关闭时再存一次。
 */
class ConfigManager(
    private val moduleManager: ModuleManager,
    private val file: Path,
) : Listener {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val fieldCache = IdentityHashMap<Module, List<Field>>()
    private val saveWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dango-ConfigIO").apply { isDaemon = true }
    }
    private val writeLock = Any()
    private var pendingEnabled: Map<Module, Boolean>? = null
    private var ticks = 0
    private var ready = false
    @Volatile private var lastSaved: String? = null
    var importedPlayerKeys: Map<String, String> = emptyMap()
        private set

    private fun modules(): List<Module> = moduleManager.modules.values.flatten()

    private fun valueFields(module: Module): List<Field> = synchronized(fieldCache) {
        fieldCache.getOrPut(module) {
            module.javaClass.declaredFields.filter {
                it.getAnnotation(ModuleValue::class.java)?.persistent == true
            }.onEach { it.isAccessible = true }
        }
    }

    // ---------------------------------------------------------------- 读取

    /** 第一次运行（没有配置文件）：所有功能默认打开 */
    private var fresh = false

    fun load() {
        importedPlayerKeys = emptyMap()
        if (!Files.exists(file)) {
            fresh = true
            DangoLogger.info("No config found, using defaults: {}", file.toString())
            return
        }
        try {
            val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            val root = JsonParser().parse(text).asJsonObject
            val enabledMap = mutableMapOf<Module, Boolean>()

            for (module in modules()) {
                val obj = root.get(module.name) as? JsonObject ?: continue
                (obj.get("enabled") as? JsonPrimitive)?.let { enabledMap[module] = it.asBoolean }

                val values = obj.get("values") as? JsonObject ?: continue
                // Old configs had a liquid-glass switch. Dark glass is the closest new material to the old black fill.
                if (module.name == "DynamicIsland" && !values.has("islandBackground")) {
                    val glass = values.get("glass") as? JsonPrimitive
                    if (glass?.isBoolean == true && !glass.asBoolean) values.addProperty("islandBackground", 1)
                }
                for (field in valueFields(module)) {
                    val json = values.get(field.name) ?: continue
                    try {
                        applyValue(field.get(module), json)
                    } catch (e: Exception) {
                        DangoLogger.warn("Config value skipped: {}.{}", module.name, field.name)
                    }
                }
            }
            // Newly added modules (for example Nearby) are absent from an older config.
            // Enable their documented default instead of leaving them silently off.
            for (module in modules()) {
                if (!enabledMap.containsKey(module)) enabledMap[module] = module.enabledByDefault
            }
            pendingEnabled = enabledMap
            val playerKeys = root.get("playerKeyBindings") as? JsonObject
            // 改名前导出的设置里快捷键还是 key.dangomusicgtnh.* 的名字
            importedPlayerKeys = playerKeys?.entrySet()?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { dangomusicce.LegacyData.keyName(key) to it.asString }
            }?.toMap().orEmpty()
            DangoLogger.info("Config loaded: {}", file.toString())
        } catch (e: Exception) {
            DangoLogger.error("Config load failed, backing up and using defaults. {}", e)
            runCatching {
                val backup = file.resolveSibling(file.fileName.toString() + ".corrupt")
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
            }
            pendingEnabled = null
            fresh = true
        }
    }

    private fun applyValue(value: Any?, json: JsonElement) {
        when (value) {
            is BooleanValue -> value.set(json.asBoolean)
            is IntValue -> value.setValue(json.asInt)
            is FloatValue -> value.setValue(json.asFloat)
            is ColorValue -> if (json.isJsonPrimitive) ColorValue.parse(json.asString)?.let { value.setValue(it) }
            is TextValue -> if (json.isJsonPrimitive) value.value = json.asString
        }
    }

    // ---------------------------------------------------------------- 保存

    private fun settingsSnapshot(): JsonObject {
        val root = JsonObject()
        for (module in modules()) {
            val obj = JsonObject()
            obj.addProperty("enabled", module.enabled)
            val values = JsonObject()
            for (field in valueFields(module)) {
                when (val v = field.get(module)) {
                    is BooleanValue -> values.addProperty(field.name, v.get())
                    is IntValue -> values.addProperty(field.name, v.value)
                    is FloatValue -> values.addProperty(field.name, v.value)
                    is ColorValue -> values.addProperty(field.name, v.hex())
                    is TextValue -> values.addProperty(field.name, v.value)
                }
            }
            obj.add("values", values)
            root.add(module.name, obj)
        }
        return root
    }

    /** Same JSON read by load(), with the platform modifiers needed to restore shortcuts elsewhere. */
    fun exportSnapshot(playerKeys: Map<String, String>): String {
        val root = settingsSnapshot()
        val bindings = JsonObject()
        playerKeys.forEach { (key, value) -> bindings.addProperty(key, value) }
        root.add("playerKeyBindings", bindings)
        return gson.toJson(root)
    }

    fun saveIfChanged(sync: Boolean = false) {
        if (!ready) return
        val text = try {
            gson.toJson(settingsSnapshot())
        } catch (e: Exception) {
            DangoLogger.error("Config snapshot failed. {}", e)
            return
        }
        if (text == lastSaved) return
        if (sync) {
            writeConfig(text)
        } else {
            saveWorker.execute { writeConfig(text) }
        }
    }

    private fun writeConfig(text: String) = synchronized(writeLock) {
        if (text == lastSaved) return
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        try {
            Files.createDirectories(file.parent)
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            lastSaved = text
        } catch (e: Exception) {
            DangoLogger.error("Config save failed. {}", e)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    // ---------------------------------------------------------------- 时机

    @EventHandler
    fun onTick(e: TickEvent) {
        ticks++
        if (!ready && ticks >= 3) {
            pendingEnabled?.forEach { (module, enabled) ->
                try {
                    module.enabled = enabled
                } catch (ex: Exception) {
                    DangoLogger.warn("Restore module state failed: {}", module.name)
                }
            }
            pendingEnabled = null
            if (fresh) {
                for (module in modules()) {
                    try {
                        module.enabled = module.enabledByDefault
                    } catch (ex: Exception) {
                        DangoLogger.warn("Enable module failed: {}", module.name)
                    }
                }
                fresh = false
            }
            ready = true
            // 也保存首次推断的语言和新增选项，避免下次启动时重新采用游戏语言。
            saveIfChanged()
            return
        }
        if (ready && ticks % 40 == 0) saveIfChanged()
    }

    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread({ saveIfChanged(sync = true) }, "Dango-ConfigSave"))
    }
}
