package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.applemusic.AppleMusicHttp;
import io.github.yghfv.saltneri.plugin.net.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** The local account identity is a token fingerprint; no Apple ID or password is collected. */
public final class AppleMusicAuthClient implements QrAuthClient {
    private final AppleMusicHttp http;
    private final Clock clock;
    private volatile boolean closed;
    public AppleMusicAuthClient() {this(new AppleMusicHttp(),Clock.systemUTC());}
    public AppleMusicAuthClient(AppleMusicHttp http,Clock clock) {this.http=http;this.clock=clock;}
    public Session create(RequestScope request) throws IOException {throw AppleMusicHttp.failure("WEB_LOGIN_REQUIRED");}
    public Poll poll(Session session,RequestScope request) throws IOException {throw AppleMusicHttp.failure("WEB_LOGIN_REQUIRED");}
    public static AccountCredentials fromToken(String token,Clock clock) throws IOException {
        if(token==null || !token.matches("[A-Za-z0-9._~+/=-]{16,4096}"))throw AppleMusicHttp.failure("CREDENTIAL");
        String id;
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            id=Long.toString((java.nio.ByteBuffer.wrap(digest).getLong()&Long.MAX_VALUE)|1L);
            Arrays.fill(digest,(byte)0);
        } catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
        return new AccountCredentials(AccountPlatform.APPLEMUSIC,id,List.of(new ScopedCookies.Entry("media-user-token",token,
                "music.apple.com","/",false,true,clock.instant().plusSeconds(30*86400L))),clock,
                new AccountCredentials.Profile("Apple Music",null));
    }
    public AccountCredentials verify(AccountCredentials saved,RequestScope request) throws IOException {
        if(closed || saved.platform()!=AccountPlatform.APPLEMUSIC || !saved.isActive())throw AppleMusicHttp.failure("LOGIN_REQUIRED");
        var rows=BoundedJson.array(http.get("/v1/me/storefront",saved,request).get("data"));
        if(rows.size()!=1)throw AppleMusicHttp.failure("STOREFRONT");
        String storefront=BoundedJson.string(BoundedJson.object(rows.getFirst()).get("id"));
        if(!storefront.matches("[a-z]{2}"))throw AppleMusicHttp.failure("STOREFRONT");
        var entries=new ArrayList<>(saved.cookieSnapshot());
        entries.removeIf(e->e.name().equals("storefront"));
        entries.add(new ScopedCookies.Entry("storefront",storefront,"music.apple.com","/",false,true,
                entries.getFirst().expiry()));
        request.check();if(closed || !saved.isActive())throw AppleMusicHttp.failure("SESSION_REVOKED");
        return new AccountCredentials(AccountPlatform.APPLEMUSIC,saved.userId(),entries,clock,
                new AccountCredentials.Profile("Apple Music · "+storefront.toUpperCase(Locale.ROOT),null),saved.userAgent());
    }
    public void close() {closed=true;}
}
