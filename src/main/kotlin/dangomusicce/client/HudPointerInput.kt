package dangomusicce.client

import dangomusicce.DangoMusic
import dangomusicce.module.impls.music.DynamicIslandModule
import dangomusicce.module.impls.music.LyricsModule
import dangomusicce.platform.ModernKeyBindings
import dangomusicce.platform.ModernPlatform
import dangomusicce.platform.PlayerAction
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/** 按住 HUD 交互键时打开透明指针屏，按点歌卡片 > 灵动岛 > 驾驶舱歌词顺序分发点击/拖拽/滚轮。 */
internal object HudPointerInput {
    private var wasHeld = false

    private val island get() = DangoMusic.instance.moduleManager.get(DynamicIslandModule::class.java)
    private val lyrics get() = DangoMusic.instance.moduleManager.get(LyricsModule::class.java)

    private fun held() = ModernKeyBindings.isHeld(PlayerAction.BILIBILI_REQUESTS)

    fun startTick(mc: Minecraft) {
        val held = held()
        val newlyHeld = held && !wasHeld
        wasHeld = held
        if (newlyHeld && !mc.options.hideGui && mc.isWindowActive && mc.mouseHandler.isMouseGrabbed &&
            mc.screen == null && mc.level != null &&
            mc.player?.let { it.isAlive && !it.isSleeping } == true
        ) {
            mc.setScreen(PointerScreen())
        }
    }

    fun endTick(mc: Minecraft) {
        val screen = mc.screen as? PointerScreen ?: return
        // Re-grabbing after queued screen inputs drain avoids leaking clicks or scrolls into the world.
        if (!screen.interacting()) mc.setScreen(null)
    }

    fun owns(screen: Screen?) = screen is PointerScreen

    fun interacting(screen: Screen?) = (screen as? PointerScreen)?.interacting() == true

    private class PointerScreen : Screen(Component.empty()) {
        private var cancelled = false
        private var waitForLeftRelease = false
        private var draggingIsland = false
        private var pressedLyrics = false

        private val mc get() = Minecraft.getInstance()

        private fun toPixelX(guiX: Double): Int {
            val guiW = mc.window.guiScaledWidth
            return if (guiW > 0) (guiX * mc.window.width / guiW).roundToInt() else ModernPlatform.mouseX
        }

        private fun toPixelY(guiY: Double): Int {
            val guiH = mc.window.guiScaledHeight
            return if (guiH > 0) (guiY * mc.window.height / guiH).roundToInt() else ModernPlatform.mouseY
        }

        override fun init() {
            // A button already held when the cursor is released cannot start a queue action.
            waitForLeftRelease = ModernPlatform.isMouseDown(0)
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            draggingIsland = false
            pressedLyrics = false
            lyrics.cockpitPointerMove(ModernPlatform.mouseX, ModernPlatform.mouseY)
        }

        fun interacting() = mc.screen === this && !cancelled && !mc.options.hideGui && mc.isWindowActive &&
            held() && mc.level != null &&
            mc.player?.let { it.isAlive && !it.isSleeping } == true

        override fun isPauseScreen() = false

        override fun shouldCloseOnEsc() = false

        override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

        override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelled = true
                return true
            }
            return true
        }

        private fun updateMove(px: Int, py: Int) {
            if (!interacting()) {
                lyrics.cockpitPointerMove(-1, -1)
                return
            }
            val onCard = BilibiliRequestHud.blocksPointer(px, py)
            if (onCard) lyrics.cockpitPointerMove(-1, -1) else lyrics.cockpitPointerMove(px, py)
            if (waitForLeftRelease) {
                if (!ModernPlatform.isMouseDown(0)) waitForLeftRelease = false
                return
            }
            if (draggingIsland) island.movePointerDrag(px, py)
            else if (BilibiliRequests.enabled) BilibiliRequestHud.movePointer(mc, px, py)
        }

        override fun mouseMoved(mouseX: Double, mouseY: Double) {
            updateMove(toPixelX(mouseX), toPixelY(mouseY))
        }

        override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
            updateMove(toPixelX(mouseX), toPixelY(mouseY))
            return true
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button != 0) return true
            if (!interacting()) {
                lyrics.cockpitPointerMove(-1, -1)
                return true
            }
            if (waitForLeftRelease) {
                if (ModernPlatform.isMouseDown(0)) return true
                waitForLeftRelease = false
            }
            val px = toPixelX(mouseX)
            val py = toPixelY(mouseY)
            val onCard = BilibiliRequestHud.blocksPointer(px, py)
            if (onCard) lyrics.cockpitPointerMove(-1, -1) else lyrics.cockpitPointerMove(px, py)
            draggingIsland = !onCard && island.beginPointerDrag(px, py)
            pressedLyrics = !onCard && !draggingIsland && lyrics.cockpitPointerPress(px, py)
            if (!draggingIsland && !pressedLyrics && BilibiliRequests.enabled) {
                BilibiliRequestHud.press(mc, px, py)
            }
            return true
        }

        override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button != 0) return true
            if (waitForLeftRelease) {
                waitForLeftRelease = false
                return true
            }
            if (!interacting()) {
                lyrics.cockpitPointerMove(-1, -1)
                return true
            }
            val px = toPixelX(mouseX)
            val py = toPixelY(mouseY)
            val onCard = BilibiliRequestHud.blocksPointer(px, py)
            if (onCard) lyrics.cockpitPointerMove(-1, -1) else lyrics.cockpitPointerMove(px, py)
            if (draggingIsland) {
                island.movePointerDrag(px, py)
                island.endPointerDrag()
                draggingIsland = false
            } else if (pressedLyrics) {
                pressedLyrics = false
            } else if (BilibiliRequests.enabled) {
                BilibiliRequestHud.release(mc, px, py)
            }
            return true
        }

        override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
            if (!interacting()) {
                lyrics.cockpitPointerMove(-1, -1)
                return true
            }
            val wheel = (scrollY * 120.0).roundToInt()
            if (wheel != 0 && !draggingIsland) {
                val px = toPixelX(mouseX)
                val py = toPixelY(mouseY)
                val onCard = BilibiliRequestHud.blocksPointer(px, py)
                if (onCard) lyrics.cockpitPointerMove(-1, -1) else lyrics.cockpitPointerMove(px, py)
                if (onCard || !lyrics.cockpitPointerScroll(px, py, wheel)) {
                    if (BilibiliRequests.enabled) BilibiliRequestHud.scroll(mc, px, py, wheel)
                }
            }
            return true
        }

        override fun removed() {
            BilibiliRequestHud.cancelGesture()
            island.endPointerDrag()
            draggingIsland = false
            pressedLyrics = false
            lyrics.cockpitPointerMove(-1, -1)
            super.removed()
        }
    }
}
