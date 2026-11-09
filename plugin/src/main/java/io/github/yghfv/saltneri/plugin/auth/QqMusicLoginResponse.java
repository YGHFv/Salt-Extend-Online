package io.github.yghfv.saltneri.plugin.auth;

/* Optional metadata/profile handling follows mygo-qyin (AGPL-3.0).
 * Credential identity/family/status remain mandatory and never reach diagnostics. */

import io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

public final class QqMusicLoginResponse {
    private QqMusicLoginResponse() { }
    public static AccountCredentials decode(QqMusicHttp.Response response,Clock clock) throws IOException {
        var root=response.json();
        var envelope=object(root.get("req"));
        long global=number(root.get("code"),-1),business=number(envelope.get("code"),-1);
        if(global!=0 || business!=0)throw AuthFailure.code(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.API,global!=0?global:business);
        var data=object(envelope.get("data"));
        String id=identity(data.get("musicid"));
        if(id.isEmpty())id=identity(data.get("str_musicid"));
        if(id.isEmpty())throw AuthFailure.of(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.IDENTITY);
        if(number(data.get("loginType"),-1)!=2)throw AuthFailure.of(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.CREDENTIAL);
        String key=secret(data.get("musickey"));
        if(key.isEmpty())throw AuthFailure.of(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.CREDENTIAL);
        long duration=optionalNumber(data.get("keyExpiresIn")),created=optionalNumber(data.get("musickeyCreateTime"));
        Instant expiry=clock.instant().plusSeconds(duration>0?Math.min(duration,2592000):2592000);
        if(created>0 && duration>0)try {
            expiry=expiry.isBefore(Instant.ofEpochSecond(Math.addExact(created,duration)))?expiry:Instant.ofEpochSecond(Math.addExact(created,duration));
        } catch(ArithmeticException | DateTimeException invalid) {throw AuthFailure.of(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.CREDENTIAL);}
        if(!expiry.isAfter(clock.instant()))throw AuthFailure.of(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.CREDENTIAL);
        var values=new LinkedHashMap<>(Map.of("uin",id,"qqmusic_uin",id,"qqmusic_key",key,"qm_keyst",key,
                "musickey",key,"login_type","1","tmeLoginType","2"));
        String euin=secret(data.get("encryptUin"));if(!euin.isEmpty())values.put("euin",euin);
        Instant until=expiry;
        var entries=values.entrySet().stream().map(e->new ScopedCookies.Entry(e.getKey(),e.getValue(),
                "y.qq.com","/",false,true,until)).toList();
        return new AccountCredentials(AccountPlatform.QQMUSIC,id,entries,clock,
                AccountCredentials.Profile.from(AccountPlatform.QQMUSIC,text(data,"nick","nickname"),text(data,"logo","headurl","avatarUrl")));
    }
    static String identity(Object value) throws IOException {
        String id=value instanceof String text?text.strip():value instanceof Number?Long.toString(number(value,0)):"";
        if(!id.matches("[1-9][0-9]{0,18}"))return "";
        try {Long.parseLong(id);return id;} catch(NumberFormatException bad) {return "";}
    }
    private static long optionalNumber(Object value) {
        try {return Math.max(0,number(value,0));} catch(IOException invalid) {return 0;}
    }
    private static String secret(Object value) throws IOException {
        if(value==null)return "";
        if(!(value instanceof String text) || text.length()>4096 || !text.matches("[\\x21-\\x7e]*")
                || text.matches(".*[;,\"\\\\].*"))throw AuthFailure.of(AuthFailure.Stage.QQ_EXCHANGE,AuthFailure.Reason.CREDENTIAL);
        return text;
    }
}
