package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.net.URI;
import java.util.Set;

/** Public image hosts only. Audio URLs, arbitrary hosts and credential-bearing queries are excluded. */
public final class CoverSource {
    private CoverSource() { }
    /** Width-only Bilibili scaling preserves aspect ratio. NetEase keeps its original image URL. */
    public static URI thumbnail(URI source,int edge) {
        if(edge<1 || edge>1024) throw new IllegalArgumentException("Invalid thumbnail edge");
        URI original=original(source);if(original==null)return null;
        return parse(AccountPlatform.BILIBILI,original.toString())!=null?URI.create(original+"@"+edge+"w.webp"):original;
    }
    /** Remove only known CDN thumbnail transforms; never pass through arbitrary query parameters. */
    public static URI original(URI source) {
        if (source == null) return null;
        URI bili = parse(AccountPlatform.BILIBILI, source.toString());
        if(bili != null) return bili;
        URI net = parse(AccountPlatform.NETEASE, source.toString());
        if(net!=null)return net;
        URI qq=parse(AccountPlatform.QQMUSIC,source.toString());
        if(qq!=null)return qq;
        URI kugou=parse(AccountPlatform.KUGOU,source.toString());
        return kugou!=null?kugou:parse(AccountPlatform.APPLEMUSIC,source.toString());
    }
    /** The same QQ album identity on its static CDN, not an arbitrary redirect or signed URL. */
    public static URI playbackFallback(URI source) {
        URI original=original(source);
        if(original==null || !original.getHost().equals("y.qq.com")
                || !original.getPath().matches("/music/photo_new/T(002|062)R[0-9]{2,4}x[0-9]{2,4}M000[A-Za-z0-9]{14}\\.jpg")) return null;
        return URI.create("https://y.gtimg.cn"+original.getPath());
    }
    public static URI parse(AccountPlatform platform, String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return null;
        try {
            value = value.trim();
            URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
            if (uri.getScheme() == null || uri.getHost() == null || !Set.of("https", "http").contains(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getRawQuery() != null && !(platform == AccountPlatform.NETEASE && uri.getRawQuery().matches("param=[0-9]{1,4}y[0-9]{1,4}")
                        || platform == AccountPlatform.QQMUSIC && qqQuery(uri))
                    || uri.getPort() != -1 || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || !uri.getRawPath().matches("/[A-Za-z0-9/_.@%=-]+")) return null;
            boolean allowed = platform == AccountPlatform.BILIBILI
                    ? Set.of("i0.hdslb.com", "i1.hdslb.com", "i2.hdslb.com", "i3.hdslb.com", "i0.biliimg.com", "i1.biliimg.com", "i2.biliimg.com").contains(uri.getHost())
                    : platform == AccountPlatform.NETEASE
                        ? Set.of("p1.music.126.net", "p2.music.126.net", "p3.music.126.net", "p4.music.126.net").contains(uri.getHost())
                        : platform==AccountPlatform.KUGOU?Set.of("imge.kugou.com","singerimg.kugou.com","imgessl.kugou.com","c1.kgimg.com","c2.kgimg.com").contains(uri.getHost())
                        : platform==AccountPlatform.APPLEMUSIC?uri.getHost().matches("is[1-5]-ssl\\.mzstatic\\.com")
                        : Set.of("y.qq.com","qpic.y.qq.com","music-file.y.qq.com","y.gtimg.cn","p.qpic.cn","q.qlogo.cn","thirdqq.qlogo.cn").contains(uri.getHost());
            String path = uri.getRawPath();
            int at = path.lastIndexOf('@');
            if (platform == AccountPlatform.BILIBILI && at > path.lastIndexOf('/')) path = path.substring(0, at);
            String query=platform==AccountPlatform.QQMUSIC && uri.getHost().equals("q.qlogo.cn") && uri.getRawQuery()!=null
                    ? "?"+uri.getRawQuery():"";
            return allowed ? URI.create("https://" + uri.getHost() + path + query) : null;
        } catch (IllegalArgumentException error) { return null; }
    }
    private static boolean qqQuery(URI uri) {
        String query=uri.getRawQuery(),host=uri.getHost(),path=uri.getRawPath();
        if(host.equals("music-file.y.qq.com") && path.startsWith("/songlist/"))
            return query.matches("imageView2/[0-5]/w/[0-9]{1,4}/h/[0-9]{1,4}");
        if(Set.of("qpic.y.qq.com","p.qpic.cn").contains(host))
            return query.matches("(n=[01]|max_age=[0-9]{1,10})(&(n=[01]|max_age=[0-9]{1,10}))?");
        if(host.equals("y.gtimg.cn") && path.startsWith("/music/photo_new/"))
            return query.matches("max_age=[0-9]{1,10}");
        return host.equals("q.qlogo.cn") && path.equals("/headimg_dl")
                && query.matches("dst_uin=[1-9][0-9]{0,18}&spec=(40|64|100|140|640)(&img_type=jpg)?");
    }
}
