package dangomusicce.neoforge;

import dangomusicce.platform.ModernDango;
import dangomusicce.platform.ModernKeyBindings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端入口：仅在物理客户端加载，负责按键注册、初始化与客户端 tick 转发。
 */
@Mod(value = "dangomusicce", dist = Dist.CLIENT)
public final class DangoMusicNeoForgeClient {
    public DangoMusicNeoForgeClient(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> ModernKeyBindings.register(event::register));
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(ModernDango::start));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> ModernDango.tick());
    }
}
