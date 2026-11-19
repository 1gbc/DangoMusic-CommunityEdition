import me.sorihiko.dangomusicgtnh.musicui.CoverflowMotion;

public class CoverflowInteractionCheck {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static int settle(CoverflowMotion motion, float dt) {
        int events = 0;
        for (int i = 0; i < 1600; i++) if (motion.step(dt, 100)) events++;
        check(events == 1, "A gesture must trigger exactly one playback after settling");
        check(motion.getPosition() == motion.getTarget(), "Settled cover must be exactly centered");
        return motion.getTarget();
    }
    private static CoverflowMotion flick(long duration) {
        var motion = new CoverflowMotion();
        motion.reset(20);
        motion.down(400, 100, 1_000_000_000L);
        motion.drag(300, 100, 1_000_000_000L + duration / 2);
        motion.up(200, 100, 1_000_000_000L + duration);
        return motion;
    }
    public static void main(String[] args) {
        var click = new CoverflowMotion();
        click.reset(20);
        click.down(100, 100, 1);
        check(click.up(103, 100, 50_000_001L), "Small movement remains a single click");
        for (int i=0; i<100; i++) check(!click.step(.016f, 100), "A click cannot schedule drag playback");
        int slow = settle(flick(400_000_000L), 1f/60);
        int fast = settle(flick(50_000_000L), 1f/60);
        check(fast > slow, "Fast release must carry farther than slow dragging");
        check(Math.abs(settle(flick(50_000_000L), 1f/30) - settle(flick(50_000_000L), 1f/120)) <= 1,
            "Refresh rate must not materially change inertia");
        var caught = flick(50_000_000L);
        caught.step(.016f, 100);
        caught.down(50, 100, 2_000_000_000L);
        float held = caught.getPosition();
        for (int i=0;i<100;i++) check(!caught.step(.016f, 100), "Regrab cancels previous playback");
        check(caught.getPosition()==held, "Holding a cover cancels inertia");
        caught.up(50,100,2_100_000_000L);
        caught.moveTo(5);
        for (int i=0;i<100;i++) check(!caught.step(.016f,100), "External navigation cancels pending playback");
        var edge=flick(50_000_000L);
        for (int i=0;i<400;i++) edge.step(.016f,2);
        check(edge.getPosition()>=0 && edge.getPosition()<=1,"Track-count changes retain valid bounds");
        edge.reset(99);
        edge.constrain(2);
        check(edge.getTarget()==1 && edge.getPosition()==1, "A shrinking playlist is safe before the next animation tick");
        edge.step(.016f,0);
        check(edge.getPosition()==0,"Empty playlists reset motion");
        System.out.println("Coverflow clicks, inertia, settling, regrab, frame rates, external navigation and boundaries passed.");
    }
}
