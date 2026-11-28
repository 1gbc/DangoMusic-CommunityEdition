package dangomusicce.client

import dangomusicce.DangoMusic
import dangomusicce.module.impls.music.DynamicIslandModule
import net.minecraft.client.Minecraft

/** 调试 HUD 会话：在“灵动岛与点歌台”标签页下强制预览并直接用原生鼠标拖动两个 HUD。 */
internal object DebugHudSession {

    /** 游戏内 Debug Mode 打开中。 */
    @Volatile
    var active = false
        private set

    /** Debug Mode 当前在“灵动岛与点歌台”标签页。 */
    @Volatile
    var hudTabActive = false
        private set

    val forceShowIsland: Boolean get() = active && hudTabActive
    val forceShowRequests: Boolean get() = active && hudTabActive
    val allowDirectHudDrag: Boolean get() = active && hudTabActive

    private enum class DragOwner { ISLAND, REQUEST }

    private var owner: DragOwner? = null

    private val island: DynamicIslandModule
        get() = DangoMusic.instance.moduleManager.get(DynamicIslandModule::class.java)

    fun update(debugActive: Boolean, onHudTab: Boolean) {
        active = debugActive
        hudTabActive = onHudTab
        if (!allowDirectHudDrag) cancelDrag()
    }

    fun onMouseMove(mc: Minecraft, rawX: Int, rawY: Int) {
        if (!allowDirectHudDrag) return
        island.updateIslandHover(rawX, rawY, !BilibiliRequestHud.blocksPointer(rawX, rawY))
        when (owner) {
            DragOwner.ISLAND -> island.movePointerDrag(rawX, rawY)
            DragOwner.REQUEST -> BilibiliRequestHud.movePointer(mc, rawX, rawY)
            null -> Unit
        }
    }

    fun onMouseDown(mc: Minecraft, rawX: Int, rawY: Int): Boolean {
        if (!allowDirectHudDrag) return false
        if (BilibiliRequestHud.blocksPointer(rawX, rawY)) {
            BilibiliRequestHud.press(mc, rawX, rawY)
            owner = DragOwner.REQUEST
            return true
        }
        if (island.beginPointerDrag(rawX, rawY)) {
            owner = DragOwner.ISLAND
            return true
        }
        return false
    }

    fun onMouseUp(mc: Minecraft, rawX: Int, rawY: Int) {
        val current = owner
        owner = null
        when (current) {
            DragOwner.ISLAND -> {
                island.movePointerDrag(rawX, rawY)
                island.endPointerDrag()
            }
            DragOwner.REQUEST -> BilibiliRequestHud.release(mc, rawX, rawY)
            null -> Unit
        }
    }

    val isDragging: Boolean get() = owner != null

    fun cancelDrag() {
        when (owner) {
            DragOwner.ISLAND -> island.endPointerDrag()
            DragOwner.REQUEST -> BilibiliRequestHud.cancelGesture()
            null -> Unit
        }
        owner = null
    }

    fun reset() {
        active = false
        hudTabActive = false
        cancelDrag()
    }
}
