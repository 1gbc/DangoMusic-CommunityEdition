import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import dangomusicce.module.impls.music.DynamicIslandModule;
import dangomusicce.module.values.BooleanValue;
import dangomusicce.module.values.FloatValue;
import dangomusicce.ui.LayoutBounds;

public final class IslandDragCheck {
    public static void main(String[] args) throws Exception {
        DynamicIslandModule island = new DynamicIslandModule();
        island.setEnabled(true);
        BooleanValue lock = (BooleanValue) field("islandLocked").get(island);
        FloatValue x = (FloatValue) field("posX").get(island);
        FloatValue y = (FloatValue) field("posY").get(island);
        Method begin = method("beginPointerDrag", 2);
        Method move = method("movePointerDrag", 2);
        Method end = method("endPointerDrag", 0);
        Class<?> placement = Class.forName(DynamicIslandModule.class.getName() + "$Placement");
        Constructor<?> constructor = placement.getDeclaredConstructor(LayoutBounds.class, float.class, float.class, float.class);
        constructor.setAccessible(true);
        field("placement").set(island, constructor.newInstance(new LayoutBounds(100f, 30f, 120f, 30f), 2f, 400f, 300f));

        check(lock.get(), "locked by default");
        check(!(boolean) begin.invoke(island, 220, 80), "locked island does not capture the mouse");
        move.invoke(island, 400, 170);
        check(x.getValue() == 0.5f && y.getValue() == 0.05f, "locked island does not move");

        lock.set(false);
        check(!(boolean) begin.invoke(island, 20, 20), "cannot initiate a drag outside the island");
        check((boolean) begin.invoke(island, 220, 80), "unlocked island captures a press on its rendered bounds");
        move.invoke(island, 420, 200);
        check(x.getValue() == 0.5f && y.getValue() == 0.3f, "position follows pointer while retaining the grab offset");
        move.invoke(island, 10_000, -10_000);
        check(x.getValue() == 1f && y.getValue() == 0f, "drag stays inside the window");

        // Start another instance for the no-movement release check, so no config manager is needed.
        DynamicIslandModule second = new DynamicIslandModule();
        second.setEnabled(true);
        ((BooleanValue) field("islandLocked").get(second)).set(false);
        field("placement").set(second, field("placement").get(island));
        check((boolean) begin.invoke(second, 220, 80), "second drag starts");
        end.invoke(second);
        move.invoke(second, 520, 200);
        check(((FloatValue) field("posY").get(second)).getValue() == 0.05f, "released drag ignores subsequent mouse movement");
        System.out.println("Dynamic Island lock, drag and edge checks passed.");
    }

    private static Field field(String name) throws Exception {
        Field field = DynamicIslandModule.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name, int count) {
        return java.util.Arrays.stream(DynamicIslandModule.class.getMethods())
            .filter(m -> m.getName().startsWith(name) && m.getParameterCount() == count)
            .findFirst().orElseThrow(AssertionError::new);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
