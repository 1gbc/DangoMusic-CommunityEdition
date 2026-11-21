package me.sorihiko.dangomusicgtnh.client

import me.sorihiko.dangomusicgtnh.DangoMusic
import me.sorihiko.dangomusicgtnh.legacy.LegacyKeyBindings
import me.sorihiko.dangomusicgtnh.module.impls.music.DynamicIslandModule
import me.sorihiko.dangomusicgtnh.platform.PlayerAction
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.Display

internal object HudPointerInput {
    private var wasHeld = false

    private val island get() = DangoMusic.instance.moduleManager.get(DynamicIslandModule::class.java)

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

        override fun initGui() {
            // A button already held when the cursor is released cannot start a queue action.
            waitForLeftRelease = Mouse.isCreated() && Mouse.isButtonDown(0)
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            draggingIsland = false
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
            if (!interacting()) return
            val x = Mouse.getEventX()
            val y = mc.displayHeight - Mouse.getEventY() - 1
            val wheel = Mouse.getEventDWheel()
            if (wheel != 0 && !draggingIsland && BilibiliRequests.enabled) BilibiliRequestHud.scroll(mc, x, y, wheel)
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
                draggingIsland = !BilibiliRequestHud.blocksPointer(x, y) && island.beginPointerDrag(x, y)
                if (!draggingIsland && BilibiliRequests.enabled) BilibiliRequestHud.press(mc, x, y)
            } else if (draggingIsland) {
                island.movePointerDrag(x, y)
                island.endPointerDrag()
                draggingIsland = false
            } else if (BilibiliRequests.enabled) BilibiliRequestHud.release(mc, x, y)
        }

        override fun onGuiClosed() {
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            draggingIsland = false
        }
    }
}
