import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import dangomusicce.module.impls.music.LyricsModule;
import dangomusicce.module.values.BooleanValue;
import dangomusicce.module.values.ModuleValue;

public final class F1LyricsVisibilityCheck {
    public static void main(String[] args) throws Exception {
        LyricsModule lyrics = new LyricsModule();
        BooleanValue world = toggle(lyrics, "worldWhileHudHidden");
        BooleanValue cockpit = toggle(lyrics, "sideWhileHudHidden");
        Method allowsWorld = method("allowsWorldLyrics");
        Method allowsCockpit = method("allowsCockpitLyrics");

        check(world.get() && !cockpit.get(), "defaults preserve both modes' existing F1 behavior");
        check(allows(allowsWorld, lyrics, true) && !allows(allowsCockpit, lyrics, true),
            "F1 keeps world lyrics visible and hides cockpit lyrics by default");
        world.set(false);
        cockpit.set(true);
        check(!allows(allowsWorld, lyrics, true) && allows(allowsCockpit, lyrics, true),
            "each mode independently controls visibility with F1");
        check(allows(allowsWorld, lyrics, false) && allows(allowsCockpit, lyrics, false),
            "F1-only preferences don't affect rendering with the vanilla HUD visible");
        cockpit.set(false);
        check(!allows(allowsWorld, lyrics, true) && !allows(allowsCockpit, lyrics, true),
            "both modes can be hidden together");
        world.set(true);
        cockpit.set(true);
        check(allows(allowsWorld, lyrics, true) && allows(allowsCockpit, lyrics, true),
            "both modes can remain visible together");

        System.out.println("F1 visibility defaults, persistence fields and independent controls passed.");
    }

    private static BooleanValue toggle(LyricsModule lyrics, String name) throws Exception {
        Field field = LyricsModule.class.getDeclaredField(name);
        field.setAccessible(true);
        ModuleValue option = field.getAnnotation(ModuleValue.class);
        check(option != null && option.persistent(), "visibility toggle is a saved setting");
        return (BooleanValue) field.get(lyrics);
    }

    private static Method method(String name) {
        return Arrays.stream(LyricsModule.class.getMethods())
            .filter(m -> m.getName().split("\\$", 2)[0].equals(name))
            .filter(m -> Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class}))
            .findFirst().orElseThrow(() -> new AssertionError("Missing F1 visibility gate: " + name));
    }

    private static boolean allows(Method method, LyricsModule lyrics, boolean hidden) throws Exception {
        return (Boolean) method.invoke(lyrics, hidden);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
