package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.kugou.KugouHttp;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** Only explicit QR approval grants a session; restore validates the saved API session. */
public final class KugouQrClient implements QrAuthClient {
    private final Clock clock;
    private final KugouHttp http;
    private Session current;
    private byte[] image;
    private volatile boolean closed;
    public KugouQrClient() {this(Clock.systemUTC(),new KugouHttp());}
    public KugouQrClient(Clock clock,KugouHttp http) {this.clock=clock;this.http=http;}
    public Session create(RequestScope request) throws IOException {
        check(request);var data=object(http.get("/login/qr/key",Map.of(),null,request));
        String key=text(data,"qrcode"),encoded=text(data,"qrcode_img");
        if(!key.matches("[A-Za-z0-9_-]{16,128}") || !encoded.startsWith("data:image/png;base64,") || encoded.length()>350000)
            throw KugouHttp.failure("QR");
        try {image=Base64.getDecoder().decode(encoded.substring("data:image/png;base64,".length()));}
        catch(IllegalArgumentException invalid) {throw KugouHttp.failure("QR_IMAGE");}
        if(image.length<8 || image.length>262144 || image[0]!=(byte)137 || image[1]!=80 || image[2]!=78 || image[3]!=71)
            throw KugouHttp.failure("QR_IMAGE");
        current=new Session(key,URI.create("https://m.kugou.com/"),UUID.randomUUID().toString(),clock.instant().plusSeconds(180));
        check(request);return current;
    }
    public Poll poll(Session session,RequestScope request) throws IOException {
        check(request);
        if(session!=current)throw KugouHttp.failure("QR_CHANGED");
        if(!clock.instant().isBefore(session.expiresAt()))return new Poll(State.EXPIRED,null);
        var data=object(http.get("/login/qr/check",Map.of("key",session.key()),null,request));
        int status=(int)number(data.get("status"),-1);
        if(status==0)return new Poll(State.EXPIRED,null);
        if(status==1)return new Poll(State.WAITING_SCAN,null);
        if(status==2)return new Poll(State.WAITING_CONFIRMATION,null);
        if(status!=4)throw KugouHttp.failure("QR_STATUS");
        String token=text(data,"token"),id=Long.toString(number(data.get("userid"),0));
        if(token.isBlank())throw KugouHttp.failure("QR_TOKEN");
        String sessionId=http.qrSession().isEmpty()?token:http.qrSession();
        var raw=new AccountCredentials(AccountPlatform.KUGOU,id,List.of(
                new ScopedCookies.Entry("kg_session",sessionId,"kugou.com","/",false,true,clock.instant().plusSeconds(30*86400L))),clock,
                AccountCredentials.Profile.from(AccountPlatform.KUGOU,data.get("nickname"),data.get("pic")));
        try {return new Poll(State.CONFIRMED,verify(raw,request));} finally {raw.retire();}
    }
    public AccountCredentials verify(AccountCredentials saved,RequestScope request) throws IOException {
        check(request);
        var data=object(http.get("/user/detail",Map.of(),saved,request));
        if(data.get("userid")!=null && !saved.userId().equals(Long.toString(number(data.get("userid"),0))))throw KugouHttp.failure("ACCOUNT_CHANGED");
        String name=text(data,"nickname");if(name.isBlank())throw KugouHttp.failure("PROFILE");
        check(request);
        return new AccountCredentials(AccountPlatform.KUGOU,saved.userId(),saved.cookieSnapshot(),clock,
                AccountCredentials.Profile.from(AccountPlatform.KUGOU,name,data.get("pic")));
    }
    public byte[] qrImage(Session session) {return session==current && image!=null?image.clone():null;}
    private void check(RequestScope request) throws IOException {request.check();if(closed)throw KugouHttp.failure("CLOSED");}
    public void close() {closed=true;image=null;current=null;}
}
