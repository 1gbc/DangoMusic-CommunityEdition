import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import dangomusicce.lyrics.LyricLine;
import dangomusicce.lyrics.Lyrics;
import dangomusicce.module.impls.music.LyricsModule;
import dangomusicce.module.values.IntValue;

/**
 * Simultaneous 3D lyric lines: the next line shown ahead, the current line and the lines just sung.
 * Checks how the slider is split (preview / past lines), the trail bookkeeping (retire, trim, gap timeout,
 * instrumental labels), slow staggered exits, the rank spring, promoting the preview without a visual jump,
 * which line gets previewed, and the overlap metric used to place a new line.
 */
public final class WorldLyricTrailCheck {
    private static final long FADE = 400_000_000L;
    private static final long RETIRE_FADE = 900_000_000L;
    private static final long STAGGER = 70_000_000L;
    private static final float PREVIEW_ALPHA = 0.55f;

    private static Class<?> floating;
    private static Constructor<?> floatingCtor;

    public static void main(String[] args) throws Exception {
        floating = Class.forName("dangomusicce.module.impls.music.LyricsModule$Floating");
        // Pick the primary constructor explicitly (Kotlin also emits a synthetic one for default arguments).
        floatingCtor = floating.getDeclaredConstructor(List.class, long.class, long.class, double.class, double.class,
            double.class, float.class, float.class, long[].class, LyricLine.class, long.class);
        floatingCtor.setAccessible(true);

        LyricsModule lyrics = new LyricsModule();
        IntValue lines = (IntValue) field(lyrics, "worldLyricLines");
        List<?> trail = (List<?>) field(lyrics, "trail");
        List<?> fading = (List<?>) field(lyrics, "fading");
        Method retire = method("retire", floating);
        Method promote = method("promote", floating);
        Method update = method("updateLines", long.class);
        Method clear = method("clearTrail", boolean.class);
        Method rankAlpha = method("rankAlpha", float.class);
        Method rankScale = method("rankScale", float.class);
        Method rankLift = method("rankLift", float.class);
        Method lineAlpha = method("lineAlpha", floating, long.class);
        Method ghostOf = method("ghostOf", floating, long.class);
        Method pastLines = method("pastLines");
        Method previewsNext = method("previewsNext");

        // How the slider is split: 1 = current only, 2 = current + next, 3 = previous + current + next, 4 = two previous.
        check(lines.getValue() == 1, "one line by default keeps the original single-line behavior");
        int[][] split = {{1, 0, 0}, {2, 0, 1}, {3, 1, 1}, {4, 2, 1}};
        for (int[] s : split) {
            lines.setValue(s[0]);
            check((Integer) pastLines.invoke(lyrics) == s[1] && (Boolean) previewsNext.invoke(lyrics) == (s[2] == 1),
                s[0] + " lines: " + s[1] + " sung line(s) stay and " + (s[2] == 1 ? "the next line is previewed" : "no preview"));
        }

        lines.setValue(1);
        Object a = line("first");
        retire.invoke(lyrics, a);
        check(trail.isEmpty() && fading.contains(a) && lng(a, "fadeNanos") == FADE, "single line: the finished line fades out as before");
        fading.clear();

        lines.setValue(2);
        Object a2 = line("first again");
        retire.invoke(lyrics, a2);
        check(trail.isEmpty() && fading.contains(a2) && lng(a2, "fadeNanos") == RETIRE_FADE,
            "current + next: the finished line drifts away slowly instead of vanishing");
        fading.clear();

        lines.setValue(4);
        Object b = line("second");
        Object c = line("third");
        Object d = line("fourth");
        retire.invoke(lyrics, b);
        retire.invoke(lyrics, c);
        check(trail.size() == 2 && trail.get(0) == c && trail.get(1) == b, "newest finished line comes first");
        check(rank(c, "targetRank") == 1 && rank(b, "targetRank") == 2, "finished lines are ranked by age");
        long beforeRetire = System.nanoTime();
        retire.invoke(lyrics, d);
        check(trail.size() == 2 && trail.get(0) == d && trail.get(1) == c && fading.contains(b),
            "four lines keep two sung lines: the oldest is pushed out");
        check(lng(b, "fadeNanos") == RETIRE_FADE && lng(b, "fadeStartNanos") - beforeRetire >= 2 * STAGGER,
            "the pushed-out line fades slowly and only after the others have started moving");
        check(lng(c, "rankDelayNanos") - lng(d, "rankDelayNanos") >= STAGGER - 1_000_000L,
            "older lines start moving a moment later, one after another");

        // Ranks follow a critically damped spring: a gentle start, no overshoot, settled within a moment.
        setField(lyrics, "active", line("singing"));
        float cBefore = flt(c, "rank");
        long now = System.nanoTime();
        check(now < lng(c, "rankDelayNanos"), "test setup ran within the stagger");
        update.invoke(lyrics, now);
        float first = flt(d, "rank");
        check(first > 0f && first < 0.05f, "the rank starts moving gently instead of jumping");
        check(flt(c, "rank") == cBefore, "the older line waits for its turn");
        float max = 0f;
        for (int i = 1; i <= 40; i++) {
            update.invoke(lyrics, now + i * 16_000_000L + STAGGER);
            max = Math.max(max, flt(d, "rank"));
        }
        check(Math.abs(flt(d, "rank") - 1f) < 0.02f && max <= 1.0001f, "rank eases from current to previous without overshooting");
        check(Math.abs(flt(c, "rank") - 2f) < 0.03f, "the older line has moved on too");

        // Lowering the setting trims the trail; the exit starts from the dimmed look.
        fading.clear();
        lines.setValue(3);
        long t = now + 41 * 16_000_000L + STAGGER;
        update.invoke(lyrics, t);
        check(trail.size() == 1 && trail.get(0) == d && fading.contains(c), "fewer lines trims the oldest");
        float cAlpha = (Float) rankAlpha.invoke(lyrics, flt(c, "rank"));
        check(Math.abs(flt(c, "fadeAlpha") - cAlpha) < 1e-4 && cAlpha < 0.5f, "a trimmed line fades from its dimmed alpha");

        // A long gap (interlude, blank line) clears the finished lines, slowly.
        setField(lyrics, "active", null);
        long gap = t + 2_000_000_000L;
        update.invoke(lyrics, gap);
        check(trail.size() == 1, "short gaps keep the finished lines");
        update.invoke(lyrics, gap + 4_000_000_000L);
        check(trail.size() == 1, "gaps under five seconds keep the finished lines");
        update.invoke(lyrics, gap + 5_100_000_000L);
        check(trail.isEmpty() && lng(d, "fadeNanos") == RETIRE_FADE, "gaps over five seconds let the finished lines drift away");

        // Instrumental labels never linger.
        lines.setValue(4);
        Object label = line("Interlude");
        Field instrumental = floating.getDeclaredField("instrumental");
        instrumental.setAccessible(true);
        instrumental.setBoolean(label, true);
        retire.invoke(lyrics, label);
        check(trail.isEmpty() && lng(label, "fadeNanos") == FADE, "instrumental labels fade quickly instead of staying");
        retire.invoke(lyrics, line("again"));
        clear.invoke(lyrics, false);
        check(trail.isEmpty(), "clearing fades every finished line");

        // Looks per rank: -1 = next line, 0 = current, 1.. = sung.
        float p = (Float) rankAlpha.invoke(lyrics, -1f);
        float r0 = (Float) rankAlpha.invoke(lyrics, 0f);
        float r1 = (Float) rankAlpha.invoke(lyrics, 1f);
        float r2 = (Float) rankAlpha.invoke(lyrics, 2f);
        float r3 = (Float) rankAlpha.invoke(lyrics, 3f);
        float r9 = (Float) rankAlpha.invoke(lyrics, 9f);
        check(r0 == 1f && r0 > r1 && r1 > r2 && r2 > r3 && r3 == r9 && r3 > 0.1f, "older lines are dimmer but stay readable");
        check(Math.abs(p - PREVIEW_ALPHA) < 1e-6 && p < r0 && p > r1, "the next line is dimmer than the current one but easier to read than sung lines");
        check((Float) rankScale.invoke(lyrics, -1f) < 1f && (Float) rankLift.invoke(lyrics, -1f) > 0f
            && (Float) rankLift.invoke(lyrics, 1f) < 0f, "the next line waits a little smaller and lower; sung lines float up");

        // Promoting the preview: it stays in place and brightens; the unsung part never disappears.
        lines.setValue(3);
        setField(lyrics, "active", null);
        Object next = line("coming up");
        setFloat(next, "rank", -1f);
        setInt(next, "targetRank", -1);
        setField(lyrics, "upcoming", next);
        long p0 = System.nanoTime();
        float ghostBefore = (Float) ghostOf.invoke(lyrics, next, p0);
        float alphaBefore = (Float) lineAlpha.invoke(lyrics, next, p0);
        check(Math.abs(ghostBefore - PREVIEW_ALPHA) < 1e-4 && Math.abs(alphaBefore - PREVIEW_ALPHA) < 1e-4,
            "the preview shows the whole line, faintly");
        promote.invoke(lyrics, next);
        check(field(lyrics, "active") == next && field(lyrics, "upcoming") == null && rank(next, "targetRank") == 0,
            "the preview becomes the current line itself");
        float lastAlpha = alphaBefore;
        setField(lyrics, "trailNanos", 0L);   // the gap checks above ran on a synthetic clock ahead of p0
        for (int i = 0; i <= 40; i++) {
            long at = p0 + i * 16_000_000L;
            update.invoke(lyrics, at);
            float ga = (Float) ghostOf.invoke(lyrics, next, at);
            float la = (Float) lineAlpha.invoke(lyrics, next, at);
            check(Math.abs(ga - PREVIEW_ALPHA) < 1e-4, "unsung words keep the same faint look while the line steps forward");
            check(la >= lastAlpha - 1e-5, "the line brightens steadily");
            lastAlpha = la;
        }
        check(Math.abs(lastAlpha - 1f) < 0.02f, "the promoted line ends fully bright");
        lines.setValue(1);
        check((Float) ghostOf.invoke(lyrics, next, p0) == 0f, "a single line keeps the original word-by-word reveal");

        // Which line is previewed: the first line with text after the current (or last started) one.
        Lyrics song = new Lyrics(List.of(new LyricLine(0L, "one"), new LyricLine(1000L, ""),
            new LyricLine(2000L, "three"), new LyricLine(3000L, "four")), "test");
        Method upcomingIndex = method("upcomingIndex", Lyrics.class, int.class, long.class);
        check((Integer) upcomingIndex.invoke(lyrics, song, 0, 500L) == 2, "blank lines are skipped");
        check((Integer) upcomingIndex.invoke(lyrics, song, -1, 1500L) == 2, "during a gap the next line with text is previewed");
        check((Integer) upcomingIndex.invoke(lyrics, song, 2, 1800L) == 3, "KTV switching early still previews the line after it");
        check((Integer) upcomingIndex.invoke(lyrics, song, 3, 3500L) == -1, "nothing after the last line");
        Method sameLine = method("sameLine", LyricLine.class, LyricLine.class);
        check((Boolean) sameLine.invoke(lyrics, new LyricLine(2000L, "three"), new LyricLine(2000L, "three", null, "translated", null)),
            "a reloaded lyric (new objects, translation added) still matches the preview");
        check(!(Boolean) sameLine.invoke(lyrics, new LyricLine(2000L, "three four"), new LyricLine(2000L, "three")),
            "a split line is a different line");

        // Overlap metric for placing a new line: boxes are (yaw, half width, top, bottom) in degrees.
        Class<?> box = Class.forName("dangomusicce.module.impls.music.LyricsModule$LyricBox");
        Constructor<?> boxCtor = box.getDeclaredConstructor(double.class, double.class, double.class, double.class);
        boxCtor.setAccessible(true);
        Method separation = method("separation", box, box);
        Object centre = boxCtor.newInstance(0.0, 10.0, -2.0, 2.0);
        double overlap = (Double) separation.invoke(lyrics, centre, boxCtor.newInstance(5.0, 10.0, -1.0, 3.0));
        double beside = (Double) separation.invoke(lyrics, centre, boxCtor.newInstance(25.0, 10.0, -2.0, 2.0));
        double below = (Double) separation.invoke(lyrics, centre, boxCtor.newInstance(0.0, 10.0, 5.0, 9.0));
        double above = (Double) separation.invoke(lyrics, boxCtor.newInstance(0.0, 10.0, 5.0, 9.0), centre);
        double wrapped = (Double) separation.invoke(lyrics, boxCtor.newInstance(175.0, 8.0, -2.0, 2.0), boxCtor.newInstance(-175.0, 8.0, -2.0, 2.0));
        check(overlap < 0, "overlapping lines have negative room");
        check(Math.abs(beside - 5.0) < 1e-9, "side by side lines report the horizontal gap");
        check(Math.abs(below - 3.0) < 1e-9 && Math.abs(above - 3.0) < 1e-9, "stacked lines report the vertical gap either way");
        // 10 degrees apart across +-180 with 16 degrees of combined half widths: they overlap; the shallower
        // (vertical, 4 degrees) penetration is reported. Without wrapping the gap would be 334 degrees.
        check(Math.abs(wrapped + 4.0) < 1e-9, "yaw wraps around at +-180 degrees");

        System.out.println("World lyric lines: slider split, retire, trim, gap timeout, labels, staggered slow exits, rank spring, "
            + "preview promotion, preview choice and overlap metric passed.");
    }

    private static Object line(String text) throws Exception {
        LyricLine lyric = new LyricLine(0L, text);
        return floatingCtor.newInstance(List.of(text), 0L, 250L, 0.0, 0.0, 0.0, 0f, 0f, new long[0], lyric, 1000L);
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

    private static long lng(Object f, String name) throws Exception {
        return floatingField(name).getLong(f);
    }

    private static void setFloat(Object f, String name, float value) throws Exception {
        floatingField(name).setFloat(f, value);
    }

    private static void setInt(Object f, String name, int value) throws Exception {
        floatingField(name).setInt(f, value);
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        Method m = LyricsModule.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
