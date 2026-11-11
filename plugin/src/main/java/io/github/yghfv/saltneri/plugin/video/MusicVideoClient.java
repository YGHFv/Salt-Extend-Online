package io.github.yghfv.saltneri.plugin.video;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.bilibili.*;
import io.github.yghfv.saltneri.plugin.library.OnlinePlaybackController.Target;
import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.qqmusic.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.net.*;
import java.time.*;
import java.util.*;

/** Exact song-to-MV mapping. Discovery never requests a video CDN or searches for a different song. */
public final class MusicVideoClient {
    public record Source(Target track,String videoId) {
        @Override public String toString() {return "MusicVideoSource["+track.platform()+"]";}
    }
    @FunctionalInterface public interface Opener {HttpURLConnection open(URI uri,byte[] body) throws IOException;}
    private final AccountCredentials account;
    private final Clock clock;
    private final Opener opener;
    private final QqMusicHttp qq;
    public MusicVideoClient(AccountCredentials account) {this(account,Clock.systemUTC(),MusicVideoClient::open);}
    public MusicVideoClient(AccountCredentials account,Clock clock,Opener opener) {
        this.account=account;this.clock=clock;this.opener=opener;qq=new QqMusicHttp(opener::open);
    }
    private static HttpURLConnection open(URI uri,byte[] body) throws IOException {
        if(body==null)return new PinnedHttpsConnection(uri);
        if(QqMusicHttp.GATEWAY.equals(uri))return PinnedHttpsConnection.qqMusicPost(uri,body);
        return PinnedHttpsConnection.neteaseVideoPost(uri,body);
    }
    public Source discover(Target track,RequestScope request) throws IOException {
        check(request);
        if(account!=null && account.platform()!=track.platform())throw new IOException("Wrong video account");
        return switch(track.platform()) {
            case BILIBILI -> new Source(track,track.id());
            case NETEASE -> {
                if(!track.id().matches("[1-9][0-9]{0,18}"))throw invalid();
                var root=json(URI.create("https://music.163.com/api/song/detail?ids=%5B"+track.id()+"%5D"),null,request);
                var songs=BoundedJson.array(root.get("songs"));
                if(songs.size()!=1)throw invalid();
                var song=BoundedJson.object(songs.getFirst());
                if(BoundedJson.integer(song.get("id"))!=Long.parseLong(track.id()))throw invalid();
                long mv=BoundedJson.integer(song.getOrDefault("mv",song.getOrDefault("mvid",0)));
                yield mv>0?new Source(track,Long.toString(mv)):null;
            }
            case QQMUSIC -> {
                var song=new QqMusicClient(account,clock,opener::open).song(new QqMusicTarget(track.id()),request);
                Object mv=song.raw().get("mv");
                String vid=mv instanceof Map<?,?>?QqMusicHttp.text(BoundedJson.object(mv),"vid"):"";
                yield vid.matches("[A-Za-z0-9]{8,32}")?new Source(track,vid):null;
            }
            default -> null;
        };
    }
    public MediaLease resolve(Source source,String scope,RequestScope request) throws IOException {
        check(request);
        if(account!=null && account.platform()!=source.track().platform())throw new IOException("Wrong video account");
        if(source.track().platform()==AccountPlatform.BILIBILI)
            return new BiliClient(account).resolveVideo(BiliTarget.parse(source.track().id()),scope,request).lease();
        List<URI> urls=new ArrayList<>();
        if(source.track().platform()==AccountPlatform.NETEASE) {
            var root=json(URI.create("https://music.163.com/weapi/song/enhance/play/mv/url"),
                    NeteaseWeapi.form(Map.of("id",source.videoId(),"r",1080,"csrf_token","")),request);
            var data=BoundedJson.object(root.get("data"));
            if(BoundedJson.integer(data.get("id"))!=Long.parseLong(source.videoId())
                    || BoundedJson.integer(data.getOrDefault("code",200))!=200
                    || flag(data.get("is_preview")) || flag(data.get("is_trial")))throw invalid();
            urls.add(media(source.track().platform(),data.get("url")));
        } else if(source.track().platform()==AccountPlatform.QQMUSIC) {
            var data=qq.call("music.stream.MvUrlProxy","GetMvUrls",Map.of(
                    "vids",List.of(source.videoId()),"request_type",10003,"guid",UUID.randomUUID().toString(),
                    "videoformat",1,"format",264,"dolby",0,"use_new_domain",1,"use_ipv6",0),account,request);
            var video=BoundedJson.object(data.get(source.videoId()));
            var mp4=BoundedJson.array(video.get("mp4"));if(mp4.size()>32)throw invalid();
            // Only server-authorized MP4 entries. HLS playlists need a different scoped transport.
            var permitted=new ArrayList<Map<String,Object>>();
            for(Object item:mp4) {
                var row=BoundedJson.object(item);
                if(QqMusicHttp.number(row.get("code"),-1)==0 && !flag(row.get("is_preview")) && !flag(row.get("is_trial")))permitted.add(row);
            }
            permitted.sort(Comparator.comparingLong(row->{
                try{return -QqMusicHttp.number(row.get("fileSize"),0);}catch(IOException e){return 0L;}
            }));
            for(var row:permitted) {
                var candidates=BoundedJson.array(row.get("url"));if(candidates.size()>8)throw invalid();
                for(Object value:candidates)urls.add(media(source.track().platform(),value));
            }
        } else throw invalid();
        var platform=MediaLease.Platform.valueOf(source.track().platform().name());
        Map<String,String> headers=Map.of("User-Agent",QqMusicHttp.AGENT,"Referer",
                platform==MediaLease.Platform.NETEASE?"https://music.163.com/":"https://y.qq.com/");
        for(URI url:urls) {
            check(request);
            try {
                var probe=MediaRangeProbe.probe(platform,url,headers,0,clock.instant().plusSeconds(1200),
                        clock,uri->opener.open(uri,null),request,()->check(request));
                return new MediaLease(platform,probe.uri(),headers,scope,probe.expiresAt(),probe.length());
            } catch(MediaRangeProbe.Rejected denied) {throw denied;}
            catch(IOException unavailable) {check(request);}
        }
        throw new IOException("No permitted MV stream");
    }
    private Map<String,Object> json(URI uri,byte[] body,RequestScope request) throws IOException {
        check(request);var connection=opener.open(uri,body);request.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            connection.setRequestMethod(body==null?"GET":"POST");
            connection.setRequestProperty("User-Agent",QqMusicHttp.AGENT);
            connection.setRequestProperty("Referer","https://music.163.com/");
            connection.setRequestProperty("Accept-Encoding","identity");
            if(account!=null)connection.setRequestProperty("Cookie",account.header(uri));
            if(connection.getResponseCode()!=200)throw invalid();
            var root=JsonResponse.read(connection,request,true);
            if(BoundedJson.integer(root.get("code"))!=200)throw invalid();
            check(request);return root;
        } finally {request.release(connection);}
    }
    private static URI media(AccountPlatform platform,Object value) throws IOException {
        try {
            String text=BoundedJson.string(value);if(text.length()>8192)throw invalid();
            var uri=URI.create(text.replaceFirst("^http:","https:"));
            MediaLease.requireTarget(MediaLease.Platform.valueOf(platform.name()),uri);return uri;
        } catch(IllegalArgumentException rejected) {throw invalid();}
    }
    private static boolean flag(Object value) throws IOException {
        if(value==null)return false;
        return value instanceof Boolean b?b:BoundedJson.integer(value)!=0;
    }
    private void check(RequestScope request) {
        request.check();if(account!=null && !account.isActive())throw new java.util.concurrent.CancellationException();
    }
    private static IOException invalid() {return new IOException("Invalid MV response");}
}
