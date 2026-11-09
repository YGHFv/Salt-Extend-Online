package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** QQ/WeChat login and manual verification on the official page in a fresh, private Edge profile. */
public final class WindowsQqMusicWebLogin {
    private WindowsQqMusicWebLogin() { }
    public static AccountCredentials login(RequestScope request) throws IOException {
        var clock=Clock.systemUTC();
        try(var client=new QqMusicQrClient()) {
            return NativeWindowsLogin.login(AccountPlatform.QQMUSIC,request,(output,scope)-> {
                try { return verifyCookies(output,clock,client,scope); }
                finally { Arrays.fill(output,(byte)0); }
            });
        } catch(IOException failure) {
            if(failure instanceof AuthFailure safe && safe.getMessage().startsWith("NET_WEB/"))
                throw safe.atStage(AuthFailure.Stage.QQ_WEB);
            throw AuthFailure.wrap(AuthFailure.Stage.QQ_WEB,AuthFailure.Reason.RESPONSE,failure);
        }
    }
    static AccountCredentials verifyCookies(byte[] output,Clock clock,QrAuthClient client,RequestScope request) throws IOException {
        var unverified=QqMusicWebCookies.decode(output,clock);
        try {request.check();return client.verify(unverified,request);}
        finally {unverified.retire();}
    }
}
