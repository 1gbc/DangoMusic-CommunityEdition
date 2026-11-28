package dangomusicce.client

import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent as ForgeTickEvent
import dangomusicce.DangoClient
import dangomusicce.DangoLogger
import dangomusicce.DangoMusic
import dangomusicce.events.GameMouseEvent
import dangomusicce.events.HudRenderEvent
import dangomusicce.events.TickEvent
import dangomusicce.events.WorldRenderEndEvent
import dangomusicce.legacy.FreecamScreen
import dangomusicce.legacy.LegacyGl
import dangomusicce.module.impls.music.DynamicIslandModule
import dangomusicce.module.impls.music.LyricsModule
import dangomusicce.network.NearbyClient
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.client.event.RenderWorldLastEvent

internal object ClientRenderBridge {
    private var graphicsAttempted = false
    private var playerListResolution: ScaledResolution? = null
    var graphicsReady = false
        private set

    @SubscribeEvent
    fun clientTick(event: ForgeTickEvent.ClientTickEvent) {
        if (event.phase == ForgeTickEvent.Phase.END) {
            if (graphicsReady) HudPointerInput.endTick(Minecraft.getMinecraft())
            return
        }
        if (!graphicsAttempted) {
            graphicsAttempted = true
            try {
                LegacyGl.init()
                graphicsReady = true
                DangoLogger.info("GTNH glass renderer initialized")
            } catch (error: Exception) {
                DangoLogger.error("GTNH glass renderer initialization failed: {}", error)
            } catch (error: LinkageError) {
                DangoLogger.error("GTNH glass renderer binding is unavailable: {}", error)
            }
        }
        DangoMusic.callEvent(TickEvent)
        NearbyClient.tick()
        FreecamScreen.guardTick(Minecraft.getMinecraft())
        if (graphicsReady) HudPointerInput.startTick(Minecraft.getMinecraft())
    }

    @SubscribeEvent
    fun menu(event: GuiScreenEvent.DrawScreenEvent.Post) {
        if (graphicsReady && DangoClient.platform.inMainMenu) DangoClient.renderMainMenuNowPlaying()
    }

    @SubscribeEvent
    fun overlay(event: RenderGameOverlayEvent.Post) {
        if (!graphicsReady) return
        when (event.type) {
            RenderGameOverlayEvent.ElementType.PLAYER_LIST -> playerListResolution = event.resolution
            RenderGameOverlayEvent.ElementType.ALL -> {
                val drawn = playerListResolution === event.resolution
                playerListResolution = null
                DangoMusic.callEvent(HudRenderEvent(if (drawn) PlayerListOverlay.bounds(event.resolution) else null))
                BilibiliRequestHud.render(Minecraft.getMinecraft())
            }
            else -> Unit
        }
    }

    @SubscribeEvent
    fun hiddenHud(event: ForgeTickEvent.RenderTickEvent) {
        if (!graphicsReady || event.phase != ForgeTickEvent.Phase.END) return
        val mc = Minecraft.getMinecraft()
        // F1 skips the Forge overlay in the world. The main framebuffer is still bound here.
        if (!mc.gameSettings.hideGUI || mc.currentScreen != null || mc.theWorld == null || mc.thePlayer == null) return
        val modules = DangoMusic.instance.moduleManager
        modules.get(DynamicIslandModule::class.java).render(HudRenderEvent())
        modules.get(LyricsModule::class.java).renderCockpit()
        BilibiliRequestHud.render(mc)
    }

    @SubscribeEvent
    fun crosshair(event: RenderGameOverlayEvent.Pre) {
        val screen = Minecraft.getMinecraft().currentScreen
        if (DebugHudSession.active || FreecamScreen.isActive(screen)) {
            when (event.type) {
                RenderGameOverlayEvent.ElementType.HOTBAR,
                RenderGameOverlayEvent.ElementType.HEALTH,
                RenderGameOverlayEvent.ElementType.ARMOR,
                RenderGameOverlayEvent.ElementType.FOOD,
                RenderGameOverlayEvent.ElementType.EXPERIENCE,
                RenderGameOverlayEvent.ElementType.AIR,
                RenderGameOverlayEvent.ElementType.HEALTHMOUNT,
                RenderGameOverlayEvent.ElementType.JUMPBAR,
                RenderGameOverlayEvent.ElementType.CROSSHAIRS,
                RenderGameOverlayEvent.ElementType.BOSSHEALTH,
                RenderGameOverlayEvent.ElementType.PORTAL,
                RenderGameOverlayEvent.ElementType.HELMET,
                RenderGameOverlayEvent.ElementType.CHAT -> {
                    event.isCanceled = true
                    return
                }
                else -> Unit
            }
        }
        if (event.type == RenderGameOverlayEvent.ElementType.CROSSHAIRS &&
            HudPointerInput.owns(screen)
        ) event.isCanceled = true
    }

    /** 游戏里（没开界面）的鼠标输入：模组用掉的就不再交给游戏 */
    @SubscribeEvent
    fun mouse(event: MouseEvent) {
        if (!graphicsReady || Minecraft.getMinecraft().currentScreen != null) return
        if (event.dwheel == 0 && event.button < 0) return
        val e = GameMouseEvent(event.dwheel, event.button, event.buttonstate)
        DangoMusic.callEvent(e)
        if (e.consumed) event.isCanceled = true
    }

    @SubscribeEvent
    fun world(event: RenderWorldLastEvent) {
        if (!graphicsReady) return
        val mc = Minecraft.getMinecraft()
        val eyeY = RenderCameraPose.activeViewEntity(mc)?.let { RenderCameraPose.eyeY(it, event.partialTicks) }
        DangoMusic.callEvent(WorldRenderEndEvent(event.partialTicks, eyeY))
    }
}
