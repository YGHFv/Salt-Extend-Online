package io.github.yghfv.saltneri.plugin.kugou;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** KA Music protocol adapter. Credentials go only to its fixed API, never to artwork/media. */
public final class KugouHttp {
    public static final String HOST="music.api.hoilai.cn";
    private static final Set<String> PATHS=Set.of("/login/qr/key","/login/qr/check","/user/detail","/user/playlist",
            "/top/playlist","/search","/playlist/detail","/playlist/track/all","/privilege/lite","/song/url","/search/lyric","/lyric");
    @FunctionalInterface public interface Opener {HttpURLConnection open(URI uri) throws IOException;}
    private final Opener opener;
    private volatile String qrSession="";
    public String qrSession() {return qrSession;}
    public KugouHttp() {this(PinnedHttpsConnection::new);}
    public KugouHttp(Opener opener) {this.opener=Objects.requireNonNull(opener);}
    public Object get(String path,Map<String,?> parameters,AccountCredentials account,RequestScope request) throws IOException {
        if(!PATHS.contains(path))throw failure("ENDPOINT");
        if(account!=null && (account.platform()!=AccountPlatform.KUGOU || !account.isActive()))throw failure("LOGIN_REQUIRED");
        String query=parameters.entrySet().stream().map(e->encode(e.getKey())+"="+encode(String.valueOf(e.getValue())))
                .collect(java.util.stream.Collectors.joining("&"));
        URI uri=URI.create("https://"+HOST+path+(query.isEmpty()?"":"?"+query));
        if(path.equals("/login/qr/key"))qrSession="";
        request.check();var connection=opener.open(uri);request.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(6000);connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("User-Agent",AGENT);
            if(account!=null)connection.setRequestProperty("X-Kg-Session-Id",token(account));
            else if(path.equals("/login/qr/check") && !qrSession.isEmpty())connection.setRequestProperty("X-Kg-Session-Id",qrSession);
            int code=connection.getResponseCode();request.check();
            if(code!=200)throw failure("HTTP_"+code);
            if(path.equals("/login/qr/key") || path.equals("/login/qr/check")) {
                String session=connection.getHeaderField("X-Kg-Session-Id");
                if(session!=null) {
                    if(!session.matches("[A-Za-z0-9_.:-]{1,4096}"))throw failure("SESSION_HEADER");
                    qrSession=session;
                }
            }
            long length=connection.getContentLengthLong();
            if(length>BoundedJson.MAX_BYTES)throw failure("RESPONSE_BOUND");
            String encoding=connection.getHeaderField("Content-Encoding");
            if(encoding!=null && !encoding.equalsIgnoreCase("identity"))throw failure("ENCODING");
            byte[] bytes;
            try(var in=connection.getInputStream();var out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];
                for(int n;(n=in.read(buffer))!=-1;) {
                    request.check();if(out.size()+n>BoundedJson.MAX_BYTES)throw failure("RESPONSE_BOUND");
                    out.write(buffer,0,n);
                }
                bytes=out.toByteArray();
            }
            Object root=BoundedJson.parse(bytes);
            if(root instanceof Map<?,?>) {
                var envelope=object(root);checkCodes(envelope);
                if(envelope.get("data")!=null)root=envelope.get("data");
                if(root instanceof Map<?,?>)checkCodes(object(root));
            }
            request.check();if(account!=null && !account.isActive())throw failure("LOGIN_REQUIRED");
            return root;
        } finally {request.release(connection);}
    }
    private static void checkCodes(Map<String,Object> value) throws IOException {
        for(String key:List.of("error_code","err_code","_errno"))if(number(value.get(key),0)!=0)throw failure("API_"+number(value.get(key),0));
    }
    public static String token(AccountCredentials account) throws IOException {
        return account.cookieSnapshot().stream().filter(e->e.name().equals("kg_session")).map(ScopedCookies.Entry::value).findFirst().orElseThrow(()->failure("LOGIN_REQUIRED"));
    }
    public static IOException failure(String stage) {return new IOException("KUGOU/"+stage);}
    public static String hash(String value) {
        if(value==null || !value.matches("[A-Fa-f0-9]{32}"))throw new IllegalArgumentException("Invalid Kugou hash");
        return value.toUpperCase(Locale.ROOT);
    }
    public static List<Object> rows(Object root,String... fields) throws IOException {
        if(root instanceof List<?>)return array(root);
        var data=object(root);
        for(String field:fields)if(data.get(field) instanceof List<?>)return array(data.get(field));
        throw failure("TRACKS_MISSING");
    }
}
