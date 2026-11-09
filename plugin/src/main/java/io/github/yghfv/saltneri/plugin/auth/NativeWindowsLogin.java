package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.lang.reflect.*;

/** Late host-native binding keeps synthetic/offline auth contracts independent of JNA. */
final class NativeWindowsLogin {
    private NativeWindowsLogin() { }
    static byte[] login(AccountPlatform platform,RequestScope request) throws IOException {
        return invoke("login",new Class<?>[]{AccountPlatform.class,RequestScope.class},platform,request);
    }
    static byte[] device(RequestScope request) throws IOException {
        return invoke("device",new Class<?>[]{RequestScope.class},request);
    }
    private static byte[] invoke(String name,Class<?>[] types,Object... args) throws IOException {
        requestWindows();
        try {
            return (byte[])Class.forName("io.github.yghfv.saltneri.plugin.auth.WindowsEdgeWebLogin").getDeclaredMethod(name,types).invoke(null,args);
        } catch(InvocationTargetException error) {
            Throwable cause=error.getCause();
            if(cause instanceof IOException io) throw io;
            if(cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
            throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_LAUNCH);
        } catch(ReflectiveOperationException | LinkageError error) {
            throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_LAUNCH);
        }
    }
    private static void requestWindows() throws IOException {
        if(!System.getProperty("os.name","").startsWith("Windows")) throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_MISSING);
    }
}
