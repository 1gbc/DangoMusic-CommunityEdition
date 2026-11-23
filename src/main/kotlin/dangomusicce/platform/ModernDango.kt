package dangomusicce.platform

import dangomusicce.DangoLogger
import dangomusicce.DangoMusic
import dangomusicce.LegacyData
import dangomusicce.client.BilibiliRequestHud
import dangomusicce.client.ClientMusic
import dangomusicce.client.HudPointerInput
import dangomusicce.client.PlayerListOverlay
import dangomusicce.events.HudRenderEvent
import dangomusicce.events.TickEvent
import dangomusicce.events.WorldRenderEndEvent
import dangomusicce.network.NearbyClient
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

/** 高版本客户端生命周期转发入口（各加载器客户端入口共用）。 */
object ModernDango {
    @Volatile
    @JvmStatic
    var started = false
        private set

    private var hudRenderedThisFrame = false

    @JvmStatic
    fun start() {
        if (started) return
        JitWorkaround.install()
        val configDir = ModernPlatform.configDir
        LegacyData.migrateConfig(configDir.parent.toFile())
        ClientMusic.start(configDir.toFile())
        started = true
    }

    @JvmStatic
    fun tick() {
        if (!started) return
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) BilibiliRequestHud.clearHitState()
        if (!McCompat.glAvailable) {
            McCompat.notifyNoGl()
            DangoMusic.callEvent(TickEvent)
            HudPointerInput.startTick(mc)
            return
        }
        ModernGl.ensureReady()
        try {
            NearbyClient.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music nearby tick failed: {}", t.toString())
        }
        try {
            DangoMusic.callEvent(TickEvent)
        } catch (t: Throwable) {
            DangoLogger.error("DangoMusic tick failed: {}", t.toString())
        }
        HudPointerInput.startTick(mc)
    }

    @JvmStatic
    fun tickEnd() {
        if (!started) return
        HudPointerInput.endTick(Minecraft.getInstance())
    }

    @JvmStatic
    fun beginGuiFrame() {
        hudRenderedThisFrame = false
        PlayerListOverlay.beginFrame()
    }

    @JvmStatic
    fun onTabLayerPre(canceled: Boolean) {
        PlayerListOverlay.onTabLayerPre(canceled)
    }

    @JvmStatic
    fun isPointerScreenOpen(): Boolean = HudPointerInput.owns(Minecraft.getInstance().screen)

    @JvmOverloads
    @JvmStatic
    fun worldRenderEnd(partialTicks: Float, playerEyeY: Double?, modelView: Matrix4f? = null) {
        if (!started || !McCompat.glAvailable || !ModernGl.ensureReady()) return
        Perf.frame()
        if (modelView != null) {
            ModernWorld.basePose.set(modelView)
        } else {
            ModernWorld.basePose.identity()
                .rotateX(Math.toRadians(McCompat.cameraXRot().toDouble()).toFloat())
                .rotateY(Math.toRadians(McCompat.cameraYRot().toDouble() + 180.0).toFloat())
        }
        Perf.begin("world")
        McCompat.beginWorld()
        McCompat.pushIdentityModelView()
        try {
            DangoMusic.callEvent(WorldRenderEndEvent(partialTicks, playerEyeY))
        } finally {
            Imm.flush()
            BorrowedGlyphTextures.endPass()
            Gl.forget()
            McCompat.popModelView()
            McCompat.endWorld()
            Perf.end("world")
        }
    }

    @JvmStatic
    fun hudLayer(graphics: Any) {
        hudRenderedThisFrame = true
        if (!started || !McCompat.glAvailable || !ModernGl.ensureReady()) return
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        Perf.begin("hud")
        try {
            McCompat.hudOverlay(graphics) {
                DangoMusic.callEvent(HudRenderEvent(PlayerListOverlay.bounds(mc)))
            }
        } finally {
            Imm.flush()
            BorrowedGlyphTextures.endPass()
            Gl.forget()
            Perf.end("hud")
        }
    }

    @JvmStatic
    fun hudPost(graphics: Any) {
        PlayerListOverlay.endFrame()
        if (!started || !McCompat.glAvailable || !ModernGl.ensureReady()) return
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) {
            BilibiliRequestHud.clearHitState()
            return
        }
        if (!hudRenderedThisFrame) hudLayer(graphics)
        Perf.begin("hud")
        try {
            McCompat.hudOverlay(graphics) {
                BilibiliRequestHud.render(mc)
            }
        } finally {
            Imm.flush()
            BorrowedGlyphTextures.endPass()
            Gl.forget()
            Perf.end("hud")
        }
    }

    @JvmStatic
    fun mainMenuRender(graphics: Any) {
        if (!started || !McCompat.glAvailable || !ModernPlatform.inMainMenu || !ModernGl.ensureReady()) return
        Perf.begin("menu")
        try {
            McCompat.mainMenuNowPlaying(graphics)
        } finally {
            Imm.flush()
            BorrowedGlyphTextures.endPass()
            Gl.forget()
            Perf.end("menu")
        }
    }
}
