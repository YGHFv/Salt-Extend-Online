package io.github.yghfv.saltneri.plugin.video;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;

/** Identity and capability fixtures, with no platform requests or video decoder. */
public final class MusicVideoContractTest {
    private static int checks;
    private static void check(boolean condition) {checks++;if(!condition)throw new AssertionError("MV check "+checks);}
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void rejects(Action action) throws Exception {
        try {action.run();throw new AssertionError("MV rejection expected");}
        catch(IOException expected) {checks++;}
    }
    private static class Response extends HttpURLConnection {
        final byte[] bytes;final boolean media;
        Response(URI uri,String body,boolean media) throws IOException {super(uri.toURL());this.media=media;bytes=media?new byte[]{42}:body.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy(){return false;}
        public int getResponseCode(){return media?206:200;}
        public String getHeaderField(String name){return switch(name){case "Content-Range"->media?"bytes 0-0/4096":null;case "Content-Type"->"application/json";case "Content-Length"->Integer.toString(bytes.length);default->null;};}
        public long getContentLengthLong(){return bytes.length;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
    }
    public static void main(String[] args) throws Exception {
        var calls=new ArrayList<URI>();var payload=new String[]{"{\"code\":200,\"songs\":[{\"id\":123,\"mv\":456}]}"};
        var client=new MusicVideoClient(null,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC),(uri,body)->{
            calls.add(uri);
            return new Response(uri,uri.getPath().equals("/api/song/detail")?payload[0]
                    :"{\"code\":200,\"data\":{\"id\":456,\"code\":200,\"url\":\"https://m7.music.126.net/video.mp4?token=PRIVATE\"}}",
                    uri.getHost().endsWith(".music.126.net"));
        });
        try(var request=new RequestScope(()->true)) {
            var target=new Target(AccountPlatform.NETEASE,"123");
            var source=client.discover(target,request);
            check(source.videoId().equals("456") && source.track().equals(target));
            check(calls.size()==1);
            var lease=client.resolve(source,"fixture-video",request);
            check(lease.length()==4096 && lease.belongsTo("fixture-video"));
            check(!source.toString().contains("PRIVATE") && !lease.toString().contains("PRIVATE"));
            payload[0]="{\"code\":200,\"songs\":[{\"id\":123,\"mv\":0}]}";
            check(client.discover(target,request)==null);
            payload[0]="{\"code\":200,\"songs\":[{\"id\":124,\"mv\":456}]}";
            try {client.discover(target,request);throw new AssertionError("Cross-song MV");}catch(IOException expected){checks++;}
            check(client.discover(new Target(AccountPlatform.APPLEMUSIC,"123"),request)==null);
        }
        qqFixtures();
        System.out.println("PASS: "+checks+" MV mapping, on-demand stream and identity checks");
    }
    private static void qqFixtures() throws Exception {
        String mid="00123456789abc",vid="a0123456789";
        var requests=new ArrayList<URI>();
        var response=new HashMap<String,Object>();
        response.put("mp4",List.of(Map.of("code",0,"fileSize",4096,"url",List.of("https://dl.stream.qqmusic.qq.com/video.mp4"))));
        var client=new MusicVideoClient(null,Clock.systemUTC(),(uri,body)->{
            requests.add(uri);
            if(body==null)return new Response(uri,"",true);
            var req=BoundedJson.object(BoundedJson.object(BoundedJson.parse(body)).get("req_0"));
            Object data;
            if(req.get("method").equals("get_song_detail_yqq")) {
                var param=BoundedJson.object(req.get("param"));check(param.get("song_mid").equals(mid));
                data=Map.of("track_info",Map.of("mid",mid,"title","Fixture","interval",120,"mv",Map.of("vid",vid)));
            } else {
                check(req.get("module").equals("music.stream.MvUrlProxy") && req.get("method").equals("GetMvUrls"));
                var param=BoundedJson.object(req.get("param"));
                check(param.get("vids").equals(List.of(vid)) && BoundedJson.integer(param.get("format"))==264);
                data=Map.of(vid,response);
            }
            return new Response(uri,new String(QqMusicHttp.jsonBytes(Map.of("code",0,"req_0",Map.of("code",0,"data",data))),
                    java.nio.charset.StandardCharsets.UTF_8),false);
        });
        try(var request=new RequestScope(()->true)) {
            var source=client.discover(new Target(AccountPlatform.QQMUSIC,mid),request);
            check(source.videoId().equals(vid) && requests.size()==1);
            check(client.resolve(source,"qq-video",request).length()==4096 && requests.size()==3);
            response.put("mp4",List.of(Map.of("code",0,"is_trial",1,"url",List.of("https://dl.stream.qqmusic.qq.com/trial.mp4"))));
            rejects(()->client.resolve(source,"qq-video",request));
            response.put("mp4",List.of(Map.of("code",403,"url",List.of("https://dl.stream.qqmusic.qq.com/denied.mp4"))));
            rejects(()->client.resolve(source,"qq-video",request));
            response.put("mp4",List.of(Map.of("code",0,"url",List.of("https://foreign.invalid/video.mp4"))));
            rejects(()->client.resolve(source,"qq-video",request));
            check(requests.stream().filter(u->!u.equals(QqMusicHttp.GATEWAY)).count()==1);
        }
        try(var request=new RequestScope(()->false)) {
            rejects(()->client.discover(new Target(AccountPlatform.QQMUSIC,mid),request));
        } catch(java.util.concurrent.CancellationException expected) {checks++;}
    }
}
