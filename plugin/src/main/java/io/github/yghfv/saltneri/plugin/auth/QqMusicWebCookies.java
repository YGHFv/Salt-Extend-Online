package io.github.yghfv.saltneri.plugin.auth;

/* Official web cookie aliases from pinned NeriPlayer (GPL-3.0-or-later).
 * Observe parent QQ cookies without forwarding unrelated QQ login credentials. */

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.io.IOException;
import java.math.*;
import java.time.*;
import java.util.*;

final class QqMusicWebCookies {
    static final Set<String> NAMES=Set.of("qqmusic_key","qm_keyst","musickey","qqmusic_uin","uin","wxuin",
            "tmeLoginType","login_type","euin","qqmusic_privilege","psrf_musickey_createtime",
            "psrf_access_token_expiresAt","psrf_qqopenid","psrf_qqunionid","psrf_qqaccess_token",
            "wxopenid","wxunionid");
    private record Observed(String name,String value,String domain,Instant expiry) { }
    private QqMusicWebCookies() { }
    static AccountCredentials decode(byte[] output,Clock clock) throws IOException {
        try {return read(output,clock);}
        catch(IOException failure) {throw AuthFailure.wrap(AuthFailure.Stage.QQ_WEB,AuthFailure.Reason.COOKIE,failure);}
    }
    private static AccountCredentials read(byte[] output,Clock clock) throws IOException {
        if(output.length>65536)throw failure(AuthFailure.Reason.RESPONSE);
        var root=BoundedJson.object(BoundedJson.parse(output));
        if(root.get("error")!=null)throw failure(AuthFailure.webHelperReason(root.get("error")));
        var rows=BoundedJson.array(root.get("cookies"));
        if(rows.size()>256)throw failure(AuthFailure.Reason.COOKIE);
        var observations=new ArrayList<Observed>();var seen=new HashSet<String>();
        for(Object row:rows) {
            var c=BoundedJson.object(row);
            if(!(c.get("name") instanceof String name) || !NAMES.contains(name))continue;
            String domain=BoundedJson.string(c.get("domain")).replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
            if(!domain.equals("qq.com") && !domain.equals("music.qq.com") && !AccountPlatform.QQMUSIC.allows(domain))continue;
            String path=BoundedJson.string(c.get("path"));
            if(!path.equals("/"))continue;
            if(!seen.add(name+"\n"+domain+"\n"+path))throw failure(AuthFailure.Reason.COOKIE);
            String value=BoundedJson.string(c.get("value"));
            if(value.isEmpty())continue;
            if(!value.matches("[\\x21-\\x7e]{1,4096}") || value.matches(".*[;,\"\\\\].*"))throw failure(AuthFailure.Reason.COOKIE);
            long seconds=30*86400L;
            if(!Boolean.TRUE.equals(c.get("session"))) {
                if(!(c.get("expires") instanceof BigDecimal expiry))throw failure(AuthFailure.Reason.COOKIE);
                try {seconds=Math.min(seconds,Math.subtractExact(expiry.setScale(0,RoundingMode.FLOOR).longValueExact(),clock.instant().getEpochSecond()));}
                catch(ArithmeticException invalid) {throw failure(AuthFailure.Reason.COOKIE);}
                if(seconds<=0)continue;
            }
            observations.add(new Observed(name,value,domain,clock.instant().plusSeconds(seconds)));
        }
        // Prefer music-owned aliases over generic parent uin; do not import p_skey/skey.
        observations.sort(Comparator.comparingInt(c->c.domain.equals("y.qq.com")?0:
                AccountPlatform.QQMUSIC.allows(c.domain)?1:c.domain.equals("music.qq.com")?2:3));
        var selected=new LinkedHashMap<String,Observed>();
        observations.forEach(c->selected.putIfAbsent(c.name,c));
        Observed key=first(selected,List.of("qqmusic_key","qm_keyst","musickey"));
        if(key==null)throw failure(AuthFailure.Reason.CREDENTIAL);
        for(String alias:List.of("qqmusic_key","qm_keyst","musickey")) {
            var other=selected.get(alias);
            if(other!=null && other.domain.equals(key.domain) && !other.value.equals(key.value))
                throw failure(AuthFailure.Reason.CREDENTIAL);
        }
        Observed family=selected.get("tmeLoginType");
        String type;
        if(family!=null)type=family.value;
        else {
            family=selected.get("login_type");
            type=family==null?"":apiType(family.value);
        }
        if(!Set.of("1","2").contains(type))throw failure(AuthFailure.Reason.CREDENTIAL);
        var webType=selected.get("login_type");
        if(webType!=null && !type.equals(apiType(webType.value)))throw failure(AuthFailure.Reason.CREDENTIAL);
        Observed identity=null;String id="";
        for(String name:type.equals("1")?List.of("qqmusic_uin","wxuin","uin"):List.of("qqmusic_uin","uin")) {
            var candidate=selected.get(name);if(candidate==null)continue;
            id=QqMusicLoginResponse.identity(candidate.value.replaceFirst("^o0*",""));
            if(!id.isEmpty()) {identity=candidate;break;}
        }
        if(identity==null)throw failure(AuthFailure.Reason.IDENTITY);
        if(type.equals("1") && selected.containsKey("wxuin") && selected.containsKey("qqmusic_uin")
                && !id.equals(QqMusicLoginResponse.identity(selected.get("wxuin").value.replaceFirst("^o0*",""))))
            throw failure(AuthFailure.Reason.IDENTITY);
        Instant expiry=key.expiry.isBefore(identity.expiry)?key.expiry:identity.expiry;
        if(family!=null && family.expiry.isBefore(expiry))expiry=family.expiry;
        var tokenExpiry=selected.get("psrf_access_token_expiresAt");
        if(tokenExpiry!=null) {
            try {
                long seconds=Long.parseLong(tokenExpiry.value);
                if(seconds>0) {
                    var until=Instant.ofEpochSecond(seconds);
                    if(!until.isAfter(clock.instant()))throw failure(AuthFailure.Reason.CREDENTIAL);
                    if(until.isBefore(expiry))expiry=until;
                }
            } catch(NumberFormatException | DateTimeException unavailable) { }
        }
        var entries=new ArrayList<ScopedCookies.Entry>();
        var canonical=new LinkedHashMap<String,String>();
        canonical.put("qqmusic_key",key.value);canonical.put("qm_keyst",key.value);canonical.put("musickey",key.value);
        canonical.put("qqmusic_uin",id);canonical.put("uin",id);canonical.put("tmeLoginType",type);
        canonical.put("login_type",type.equals("1")?"2":"1");
        if(type.equals("1"))canonical.put("wxuin",id);
        for(var c:selected.values())if(!Set.of("musickey","login_type","wxuin").contains(c.name))
            canonical.putIfAbsent(c.name,c.value);
        for(var c:canonical.entrySet())entries.add(new ScopedCookies.Entry(c.getKey(),c.getValue(),"y.qq.com","/",false,true,expiry));
        String agent=root.containsKey("userAgent")?BoundedJson.string(root.get("userAgent")):AuthHttp.DEFAULT_USER_AGENT;
        return new AccountCredentials(AccountPlatform.QQMUSIC,id,entries,clock,new AccountCredentials.Profile("",null),agent);
    }
    private static String apiType(String webType) {return switch(webType) {case "1" -> "2";case "2" -> "1";default -> "";};}
    private static Observed first(Map<String,Observed> entries,List<String> names) {
        for(String name:names)if(entries.containsKey(name))return entries.get(name);return null;
    }
    private static AuthFailure failure(AuthFailure.Reason reason) {return AuthFailure.of(AuthFailure.Stage.QQ_WEB,reason);}
}
