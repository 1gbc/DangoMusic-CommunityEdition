package dangomusicce.platform

import dangomusicce.DangoClient
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** 内置播放器在高版本中的界面外壳。 */
class ModernMusicScreen(
    private val parent: Screen?,
) : CompatScreen(Component.literal("DangoMusic Community Edition")) {

    private val screen = DangoClient.newMusicScreen { McCompat.setScreen(parent) }
    private var opened = false

    override fun init() {
        if (!ModernGl.ensureReady()) return
        if (!opened) {
            opened = true
            screen.opened()
        }
    }

    override fun removed() {
        screen.closeSettingsPage()
        screen.login.close()
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    private fun syncIme() = setImeFocus(McCompat.currentScreen() === this && screen.textInputActive)

    override fun onRender() {
        if (!ModernGl.ensureReady()) return
        screen.render(McCompat.renderMenuBackdrop(parent))
        syncIme()
    }

    override fun onMouseClicked(button: Int) {
        screen.mouseClicked(button)
        syncIme()
    }

    override fun onMouseDragged(button: Int) {
        screen.mouseDragged(button)
    }

    override fun onMouseReleased(button: Int) {
        screen.mouseReleased(button)
        syncIme()
    }

    override fun onScroll(delta: Double) {
        val wheel = (delta * 120).toInt()
        if (wheel != 0) screen.scroll(wheel, ModernPlatform.mouseX, ModernPlatform.mouseY)
    }

    override fun onKeyPressed(glfwKey: Int) {
        val key = KeyMap.fromGlfw(glfwKey)
        if (key != Keys.NONE) {
            screen.keyTyped('\u0000', key)
            syncIme()
        }
    }

    override fun onKeyReleased(glfwKey: Int) {
        val key = KeyMap.fromGlfw(glfwKey)
        if (key != Keys.NONE) screen.keyReleased(key)
    }

    override fun onCharTyped(c: Char) {
        screen.keyTyped(c, Keys.NONE)
        syncIme()
    }
}
