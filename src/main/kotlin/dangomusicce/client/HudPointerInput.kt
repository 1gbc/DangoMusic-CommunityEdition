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
        if (!screen.interacting()) mc.displayGuiScreen(null) else screen.updateHover()
    }

    fun owns(screen: GuiScreen?) = screen is PointerScreen

    fun interacting(screen: GuiScreen?) = (screen as? PointerScreen)?.interacting() == true

    private enum class Gesture { REQUEST, PLAYER_CARD, ISLAND_DRAG, LYRICS }

    private class PointerScreen : GuiScreen() {
        private var cancelled = false
        private var waitForLeftRelease = false
        private var gesture: Gesture? = null

        override fun initGui() {
            // A button already held when the cursor is released cannot start a queue action.
            waitForLeftRelease = Mouse.isCreated() && Mouse.isButtonDown(0)
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            island.clearPlayerCardPointer()
            gesture = null
            updateHover()
        }

        fun interacting() = mc.currentScreen === this && !cancelled && Display.isActive() &&
            held() && mc.theWorld != null &&
            mc.thePlayer?.let { it.isEntityAlive && !it.isPlayerSleeping } == true

        override fun doesGuiPauseGame() = false

        override fun keyTyped(typedChar: Char, keyCode: Int) {
            if (keyCode == Keyboard.KEY_ESCAPE) cancelled = true
        }

        fun updateHover() {
            if (!Mouse.isCreated()) return
            val x = Mouse.getX()
            val y = mc.displayHeight - Mouse.getY() - 1
            val hidden = mc.gameSettings.hideGUI
            val onRequest = BilibiliRequestHud.isVisible(hidden) && BilibiliRequestHud.blocksPointer(x, y)
            pointAt(x, y, onRequest, island.allowsIslandHud(hidden))
        }

        private fun pointAt(x: Int, y: Int, onRequest: Boolean, islandVisible: Boolean): Boolean {
            val onIsland = islandVisible && !onRequest && island.playerCardAt(x, y)
            island.updatePlayerCardPointer(x, y, onIsland)
            if (onRequest || onIsland) lyrics.cockpitPointerMove(-1, -1) else lyrics.cockpitPointerMove(x, y)
            return onIsland
        }

        override fun handleMouseInput() {
            // Never delegate to a screen or key binding behind this transparent screen.
            if (!interacting()) {
                island.clearPlayerCardPointer()
                lyrics.cockpitPointerMove(-1, -1)
                return
            }
            val x = Mouse.getEventX()
            val y = mc.displayHeight - Mouse.getEventY() - 1
            val hudHidden = mc.gameSettings.hideGUI
            val islandVisible = island.allowsIslandHud(hudHidden)
            val requestVisible = BilibiliRequestHud.isVisible(hudHidden)
            if (!islandVisible) {
                if (gesture == Gesture.ISLAND_DRAG) island.endPointerDrag()
                if (gesture == Gesture.PLAYER_CARD) island.clearPlayerCardPointer()
            }
            if (!requestVisible) BilibiliRequestHud.cancelGesture()
            // The HUD layers are painted from cockpit lyrics to island to song requests.
            val onRequest = requestVisible && BilibiliRequestHud.blocksPointer(x, y)
            val onIsland = pointAt(x, y, onRequest, islandVisible)
            val wheel = Mouse.getEventDWheel()
            if (wheel != 0 && gesture == null) {
                when {
                    onRequest -> BilibiliRequestHud.scroll(mc, x, y, wheel)
                    onIsland -> island.scrollPlayerCard(x, y, wheel)
                    else -> if (!lyrics.cockpitPointerScroll(x, y, wheel) && requestVisible) {
                        BilibiliRequestHud.scroll(mc, x, y, wheel)
                    }
                }
            }
            if (Mouse.getEventButton() != 0) {
                when (gesture) {
                    Gesture.PLAYER_CARD -> island.movePlayerCard(x)
                    Gesture.ISLAND_DRAG -> island.movePointerDrag(x, y)
                    Gesture.REQUEST, null -> if (requestVisible) BilibiliRequestHud.movePointer(mc, x, y)
                    Gesture.LYRICS -> Unit
                }
                return
            }
            if (waitForLeftRelease) {
                if (!Mouse.getEventButtonState()) waitForLeftRelease = false
                return
            }
            if (Mouse.getEventButtonState()) {
                gesture = when {
                    onRequest -> {
                        BilibiliRequestHud.press(mc, x, y)
                        Gesture.REQUEST
                    }
                    onIsland && island.pressPlayerCard(x, y) -> Gesture.PLAYER_CARD
                    islandVisible && island.beginPointerDrag(x, y) -> Gesture.ISLAND_DRAG
                    onIsland -> null
                    lyrics.cockpitPointerPress(x, y) -> Gesture.LYRICS
                    else -> null
                }
                return
            }
            val captured = gesture
            gesture = null
            when (captured) {
                Gesture.PLAYER_CARD -> island.releasePlayerCard(x, y, islandVisible && !onRequest)
                Gesture.ISLAND_DRAG -> {
                    island.movePointerDrag(x, y)
                    island.endPointerDrag()
                }
                Gesture.REQUEST -> if (requestVisible) BilibiliRequestHud.release(mc, x, y)
                else -> Unit
            }
        }

        override fun onGuiClosed() {
            BilibiliRequestHud.cancelGesture()
            island.clearPlayerCardPointer()
            island.endPointerDrag()
            gesture = null
            lyrics.cockpitPointerMove(-1, -1)
        }
    }
}
