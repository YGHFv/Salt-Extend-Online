package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Official-page login over private native pipes; no PowerShell or default browser context. */
final class WindowsEdgeWebLogin {
    private WindowsEdgeWebLogin() { }
    static byte[] login(AccountPlatform platform,RequestScope request) throws IOException {
        String url=platform==AccountPlatform.QQMUSIC?"https://y.qq.com/":"https://music.163.com/";
        try(var browser=WindowsEdgeProcess.open(request,false,url)) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(250);
            String target=null,session=null;byte[] stable=null;
            try {
                while(System.nanoTime()<deadline) {
                    request.check();
                    if(!browser.alive()) throw failure(AuthFailure.Reason.CANCELLED);
                    List<Map<String,Object>> pages=pages(browser);
                    Map<String,Object> page=null;
                    if(platform==AccountPlatform.QQMUSIC) {
                        page=pages.stream().filter(p->official(platform,p.get("url"))).findFirst().orElse(null);
                        if(pages.isEmpty()) throw failure(AuthFailure.Reason.CANCELLED);
                    } else {
                        for(var candidate:pages) if(target==null?official(platform,candidate.get("url")):target.equals(candidate.get("targetId"))) {page=candidate;break;}
                        if(target!=null && page==null) throw failure(AuthFailure.Reason.CANCELLED);
                        if(target==null && page!=null) {
                            target=BoundedJson.string(page.get("targetId"));
                            session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",target,"flatten",true)).get("sessionId"));
                        }
                    }
                    if(page!=null && official(platform,page.get("url"))) {
                        List<Object> cookies;
                        if(platform==AccountPlatform.QQMUSIC) cookies=qqCookies(browser);
                        else cookies=filterCookies(BoundedJson.array(browser.call("Network.getCookies",Map.of("urls",List.of(
                            "https://music.163.com/","https://music.163.com/weapi/w/nuser/account/get",
                            "https://interface.music.163.com/eapi/song/enhance/player/url/v1")),session).get("cookies")),platform);
                        if(cookies.size()>64) throw failure(AuthFailure.Reason.COOKIE);
                        boolean ready=ready(platform,cookies);
                        if(platform==AccountPlatform.QQMUSIC) {
                            byte[] snapshot=BoundedJson.encode(cookies);
                            boolean unchanged=stable!=null && Arrays.equals(stable,snapshot);
                            if(stable!=null) Arrays.fill(stable,(byte)0);
                            stable=snapshot;ready=ready && unchanged;
                        }
                        if(ready) return BoundedJson.encode(Map.of("cookies",cookies));
                    }
                    WindowsEdgeProcess.pause(1000);
                }
                throw failure(AuthFailure.Reason.TIMEOUT);
            } finally {if(stable!=null) Arrays.fill(stable,(byte)0);}
        }
    }
    static List<Map<String,Object>> pages(WindowsEdgeProcess browser) throws IOException {
        var result=new ArrayList<Map<String,Object>>();
        for(Object row:BoundedJson.array(browser.call("Target.getTargets",Map.of()).get("targetInfos"))) {
            var page=BoundedJson.object(row);if("page".equals(page.get("type"))) result.add(page);
        }
        return result;
    }
    static boolean official(AccountPlatform platform,Object value) {
        if(!(value instanceof String text)) return false;
        try {var url=URI.create(text);
            return "https".equals(url.getScheme()) && url.getUserInfo()==null && url.getPort()==-1 &&
                (platform==AccountPlatform.QQMUSIC?Set.of("y.qq.com","music.qq.com").contains(url.getHost()):"music.163.com".equals(url.getHost()));
        } catch(IllegalArgumentException error) {return false;}
    }
    static List<Object> qqCookies(WindowsEdgeProcess browser) throws IOException {
        return filterCookies(BoundedJson.array(browser.call("Storage.getCookies",Map.of()).get("cookies")),AccountPlatform.QQMUSIC);
    }
    static List<Object> filterCookies(List<Object> rows,AccountPlatform platform) throws IOException {
        if(rows.size()>4096) throw failure(AuthFailure.Reason.COOKIE);
        var result=new ArrayList<Object>();
        for(Object row:rows) {
            var cookie=BoundedJson.object(row);
            String domain=BoundedJson.string(cookie.get("domain")).replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
            boolean allowed=platform==AccountPlatform.QQMUSIC?
                QqMusicWebCookies.NAMES.contains(cookie.get("name")) &&
                    (Set.of("qq.com","music.qq.com","y.qq.com").contains(domain) || domain.endsWith(".y.qq.com")):
                domain.equals("music.163.com") || domain.endsWith(".music.163.com");
            if(allowed) result.add(cookie);
        }
        result.sort(Comparator.comparing(row->{
            var cookie=(Map<?,?>)row;
            return String.valueOf(cookie.get("name"))+"\n"+cookie.get("domain")+"\n"+cookie.get("path");
        }));
        return result;
    }
    static boolean ready(AccountPlatform platform,List<Object> cookies) throws IOException {
        boolean key=false,identity=false;
        for(Object row:cookies) {
            var cookie=BoundedJson.object(row);Object name=cookie.get("name");
            String value=BoundedJson.string(cookie.get("value"));
            if(platform==AccountPlatform.NETEASE) {if("MUSIC_U".equals(name) && !value.isEmpty()) return true;}
            else {
                if(Set.of("qqmusic_key","qm_keyst","musickey").contains(name) && !value.isEmpty()) key=true;
                if(Set.of("qqmusic_uin","uin","wxuin").contains(name) && value.matches("^(o0*)?[1-9][0-9]{0,18}$")) identity=true;
            }
        }
        return platform==AccountPlatform.QQMUSIC && key && identity;
    }
    static byte[] device(RequestScope request) throws IOException {
        try(var browser=WindowsEdgeProcess.open(request,true,null)) {
            var page=pages(browser).stream().filter(p->"about:blank".equals(p.get("url"))).findFirst().orElseThrow(()->failure(AuthFailure.Reason.DEVICE));
            String session=BoundedJson.string(browser.call("Target.attachToTarget",Map.of("targetId",page.get("targetId"),"flatten",true)).get("sessionId"));
            return new NeteaseDeviceCollector(new NeteaseDeviceCollector.Page() {
                public Map<String,Object> call(String method,Map<String,Object> params) throws IOException {
                    return browser.call(method,params,session);
                }
                public void pause(long milliseconds) {WindowsEdgeProcess.pause(milliseconds);}
            }).collect(request);
        }
    }
    private static AuthFailure failure(AuthFailure.Reason reason) {return AuthFailure.of(AuthFailure.Stage.NET_WEB,reason);}
}
