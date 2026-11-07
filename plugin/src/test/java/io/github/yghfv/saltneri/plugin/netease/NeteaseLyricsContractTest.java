package io.github.yghfv.saltneri.plugin.netease;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NeteaseLyricsContractTest {
    private static int checks;
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("Lyric check "+checks); }
    public static Map<String,Object> fixture() {
        return Map.of("code",200,"yrc",Map.of("lyric","[1000,1500](1000,400,0)One(1600,900,0)Two\n[3000,500](3000,500,0)End"),
                "lrc",Map.of("lyric","[00:01.000]Fallback\n[00:03.000]End"),
                "ytlrc",Map.of("lyric","[00:01.000]Translation\n[00:03.000]Final translation"));
    }
    static class Response extends HttpURLConnection {
        String json; int status=200; String type="application/json"; boolean closed;
        Response(URI uri,String json) throws IOException { super(uri.toURL()); this.json=json; }
        public void connect() { }
        public int getResponseCode() { return status; }
        public String getHeaderField(String key) { return key.equalsIgnoreCase("Content-Type")?type:null; }
        public InputStream getInputStream() { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }
        public void disconnect() { closed=true; }
        public boolean usingProxy() { return false; }
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--explicit-public-lyrics")) {
            try(var scope=new RequestScope(()->true,6000)) {
                String text=new NeteaseLyricsClient(null).load(347230,scope);
                check(text!=null && text.contains("["));
                System.out.println("PASS: public NetEase new lyric endpoint; nonempty timed text; no lyrics/accounts/audio printed");
            }
            return;
        }
        String converted=NeteaseLyricsText.convert(fixture());
        check(converted.contains("[00:01.000]<00:01.000>One<00:01.400><00:01.600>Two<00:02.500>"));
        check(converted.contains("[00:01.000]Translation") && !converted.contains("Fallback"));
        check(NeteaseLyricsText.convert(Map.of("code",200,"nolyric",true))==null);
        check(NeteaseLyricsText.convert(Map.of("code",200,"uncollected",true))==null);
        check(NeteaseLyricsText.convert(Map.of("lrc",Map.of("lyric","[00:01:50][00:02.123]Test\r\n"))).equals("[00:01.500]Test\n[00:02.123]Test\n"));
        check(NeteaseLyricsText.convert(Map.of("lrc",Map.of("lyric","[offset:100]\n[00:01.00]Test"))).startsWith("[00:01.100]"));
        for(String yrc:List.of(" ","[1000,500](1000,900,0)Broken","[1000,500]Broken","[1000,500](900,200,0)Broken")) {
            check(NeteaseLyricsText.convert(Map.of("yrc",Map.of("lyric",yrc),"lrc",Map.of("lyric","[00:01.000]Safe"))).equals("[00:01.000]Safe\n"));
        }
        check(NeteaseLyricsText.convert(Map.of("lrc",Map.of("lyric","[00:01]Original"),"tlyric",Map.of("lyric","[00:02]Wrong timing"))).equals("[00:01.000]Original\n"));
        try { NeteaseLyricsText.convert(Map.of("lrc",Map.of("lyric","x".repeat(NeteaseLyricsText.MAX_CHARS+1)))); throw new AssertionError(); }
        catch(IOException expected) { checks++; }
        var clock=Clock.systemUTC(); var cookies=new ScopedCookies(AccountPlatform.NETEASE,clock);
        cookies.accept(URI.create("https://music.163.com/"),List.of("MUSIC_U=FIXTURE_ONLY; Domain=.music.163.com; Path=/; Secure; Max-Age=3600"));
        var account=new AccountCredentials(AccountPlatform.NETEASE,"42",cookies.snapshot(),clock);
        var responses=new ArrayList<Response>(); var active=new AtomicBoolean(true);
        NeteaseLyricsClient.Opener opener=(uri,form)->{
            check(uri.toString().equals("https://interface.music.163.com/eapi/song/lyric/v1"));
            try {
            var cipher=javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII),"AES"));
            String payload=new String(cipher.doFinal(HexFormat.of().parseHex(new String(form,StandardCharsets.US_ASCII).substring(7))),StandardCharsets.UTF_8);
            check(payload.startsWith("/api/song/lyric/v1-36cd479b6b5-") && payload.contains("\"id\":\"123\"") && payload.contains("\"yv\":1"));
            } catch(java.security.GeneralSecurityException error) { throw new IOException(error); }
            var response=new Response(uri,new String(BoundedJson.encode(fixture()),StandardCharsets.UTF_8)); responses.add(response); return response;
        };
        try {
            try(var scope=new RequestScope(active::get)) {
                check(new NeteaseLyricsClient(account,opener).load(123,scope).equals(converted));
                check(responses.getLast().closed && responses.getLast().getRequestProperty("Cookie").contains("MUSIC_U=FIXTURE_ONLY"));
                check(!responses.getLast().getInstanceFollowRedirects());
                new NeteaseLyricsClient(null,opener).load(123,scope);
                check(responses.getLast().getRequestProperty("Cookie")==null);
            }
            for(String bad:List.of("{\"code\":301}","{\"code\":200,\"lrc\":{\"lyric\":\"\\u0000\"}}")) {
                try(var scope=new RequestScope(active::get)) {
                    try { new NeteaseLyricsClient(null,(uri,form)->new Response(uri,bad)).load(123,scope); throw new AssertionError(); }
                    catch(IOException expected) { checks++; }
                }
            }
            try(var scope=new RequestScope(active::get)) {
                try { new NeteaseLyricsClient(null,(uri,form)->{ var r=new Response(uri,"{}"); r.status=302; return r; }).load(123,scope); throw new AssertionError(); }
                catch(IOException expected) { checks++; }
                account.retire(); int count=responses.size();
                try { new NeteaseLyricsClient(account,opener).load(123,scope); throw new AssertionError(); }
                catch(java.util.concurrent.CancellationException expected) { check(responses.size()==count); }
                active.set(false);
                try { new NeteaseLyricsClient(null,opener).load(123,scope); throw new AssertionError(); }
                catch(java.util.concurrent.CancellationException expected) { check(responses.size()==count); }
            }
        } finally { account.retire(); }
        for(String uri:List.of("https://music.163.com/eapi/song/lyric/v1","https://interface.music.163.com/eapi/song/lyric/v1?other=1","https://interface.music.163.com/elsewhere")) {
            try { PinnedHttpsConnection.neteaseLyricPost(URI.create(uri),"params=A".getBytes(StandardCharsets.US_ASCII)); throw new AssertionError(); }
            catch(IOException expected) { checks++; }
        }
        System.out.println("PASS: "+checks+" NetEase EAPI lyric, YRC/LRC/translation/fallback/identity/bounds checks; synthetic only");
    }
}
