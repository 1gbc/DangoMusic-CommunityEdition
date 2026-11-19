import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import me.sorihiko.dangomusicgtnh.DangoMusic;
import me.sorihiko.dangomusicgtnh.music.*;
import me.sorihiko.dangomusicgtnh.musicui.*;
import me.sorihiko.dangomusicgtnh.platform.Platform;

public class PlaylistCacheCheck {
    static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        var config=Files.createTempDirectory(Path.of(args[0]),"playlist-cache-");
        var platform=(Platform)Proxy.newProxyInstance(Platform.class.getClassLoader(),new Class[]{Platform.class},
            (p,m,a)->m.getName().equals("getConfigDir")?config:null);
        DangoMusic.start(platform,List.of());
        MusicSources.INSTANCE.setSelected(MusicSource.NETEASE);
        field(Library.class,"source").set(null,MusicSource.NETEASE);
        var first=new NeteaseTrack(1,"First",List.of("Artist"),60000,"");
        var second=new NeteaseTrack(2,"Second",List.of("Artist"),60000,"");
        var liked=new NeteasePlaylist(10,"Liked",1,"",true,true,"");
        var custom=new NeteasePlaylist(11,"Mine",1,"",true);
        var readonly=new NeteasePlaylist(12,"Subscribed",1,"",false);
        var cloud=new NeteasePlaylist(NeteaseApi.CLOUD_ID,"Cloud",0,"",false);
        field(Library.class,"playlists").set(null,List.of(liked,custom,readonly,cloud));
        var cache=(Map<Long,List<NeteaseTrack>>)field(Library.class,"tracksCache").get(null);
        cache.put(10L,List.of(first));cache.put(11L,List.of(first));
        var change=Library.class.getDeclaredMethod("recordTrackChange",long.class,NeteaseTrack.class,boolean.class);change.setAccessible(true);
        var replace=Library.class.getDeclaredMethod("replaceTracks",long.class,List.class,boolean.class);replace.setAccessible(true);
        change.invoke(Library.INSTANCE,10L,second,true);
        check(Library.INSTANCE.tracks(10).get(0)==second,"Successful likes immediately update cached favorites");
        replace.invoke(Library.INSTANCE,10L,List.of(first),false);
        check(Library.INSTANCE.tracks(10).contains(second),"An older page cannot undo a confirmed like");
        replace.invoke(Library.INSTANCE,10L,List.of(second,first),true);
        change.invoke(Library.INSTANCE,10L,second,false);
        replace.invoke(Library.INSTANCE,10L,List.of(second,first),false);
        check(!Library.INSTANCE.tracks(10).contains(second),"An older page cannot restore an unliked song");
        change.invoke(Library.INSTANCE,11L,second,true);
        check(Library.INSTANCE.tracks(11).equals(List.of(first,second)),"Ordinary playlist additions preserve append order");
        check(Library.INSTANCE.userPlaylists().size()==2,"Only writable user playlists are offered");
        cache.put(NeteaseApi.DAILY_ID,List.of(first));
        Library.INSTANCE.loadTracks(NeteaseApi.DAILY_ID,true);
        for(int i=0;i<200 && Library.INSTANCE.loadingTracks(NeteaseApi.DAILY_ID);i++)Thread.sleep(5);
        check(!Library.INSTANCE.loadingTracks(NeteaseApi.DAILY_ID) && Library.INSTANCE.tracks(NeteaseApi.DAILY_ID).isEmpty(),
            "A forced retry must replace an existing partial cache, including an empty server result");
        MusicSources.INSTANCE.setSelected(MusicSource.APPLE);
        field(Library.class,"source").set(null,MusicSource.APPLE);
        var appleFavorites=AppleMusicSource.INSTANCE.favoritesEntry();
        field(Library.class,"playlists").set(null,List.of(appleFavorites));
        var catalog=new NeteaseTrack(-123,"Catalog song",List.of("Artist"),60000,"",MusicSource.APPLE,"123","123","");
        var library=new NeteaseTrack(-456,"Library song",List.of("Artist"),60000,"",MusicSource.APPLE,"i.abc","123","");
        cache.put(appleFavorites.getId(),List.of(library));
        change.invoke(Library.INSTANCE,appleFavorites.getId(),catalog,false);
        check(Library.INSTANCE.tracks(appleFavorites.getId()).isEmpty(),"Unfavoriting a catalog song removes its library representation");
        change.invoke(Library.INSTANCE,appleFavorites.getId(),catalog,true);
        replace.invoke(Library.INSTANCE,appleFavorites.getId(),List.of(library),true);
        check(Library.INSTANCE.tracks(appleFavorites.getId()).size()==1 && Library.INSTANCE.tracks(appleFavorites.getId()).get(0)==library,
            "Catalog and library IDs for one recording cannot create duplicate favorites");
        var requestMethod=Library.class.getDeclaredMethod("request");requestMethod.setAccessible(true);
        Object request=requestMethod.invoke(Library.INSTANCE);
        var generation=field(Library.class,"generation");generation.setInt(null,generation.getInt(null)+1);
        var publish=Library.class.getDeclaredMethod("publish",request.getClass(),Function0.class);publish.setAccessible(true);
        boolean[] applied={false};
        publish.invoke(Library.INSTANCE,request,(Function0<Unit>)()->{applied[0]=true;return Unit.INSTANCE;});
        check(!applied[0],"Replies from a previous source generation must be ignored");
        System.out.println("Favorites add/remove, stale pages, append order, writable filtering and source generation passed.");
    }
}
