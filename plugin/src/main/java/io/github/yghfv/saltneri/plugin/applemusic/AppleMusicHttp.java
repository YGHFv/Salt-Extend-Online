package io.github.yghfv.saltneri.plugin.applemusic;

/* AMPWebPlay discovery and Web API headers follow MineRadio Apple Music edition (GPL-3.0).
 * Fixed GET targets, bounded responses and private credentials are specific to Salt Extend. */
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

public final class AppleMusicHttp {
    public static final URI API=URI.create("https://amp-api.music.apple.com");
    @FunctionalInterface public interface Opener {HttpURLConnection open(URI uri) throws IOException;}
    private String bearer="";
    private Instant bearerUntil=Instant.MIN;
    private final Opener opener;
    private final Clock clock;
    public AppleMusicHttp() {this(PinnedHttpsConnection::new,Clock.systemUTC());}
    public AppleMusicHttp(Opener opener,Clock clock) {this.opener=opener;this.clock=clock;}
    public Map<String,Object> get(String path,AccountCredentials account,RequestScope request) throws IOException {
        if(!path.matches("/v1/(me/(storefront|library/(albums|playlists|songs)(/[A-Za-z0-9.-]{1,128}(/tracks)?)?)|catalog/[a-z]{2}/(charts|search|songs/[0-9]{1,19}))(\\?[A-Za-z0-9_%=&+.,-]{1,2048})?"))
            throw failure("ENDPOINT");
        String token=publicBearer(request);
        var headers=new LinkedHashMap<String,String>(Map.of("Authorization","Bearer "+token,"Origin","https://music.apple.com",
                "Referer","https://music.apple.com/","Accept","application/json"));
        if(account!=null) {
            if(account.platform()!=AccountPlatform.APPLEMUSIC || !account.isActive())throw failure("LOGIN_REQUIRED");
            String user=account.cookieSnapshot().stream().filter(e->e.name().equals("media-user-token")).map(ScopedCookies.Entry::value).findFirst().orElse("");
            headers.put("Music-User-Token",user);
        }
        byte[] bytes=read(API.resolve(path),headers,BoundedJson.MAX_BYTES,request);
        request.check();if(account!=null && !account.isActive())throw failure("SESSION_REVOKED");
        return BoundedJson.object(BoundedJson.parse(bytes));
    }
    public String publicBearer(RequestScope request) throws IOException {
        synchronized(this) {if(clock.instant().plusSeconds(60).isBefore(bearerUntil))return bearer;}
        String html=new String(read(URI.create("https://music.apple.com/us/browse"),Map.of(),4*1024*1024,request),StandardCharsets.UTF_8);
        var script=Pattern.compile("<script[^>]+src=\"(/assets/index[A-Za-z0-9_~.-]+\\.js)\"").matcher(html);
        if(!script.find())throw failure("WEB_BUNDLE");
        String bundle=new String(read(URI.create("https://music.apple.com"+script.group(1)),Map.of(),16*1024*1024,request),StandardCharsets.UTF_8);
        var jwt=Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}").matcher(bundle);
        while(jwt.find())try {
            var claims=BoundedJson.object(BoundedJson.parse(Base64.getUrlDecoder().decode(jwt.group().split("\\.")[1])));
            if(!"AMPWebPlay".equals(claims.get("iss")))continue;
            Instant until=Instant.ofEpochSecond(BoundedJson.integer(claims.get("exp")));
            if(!clock.instant().plusSeconds(60).isBefore(until))continue;
            synchronized(this) {bearer=jwt.group();bearerUntil=until;return bearer;}
        } catch(IOException | IllegalArgumentException ignored) { }
        throw failure("WEB_BEARER");
    }
    private byte[] read(URI uri,Map<String,String> headers,int limit,RequestScope request) throws IOException {
        return read(uri,headers,limit,request,0);
    }
    private byte[] read(URI uri,Map<String,String> headers,int limit,RequestScope request,int redirects) throws IOException {
        if(!"https".equals(uri.getScheme()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getFragment()!=null
                || !Set.of("music.apple.com","amp-api.music.apple.com").contains(uri.getHost()))throw failure("ENDPOINT");
        request.check();var connection=opener.open(uri);request.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent",io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.AGENT);
            connection.setRequestProperty("Accept-Encoding","identity");
            for(var h:headers.entrySet())connection.setRequestProperty(h.getKey(),h.getValue());
            int status=connection.getResponseCode();
            if(headers.isEmpty() && Set.of(301,302,307,308).contains(status) && redirects<4) {
                String location=connection.getHeaderField("Location");
                if(location==null)throw failure("REDIRECT");
                URI next;
                try {next=uri.resolve(location);} catch(IllegalArgumentException invalid) {throw failure("REDIRECT");}
                if(!"music.apple.com".equals(next.getHost()) || !next.getPath().matches("/([a-z]{2}(/(browse|new))?)"))throw failure("REDIRECT");
                request.release(connection);
                return read(next,headers,limit,request,redirects+1);
            }
            if(status==401 && !headers.isEmpty())synchronized(this) {bearer="";bearerUntil=Instant.MIN;}
            if(status!=200)throw failure("HTTP_"+status);
            if(connection.getContentLengthLong()>limit)throw failure("SIZE");
            String encoding=connection.getHeaderField("Content-Encoding");
            if(encoding!=null && !encoding.equalsIgnoreCase("identity"))throw failure("ENCODING");
            try(var input=connection.getInputStream();var output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];
                for(int n;(n=input.read(buffer))!=-1;) {request.check();if(output.size()+n>limit)throw failure("SIZE");output.write(buffer,0,n);}
                request.check();return output.toByteArray();
            }
        } finally {request.release(connection);}
    }
    public static IOException failure(String code) {return new IOException("APPLE/"+code);}
}
