package dangomusicce.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * 公共 Mod 入口：服务端与客户端都会加载，严禁在此引用任何客户端类。
 */
@Mod("dangomusicce")
public final class DangoMusicNeoForge {
    public DangoMusicNeoForge(IEventBus modBus) {
        // M4 将在此注册双端网络通道与服务端指令。
    }
}
