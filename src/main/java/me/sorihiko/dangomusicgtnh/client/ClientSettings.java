package me.sorihiko.dangomusicgtnh.client;

import java.io.File;

import me.sorihiko.dangomusicgtnh.music.MusicPlayer;

import net.minecraftforge.common.config.Configuration;

final class ClientSettings {

    final float initialVolume;
    ClientSettings(File directory) {
        Configuration config = new Configuration(new File(directory, "settings.cfg"));
        config.load();
        initialVolume = config.getFloat("volume", "client", MusicPlayer.DEFAULT_VOLUME, 0.0f, 1.0f, "DangoMusic playback volume.");
        if (config.hasChanged()) {
            config.save();
        }
    }

}

