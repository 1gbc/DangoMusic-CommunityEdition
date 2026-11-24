import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import dangomusicce.*;
import dangomusicce.gfx.*;
import dangomusicce.module.impls.music.DynamicIslandModule;
import dangomusicce.module.values.*;
import dangomusicce.music.*;
import dangomusicce.platform.Platform;
import dangomusicce.ui.*;

public class DynamicIslandCompactCheck {
    static Field field(Class<?> type, String name) throws Exception {
        Field f=type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    static NeteaseTrack track(String id,String title) {
        var track = new NeteaseTrack(1,title,List.of("Extra artist"),120000,"",MusicSource.APPLE,id);
        track.setAlbumName("Afterglow");
        return track;
    }
    public static void main(String[] args)throws Exception {
        var island = new DynamicIslandModule();
        var layout = (IntValue)field(DynamicIslandModule.class,"islandLayout").get(island);
        var animations = (BooleanValue)field(DynamicIslandModule.class,"islandAnimations").get(island);
        check(layout.getValue()==0 && layout.getDefault()==0 && !animations.getDefault(),
            "Fresh installation uses style C and disables optional animations");
        var clipStack = new ArrayDeque<LayoutBounds>();
        var texts = new ArrayList<String>();
        var textXs = new ArrayList<Float>();
        var titleSizes = new ArrayList<Float>();
        var fonts = new ArrayList<String>();
        var cjkRuns = new ArrayList<CjkRun>();
        float[] renderScale = {1f};
        var shell = new ArrayList<LayoutBounds>();
        var shapes = new ArrayList<LayoutBounds>();
        var shapeRadii = new ArrayList<Float>();
        var textClips = new ArrayList<LayoutBounds>();
        var radii = new ArrayList<Float>();
        int[] width={1280};
        int[] height={800};
        var canvas = proxy(GuiCanvas.class,(p,m,a)->{
            switch(m.getName()) {
                case "drawShape": shapes.add((LayoutBounds)a[0]); shapeRadii.add(((ShapeStyle)a[1]).getRadius()); break;
                case "drawGlass": shell.add((LayoutBounds)a[0]); radii.add(((GlassStyle)a[1]).getRadius()); break;
                case "drawText": if (((FontStyle)a[3]).getColor().getR() == 0f) break; texts.add((String)a[0]); textXs.add((float)a[1]); textClips.add(clipStack.peek());
                    titleSizes.add(((FontStyle)a[3]).getFontSize() * renderScale[0]);
                    fonts.add(((FontStyle)a[3]).getFont()); break;
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
                    for (Object run : (List<?>)a[0]) { if (((CjkRun)run).getR() != 0f) cjkRuns.add((CjkRun)run); }
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
                case "getWindowHeight" -> height[0];
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
                texts.clear();textXs.clear();textClips.clear();shell.clear();shapes.clear();shapeRadii.clear();radii.clear();titleSizes.clear();fonts.clear();cjkRuns.clear();
                previous.setLong(island,System.nanoTime()-16_000_000L);
                render.invoke(island,new Object[]{null});
                check(clipStack.isEmpty(),"Islands must restore scissor");
            } catch(ReflectiveOperationException e){throw new RuntimeException(e);}
        };
        for (int i=0;i<100;i++) draw.run();
        check(Math.abs(shell.get(0).getHeight()-29f/.6f)<.01f && radii.get(0)==10f,
            "The default style C keeps card corners at style B's height");
        layout.setValue(1);
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
        animations.set(false);
        for (boolean continuous : new boolean[]{false, true}) {
            UiStyle.INSTANCE.setContinuous(continuous);
            width[0]=1920;
            height[0]=1080;
            MusicPlayer.INSTANCE.acceptAppleState(track("5", "Blue Moon"),65000,false);
            layout.setValue(2);
            for (int i=0;i<100;i++) draw.run();
            var slim = shell.get(0);
            float expectedTitleSize = 18f * Math.min(width[0] / 2464f,height[0] / 1292f);
            check(Math.abs(slim.getWidth()-238.5f)<.01f && Math.abs(slim.getHeight()-29f/.6f)<.01f
                && radii.get(0)==5.5f, "Style B restores the slim card and its original corners");
            check(texts.equals(List.of("Blue Moon", "Extra artist / Afterglow")) && shapes.get(1).getHeight()==2f,
                "Style B renders both text lines and the thin progress bar");
            check(Math.abs(titleSizes.get(0)-expectedTitleSize)<.001f && titleSizes.get(1)<titleSizes.get(0),
                "Style B title matches style A and metadata is smaller");
            var slimProgress = shapes.get(1);
            for (int i=0;i<texts.size();i++) {
                float textWidth = texts.get(i).length()*titleSizes.get(i)/renderScale[0]*.58f;
                check(Math.abs(textXs.get(i)+textWidth/2-slimProgress.getX()-slimProgress.getWidth()/2)<.01f,
                    "Both style B text rows center over the progress track");
            }

            layout.reset();
            check(layout.getValue()==0, "Resetting the layout restores style C");
            for(int i=0;i<100;i++)draw.run();
            var full = shell.get(0);
            check(Math.abs(full.getWidth()-slim.getWidth())<.01f && Math.abs(full.getHeight()-slim.getHeight())<.01f,
                "Style C matches style B's width and height");
            check(Math.abs((full.getX()+full.getWidth()/2)*renderScale[0]-960f)<.05f
                && Math.abs(radii.get(0)-10f)<.001f,"Style C is centered with the reference card radius");
            check(texts.equals(List.of("Blue Moon", "Extra artist / Afterglow")),
                "Style C displays a title and an artist/album line: "+texts);
            check(fonts.get(0).equals(Theme.FONT_SEMIBOLD) && Math.abs(titleSizes.get(0)-expectedTitleSize)<.001f
                && titleSizes.get(1)<titleSizes.get(0), "Style C title matches style A and metadata is smaller");
            var cover = shapes.get(0);
            check(textXs.stream().allMatch(x -> Math.abs(x-cover.getRight()-12f)<.01f),
                "Both text rows left align 12 px after the cover");
            var progress = shapes.get(1);
            check(cover.getWidth()==cover.getHeight() && cover.getBottom()<progress.getY()
                && Math.abs(progress.getWidth()-(full.getWidth()-24f))<.01f
                && Math.abs(progress.getX()-cover.getX())<.01f,
                "Style C retains a square cover and separate track with equal side insets");
            MusicPlayer.INSTANCE.acceptAppleState(track("6",longTitle),0,true);
            for(int i=0;i<90;i++)draw.run();
            check(Math.abs(shell.get(0).getWidth()-slim.getWidth())<.05f,"Long titles keep the full island width");
            width[0]=70;
            for(int i=0;i<90;i++)draw.run();
            check(shell.get(0).getWidth()<=174.01f,"The full island fits a narrow window");
            width[0]=1280;
        }
        System.out.println("Island styles A, B and default C passed resizing, clipping and song-change checks");
    }
}
