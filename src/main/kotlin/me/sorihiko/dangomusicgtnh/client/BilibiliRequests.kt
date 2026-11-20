package me.sorihiko.dangomusicgtnh.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.Tags
import me.sorihiko.dangomusicgtnh.i18n.I18n
import me.sorihiko.dangomusicgtnh.music.request.SongRequests
import java.awt.EventQueue
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.swing.JOptionPane
import javax.swing.JTextField

internal enum class BilibiliState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** The helper owns Bilibili transport. The local pipe carries the room number and incoming messages. */
internal object BilibiliRequests {
    private const val BUNDLE = "/assets/dangomusicgtnh/bilibili-live/windows-x64.zip"
    private val helperFiles = setOf(
        "BilibiliLiveHelper.exe", "BilibiliLiveHelper.dll",
        "BilibiliLiveHelper.deps.json", "BilibiliLiveHelper.runtimeconfig.json",
    )
    private val examples = listOf("/点歌 美好事物 房东的猫", "/点歌 出山 花粥", "/点歌 归去来兮 叶炫清")
    private val gson = Gson()
    private val supervisor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Dango-Bilibili-Helper").apply { isDaemon = true }
    }
    private val enteringRoom = AtomicBoolean(false)
    private data class Config(val mock: Boolean, val roomId: String)
    private data class Child(val config: Config, val process: Process, val input: BufferedWriter)

    @Volatile var enabled = false
        private set
    @Volatile var state = BilibiliState.DISCONNECTED
        private set
    @Volatile private var reason = ""
    @Volatile private var roomId = ""
    @Volatile private var desired: Config? = null
    @Volatile private var child: Child? = null
    @Volatile private var useMock = false
    private var closing = false

    @Synchronized fun update(active: Boolean, mock: Boolean) {
        if (closing || (enabled == active && useMock == mock)) return
        enabled = active
        useMock = mock
        SongRequests.enable(active)
        updateDesired()
    }

    private fun updateDesired(force: Boolean = false) {
        val config = when {
            !enabled -> null
            useMock -> Config(true, "")
            roomId.isNotEmpty() -> Config(false, roomId)
            else -> null
        }
        if (config == desired && !force) return
        desired = config
        state = if (config == null) BilibiliState.DISCONNECTED else BilibiliState.CONNECTING
        reason = ""
        supervisor.execute { reconcile(force) }
    }

    fun configureRoom() {
        if (!enteringRoom.compareAndSet(false, true)) return
        EventQueue.invokeLater {
            try {
                val input = JTextField(roomId, 23)
                val title = I18n.text("Bilibili room number")
                val option = JOptionPane(arrayOf(I18n.text("Enter the Bilibili livestream room number"), input),
                    JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
                val dialog = option.createDialog(title)
                try {
                    dialog.isAlwaysOnTop = true
                    EventQueue.invokeLater {
                        input.requestFocusInWindow()
                        input.selectAll()
                    }
                    dialog.isVisible = true
                } finally {
                    dialog.dispose()
                }
                if (option.value == JOptionPane.OK_OPTION && !saveRoom(input.text.trim())) {
                    JOptionPane.showMessageDialog(null, I18n.text("Invalid Bilibili room number"),
                        title, JOptionPane.WARNING_MESSAGE)
                }
            } finally {
                enteringRoom.set(false)
            }
        }
    }

    @Synchronized private fun saveRoom(value: String): Boolean {
        if (closing) return true
        val number = value.toLongOrNull()
        if (number == null || number <= 0 || value.any { it !in '0'..'9' }) return false
        roomId = number.toString()
        updateDesired(force = true)
        return true
    }

    @Synchronized fun restart() {
        if (!closing && desired != null) updateDesired(force = true)
    }

    fun statusText(): String = I18n.text(when (state) {
        BilibiliState.CONNECTING -> "Connecting to Bilibili…"
        BilibiliState.CONNECTED -> if (desired?.mock == true) "Mock messages connected" else "Bilibili connected"
        BilibiliState.RECONNECTING -> "Reconnecting to Bilibili…"
        BilibiliState.DISCONNECTED -> if (enabled && !useMock && roomId.isEmpty())
            "Enter the Bilibili livestream room number" else "Bilibili disconnected"
        BilibiliState.FAILED -> when (reason) {
            "invalid_room" -> "Invalid Bilibili room number"
            "room_not_found" -> "Bilibili could not find that livestream room"
            "platform_limited" -> "Bilibili limited the connection. Try again later."
            "anonymous_unavailable" -> "Bilibili did not allow an anonymous danmaku connection"
            "platform_rejected" -> "Bilibili rejected the danmaku request"
            "platform_unavailable" -> "Bilibili is temporarily unavailable"
            "websocket_unavailable", "connection_lost" -> "Bilibili livestream connection failed"
            "windows_required" -> "The Bilibili helper requires Windows x64"
            else -> "Bilibili helper failed. Check the .NET 8 runtime."
        }
    })

    fun sendExamples() {
        if (!enabled || desired?.mock != true || state != BilibiliState.CONNECTED) return
        supervisor.execute {
            val running = child?.takeIf { it.config == desired && it.config.mock } ?: return@execute
            for (example in examples) {
                val data = mapOf("type" to "danmaku", "id" to UUID.randomUUID().toString(),
                    "user" to "DemoViewer", "content" to example)
                if (!send(running, data)) break
            }
        }
    }

    private fun reconcile(force: Boolean) {
        val config = desired
        if (force || child?.config != config) retire()
        if (config == null || config != desired || child != null) return
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        if (!System.getProperty("os.name").startsWith("Windows") || arch !in setOf("amd64", "x86_64")) {
            setStatus(BilibiliState.FAILED, "windows_required")
            return
        }
        try {
            val dir = installHelper()
            if (config != desired) return
            val builder = ProcessBuilder(dir.resolve("BilibiliLiveHelper.exe").toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
            val process = builder.start()
            val running = Child(config, process, process.outputStream.bufferedWriter(Charsets.UTF_8))
            child = running
            if (!send(running, mapOf("roomId" to config.roomId,
                    "mode" to if (config.mock) "mock" else "live"))) {
                retire()
                if (config == desired) setStatus(BilibiliState.FAILED, "helper_exited")
                return
            }
            Thread({ readEvents(running) }, "Dango-Bilibili-Events").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            if (config == desired) setStatus(BilibiliState.FAILED, "helper_exited")
        }
    }

    private fun send(running: Child, data: Map<String, String>): Boolean = runCatching {
        running.input.write(gson.toJson(data))
        running.input.newLine()
        running.input.flush()
    }.isSuccess

    private fun readEvents(running: Child) {
        try {
            running.process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.length <= 8192 && child === running && running.config == desired) {
                        val event = runCatching { JsonParser().parse(line) as? JsonObject }.getOrNull() ?: return@forEach
                        fun string(key: String) = event.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                        when (string("type")) {
                            "status" -> {
                                val next = BilibiliState.values().firstOrNull { it.name.equals(string("state"), ignoreCase = true) }
                                if (next != null) setStatus(next, string("reason"))
                            }
                            "danmaku" -> SongRequests.receive(string("id"), string("user"), string("content"))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Child output is never logged because it can include user or platform data.
        } finally {
            runCatching {
                supervisor.execute {
                    if (child === running) {
                        retire()
                        if (running.config == desired && state != BilibiliState.FAILED) {
                            setStatus(BilibiliState.FAILED, "helper_exited")
                        }
                    }
                }
            }
        }
    }

    private fun setStatus(status: BilibiliState, detail: String) {
        reason = detail
        state = status
    }

    private fun retire() {
        val running = child ?: return
        child = null
        if (running.process.isAlive) send(running, mapOf("type" to "stop"))
        runCatching { running.input.close() }
        if (!running.process.waitFor(15, TimeUnit.SECONDS)) running.process.destroyForcibly()
    }

    @Synchronized fun shutdown() {
        closing = true
        desired = null
        roomId = ""
        SongRequests.enable(false)
        runCatching { supervisor.submit(::retire).get(17, TimeUnit.SECONDS) }
        supervisor.shutdownNow()
    }

    private fun installHelper(): Path {
        val dir = DangoMusic.instance.clientDir.resolve("bilibili-helper").resolve(Tags.VERSION)
        Files.createDirectories(dir)
        val seen = HashSet<String>()
        val stream = javaClass.getResourceAsStream(BUNDLE) ?: error("Missing Bilibili helper")
        ZipInputStream(stream).use { zip ->
            while (true) {
                val file = zip.nextEntry ?: break
                if (file.isDirectory || file.name !in helperFiles || !seen.add(file.name)) continue
                val temporary = Files.createTempFile(dir, "helper-", ".tmp")
                try {
                    Files.newOutputStream(temporary).use { zip.copyTo(it) }
                    Files.move(temporary, dir.resolve(file.name), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
        check(seen == helperFiles)
        return dir
    }
}
