package io.github.yghfv.saltneri.plugin.kugou;

import io.github.yghfv.saltneri.core.TrackKey;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.host.Salt1193PlaybackSession.Metadata;
import io.github.yghfv.saltneri.plugin.library.CoverSource;
import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.transport.MediaLease;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** Resolve the requested hash and full-track size before entering Salt's existing range transport. */
public final class KugouClient {
    public record Song(String hash,String title,String artist,String album,long duration,long size,URI cover) {}
    public record Resolved(TrackKey key,Metadata metadata,URI coverCandidate,long durationMillis,MediaLease lease,Instant expiresAt) {}
    private final AccountCredentials account;
    private final Clock clock;
    private final KugouHttp http;
    private final KugouHttp.Opener opener;
    public KugouClient(AccountCredentials account) {this(account,Clock.systemUTC(),PinnedHttpsConnection::kugouMedia);}
    public KugouClient(AccountCredentials account,Clock clock,KugouHttp.Opener opener) {
        this.account=account;this.clock=clock;this.opener=opener;this.http=new KugouHttp(opener);
    }
    public Song songInfo(String id,RequestScope request) throws IOException {
        String hash=KugouHttp.hash(id);
        var rows=KugouHttp.rows(http.get("/privilege/lite",Map.of("hash",hash),account,request),"data","list");
        var raw=rows.stream().filter(v->v instanceof Map<?,?> m && hash.equalsIgnoreCase(String.valueOf(m.get("hash"))))
                .findFirst().orElseThrow(()->KugouHttp.failure("HASH_MISMATCH"));
        return parseSong(object(raw));
    }
    public Resolved resolve(String id,String quality,String scope,RequestScope request) throws IOException {
        String hash=KugouHttp.hash(id);
        if(scope==null || scope.isBlank() || account!=null && !scope.equals(account.accountScope()))throw KugouHttp.failure("SCOPE");
        if(!Set.of("128","320","flac").contains(quality))quality="128";
        var rows=KugouHttp.rows(http.get("/privilege/lite",Map.of("hash",hash),account,request),"data","list");
        var base=rows.stream().filter(v->v instanceof Map<?,?> m && hash.equalsIgnoreCase(String.valueOf(m.get("hash")))).findFirst().orElseThrow(()->KugouHttp.failure("HASH_MISMATCH"));
        var raw=object(base);var song=parseSong(raw);
        var selected=raw;
        String effectiveQuality=text(raw,"quality");
        if(!Set.of("128","320","flac").contains(effectiveQuality))effectiveQuality="128";
        if(!quality.equals(effectiveQuality))for(Object value:array(raw.get("relate_goods"))) {
            var candidate=object(value);
            if(quality.equals(text(candidate,"quality"))) {selected=candidate;effectiveQuality=quality;break;}
        }
        String selectedHash=KugouHttp.hash(text(selected,"hash"));
        var selectedMetadata=new LinkedHashMap<String,Object>(raw);
        if(selected!=raw)for(String field:List.of("FileSize","size","timelen","Duration","duration","info"))selectedMetadata.remove(field);
        selectedMetadata.putAll(selected);
        var selectedSong=parseSong(selectedMetadata);
        if(selectedSong.size()<=0 || selectedSong.duration()<song.duration()-1000)throw KugouHttp.failure("FULL_TRACK_METADATA");
        var parameters=new LinkedHashMap<String,Object>(Map.of("hash",hash,"quality",effectiveQuality,"free_part",false));
        // Numeric album audio IDs are required by the upstream; a hash is never substituted here.
        for(String field:List.of("album_id","album_audio_id")) {
            Object value=raw.get(field);
            if(value!=null && String.valueOf(value).matches("[0-9]{1,19}"))parameters.put(field,String.valueOf(value));
        }
        var data=object(http.get("/song/url",parameters,account,request));
        if(!selectedHash.equalsIgnoreCase(text(data,"hash")))throw KugouHttp.failure("MEDIA_HASH_MISMATCH");
        Object freePart=data.get("free_part");
        if(Boolean.TRUE.equals(data.get("is_free_part")) || Boolean.TRUE.equals(freePart) || !(freePart instanceof Boolean) && number(freePart,0)!=0
                || number(data.get("priv_status"),0)<0)throw KugouHttp.failure("TRACK_UNAVAILABLE");
        var urls=data.get("url") instanceof String s?List.of(s):array(data.get("url"));
        if(urls.isEmpty())throw KugouHttp.failure("TRACK_UNAVAILABLE");
        var candidates=new LinkedHashSet<URI>();
        for(Object value:urls)try {
            URI candidate=URI.create(String.valueOf(value));
            MediaLease.requireTarget(MediaLease.Platform.KUGOU,candidate);
            // Reject preview resources even when hosted on the legitimate media CDN.
            if(candidate.getPath().matches("(?i).*\\b(preview|clip|trial)\\b.*"))continue;
            candidates.add(candidate);
            if(candidates.size()==6)break;
        } catch(IllegalArgumentException rejected) {}
        if(candidates.isEmpty())throw KugouHttp.failure("MEDIA_TARGET");
        var headers=Map.of("User-Agent",AGENT,"Referer","https://www.kugou.com/");
        Instant expiry=clock.instant().plusSeconds(600);
        MediaRangeProbe.Result probe=null;IOException last=null;
        // Probe original API candidates, preserving their protocol. Never publish an unreachable first edge.
        for(URI candidate:candidates) {
            request.check();
            try {
                probe=MediaRangeProbe.probe(MediaLease.Platform.KUGOU,candidate,headers,selectedSong.size(),expiry,clock,opener::open,request,
                    ()->{request.check();if(account!=null && !account.isActive())throw new java.util.concurrent.CancellationException();});
                break;
            } catch(IOException failure) {request.check();last=failure;}
        }
        if(probe==null)throw KugouHttp.failure(last instanceof javax.net.ssl.SSLException?"MEDIA_TLS":
                last instanceof java.net.SocketTimeoutException?"MEDIA_TIMEOUT":
                last instanceof java.net.UnknownHostException?"MEDIA_DNS":"MEDIA_UNAVAILABLE");
        var lease=new MediaLease(MediaLease.Platform.KUGOU,probe.uri(),headers,scope,probe.expiresAt(),probe.length());
        if(account!=null)lease=lease.bindTo(scope,account::isActive);
        return new Resolved(TrackKey.kugou(hash),new Metadata(song.title(),song.artist(),song.album(),song.artist()),song.cover(),song.duration(),lease,probe.expiresAt());
    }
    public static Song parseSong(Map<String,Object> raw) throws IOException {
        var info=raw.get("info") instanceof Map<?,?>?object(raw.get("info")):Map.<String,Object>of();
        var album=raw.get("albuminfo") instanceof Map<?,?>?object(raw.get("albuminfo")):Map.<String,Object>of();
        String hash=KugouHttp.hash(text(raw,"hash","FileHash","Hash"));
        String title=text(raw,"OriSongName","audio_name","name","FileName","filename");
        String artist=text(raw,"SingerName","singername","author_name");
        for(String field:List.of("singerinfo","Singers","authors"))if(raw.get(field) instanceof List<?> singers) {
            var names=new ArrayList<String>();
            for(Object singer:singers) {String name=text(object(singer),"name","author_name");if(!name.isEmpty())names.add(name);}
            if(!names.isEmpty())artist=String.join(" / ",names);
            break;
        }
        if(artist.isEmpty() && title.contains(" - ")) {artist=title.substring(0,title.indexOf(" - "));title=title.substring(title.indexOf(" - ")+3);}
        else if(!artist.isEmpty() && title.startsWith(artist+" - "))title=title.substring(artist.length()+3);
        long duration=number(raw.get("timelen"),number(info.get("duration"),0));
        if(duration<=0)duration=number(raw.getOrDefault("Duration",raw.get("duration")),0)*1000;
        if(title.isBlank() || duration<=0 || duration>86400000)throw KugouHttp.failure("SONG_METADATA");
        long size=number(raw.getOrDefault("FileSize",raw.get("size")),number(info.get("filesize"),0));
        String cover=text(raw,"Image","cover","imgurl");if(cover.isEmpty())cover=text(info,"image");
        return new Song(hash,title,artist,text(raw,"AlbumName","albumname","album_name").isEmpty()?text(album,"name"):text(raw,"AlbumName","albumname","album_name"),
                duration,size,CoverSource.parse(AccountPlatform.KUGOU,cover.replace("{size}","400")));
    }
    public String lyrics(String id,RequestScope request) throws IOException {
        String hash=KugouHttp.hash(id);
        var data=object(http.get("/search/lyric",Map.of("hash",hash),account,request));
        var candidates=array(data.get("candidates"));if(candidates.isEmpty())return null;
        if(candidates.size()>100)throw KugouHttp.failure("LYRIC_BOUND");
        var candidate=object(candidates.getFirst());
        String lyricId=text(candidate,"id"),access=text(candidate,"accesskey");
        if(!lyricId.matches("[0-9]{1,19}") || !access.matches("[A-Za-z0-9]{1,128}"))throw KugouHttp.failure("LYRIC_ID");
        var result=object(http.get("/lyric",Map.of("id",lyricId,"accesskey",access,"fmt","krc","decode",true),account,request));
        String original=result.get("decodedContent") instanceof String s?s:"";
        String translation=result.get("decodedTranslation") instanceof String s?s:"";
        long adjust=number(candidate.get("adjust"),0);
        return krc(original,translation,adjust);
    }
    /** KRC word offsets are relative to the line; reuse the established QRC serializer. */
    public static String krc(String original,String translation,long adjust) throws IOException {
        if(original.length()>1048576 || Math.abs(adjust)>86400000)throw KugouHttp.failure("LYRIC_BOUND");
        Pattern linePattern=Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})](.*)$");
        Pattern words=Pattern.compile("<(\\d{1,9}),(\\d{1,9}),\\d+>([^<]*)");
        var qrc=new StringBuilder();long offset=adjust;
        for(String line:original.replace("\r","").split("\n"))if(line.matches("\\[offset:[+-]?\\d{1,9}]"))
            offset+=Long.parseLong(line.substring(8,line.length()-1));
        qrc.append("[offset:").append(offset).append("]\n");
        for(String line:original.replace("\r","").split("\n")) {
            var match=linePattern.matcher(line);if(!match.matches())continue;
            long start=Long.parseLong(match.group(1));
            qrc.append('[').append(start).append(',').append(match.group(2)).append(']');
            var word=words.matcher(match.group(3));
            while(word.find())qrc.append(word.group(3)).append('(').append(start+Long.parseLong(word.group(1))).append(',').append(word.group(2)).append(')');
            qrc.append('\n');
        }
        return io.github.yghfv.saltneri.plugin.qqmusic.QqMusicLyricsText.convert(qrc.toString(),translation);
    }
}
