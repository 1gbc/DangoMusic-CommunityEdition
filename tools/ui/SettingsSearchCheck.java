import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class SettingsSearchCheck {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("dangomusicce.musicui.SettingsSearchRank");
        Method score = type.getMethod("score", String.class, String.class, String.class, String.class, String.class);
        int title = rank(score, "评论", "开启评论区", "", "网易云界面设置", "界面与 HUD");
        check(title >= 0 && title > rank(score, "评论", "网易云界面设置", "管理评论区", "", "界面与 HUD"),
            "prefer a setting label to incidental text");
        check(rank(score, "点歌位置", "点歌台横向位置", "", "B 站直播点歌台", "界面与 HUD") >= 0,
            "match an ordered Chinese abbreviation");
        check(rank(score, "ＮｅｔＥａｓｅ 评论", "开启评论区", "", "NetEase interface settings", "界面与 HUD") >= 0,
            "match terms across group and setting and normalize width and case");
        check(rank(score, "第九星团", "音量", "", "播放", "播放") < 0, "exclude unrelated settings");
        check(rank(score, " - ", "音量", "", "播放", "播放") < 0, "ignore punctuation-only input");
        verifyWindowEdges();
        System.out.println("Global settings search ranking checks passed.");
    }

    private static void verifyWindowEdges() throws Exception {
        Class<?> type = Class.forName("dangomusicce.client.BilibiliRequestHud$Layout");
        Constructor<?> constructor = type.getDeclaredConstructor(float.class, float.class, float.class, float.class,
            float.class, float.class, float.class, float.class, float.class, float.class);
        constructor.setAccessible(true);
        Method panel = type.getMethod("getPanel");
        panel.setAccessible(true);
        Object topLeft = panel.invoke(constructor.newInstance(1f, 800f, 460f, 318f, 200f, 1f, 1f, 0f, 0f, 0f));
        Object bottomRight = panel.invoke(constructor.newInstance(1f, 800f, 460f, 318f, 200f, 1f, 1f, 1f, 1f, 80f));
        check((float) topLeft.getClass().getMethod("getLeft").invoke(topLeft) == 0f &&
            (float) topLeft.getClass().getMethod("getTop").invoke(topLeft) == 0f, "HUD reaches the top left");
        check((float) bottomRight.getClass().getMethod("getRight").invoke(bottomRight) == 800f &&
            (float) bottomRight.getClass().getMethod("getBottom").invoke(bottomRight) == 460f,
            "HUD reaches the bottom right while notices fit inside the window");
    }

    private static int rank(Method score, String query, String label, String detail, String group, String tab) throws Exception {
        return (Integer) score.invoke(null, query, label, detail, group, tab);
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
