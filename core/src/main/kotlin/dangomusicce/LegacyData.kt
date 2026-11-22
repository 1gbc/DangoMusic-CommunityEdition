package dangomusicce

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * 项目从 DangoMusic GTNH Edition 改名为 DangoMusic Community Edition（mod ID `dangomusicgtnh` → `dangomusicce`）。
 * 老玩家升级后第一次启动时，把旧目录里的设置和登录资料复制到新目录。只复制、不删除：旧目录原样留着，退回旧版也能用。
 * 新目录已经存在时什么都不做，所以只会发生一次，也不会覆盖新版里改过的东西。
 */
object LegacyData {
    /** 改名前的 mod ID：旧配置目录名、旧快捷键名里都是它 */
    const val OLD_ID = "dangomusicgtnh"

    /** 改名前登录资料目录的名字（Windows 上是 %LOCALAPPDATA%\DangoMusicGTNH\accounts） */
    const val OLD_ACCOUNTS_ROOT = "DangoMusicGTNH"

    /**
     * 不复制的子目录：从 JAR 里解出来的 Apple / B 站组件（启动时会重新解压），
     * 以及 0.1.1 以前放在实例里的旧登录目录 accounts（早就不再读取，复制过去只会多一份过期的登录凭据）
     */
    private val REBUILT = setOf("apple-player", "bilibili-helper", "accounts")

    private const val OLD_KEY_PREFIX = "key.$OLD_ID."
    private const val NEW_KEY_PREFIX = "key.${DangoMusic.MOD_ID}."

    /** `config/dangomusicgtnh` → `config/dangomusicce`（客户端设置、字体、歌词；服务端的 server.cfg 和听歌房） */
    @JvmStatic
    fun migrateConfig(configRoot: File) {
        copyIfAbsent(File(configRoot, OLD_ID).toPath(), File(configRoot, DangoMusic.MOD_ID).toPath(), REBUILT, "settings")
    }

    /** 同一个游戏实例的旧登录资料目录 → 新目录（网易云、QQ、Apple 的登录） */
    fun migrateAccounts(oldDir: Path, newDir: Path) {
        copyIfAbsent(oldDir, newDir, emptySet(), "accounts")
    }

    /** 旧快捷键名 `key.dangomusicgtnh.xxx` 换成新名字；options.txt 和导出的设置里都可能还是旧名字 */
    fun keyName(name: String): String =
        if (name.startsWith(OLD_KEY_PREFIX)) NEW_KEY_PREFIX + name.substring(OLD_KEY_PREFIX.length) else name

    /**
     * 先完整复制到旁边的临时目录，成功后再改名成新目录：中途失败（文件被占用、磁盘满）不会留下半个新目录，
     * 下次启动还会再试；这次就当没有旧数据，用默认设置 / 重新登录。
     */
    private fun copyIfAbsent(old: Path, new: Path, skip: Set<String>, what: String) {
        if (Files.exists(new) || !Files.isDirectory(old)) return
        val staging = new.resolveSibling(new.fileName.toString() + ".migrating")
        try {
            deleteTree(staging)
            Files.walkFileTree(old, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir.parent == old && dir.fileName.toString() in skip) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectories(staging.resolve(old.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, staging.resolve(old.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            })
            try {
                Files.move(staging, new, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, new)
            }
            DangoLogger.info("Copied old {} from {} to {}", what, old.toString(), new.toString())
        } catch (e: Exception) {
            DangoLogger.warn("Could not copy old {} from {}: {}", what, old.toString(), e.toString())
            try {
                deleteTree(staging)
            } catch (_: Exception) {
            }
        }
    }

    /** 只用来清理上面自己建的临时目录 */
    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                Files.delete(d)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
