import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import me.sorihiko.dangomusicgtnh.DangoClient;
import me.sorihiko.dangomusicgtnh.DangoMusic;
import me.sorihiko.dangomusicgtnh.font.MixedText;
import me.sorihiko.dangomusicgtnh.gfx.CjkRun;
import me.sorihiko.dangomusicgtnh.gfx.Gfx;
import me.sorihiko.dangomusicgtnh.gfx.GuiCanvas;
import me.sorihiko.dangomusicgtnh.gfx.Texture;
import me.sorihiko.dangomusicgtnh.i18n.I18n;
import me.sorihiko.dangomusicgtnh.lyrics.LyricLine;
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule;
import me.sorihiko.dangomusicgtnh.module.values.BooleanValue;
import me.sorihiko.dangomusicgtnh.music.*;
import me.sorihiko.dangomusicgtnh.musicui.*;
import me.sorihiko.dangomusicgtnh.platform.Platform;
import me.sorihiko.dangomusicgtnh.ui.LayoutBounds;
import me.sorihiko.dangomusicgtnh.ui.GlassStyle;
import me.sorihiko.dangomusicgtnh.ui.ShapeStyle;
import me.sorihiko.dangomusicgtnh.ui.Theme;
import me.sorihiko.dangomusicgtnh.ui.UiStyle;
import me.sorihiko.dangomusicgtnh.ui.VectorIcon;
import me.sorihiko.dangomusicgtnh.utils.Color;

/** Run on the compiled client and core classpath, with core resources. No game or login needed. */
public class AppleLyricsMenuCheck {
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void draw(AppleLyricsMenu menu, AppleLyricsLayout frame, NeteaseTrack track, Ui ui,
                             List<String> labels, List<VectorIcon> icons) {
        labels.clear();
        icons.clear();
        ui.getHits().clear();
        menu.draw(frame, track);
        ui.getMt().flush(1f);
    }

    private static void click(Ui ui, int x, int y) {
        ui.getHits().stream().filter(hit -> hit.getFirst().contains(x, y)).findFirst()
            .orElseThrow().getSecond().invoke();
    }

    private static float rowTop(LyricsPage page, int index) throws Exception {
        Object row = ((List<?>)field(page, "rows").get(page)).get(index);
        return field(row, "top").getFloat(row);
    }

    private static void layout(LyricsPage page, List<LyricLine> lines) throws Exception {
        Method layout = LyricsPage.class.getDeclaredMethod("layout", List.class, float.class, float.class, boolean.class, float.class);
        layout.setAccessible(true);
        layout.invoke(page, lines, 938f, 22f, true, 22f * .74f);
    }

    public static void main(String[] args) throws Exception {
        var labels = new ArrayList<String>();
        var icons = new ArrayList<VectorIcon>();
        var textLeft = new java.util.HashMap<String,Float>();
        var clipboard = new AtomicReference<String>();
        var glassMenuBounds = new AtomicReference<LayoutBounds>();
        var glassMenuStyle = new AtomicReference<GlassStyle>();
        var litRim = new AtomicReference<ShapeStyle>();
        var flowingTextures = new ArrayList<Texture>();
        var flowingRadii = new ArrayList<Float>();
        var flowingAlpha = new ArrayList<Float>();
        var flowingPhase = new ArrayList<Float>();
        var opacity = new java.util.ArrayDeque<Float>();
        opacity.push(1f);
        int[] glassDraws = {0};
        var tempConfig = java.nio.file.Files.createTempDirectory(Path.of(args[0]), "apple-menu-");
        DangoMusic.start(proxy(Platform.class, (p,m,a) -> {
            if (m.getName().equals("getConfigDir")) return tempConfig;
            if (m.getName().equals("setClipboard")) clipboard.set((String)a[0]);
            if (m.getName().equals("getWindowHeight")) return 1292;
            return null;
        }), List.of());
        field(DangoClient.INSTANCE, "gfx").set(null, proxy(Gfx.class, (p,m,a) -> {
            return switch (m.getName()) {
                case "uiTextWidth", "cjkWidth" -> ((String)a[0]).length() * (float)a[1] * .56f;
                case "drawCjkRuns" -> {
                    ((List<?>)a[0]).forEach(run -> labels.add(((CjkRun)run).getS()));
                    yield null;
                }
                default -> null;
            };
        }));
        ScreenState.INSTANCE.setContext(proxy(GuiCanvas.class, (p,m,a) -> {
            if (m.getName().equals("drawText")) {
                labels.add((String)a[0]);
                textLeft.put((String)a[0], (float)a[1]);
            }
            if (m.getName().equals("drawIcon")) icons.add((VectorIcon)a[1]);
            if (m.getName().equals("drawGlass")) {
                glassDraws[0]++;
                glassMenuBounds.set((LayoutBounds)a[0]);
                glassMenuStyle.set((GlassStyle)a[1]);
            }
            if (m.getName().equals("drawShape") && ((ShapeStyle)a[1]).getClipBounds() != null) {
                litRim.set((ShapeStyle)a[1]);
            }
            if (m.getName().equals("pushOpacity")) opacity.push((float)a[0] * opacity.peek());
            if (m.getName().equals("popOpacity")) opacity.pop();
            if (m.getName().equals("drawFlowingBackdrop")) {
                flowingTextures.add((Texture)a[1]);
                flowingRadii.add((float)a[3]);
                flowingAlpha.add(opacity.peek());
                flowingPhase.add((float)a[2]);
            }
            return m.getName().equals("getOpacity") ? opacity.peek() : null;
        }));

        var module = new NeteaseMusicModule();
        var appleSize = java.util.Arrays.stream(NeteaseMusicModule.class.getMethods())
            .filter(method -> method.getName().startsWith("getAppleLyricsPageSize"))
            .findFirst().orElseThrow(() -> new AssertionError("Apple Music needs its own default text size"));
        var textSize = (me.sorihiko.dangomusicgtnh.module.values.IntValue)field(module, "lyricsPageSize").get(module);
        check(textSize.getValue() == 22 && appleSize.invoke(module).equals(26),
            "Apple Music defaults to 26 while the existing NetEase size remains 22");
        textSize.setValue(26);
        check(appleSize.invoke(module).equals(26), "Existing custom lyric size remains in effect");
        textSize.reset();
        var screen = new MusicScreen(module, () -> Unit.INSTANCE);
        var menu = (AppleLyricsMenu)field(screen, "appleLyricsMenu").get(screen);
        var page = (LyricsPage)field(screen, "lyricsPage").get(screen);
        var ui = (Ui)field(screen, "ui").get(screen);
        var frame = new AppleLyricsLayout(new LayoutBounds(0, 0, 2464, 1292), true);
        check(frame.getLyrics().getX() == 1332f && frame.getLyrics().getWidth() == 714f,
            "Lyrics start farther right without shifting the empty-state center");
        var track = new NeteaseTrack(1L, "Song", List.of("Singer"), 60_000L, "", MusicSource.APPLE);
        var romanization = (BooleanValue)field(module, "lyricsPageRomanization").get(module);
        var translation = (BooleanValue)field(module, "lyricsPageTranslation").get(module);

        I18n.INSTANCE.setLanguage(I18n.ENGLISH);
        ui.setMx(982);
        ui.setMy(906);
        menu.draw(frame, track);
        menu.toggle();
        draw(menu, frame, track, ui, labels, icons);
        check(labels.equals(List.of("View Credits", "Copy Link", "Romanization", "Translation")),
            "Expected four English menu rows, got " + labels);
        check(icons.size() == 5, "One default checkbox plus four row icons");

        var lines = List.of(new LyricLine(0, "First line", null, "First translation", "First romanization"),
            new LyricLine(1000, "Second line", null, "Second translation", "Second romanization"));
        layout(page, lines);
        field(page, "lyricArea").set(page, frame.getLyrics());
        Method drawLyrics = LyricsPage.class.getDeclaredMethod("drawLyrics", int.class, float.class, LayoutBounds.class,
            boolean.class, float.class);
        drawLyrics.setAccessible(true);
        drawLyrics.invoke(page, 0, 22f, null, true, 22f * .74f);
        float centerX = frame.getLyrics().getX() + frame.getLyrics().getWidth() / 2f;
        check(Math.abs(textLeft.get("First line") + "First line".length() * 22f * .56f / 2f - centerX) < .01f,
            "Lyrics center on the same axis as the no-lyrics message");
        check(Math.abs(textLeft.get("First translation") + "First translation".length() * 22f * .74f * .56f / 2f - centerX) < .01f,
            "Annotations use the same center");
        drawLyrics.invoke(page, 0, 22f, null, false, 22f * .74f);
        check(textLeft.get("First line").equals(frame.getLyrics().getX()), "NetEase retains left alignment");
        float anchor = rowTop(page, 1) + 5f;
        field(page, "manualPosition").set(page, anchor);
        field(page, "scrollPosition").setFloat(page, anchor);
        click(ui, 1000, 973);
        check(romanization.get() && translation.get(), "Romanization toggles independently");
        layout(page, lines);
        float shifted = rowTop(page, 1) + 5f;
        check(shifted > anchor && field(page, "manualPosition").get(page).equals(shifted)
            && field(page, "scrollPosition").getFloat(page) == shifted, "Keep the displayed original line anchored");
        draw(menu, frame, track, ui, labels, icons);
        check(icons.size() == 6, "Both toggles are marked on");
        click(ui, 1000, 999);
        check(romanization.get() && !translation.get(), "Translation toggles independently");

        menu.dismiss();
        I18n.INSTANCE.setLanguage(I18n.CHINESE);
        check(I18n.INSTANCE.text("Translation").equals("歌词翻译"),
            "The Apple menu must keep the existing world setting translation label");
        menu.toggle();
        draw(menu, frame, track, ui, labels, icons);
        check(labels.equals(List.of("查看制作人员", "复制链接", "音译", "翻译")),
            "Expected four Chinese menu rows, got " + labels);
        var details = CompletableFuture.completedFuture(new AppleMusicSource.SongDetails("Singer", "Composer", "https://music.apple.com/us/song/1"));
        field(menu, "details").set(menu, details);
        click(ui, 1000, 924);
        draw(menu, frame, track, ui, labels, icons);
        check(labels.contains("查看制作人员") && labels.contains("Singer"), "Credits still show localized title and metadata");
        menu.dismiss();
        menu.toggle();
        draw(menu, frame, track, ui, labels, icons);
        click(ui, 1000, 956);
        draw(menu, frame, track, ui, labels, icons);
        check(details.join().getLink().equals(clipboard.get()), "Copy Link still writes the official URL");
        var bottom = (AppleLyricsMenu)field(screen, "appleBarMenu").get(screen);
        var barAnchor = new LayoutBounds(1500f, 1200f, 30f, 30f);
        bottom.draw(frame, track, barAnchor);
        ui.setMx(2430);
        ui.setMy(1240);
        bottom.toggle();
        ui.setMx(40);
        ui.setMy(40);
        ui.getHits().clear();
        labels.clear();
        icons.clear();
        int beforeGlass = glassDraws[0];
        bottom.draw(frame, track, barAnchor);
        ui.getMt().flush(1f);
        check(labels.equals(List.of("插播", "最后播放", "个人收藏", "查看制作人员", "拷贝链接")),
            "Bottom menu contains only the five supported and localized actions: " + labels);
        check(icons.size() == 5, "All bottom menu actions have vector icons");
        var bounds = glassMenuBounds.get();
        var style = glassMenuStyle.get();
        check(glassDraws[0] == beforeGlass + 1 && style.getRadius() == 5f && style.getFrost() > .5f,
            "Bottom menu uses frosted liquid glass with the existing small radius");
        check(bounds.getX() == 2242f && bounds.getY() == 1110f,
            "Menu flips left and up when necessary and stays anchored to the original click");
        var queue = (AppleQueuePanel)field(screen, "appleQueue").get(screen);
        queue.toggle();
        var window = new LayoutBounds(245f, 27f, 980f, 734f);
        var fitted = queue.layoutIn(window);
        check(fitted.getWidth() == 226f && fitted.getBottom() == window.getBottom()
            && fitted.getRight() == window.getRight(), "Up Next is narrow and extends to the lower right edge");

        for (boolean continuous : new boolean[]{false, true}) {
            UiStyle.INSTANCE.setContinuous(continuous);
            for (float scale : new float[]{1f, .5f}) {
                var viewport = new LayoutBounds(0f, 0f, 2464f * scale, 1292f * scale);
                var lyricsFrame = new AppleLyricsLayout(viewport, true);
                var centeredFrame = new AppleLyricsLayout(viewport, false);
                float expectedRadius = (continuous ? 12f : 18f) * scale;
                for (var layout : List.of(lyricsFrame, centeredFrame)) {
                    page.drawApple(layout, new PlaybackView.Snapshot(), () -> Unit.INSTANCE, () -> Unit.INSTANCE);
                    check(Math.abs(litRim.get().getClipRadius() - expectedRadius) < .001f
                            && litRim.get().getClipBounds().equals(layout.getArtwork()),
                        "Artwork and its rim use the Major 5 mask at both scales and in both layouts");
                }
                check(field(page, "layoutSize").getFloat(page) == 29f * scale, "Original lyrics are three sizes larger");
                check(Math.abs(field(page, "layoutAnnotationSize").getFloat(page) - (26f * .74f + 3f) * scale) < .001f,
                    "Translation and romanization each gain three sizes");
            }
        }
        var player = (PlayerBar)field(screen, "bar").get(screen);
        Method drawBar = PlayerBar.class.getDeclaredMethod("drawAppleBar", LayoutBounds.class, PlaybackView.Snapshot.class);
        drawBar.setAccessible(true);
        var snapshot = new PlaybackView.Snapshot(false, track, 5_000L, 60_000L, true, false, "", "");
        bottom.dismiss();
        MusicSources.INSTANCE.setSelected(MusicSource.APPLE);
        MusicPlayer.INSTANCE.acceptAppleState(track, 5_000L, true);
        for (float available : new float[]{900f, 420f}) {
            float baseWidth = Math.min(540f, available - 12f);
            var bar = PlayerBar.Companion.appleBarBounds(240f, available, 784f);
            check(Math.abs(bar.getWidth() - .8f * baseWidth) < .001f && bar.getHeight() == .8f * 45f
                    && Math.abs(bar.getX() + bar.getWidth() / 2f - (240f + available / 2f)) < .001f
                    && bar.getBottom() == 784f, "Apple bar is 80% of Major 11 and retains its anchor");
            ui.getHits().clear();
            drawBar.invoke(player, bar, snapshot);
            float unit = bar.getWidth() / 540f;
            var moreHit = player.getAppleMenuAnchor();
            check(moreHit.getWidth() == 24f * unit && ui.getHits().stream().anyMatch(hit -> hit.getFirst() == moreHit),
                "The three-dot button uses a scaled clickable region");
            var seekHit = (LayoutBounds)field(player, "progressHit").get(player);
            check(seekHit.getWidth() > 0f && Math.abs(seekHit.getHeight() - 12f * unit) < .001f
                    && seekHit.getX() > bar.getX() && seekHit.contains((int)(seekHit.getX() + seekHit.getWidth() / 2f),
                        Math.round(bar.getBottom() - 5.5f * unit)),
                "The progress track stays within the smaller bar and its hit area scales");
            int trackY = Math.round(bar.getBottom() - 5.5f * unit);
            check(!player.mouseDown((int)seekHit.getX() - 2, trackY), "The old hit area cannot start a drag outside the new bar");
            check(player.mouseDown((int)(seekHit.getX() + seekHit.getWidth() / 4f), trackY), "Seek starts on the scaled track");
            player.mouseDrag(Math.round(seekHit.getX() + seekHit.getWidth() * .75f), trackY);
            check(Math.abs(field(player, "seekFraction").getFloat(player) - .75f) < .01f, "Drag uses the scaled track length");
            int glassBeforeSeek = glassDraws[0];
            drawBar.invoke(player, bar, snapshot);
            check(glassDraws[0] == glassBeforeSeek && field(player, "appleTimeReveal").getFloat(player) > 0f,
                "Seek reveals the time without drawing a separate blur panel");
            player.mouseUp();
        }

        var backdrop = (FlowingCoverBackdrop)field(screen, "coverBackdrop").get(screen);
        var firstTexture = proxy(Texture.class, (p,m,a) -> null);
        var secondTexture = proxy(Texture.class, (p,m,a) -> null);
        var nextTrack = new NeteaseTrack(2L, "Next song", List.of("Singer"), 60_000L, "", MusicSource.APPLE);
        var cached = (java.util.Map<String, Texture>)field(CoverCache.INSTANCE, "textures").get(null);
        cached.put("L|t:" + track.getKey() + "#r-lyrics-backdrop", firstTexture);
        backdrop.prepareMenuFrame(track);
        var start = field(backdrop, "transitionStartedAt");
        start.setLong(backdrop, System.nanoTime() - 500_000_000L);
        cached.put("L|t:" + nextTrack.getKey() + "#r-lyrics-backdrop", secondTexture);
        backdrop.prepareMenuFrame(nextTrack);
        flowingTextures.clear();
        flowingRadii.clear();
        flowingAlpha.clear();
        flowingPhase.clear();
        start.setLong(backdrop, System.nanoTime() - 210_000_000L);
        backdrop.prepareMenuFrame(nextTrack);
        float windowRadius = Theme.INSTANCE.getGLASS_WINDOW().getRadius();
        backdrop.drawMenuPanel(window, windowRadius, Color.Companion.getTRANSPARENT(), 1f);
        var narrowerBar = PlayerBar.Companion.appleBarBounds(240f, 800f, 784f);
        backdrop.drawMenuPanel(narrowerBar, narrowerBar.getHeight() / 2f, Color.Companion.getTRANSPARENT(), 1f);
        float lower = flowingAlpha.get(0);
        float upper = flowingAlpha.get(1);
        check(flowingTextures.size() == 4 && flowingTextures.get(0) == firstTexture
                && flowingTextures.get(1) == secondTexture && Math.abs(upper + lower * (1f - upper) - 1f) < .001f,
            "The window stays opaque throughout a palette transition");
        check(flowingTextures.subList(0, 2).equals(flowingTextures.subList(2, 4))
                && flowingPhase.stream().allMatch(phase -> phase.equals(flowingPhase.get(0)))
                && flowingRadii.get(0) == windowRadius && flowingRadii.get(2) == narrowerBar.getHeight() / 2f,
            "Window and bar use the same cover transition and phase with separate masks");
        check(opacity.size() == 1, "Opacity stack remains balanced");
        System.out.println("Apple UI checks passed for menu actions, bar geometry, cover mask, lyric sizes and backdrop blending");
    }
}
