package io.github.yghfv.saltneri.plugin.qqmusic;

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.github.yghfv.saltneri.plugin.qqmusic.QqMusicHttp.*;

/** Independent synthetic modified-DES vectors, parser and bounded official endpoint fixtures. */
public final class QqMusicLyricsContractTest {
    public static final String WORDS="ee7befb746f93831d42dcdf2e21f0d5d977de00c85449a7c535da3bcf1beb3ca54d5d801d40005b7ab0043b5a1f982c2f526803b7338b600d4c244d8b2fd8db7d2e49282eb6f2c05f59225634e42f290ba1a90fc00a3d90c200a5cfc63ad27474f8f4d45ef36b1ac";
    public static final String TRANS="32dabb4c5e9846faf3899c1a0e465b1306d2671a8eb879451fc9781ca7686a87282d0e4a6494dda8bdb5c30688cc5ff6";
    private static final String LRC="32dabb4c5e9846fad1795322c6ddac25415122be3aaf7a4866db60ea327aa1df406e744a3ea1c78e";
    private static final String MID="00123456789abc";
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("QQ lyrics "+checks);}
    @FunctionalInterface private interface Action {void run()throws Exception;}
    private static void fails(Action action)throws Exception {
        try {action.run();throw new AssertionError("Expected lyric rejection");}
        catch(IOException | java.util.concurrent.CancellationException error) {check(!String.valueOf(error.getMessage()).contains("SECRET"));}
    }
    private static final class Response extends HttpURLConnection {
        final byte[] bytes;boolean disconnected;
        Response(URI uri,Map<String,Object> payload)throws IOException {super(uri.toURL());bytes=jsonBytes(payload);}
        public void connect() { }
        public int getResponseCode() {return 200;}
        public long getContentLengthLong() {return bytes.length;}
        public InputStream getInputStream() {return new ByteArrayInputStream(bytes);}
        public void disconnect() {disconnected=true;}
        public boolean usingProxy() {return false;}
    }
    private static final class Fixture implements Opener {
        final List<Map<String,Object>> params=new ArrayList<>();
        final List<Response> requests=new ArrayList<>();
        int denied;String original=WORDS,translation=TRANS;boolean brokenOrdinary,retire;
        AccountCredentials account;
        public HttpURLConnection open(URI uri,byte[] body)throws IOException {
            Map<String,Object> result;
            if(body==null) {
                check(uri.getPath().equals("/lyric/fcgi-bin/fcg_query_lyric_new.fcg") && uri.getRawQuery().contains(MID));
                result=Map.of("code",0,"lyric",base64("[00:01.00]Legacy\n"),"trans","");
            } else {
                var root=object(BoundedJson.parse(body));var req=object(root.get("req_0"));var param=object(req.get("param"));params.add(param);
                check(text(req,"method").equals("GetPlayLyricInfo") && MID.equals(param.get("songMID")));
                boolean crypt=number(param.get("crypt"),0)==1;
                result=Map.of("code",0,"req_0",Map.of("code",denied,"data",Map.of(
                        "lyric",crypt?original:brokenOrdinary?"broken":base64("[00:01.00]Ordinary\n"),
                        "trans",crypt?translation:base64("[00:01.00]Recovered translation\n"))));
            }
            if(retire && account!=null)account.retire();
            var response=new Response(uri,result);requests.add(response);return response;
        }
        String load()throws IOException {
            try(var request=new RequestScope(()->account==null || account.isActive())) {
                return new QqMusicLyricsClient(account,new QqMusicHttp(this)).lyrics(QqMusicTarget.parse(MID),request);
            }
        }
    }
    private static String base64(String text) {return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));}
    public static String nativeFixture()throws IOException {
        try(var r=new RequestScope(()->true)) {return QqMusicLyricsText.convert(QqMusicQrcDecoder.decode(WORDS,r),QqMusicQrcDecoder.decode(TRANS,r));}
    }
    public static void main(String[] args)throws Exception {
        try(var request=new RequestScope(()->true)) {
            var decoded=QqMusicQrcDecoder.decode(WORDS,request);
            check(QqMusicLyricsText.hasWords(decoded));
            check(decoded.contains("One(1000,400) Two(1600,700)"));
            check(QqMusicQrcDecoder.decode("",request).isEmpty());
            check(QqMusicQrcDecoder.decode(LRC,request).contains("Fallback"));
            for(String invalid:List.of("nothex","00","0000000000000000",WORDS.substring(0,100),WORDS.substring(0,72)+"0"+WORDS.substring(73),"00".repeat(262145)))
                fails(()->QqMusicQrcDecoder.decode(invalid,request));
        }
        String nativeText=nativeFixture();
        check(nativeText.contains("<00:01.000>One<00:01.400><00:01.600> Two<00:02.300><00:02.800>"));
        check(nativeText.contains("[00:01.000]Translation") && nativeText.contains("[00:04.000]Last translation"));
        check(QqMusicLyricsText.convert("[offset:50]\n[1000,1200]A(1000,200)B(1400,400)","[offset:50]\n[00:01.00]T")
                .contains("[00:01.050]<00:01.050>A<00:01.250><00:01.450>B<00:01.850><00:02.250>"));
        check(QqMusicLyricsText.convert("[29264,1000]A(29264,390)B(29654,610)","[00:29.26]Aligned")
                .contains("[00:29.264]Aligned"));
        String entities=QqMusicLyricsText.convert("<Lyric_1 LyricContent=\"[1000,1200]I(1000,200) (1200,100)&amp;(1300,300)((1700,100)you(1800,200))(2000,100)\"/>","");
        check(entities.contains("> <") && entities.contains(">&<") && entities.contains(">(<") && entities.contains(">)<"));
        check(!QqMusicLyricsText.hasWords("[1000,500]A(1000,500)unfinished"));
        check(!QqMusicLyricsText.hasWords("[1000,500]A(1200,300)B(1000,100)"));
        check(!QqMusicLyricsText.hasWords("[1000,500]A"));
        check(QqMusicLyricsText.convert("[00:01.00]Ordinary","[00:01.00]//")==null ||
                !QqMusicLyricsText.convert("[00:01.00]Ordinary","[00:01.00]//").contains("//"));
        check(QqMusicLyricsText.convert("[00:00:00]\u6b64\u6b4c\u66f2\u4e3a\u6ca1\u6709\u586b\u8bcd\u7684\u7eaf\u97f3\u4e50","")==null);
        for(String invalid:List.of("<!DOCTYPE x><Lyric_1 LyricContent=\"[1000,500]A(1000,500)\"/>",
                "<Lyric_1 LyricContent=\"[1000,500]&unknown;(1000,500)\"/>","\0","A".repeat(262145)))
            fails(()->QqMusicLyricsText.convert(invalid,""));
        var f=new Fixture();check(f.load().equals(nativeText));
        check(number(f.params.getFirst().get("qrc"),0)==1 && number(f.params.getFirst().get("crypt"),0)==1);
        check(f.requests.size()==1 && f.requests.getFirst().disconnected && f.requests.getFirst().getRequestProperty("Cookie")==null);
        f=new Fixture();f.translation="corrupt";String recovered=f.load();
        check(recovered.contains("<00:01.000>One") && recovered.contains("Recovered translation") && f.requests.size()==2);
        f=new Fixture();f.translation="<!DOCTYPE x>";recovered=f.load();
        check(recovered.contains("<00:01.000>One") && f.requests.size()==2);
        f=new Fixture();f.original="broken";check(f.load().contains("Ordinary") && f.requests.size()==2);
        f=new Fixture();f.original="broken";f.brokenOrdinary=true;check(f.load().contains("Legacy") && f.requests.size()==3);
        f=new Fixture();f.original=LRC;check(f.load().contains("Fallback") && !f.load().contains("<00:"));
        f=new Fixture();f.denied=1000;var denied=f;fails(denied::load);check(f.requests.size()==1);
        f=new Fixture();f.account=new AccountCredentials(AccountPlatform.QQMUSIC,"42",List.of(
                new ScopedCookies.Entry("qqmusic_key","KEY_SECRET","y.qq.com","/",false,true,CLOCK.instant().plusSeconds(3600)),
                new ScopedCookies.Entry("tmeLoginType","2","y.qq.com","/",false,true,CLOCK.instant().plusSeconds(3600))),CLOCK);
        check(f.load().equals(nativeText) && f.requests.getFirst().getRequestProperty("Cookie").contains("KEY_SECRET"));
        f.retire=true;var revoked=f;fails(revoked::load);check(f.requests.size()==2);
        try(var r=new RequestScope(()->false)) {fails(()->QqMusicQrcDecoder.decode(WORDS,r));}
        System.out.println("PASS: "+checks+" QQ QRC modified-DES/absolute words/gaps/XML/offset/translation/fallback/bounds/account checks; synthetic only");
    }
}
