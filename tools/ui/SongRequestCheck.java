import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
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
import me.sorihiko.dangomusicgtnh.music.request.RequestNotice;
import me.sorihiko.dangomusicgtnh.music.request.RequestNoticeKind;
import me.sorihiko.dangomusicgtnh.shadow.kotlin.jvm.functions.Function1;
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
        SongRequestQuery titleOnly = SongRequestQuery.parse("/song Always Remember Us This Way");
        check(titleOnly.matches(studio) && titleOnly.matches(cover) && !titleOnly.matches(live), "short command matches exact multiword title and keeps cover candidates");
        SongRequestQuery titleAndArtist = SongRequestQuery.parse("/song Always Remember Us This Way - Lady Gaga");
        check(titleAndArtist.matches(studio) && !titleAndArtist.matches(cover), "optional artist disambiguates multiword title");
        for (String command : Arrays.asList("/song Always Remember Us This Way-Lady Gaga",
                "/song Always Remember Us This Way－Lady Gaga", "/song Always Remember Us This Way- Lady Gaga")) {
            SongRequestQuery request = SongRequestQuery.parse(command);
            check(request.matches(studio) && !request.matches(cover), "compact delimiter preserves spaces in names");
        }
        NeteaseTrack hyphenatedTitle = track(7, "A-Punk", "Vampire Weekend");
        check(SongRequestQuery.parse("/song A-Punk-Vampire Weekend").matches(hyphenatedTitle),
            "support titles that contain hyphens");
        check(SongRequestQuery.parse("/song A-Punk").matches(hyphenatedTitle), "keep standalone hyphenated song titles");
        check(SongRequestQuery.parse("/点歌 A-Punk Vampire Weekend").matches(hyphenatedTitle) &&
            SongRequestQuery.parse("/点歌 \"A-Punk\" \"Vampire Weekend\"").matches(hyphenatedTitle),
            "keep the old space-separated forms for hyphenated song titles");
        check(SongRequestQuery.parse("/song Mamma Mia-A-Teens").matches(track(8, "Mamma Mia", "A-Teens")),
            "support artist names that contain hyphens");
        check(SongRequestQuery.parse("/song id:202").getTrackId() == 202L, "explicit ID");
        check(SongRequestQuery.parse("/song https://music.163.com/#/song?id=202").getTrackId() == 202L, "web song link");
        check(SongRequestQuery.parse("/song https://y.music.163.com/m/song?id=202&uct=fixture").getTrackId() == 202L, "mobile song link");
        check(SongRequestQuery.parse("/song https://music.163.com.evil.invalid/song?id=202") == null &&
            SongRequestQuery.parse("/song id:99999999999999999999") == null, "reject unrelated links and invalid IDs");
        String gemJson = "{\"id\":233931,\"name\":\"泡沫\",\"ar\":[{\"id\":7763,\"name\":\"G.E.M.邓紫棋\",\"alias\":[\"G.E.M.\",\"Gloria Tang\"]}]}";
        NeteaseTrack gem = NeteaseTrack.Companion.parse(new com.google.gson.JsonParser().parse(gemJson).getAsJsonObject());
        for (String artist : Arrays.asList("邓紫棋", "gem", "G.E.M.", "id:7763")) {
            check(SongRequestQuery.parse("/song 泡沫-" + artist).matches(gem), "compact artist query " + artist);
            check(SongRequestQuery.parse("/song 泡沫 - " + artist).matches(gem), "match artist spelling " + artist);
            check(SongRequestQuery.parse("/点歌 泡沫 " + artist).matches(gem), "legacy artist spelling " + artist);
        }
        check(!SongRequestQuery.parse("/song 泡沫 - gem").matches(track(6, "泡沫", "宝石Gem")),
            "partial unrelated artist name cannot match");
        String japaneseJson = "{\"id\":2056914217,\"name\":\"青のすみか\",\"ar\":[{\"id\":15250026,\"name\":\"キタニタツヤ\",\"tns\":[\"木谷龙也\"]}]}";
        NeteaseTrack japanese = NeteaseTrack.Companion.parse(new com.google.gson.JsonParser().parse(japaneseJson).getAsJsonObject());
        check(SongRequestQuery.parse("/点歌 青のすみか　キタニタツヤ").matches(japanese), "Japanese title and artist");
        check(SongRequestQuery.parse("/song 青のすみか - ｷﾀﾆﾀﾂﾔ").matches(japanese), "Japanese half-width input");
        check(SongRequestQuery.parse("/song 青のすみか - 木谷龙也").matches(japanese), "localized artist credit");
        check(SongRequestQuery.parse("/song 3459356843").getTrackId() == 3459356843L, "bare song ID beyond int range");
        check(SongRequestQuery.parse("/点歌 3459356843").getTrackId() == 3459356843L, "legacy prefix with bare ID");
        check(SongRequestQuery.parse("/song 分享Lunak的单曲《Stars Get Lonely》: https://y.music.163.com/m/song?id=3459356843 (来自@网易云音乐)").getTrackId() == 3459356843L,
            "copied share text");
        verifyMatchFallback(japanese, args);
        verifyShortLinks(args);
        verifyNoticeSchedule();
        verifyRequesterAndCopy(studio);
        verifyPlayback();
        System.out.println("Bilibili request parsing, disambiguation, repeat, timed skip, queue order and resume checks passed.");
    }

    @SuppressWarnings("unchecked")
    private static void verifyMatchFallback(NeteaseTrack japanese, String[] args) throws Exception {
        Method match = SongRequests.class.getDeclaredMethod("searchMatches", SongRequestQuery.class, Function1.class);
        match.setAccessible(true);
        NeteaseTrack alternate = track(2061989812L, "青のすみか", "キタニタツヤ");
        alternate.setAlbumName("Alternate edition");
        List<String> searches = new ArrayList<>();
        Function1<String, List<NeteaseTrack>> fixture = query -> {
            searches.add(query);
            return query.equals("青のすみか") ? Arrays.asList(japanese, alternate, japanese) :
                Collections.singletonList(japanese);
        };
        SongRequestQuery query = SongRequestQuery.parse("/song 青のすみか - キタニタツヤ");
        List<NeteaseTrack> candidates = (List<NeteaseTrack>) match.invoke(SongRequests.INSTANCE, query, fixture);
        check(searches.equals(Arrays.asList("青のすみか キタニタツヤ", "青のすみか")), "search title separately to surface additional editions");
        check(candidates.equals(Arrays.asList(japanese, alternate)), "keep different IDs, collapse repeated search hits");

        Method show = SongRequests.class.getDeclaredMethod("show", String.class, RequestNoticeKind.class, List.class);
        show.setAccessible(true);
        show.invoke(SongRequests.INSTANCE, query.getSearchText(), RequestNoticeKind.AMBIGUOUS, candidates);
        RequestNotice notice = SongRequests.INSTANCE.snapshot().getNotices().get(0);
        check(notice.getMatchCount() == 2 && notice.getCandidates().get(1).getId() == 2061989812L &&
            notice.getCandidates().get(1).getAlbum().equals("Alternate edition"),
            "disambiguation notice retains the ID and album needed to specify a version");
        Object notices = field(SongRequests.class, "notices").get(SongRequests.INSTANCE);
        notices.getClass().getMethod("clear").invoke(notices);

        if (Arrays.asList(args).contains("--live")) {
            Class<?> api = Class.forName("me.sorihiko.dangomusicgtnh.music.NeteaseApi");
            Object instance = api.getField("INSTANCE").get(null);
            field(api, "cachedCookie").set(instance, ""); // Anonymous catalog verification.
            Method search = api.getMethod("search", String.class, int.class);
            Method detail = Arrays.stream(api.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("trackById") && method.getParameterCount() == 1)
                .findFirst().orElseThrow(AssertionError::new);
            long idFromCommand = SongRequestQuery.parse("/song 3459356843").getTrackId();
            NeteaseTrack byId = (NeteaseTrack) detail.invoke(instance, idFromCommand);
            check(byId != null && byId.getId() == idFromCommand && byId.getName().equals("Stars Get Lonely") &&
                byId.getArtists().contains("Lunak"), "song ID resolves to the track shown in the reference");
            Function1<String, List<NeteaseTrack>> live = queryText -> {
                try {
                    return (List<NeteaseTrack>) search.invoke(instance, queryText, 25);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            };
            for (String command : Arrays.asList("/song 泡沫 - 邓紫棋", "/song 泡沫 - gem", "/点歌 泡沫 G.E.M.",
                    "/song 青のすみか-キタニタツヤ", "/点歌 青のすみか キタニタツヤ")) {
                List<NeteaseTrack> hits = (List<NeteaseTrack>) match.invoke(SongRequests.INSTANCE, SongRequestQuery.parse(command), live);
                long id = command.contains("泡沫") ? 233931L : 2056914217L;
                check(hits.stream().anyMatch(track -> track.getId() == id), "live NetEase catalog: " + command);
                System.out.println(command + " -> " + hits.size() + " matching editions, includes " + id);
            }
        }
    }

    private static void verifyShortLinks(String[] args) throws Exception {
        SongRequestQuery shortQuery = SongRequestQuery.parse("/song https://163cn.tv/example?tracking=1");
        check(shortQuery.getShortLink() != null && shortQuery.getTrackId() == null, "accept official short links");
        check(SongRequestQuery.parse("/song https://163cn.tv:8443/example") == null &&
            SongRequestQuery.parse("/song https://163cn.tv@example.org/example") == null &&
            SongRequestQuery.parse("/song https://music.163.com/song?id=201&id=202") == null,
            "reject unexpected ports, credentials and conflicting song IDs");

        Class<?> link = Class.forName("me.sorihiko.dangomusicgtnh.music.request.NeteaseSongLink");
        Object instance = link.getField("INSTANCE").get(null);
        Method resolve = link.getMethod("resolve", URI.class, Function1.class);
        List<String> hops = new ArrayList<>();
        Function1<URI, String> redirect = uri -> {
            hops.add(uri.toString());
            return uri.getPath().equals("/example") ? "/second" : "https://y.music.163.com/m/song?id=3459356843";
        };
        check(resolve.invoke(instance, shortQuery.getShortLink(), redirect).equals(3459356843L) &&
            hops.equals(Arrays.asList("https://163cn.tv/example", "https://163cn.tv/second")),
            "resolve only official shortener hops, ignoring tracking fields");
        hops.clear();
        Function1<URI, String> unrelated = uri -> {
            hops.add(uri.toString());
            return "https://example.com/m/song?id=3459356843";
        };
        check(resolve.invoke(instance, shortQuery.getShortLink(), unrelated) == null && hops.size() == 1,
            "reject redirects outside NetEase");
        Function1<URI, String> loop = uri -> uri.toString();
        check(resolve.invoke(instance, shortQuery.getShortLink(), loop) == null, "limit redirect loops");

        if (Arrays.asList(args).contains("--live")) {
            Method defaultResolve = link.getMethod("resolve$default", link, URI.class, Function1.class, int.class, Object.class);
            check(defaultResolve.invoke(null, instance, new URI("https://163cn.tv/rSfsXhr"), null, 2, null)
                .equals(29758212L), "resolve a public short link against the actual NetEase redirect service");
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyNoticeSchedule() throws Exception {
        Class<?> type;
        try {
            type = Class.forName("me.sorihiko.dangomusicgtnh.music.request.RequestNoticeQueue");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("The HUD needs a notice queue instead of a single replaceable message", missing);
        }
        Object queue = type.getConstructor().newInstance();
        Method add = type.getMethod("add", long.class, int.class, String.class, RequestNoticeKind.class, List.class);
        Method visible = type.getMethod("visible", long.class);
        long now = 100_000_000_000L;
        List<?> noCandidates = Collections.emptyList();
        add.invoke(queue, now, 5, "first", RequestNoticeKind.NOT_FOUND, noCandidates);
        add.invoke(queue, now, 5, "second", RequestNoticeKind.AMBIGUOUS, noCandidates);
        add.invoke(queue, now, 5, "third", RequestNoticeKind.NOT_FOUND, noCandidates);
        add.invoke(queue, now, 5, "fourth", RequestNoticeKind.NOT_FOUND, noCandidates);
        List<RequestNotice> opening = (List<RequestNotice>) visible.invoke(queue, now + 100_000_000L);
        check(opening.size() == 1 && opening.get(0).getTitle().equals("first"), "arrivals start in order");
        List<RequestNotice> stacked = (List<RequestNotice>) visible.invoke(queue, now + 1_200_000_000L);
        check(stacked.size() == 3 && stacked.get(1).getTitle().equals("second"), "three notices coexist in arrival order");
        RequestNotice first = stacked.get(0);
        check(first.reveal(now) == 0f && first.reveal(now + 125_000_000L) == 0.5f &&
            first.reveal(now + 300_000_000L) == 1f && first.reveal(now + 5_375_000_000L) == 0.5f,
            "notice height and opacity interpolate while it opens and closes");
        List<RequestNotice> leaving = (List<RequestNotice>) visible.invoke(queue, now + 5_550_000_000L);
        check(leaving.size() == 3 && leaving.get(0).getTitle().equals("second") &&
            leaving.get(2).getTitle().equals("fourth"), "first expires before later arrivals and frees a visible slot");
        check(((List<?>) visible.invoke(queue, now + 11_000_000_000L)).isEmpty(), "each remaining notice expires");
        add.invoke(queue, now + 12_000_000_000L, 9, "longer", RequestNoticeKind.NOT_FOUND, noCandidates);
        check(((List<?>) visible.invoke(queue, now + 20_000_000_000L)).size() == 1, "configured hold controls lifetime");
    }

    private static void verifyRequesterAndCopy(NeteaseTrack song) throws Exception {
        Method displayName = RequestEntry.class.getMethod("displayName", boolean.class);
        RequestEntry request = new RequestEntry(1, song, "1个白菜", RequestCacheState.READY);
        check(displayName.invoke(request, true).equals("1个白菜"), "show the received nickname when enabled");
        check(displayName.invoke(request, false).equals("1***"), "mask the nickname by default");
        RequestEntry oneCharacter = new RequestEntry(2, song, "白", RequestCacheState.READY);
        check(displayName.invoke(oneCharacter, false).equals("***"), "mask single-character nicknames");
        RequestEntry alreadyMasked = new RequestEntry(3, song, "1***", RequestCacheState.READY);
        check(displayName.invoke(alreadyMasked, true).equals("1***"), "keep the nickname as received from Bilibili");

        String hud = "me.sorihiko.dangomusicgtnh.client.BilibiliRequestHud";
        Class<?> target = Class.forName(hud + "$CommandKey");
        java.lang.reflect.Constructor<?> key = target.getDeclaredConstructor(long.class, long.class);
        key.setAccessible(true);
        Object commandKey = key.newInstance(123L, 3459356843L);
        Method command = target.getMethod("getCommand");
        command.setAccessible(true);
        check(command.invoke(commandKey).equals("/song 3459356843"), "copy a complete valid command");
        Class<?> press = Class.forName(hud + "$PanelPress$Copy");
        java.lang.reflect.Constructor<?> constructor = press.getDeclaredConstructor(target, float.class, float.class);
        constructor.setAccessible(true);
        Object click = constructor.newInstance(commandKey, 10f, 10f);
        Method move = press.getMethod("move", float.class, float.class);
        move.setAccessible(true);
        move.invoke(click, 11f, 10f);
        check(!field(press, "dragged").getBoolean(click), "pointer jitter still allows the click");
        move.invoke(click, 100f, 10f);
        move.invoke(click, 10f, 10f);
        check(field(press, "dragged").getBoolean(click), "leaving and returning cannot copy during a drag");
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
            check(requests.snapshot().getNotices().get(0).getKind() == RequestNoticeKind.SKIPPED, "failure publishes a notice");
            naturalEnd(player);
            check(player.getCurrent() == original.get(1), "natural insertion resumes at the next original track");

            restore(player, original);
            entries.add(new RequestEntry(8, good, "Viewer", RequestCacheState.READY));
            field(MusicPlayer.class, "state").set(player, MusicPlayer.State.PLAYING);
            player.next();
            check(player.getCurrent() == good && player.getQueue() == original,
                "manual next prioritizes the waiting request before the original queue");
            check(requests.snapshot().getEntries().size() == 1, "keep the request while audio is opening");
            player.next();
            check(player.getCurrent() == good && requests.snapshot().getEntries().size() == 1,
                "consecutive next calls cannot consume the same loading entry");
            startRequest(player);
            check(requests.snapshot().getEntries().isEmpty(), "consume the request when playback starts");
            player.prev();
            check(player.getCurrent() == original.get(0), "previous returns to the interrupted original track");
            player.next();
            check(player.getCurrent() == original.get(1), "next never reinserts the consumed request");

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
                player.next();
                check(field(MusicPlayer.class, "requestWait").get(player) == wait, "next must not restart or bypass the same pending wait");
                player.togglePause();
                check(player.getState() == MusicPlayer.State.PAUSED, "request wait can be paused");
                field(wait.getClass(), "deadline").setLong(wait, System.nanoTime() - 1_000_000_000L);
                player.togglePause();
                check(player.getCurrent() == good && requests.snapshot().getEntries().size() == 1, "expired cache wait skips without blocking");
            }
            startRequest(player);
            check(requests.snapshot().getEntries().isEmpty(), "the successor is consumed after its output starts");

            restore(player, original);
            entries.add(new RequestEntry(6, bad, "Viewer", RequestCacheState.FAILED));
            entries.add(new RequestEntry(7, good, "Viewer", RequestCacheState.READY));
            requests.move(7, 6L);
            check(requests.snapshot().getEntries().get(0).getId() == 7L, "reorder targets a stable request ID");
            requests.remove(6);
            player.setRepeatOne(true);
            requests.play(7);
            check(player.getCurrent() == good && requests.snapshot().getEntries().size() == 1, "manual request inserts directly");
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
        startRequest(player);
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

    private static void startRequest(MusicPlayer player) throws Exception {
        Method started = MusicPlayer.class.getDeclaredMethod("markRequestStarted");
        started.setAccessible(true);
        synchronized (player) {
            started.invoke(player);
            field(MusicPlayer.class, "state").set(player, MusicPlayer.State.PLAYING);
        }
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
