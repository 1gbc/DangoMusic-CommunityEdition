package dangomusicce.client;

import java.io.File;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

import dangomusicce.CommonProxy;
import dangomusicce.DangoMusicCE;
import dangomusicce.LegacyData;

public final class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // Players upgrading from DangoMusic GTNH Edition keep their settings (config/dangomusicgtnh is copied once).
        LegacyData.migrateConfig(event.getModConfigurationDirectory());
        super.preInit(event);
        File directory = new File(event.getModConfigurationDirectory(), DangoMusicCE.ID);
        ClientSettings settings = new ClientSettings(directory);
        ClientMusic.start(directory, settings.initialVolume);
    }
}

