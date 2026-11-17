import java.lang.reflect.*;
import java.util.*;
import me.sorihiko.dangomusicgtnh.music.*;
import me.sorihiko.dangomusicgtnh.lyrics.*;
import me.sorihiko.dangomusicgtnh.module.impls.music.LyricsModule;

/** Exercise the actual renderer clock updater without a Minecraft world or GPU. */
public class AppleWorldClockCheck {
    private static void checkReports() {
        long origin = 1_000_000_000L;
        ApplePlaybackClock clock = new ApplePlaybackClock().report(1000, origin, 0, false);
        long nextReport = origin + 120_000_000L;
        if (clock.positionAt(nextReport) != 1120) throw new AssertionError("Playback should advance continuously");
        ApplePlaybackClock corrected = clock.report(1090, nextReport, 0, true);
        if (corrected.positionAt(nextReport) != 1120 || corrected.positionAt(nextReport + 250_000_000L) != 1340)
            throw new AssertionError("Small telemetry corrections should converge smoothly");
        if (corrected.report(1090, nextReport + 700_000_000L, 0, true) != corrected ||
            corrected.positionAt(nextReport + 2_000_000_000L) != 2090)
            throw new AssertionError("Duplicate positions must not extend the projection");
        ApplePlaybackClock seek = corrected.report(1080, nextReport + 10_000_000L, 1, true);
        if (seek.positionAt(nextReport + 10_000_000L) != 1080)
            throw new AssertionError("Even a small confirmed seek must reset smoothing");
        if (seek.report(0, nextReport + 20_000_000L, 1, false).positionAt(nextReport + 20_000_000L) != 0)
            throw new AssertionError("A new track must not inherit the previous clock");
    }
    public static void main(String[] args)throws Exception {
        LyricsModule renderer=new LyricsModule();
        NeteaseTrack track=new NeteaseTrack(1,"Fixture",List.of("Singer"),20000,"",MusicSource.APPLE,"fixture");
        Field cache=LyricsModule.class.getDeclaredField("cache");cache.setAccessible(true);
        ((Map)cache.get(renderer)).put(track.getKey(),new Lyrics(List.of(new LyricLine(0,"One sentence"),new LyricLine(10000,"Next sentence")),"Fixture"));
        Method update=LyricsModule.class.getDeclaredMethod("updateClock");update.setAccessible(true);
        Field index=LyricsModule.class.getDeclaredField("lastIndex");index.setAccessible(true);
        MusicSources.INSTANCE.setSelected(MusicSource.APPLE);
        MusicPlayer.INSTANCE.acceptAppleState(track,1000,true);update.invoke(renderer);
        Thread.sleep(65);
        if (MusicPlayer.INSTANCE.getPositionMs() <= 1000)
            throw new AssertionError("Apple Music progress must advance between telemetry reports");
        index.setInt(renderer,0);
        for(long position:new long[]{1700,2400,3100,4500,5200,9000}) {
            MusicPlayer.INSTANCE.acceptAppleState(track,position,true);update.invoke(renderer);
            if(index.getInt(renderer)!=0)throw new AssertionError("Telemetry must not invalidate the active line at "+position);
        }
        MusicPlayer.INSTANCE.acceptAppleState(track, 9300, false);
        update.invoke(renderer);
        if (MusicPlayer.INSTANCE.getPositionMs() != 9300) throw new AssertionError("Paused clock must hold");
        MusicPlayer.INSTANCE.acceptAppleState(track, 9300, true);
        update.invoke(renderer);
        Thread.sleep(50);
        if (MusicPlayer.INSTANCE.getPositionMs() <= 9300) throw new AssertionError("Resumed clock must advance");
        checkReports();
        System.out.println("Apple Music playback advances between reports, retains the active line, and handles pause, resume, late reports, seeks and track changes");
    }
}
