package dangomusicce.platform

import dangomusicce.DangoMusic
import dangomusicce.Tags
import dangomusicce.module.impls.music.NeteaseMusicModule
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * M0 占位界面：验证 1.21.1 NeoForge 按键触发、开关与回到前一个界面。
 */
internal class ModernPlaceholderScreen(
    private val parent: Screen?,
) : Screen(Component.literal("DangoMusic Community Edition")) {

    override fun init() {
        super.init()
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds((width - 120) / 2, height / 2 + 28, 120, 20)
                .build(),
        )
    }

    override fun isPauseScreen(): Boolean = false

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(
            font,
            "DangoMusic Community Edition",
            width / 2,
            height / 2 - 28,
            0xFFFFFFFF.toInt(),
        )
        graphics.drawCenteredString(
            font,
            "M0 Skeleton Loaded (1.21.1 NeoForge) · v${Tags.VERSION}",
            width / 2,
            height / 2 - 10,
            0xFFA0D8FF.toInt(),
        )
        graphics.drawCenteredString(
            font,
            "Press Esc or your player key to close",
            width / 2,
            height / 2 + 6,
            0xFFB0B0B0.toInt(),
        )
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = KeyMap.fromGlfw(keyCode)
        if (key != Keys.NONE && tryCloseWithOpenKey(key)) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (tryCloseWithOpenKey(Keys.mouse(button))) return true
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun tryCloseWithOpenKey(key: Int): Boolean {
        val module = runCatching {
            DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java)
        }.getOrNull() ?: return false
        if (!module.matchesOpenKey(key)) return false
        module.consumeOpenKey()
        onClose()
        return true
    }

    override fun onClose() {
        McCompat.setScreen(parent)
    }
}
