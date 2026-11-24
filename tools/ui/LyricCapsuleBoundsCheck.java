import java.lang.reflect.*;
import dangomusicce.ui.LayoutBounds;

public final class LyricCapsuleBoundsCheck {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("dangomusicce.musicui.IslandPlayerCard$Layout");
        Constructor<?> make = type.getDeclaredConstructor(LayoutBounds.class, float.class, float.class);
        make.setAccessible(true);
        Method hit = type.getDeclaredMethod("controlAt", float.class, float.class, boolean.class);
        hit.setAccessible(true);
        Object compact = make.newInstance(new LayoutBounds(20, 15, 218, 32), 0f, 500f);
        Object full = make.newInstance(new LayoutBounds(20, 15, 306, 94), 1f, 500f);
        Object low = make.newInstance(new LayoutBounds(20, 400, 306, 94), 1f, 500f);
        check(bounds(type, compact, "Lyrics").getHeight() == bounds(type, full, "Lyrics").getHeight(), "one lyric row in both states");
        Object mid = make.newInstance(new LayoutBounds(20, 15, 262, 63), 0.5f, 500f);
        float compactY = bounds(type, compact, "Lyrics").getY();
        float fullY = bounds(type, full, "Lyrics").getY();
        check(compactY < bounds(type, mid, "Lyrics").getY() && bounds(type, mid, "Lyrics").getY() < fullY,
            "lyric moves continuously near the top");
        check(bounds(type, full, "Cover").getWidth() == 76f, "expanded artwork fills the left column");
        LayoutBounds compactProgress = bounds(type, compact, "Progress");
        check(hit.invoke(compact, compactProgress.getX() + 2f, compactProgress.getY(), false) == null, "compact mode has no controls");
        LayoutBounds fullProgress = bounds(type, full, "Progress");
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
        check(bounds(type, full, "Popup").getY() >= 109f, "top-docked popup opens below");
        check(bounds(type, low, "Popup").getBottom() <= 400f, "bottom-docked popup opens above");
        LayoutBounds slider = bounds(type, full, "VolumeTrack");
        check("VOLUME_SLIDER".equals(hit.invoke(full, slider.getX() + 2f, slider.getY(), true).toString()), "volume slider hit matches track");
        LayoutBounds lock = bounds(type, full, "Lock");
        check("LOCK".equals(hit.invoke(full, lock.getX() + 2f, lock.getY() + 2f, false).toString()), "lock is distinct from controls");
        System.out.println("Lyric capsule layout and hit tests passed.");
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
