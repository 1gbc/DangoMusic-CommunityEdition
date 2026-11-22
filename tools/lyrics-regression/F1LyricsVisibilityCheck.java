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
        BooleanValue world = toggle(lyrics, "worldWhileHudHidden");
        BooleanValue cockpit = toggle(lyrics, "sideWhileHudHidden");
        Method allowsWorld = method(LyricsModule.class, "allowsWorldLyrics");
        Method allowsCockpit = method(LyricsModule.class, "allowsCockpitLyrics");

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

        System.out.println("F1 visibility defaults, persistence fields and independent controls passed for all four overlays.");
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
