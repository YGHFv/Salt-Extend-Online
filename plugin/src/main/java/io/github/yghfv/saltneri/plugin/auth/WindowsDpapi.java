package io.github.yghfv.saltneri.plugin.auth;

import java.io.IOException;
import java.lang.reflect.*;

/** CurrentUser DPAPI through Salt's existing native Windows binding; no interpreter process. */
public final class WindowsDpapi implements EncryptedAccountStore.Protector {
    @Override public byte[] protect(byte[] plain) throws IOException { return run("protect", plain); }
    @Override public byte[] unprotect(byte[] cipher) throws IOException { return run("unprotect", cipher); }
    private static byte[] run(String mode, byte[] bytes) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows") || bytes.length > 65536 || Thread.currentThread().isInterrupted()) throw new IOException("Windows secure storage unavailable");
        try {
            byte[] output=(byte[])Class.forName("io.github.yghfv.saltneri.plugin.auth.WindowsNativeDpapi")
                .getDeclaredMethod("run",String.class,byte[].class).invoke(null,mode,bytes);
            if(Thread.currentThread().isInterrupted()) {
                java.util.Arrays.fill(output,(byte)0);throw new IOException("Windows secure storage operation cancelled");
            }
            return output;
        } catch(ReflectiveOperationException | LinkageError error) {
            throw new IOException("Windows secure storage operation failed");
        }
    }
}
