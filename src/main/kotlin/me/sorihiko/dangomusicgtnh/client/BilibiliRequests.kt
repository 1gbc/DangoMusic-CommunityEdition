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
import javax.swing.JPasswordField

internal enum class BilibiliState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** The helper owns Bilibili transport. Only its local pipes carry the identity code and messages. */
internal object BilibiliRequests {
    private const val BUNDLE = "/assets/dangomusicgtnh/bilibili-live/windows-x64.zip"
    private const val SIGNER_URL = "/assets/dangomusicgtnh/bilibili-live/signer-url.txt"
    private val endpoint by lazy {
        System.getProperty("dangomusicgtnh.bilibiliSignerUrl")?.trim()?.takeIf { it.isNotEmpty() }
            ?: javaClass.getResourceAsStream(SIGNER_URL)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().trim() }.orEmpty()
    }
    private val helperFiles = setOf(
        "BilibiliLiveHelper.exe", "BilibiliLiveHelper.dll",
        "BilibiliLiveHelper.deps.json", "BilibiliLiveHelper.runtimeconfig.json",
    )
    private val examples = listOf("/点歌 美好事物 房东的猫", "/点歌 出山 花粥", "/点歌 归去来兮 叶炫清")
    private val gson = Gson()
    private val supervisor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Dango-Bilibili-Helper").apply { isDaemon = true }
    }
    private val enteringCode = AtomicBoolean(false)
    private data class Config(val mock: Boolean, val identityCode: String)
    private data class Child(val config: Config, val process: Process, val input: BufferedWriter)

    @Volatile var enabled = false
        private set
    @Volatile var state = BilibiliState.DISCONNECTED
        private set
    @Volatile private var reason = ""
    @Volatile private var identity = ""
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
            identity.isNotEmpty() -> Config(false, identity)
            else -> null
        }
        if (config == desired && !force) return
        desired = config
        state = if (config == null) BilibiliState.DISCONNECTED else BilibiliState.CONNECTING
        reason = ""
        supervisor.execute { reconcile(force) }
    }

    fun configureIdentity() {
        if (!enteringCode.compareAndSet(false, true)) return
        EventQueue.invokeLater {
            try {
                val input = JPasswordField(23)
                val option = JOptionPane(arrayOf(I18n.text("Enter the Bilibili streamer identity code"), input),
                    JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
                val dialog = option.createDialog(I18n.text("Bilibili identity code"))
                dialog.isAlwaysOnTop = true
                EventQueue.invokeLater { input.requestFocusInWindow() }
                dialog.isVisible = true
                dialog.dispose()
                val chars = input.password
                try {
                    if (option.value == JOptionPane.OK_OPTION) saveIdentity(String(chars).trim())
                } finally {
                    chars.fill('\u0000')
                    input.text = ""
                }
            } finally {
                enteringCode.set(false)
            }
        }
    }

    @Synchronized private fun saveIdentity(code: String) {
        if (closing) return
        if (code.length > 128 || code.any { it.isWhitespace() || it.isISOControl() }) {
            reason = "invalid_config"
            if (desired == null) state = BilibiliState.FAILED
            return
        }
        identity = code
        updateDesired(force = true)
    }

    @Synchronized fun restart() {
        if (!closing && desired != null) updateDesired(force = true)
    }

    fun statusText(): String = I18n.text(when (state) {
        BilibiliState.CONNECTING -> "Connecting to Bilibili…"
        BilibiliState.CONNECTED -> if (desired?.mock == true) "Mock messages connected" else "Bilibili connected"
        BilibiliState.RECONNECTING -> "Reconnecting to Bilibili…"
        BilibiliState.DISCONNECTED -> when {
            reason == "session_ended" -> "Bilibili session ended"
            enabled && !useMock && identity.isEmpty() -> "Enter the streamer identity code"
            else -> "Bilibili disconnected"
        }
        BilibiliState.FAILED -> when (reason) {
            "invalid_config" -> "Invalid Bilibili identity code"
            "signer_address_missing" -> "This build has no Bilibili request service configured"
            "signer_unavailable" -> "Bilibili request service is unavailable"
            "signer_not_configured" -> "Bilibili request service is not configured"
            "signer_busy" -> "Bilibili request service is busy. Try again shortly."
            "identity_rejected" -> "Bilibili rejected the identity code"
            "platform_rejected" -> "Bilibili rejected the session request"
            "platform_unavailable" -> "Bilibili is temporarily unavailable"
            "websocket_unavailable", "connection_lost" -> "Bilibili livestream connection failed"
            "session_ended" -> "Bilibili session ended"
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
        if (!config.mock && endpoint.isEmpty()) {
            setStatus(BilibiliState.FAILED, "signer_address_missing")
            return
        }
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
            builder.environment().keys.removeAll { it.startsWith("BILIBILI_LIVE_", ignoreCase = true) }
            val process = builder.start()
            val running = Child(config, process, process.outputStream.bufferedWriter(Charsets.UTF_8))
            child = running
            if (!send(running, mapOf("identityCode" to config.identityCode,
                    "endpoint" to (if (config.mock) "" else endpoint), "mode" to if (config.mock) "mock" else "live"))) {
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
                        if (running.config == desired && state != BilibiliState.FAILED && reason != "session_ended") {
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
        identity = ""
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
