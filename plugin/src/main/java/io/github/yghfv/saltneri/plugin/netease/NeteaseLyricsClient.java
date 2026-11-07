package io.github.yghfv.saltneri.plugin.netease;

/* New lyric endpoint/fields adapted from pinned NeriPlayer getLyricNew and
 * PlayerLyricsSupport. GPL-3.0-or-later. Transport and host conversion are local. */

import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.net.*;
import java.util.Map;

/** Called only for an owned playing item, never for catalog rows or at plugin startup. */
public final class NeteaseLyricsClient {
    @FunctionalInterface public interface Opener { HttpURLConnection open(URI uri, byte[] form) throws IOException; }
    private static final URI ENDPOINT = URI.create("https://interface.music.163.com" + NeteaseEapi.LYRIC_PATH);
    private final AccountCredentials account;
    private final Opener opener;
    public NeteaseLyricsClient(AccountCredentials account) { this(account, PinnedHttpsConnection::neteaseLyricPost); }
    public NeteaseLyricsClient(AccountCredentials account, Opener opener) {
        if (account != null && account.platform() != AccountPlatform.NETEASE) throw new IllegalArgumentException("Wrong lyric account");
        this.account=account; this.opener=opener;
    }
    private void check(RequestScope request) {
        request.check(); if(account!=null && !account.isActive()) throw new java.util.concurrent.CancellationException("Lyric account retired");
    }
    public String load(long songId, RequestScope request) throws IOException {
        if(songId<=0) throw new IllegalArgumentException("Invalid lyric song ID");
        check(request);
        byte[] form=NeteaseEapi.form(NeteaseEapi.LYRIC_PATH,Map.of("id",Long.toString(songId),"cp","false",
                "lv",0,"tv",1,"rv",0,"yv",1,"ytv",1,"yrv",0));
        var connection=opener.open(ENDPOINT,form); request.attach(connection);
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(4000); connection.setReadTimeout(4000); connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept","application/json"); connection.setRequestProperty("Accept-Encoding","identity");
            connection.setRequestProperty("Referer","https://music.163.com/");
            connection.setRequestProperty("User-Agent","Mozilla/5.0");
            if(account!=null) connection.setRequestProperty("Cookie",account.header(ENDPOINT));
            int status=connection.getResponseCode(); check(request);
            if(status!=200) throw new IOException("Lyrics unavailable (HTTP "+status+")");
            var root=JsonResponse.read(connection,request,true); check(request);
            if(BoundedJson.integer(root.get("code"))!=200) throw new IOException("Lyrics unavailable");
            String result=NeteaseLyricsText.convert(root); check(request); return result;
        } finally { request.release(connection); }
    }
}
