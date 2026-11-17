import me.sorihiko.dangomusicgtnh.music.AppleWebServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;

public class GameBridgeCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static JsonObject post(AppleWebServer s, String path, String key, String json, int expected) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(s.origin()+path).openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Origin","chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");
        c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        check(c.getResponseCode()==expected,"Unexpected HTTP status: "+c.getResponseCode());
        if(expected!=200){c.disconnect();return new JsonObject();}
        JsonObject result=new JsonParser().parse(new java.io.InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)).getAsJsonObject();c.disconnect();return result;
    }
    static void expireCatalogMatch(AppleWebServer server) throws Exception {
        java.lang.reflect.Field pending=AppleWebServer.class.getDeclaredField("pendingLoad");
        pending.setAccessible(true);
        Object load=pending.get(server);
        java.lang.reflect.Field since=load.getClass().getDeclaredField("mismatchSince");
        since.setAccessible(true);
        since.setLong(load,System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(8));
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders","true");
        AppleWebServer s=new AppleWebServer();
        try {
            String code=s.pairingLink().split("#")[1];
            post(s,"/pair","wrong","{}",403);
            String key=post(s,"/pair",code,"{}",200).get("session").getAsString();
            post(s,"/pair",code,"{}",403);
            String state="{\"id\":\"123\",\"title\":\"Song\",\"artist\":\"Singer\",\"available\":true,\"playing\":true,\"positionMs\":92000,\"durationMs\":240000,\"cookie\":\"secret\"}";
            post(s,"/sync",key,"{\"ack\":0,\"state\":"+state+"}",200);
            check(s.snapshot().get("positionMs").getAsLong()==92000,"Actual clock");
            check(!s.snapshot().has("cookie"),"Credentials excluded");
            s.command("stop",null);
            JsonObject stop=post(s,"/sync",key,"{\"ack\":0,\"state\":"+state+"}",200);
            long ack=stop.getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            check(s.snapshot()==null,"Stop cannot be revived by late telemetry");
            s.command("play",null);
            JsonObject play=post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            ack=play.getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200);
            check(s.snapshot()!=null,"Explicit play resumes");
            long beforeSeek = s.snapshot().get("receivedAtNanos").getAsLong();
            s.command("seek", 91.9);
            JsonObject seek = post(s, "/sync", key, "{\"ack\":" + ack + ",\"state\":" + state + "}", 200).getAsJsonObject("command");
            check(s.snapshot().get("seekSequence").getAsLong() == 0, "A pending seek must not mark the old snapshot");
            ack = seek.get("id").getAsLong();
            post(s, "/sync", key, "{\"ack\":" + ack + ",\"state\":" + state.replace("92000", "91900") + "}", 200);
            check(s.snapshot().get("seekSequence").getAsLong() == ack, "A confirmed seek marks the next clock sample");
            check(s.snapshot().get("receivedAtNanos").getAsLong() > beforeSeek, "Only new accepted samples update receipt time");
            final long beforeFavorite=ack;
            java.util.concurrent.FutureTask<JsonObject> favorite=new java.util.concurrent.FutureTask<>(()->s.request("favoriteAdd","123",0));
            Thread favoriteWorker=new Thread(favorite,"Favorite request test");
            favoriteWorker.start();
            JsonObject command=null;
            for(int i=0;i<40&&command==null;i++){
                JsonElement pending=post(s,"/sync",key,"{\"ack\":"+beforeFavorite+"}",200).get("command");
                if(pending.isJsonObject())command=pending.getAsJsonObject();
                else Thread.sleep(25);
            }
            check(command!=null&&command.get("name").getAsString().equals("query"),"Favorite write is queued");
            s.playSong("456",false);
            check(post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command").equals(command),
                "Changing songs must preserve the pending favorite write");
            ack=command.get("id").getAsLong();
            JsonObject next=post(s,"/sync",key,"{\"ack\":"+ack+",\"commandResult\":{\"id\":"+ack+",\"favorite\":true}}",200);
            check(favorite.get(2,java.util.concurrent.TimeUnit.SECONDS).get("favorite").getAsBoolean(),"The confirmed favorite reaches its caller");
            check(next.getAsJsonObject("command").get("name").getAsString().equals("load"),"The newly selected song still loads");
            s.reconnect();
            JsonObject paired=post(s,"/pair",s.pairingLink().split("#")[1],"{}",200);
            key=paired.get("session").getAsString();
            ack=paired.get("ack").getAsLong();
            for(int i=0;i<30;i++)s.command("volume",i/30.0);
            s.command("next",null);
            JsonObject control=post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command");
            check(control.get("name").getAsString().equals("next"),"Unsent drag updates must not delay or drop Next");
            ack=control.get("id").getAsLong();
            control=post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command");
            check(control.get("name").getAsString().equals("volume")&&control.get("value").getAsDouble()==29/30.0,
                "Only the final drag volume is dispatched");
            s.command("volume",0.2);
            s.command("volume",0.4);
            s.playSong("456",false);
            long volumeAck=control.get("id").getAsLong();
            control=post(s,"/sync",key,"{\"ack\":"+volumeAck+"}",200).getAsJsonObject("command");
            check(control.get("name").getAsString().equals("load"),"Load is sent before pending volume");
            ack=control.get("id").getAsLong();
            control=post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+state+"}",200).getAsJsonObject("command");
            check(s.snapshot()==null,"Previous song telemetry is rejected after the new load is acknowledged");
            check(control.get("name").getAsString().equals("volume")&&control.get("value").getAsDouble()==0.4,
                "The latest volume survives the load and supersedes the in-flight value");
            String updated=state.replace("123","456");
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+updated+"}",200);
            check(s.snapshot()!=null&&s.snapshot().get("id").getAsString().equals("456"),
                "The current track publishes telemetry while a volume update is awaiting acknowledgement");
            long ackBeforeRedirect=control.get("id").getAsLong();
            s.playSong("789",false);
            JsonObject load=post(s,"/sync",key,"{\"ack\":"+ackBeforeRedirect+"}",200).getAsJsonObject("command");
            ack=load.get("id").getAsLong();
            String replacement=state.replace("123","790");
            String redirect="{\"ack\":"+ack+",\"state\":"+replacement+",\"loadResult\":{\"id\":"+ack+",\"resolvedId\":\"790\"}}";
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+"}",200);
            check(s.snapshot()==null,"Unconfirmed replacement stays guarded");
            post(s,"/sync",key,redirect,200);
            check(s.snapshot()!=null&&s.snapshot().get("id").getAsString().equals("790"),"Confirmed replacement is accepted immediately");
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+"}",200);
            check(s.snapshot().get("requestedCatalogId").getAsString().equals("789"),
                "The replacement retains the requested queue identity on later telemetry");

            s.playSong("789",false);
            ack=post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+",\"loadResult\":{\"id\":"+ack+",\"resolvedId\":\"790\"}}",200);
            check(s.snapshot()!=null&&s.snapshot().get("requestedCatalogId").getAsString().equals("789"),
                "Repeating the same requested song accepts its already confirmed replacement");

            long priorLoad=ack;
            s.playSong("900",false);
            ack=post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+",\"loadResult\":{\"id\":"+priorLoad+",\"error\":\"old error\"}}",200);
            check(s.snapshot()==null&&s.error().isEmpty(),"Earlier load errors do not affect the current load");
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+",\"loadResult\":{\"id\":"+ack+",\"error\":\"queue failed\"}}",200);
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+"}",200);
            check(s.snapshot()==null&&s.error().equals("queue failed"),"A failed load rejects old telemetry and exposes its error");

            s.playSong("901",false);
            ack=post(s,"/sync",key,"{\"ack\":"+ack+"}",200).getAsJsonObject("command").get("id").getAsLong();
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+",\"loadResult\":{\"id\":"+ack+",\"resolvedId\":\"790\"}}",200);
            expireCatalogMatch(s);
            post(s,"/sync",key,"{\"ack\":"+ack+",\"state\":"+replacement+"}",200);
            check(s.snapshot()==null&&!s.error().isEmpty(),"The previous song cannot be accepted as a replacement");
            s.reconnect();post(s,"/sync",key,"{\"ack\":0}",403);
            System.out.println("Game bridge protocol checks passed");
        } finally {s.close();}
    }
}
