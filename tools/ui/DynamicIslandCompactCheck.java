import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import me.sorihiko.dangomusicgtnh.*;
import me.sorihiko.dangomusicgtnh.gfx.*;
import me.sorihiko.dangomusicgtnh.module.impls.music.DynamicIslandModule;
import me.sorihiko.dangomusicgtnh.module.values.*;
import me.sorihiko.dangomusicgtnh.music.*;
import me.sorihiko.dangomusicgtnh.platform.Platform;
import me.sorihiko.dangomusicgtnh.ui.*;

public class DynamicIslandCompactCheck {
    static Field field(Class<?> type, String name) throws Exception {
        Field f=type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    static NeteaseTrack track(String id,String title) {
        return new NeteaseTrack(1,title,List.of("Extra artist"),120000,"",MusicSource.APPLE,id);
    }
    public static void main(String[] args)throws Exception {
        var island = new DynamicIslandModule();
        var layout = (IntValue)field(DynamicIslandModule.class,"islandLayout").get(island);
        var animations = (BooleanValue)field(DynamicIslandModule.class,"islandAnimations").get(island);
        check(layout.getDefault()==1 && !animations.getDefault(),"Fresh installation uses compact and disables optional animations");
        var clipStack = new ArrayDeque<LayoutBounds>();
        var texts = new ArrayList<String>();
        var textXs = new ArrayList<Float>();
        var titleSizes = new ArrayList<Float>();
        var cjkRuns = new ArrayList<CjkRun>();
        float[] renderScale = {1f};
        var shell = new ArrayList<LayoutBounds>();
        var shapes = new ArrayList<LayoutBounds>();
        var textClips = new ArrayList<LayoutBounds>();
        var radii = new ArrayList<Float>();
        int[] width={1280};
        var canvas = proxy(GuiCanvas.class,(p,m,a)->{
            switch(m.getName()) {
                case "drawShape": shapes.add((LayoutBounds)a[0]); break;
                case "drawGlass": shell.add((LayoutBounds)a[0]); radii.add(((GlassStyle)a[1]).getRadius()); break;
                case "drawText": texts.add((String)a[0]); textXs.add((float)a[1]); textClips.add(clipStack.peek());
                    titleSizes.add(((FontStyle)a[3]).getFontSize() * renderScale[0]); break;
                case "pushScale": renderScale[0]=(float)a[0]; break;
                case "pushClip": clipStack.push((LayoutBounds)a[0]); break;
                case "popClip": clipStack.pop(); break;
                case "getOpacity": return 1f;
            }
            return null;
        });
        field(DangoClient.class,"gfx").set(null,proxy(Gfx.class,(p,m,a)->{
            return switch(m.getName()) {
                case "newCanvas" -> canvas;
                case "drawCjkRuns" -> {
                    for (Object run : (List<?>)a[0]) cjkRuns.add((CjkRun)run);
                    yield null;
                }
                case "uiTextWidth", "cjkWidth" -> ((String)a[0]).length() * (float)a[1] * .58f;
                default -> null;
            };
        }));
        Path cfg=Files.createTempDirectory(Path.of(args[0]),"island-");
        DangoMusic.start(proxy(Platform.class,(p,m,a)->{
            return switch(m.getName()){
                case "getConfigDir" -> cfg;
                case "getWindowWidth" -> width[0];
                case "getWindowHeight" -> 800;
                case "getMouseX","getMouseY" -> 0;
                default -> null;
            };
        }),List.of());
        Method render=Arrays.stream(DynamicIslandModule.class.getDeclaredMethods())
            .filter(m->m.getName().startsWith("renderIsland$")&&m.getParameterCount()==1).findFirst().orElseThrow();
        MusicSources.INSTANCE.setSelected(MusicSource.APPLE);
        MusicPlayer.INSTANCE.acceptAppleState(track("1","Short"),0,true);
        island.setEnabled(true);
        Field previous=field(DynamicIslandModule.class,"lastNanos");
        Runnable draw=()->{
            try {
                texts.clear();textXs.clear();textClips.clear();shell.clear();shapes.clear();radii.clear();titleSizes.clear();cjkRuns.clear();
                previous.setLong(island,System.nanoTime()-16_000_000L);
                render.invoke(island,new Object[]{null});
                check(clipStack.isEmpty(),"Islands must restore scissor");
            } catch(ReflectiveOperationException e){throw new RuntimeException(e);}
        };
        for(boolean continuous:new boolean[]{false,true}) {
            UiStyle.INSTANCE.setContinuous(continuous);
            for(int i=0;i<70;i++)draw.run();
            check(texts.equals(List.of("Short")),"Only title is displayed in compact: "+texts);
            check(shell.get(0).getWidth()>150 && shell.get(0).getWidth()<180,"Compact keeps a longer minimum width");
            check(Math.abs(radii.get(0)-shell.get(0).getHeight()/2)<.001f,"Use capsule radius");
            float titleWidth = "Short".length() * titleSizes.get(0) / renderScale[0] * .58f;
            float center = shell.get(0).getX() + shell.get(0).getWidth() / 2f;
            check(Math.abs(textXs.get(0) + titleWidth / 2f - center) < .01f,"Short title is centered in the compact capsule");
            check(Math.abs(titleSizes.get(0) - 18f * 1280f / 2464f) < .001f,
                "The island renders the Apple Music track title at the same screen size");
        }
        String longTitle="A long title retaining every character through scrolling ".repeat(10);
        MusicPlayer.INSTANCE.acceptAppleState(track("2",longTitle),0,true);
        draw.run();
        check(texts.equals(List.of(longTitle)) && Math.abs(textXs.get(0)-textClips.get(0).getX())<.001f,
            "A new song starts from the first character");
        var started=field(DynamicIslandModule.class,"titleScrollStartedAt");
        started.setLong(island,System.nanoTime()-2_000_000_000L);
        for(int i=0;i<70;i++)draw.run();
        check(shell.get(0).getWidth()<=256.001f && textXs.get(0)<textClips.get(0).getX(),"Title rolls inside the fixed cap with animations off");
        check(textClips.get(0).getX()-shell.get(0).getX()>=45f,"Clip starts past the cover and gap");
        width[0]=70;
        for(int i=0;i<70;i++)draw.run();
        check(shell.get(0).getWidth()<=174.001f,"Cap respects narrow windows");
        animations.set(true);
        for(int i=0;i<10;i++)draw.run();
        check(texts.equals(List.of(longTitle)) && shell.get(0).getHeight()<33f,"Compact stays cover and title with animations on");
        MusicPlayer.INSTANCE.acceptAppleState(track("3", "Next song"),0,true);
        for (int i=0;i<35;i++) {
            draw.run();
            check(texts.equals(List.of("Next song")) && shell.get(0).getHeight()<33f,
                "Each frame after switching songs remains compact with animations on");
        }
        Method titleOffset = DynamicIslandModule.class.getDeclaredMethod("titleOffset",float.class,double.class);
        titleOffset.setAccessible(true);
        check((float)titleOffset.invoke(island,81f,0.8) == 0f,"Start with a reading pause");
        check(Math.abs((float)titleOffset.invoke(island,81f,4.5)-81f)<.001f,"The full title suffix can be read");
        check((float)titleOffset.invoke(island,81f,7.0)<81f,"Return smoothly after reaching the ending");
        width[0]=2560;
        MusicPlayer.INSTANCE.acceptAppleState(track("4", "中文歌名"),0,true);
        draw.run();
        check(cjkRuns.size()==1 && cjkRuns.get(0).getStyle()==CjkStyle.NOTO_SC_BOLD,
            "Chinese titles use the Apple lyrics page font fallback");
        check(Math.abs(cjkRuns.get(0).getSize()*renderScale[0] - 18f*800f/1292f*.96f)<.001f,
            "The title also matches the lyrics page in a height-limited viewport");
        width[0]=1280;
        layout.setValue(0);
        for (boolean continuous : new boolean[]{false, true}) {
            UiStyle.INSTANCE.setContinuous(continuous);
            MusicPlayer.INSTANCE.acceptAppleState(track("5", "Blue Moon"),65000,false);
            for(int i=0;i<100;i++)draw.run();
            check(Math.abs(shell.get(0).getWidth()-208f)<.05f && Math.abs(shell.get(0).getHeight()-54f)<.05f,
                "Full island B uses the approved 208 x 54 dimensions");
            check(texts.contains("Extra artist") && texts.contains("1:05") && texts.contains("-0:55"),
                "The reduced full layout retains artist, elapsed time and remaining time");
            check(shapes.stream().anyMatch(b -> b.getWidth()==36f && b.getHeight()==36f),
                "The full layout shrinks its cover to 36 pixels");
            check(shapes.stream().anyMatch(b -> b.getHeight()==3f && b.getWidth()>40f),
                "The progress track still has enough room with optional animations");
            MusicPlayer.INSTANCE.acceptAppleState(track("6",longTitle),0,true);
            for(int i=0;i<90;i++)draw.run();
            check(Math.abs(shell.get(0).getWidth()-270f)<.05f,"Long full titles are capped at 270 pixels");
            width[0]=70;
            for(int i=0;i<90;i++)draw.run();
            check(shell.get(0).getWidth()<=174.01f,"The full island fits a narrow window");
            width[0]=1280;
        }
        System.out.println("Compact layout, stable short title, scrolling caps, clipping, narrow window and song change passed");
    }
}
