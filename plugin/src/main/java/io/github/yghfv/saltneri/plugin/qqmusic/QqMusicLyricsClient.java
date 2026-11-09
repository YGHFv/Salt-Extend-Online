package io.github.yghfv.saltneri.plugin.qqmusic;

/* Official QRC-first/fallback request mapping from mygo-qyin (AGPL-3.0).
 * Requests stay attached to the captured account and original song MID. */

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

public final class QqMusicLyricsClient {
    private final QqMusicHttp http;
    private final AccountCredentials account;
    public QqMusicLyricsClient(AccountCredentials account,QqMusicHttp http) {this.account=account;this.http=http;}
    public String lyrics(QqMusicTarget target,RequestScope request) throws IOException {
        String original="",translation="";
        boolean needFallback=false;
        try {
            var data=http.call("music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",
                    Map.of("songMID",target.mid(),"crypt",1,"ct",24,"cv",0,"lver",0,"qrc",1,"trans",1,"roma",0),account,request);
            original=payload(data.get("lyric"),request);
            try {translation=payload(data.get("trans"),request);}
            catch(IOException damagedTranslation) {request.check();}
            String text;
            try {text=QqMusicLyricsText.convert(original,translation);}
            catch(IOException badTranslation) {translation="";text=QqMusicLyricsText.convert(original,"");}
            if(text!=null) {
                if(translation.isBlank()) {
                    translation=ordinaryTranslation(target,request);
                    try {text=QqMusicLyricsText.convert(original,translation);}
                    catch(IOException invalidTranslation) {request.check();}
                }
                return text;
            }
            needFallback=true;
        } catch(QqMusicHttp.Rejected denied) {throw denied;}
        catch(IOException malformed) {request.check();needFallback=true;}
        if(needFallback) {
            var data=http.call("music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",
                    Map.of("songMID",target.mid(),"crypt",0,"qrc",0,"trans",1),account,request);
            try {original=payload(data.get("lyric"),request);}
            catch(IOException invalid) {original="";}
            try {translation=payload(data.get("trans"),request);}
            catch(IOException invalid) {translation="";}
            String result;
            try {result=QqMusicLyricsText.convert(original,translation);}
            catch(IOException invalidTranslation) {translation="";result=QqMusicLyricsText.convert(original,"");}
            if(result!=null)return result;
        }
        var uri=URI.create("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="+target.mid()+"&format=json&nobase64=0");
        var root=http.exchange(uri,null,account==null?"":account.header(uri),request,524288).json();
        long code=number(root.get("code"),-1);if(code!=0)throw new Rejected(code);
        original=payload(root.get("lyric"),request);
        String legacyTranslation="";
        try {legacyTranslation=payload(root.get("trans"),request);} catch(IOException invalid) {request.check();}
        try {return QqMusicLyricsText.convert(original,legacyTranslation.isBlank()?translation:legacyTranslation);}
        catch(IOException invalidTranslation) {return QqMusicLyricsText.convert(original,"");}
    }
    private String ordinaryTranslation(QqMusicTarget target,RequestScope request) throws IOException {
        try {
            var data=http.call("music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",
                    Map.of("songMID",target.mid(),"crypt",0,"qrc",0,"trans",1),account,request);
            return payload(data.get("trans"),request);
        } catch(IOException malformed) {request.check();return "";}
    }
    private static String payload(Object value,RequestScope request) throws IOException {
        if(!(value instanceof String text) || text.isEmpty())return "";
        if(text.length()>QqMusicQrcDecoder.MAX_BYTES*2)throw new IOException("QQ lyric exceeds limit");
        String stripped=text.stripLeading();
        if(stripped.startsWith("[") || stripped.startsWith("<"))return text;
        if(text.matches("[A-Fa-f0-9]+") && (text.length()&15)==0)return QqMusicQrcDecoder.decode(text,request);
        try {
            byte[] bytes=Base64.getDecoder().decode(text);
            if(bytes.length>QqMusicQrcDecoder.MAX_BYTES)throw new IOException("QQ lyric exceeds limit");
            return QqMusicQrcDecoder.utf8(bytes);
        } catch(IllegalArgumentException malformed) {throw new IOException("Invalid QQ lyric encoding");}
    }
}
