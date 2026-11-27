import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import dangomusicce.lyrics.LyricLine;
import dangomusicce.lyrics.LyricWord;
import dangomusicce.lyrics.Lyrics;
import dangomusicce.module.impls.music.LyricsModule;
import dangomusicce.module.values.IntValue;

/**
 * Multi-line 3D world lyrics: forward-only preview queue, immediate disappearance after timed words end,
 * rank spring & queue promotion, overlap metric, vertical collision avoidance and avoid-pitch baking.
 */
public final class WorldLyricTrailCheck {
    private static final long FADE = 400_000_000L;
    private static final long RETIRE_FADE = 380_000_000L;
    private static final long STAGGER = 70_000_000L;
    private static final float PREVIEW_ALPHA = 0.56f;

    private static Class<?> floating;
    private static Constructor<?> floatingCtor;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        floating = Class.forName("dangomusicce.module.impls.music.LyricsModule$Floating");
        // Pick the primary constructor explicitly (Kotlin also emits a synthetic one for default arguments).
        floatingCtor = floating.getDeclaredConstructor(List.class, long.class, long.class, double.class, double.class,
            double.class, float.class, float.class, long[].class, LyricLine.class, long.class);
        floatingCtor.setAccessible(true);

        LyricsModule lyrics = new LyricsModule();
        IntValue lines = (IntValue) field(lyrics, "worldLyricLines");
        List<Object> upcoming = (List<Object>) field(lyrics, "upcoming");
        List<Object> fading = (List<Object>) field(lyrics, "fading");
        Method retire = method("retire", floating);
        Method promoteIndex = method("promote", int.class);
        Method update = method("updateLines", long.class);
        Method rankAlpha = method("rankAlpha", float.class);
        Method rankScale = method("rankScale", float.class);
        Method rankLift = method("rankLift", float.class);
        Method lineAlpha = method("lineAlpha", floating, long.class);
        Method ghostOf = method("ghostOf", floating, long.class);
        Method pastLines = method("pastLines");
        Method previewCount = method("previewCount");
        Method previewsNext = method("previewsNext");
        Method worldIndexAt = method("worldIndexAt", Lyrics.class, long.class, boolean.class);
        Method upcomingIndices = method("upcomingIndices", Lyrics.class, int.class, long.class, int.class);
        Method upcomingIndex = method("upcomingIndex", Lyrics.class, int.class, long.class);
        Method sameLine = method("sameLine", LyricLine.class, LyricLine.class);
        Method bakeAvoidPitch = method("bakeAvoidPitch", floating, double.class, double.class, double.class);

        // 1. Slider split: 1 = current only, 2..4 = current + 1..3 upcoming previews, zero retained past lines.
        check(lines.getValue() == 1, "one line by default keeps the original single-line behavior");
        for (int n = 1; n <= 4; n++) {
            lines.setValue(n);
            check((Integer) pastLines.invoke(lyrics) == 0, n + " lines: finished lines are never retained");
            check((Integer) previewCount.invoke(lyrics) == n - 1, n + " lines: shows " + (n - 1) + " upcoming previews");
            check((Boolean) previewsNext.invoke(lyrics) == (n >= 2), n + " lines: previewsNext matches n >= 2");
        }

        // 2. Finished lines fade out immediately instead of piling into a trail.
        lines.setValue(1);
        Object single = line("first");
        retire.invoke(lyrics, single);
        check(fading.contains(single) && lng(single, "fadeNanos") == FADE, "single line retires with standard fade");
        fading.clear();

        lines.setValue(4);
        Object multi = line("second");
        retire.invoke(lyrics, multi);
        check(fading.contains(multi) && lng(multi, "fadeNanos") == RETIRE_FADE, "multi-line retires finished line directly with 380ms fade");
        fading.clear();

        // 3. Timed lines vanish right after the last word ends (+200ms grace), without waiting for the next line.
        LyricLine timed = new LyricLine(1000L, "hello", Arrays.asList(
            new LyricWord(1000L, 400L, "hel"),
            new LyricWord(1400L, 600L, "lo")
        ));
        LyricLine lrc = new LyricLine(1000L, "untimed");
        Lyrics timedSong = new Lyrics(Arrays.asList(timed, new LyricLine(8000L, "later")), "test");
        Lyrics untimedSong = new Lyrics(Arrays.asList(lrc, new LyricLine(8000L, "later")), "test");
        check((Integer) worldIndexAt.invoke(lyrics, timedSong, 1999L, false) == 0, "timed line still visible while singing");
        check((Integer) worldIndexAt.invoke(lyrics, timedSong, 2199L, false) == 0, "timed line kept during 200ms word-end grace");
        check((Integer) worldIndexAt.invoke(lyrics, timedSong, 2200L, false) == -1, "timed line vanishes right after grace even when next line is 5.8s away");
        check((Integer) worldIndexAt.invoke(lyrics, timedSong, 2200L, true) == -1, "KTV mode also retires timed line after grace on long gaps");
        check((Integer) worldIndexAt.invoke(lyrics, untimedSong, 5000L, false) == 0, "plain LRC without word timings remains visible until next line");

        // 4. Multi-slot upcoming queue selection.
        Lyrics song = new Lyrics(Arrays.asList(
            new LyricLine(0L, "one"),
            new LyricLine(1000L, ""),
            new LyricLine(2000L, "three"),
            new LyricLine(3000L, "four"),
            new LyricLine(4000L, "five"),
            new LyricLine(5000L, "six")
        ), "test");
        List<Integer> up3 = (List<Integer>) upcomingIndices.invoke(lyrics, song, 0, 500L, 3);
        check(up3.equals(Arrays.asList(2, 3, 4)), "skips blank lines and picks up to 3 upcoming lines");
        List<Integer> upAfterRetire = (List<Integer>) upcomingIndices.invoke(lyrics, song, -1, 1500L, 2);
        check(upAfterRetire.equals(Arrays.asList(2, 3)), "after current line retires mid-gap, next lines stay in queue");
        check((Integer) upcomingIndex.invoke(lyrics, song, 2, 1800L) == 3, "KTV switching early still previews the line after it");
        check((Integer) upcomingIndex.invoke(lyrics, song, 5, 5500L) == -1, "nothing after the last line");
        check((Boolean) sameLine.invoke(lyrics, new LyricLine(2000L, "three"), new LyricLine(2000L, "three", null, "translated", null)),
            "a reloaded lyric still matches the preview");
        check(!(Boolean) sameLine.invoke(lyrics, new LyricLine(2000L, "three four"), new LyricLine(2000L, "three")),
            "a split line is a different line");

        // 5. Rank curves for current (0) and upcoming slots (-1, -2, -3).
        float a0 = (Float) rankAlpha.invoke(lyrics, 0f);
        float a1 = (Float) rankAlpha.invoke(lyrics, -1f);
        float a2 = (Float) rankAlpha.invoke(lyrics, -2f);
        float a3 = (Float) rankAlpha.invoke(lyrics, -3f);
        check(a0 == 1f && Math.abs(a1 - PREVIEW_ALPHA) < 1e-6 && a1 > a2 && a2 > a3 && a3 > 0.15f,
            "upcoming slots dim progressively but remain legible");
        check((Float) rankScale.invoke(lyrics, -1f) < 1f && (Float) rankScale.invoke(lyrics, -2f) < (Float) rankScale.invoke(lyrics, -1f)
            && (Float) rankLift.invoke(lyrics, -1f) > 0f && (Float) rankLift.invoke(lyrics, -2f) > (Float) rankLift.invoke(lyrics, -1f),
            "further upcoming previews sit slightly smaller and lower");

        // 6. Queue promotion, stagger, avoidPitch baking and lowering worldLyricLines trimming.
        lines.setValue(4);
        upcoming.clear();
        fading.clear();
        setField(lyrics, "active", null);
        Object u1 = line("u1");
        setFloat(u1, "rank", -1f);
        setInt(u1, "targetRank", -1);
        setDouble(u1, "x", 0.0);
        setDouble(u1, "y", 0.0);
        setDouble(u1, "z", 10.0);
        setDouble(u1, "avoidPitch", -10.0);
        setFloat(u1, "pitch", 0f);

        Object u2 = line("u2");
        setFloat(u2, "rank", -2f);
        setInt(u2, "targetRank", -2);
        Object u3 = line("u3");
        setFloat(u3, "rank", -3f);
        setInt(u3, "targetRank", -3);

        upcoming.addAll(Arrays.asList(u1, u2, u3));
        long p0 = System.nanoTime();
        float ghostBefore = (Float) ghostOf.invoke(lyrics, u1, p0);
        float alphaBefore = (Float) lineAlpha.invoke(lyrics, u1, p0);
        check(Math.abs(ghostBefore - PREVIEW_ALPHA) < 1e-4 && Math.abs(alphaBefore - PREVIEW_ALPHA) < 1e-4,
            "first preview shows faintly before promotion");

        // Bake avoidPitch explicitly at origin before testing promote + spring
        bakeAvoidPitch.invoke(lyrics, u1, 0.0, 0.0, 0.0);
        check(Math.abs(dbl(u1, "avoidPitch")) < 1e-6 && dbl(u1, "y") > 1.5 && Math.abs(Math.hypot(dbl(u1, "y"), dbl(u1, "z")) - 10.0) < 1e-4,
            "baking negative avoidPitch moves world Y upward while preserving radial distance");

        promoteIndex.invoke(lyrics, 0);
        check(field(lyrics, "active") == u1 && upcoming.size() == 2 && upcoming.get(0) == u2 && rank(u2, "targetRank") == -1 && rank(u3, "targetRank") == -2,
            "promoting u1 shifts u2->-1 and u3->-2");
        float lastAlpha = alphaBefore;
        setField(lyrics, "trailNanos", 0L);
        for (int i = 0; i <= 40; i++) {
            long at = p0 + i * 16_000_000L + STAGGER;
            update.invoke(lyrics, at);
            float la = (Float) lineAlpha.invoke(lyrics, u1, at);
            check(la >= lastAlpha - 1e-5, "promoted line brightens smoothly");
            lastAlpha = la;
        }
        check(Math.abs(lastAlpha - 1f) < 0.02f, "promoted line finishes at full alpha");
        check(Math.abs(flt(u2, "rank") - (-1f)) < 0.05f, "u2 springs smoothly from -2 to -1");

        // Lowering slider trims excess previews immediately on updateLines.
        lines.setValue(2); // 1 preview allowed
        update.invoke(lyrics, p0 + 45 * 16_000_000L + STAGGER);
        check(upcoming.size() == 1 && upcoming.get(0) == u2 && fading.contains(u3),
            "lowering simultaneous line count fades out the farthest upcoming preview");

        lines.setValue(1);
        check((Float) ghostOf.invoke(lyrics, u1, p0) == 0f, "single-line mode disables preview ghosting");

        // 7. Overlap metric: cos(avgPitch) scaling & +-180 deg wrapping.
        Class<?> box = Class.forName("dangomusicce.module.impls.music.LyricsModule$LyricBox");
        Constructor<?> boxCtor = box.getDeclaredConstructor(double.class, double.class, double.class, double.class);
        boxCtor.setAccessible(true);
        Method separation = method("separation", box, box);
        Object centre = boxCtor.newInstance(0.0, 10.0, -2.0, 2.0);
        double overlap = (Double) separation.invoke(lyrics, centre, boxCtor.newInstance(5.0, 10.0, -1.0, 3.0));
        double beside = (Double) separation.invoke(lyrics, centre, boxCtor.newInstance(25.0, 10.0, -2.0, 2.0));
        double below = (Double) separation.invoke(lyrics, centre, boxCtor.newInstance(0.0, 10.0, 5.0, 9.0));
        double wrapped = (Double) separation.invoke(lyrics, boxCtor.newInstance(175.0, 8.0, -2.0, 2.0), boxCtor.newInstance(-175.0, 8.0, -2.0, 2.0));
        double steep = (Double) separation.invoke(lyrics, boxCtor.newInstance(0.0, 10.0, -62.0, -58.0), boxCtor.newInstance(22.0, 10.0, -62.0, -58.0));
        check(overlap < 0, "overlapping lines have negative room");
        check(Math.abs(beside - 5.0) < 0.05, "horizontal gap on horizon matches yaw difference");
        check(Math.abs(below - 3.0) < 1e-9, "vertical gap reports pitch separation");
        check(Math.abs(wrapped + 4.0) < 0.05, "yaw wraps around at +-180 degrees");
        check(steep < 0, "high-pitch compression detects yaw overlap near zenith");

        // 8. Vertical collision avoidance solver: dodging, role bias, hysteresis and viewport bounds.
        Method solveAvoidPitch = internalMethod("solveAvoidPitch");
        Class<?> pairClass = solveAvoidPitch.getReturnType();
        Constructor<?> pairCtor = pairClass.getConstructor(Object.class, Object.class);
        Method pairFirst = pairClass.getMethod("getFirst");
        Method pairSecond = pairClass.getMethod("getSecond");
        List<Object> midBlocker = Collections.singletonList(pairCtor.newInstance(-4.0, 4.0));

        Object dodgeDown = solveAvoidPitch.invoke(lyrics, -2.0, 2.0, midBlocker, -30.0, 30.0, false, 0);
        check(((Double) pairFirst.invoke(dodgeDown)) > 5.9 && ((Integer) pairSecond.invoke(dodgeDown)) == 1,
            "upcoming line prefers dodging downward when unblocked below");

        Object dodgeUp = solveAvoidPitch.invoke(lyrics, -2.0, 2.0, midBlocker, -30.0, 30.0, true, 0);
        check(((Double) pairFirst.invoke(dodgeUp)) < -5.9 && ((Integer) pairSecond.invoke(dodgeUp)) == -1,
            "fading line prefers dodging upward");

        Object hysteresisHold = solveAvoidPitch.invoke(lyrics, -1.5, 2.5, midBlocker, -30.0, 30.0, false, -1);
        check(((Double) pairFirst.invoke(hysteresisHold)) < -6.0 && ((Integer) pairSecond.invoke(hysteresisHold)) == -1,
            "hysteresis keeps previous dodge direction instead of flipping");

        Object groundBounded = solveAvoidPitch.invoke(lyrics, -2.0, 2.0, midBlocker, -30.0, 3.0, false, 0);
        check(((Double) pairFirst.invoke(groundBounded)) < -5.9 && ((Integer) pairSecond.invoke(groundBounded)) == -1,
            "clamped bottom bound forces dodging upward cleanly");

        System.out.println("World lyric multi-preview queue, immediate timed fadeout, same-sphere collision solver & regression passed.");
    }

    private static Object line(String text) throws Exception {
        LyricLine lyric = new LyricLine(0L, text);
        return floatingCtor.newInstance(Collections.singletonList(text), 0L, 250L, 0.0, 0.0, 0.0, 0f, 0f, new long[0], lyric, 1000L);
    }

    private static void setField(LyricsModule lyrics, String name, Object value) throws Exception {
        Field f = LyricsModule.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(lyrics, value);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static Field floatingField(String name) throws Exception {
        Field field = floating.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static int rank(Object f, String name) throws Exception {
        return floatingField(name).getInt(f);
    }

    private static float flt(Object f, String name) throws Exception {
        return floatingField(name).getFloat(f);
    }

    private static double dbl(Object f, String name) throws Exception {
        return floatingField(name).getDouble(f);
    }

    private static long lng(Object f, String name) throws Exception {
        return floatingField(name).getLong(f);
    }

    private static void setFloat(Object f, String name, float value) throws Exception {
        floatingField(name).setFloat(f, value);
    }

    private static void setDouble(Object f, String name, double value) throws Exception {
        floatingField(name).setDouble(f, value);
    }

    private static void setInt(Object f, String name, int value) throws Exception {
        floatingField(name).setInt(f, value);
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        Method m = LyricsModule.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Method internalMethod(String name) {
        return Arrays.stream(LyricsModule.class.getDeclaredMethods())
            .filter(m -> m.getName().split("\\$", 2)[0].equals(name))
            .peek(m -> m.setAccessible(true))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing internal method: " + name));
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
