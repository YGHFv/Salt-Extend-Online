package io.github.yghfv.saltneri.plugin.qqmusic;

/* Official gateway/auth field mapping adapted from pinned NeriPlayer QQMusicClient.
 * GPL-3.0-or-later. Bounded, cancellable and isolated transport is plugin-specific. */

import io.github.yghfv.saltneri.plugin.auth.AccountCredentials;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class QqMusicHttp {
    public static final URI GATEWAY = URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg");
    public static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/132.0.0.0 Safari/537.36";
    @FunctionalInterface public interface Opener { HttpURLConnection open(URI uri, byte[] body) throws IOException; }
    private final Opener opener;
    public QqMusicHttp() { this(QqMusicHttp::openPinned); }
    public QqMusicHttp(Opener opener) { this.opener = Objects.requireNonNull(opener); }
    public static HttpURLConnection openPinned(URI uri, byte[] body) throws IOException {
        return body == null ? new PinnedHttpsConnection(uri) : GATEWAY.equals(uri)
                ? PinnedHttpsConnection.qqMusicPost(uri, body) : PinnedHttpsConnection.qqAuthorizePost(uri, body);
    }
    public record Response(int status, byte[] bytes, List<String> cookies, String location) {
        @Override public String toString() { return "QQResponse[redacted]"; }
        public Map<String,Object> json() throws IOException { return BoundedJson.object(BoundedJson.parse(bytes)); }
    }
    public Response exchange(URI uri, byte[] body, String cookie, RequestScope request, int limit) throws IOException {
        boolean allowed = GATEWAY.equals(uri)
                || "c.y.qq.com".equals(uri.getHost()) && Set.of("/fav/fcgi-bin/fcg_get_profile_order_asset.fcg",
                    "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg","/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                    "/lyric/fcgi-bin/fcg_query_lyric_new.fcg").contains(uri.getPath()) && body == null
                || "xui.ptlogin2.qq.com".equals(uri.getHost()) && Set.of("/ssl/ptqrshow","/ssl/ptqrlogin").contains(uri.getPath()) && body == null
                || "ssl.ptlogin2.graph.qq.com".equals(uri.getHost()) && "/check_sig".equals(uri.getPath()) && body == null
                || "graph.qq.com".equals(uri.getHost()) && ("/oauth2.0/login_jump".equals(uri.getPath()) && body == null
                    || "/oauth2.0/authorize".equals(uri.getPath()) && body != null);
        if(!allowed || !"https".equals(uri.getScheme()) || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                || uri.getPort()!=-1 && uri.getPort()!=443 || limit<1 || limit>BoundedJson.MAX_BYTES) throw new IOException("QQ endpoint rejected");
        request.check(); var connection=opener.open(uri,body); request.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestMethod(body==null?"GET":"POST");
            boolean credentialExchange=body!=null && GATEWAY.equals(uri) && object(BoundedJson.parse(body)).containsKey("req");
            connection.setRequestProperty("User-Agent",credentialExchange?"QQMusic":AGENT);
            connection.setRequestProperty("Referer",uri.getHost().contains("ptlogin2")?"https://xui.ptlogin2.qq.com/":"https://y.qq.com/");
            connection.setRequestProperty("Accept-Encoding","identity");
            if(cookie!=null && !cookie.isEmpty()) connection.setRequestProperty("Cookie",cookie);
            int status=connection.getResponseCode(); request.check();
            if(status!=200 && status!=302) throw new Rejected(status,true);
            if(connection.getContentLengthLong()>limit) throw new IOException("QQ response too large");
            String encoding=connection.getHeaderField("Content-Encoding");
            if(encoding!=null && !encoding.equalsIgnoreCase("identity")) throw new IOException("QQ encoding rejected");
            byte[] bytes;
            if(status==302) bytes=new byte[0];
            else try(var input=connection.getInputStream(); var output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];
                for(int n;(n=input.read(buffer))!=-1;) {
                    request.check(); if(output.size()+n>limit) throw new IOException("QQ response too large");
                    output.write(buffer,0,n);
                }
                bytes=output.toByteArray();
            }
            request.check();
            return new Response(status,bytes,JsonResponse.setCookies(connection),connection.getHeaderField("Location"));
        } finally { request.release(connection); }
    }
    public static class Rejected extends IOException {
        private final long code;
        private final boolean http;
        public Rejected(long code) { this(code,false); }
        public Rejected(long code,boolean http) { super("QQ Music rejected request (code "+code+")"); this.code=code;this.http=http; }
        public long code() { return code; }
        public boolean httpStatus() { return http; }
    }
    public Map<String,Object> call(String module,String method,Map<String,Object> param,AccountCredentials account,RequestScope request) throws IOException {
        return call(module,method,param,account,Map.of(),request);
    }
    public Map<String,Object> call(String module,String method,Map<String,Object> param,AccountCredentials account,Map<String,Object> overrides,RequestScope request) throws IOException {
        Map<String,Object> comm=new LinkedHashMap<>(Map.of("uin","0","format","json","ct",24,"cv",0));
        if(account!=null) {
            if(account.platform()!=AccountPlatform.QQMUSIC) throw new IOException("Wrong QQ account");
            comm.put("uin",account.userId()); comm.put("qq",account.userId());
            comm.put("authst",cookie(account,"qqmusic_key"));
            String type=cookie(account,"tmeLoginType");
            if(!Set.of("1","2").contains(type)) throw new IOException("QQ login type missing");
            comm.put("tmeLoginType",Integer.parseInt(type));
        }
        comm.putAll(overrides);
        var response=exchange(GATEWAY,jsonBytes(Map.of("comm",comm,"req_0",Map.of("module",module,"method",method,"param",param))),
                account==null?"":account.header(GATEWAY),request,BoundedJson.MAX_BYTES);
        if(response.status()!=200) throw new Rejected(response.status(),true);
        var root=response.json(); var envelope=object(root.get("req_0"));
        for(Object code:List.of(root.getOrDefault("code",-1),envelope.getOrDefault("code",-1),envelope.getOrDefault("subcode",0)))
            if(number(code,-1)!=0) throw new Rejected(number(code,-1));
        var data=object(envelope.get("data"));
        for(Object code:List.of(data.getOrDefault("code",0),data.getOrDefault("subcode",0)))
            if(number(code,-1)!=0) throw new Rejected(number(code,-1));
        request.check(); if(account!=null && !account.isActive()) throw new IOException("QQ account retired");
        return data;
    }
    public static String cookie(AccountCredentials account,String name) throws IOException {
        return account.cookieSnapshot().stream().filter(e->e.name().equals(name)).map(e->e.value()).findFirst().orElse("");
    }
    public static Map<String,Object> object(Object value) throws IOException { return BoundedJson.object(value); }
    public static List<Object> array(Object value) throws IOException { return value==null?List.of():BoundedJson.array(value); }
    public static long number(Object value,long fallback) throws IOException {
        if(value==null) return fallback;
        if(value instanceof String text) try { return Long.parseLong(text); } catch(NumberFormatException ignored) { throw new IOException("Invalid QQ number"); }
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)return ((Number)value).longValue();
        return BoundedJson.integer(value);
    }
    public static String text(Map<String,Object> value,String... names) {
        for(String name:names) if(value.get(name) instanceof String s && !s.isBlank() && !s.equals("null"))
            return s.replaceAll("[\\p{Cntrl}]+"," ").strip();
        return "";
    }
    public static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    public static byte[] jsonBytes(Object value) {
        try {return BoundedJson.encode(value);}
        catch(IOException invalid) {throw new IllegalArgumentException("Invalid QQ JSON");}
    }
}
