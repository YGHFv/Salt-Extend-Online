package io.github.yghfv.saltneri.plugin.auth;

public enum AccountPlatform {
    BILIBILI("bilibili.com", "SESSDATA"), NETEASE("music.163.com", "MUSIC_U"), QQMUSIC("y.qq.com", "qqmusic_key"),
    APPLEMUSIC("music.apple.com", "media-user-token"), KUGOU("kugou.com", "kg_session");
    private final String domain, loginCookie;
    AccountPlatform(String domain, String loginCookie) { this.domain = domain; this.loginCookie = loginCookie; }
    public String domain() { return domain; }
    public String loginCookie() { return loginCookie; }
    public String label() { return switch(this) { case BILIBILI -> "Bilibili"; case NETEASE -> "\u7f51\u6613\u4e91"; case QQMUSIC -> "QQ \u97f3\u4e50"; case APPLEMUSIC -> "Apple Music"; case KUGOU -> "酷狗"; }; }
    public String configPrefix() { return switch(this) { case BILIBILI -> "bili"; case NETEASE -> "netease"; case QQMUSIC -> "qqmusic"; case APPLEMUSIC -> "applemusic"; case KUGOU -> "kugou"; }; }
    public java.net.URI accountUri() { return java.net.URI.create("https://" + (this == BILIBILI ? "api.bilibili.com" : domain) + "/"); }
    public boolean allows(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if(this==APPLEMUSIC)return lower.equals(domain);
        return lower.equals(domain) || lower.endsWith("." + domain);
    }
}
