package me.sorihiko.dangomusicgtnh.music;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Local transport only. Never receives Apple credentials or audio URLs. */
public final class AppleWebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Dango-AppleBridge");t.setDaemon(true);return t;});
    private String pairing=random(),session,owner;
    private long born=System.nanoTime(),lastSync,sequence,lastAck;
    private boolean stopped;
    private JsonObject state;
    private boolean authorized;
    private String authState="restoring";
    private static final class PendingQuery {
        final String operation;
        final CompletableFuture<JsonObject> future;
        PendingQuery(String operation,CompletableFuture<JsonObject> future){
            this.operation=operation;
            this.future=future;
        }
    }
    private final Map<Long,PendingQuery> requests=new HashMap<>();
    private final ArrayDeque<JsonObject> commands=new ArrayDeque<>();
    private static final boolean TRACE_TIMING=Boolean.getBoolean("dangomusic.apple.timing");
    private Double pendingVolume;
    private long pendingVolumeAt,loadQueuedAt;
    private long observedCommand;
    private static long millisSince(long nanos){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-nanos);}
    private static void trace(String operation,String phase,long ms){
        if(TRACE_TIMING)System.out.printf("[DangoMusic Apple timing] %s %s %d ms%n",operation,phase,ms);
    }
    private void enqueue(JsonObject command,long at){
        if(TRACE_TIMING){
            command.addProperty("queuedNanos",at);
            command.addProperty("trace",true);
        }
        commands.add(command);
    }
    private static final long CATALOG_MATCH_TIMEOUT_MS=7000;
    private static final class PendingLoad {
        final long id,queuedAt=System.nanoTime();
        final String catalogId,previousId;
        String resolvedId="";
        long mismatchSince;
        PendingLoad(long id,String catalogId,String previousId){
            this.id=id;
            this.catalogId=catalogId;
            this.previousId=previousId;
        }
    }
    private PendingLoad pendingLoad;
    private String lastTrackId="";
    private volatile String loadFailure="";
    private void clearLoad(){
        pendingLoad=null;
        loadQueuedAt=0;
        loadFailure="";
    }
    private void failLoad(String message){
        pendingLoad=null;
        loadQueuedAt=0;
        state=null;
        stopped=true;
        loadFailure=message;
    }
    private void updateLoadResult(JsonObject report){
        PendingLoad load=pendingLoad;
        if(load==null)return;
        if(elapsed(load.queuedAt)>=65000){
            failLoad("Apple Music load timed out; try playing the song again");
            return;
        }
        if(!report.has("loadResult")||!report.get("loadResult").isJsonObject()){
            String failure=text(report,"commandError",300);
            if(load.id==lastAck&&(failure.startsWith("Apple Music queue failed")||failure.startsWith("Apple Music play failed")||
                failure.contains("[MEDIA_TIMEOUT]")))failLoad(failure);
            return;
        }
        JsonObject result=report.getAsJsonObject("loadResult");
        if(!Long.toString(load.id).equals(text(result,"id",24))||load.id>lastAck)return;
        String failure=text(result,"error",300);
        if(!failure.isEmpty()){
            failLoad(failure);
            return;
        }
        load.resolvedId=text(result,"resolvedId",100);
        if(load.mismatchSince==0)load.mismatchSince=System.nanoTime();
    }
    private boolean acceptLoadState(JsonObject incoming){
        PendingLoad load=pendingLoad;
        if(load==null)return true;
        String id=text(incoming,"id",100);
        boolean available=!id.isEmpty()&&!text(incoming,"title",300).isEmpty()&&
            incoming.has("available")&&incoming.get("available").getAsBoolean();
        if(available&&(load.catalogId.isEmpty()||load.catalogId.equals(id)||
            (id.equals(load.resolvedId)&&!id.equals(load.previousId))))return true;
        if(load.mismatchSince==0&&available)load.mismatchSince=System.nanoTime();
        if(load.mismatchSince==0||elapsed(load.mismatchSince)<CATALOG_MATCH_TIMEOUT_MS)return false;
        failLoad("Apple Music did not confirm the requested song; try playing it again");
        return false;
    }
    private volatile String error="";
    public AppleWebServer() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.setExecutor(worker);server.createContext("/",this::handle);server.start();
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format("%02x",x&255));return b.toString();}
    private static long elapsed(long t){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);}
    public String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public synchronized String pairingLink(){return origin()+"/#"+pairing;}
    public synchronized boolean connected(){return session!=null&&elapsed(lastSync)<10000;}
    public synchronized JsonObject snapshot(){return connected()&&!stopped&&state!=null?new JsonParser().parse(state.toString()).getAsJsonObject():null;}
    public String error(){String failure=loadFailure;return failure.isEmpty()?error:failure;}
    public synchronized boolean authorized(){return connected()&&authorized;}
    public synchronized String authState(){return connected()?authState:"disconnected";}
    private void cancelRequests(){for(PendingQuery query:requests.values())query.future.completeExceptionally(new IOException("Apple Music request cancelled"));requests.clear();}
    private void clearPlaybackCommands(){
        // A favorite write belongs to the account even if the user changes songs.
        for(Iterator<JsonObject> it=commands.iterator();it.hasNext();){
            JsonObject command=it.next();
            long id=command.get("id").getAsLong();
            if("query".equals(command.get("name").getAsString())&&requests.containsKey(id)){
                String operation=command.getAsJsonObject("value").get("op").getAsString();
                if("favoriteAdd".equals(operation)||"favoriteRemove".equals(operation))continue;
            }
            if ("volume".equals(command.get("name").getAsString()) && pendingVolume==null) {
                pendingVolume=command.get("value").getAsDouble();
                pendingVolumeAt=command.has("queuedNanos")?command.get("queuedNanos").getAsLong():System.nanoTime();
            }
            it.remove();
            PendingQuery request=requests.remove(id);
            if(request!=null)request.future.completeExceptionally(new IOException("Apple Music request cancelled"));
        }
    }
    public synchronized void reconnect(){cancelRequests();session=null;owner=null;state=null;commands.clear();pendingVolume=null;clearLoad();lastTrackId="";authorized=false;authState="restoring";stopped=false;pairing=random();born=System.nanoTime();lastAck=sequence;error="";}
    public JsonObject request(String op,String value,int offset)throws Exception{
        if(!Arrays.asList("search","charts","songs","playlists","playlistTracks","recent","recommend","resourceTracks",
            "librarySearch","discover","genres","genreCharts","radio","liveRadio","webDiscover","webRadio","recentlyAdded","libraryAlbums","favoriteSongs",
            "favoriteState","favoriteAdd","favoriteRemove","heavyRotation","songDetails").contains(op)||value==null||value.length()>200||offset<0||offset>10000)throw new IllegalArgumentException("Invalid browse request");
        CompletableFuture<JsonObject> future=new CompletableFuture<>();long id;
        synchronized(this){
            if(!connected())throw new IOException("Apple player disconnected");
            if(commands.size()>=16)throw new IOException("Apple controls busy");
            JsonObject c=new JsonObject(),v=new JsonObject();id=++sequence;c.addProperty("id",id);c.addProperty("name","query");v.addProperty("op",op);v.addProperty(op.equals("search")||op.equals("librarySearch")?"term":"id",value);v.addProperty("offset",offset);c.add("value",v);commands.add(c);requests.put(id,new PendingQuery(op,future));
        }
        try{return future.get("songDetails".equals(op)?8:20,TimeUnit.SECONDS);}
        finally{synchronized(this){
            requests.remove(id);
            if("songDetails".equals(op))commands.removeIf(command->command.get("id").getAsLong()==id);
        }}
    }
    public synchronized void playSong(String id,boolean library){
        if(id==null||!id.matches("[A-Za-z0-9._:-]{1,100}"))throw new IllegalArgumentException("Invalid song ID");
        if(!connected())return;
        clearPlaybackCommands();stopped=false;state=null;error="";
        clearLoad();
        pendingLoad=new PendingLoad(++sequence,!library&&id.matches("[0-9]{1,20}")?id:"",lastTrackId);
        loadQueuedAt=TRACE_TIMING?System.nanoTime():0L;
        JsonObject c=new JsonObject(),v=new JsonObject();c.addProperty("id",sequence);c.addProperty("name","load");v.addProperty("id",id);v.addProperty("library",library);c.add("value",v);enqueue(c,loadQueuedAt);
    }
    public synchronized void command(String name,Double value){
        if(!Arrays.asList("play","pause","stop","next","previous","seek","volume","show","hide","login","shutdown").contains(name))throw new IllegalArgumentException("Unsupported control");
        if((name.equals("seek")||name.equals("volume"))&&(value==null||Double.isNaN(value)||Double.isInfinite(value)||value<0||value>(name.equals("volume")?1:86400)))throw new IllegalArgumentException("Invalid control value");
        if(!connected())return;
        if(name.equals("volume")){pendingVolume=value;pendingVolumeAt=System.nanoTime();return;}
        if(name.equals("stop")){stopped=true;state=null;clearLoad();clearPlaybackCommands();}
        if(name.equals("next")||name.equals("previous"))clearLoad();
        if(name.equals("play"))loadFailure="";
        if(commands.size()>=16){error="Apple Music controls are waiting for the browser";return;}
        if(Arrays.asList("play","next","previous").contains(name))stopped=false;
        JsonObject c=new JsonObject();c.addProperty("id",++sequence);c.addProperty("name",name);if(value!=null)c.addProperty("value",value);enqueue(c,System.nanoTime());
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            String origin=x.getRequestHeaders().getFirst("Origin");
            if(!("127.0.0.1:"+server.getAddress().getPort()).equals(x.getRequestHeaders().getFirst("Host"))||origin==null||!(origin.matches("chrome-extension://[a-p]{32}")||origin.equals("dango-native://player"))){reply(x,403,"{}");return;}
            String path=x.getRequestURI().toString(),auth=x.getRequestHeaders().getFirst("Authorization");
            if(!x.getRequestMethod().equals("POST")||!(path.equals("/pair")||path.equals("/sync"))){reply(x,405,"{}");return;}
            // Read outside the state lock so a stalled browser cannot freeze game controls.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;
            while((n=x.getRequestBody().read(buf))!=-1){if(bytes.size()+n>196608){reply(x,413,"{}");return;}bytes.write(buf,0,n);}
            synchronized(this){
                if(path.equals("/pair")){
                    if(session!=null||elapsed(born)>120000||!("Bearer "+pairing).equals(auth)){reply(x,403,"{}");return;}
                    session=random();owner=origin;pairing="";lastSync=System.nanoTime();JsonObject r=new JsonObject();r.addProperty("session",session);r.addProperty("ack",sequence);reply(x,200,r.toString());return;
                }
                if(!connected()||!origin.equals(owner)||!("Bearer "+session).equals(auth)){reply(x,403,"{}");return;}
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.startsWith("application/json")){reply(x,415,"{}");return;}
                JsonObject report=new JsonParser().parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getAsJsonObject();
                double a=report.get("ack").getAsDouble();if(a!=Math.floor(a)||a<lastAck||a>sequence){reply(x,400,"{}");return;}
                lastAck=(long)a;lastSync=System.nanoTime();
                while(!commands.isEmpty()&&commands.peek().get("id").getAsLong()<=lastAck){
                    JsonObject acknowledged=commands.remove();
                    if(acknowledged.has("queuedNanos")&&acknowledged.get("id").getAsLong()==lastAck)
                        trace(text(acknowledged,"name",24),"bridge-ack",millisSince(acknowledged.get("queuedNanos").getAsLong()));
                }
                if(TRACE_TIMING&&report.has("timing")&&report.get("timing").isJsonObject()){
                    JsonObject timing=report.getAsJsonObject("timing");
                    String operation=text(timing,"operation",24);
                    if(Arrays.asList("volume","next","previous","load","seek").contains(operation)){
                        for(String phase:Arrays.asList("webview-ms","sdk-ms","queue-ms","play-ms")){
                            if(!timing.has(phase)||!timing.get(phase).isJsonPrimitive())continue;
                            try{
                                long ms=timing.get(phase).getAsLong();
                                if(ms>=0&&ms<=120000)trace(operation,phase,ms);
                            }catch(NumberFormatException ignored){}
                        }
                    }
                }
                if(report.has("commandResult")&&report.get("commandResult").isJsonObject()){
                    JsonObject result=report.getAsJsonObject("commandResult");long id=result.get("id").getAsLong();
                    PendingQuery query=requests.get(id);
                    if(query!=null&&id==lastAck){JsonObject cleaned=new JsonObject();JsonArray items=new JsonArray();
                        if("songDetails".equals(query.operation)){
                            cleaned.addProperty("url",appleLink(result,"url"));
                            cleaned.addProperty("artist",text(result,"artist",300));
                            cleaned.addProperty("composer",text(result,"composer",300));
                        }
                        if(result.has("items")&&result.get("items").isJsonArray())for(JsonElement item:result.getAsJsonArray("items")){if(items.size()>=40)break;if(!item.isJsonObject())continue;JsonObject raw=item.getAsJsonObject(),row=sanitize(raw);row.addProperty("playId",text(raw,"playId",100));row.addProperty("genre",text(raw,"genre",100));for(String k:Arrays.asList("library","compilation","favoriteList"))row.addProperty(k,raw.has(k)&&raw.get(k).getAsBoolean());
                            if(raw.has("featuredCover"))row.addProperty("featuredCover",artwork(raw,"featuredCover"));
                            if(raw.has("releaseDate"))row.addProperty("releaseDate",text(raw,"releaseDate",10));
                            if(raw.has("sectionTitle"))row.addProperty("sectionTitle",text(raw,"sectionTitle",300));
                            if(raw.has("sectionIndex")){
                                int index=raw.get("sectionIndex").getAsInt();
                                if(index<0||index>=30)continue;
                                row.addProperty("sectionIndex",index);
                                for(String key:Arrays.asList("layout","heading"))row.addProperty(key,text(raw,key,300));
                                row.addProperty("link",appleLink(raw,"link"));
                            }
                            items.add(row);}
                        if(result.has("nextOffset")){int next=result.get("nextOffset").getAsInt();if(next>=0&&next<=10025)cleaned.addProperty("nextOffset",next);}
                        if(result.has("favorite")&&result.get("favorite").isJsonPrimitive()&&result.getAsJsonPrimitive("favorite").isBoolean())
                            cleaned.add("favorite",result.get("favorite"));
                        cleaned.add("items",items);cleaned.addProperty("hasMore",result.has("hasMore")&&result.get("hasMore").getAsBoolean());cleaned.addProperty("error",text(result,"error",300));query.future.complete(cleaned);
                    }
                }
                updateLoadResult(report);
                boolean changing=false;for(JsonObject c:commands)if(!Arrays.asList("query","show","hide","login","volume").contains(c.get("name").getAsString()))changing=true;
                if(report.has("state")&&report.get("state").isJsonObject()){
                    JsonObject incoming=report.getAsJsonObject("state");authorized=incoming.has("authorized")&&incoming.get("authorized").getAsBoolean();
                    String status=text(incoming,"authState",24);authState=Arrays.asList("ready","login","restoring","unavailable").contains(status)?status:(authorized?"ready":"restoring");
                    if(!stopped&&!changing&&acceptLoadState(incoming)){
                        String reportedId=text(incoming,"id",100);
                        String requestedId=pendingLoad!=null?pendingLoad.catalogId:
                            state!=null&&reportedId.equals(text(state,"id",100))?text(state,"requestedCatalogId",100):"";
                        state=sanitize(incoming);
                        if(!requestedId.isEmpty()&&!requestedId.equals(reportedId))
                            state.addProperty("requestedCatalogId",requestedId);
                        pendingLoad=null;
                        if(state.get("available").getAsBoolean()&&!reportedId.isEmpty())lastTrackId=reportedId;
                        if(loadQueuedAt!=0&&state.get("available").getAsBoolean()&&state.get("playing").getAsBoolean()&&!text(state,"id",100).isEmpty()){
                            trace("load","playing-reported",millisSince(loadQueuedAt));
                            loadQueuedAt=0;
                        }
                        if(incoming.has("stationQueue")&&incoming.get("stationQueue").isJsonArray()){
                            JsonArray queue=new JsonArray();
                            for(JsonElement item:incoming.getAsJsonArray("stationQueue")){
                                if(queue.size()>=40)break;
                                if(!item.isJsonObject())continue;
                                JsonObject row=item.getAsJsonObject();
                                if(!text(row,"id",101).matches("[A-Za-z0-9._:-]{1,100}")||text(row,"title",300).isEmpty())continue;
                                queue.add(sanitize(row));
                            }
                            state.add("stationQueue",queue);
                        }
                    }
                }
                error=text(report,"commandError",300);
                if(error.isEmpty()&&state!=null)error=text(state,"error",300);
                if(commands.isEmpty()&&pendingVolume!=null){
                    JsonObject volume=new JsonObject();
                    volume.addProperty("id",++sequence);
                    volume.addProperty("name","volume");
                    volume.addProperty("value",pendingVolume);
                    pendingVolume=null;
                    enqueue(volume,pendingVolumeAt);
                }
                JsonObject outgoing=commands.peek();
                if(outgoing!=null&&outgoing.has("queuedNanos")&&outgoing.get("id").getAsLong()!=observedCommand){
                    observedCommand=outgoing.get("id").getAsLong();
                    trace(text(outgoing,"name",24),"bridge-dispatch",millisSince(outgoing.get("queuedNanos").getAsLong()));
                }
                JsonObject r=new JsonObject();r.add("command",outgoing==null?JsonNull.INSTANCE:outgoing);reply(x,200,r.toString());
            }
        }catch(Exception e){try{reply(x,400,"{}");}catch(IOException ignored){}}
        finally{x.close();}
    }
    private static JsonObject sanitize(JsonObject source){
        JsonObject r=new JsonObject();for(String k:Arrays.asList("title","artist","album","id","kind","error"))r.addProperty(k,text(source,k,300));
        for(String k:Arrays.asList("positionMs","durationMs")){double v=source.has(k)?source.get(k).getAsDouble():0;r.addProperty(k,Double.isNaN(v)||Double.isInfinite(v)?0:Math.max(0,Math.min(v,86400000)));}
        for(String k:Arrays.asList("playing","available","authorized","ended"))r.addProperty(k,source.has(k)&&source.get(k).getAsBoolean());
        r.addProperty("cover",artwork(source,"cover"));return r;
    }
    private static String artwork(JsonObject source,String field){
        try {
            URI url=URI.create(text(source,field,2048));
            String host=url.getHost();
            if("https".equals(url.getScheme())&&host!=null&&(host.equals("mzstatic.com")||host.endsWith(".mzstatic.com"))&&url.getUserInfo()==null)return url.toString();
        }catch(IllegalArgumentException ignored){}
        return "";
    }
    private static String appleLink(JsonObject source,String field){
        try {
            String link=text(source,field,701);
            if(link.length()>700)return "";
            URI url=URI.create(link);
            if("https".equals(url.getScheme())&&"music.apple.com".equals(url.getHost())&&url.getUserInfo()==null&&
                (url.getPort()==-1||url.getPort()==443)&&url.getPath().matches("/[a-z]{2}/.+"))return url.toString();
        }catch(IllegalArgumentException ignored){}
        return "";
    }
    public static String text(JsonObject o,String k,int max){JsonElement e=o.get(k);if(e==null||!e.isJsonPrimitive())return "";String v=e.getAsString();return v.substring(0,Math.min(v.length(),max));}
    private static void reply(HttpExchange x,int code,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);}
    public synchronized void close(){cancelRequests();server.stop(0);worker.shutdownNow();session=null;state=null;commands.clear();}
}
