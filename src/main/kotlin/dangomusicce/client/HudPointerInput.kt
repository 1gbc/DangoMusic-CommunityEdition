package dangomusicce.client

import dangomusicce.DangoMusic
import dangomusicce.legacy.LegacyKeyBindings
import dangomusicce.module.impls.music.DynamicIslandModule
import dangomusicce.module.impls.music.LyricsModule
import dangomusicce.platform.PlayerAction
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.Display

internal object HudPointerInput {
    private var wasHeld = false

    private val island get() = DangoMusic.instance.moduleManager.get(DynamicIslandModule::class.java)
    private val lyrics get() = DangoMusic.instance.moduleManager.get(LyricsModule::class.java)

    private fun held() = LegacyKeyBindings.isHeld(PlayerAction.BILIBILI_REQUESTS)

    fun startTick(mc: Minecraft) {
        val held = held()
        val newlyHeld = held && !wasHeld
        wasHeld = held
        if (newlyHeld && Display.isActive() && mc.inGameHasFocus &&
            mc.currentScreen == null && mc.theWorld != null &&
            mc.thePlayer?.let { it.isEntityAlive && !it.isPlayerSleeping } == true
        ) {
            mc.displayGuiScreen(PointerScreen())
        }
    }

    fun endTick(mc: Minecraft) {
        val screen = mc.currentScreen as? PointerScreen ?: return
        // Forge's END phase runs after GuiScreen drains the LWJGL event queues.
        // Re-grabbing at START could deliver a queued click or wheel event to the world.
        if (!screen.interacting()) mc.displayGuiScreen(null)
    }

    fun owns(screen: GuiScreen?) = screen is PointerScreen

    fun interacting(screen: GuiScreen?) = (screen as? PointerScreen)?.interacting() == true

    private class PointerScreen : GuiScreen() {
        private var cancelled = false
        private var waitForLeftRelease = false
        private var draggingIsland = false
        /** 这次按下点在了驾驶舱歌词上：松开时不再交给点歌台 */
        private var pressedLyrics = false

        override fun initGui() {
            // A button already held when the cursor is released cannot start a queue action.
            waitForLeftRelease = Mouse.isCreated() && Mouse.isButtonDown(0)
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            draggingIsland = false
            pressedLyrics = false
            // 鼠标刚放出来时还没有移动事件：先用当前位置，指着的歌词行马上就能亮
            if (Mouse.isCreated()) lyrics.cockpitPointerMove(Mouse.getX(), mc.displayHeight - Mouse.getY() - 1)
        }

        fun interacting() = mc.currentScreen === this && !cancelled && Display.isActive() &&
            held() && mc.theWorld != null &&
            mc.thePlayer?.let { it.isEntityAlive && !it.isPlayerSleeping } == true

        override fun doesGuiPauseGame() = false

        override fun keyTyped(typedChar: Char, keyCode: Int) {
            if (keyCode == Keyboard.KEY_ESCAPE) cancelled = true
        }

        override fun handleMouseInput() {
            // Never delegate to a screen or key binding behind this transparent screen.
            if (!interacting()) {
                lyrics.cockpitPointerMove(-1, -1)
                return
            }
            val x = Mouse.getEventX()
            val y = mc.displayHeight - Mouse.getEventY() - 1
            // 点歌卡片画在最上面，和驾驶舱歌词重叠时先归点歌卡片；指在卡片上时歌词行也不亮
            val onCard = BilibiliRequestHud.blocksPointer(x, y)
            if (onCard) lyrics.cockpitPointerMove(-1, -1) else lyrics.cockpitPointerMove(x, y)
            val wheel = Mouse.getEventDWheel()
            if (wheel != 0 && !draggingIsland) {
                if (onCard || !lyrics.cockpitPointerScroll(x, y, wheel)) {
                    if (BilibiliRequests.enabled) BilibiliRequestHud.scroll(mc, x, y, wheel)
                }
            }
            if (Mouse.getEventButton() != 0) {
                if (draggingIsland) island.movePointerDrag(x, y)
                else if (BilibiliRequests.enabled) BilibiliRequestHud.movePointer(mc, x, y)
                return
            }
            if (waitForLeftRelease) {
                if (!Mouse.getEventButtonState()) waitForLeftRelease = false
                return
            }
            if (Mouse.getEventButtonState()) {
                // The request card is rendered above the island where their bounds overlap.
                draggingIsland = !onCard && island.beginPointerDrag(x, y)
                // 驾驶舱歌词在灵动岛之后：单击从指着的那一行开始播放
                pressedLyrics = !onCard && !draggingIsland && lyrics.cockpitPointerPress(x, y)
                if (!draggingIsland && !pressedLyrics && BilibiliRequests.enabled) BilibiliRequestHud.press(mc, x, y)
            } else if (draggingIsland) {
                island.movePointerDrag(x, y)
                island.endPointerDrag()
                draggingIsland = false
            } else if (pressedLyrics) {
                pressedLyrics = false
            } else if (BilibiliRequests.enabled) BilibiliRequestHud.release(mc, x, y)
        }

        override fun onGuiClosed() {
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            draggingIsland = false
            pressedLyrics = false
            lyrics.cockpitPointerMove(-1, -1)
        }
    }
}
