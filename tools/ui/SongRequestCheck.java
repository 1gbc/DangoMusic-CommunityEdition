import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import me.sorihiko.dangomusicgtnh.music.AudioOutput;
import me.sorihiko.dangomusicgtnh.music.FadingAudioOutput;
import me.sorihiko.dangomusicgtnh.music.MusicPlayer;
import me.sorihiko.dangomusicgtnh.music.NeteaseDiskCache;
import me.sorihiko.dangomusicgtnh.music.NeteaseTrack;
import me.sorihiko.dangomusicgtnh.music.ResumeStore;
import me.sorihiko.dangomusicgtnh.music.request.RequestCacheState;
import me.sorihiko.dangomusicgtnh.music.request.RequestEntry;
import me.sorihiko.dangomusicgtnh.music.request.RequestNoticeKind;
import me.sorihiko.dangomusicgtnh.music.request.SongRequestQuery;
import me.sorihiko.dangomusicgtnh.music.request.SongRequests;

public final class SongRequestCheck {
    public static void main(String[] args) throws Exception {
        NeteaseTrack studio = track(1, "Always Remember Us This Way", "Lady Gaga");
        NeteaseTrack live = track(2, "Always Remember Us This Way (Live)", "Lady Gaga");
        NeteaseTrack cover = track(3, "Always Remember Us This Way", "Other Artist");
        NeteaseTrack duplicateTitle = track(4, "Always Remember Us This Way", "Lady Gaga");
        for (String command : Arrays.asList("/点歌 Always Remember Us This Way Lady Gaga",
                "/点歌 \"Always Remember Us This Way\" \"Lady Gaga\"")) {
            SongRequestQuery query = SongRequestQuery.parse(command);
            check(query != null, "request command parses");
            check(query.matches(studio), "title and multi-word artist match");
            check(!query.matches(live) && !query.matches(cover), "versions and artists stay distinct");
            check(query.matches(duplicateTitle), "ambiguous editions remain separate candidates");
        }
        check(SongRequestQuery.parse("大家好 /点歌 稻香 周杰伦") == null, "prefix must start the message");
        check(SongRequestQuery.parse("/点歌 稻香") == null, "missing artist is rejected");
        check(SongRequestQuery.parse("/点歌曲 稻香 周杰伦") == null, "unrelated command");
        check(SongRequestQuery.parse("/点歌　稻香　周杰伦").matches(track(5, "稻香", "周杰伦")), "full-width spaces");
        check(SongRequestQuery.parse("/点歌 \"稻香\" 周杰伦").matches(track(5, "稻香", "周杰伦")), "quoted title only");
        verifyPlayback();
        System.out.println("Bilibili request parsing, repeat, timed skip, queue order and resume checks passed.");
    }

    private static NeteaseTrack track(long id, String title, String artist) {
        return new NeteaseTrack(id, title, Collections.singletonList(artist), 210_000L, "");
    }

    @SuppressWarnings("unchecked")
    private static void verifyPlayback() throws Exception {
        MusicPlayer player = MusicPlayer.INSTANCE;
        SongRequests requests = SongRequests.INSTANCE;
        ExecutorService loader = (ExecutorService) field(MusicPlayer.class, "loader").get(player);
        // Hold actual network/audio work while driving the player through the same transitions.
        loader.execute(() -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        requests.enable(true);
        field(SongRequests.class, "cacheQuality").set(requests, player.getNeteaseQuality());
        long stamp = field(NeteaseDiskCache.class, "generation").getLong(NeteaseDiskCache.INSTANCE);
        field(SongRequests.class, "diskGeneration").setLong(requests, stamp);
        field(SongRequests.class, "workerActive").setBoolean(requests, true);
        List<RequestEntry> entries = (List<RequestEntry>) field(SongRequests.class, "entries").get(requests);
        List<NeteaseTrack> original = Arrays.asList(track(101, "Original A", "Host"), track(102, "Original B", "Host"));
        NeteaseTrack bad = track(201, "Unavailable", "Artist");
        NeteaseTrack good = track(202, "Viewer request", "Artist");
        try {
            restore(player, original);
            entries.add(new RequestEntry(1, bad, "Viewer", RequestCacheState.FAILED));
            entries.add(new RequestEntry(2, good, "Viewer", RequestCacheState.READY));
            naturalEnd(player);
            check(player.getCurrent() == good && player.getQueue() == original, "failed request skips into next without replacing host list");
            check(requests.snapshot().getNotice().getKind() == RequestNoticeKind.SKIPPED, "failure publishes a notice");
            naturalEnd(player);
            check(player.getCurrent() == original.get(1), "natural insertion resumes at the next original track");

            restore(player, original);
            player.setRepeatOne(true);
            entries.add(new RequestEntry(3, good, "Viewer", RequestCacheState.READY));
            naturalEnd(player);
            check(player.getCurrent() == good, "a repeat iteration yields to requests");
            naturalEnd(player);
            check(player.getCurrent() == original.get(0) && player.getRepeatOne(), "repeat mode resumes the original track");

            restore(player, original);
            entries.add(new RequestEntry(4, bad, "Viewer", RequestCacheState.LOADING));
            entries.add(new RequestEntry(5, good, "Viewer", RequestCacheState.READY));
            naturalEnd(player);
            synchronized (player) {
                Object wait = field(MusicPlayer.class, "requestWait").get(player);
                check(wait != null && player.getState() == MusicPlayer.State.LOADING, "wait for the uncached queue head");
                player.togglePause();
                check(player.getState() == MusicPlayer.State.PAUSED, "request wait can be paused");
                field(wait.getClass(), "deadline").setLong(wait, System.nanoTime() - 1_000_000_000L);
                player.togglePause();
                check(player.getCurrent() == good && requests.snapshot().getEntries().isEmpty(), "expired cache wait skips without blocking");
            }

            restore(player, original);
            entries.add(new RequestEntry(6, bad, "Viewer", RequestCacheState.FAILED));
            entries.add(new RequestEntry(7, good, "Viewer", RequestCacheState.READY));
            requests.move(7, 6L);
            check(requests.snapshot().getEntries().get(0).getId() == 7L, "reorder targets a stable request ID");
            requests.remove(6);
            player.setRepeatOne(true);
            requests.play(7);
            check(player.getCurrent() == good && requests.snapshot().getEntries().isEmpty(), "manual request inserts directly");
            naturalEnd(player);
            check(player.getCurrent() == original.get(1), "manual request resumes the next original track");
        } finally {
            requests.enable(false);
            player.shutdown();
        }
    }

    private static void restore(MusicPlayer player, List<NeteaseTrack> tracks) {
        player.stop(false);
        player.setRepeatOne(false);
        check(player.restoreSession(new ResumeStore.Session(tracks, 0, 0)), "restore test queue");
    }

    private static void naturalEnd(MusicPlayer player) throws Exception {
        AudioOutput silent = (AudioOutput) Proxy.newProxyInstance(AudioOutput.class.getClassLoader(),
            new Class<?>[] {AudioOutput.class}, (proxy, method, args) -> {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        FadingAudioOutput output = new FadingAudioOutput(silent, 44_100, 2, false,
            () -> true, () -> false, () -> false, () -> 60);
        field(MusicPlayer.class, "state").set(player, MusicPlayer.State.PLAYING);
        Method complete = MusicPlayer.class.getDeclaredMethod("finishTrack", int.class, FadingAudioOutput.class);
        complete.setAccessible(true);
        complete.invoke(player, field(MusicPlayer.class, "generation").getInt(player), output);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
