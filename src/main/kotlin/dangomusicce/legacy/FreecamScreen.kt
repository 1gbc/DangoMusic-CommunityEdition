package dangomusicce.legacy

import com.mojang.authlib.GameProfile
import dangomusicce.DangoClient
import dangomusicce.DangoMusic
import dangomusicce.i18n.I18n
import dangomusicce.module.impls.music.NeteaseMusicModule
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.EntityLivingBase
import net.minecraft.world.World
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.Display
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 内置调试自由视角：一个透明的、不暂停游戏的 [GuiScreen]，把镜头挂到一只只在客户端存在、
 * 从不加入世界实体列表的 [EntityOtherPlayerMP] 上（灵魂出窍），方便检查 3D 歌词的摆放。
 *
 * - WASD / Space / Shift 移动，按冲刺键（[net.minecraft.client.settings.GameSettings.keyBindSprint]）加速
 * - 鼠标转视角，Tab / Esc 回到进入前的界面
 * - 玩家死亡、切换世界、断开连接、失去窗口焦点或界面被意外顶掉时，幂等地把镜头还给原实体
 */
class FreecamScreen(
    /** 退出后回到哪个界面（null = 回到游戏） */
    private val returnScreen: GuiScreen?,
) : GuiScreen() {

    private var cam: EntityOtherPlayerMP? = null
    private var entryWorld: World? = null
    private var entryPlayer: EntityLivingBase? = null
    private var previousViewEntity: EntityLivingBase? = null
    private var previousThirdPerson = 0
    private var initialized = false
    private var cleanedUp = false

    override fun doesGuiPauseGame() = false

    override fun initGui() {
        // 窗口缩放会重新触发 initGui：已经持有假镜头时直接保持，不把假镜头自身覆盖进恢复目标。
        if (cam != null) {
            if (Mouse.isCreated()) Mouse.setGrabbed(true)
            return
        }
        if (!canEnter(mc)) {
            mc.displayGuiScreen(returnScreen)
            return
        }
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        // 只建在客户端、不加入 loadedEntityList：不会被渲染、不会参与物理、不会同步给服务器。
        val c = EntityOtherPlayerMP(world, GameProfile(UUID.randomUUID(), "DangoFreecam"))
        c.setPositionAndRotation(player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch)
        // 逐字段复制，保证 EntityRenderer 的镜头高度 / 朝向和玩家完全一致，切换瞬间零跳变。
        c.posX = player.posX; c.posY = player.posY; c.posZ = player.posZ
        c.prevPosX = player.prevPosX; c.prevPosY = player.prevPosY; c.prevPosZ = player.prevPosZ
        c.lastTickPosX = player.lastTickPosX; c.lastTickPosY = player.lastTickPosY; c.lastTickPosZ = player.lastTickPosZ
        c.yOffset = player.yOffset
        c.eyeHeight = player.eyeHeight
        c.rotationYaw = player.rotationYaw; c.rotationPitch = player.rotationPitch
        c.prevRotationYaw = player.prevRotationYaw; c.prevRotationPitch = player.prevRotationPitch
        c.rotationYawHead = player.rotationYawHead; c.prevRotationYawHead = player.prevRotationYawHead

        cam = c
        entryWorld = world
        entryPlayer = player
        previousViewEntity = mc.renderViewEntity
        previousThirdPerson = mc.gameSettings.thirdPersonView
        initialized = true
        cleanedUp = false
        mc.gameSettings.thirdPersonView = 0
        mc.renderViewEntity = c
        active = this
        KeyBinding.unPressAllKeys()
        if (Mouse.isCreated()) {
            Mouse.setGrabbed(true)
            Mouse.getDX(); Mouse.getDY() // 丢弃接管鼠标前累积的位移
        }
    }

    override fun updateScreen() {
        val c = cam ?: return
        if (!Display.isActive() || mc.theWorld == null || c.worldObj !== mc.theWorld ||
            mc.thePlayer == null || mc.thePlayer !== entryPlayer || !mc.thePlayer.isEntityAlive ||
            mc.renderViewEntity !== c
        ) {
            exitToDebug()
            return
        }

        // 20Hz tick：先把上一位置记好，再按输入平移。
        c.prevPosX = c.posX; c.prevPosY = c.posY; c.prevPosZ = c.posZ
        c.lastTickPosX = c.posX; c.lastTickPosY = c.posY; c.lastTickPosZ = c.posZ
        c.prevRotationYaw = c.rotationYaw
        c.prevRotationPitch = c.rotationPitch
        c.prevRotationYawHead = c.rotationYawHead

        var fwd = (if (keyDown(mc.gameSettings.keyBindForward.keyCode)) 1.0 else 0.0) -
            (if (keyDown(mc.gameSettings.keyBindBack.keyCode)) 1.0 else 0.0)
        var strafe = (if (keyDown(mc.gameSettings.keyBindLeft.keyCode)) 1.0 else 0.0) -
            (if (keyDown(mc.gameSettings.keyBindRight.keyCode)) 1.0 else 0.0)
        val up = (if (keyDown(mc.gameSettings.keyBindJump.keyCode)) 1.0 else 0.0) -
            (if (keyDown(mc.gameSettings.keyBindSneak.keyCode)) 1.0 else 0.0)
        val sprint = keyDown(mc.gameSettings.keyBindSprint.keyCode)

        val horizMag = hypot(fwd, strafe)
        if (horizMag > 1.0) {
            fwd /= horizMag
            strafe /= horizMag
        }
        val speed = if (sprint) 1.1 else 0.45
        val yawRad = Math.toRadians(c.rotationYaw.toDouble())
        val s = sin(yawRad)
        val k = cos(yawRad)
        // 和 EntityLivingBase.moveFlying 同一套朝向约定：yaw=0 朝 +Z，A=左移为正 strafe；用 setPosition 同步包围盒。
        val nx = c.posX + (strafe * k - fwd * s) * speed
        val ny = c.posY + up * speed
        val nz = c.posZ + (fwd * k + strafe * s) * speed
        c.setPosition(nx, ny, nz)
    }

    /** 鼠标接管后每帧读取累积位移，用原版灵敏度换算成视角变化。 */
    private fun updateLook() {
        val c = cam ?: return
        if (!Mouse.isCreated() || !Mouse.isGrabbed()) return
        val f = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f
        val s = f * f * f * 8.0f * 0.15f
        val dx = Mouse.getDX().toFloat()
        val dy = Mouse.getDY().toFloat()
        if (dx != 0f || dy != 0f) {
            c.rotationYaw += dx * s
            c.rotationPitch = (c.rotationPitch - dy * s).coerceIn(-90f, 90f)
            c.rotationYawHead = c.rotationYaw
        }
    }

    override fun handleMouseInput() {
        updateLook()
        super.handleMouseInput()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        updateLook()
        drawBadge()
    }

    private fun drawBadge() {
        val text = I18n.text("Freecam · WASD/Space/Shift to move · Mouse to look · Tab/Esc to return")
        val hud = DangoClient.gfx.hud
        hud.begin()
        val w = mc.fontRenderer.getStringWidth(text) + 8
        val h = mc.fontRenderer.FONT_HEIGHT + 6
        hud.rect(4, 4, 4 + w, 4 + h, 0x80000000.toInt())
        hud.text(text, 8f, 7f, 0xFFFFFFFF.toInt(), true)
        hud.end()
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_TAB) {
            exitToDebug()
            return
        }
        if (Keyboard.isRepeatEvent()) return
        if (DangoMusic.instance.platform.activePlayerKeyModifier() == null && isMoveKey(keyCode)) return
        dispatchMusicHotkey(keyCode)
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        val code = mouseButton - 100
        if (DangoMusic.instance.platform.activePlayerKeyModifier() == null && isMoveKey(code)) return
        dispatchMusicHotkey(code)
    }

    private fun isMoveKey(code: Int): Boolean {
        val gs = mc.gameSettings
        return code == gs.keyBindForward.keyCode ||
            code == gs.keyBindBack.keyCode ||
            code == gs.keyBindLeft.keyCode ||
            code == gs.keyBindRight.keyCode ||
            code == gs.keyBindJump.keyCode ||
            code == gs.keyBindSneak.keyCode ||
            code == gs.keyBindSprint.keyCode
    }

    private fun dispatchMusicHotkey(code: Int) {
        try {
            DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).handleHotkey(code)
        } catch (_: Exception) {
        }
    }

    /** 死亡时交给原版显示重生界面。 */
    private fun exitToDebug() {
        cleanup()
        mc.displayGuiScreen(if (mc.theWorld == null || mc.thePlayer?.isEntityAlive == false) null else returnScreen)
    }

    override fun onGuiClosed() {
        cleanup()
    }

    /** 幂等清理：镜头还给原实体、恢复第三人称、释放鼠标、松开按键。 */
    private fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        val c = cam
        cam = null
        entryWorld = null
        entryPlayer = null
        if (active === this) active = null
        val world = mc.theWorld
        if (c != null && mc.renderViewEntity === c) {
            mc.renderViewEntity = when {
                world == null -> null
                previousViewEntity?.worldObj === world -> previousViewEntity
                mc.thePlayer?.worldObj === world -> mc.thePlayer
                else -> null
            }
        }
        if (initialized) {
            mc.gameSettings.thirdPersonView = previousThirdPerson
            initialized = false
        }
        previousViewEntity = null
        if (Mouse.isCreated()) Mouse.setGrabbed(false)
        KeyBinding.unPressAllKeys()
    }

    private fun keyDown(code: Int): Boolean = when {
        code in -100..-1 -> Mouse.isCreated() && Mouse.isButtonDown(code + 100)
        code in 1 until Keyboard.KEYBOARD_SIZE -> Keyboard.isKeyDown(code)
        else -> false
    }

    companion object {
        /** 当前活动的 FreecamScreen（用于 guardTick / ownsViewEntity）。 */
        @Volatile
        private var active: FreecamScreen? = null

        fun canEnter(mc: Minecraft): Boolean =
            mc.theWorld != null && mc.thePlayer != null && mc.thePlayer.isEntityAlive &&
                (mc.renderViewEntity == null || mc.renderViewEntity === mc.thePlayer)

        fun ownsViewEntity(entity: EntityLivingBase?): Boolean = entity != null && active?.cam === entity

        fun isActive(screen: GuiScreen?): Boolean = screen is FreecamScreen

        /**
         * 每游戏 tick 兜底：世界变了、玩家死了、实体被替换或界面被意外顶掉时幂等地清理假镜头。
         * [dangomusicce.client.ClientRenderBridge.clientTick] 的 START 阶段调用。
         */
        fun guardTick(mc: Minecraft) {
            val screen = active ?: return
            val c = screen.cam
            if (mc.currentScreen !== screen) {
                screen.cleanup()
                return
            }
            if (c == null || mc.theWorld == null || c.worldObj !== mc.theWorld ||
                mc.thePlayer == null || mc.thePlayer !== screen.entryPlayer ||
                !mc.thePlayer.isEntityAlive || mc.renderViewEntity !== c
            ) {
                screen.exitToDebug()
            }
        }
    }
}
