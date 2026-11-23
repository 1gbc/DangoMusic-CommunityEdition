package dangomusicce.neoforge;

import dangomusicce.platform.DanceRender;
import dangomusicce.platform.ModernDango;
import dangomusicce.platform.ModernKeyBindings;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端入口：仅在物理客户端加载，负责按键注册、初始化与渲染/tick 事件转发。
 */
@Mod(value = "dangomusicce", dist = Dist.CLIENT)
public final class DangoMusicNeoForgeClient {
    public DangoMusicNeoForgeClient(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> ModernKeyBindings.register(event::register));
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(ModernDango::start));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> ModernDango.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ModernDango.tickEnd());
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForgeClient::onRenderLevel);
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Pre event) -> ModernDango.beginGuiFrame());
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForgeClient::onCrosshairPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, DangoMusicNeoForgeClient::onTabLayerPre);
        NeoForge.EVENT_BUS.addListener(DangoMusicNeoForgeClient::onCameraOverlaysPost);
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> ModernDango.hudPost(event.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post event) -> ModernDango.mainMenuRender(event.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, DangoMusicNeoForgeClient::onRenderLivingPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, DangoMusicNeoForgeClient::onRenderLivingPost);
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            DanceRender.beginWorldPass();
            return;
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            DanceRender.endWorldPass();
            return;
        }
        if (stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        DanceRender.endWorldPass();
        float partialTicks = event.getCamera().getPartialTickTime();
        Minecraft mc = Minecraft.getInstance();
        Double eyeY = mc.player != null ? mc.player.getEyePosition(partialTicks).y : null;
        ModernDango.worldRenderEnd(partialTicks, eyeY, event.getModelViewMatrix());
    }

    private static void onCrosshairPre(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(VanillaGuiLayers.CROSSHAIR) && ModernDango.isPointerScreenOpen()) {
            event.setCanceled(true);
        }
    }

    private static void onCameraOverlaysPost(RenderGuiLayerEvent.Post event) {
        if (event.getName().equals(VanillaGuiLayers.CAMERA_OVERLAYS)) {
            ModernDango.hudLayer(event.getGuiGraphics());
        }
    }

    private static void onTabLayerPre(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(VanillaGuiLayers.TAB_LIST)) {
            ModernDango.onTabLayerPre(event.isCanceled());
        }
    }

    private static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        DanceRender.onLivingPre(event.getEntity(), event.getPoseStack());
    }

    private static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        DanceRender.onLivingPost(event.getEntity());
    }
}
