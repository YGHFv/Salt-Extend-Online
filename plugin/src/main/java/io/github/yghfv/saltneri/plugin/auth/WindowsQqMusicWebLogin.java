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
        byte[] output=null;
        try {
            output=NativeWindowsLogin.login(AccountPlatform.QQMUSIC,request);
            try(var client=new QqMusicQrClient()) {return verifyCookies(output,clock,client,request);}
        } catch(IOException failure) {
            if(failure instanceof AuthFailure safe && safe.getMessage().startsWith("NET_WEB/"))
                throw safe.atStage(AuthFailure.Stage.QQ_WEB);
            throw AuthFailure.wrap(AuthFailure.Stage.QQ_WEB,AuthFailure.Reason.RESPONSE,failure);
        } finally {if(output!=null)Arrays.fill(output,(byte)0);}
    }
    static AccountCredentials verifyCookies(byte[] output,Clock clock,QrAuthClient client,RequestScope request) throws IOException {
        var unverified=QqMusicWebCookies.decode(output,clock);
        try {request.check();return client.verify(unverified,request);}
        finally {unverified.retire();}
    }
}
