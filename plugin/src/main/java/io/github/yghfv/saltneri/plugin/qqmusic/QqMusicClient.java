package io.github.yghfv.saltneri.plugin.qqmusic;

/* Song/quality/vkey mapping adapted from NeriPlayer QQMusicClient (GPL-3.0-or-later).
 * Never asks for RS02 previews or retries a login/risk-control rejection. */

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.library.*;
import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

public final class QqMusicClient {
    public enum Quality {
        STANDARD("standard","M500","mp3",-1,false), HIGHER("higher","O600","ogg",-1,false),
        HIGH("exhigh","O800","ogg",-1,false), LOSSLESS("lossless","F000","flac",-1,false),
        HIRES("hires","Q000","flac",4,true), ATMOS51("atmos51","Q001","flac",-1,true),
        ATMOS71("atmos71","Q003","ogg",24,true), DOLBY("dolby","D004","mp4",-1,true),
        DTSX("dtsx","DT03","mp4",30,true), MASTER("master","AI00","flac",3,true);
        private final String key,prefix,extension;
        private final int version;
        private final boolean mediaOnly;
        Quality(String key,String prefix,String extension,int version,boolean mediaOnly) {this.key=key;this.prefix=prefix;this.extension=extension;this.version=version;this.mediaOnly=mediaOnly;}
        public String key() {return key;}
        public static Quality fromKey(String key) {for(var q:values())if(q.key.equals(key))return q;return STANDARD;}
        List<Quality> fallbacks() {
            return switch(this) {
                case STANDARD -> List.of(STANDARD);
                case HIGHER -> List.of(HIGHER,STANDARD);
                case HIGH -> List.of(HIGH,HIGHER,STANDARD);
                case LOSSLESS -> List.of(LOSSLESS,HIGH,HIGHER,STANDARD);
                case HIRES -> List.of(HIRES,LOSSLESS,HIGH,HIGHER,STANDARD);
                case MASTER -> List.of(MASTER,HIRES,ATMOS71,ATMOS51,DOLBY,DTSX,LOSSLESS,HIGH,HIGHER,STANDARD);
                default -> List.of(this,LOSSLESS,HIGH,HIGHER,STANDARD);
            };
        }
    }
    public static final class TrackUnavailable extends QqMusicHttp.Rejected {public TrackUnavailable(long code) {super(code);}}
    public static final class MediaRejected extends IOException {
        public enum Reason { PATH, AUTHORIZATION }
        private final Reason reason;
        public MediaRejected(Reason reason) {super("QQ_MEDIA/"+reason.name());this.reason=reason;}
        public Reason reason() {return reason;}
    }
    public record Song(String mid,String mediaMid,Metadata metadata,URI cover,long duration,Map<String,Object> raw) { }
    private record AudioFormat(String prefix,String extension,long length) { }
    public record Resolved(TrackKey key,Metadata metadata,URI coverCandidate,long durationMillis,String quality,MediaLease lease,Instant expiresAt) { }
    private final AccountCredentials account;
    private final Clock clock;
    private final QqMusicHttp http;
    private final QqMusicHttp.Opener opener;
    public QqMusicClient(AccountCredentials account) {this(account,Clock.systemUTC(),QqMusicHttp::openPinned);}
    public QqMusicClient(AccountCredentials account,Clock clock,QqMusicHttp.Opener opener) {
        if(account!=null && account.platform()!=AccountPlatform.QQMUSIC) throw new IllegalArgumentException("Wrong QQ account");
        this.account=account;this.clock=clock;this.opener=opener;this.http=new QqMusicHttp(opener);
    }
    public Song song(QqMusicTarget target,RequestScope request) throws IOException {
        var data=http.call("music.pf_song_detail_svr","get_song_detail_yqq",Map.of("song_type",0,"song_mid",target.mid(),"song_id",""),account,request);
        var song=parseSong(object(data.get("track_info")));
        if(!target.mid().equals(song.mid())) throw new IOException("QQ song identity mismatch");
        return song;
    }
    public Resolved resolve(QqMusicTarget target,Quality preferred,String scope,RequestScope request) throws IOException {
        if(scope==null || scope.isBlank() || account!=null && !scope.equals(account.accountScope())) throw new IOException("QQ account scope mismatch");
        if(account!=null) try(var verifier=new QqMusicQrClient(clock,http)) {var verified=verifier.verify(account,request);verified.retire();}
        Song song=song(target,request);String guid=Long.toString(1_000_000_000L+new java.security.SecureRandom().nextInt(1_000_000_000));
        MediaRangeProbe.Rejected missingMedia=null;
        for(Quality quality:preferred.fallbacks()) {
            request.check();
            String media=song.mediaMid();
            if(quality.version>=0) {
                var versions=array(song.raw().get("vs"));
                if(versions.size()<=quality.version || !(versions.get(quality.version) instanceof String m) || !m.matches("[A-Za-z0-9]{14}")) continue;
                media=m;
            }
            for(AudioFormat format:formats(song.raw(),quality)) {
                long expected=format.length();
                if(expected<=0) continue;
                if(expected>2L*1024*1024*1024)throw new IOException("QQ full-track size invalid");
                String filename=format.prefix()+(quality.mediaOnly?media:target.mid()+media)+"."+format.extension();
                var parameters=new LinkedHashMap<String,Object>(Map.of("filename",List.of(filename),"guid",guid,"songmid",List.of(target.mid()),
                        "songtype",List.of(0),"uin",account==null?"0":account.userId(),"loginflag",1,"platform","20"));
                if(account!=null)parameters.put("ctx",0);
                var data=http.call(account==null?"vkey.GetVkeyServer":"music.vkey.GetVkey",account==null?"CgiGetVkey":"UrlGetVkey",parameters,account,request);
                var entries=array(data.get("midurlinfo"));
                if(entries.size()!=1)throw new IOException("QQ vkey identity missing");
                var entry=object(entries.getFirst());
                if(!target.mid().equals(text(entry,"songmid")))throw new IOException("QQ vkey identity mismatch");
                long result=number(entry.get("result"),0);
                if(result!=0 && result!=104003) throw new Rejected(result);
                String purl=text(entry,"purl");
                if(purl.isEmpty() || result==104003)continue;
                validateMediaPath(purl,filename,format.prefix()+media+"."+format.extension(),entry);
                var candidates=new LinkedHashSet<URI>();
                for(Object domain:array(data.get("sip"))) {
                    if(!(domain instanceof String s))continue;
                    try {
                        URI base=URI.create(s.replaceFirst("^http:","https:"));
                        MediaLease.requireTarget(MediaLease.Platform.QQMUSIC,base);
                        if(base.getRawQuery()!=null || !"/".equals(base.getPath()))continue;
                        URI candidate=base.resolve(purl);MediaLease.requireTarget(MediaLease.Platform.QQMUSIC,candidate);candidates.add(candidate);
                    } catch(IllegalArgumentException ignored) { }
                }
                if(candidates.isEmpty())candidates.add(URI.create("https://dl.stream.qqmusic.qq.com/"+purl));
                var headers=Map.of("User-Agent",AGENT,"Referer","https://y.qq.com/");
                Instant expiry=clock.instant().plusSeconds(600);
                MediaRangeProbe.Result probe=null;
                for(URI candidate:candidates) {
                    try {
                        probe=MediaRangeProbe.probe(MediaLease.Platform.QQMUSIC,candidate,headers,expected,expiry,clock,
                                address->opener.open(address,null),request,()->{request.check();if(account!=null && !account.isActive())throw new java.util.concurrent.CancellationException();});
                        break;
                    } catch(MediaRangeProbe.Rejected rejected) {
                        // A valid vkey can point to an absent encoding. Try only the supplied
                        // mirrors, then the next official quality; authorization failures stop here.
                        if(rejected.status()!=404 && rejected.status()!=410)throw rejected;
                        missingMedia=rejected;request.check();
                    }
                }
                if(probe==null)continue;
                request.check();
                var lease=new MediaLease(MediaLease.Platform.QQMUSIC,probe.uri(),headers,scope,probe.expiresAt(),probe.length());
                if(account!=null)lease=lease.bindTo(scope,account::isActive);
                return new Resolved(TrackKey.qqmusic(target.mid()),song.metadata(),song.cover(),song.duration(),quality.key(),lease,probe.expiresAt());
            }
        }
        if(missingMedia!=null)throw missingMedia;
        throw new TrackUnavailable(104003);
    }
    private static List<AudioFormat> formats(Map<String,Object> raw,Quality quality) throws IOException {
        var primary=new AudioFormat(quality.prefix,quality.extension,expectedSize(raw,quality));
        if(quality!=Quality.HIGH)return List.of(primary);
        // HQ has two official encodings. Exhaust OGG and MP3 at the same tier before lowering quality.
        var file=raw.get("file") instanceof Map<?,?>?object(raw.get("file")):Map.<String,Object>of();
        long mp3=number(file.getOrDefault("size_320mp3",raw.get("size320")),0);
        return List.of(primary,new AudioFormat("M800","mp3",mp3));
    }
    private static void validateMediaPath(String purl,String requested,String mediaOnly,Map<String,Object> entry) throws IOException {
        // QQ can canonicalize song+media requests to a single media MID path.
        // Accept only those two exact identities, never arbitrary same-prefix files.
        if(purl.length()>8192 || !purl.matches("[A-Za-z0-9_.?=&%+/-]+"))
            throw new MediaRejected(MediaRejected.Reason.PATH);
        URI relative;
        try {relative=URI.create(purl);}
        catch(IllegalArgumentException invalid) {throw new MediaRejected(MediaRejected.Reason.PATH);}
        String path=relative.getRawPath(),query=relative.getRawQuery();
        if(relative.isAbsolute() || relative.getRawAuthority()!=null || relative.getRawFragment()!=null
                || !(requested.equals(path) || mediaOnly.equals(path)) || query==null)
            throw new MediaRejected(MediaRejected.Reason.PATH);
        String echoed=text(entry,"filename");
        if(!echoed.isEmpty() && !echoed.equals(requested))throw new MediaRejected(MediaRejected.Reason.PATH);
        String key=text(entry,"vkey");
        if(key.isEmpty())throw new MediaRejected(MediaRejected.Reason.AUTHORIZATION);
        int matches=0;
        for(String pair:query.split("&",-1)) {
            String[] parts=pair.split("=",2);
            if(parts[0].equals("vkey")) {
                if(parts.length!=2 || !parts[1].equals(key))throw new MediaRejected(MediaRejected.Reason.AUTHORIZATION);
                matches++;
            }
        }
        if(matches!=1)throw new MediaRejected(MediaRejected.Reason.AUTHORIZATION);
    }
    private static long expectedSize(Map<String,Object> raw,Quality q) throws IOException {
        var file=raw.get("file") instanceof Map<?,?>?object(raw.get("file")):Map.<String,Object>of();
        String key=switch(q) {case STANDARD -> "size_128mp3";case HIGHER -> "size_192ogg";case LOSSLESS -> "size_flac";case DOLBY -> "size_dolby";default -> "";};
        if(!key.isEmpty()) {
            String legacy=switch(q) {case STANDARD -> "size128";case LOSSLESS -> "sizeflac";default -> "";};
            return number(file.getOrDefault(key,raw.get(legacy)),0);
        }
        int index=switch(q) {case HIGH -> 3;case HIRES -> 1;case MASTER -> 0;case ATMOS51 -> 2;case ATMOS71 -> 6;case DTSX -> 9;default -> -1;};
        var sizes=array(file.get("size_new"));return index>=0 && sizes.size()>index?number(sizes.get(index),0):0;
    }
    public static Song parseSong(Map<String,Object> raw) throws IOException {
        for(String wrapper:List.of("musicData","songInfo","track_info")) if(raw.get(wrapper) instanceof Map<?,?>) {raw=object(raw.get(wrapper));break;}
        String mid=text(raw,"mid","songmid","songMid");QqMusicTarget.parse(mid);
        String title=text(raw,"title","name","songname","songName");if(title.isBlank() || title.length()>4096)throw new IOException("QQ title missing");
        var album=raw.get("album") instanceof Map<?,?>?object(raw.get("album")):Map.<String,Object>of();
        var file=raw.get("file") instanceof Map<?,?>?object(raw.get("file")):Map.<String,Object>of();
        String media=text(file,"media_mid");if(media.isBlank())media=text(raw,"strMediaMid","media_mid","mediaMid");if(media.isBlank())media=mid;
        QqMusicTarget.parse(media);
        List<String> artists=new ArrayList<>();
        var singers=array(raw.getOrDefault("singer",raw.get("singers")));if(singers.size()>64)throw new IOException("QQ singers too large");
        for(Object singer:singers)artists.add(text(object(singer),"name","singername","singer_name","singerName"));
        String artist=String.join(" / ",artists);
        String albumMid=text(album,"mid","albummid","albumMid","pmid");if(albumMid.isEmpty())albumMid=text(raw,"albummid","album_mid");
        URI cover=albumMid.matches("[A-Za-z0-9]{14}")?CoverSource.parse(AccountPlatform.QQMUSIC,"https://y.qq.com/music/photo_new/T002R800x800M000"+albumMid+".jpg"):null;
        // Albumless songs have a song-picture MID at vs[1], also used by QQ's official song page.
        // This is a picture identity, not the audio media MID or an arbitrary same-title cover.
        if(cover==null && raw.get("vs") instanceof List<?> versions && versions.size()>1
                && versions.get(1) instanceof String picture && picture.matches("[A-Za-z0-9]{14}"))
            cover=CoverSource.parse(AccountPlatform.QQMUSIC,"https://y.qq.com/music/photo_new/T062R800x800M000"+picture+".jpg");
        long seconds=number(raw.getOrDefault("interval",raw.get("duration")),0);
        if(seconds<=0 || seconds>86400)throw new IOException("QQ duration invalid");
        return new Song(mid,media,new Metadata(title,artist,text(album,"name","title","albumname"),artist),cover,seconds*1000,raw);
    }
    public String lyrics(QqMusicTarget target,RequestScope request) throws IOException {
        return new QqMusicLyricsClient(account,http).lyrics(target,request);
    }
}
