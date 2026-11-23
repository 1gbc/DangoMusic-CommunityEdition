package dangomusicce.platform

import dangomusicce.DangoLogger
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * 绕开 Java 25 的 C2 编译器崩溃：启动时通知 JVM 对本项目代码禁用 C2 优化与内联。
 */
object JitWorkaround {
    private const val DIRECTIVES = """[
      { match: ["dangomusicce/*.*"], c2: { Exclude: true } },
      { match: ["*.*"], c2: { inline: ["-dangomusicce/*.*"] } }
    ]"""

    fun install(): Boolean {
        val java = try { Runtime.version().feature() } catch (_: Throwable) { 0 }
        if (java < 25) return true
        val dirs = candidateDirs()
        for (dir in dirs) {
            if (tryInstall(dir)) {
                DangoLogger.info("Dango Music: JIT workaround installed (Java {})", java)
                return true
            }
        }
        DangoLogger.warn(
            "Dango Music: cannot install the Java {} JIT workaround (tried {} folders); the game may crash in a \"C2 CompilerThread\". " +
                "Using Java 21 where the Minecraft version allows it avoids the crash.",
            java,
            dirs.size,
        )
        return false
    }

    private fun candidateDirs(): List<File> {
        val raw = listOfNotNull(
            System.getProperty("java.io.tmpdir"),
            File(".").absoluteFile.parent,
            System.getenv("PUBLIC"),
            System.getenv("ProgramData"),
            System.getenv("SystemDrive")?.let { "$it\\Temp" },
            "/tmp",
        )
        return raw.map { File(it).absoluteFile }
            .filter { dir -> dir.path.all { it.code in 32..126 } }
            .distinctBy { it.path }
    }

    private fun tryInstall(dir: File): Boolean {
        var file: File? = null
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            file = File.createTempFile("dangomusic-jit", ".json", dir)
            file.writeText(DIRECTIVES)
            val result = ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"),
                "compilerDirectivesAdd",
                arrayOf<Any>(arrayOf(file.absolutePath)),
                arrayOf(Array<String>::class.java.name),
            )
            Regex("""\d+ compiler directives added""").containsMatchIn(result.toString())
        } catch (_: Throwable) {
            false
        } finally {
            try { file?.delete() } catch (_: Throwable) {}
        }
    }
}
