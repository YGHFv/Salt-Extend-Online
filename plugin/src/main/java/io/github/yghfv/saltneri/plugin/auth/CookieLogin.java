package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Explicit pasted Cookie header only; never reads a browser, file or global cookie jar. */
public final class CookieLogin {
    public static final int MAX_LENGTH=8192;
    private static final Set<String> NET_NAMES=Set.of("MUSIC_U","MUSIC_A","__csrf","NMTID","__remember_me",
            "os","osver","appver","deviceId","WNMCID","WEVNSM");
    private CookieLogin() {}
    public static List<ScopedCookies.Entry> parse(AccountPlatform platform,String input,Clock clock) throws IOException {
        if(platform!=AccountPlatform.NETEASE && platform!=AccountPlatform.QQMUSIC)throw new IllegalArgumentException("Cookie login unavailable");
        var stage=platform==AccountPlatform.NETEASE?AuthFailure.Stage.NET_COOKIE:AuthFailure.Stage.QQ_COOKIE;
        if(input==null || input.length()>MAX_LENGTH || input.chars().anyMatch(c->c<32 || c>126))throw AuthFailure.of(stage,AuthFailure.Reason.COOKIE);
        String text=input.strip();
        if(text.regionMatches(true,0,"Cookie:",0,7))text=text.substring(7).strip();
        var values=new LinkedHashMap<String,String>();
        for(String part:text.split(";",-1)) {
            if(part.isBlank())continue;
            int split=part.indexOf('=');
            if(split<=0 || values.size()>=64)throw AuthFailure.of(stage,AuthFailure.Reason.COOKIE);
            String name=part.substring(0,split).strip(),value=part.substring(split+1).strip();
            if(!name.matches("[!#$%&'*+.^_`|~a-zA-Z0-9-]{1,128}") || value.length()>4096
                    || value.chars().anyMatch(c->c<33 || c>126 || c==',' || c=='"' || c=='\\')
                    || values.putIfAbsent(name,value)!=null)throw AuthFailure.of(stage,AuthFailure.Reason.COOKIE);
        }
        if(platform==AccountPlatform.NETEASE) {
            if(values.getOrDefault("MUSIC_U","").isBlank())throw AuthFailure.of(stage,AuthFailure.Reason.CREDENTIAL);
            var jar=new ScopedCookies(platform,clock);
            try {
                for(var entry:values.entrySet()) if(NET_NAMES.contains(entry.getKey()) && !entry.getValue().isEmpty())
                    jar.accept(URI.create("https://music.163.com/"),List.of(entry.getKey()+"="+entry.getValue()+"; Domain=music.163.com; Path=/; Secure; Max-Age=2592000"));
                return jar.snapshot();
            } catch(IOException invalid) {throw AuthFailure.of(stage,AuthFailure.Reason.COOKIE);}
            finally {jar.clear();}
        }
        // MusicOne supports authst and API loginType as well as official web cookie aliases.
        if(!values.containsKey("qqmusic_key") && !values.containsKey("qm_keyst") && !values.containsKey("musickey")
                && !values.getOrDefault("authst","").isEmpty())values.put("qqmusic_key",values.get("authst"));
        String apiType=values.getOrDefault("loginType","");
        if(!apiType.isEmpty()) {
            if(!Set.of("1","2").contains(apiType) || values.containsKey("tmeLoginType") && !apiType.equals(values.get("tmeLoginType")))
                throw AuthFailure.of(stage,AuthFailure.Reason.CREDENTIAL);
            values.put("tmeLoginType",apiType);
        }
        if(!values.containsKey("tmeLoginType") && !values.containsKey("login_type")) {
            String key=List.of("qqmusic_key","qm_keyst","musickey").stream().map(values::get).filter(Objects::nonNull).findFirst().orElse("");
            values.put("tmeLoginType",key.startsWith("W_X")?"1":"2");
        }
        var rows=new ArrayList<Object>();
        for(var entry:values.entrySet()) if(QqMusicWebCookies.NAMES.contains(entry.getKey()) && !entry.getValue().isEmpty())
            rows.add(Map.of("name",entry.getKey(),"value",entry.getValue(),"domain","y.qq.com","path","/","session",true));
        AccountCredentials raw=null;
        try {
            raw=QqMusicWebCookies.decode(BoundedJson.encode(Map.of("cookies",rows)),clock);
            return raw.cookieSnapshot();
        } catch(IOException invalid) {
            throw invalid instanceof AuthFailure safe?safe.atStage(stage):AuthFailure.of(stage,AuthFailure.Reason.COOKIE);
        } finally {if(raw!=null)raw.retire();}
    }
}
