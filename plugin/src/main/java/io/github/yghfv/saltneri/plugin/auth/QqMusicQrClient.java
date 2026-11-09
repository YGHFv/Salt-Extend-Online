package io.github.yghfv.saltneri.plugin.auth;

/* QQ OAuth QR protocol adapted from pinned NeriPlayer QQMusicQrLoginClient,
 * GPL-3.0-or-later; modern response/session behavior from mygo-qyin, AGPL-3.0.
 * Login cookies are never added to the music account jar. */

import io.github.yghfv.saltneri.plugin.net.*;
import io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

public final class QqMusicQrClient implements QrAuthClient {
    private final QqMusicHttp http;
    private final Clock clock;
    private final List<ScopedCookies.Entry> loginCookies=new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Session current;
    private volatile byte[] image;
    private volatile boolean closed;
    public QqMusicQrClient() { this(Clock.systemUTC(),new QqMusicHttp()); }
    public QqMusicQrClient(Clock clock,QqMusicHttp http) {this.clock=clock;this.http=http;}
    @Override public Session create(RequestScope request) throws IOException {
        try {return createSession(request);}
        catch(IOException error) {
            synchronized(this) {current=null;image=null;loginCookies.clear();}
            throw AuthFailure.wrap(AuthFailure.Stage.QQ_CREATE,AuthFailure.Reason.RESPONSE,error);
        }
    }
    private Session createSession(RequestScope request) throws IOException {
        check(); current=null;image=null;loginCookies.clear();
        URI uri=URI.create("https://xui.ptlogin2.qq.com/ssl/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t="
                +UUID.randomUUID()+"&daid=383&pt_3rd_aid=100497308&u1="+encode("https://graph.qq.com/oauth2.0/login_jump"));
        var response=send(uri,null,request,65536);
        byte[] bytes=response.bytes();
        boolean png=bytes.length>8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71;
        boolean jpeg=bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
        boolean gif=bytes.length>6 && new String(bytes,0,6,StandardCharsets.US_ASCII).matches("GIF8[79]a");
        if(response.status()!=200 || !(png || jpeg || gif))throw AuthFailure.of(AuthFailure.Stage.QQ_CREATE,AuthFailure.Reason.QR_IMAGE);
        if(cookieValue("qrsig").isBlank())throw AuthFailure.of(AuthFailure.Stage.QQ_CREATE,AuthFailure.Reason.QR_COOKIE);
        synchronized(this) {
            check(); image=bytes.clone();
            current=new Session(UUID.randomUUID().toString(),null,"",clock.instant().plusSeconds(120));
            return current;
        }
    }
    @Override public synchronized byte[] qrImage(Session session) { return !closed && session==current && image!=null?image.clone():null; }
    @Override public Poll poll(Session session,RequestScope request) throws IOException {
        check();
        if(session==null || session!=current)return new Poll(State.EXPIRED,null);
        if(!clock.instant().isBefore(session.expiresAt())) {clearSession(session);return new Poll(State.EXPIRED,null);}
        var response=send(URI.create("https://xui.ptlogin2.qq.com/ssl/ptqrlogin?u1="+encode("https://graph.qq.com/oauth2.0/login_jump")
                +"&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-"+clock.millis()
                +"&js_ver=20102616&js_type=1&pt_uistyle=40&has_onekey=1"
                +"&aid=716027609&daid=383&pt_3rd_aid=100497308&ptqrtoken="+hash(cookieValue("qrsig"),0)),null,request,262144);
        String raw=new String(response.bytes(),StandardCharsets.UTF_8).strip();
        if(response.status()!=200)throw AuthFailure.of(AuthFailure.Stage.QQ_POLL,AuthFailure.Reason.RESPONSE);
        var values=QqMusicPtui.parse(raw);
        return switch(values.getFirst()) {
            case "66" -> new Poll(State.WAITING_SCAN,null);
            case "67" -> new Poll(State.WAITING_CONFIRMATION,null);
            case "65","68" -> {clearSession(session);yield new Poll(State.EXPIRED,null);}
            case "0" -> {
                var credentials=exchange(values.get(2),request);
                try {
                    check();request.check();
                    if(!consumeSession(session)) {
                        credentials.retire();clearSession(session);yield new Poll(State.EXPIRED,null);
                    }
                } catch(IOException | RuntimeException failure) {credentials.retire();throw failure;}
                yield new Poll(State.CONFIRMED,credentials);
            }
            default -> throw new IOException("QQ QR status rejected");
        };
    }
    private AccountCredentials exchange(String checkSig,RequestScope request) throws IOException {
        URI signatureUri=trustedSignature(checkSig);
        var signature=send(signatureUri,null,request,262144);
        if(signature.status()!=302) throw AuthFailure.of(AuthFailure.Stage.QQ_SIGNATURE,AuthFailure.Reason.RESPONSE);
        var jump=send(trusted(signature.location(),"graph.qq.com","/oauth2.0/login_jump"),null,request,262144);
        URI authorize=URI.create("https://graph.qq.com/oauth2.0/authorize");
        String graphKey=cookieValue(authorize,"p_skey");
        if(jump.status()!=200 || graphKey.isBlank() || cookieValue(authorize,"p_uin").isBlank()) throw AuthFailure.of(AuthFailure.Stage.QQ_OAUTH,AuthFailure.Reason.COOKIE);
        String state=UUID.randomUUID().toString().replace("-","");
        var params=new LinkedHashMap<String,String>();
        params.put("response_type","code");params.put("client_id","100497308");
        params.put("redirect_uri","https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/");
        params.put("scope","get_user_info,get_app_friends");params.put("state",state);
        params.put("switch","");params.put("from_ptlogin","1");params.put("src","1");params.put("update_auth","1");
        params.put("openapi","1010_1030");params.put("g_tk",Long.toString(hash(graphKey,5381)));
        params.put("auth_time",Long.toString(clock.millis()));params.put("ui",UUID.randomUUID().toString());
        byte[] form=params.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.US_ASCII);
        var authorization=send(authorize,form,request,262144);
        if(authorization.status()!=302) throw AuthFailure.of(AuthFailure.Stage.QQ_OAUTH,AuthFailure.Reason.RESPONSE);
        var callback=trusted(authorization.location(),"y.qq.com","/portal/wx_redirect.html");
        var query=new HashMap<String,String>();
        for(String part:Objects.requireNonNullElse(callback.getRawQuery(),"").split("&")) {
            String[] entry=part.split("=",2); if(entry.length!=2) continue;
            String name=URLDecoder.decode(entry[0],StandardCharsets.UTF_8);
            if(query.putIfAbsent(name,URLDecoder.decode(entry[1],StandardCharsets.UTF_8))!=null) throw new IOException("Duplicate QQ OAuth value");
        }
        String code=query.getOrDefault("code","");
        if(!code.matches("[A-F0-9]{32}") || !state.equals(query.get("state"))) throw AuthFailure.of(AuthFailure.Stage.QQ_OAUTH,AuthFailure.Reason.RESPONSE);
        var result=http.exchange(GATEWAY,jsonBytes(Map.of("comm",Map.of("g_tk",5381,"platform","yqq","ct",24,"cv",0,"tmeLoginType",2),
                "req",Map.of("module","QQConnectLogin.LoginServer","method","QQLogin","param",Map.of("code",code)))),"",request,262144);
        var unverified=QqMusicLoginResponse.decode(result,clock);
        try {return verify(unverified,request);} finally {unverified.retire();}
    }
    @Override public AccountCredentials verifyCookies(List<ScopedCookies.Entry> entries,Clock clock,RequestScope request) throws IOException {
        String identity=entries.stream().filter(e->e.name().equals("qqmusic_uin")).map(ScopedCookies.Entry::value).findFirst().orElse("");
        var unverified=new AccountCredentials(AccountPlatform.QQMUSIC,identity,entries,clock);
        try {return verify(unverified,request);} finally {unverified.retire();}
    }
    @Override public AccountCredentials verify(AccountCredentials saved,RequestScope request) throws IOException {
        check();
        if(saved.platform()!=AccountPlatform.QQMUSIC) throw new IOException("Wrong QQ account");
        Map<String,Object> data;
        try {data=http.call("music.UserInfo.userInfoServer","GetLoginUserInfo",Map.of(),saved,request);}
        catch(QqMusicHttp.Rejected denied) {throw AuthFailure.code(AuthFailure.Stage.QQ_VERIFY,
                denied.httpStatus()?AuthFailure.Reason.HTTP:AuthFailure.Reason.API,denied.code());}
        catch(IOException failure) {throw AuthFailure.wrap(AuthFailure.Stage.QQ_VERIFY,AuthFailure.Reason.RESPONSE,failure);}
        var profiles=new ArrayList<Map<String,Object>>();profiles.add(data);
        for(String field:List.of("info","creator","profile","user"))if(data.get(field) instanceof Map<?,?>)profiles.add(object(data.get(field)));
        if(profiles.size()==1 && text(data,"nick","hostname","nickname","name","headurl","logo","avatarUrl","avatar","headpic").isBlank())
            throw AuthFailure.of(AuthFailure.Stage.QQ_VERIFY,AuthFailure.Reason.RESPONSE);
        // This authenticated endpoint normally omits UIN. Its successful response verifies
        // the key; the account ID comes from QQLogin, never a public profile lookup.
        for(var values:profiles)for(String field:List.of("hostuin","uin","musicid","musicId","qqmusic_uin")) {
            if(!values.containsKey(field) || values.get(field)==null || "".equals(values.get(field)))continue;
            String returned=QqMusicLoginResponse.identity(values.get(field));
            if(!saved.userId().equals(returned))throw AuthFailure.of(AuthFailure.Stage.QQ_VERIFY,AuthFailure.Reason.IDENTITY);
        }
        String name="",avatar="";
        for(var profile:profiles) {
            if(name.isBlank())name=text(profile,"nick","hostname","nickname","name");
            if(avatar.isBlank())avatar=text(profile,"headurl","logo","avatarUrl","avatar","headpic");
        }
        if(name.isBlank())name=saved.profile().name();
        if(name.isBlank())name="QQ "+saved.userId();
        if(avatar.isBlank() && saved.profile().avatar()!=null)avatar=saved.profile().avatar().toString();
        check();request.check();
        return new AccountCredentials(AccountPlatform.QQMUSIC,saved.userId(),saved.cookieSnapshot(),clock,
                AccountCredentials.Profile.from(AccountPlatform.QQMUSIC,name,avatar), saved.userAgent());
    }
    private Response send(URI uri,byte[] body,RequestScope request,int limit) throws IOException {
        check();String host=uri.getHost();
        String header;
        if(host.equals("graph.qq.com") && uri.getPath().equals("/oauth2.0/authorize")) {
            var graph=new LinkedHashMap<String,String>();
            for(String name:List.of("p_uin","p_skey","pt_oauth_token")) {
                String value=cookieValue(uri,name);if(!value.isBlank())graph.put(name,value);
            }
            header=graph.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("; "));
        } else header=loginCookies.stream().filter(e->matches(e,uri))
                .sorted(Comparator.comparingInt((ScopedCookies.Entry e)->e.path().length()).reversed())
                .map(e->e.name()+"="+e.value()).collect(java.util.stream.Collectors.joining("; "));
        Response response;
        var stage=uri.getPath().equals("/check_sig")?AuthFailure.Stage.QQ_SIGNATURE:
                uri.getHost().equals("graph.qq.com")?AuthFailure.Stage.QQ_OAUTH:
                uri.getPath().equals("/ssl/ptqrshow")?AuthFailure.Stage.QQ_CREATE:AuthFailure.Stage.QQ_POLL;
        try {response=http.exchange(uri,body,header,request,limit);}
        catch(QqMusicHttp.Rejected denied) {throw AuthFailure.code(stage,AuthFailure.Reason.HTTP,denied.code());}
        catch(IOException failure) {throw AuthFailure.wrap(stage,AuthFailure.Reason.NETWORK,failure);}
        for(String raw:response.cookies()) {
            if(raw.length()>8192) throw new IOException("QQ login cookie too large");
            try {
                for(HttpCookie c:HttpCookie.parse(raw)) {
                    String domain=c.getDomain()==null?host:c.getDomain().replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
                    if(!(domain.equals("qq.com") || domain.endsWith(".qq.com")) || !(host.equals(domain) || host.endsWith("."+domain))
                            || !c.getName().matches("[A-Za-z0-9_-]{1,128}") || !c.getValue().matches("[\\x21-\\x7e]{0,4096}")
                            || c.getValue().matches(".*[;,\"\\\\].*")) throw new IOException("QQ login cookie invalid");
                    String p=c.getPath()==null?"/":c.getPath();
                    if(!p.startsWith("/") || p.length()>1024 || p.chars().anyMatch(ch->ch<32 || ch>126))
                        throw new IOException("QQ login cookie invalid");
                    loginCookies.removeIf(e->e.name().equals(c.getName()) && e.domain().equals(domain) && e.path().equals(p));
                    if(c.getMaxAge()!=0 && !c.getValue().isEmpty())loginCookies.add(new ScopedCookies.Entry(c.getName(),c.getValue(),domain,p,c.getDomain()==null,true,
                            clock.instant().plusSeconds(c.getMaxAge()<0?120:Math.min(120,c.getMaxAge()))));
                }
            } catch(IllegalArgumentException failure) {throw new IOException("QQ login cookie invalid");}
        }
        if(loginCookies.size()>64) throw new IOException("QQ cookie limit");
        check();return response;
    }
    private boolean matches(ScopedCookies.Entry e,URI uri) {
        String host=uri.getHost(),path=uri.getPath();
        return e.expiry().isAfter(clock.instant()) && (host.equals(e.domain()) || !e.hostOnly() && host.endsWith("."+e.domain()))
                && (path.equals(e.path()) || path.startsWith(e.path()) && (e.path().endsWith("/") || path.charAt(e.path().length())=='/'));
    }
    private String cookieValue(String name) {return cookieValue(URI.create("https://xui.ptlogin2.qq.com/ssl/ptqrlogin"),name);}
    private String cookieValue(URI uri,String name) {
        return loginCookies.stream().filter(e->e.name().equals(name) && matches(e,uri))
                .sorted(Comparator.comparingInt((ScopedCookies.Entry e)->e.domain().length()).reversed()
                        .thenComparing(Comparator.comparingInt((ScopedCookies.Entry e)->e.path().length()).reversed()))
                .map(ScopedCookies.Entry::value).findFirst().orElse("");
    }
    private synchronized void clearSession(Session session) {if(current==session) {current=null;image=null;loginCookies.clear();}}
    private synchronized boolean consumeSession(Session session) {
        if(closed || current!=session || !clock.instant().isBefore(session.expiresAt()))return false;
        clearSession(session);return true;
    }
    private static URI trusted(String value,String host,String path) throws IOException {
        try {
            URI uri=URI.create(value);
            if(!"https".equals(uri.getScheme()) || !host.equals(uri.getHost()) || !path.equals(uri.getPath())
                    || uri.getPort()!=-1 && uri.getPort()!=443 || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException failure) {throw new IOException("QQ login redirect rejected");}
    }
    private static URI trustedSignature(String value) throws IOException {
        try {
            URI uri=URI.create(value);
            if(uri.getHost()==null || !Set.of("ssl.ptlogin2.graph.qq.com","ssl.ptlogin2.qq.com").contains(uri.getHost()))
                throw new IllegalArgumentException();
            return trusted(value,uri.getHost(),"/check_sig");
        } catch(IllegalArgumentException invalid) {throw new IOException("QQ login redirect rejected");}
    }
    public static long hash(String value,long initial) {long hash=initial;for(char c:value.toCharArray()) hash=(hash*33+c)&0xffffffffL;return hash&0x7fffffffL;}
    private void check() throws IOException {if(closed) {loginCookies.clear();throw new IOException("QQ login closed");}}
    @Override public synchronized void close() {closed=true;current=null;image=null;loginCookies.clear();}
}
