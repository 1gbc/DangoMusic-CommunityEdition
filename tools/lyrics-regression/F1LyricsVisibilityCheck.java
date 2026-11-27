import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import dangomusicce.module.impls.music.LyricsModule;
import dangomusicce.module.impls.music.DynamicIslandModule;
import dangomusicce.module.impls.music.NeteaseMusicModule;
import dangomusicce.module.values.BooleanValue;
import dangomusicce.module.values.ModuleValue;

public final class F1LyricsVisibilityCheck {
    public static void main(String[] args) throws Exception {
        LyricsModule lyrics = new LyricsModule();
        // One switch covers 3D and cockpit lyrics; it keeps the old field name so saved values still load.
        BooleanValue lyricsToggle = toggle(lyrics, "worldWhileHudHidden");
        Method allowsWorld = method(LyricsModule.class, "allowsWorldLyrics");
        Method allowsCockpit = method(LyricsModule.class, "allowsCockpitLyrics");
        check(Arrays.stream(LyricsModule.class.getDeclaredFields()).noneMatch(f -> f.getName().equals("sideWhileHudHidden")),
            "the separate cockpit F1 switch is gone");

        check(lyricsToggle.get(), "lyrics stay visible with F1 by default");
        check(allows(allowsWorld, lyrics, true) && allows(allowsCockpit, lyrics, true),
            "F1 keeps both 3D and cockpit lyrics visible by default");
        lyricsToggle.set(false);
        check(!allows(allowsWorld, lyrics, true) && !allows(allowsCockpit, lyrics, true),
            "turning the switch off hides both lyric styles with F1");
        check(allows(allowsWorld, lyrics, false) && allows(allowsCockpit, lyrics, false),
            "the F1 preference doesn't affect rendering with the vanilla HUD visible");
        lyricsToggle.set(true);

        DynamicIslandModule island = new DynamicIslandModule();
        NeteaseMusicModule player = new NeteaseMusicModule();
        BooleanValue islandToggle = toggle(island, "islandWhileHudHidden");
        BooleanValue requestsToggle = toggle(player, "requestHudWhileHidden");
        Method allowsIsland = method(DynamicIslandModule.class, "allowsIslandHud");
        Method allowsRequests = method(NeteaseMusicModule.class, "allowsRequestHud");
        check(!islandToggle.get() && !requestsToggle.get(), "both HUD cards remain hidden by default");
        check(!allows(allowsIsland, island, true) && !allows(allowsRequests, player, true),
            "both card switches govern rendering when F1 hides the HUD");
        islandToggle.set(true);
        check(allows(allowsIsland, island, true) && !allows(allowsRequests, player, true),
            "the island can show while the request card stays hidden");
        islandToggle.set(false);
        requestsToggle.set(true);
        check(!allows(allowsIsland, island, true) && allows(allowsRequests, player, true),
            "the request card can show while the island stays hidden");
        check(allows(allowsIsland, island, false) && allows(allowsRequests, player, false),
            "normal HUD rendering ignores both F1 options");

        System.out.println("F1 visibility defaults, persistence fields and controls passed for lyrics, the island and the request card.");
    }

    private static BooleanValue toggle(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        ModuleValue option = field.getAnnotation(ModuleValue.class);
        check(option != null && option.persistent(), "visibility toggle is a saved setting");
        return (BooleanValue) field.get(owner);
    }

    private static Method method(Class<?> owner, String name) {
        return Arrays.stream(owner.getMethods())
            .filter(m -> m.getName().split("\\$", 2)[0].equals(name))
            .filter(m -> Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class}))
            .findFirst().orElseThrow(() -> new AssertionError("Missing F1 visibility gate: " + name));
    }

    private static boolean allows(Method method, Object owner, boolean hidden) throws Exception {
        return (Boolean) method.invoke(owner, hidden);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
