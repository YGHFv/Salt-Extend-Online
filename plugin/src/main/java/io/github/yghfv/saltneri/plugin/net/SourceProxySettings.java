package io.github.yghfv.saltneri.plugin.net;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.function.Function;

/** Plugin-local routing; direct requests never consult the JVM or Windows proxy configuration. */
public final class SourceProxySettings {
    public static final List<String> SOURCES=List.of("bili","netease","qqmusic","kugou","applemusic");
    private static volatile Set<String> enabled=Set.of();
    private SourceProxySettings() { }
    public static void configure(Function<String,String> config) {
        var next=new HashSet<String>();
        for(String source:SOURCES)if("system".equals(config.apply(source+".proxy")))next.add(source);
        enabled=Set.copyOf(next);
    }
    public static void reset() {enabled=Set.of();}
    public static String source(URI target) {
        String host=target.getHost();if(host==null)return "";host=host.toLowerCase(Locale.ROOT);
        for(String domain:List.of("bilibili.com","bilivideo.com","bilivideo.cn","hdslb.com","biliimg.com"))
            if(under(host,domain))return "bili";
        if(host.matches("[a-f0-9]{32}\\.v\\.smtcdns\\.com"))return "bili";
        if(under(host,"music.163.com") || under(host,"music.126.net"))return "netease";
        if(under(host,"qq.com") || under(host,"gtimg.cn") || under(host,"qpic.cn") || under(host,"qlogo.cn"))return "qqmusic";
        if(under(host,"kugou.com") || under(host,"kgimg.com") || host.equals("music.api.hoilai.cn"))return "kugou";
        if(under(host,"music.apple.com") || under(host,"mzstatic.com"))return "applemusic";
        return "";
    }
    private static boolean under(String host,String domain) {return host.equals(domain) || host.endsWith("."+domain);}
    public static boolean usesSystemProxy(URI target) {return enabled.contains(source(target));}
    public static Proxy proxy(URI target) throws IOException {
        if(!usesSystemProxy(target))return Proxy.NO_PROXY;
        ProxySelector selector=ProxySelector.getDefault();
        if(selector!=null)try {
            for(Proxy proxy:selector.select(target))if(proxy!=null && proxy.type()!=Proxy.Type.DIRECT)return proxy;
        } catch(RuntimeException failure) {throw new IOException("System proxy selection failed",failure);}
        if(System.getProperty("os.name","").startsWith("Windows"))try {
            Proxy proxy=(Proxy)Class.forName("io.github.yghfv.saltneri.plugin.net.WindowsSystemProxy")
                    .getMethod("select",URI.class).invoke(null,target);
            if(proxy!=null)return proxy;
        } catch(ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Windows system proxy unavailable",failure);
        }
        throw new IOException("此源已选择代理，请先在系统中配置 HTTP 或 SOCKS 代理");
    }
    static Proxy parse(String value,String scheme) throws IOException {
        String selected=null;
        for(String part:value.split(";")) {
            String[] pair=part.trim().split("=",2);
            if(pair.length==1 && selected==null)selected=pair[0];
            if(pair.length==2 && pair[0].equalsIgnoreCase(scheme)){selected=pair[1];break;}
        }
        if(selected==null || selected.isBlank())return null;
        try {
            URI uri=URI.create(selected.contains("://")?selected:"http://"+selected);
            boolean socks=Set.of("socks","socks5").contains(uri.getScheme());
            if(!socks && !"http".equals(uri.getScheme()) || uri.getHost()==null || uri.getPort()<1 || uri.getPort()>65535
                    || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null
                    || uri.getPath()!=null && !uri.getPath().isEmpty())throw new IllegalArgumentException();
            return new Proxy(socks?Proxy.Type.SOCKS:Proxy.Type.HTTP,new InetSocketAddress(uri.getHost(),uri.getPort()));
        } catch(IllegalArgumentException failure) {throw new IOException("Invalid system proxy address",failure);}
    }
}
