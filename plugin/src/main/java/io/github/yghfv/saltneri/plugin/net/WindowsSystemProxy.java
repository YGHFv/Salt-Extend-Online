package io.github.yghfv.saltneri.plugin.net;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.IOException;
import java.net.*;

/** Read the current user's explicit Windows proxy without changing process-wide settings. */
public final class WindowsSystemProxy {
    private static final String KEY="Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private WindowsSystemProxy() { }
    public static Proxy select(URI target) throws IOException {
        if(!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER,KEY,"ProxyEnable")
                || Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,KEY,"ProxyEnable")==0
                || !Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER,KEY,"ProxyServer"))return null;
        return SourceProxySettings.parse(Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER,KEY,"ProxyServer"),target.getScheme());
    }
}
