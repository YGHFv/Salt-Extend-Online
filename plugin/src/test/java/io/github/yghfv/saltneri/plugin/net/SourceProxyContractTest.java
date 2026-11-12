package io.github.yghfv.saltneri.plugin.net;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Local proxy handshakes and routing isolation; no platform requests or global configuration writes. */
public final class SourceProxyContractTest {
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("Proxy check "+checks);}
    @FunctionalInterface interface Action {void run() throws Exception;}
    private static void fails(Action action) throws Exception {
        try {action.run();throw new AssertionError("Expected proxy rejection");}
        catch(IOException expected) {checks++;}
    }
    private static String headers(InputStream input) throws IOException {
        var bytes=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1 && bytes.size()<32768) {
            bytes.write(value);
            if(bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))return bytes.toString(StandardCharsets.US_ASCII);
        }
        throw new IOException("Truncated fixture request");
    }
    private static void tunnel(boolean socks,boolean reject) throws Exception {
        var recorded=new AtomicReference<String>();var errors=new AtomicReference<Throwable>();
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            Thread peer=new Thread(()->{
                try(var socket=server.accept()) {
                    socket.setSoTimeout(3000);var input=socket.getInputStream();var output=socket.getOutputStream();
                    if(socks) {
                        check(Arrays.equals(input.readNBytes(3),new byte[]{5,1,0}));output.write(new byte[]{5,0});output.flush();
                        check(Arrays.equals(input.readNBytes(10),new byte[]{5,1,0,1,8,8,8,8,1,(byte)187}));
                        output.write(new byte[]{5,(byte)(reject?5:0),0,1,127,0,0,1,0,1});output.flush();
                    } else {
                        recorded.set(headers(input));
                        output.write((reject?"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n":
                                "HTTP/1.1 200 Connection Established\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
                    }
                    if(reject)return;
                    String request=headers(input);check(request.startsWith("GET /fixture HTTP/1.1\r\nHost: api.bilibili.com\r\n"));
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));output.flush();
                } catch(Throwable error) {errors.set(error);}
            },"local-proxy-fixture");peer.setDaemon(true);peer.start();
            Proxy proxy=new Proxy(socks?Proxy.Type.SOCKS:Proxy.Type.HTTP,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()));
            var connection=new PinnedHttpsConnection(URI.create("https://api.bilibili.com/fixture"),
                    h->new InetAddress[]{InetAddress.getByAddress(new byte[]{8,8,8,8})},new PinnedHttpsConnection.Sockets() {
                public Socket create(){return new Socket(Proxy.NO_PROXY);}
                public Socket secure(Socket socket,String host){check(host.equals("api.bilibili.com"));return socket;}
            },false,proxy);
            try {
                check(connection.usingProxy());
                if(reject)fails(connection::connect);
                else check(new String(connection.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).equals("ok"));
            } finally {connection.disconnect();}
            peer.join(5000);check(!peer.isAlive());if(errors.get()!=null)throw new AssertionError(errors.get());
            if(!socks)check(recorded.get().equals("CONNECT 8.8.8.8:443 HTTP/1.1\r\nHost: 8.8.8.8:443\r\n\r\n"));
        }
    }
    public static void main(String[] args) throws Exception {
        var old=ProxySelector.getDefault();
        try {
            Proxy fixed=new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",7890));
            ProxySelector.setDefault(new ProxySelector() {
                public List<Proxy> select(URI target){check(SourceProxySettings.usesSystemProxy(target));return List.of(fixed);}
                public void connectFailed(URI target,SocketAddress address,IOException failure){throw new AssertionError();}
            });
            var targets=Map.of("bili",URI.create("https://api.bilibili.com/"),"netease",URI.create("https://interface.music.163.com/"),
                    "qqmusic",URI.create("https://u.y.qq.com/"),"kugou",URI.create("https://music.api.hoilai.cn/"),"applemusic",URI.create("https://amp-api.music.apple.com/"));
            SourceProxySettings.reset();for(var target:targets.values())check(SourceProxySettings.proxy(target)==Proxy.NO_PROXY);
            for(String selected:SourceProxySettings.SOURCES) {
                SourceProxySettings.configure(key->key.equals(selected+".proxy")?"system":"direct");
                for(var entry:targets.entrySet())check(SourceProxySettings.proxy(entry.getValue())==(entry.getKey().equals(selected)?fixed:Proxy.NO_PROXY));
            }
            SourceProxySettings.configure(key->"system");
            for(String url:List.of("https://127.0.0.1/","http://localhost/","https://api.bilibili.com.evil.test/","https://evil.test/"))
                check(SourceProxySettings.proxy(URI.create(url))==Proxy.NO_PROXY);
            for(var entry:Map.of("bili",List.of("i0.hdslb.com","test.bilivideo.com","test.bilivideo.cn"),
                    "netease",List.of("p1.music.126.net","m7.music.126.net"),"qqmusic",List.of("isure.stream.qqmusic.qq.com","y.gtimg.cn","p.qpic.cn"),
                    "kugou",List.of("fs.youthandroid.kugou.com","c1.kgimg.com"),"applemusic",List.of("music.apple.com","is1-ssl.mzstatic.com")).entrySet())
                for(String host:entry.getValue())check(SourceProxySettings.source(URI.create("https://"+host)).equals(entry.getKey()));
            check(((InetSocketAddress)SourceProxySettings.parse("127.0.0.1:7890","https").address()).getPort()==7890);
            check(((InetSocketAddress)SourceProxySettings.parse("http=127.0.0.1:80;https=127.0.0.1:443","https").address()).getPort()==443);
            check(SourceProxySettings.parse("socks5://127.0.0.1:1080","https").type()==Proxy.Type.SOCKS);
            for(String invalid:List.of("user:secret@localhost:80","localhost:0","localhost:65536","https://localhost:80","localhost:80/path"))
                fails(()->SourceProxySettings.parse(invalid,"https"));
            tunnel(false,false);tunnel(true,false);tunnel(false,true);tunnel(true,true);
        } finally {SourceProxySettings.reset();ProxySelector.setDefault(old);}
        System.out.println("PASS: "+checks+" per-source direct/proxy routing, HTTP CONNECT, SOCKS5, rejection and isolation checks");
    }
}
