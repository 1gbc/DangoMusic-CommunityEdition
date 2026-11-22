package dangomusicce.music

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal object SessionFiles {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val ownerDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val ownerFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    fun accountDirectory(clientDir: Path): Path {
        val home = Paths.get(System.getProperty("user.home"))
        val base = if (windows) {
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: home.resolve("AppData/Local")
        } else {
            System.getenv("XDG_DATA_HOME")?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
                ?: home.resolve(".local/share")
        }
        val instance = realPath(clientDir)
        val key = sha256(instance.toString())
        val directory = base.resolve("DangoMusicCE/accounts").resolve(key).toFile().canonicalFile.toPath()
        val gameDir = instance.parent?.parent ?: instance
        require(!directory.startsWith(gameDir)) { "Account storage must be outside the game directory" }
        // 改名前的登录资料在 DangoMusicGTNH/accounts 下，目录名是按旧配置目录 config/dangomusicgtnh 的路径算的
        // （迁移只复制不删除，旧配置目录还在，算出来和旧版一样）。第一次用到时复制过来，不用重新登录
        val oldKey = sha256(realPath(clientDir.resolveSibling(dangomusicce.LegacyData.OLD_ID)).toString())
        dangomusicce.LegacyData.migrateAccounts(
            base.resolve(dangomusicce.LegacyData.OLD_ACCOUNTS_ROOT).resolve("accounts").resolve(oldKey), directory)
        return directory
    }

    private fun realPath(dir: Path): Path =
        dir.toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun read(target: Path): ByteArray? {
        if (!Files.isRegularFile(target)) return null
        val stored = Files.readAllBytes(target)
        return if (windows) WindowsAccountProtection.transform(stored, false) else stored
    }

    fun write(target: Path, bytes: ByteArray) {
        val stored = if (windows) WindowsAccountProtection.transform(bytes, true) else bytes
        val temporary = if (windows) {
            Files.createDirectories(target.parent)
            Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        } else {
            Files.createDirectories(target.parent, ownerDirectory)
            Files.createTempFile(target.parent, target.fileName.toString(), ".tmp", ownerFile)
        }
        try {
            Files.write(temporary, stored)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
