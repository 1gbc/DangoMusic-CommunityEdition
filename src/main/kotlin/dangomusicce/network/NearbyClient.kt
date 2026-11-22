package dangomusicce.network

import dangomusicce.multiplayer.GroupUi
import dangomusicce.multiplayer.LyricsData
import dangomusicce.multiplayer.RemoteHost
import dangomusicce.multiplayer.SharedLyricLine

/**
 * M0 占位实现：M4 接入 Forge/NeoForge 网络通道时替换为完整实现。
 */
object NearbyClient {
    var localYielded = false

    fun init() {}

    fun tick() {}

    fun activeHost(): RemoteHost? = null

    fun lyricsFor(trackKey: String): List<SharedLyricLine>? = null

    fun lyricsData(trackKey: String): LyricsData? = null

    fun groupUi(): GroupUi? = null

    fun groupAction(action: String, name: String = "", target: String = "") {}

    fun selectGroup(groupId: String) {}

    fun selectedGroupId(): String = ""

    fun radius(): Int = 32

    fun isAvailable(): Boolean = false

    fun shouldUseLocalLyrics(): Boolean = false

    fun stopForUser() {}

    fun stopAll() {}
}
