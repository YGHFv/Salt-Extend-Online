package io.github.yghfv.saltneri.plugin.auth;

public enum AccountPlatform {
    BILIBILI("bilibili.com", "SESSDATA"), NETEASE("music.163.com", "MUSIC_U");
    private final String domain, loginCookie;
    AccountPlatform(String domain, String loginCookie) { this.domain = domain; this.loginCookie = loginCookie; }
    public String domain() { return domain; }
    public String loginCookie() { return loginCookie; }
    public boolean allows(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.equals(domain) || lower.endsWith("." + domain);
    }
}
