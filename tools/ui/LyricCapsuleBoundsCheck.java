import java.lang.reflect.*;
import dangomusicce.lyrics.LyricLine;
import dangomusicce.lyrics.Lyrics;
import dangomusicce.ui.LayoutBounds;
import java.util.Collections;

public final class LyricCapsuleBoundsCheck {
    public static void main(String[] args) throws Exception {
        Class<?> card = Class.forName("dangomusicce.musicui.IslandPlayerCard");
        float fullWidth = card.getField("WIDTH").getFloat(null);
        float fullHeight = card.getField("HEIGHT").getFloat(null);
        check(fullWidth == 306f && fullHeight == 94f, "expanded capsule matches the original footprint");

        Class<?> type = Class.forName("dangomusicce.musicui.IslandPlayerCard$Layout");
        Constructor<?> make = type.getDeclaredConstructor(LayoutBounds.class, float.class, float.class);
        make.setAccessible(true);
        Method hit = type.getDeclaredMethod("controlAt", float.class, float.class, boolean.class);
        hit.setAccessible(true);
        Object compact = make.newInstance(new LayoutBounds(20, 15, 218, 34), 0f, 500f);
        Object full = make.newInstance(new LayoutBounds(20, 15, fullWidth, fullHeight), 1f, 500f);
        Object low = make.newInstance(new LayoutBounds(20, 400, fullWidth, fullHeight), 1f, 500f);
        Object mid = make.newInstance(new LayoutBounds(20, 15, 262, 64), 0.5f, 500f);
        float compactY = bounds(type, compact, "Lyrics").getY();
        float fullY = bounds(type, full, "Lyrics").getY();
        check(compactY < bounds(type, mid, "Lyrics").getY() && bounds(type, mid, "Lyrics").getY() < fullY,
            "lyric moves continuously near the top");
        check(bounds(type, full, "Cover").getWidth() == 76f, "expanded artwork fills the left column");
        LayoutBounds compactProgress = bounds(type, compact, "Progress");
        check(hit.invoke(compact, compactProgress.getX() + 2f, compactProgress.getY(), false) == null, "compact mode has no controls");
        LayoutBounds fullProgress = bounds(type, full, "Progress");
        check(bounds(type, compact, "Lyrics").getHeight() >= 18f, "compact lyrics leave room for CJK ascenders, descenders and shadow");
        check(bounds(type, compact, "Lyrics").getBottom() < compactProgress.getY(), "compact track clears the lyric");
        check(bounds(type, full, "Lyrics").getHeight() >= 16f, "expanded lyrics keep vertical room for CJK text");
        check(bounds(type, full, "Lyrics").getBottom() < bounds(type, full, "Title").getY(), "expanded lyric clears title");
        check(bounds(type, full, "Lock").getBottom() <= bounds(type, full, "Title").getY(), "lock clears the title row");
        check(bounds(type, full, "Title").getBottom() < bounds(type, full, "Subtitle").getY(), "title clears artist and album");
        LayoutBounds elapsed = bounds(type, full, "ElapsedTime");
        LayoutBounds duration = bounds(type, full, "DurationTime");
        check(bounds(type, full, "Subtitle").getBottom() < elapsed.getY(), "track metadata clears timestamps");
        check(elapsed.getRight() < fullProgress.getX() && duration.getX() > fullProgress.getRight(),
            "timestamps stay on opposite sides of the seek bar");
        Object opening = make.newInstance(new LayoutBounds(20, 15, 289, 82.6f), 0.81f, 500f);
        LayoutBounds openingBar = bounds(type, opening, "Progress");
        LayoutBounds openingElapsed = bounds(type, opening, "ElapsedTime");
        check(openingBar.getX() > openingElapsed.getRight() &&
            openingElapsed.getY() > bounds(type, opening, "Subtitle").getBottom(),
            "fading metadata and timestamps already have separate space");
        checkLeadIn();
        Class<?> control = Class.forName("dangomusicce.musicui.IslandPlayerCard$Control");
        Method button = type.getDeclaredMethod("button", control);
        button.setAccessible(true);
        Object[] controls = control.getEnumConstants();
        LayoutBounds firstButton = (LayoutBounds) button.invoke(full, controls[0]);
        LayoutBounds lastButton = (LayoutBounds) button.invoke(full, controls[3]);
        check(Math.abs((firstButton.getX() + lastButton.getRight()) / 2f -
            (fullProgress.getX() + fullProgress.getRight()) / 2f) < 0.01f,
            "expanded buttons share the progress track center");
        check("PROGRESS".equals(hit.invoke(full, fullProgress.getX() + 2f, fullProgress.getY(), false).toString()), "full progress can be dragged");
        check(elapsed.getBottom() <= firstButton.getY() && firstButton.getBottom() < 15f + fullHeight,
            "timestamps and controls fit on separate rows");
        check(hit.invoke(full, firstButton.getX() + 2f, firstButton.getY() + 1f, false) == controls[0],
            "the seek hit region leaves the button row clickable");
        check(bounds(type, full, "Popup").getY() >= 109f, "top-docked popup opens below");
        check(bounds(type, low, "Popup").getBottom() <= 400f, "bottom-docked popup opens above");
        LayoutBounds slider = bounds(type, full, "VolumeTrack");
        check("VOLUME_SLIDER".equals(hit.invoke(full, slider.getX() + 2f, slider.getY(), true).toString()), "volume slider hit matches track");
        LayoutBounds lock = bounds(type, full, "Lock");
        check("LOCK".equals(hit.invoke(full, lock.getX() + 2f, lock.getY() + 2f, false).toString()), "lock is distinct from controls");
        System.out.println("Lyric capsule layout and hit tests passed.");
    }
    private static void checkLeadIn() throws Exception {
        Class<?> companion = Class.forName("dangomusicce.musicui.IslandPlayerCard$Companion");
        Method label = companion.getDeclaredMethod("lyricLabel", String.class, LyricLine.class);
        label.setAccessible(true);
        Object instance = Class.forName("dangomusicce.musicui.IslandPlayerCard").getField("Companion").get(null);
        Lyrics sheet = new Lyrics(Collections.singletonList(new LyricLine(5000, "\u5915\u713c\u3051\u8272\u306e\u7a7a")), "test");
        int intro = sheet.indexAt(4999);
        int active = sheet.indexAt(5000);
        check(intro == -1 && "Track title".equals(label.invoke(instance, "Track title", null)), "the lead-in shows the track title");
        check("\u5915\u713c\u3051\u8272\u306e\u7a7a".equals(label.invoke(instance, "Track title", sheet.getLines().get(active))),
            "the first lyric replaces the title at its timestamp");
        check("".equals(label.invoke(instance, "Track title", new LyricLine(7000, ""))),
            "a blank timed line does not bring back the intro label");
    }

    private static LayoutBounds bounds(Class<?> type, Object layout, String name) throws Exception {
        Method method = type.getDeclaredMethod("get" + name);
        method.setAccessible(true);
        return (LayoutBounds) method.invoke(layout);
    }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
