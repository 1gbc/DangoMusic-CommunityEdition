package dangomusicce.client

import dangomusicce.ui.LayoutBounds
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.world.level.GameType
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.ReadOnlyScoreInfo
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.criteria.ObjectiveCriteria

/** 计算 1.21.1 原版 Tab 玩家列表的屏幕像素边界，供歌词/HUD 避让，并在事件被取消时不返回遮挡。 */
internal object PlayerListOverlay {
    private var tabLayerSeenThisFrame = false
    private var tabLayerCanceledThisFrame = false
    private var tabLayerHiddenPrevFrame = false

    private val getPlayerInfosMethod by lazy {
        runCatching {
            PlayerTabOverlay::class.java.getDeclaredMethod("getPlayerInfos").apply { isAccessible = true }
        }.getOrNull()
    }

    private val headerField by lazy {
        runCatching {
            PlayerTabOverlay::class.java.getDeclaredField("header").apply { isAccessible = true }
        }.getOrNull()
    }

    private val footerField by lazy {
        runCatching {
            PlayerTabOverlay::class.java.getDeclaredField("footer").apply { isAccessible = true }
        }.getOrNull()
    }

    private val fallbackOrder = Comparator<PlayerInfo> { a, b ->
        val teamCompare = (a.team?.name.orEmpty()).compareTo(b.team?.name.orEmpty())
        val specCompare = (a.gameMode == GameType.SPECTATOR).compareTo(b.gameMode == GameType.SPECTATOR)
        if (specCompare != 0) specCompare
        else if (teamCompare != 0) teamCompare
        else a.profile.name.compareTo(b.profile.name, ignoreCase = true)
    }

    fun beginFrame() {
        tabLayerSeenThisFrame = false
        tabLayerCanceledThisFrame = false
    }

    fun onTabLayerPre(canceled: Boolean) {
        tabLayerSeenThisFrame = true
        tabLayerCanceledThisFrame = canceled
    }

    fun endFrame() {
        tabLayerHiddenPrevFrame = !tabLayerSeenThisFrame || tabLayerCanceledThisFrame
    }

    fun bounds(mc: Minecraft): LayoutBounds? {
        if (mc.options.hideGui || tabLayerHiddenPrevFrame) return null
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val connection = mc.connection ?: return null
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.LIST)
        val visible = mc.options.keyPlayerList.isDown &&
            (!mc.isLocalServer || player.connection.listedOnlinePlayers.size > 1 || objective != null)
        if (!visible) return null

        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val tabList = mc.gui.tabList
        @Suppress("UNCHECKED_CAST")
        val infos = (runCatching { getPlayerInfosMethod?.invoke(tabList) as? List<PlayerInfo> }.getOrNull())
            ?: connection.listedOnlinePlayers.sortedWith(fallbackOrder).take(80)

        val font = mc.font
        val spaceWidth = font.width(" ")
        var nameWidth = 0
        var scoreWidth = 0
        for (info in infos) {
            nameWidth = maxOf(nameWidth, font.width(tabList.getNameForDisplay(info)))
            if (objective != null) {
                val holder = ScoreHolder.fromGameProfile(info.profile)
                val scoreInfo = scoreboard.getPlayerScoreInfo(holder, objective)
                if (objective.renderType != ObjectiveCriteria.RenderType.HEARTS) {
                    val format = objective.numberFormatOrDefault(StyledFormat.PLAYER_LIST_DEFAULT)
                    val formatted = ReadOnlyScoreInfo.safeFormatValue(scoreInfo, format)
                    val w = font.width(formatted)
                    scoreWidth = maxOf(scoreWidth, if (w > 0) spaceWidth + w else 0)
                }
            }
        }

        val count = infos.size
        var rows = count
        var cols = 1
        while (rows > 20) {
            cols++
            rows = (count + cols - 1) / cols
        }

        val showHeads = mc.isLocalServer || connection.connection.isEncrypted
        val scoreSlot = when {
            objective == null -> 0
            objective.renderType == ObjectiveCriteria.RenderType.HEARTS -> 90
            else -> scoreWidth
        }
        val maxWidth = (screenWidth - 50).coerceAtLeast(1)
        val colWidth = minOf(
            cols * ((if (showHeads) 9 else 0) + nameWidth + scoreSlot + 13),
            maxWidth,
        ) / cols
        var boxWidth = (colWidth * cols + (cols - 1) * 5).coerceAtLeast(0)

        val header = runCatching { headerField?.get(tabList) as? Component }.getOrNull()
        val footer = runCatching { footerField?.get(tabList) as? Component }.getOrNull()
        val headerLines = header?.let { font.split(it, maxWidth) }
        if (headerLines != null) {
            for (line in headerLines) boxWidth = maxOf(boxWidth, font.width(line))
        }
        val footerLines = footer?.let { font.split(it, maxWidth) }
        if (footerLines != null) {
            for (line in footerLines) boxWidth = maxOf(boxWidth, font.width(line))
        }

        val left = (screenWidth / 2 - boxWidth / 2 - 1).toFloat()
        val right = (screenWidth / 2 + boxWidth / 2 + 1).toFloat()
        val top = 9f
        var bottom = 10
        if (headerLines != null) bottom += headerLines.size * 9 + 1
        bottom += rows * 9
        if (footerLines != null) bottom += 1 + footerLines.size * 9

        val sx = mc.window.width.toFloat() / screenWidth.toFloat()
        val sy = mc.window.height.toFloat() / screenHeight.toFloat()
        return LayoutBounds(left * sx, top * sy, (right - left) * sx, (bottom.toFloat() - top) * sy)
    }
}
