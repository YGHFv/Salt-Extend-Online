package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** Full synthetic QR/OAuth confirmation, session fencing and redaction; no network or browser. */
public final class QqMusicQrContractTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("QQ QR check "+checks);}
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("QQ QR rejection expected");}
        catch(IOException | IllegalArgumentException expected) {check(!String.valueOf(expected.getMessage()).contains("SECRET"));}
    }
    private static final class Response extends HttpURLConnection {
        final byte[] body;final List<String> cookies;final String location;boolean disconnected;
        Response(URI uri,int status,byte[] body,List<String> cookies,String location) throws IOException {
            super(uri.toURL());responseCode=status;this.body=body;this.cookies=cookies;this.location=location;
        }
        public void connect() {}
        public int getResponseCode() {return responseCode;}
        public InputStream getInputStream() {return new ByteArrayInputStream(body);}
        public String getHeaderField(String name) {return "Location".equals(name)?location:null;}
        public Map<String,List<String>> getHeaderFields() {return Map.of("Set-Cookie",cookies);}
        public long getContentLengthLong() {return body.length;}
        public void disconnect() {disconnected=true;}
        public boolean usingProxy() {return false;}
    }
    private static final class Fixture implements QqMusicHttp.Opener {
        final List<Response> requests=new ArrayList<>();
        String state="66",redirect="https://ssl.ptlogin2.graph.qq.com/check_sig?token=SECRET";
        boolean wrongState,wrongIdentity,wrongType,badImage,omitIdentity,emptyProfile,parentDeletion,escaped;
        int loginCode;
        int verifyCode,verifyStatus=200;
        String profileField="info";
        boolean missingProfile;
        String rawStatus,metadata="";
        public HttpURLConnection open(URI uri,byte[] body) throws IOException {
            byte[] bytes=new byte[0];List<String> cookies=List.of();String location=null;int status=200;
            switch(uri.getPath()) {
                case "/ssl/ptqrshow" -> {
                    bytes=badImage?"HTML".getBytes(StandardCharsets.UTF_8):new byte[]{(byte)137,80,78,71,13,10,26,10,0};
                    cookies=List.of("qrsig=QR_SECRET; Domain=ptlogin2.qq.com; Path=/; Secure; Max-Age=120");
                }
                case "/ssl/ptqrlogin" -> bytes=(rawStatus!=null?rawStatus:"ptuiCB('"+state+"','0','"+
                        (escaped?redirect.replace("&","\\x26"):redirect)+"','0','MESSAGE_SECRET','nick');").getBytes(StandardCharsets.UTF_8);
                case "/check_sig" -> {
                    status=302;location="https://graph.qq.com/oauth2.0/login_jump";
                    cookies=List.of("p_uin=o00042; Domain=graph.qq.com; Path=/; Secure; Max-Age=3600",
                            "p_skey=PSKEY_SECRET; Domain=graph.qq.com; Path=/; Secure; Max-Age=3600");
                    if(parentDeletion)cookies=List.of(cookies.getFirst(),cookies.getLast(),"p_skey=; Domain=qq.com; Path=/; Secure; Max-Age=0");
                }
                case "/oauth2.0/login_jump" -> bytes="ok".getBytes(StandardCharsets.UTF_8);
                case "/oauth2.0/authorize" -> {
                    Map<String,String> form=new HashMap<>();
                    for(String part:new String(body,StandardCharsets.US_ASCII).split("&")) {var pair=part.split("=",2);form.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
                    status=302;location="https://y.qq.com/portal/wx_redirect.html?code="+"A".repeat(32)+"&state="+(wrongState?"FOREIGN":form.get("state"));
                }
                case "/cgi-bin/musicu.fcg" -> {
                    var root=object(io.github.yghfv.saltneri.plugin.net.BoundedJson.parse(body));
                    if(root.containsKey("req")) bytes=("{\"code\":0,\"req\":{\"code\":"+loginCode+",\"data\":{\"musicid\":42,\"musickey\":\"MUSIC_SECRET\",\"loginType\":"+
                            (wrongType?1:2)+",\"encryptUin\":\"ENCRYPTED_SECRET\""+(metadata.isEmpty()?",\"keyExpiresIn\":3600":metadata)+"}}}").getBytes(StandardCharsets.UTF_8);
                    else {
                        var info=new LinkedHashMap<String,Object>();
                        if(!omitIdentity)info.put("uin",wrongIdentity?"43":"42");
                        if(!emptyProfile) {info.put("nick","Verified");info.put("avatarUrl","https://y.qq.com/avatar.png");}
                        status=verifyStatus;
                        bytes=jsonBytes(Map.of("code",0,"req_0",Map.of("code",verifyCode,"data",
                                missingProfile?Map.of():profileField.equals("direct")?info:Map.of(profileField,info))));
                    }
                }
                default -> throw new IOException("Unexpected fixture endpoint");
            }
            var response=new Response(uri,status,bytes,cookies,location);requests.add(response);return response;
        }
        QqMusicQrClient client() {return new QqMusicQrClient(CLOCK,new QqMusicHttp(this));}
    }
    private static Map<String,Object> webCookie(String name,String value,String domain) {
        return Map.of("name",name,"value",value,"domain",domain,"path","/","session",true,"secure",true);
    }
    private static byte[] webCookies(List<Map<String,Object>> rows) {return jsonBytes(Map.of("cookies",rows));}
    private static List<Map<String,Object>> webFixture(String key,String family,String type,String identityName,String identity) {
        var rows=new ArrayList<Map<String,Object>>();
        rows.add(webCookie(key,"MUSIC_SECRET","y.qq.com"));
        rows.add(webCookie(identityName,identity,".qq.com"));
        if(!family.isEmpty())rows.add(webCookie(family,type,"y.qq.com"));
        rows.add(webCookie("p_skey","UNRELATED_SECRET",".qq.com"));
        rows.add(webCookie("tracking","ignored, spaced auxiliary",".y.qq.com"));
        rows.add(webCookie("qqmusic_key","FOREIGN_SECRET","evil.invalid"));
        return rows;
    }
    public static void main(String[] args) throws Exception {
        var f=new Fixture();
        try(var client=f.client();var request=new RequestScope(()->true)) {
            var session=client.create(request);
            check(session.qrContent()==null && client.qrImage(session).length==9);
            byte[] pixels=client.qrImage(session);pixels[0]=0;check(client.qrImage(session)[0]==(byte)137);
            check(client.poll(session,request).state()==QrAuthClient.State.WAITING_SCAN);
            f.state="67";check(client.poll(session,request).state()==QrAuthClient.State.WAITING_CONFIRMATION);
            var foreign=new QrAuthClient.Session(session.key(),null,"",session.expiresAt());
            int before=f.requests.size();check(client.poll(foreign,request).state()==QrAuthClient.State.EXPIRED && f.requests.size()==before);
            f.state="0";var result=client.poll(session,request);
            check(result.state()==QrAuthClient.State.CONFIRMED && result.credentials().userId().equals("42"));
            var account=result.credentials();
            check(account.header(GATEWAY).contains("qqmusic_key=MUSIC_SECRET") && account.header(GATEWAY).contains("tmeLoginType=2"));
            check(account.cookieSnapshot().stream().noneMatch(e->e.name().equals("p_skey") || e.name().equals("qrsig")));
            check(!account.toString().contains("SECRET") && !session.toString().contains("SECRET"));
            check(client.qrImage(session)==null && client.poll(session,request).state()==QrAuthClient.State.EXPIRED);
            check(f.requests.stream().allMatch(r->r.disconnected && !r.getInstanceFollowRedirects()));
            check(f.requests.stream().filter(r->r.getURL().getHost().equals("graph.qq.com") && r.getURL().getPath().equals("/oauth2.0/authorize"))
                    .allMatch(r->r.getRequestProperty("Cookie").contains("p_skey=PSKEY_SECRET")));
            var exchanged=f.requests.stream().filter(r->r.getURL().getHost().equals("u.y.qq.com")).findFirst().orElseThrow();
            check(exchanged.getRequestProperty("Cookie")==null && exchanged.getRequestProperty("User-Agent").equals("QQMusic"));
            account.retire();
        }
        for(String state:List.of("65","68")) {
            var expired=new Fixture();expired.state=state;
            try(var client=expired.client();var request=new RequestScope(()->true)) {var session=client.create(request);check(client.poll(session,request).state()==QrAuthClient.State.EXPIRED);}
        }
        for(String redirect:List.of("https://evil/check_sig?SECRET","http://ssl.ptlogin2.graph.qq.com/check_sig","https://ssl.ptlogin2.graph.qq.com/check_sig#SECRET")) {
            var bad=new Fixture();bad.state="0";bad.redirect=redirect;
            try(var client=bad.client();var request=new RequestScope(()->true)) {var session=client.create(request);fails(()->client.poll(session,request));check(bad.requests.size()==2);}
        }
        for(int kind=0;kind<3;kind++) {
            var bad=new Fixture();bad.state="0";bad.wrongState=kind==0;bad.wrongIdentity=kind==1;bad.wrongType=kind==2;
            try(var client=bad.client();var request=new RequestScope(()->true)) {var session=client.create(request);fails(()->client.poll(session,request));}
        }
        var bad=new Fixture();bad.badImage=true;
        try(var client=bad.client();var request=new RequestScope(()->true)) {fails(()->client.create(request));}
        var closed=new Fixture();var client=closed.client();
        try(var request=new RequestScope(()->true)) {var session=client.create(request);client.close();check(client.qrImage(session)==null);fails(()->client.poll(session,request));}
        check(QqMusicQrClient.hash("abc",0)==108966);
        for(String metadata:List.of(",\"keyExpiresIn\":\"3600\",\"musickeyCreateTime\":\"0\"",
                ",\"keyExpiresIn\":0",",\"keyExpiresIn\":null",",\"keyExpiresIn\":true",",\"keyExpiresIn\":\"invalid\"")) {
            var modern=new Fixture();modern.state="0";modern.metadata=metadata;modern.omitIdentity=true;
            try(var c=modern.client();var r=new RequestScope(()->true)) {
                var session=c.create(r);var result=c.poll(session,r);
                check(result.credentials().userId().equals("42") && result.credentials().profile().name().equals("Verified"));
                check(result.credentials().profile().avatar()!=null);
                check(modern.requests.stream().noneMatch(response->response.getURL().getHost().equals("c.y.qq.com")));
                result.credentials().retire();
            }
        }
        var empty=new Fixture();empty.state="0";empty.omitIdentity=true;empty.emptyProfile=true;
        try(var c=empty.client();var r=new RequestScope(()->true)) {var s=c.create(r);var result=c.poll(s,r);check(result.credentials().profile().name().equals("QQ 42"));result.credentials().retire();}
        var escaped=new Fixture();escaped.state="0";escaped.escaped=true;escaped.parentDeletion=true;
        escaped.redirect="https://ssl.ptlogin2.graph.qq.com/check_sig?token=SECRET&daid=383";
        try(var c=escaped.client();var r=new RequestScope(()->true)) {
            var s=c.create(r);var result=c.poll(s,r);
            check(escaped.requests.stream().anyMatch(response->response.getURL().getQuery()!=null && response.getURL().getQuery().endsWith("&daid=383")));
            check(result.credentials().isActive());result.credentials().retire();
        }
        for(String invalid:List.of("ptuiCB('66','0','x');","ptuiCB('66','0','','0','x','n',);",
                "ptuiCB('66','0','','0','x','n');evil()","ptuiCB('66','0','','0','\\z','n');")) {
            var malformed=new Fixture();malformed.rawStatus=invalid;
            try(var c=malformed.client();var r=new RequestScope(()->true)) {var s=c.create(r);fails(()->c.poll(s,r));}
        }
        check(QqMusicPtui.parse("ptuiCB('66','0','','0','it\\'s \\\\ \\u4e2d','nick');").get(4).equals("it's \\ \u4e2d"));
        var expiredKey=new Fixture();expiredKey.state="0";expiredKey.metadata=",\"musickeyCreateTime\":1,\"keyExpiresIn\":1";
        try(var c=expiredKey.client();var r=new RequestScope(()->true)) {var s=c.create(r);fails(()->c.poll(s,r));}
        var api=new Fixture();api.state="0";api.loginCode=1000;
        try(var c=api.client();var r=new RequestScope(()->true)) {
            var s=c.create(r);
            try {c.poll(s,r);throw new AssertionError("Missing login code");}
            catch(AuthFailure failure) {check(failure.getMessage().equals("QQ_EXCHANGE/API=1000") && !AuthFailure.diagnostic(failure).contains("SECRET"));}
        }
        var replaced=new Fixture();
        try(var c=replaced.client();var r=new RequestScope(()->true)) {
            var first=c.create(r);replaced.badImage=true;fails(()->c.create(r));
            check(c.qrImage(first)==null && c.poll(first,r).state()==QrAuthClient.State.EXPIRED);
            replaced.badImage=false;var fresh=c.create(r);check(fresh!=first && c.qrImage(fresh)!=null);
        }
        for(boolean invalidImage:List.of(true,false)) {
            try(var request=new RequestScope(()->true);var qrClient=new QqMusicQrClient(CLOCK,new QqMusicHttp((uri,body)->
                new Response(uri,200,invalidImage?new byte[]{1,2,3}:new byte[]{(byte)137,80,78,71,13,10,26,10,0},
                    invalidImage?List.of("qrsig=SYNTHETIC; Domain=ptlogin2.qq.com; Path=/; Secure"):List.of(),null)))) {
                try {qrClient.create(request);throw new AssertionError("Invalid QR accepted");}
                catch(AuthFailure safe) {
                    check(safe.getMessage().equals("QQ_CREATE/"+(invalidImage?"QR_IMAGE":"QR_COOKIE")));
                    check(qrClient.qrImage(null)==null && !AuthFailure.diagnostic(safe).contains("SYNTHETIC"));
                }
            }
        }
        for(String profile:List.of("info","creator","profile","user","direct")) {
            var web=new Fixture();web.omitIdentity=true;web.profileField=profile;
            try(var c=web.client();var r=new RequestScope(()->true)) {
                var account=WindowsQqMusicWebLogin.verifyCookies(webCookies(webFixture("qm_keyst","login_type","1","uin","o00042")),CLOCK,c,r);
                check(account.userId().equals("42") && account.profile().name().equals("Verified"));
                check(account.header(GATEWAY).contains("qqmusic_key=MUSIC_SECRET") && account.header(GATEWAY).contains("tmeLoginType=2"));
                check(account.cookieSnapshot().stream().allMatch(e->e.domain().equals("y.qq.com") && !e.hostOnly()));
                check(!account.header(GATEWAY).contains("UNRELATED") && !account.header(GATEWAY).contains("FOREIGN"));
                check(web.requests.size()==1 && web.requests.getFirst().getRequestProperty("Cookie").contains("uin=42"));
                account.retire();
            }
        }
        for(String family:List.of("tmeLoginType","login_type",""))for(String key:List.of("qqmusic_key","qm_keyst","musickey")) {
            var web=new Fixture();web.omitIdentity=true;
            try(var c=web.client();var r=new RequestScope(()->true)) {
                var account=WindowsQqMusicWebLogin.verifyCookies(webCookies(webFixture(key,family,
                        family.equals("login_type")?"2":"1","wxuin","42")),CLOCK,c,r);
                check(account.header(GATEWAY).contains("tmeLoginType=1") && account.header(GATEWAY).contains("wxuin=42"));
                account.retire();
            }
        }
        var scoped=webFixture("qqmusic_key","tmeLoginType","2","uin","o00099");
        scoped.add(webCookie("qqmusic_uin","42","y.qq.com"));
        try(var c=new Fixture().client();var r=new RequestScope(()->true)) {
            var account=WindowsQqMusicWebLogin.verifyCookies(webCookies(scoped),CLOCK,c,r);
            check(account.userId().equals("42"));account.retire();
        }
        for(String error:List.of("EDGE_MISSING","WEB_CLOSED","WEB_TIMEOUT","FAIL_SECRET")) {
            try {QqMusicWebCookies.decode(jsonBytes(Map.of("error",error)),CLOCK);throw new AssertionError("Helper error ignored");}
            catch(AuthFailure safe) {check(safe.getMessage().startsWith("QQ_WEB/") && !safe.getMessage().contains("SECRET"));}
        }
        for(int mode=0;mode<6;mode++) {
            var rows=webFixture("qqmusic_key","tmeLoginType","2","uin","o00042");
            if(mode==0)rows.set(0,webCookie("qqmusic_key","bad;SECRET","y.qq.com"));
            if(mode==1)rows.remove(0);
            if(mode==2)rows.set(1,webCookie("uin","not-an-id","qq.com"));
            if(mode==3)rows.set(2,webCookie("tmeLoginType","3","y.qq.com"));
            if(mode==4)rows.add(rows.getFirst());
            if(mode==5) {var expired=new HashMap<>(rows.getFirst());expired.put("session",false);expired.put("expires",CLOCK.instant().getEpochSecond()-1);rows.set(0,expired);}
            var web=new Fixture();
            try(var c=web.client();var r=new RequestScope(()->true)) {
                fails(()->WindowsQqMusicWebLogin.verifyCookies(webCookies(rows),CLOCK,c,r));
                check(web.requests.isEmpty());
            }
        }
        for(int mode=0;mode<4;mode++) {
            var web=new Fixture();web.wrongIdentity=mode==0;web.verifyCode=mode==1?1000:0;
            web.verifyStatus=mode==2?403:200;web.missingProfile=mode==3;
            try(var c=web.client();var r=new RequestScope(()->true)) {
                try {WindowsQqMusicWebLogin.verifyCookies(webCookies(webFixture("qqmusic_key","tmeLoginType","2","uin","42")),CLOCK,c,r);
                    throw new AssertionError("Unverified web credential imported");}
                catch(AuthFailure safe) {
                    check(safe.getMessage().equals(switch(mode) {case 0 -> "QQ_VERIFY/IDENTITY";case 1 -> "QQ_VERIFY/API=1000";
                        case 2 -> "QQ_VERIFY/HTTP=403";default -> "QQ_VERIFY/RESPONSE";}));
                    check(!AuthFailure.diagnostic(safe).contains("SECRET"));
                }
            }
        }
        System.out.println("PASS: "+checks+" QQ QR/OAuth confirmation/session/cookie/identity/state/redaction checks; synthetic only");
    }
}
