package io.github.yghfv.saltneri.plugin.auth;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
public final class WindowsAppleMusicWebLogin {
    private WindowsAppleMusicWebLogin() {}
    public static AccountCredentials login(RequestScope request) throws IOException {
        return NativeWindowsLogin.login(AccountPlatform.APPLEMUSIC,request,(bytes,r)->{throw new IOException("APPLE/CAPTURE");});
    }
}
