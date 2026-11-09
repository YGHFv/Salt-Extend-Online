package io.github.yghfv.saltneri.plugin.auth;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Same CurrentUser DPAPI entropy as earlier releases, without launching an interpreter. */
final class WindowsNativeDpapi {
    private WindowsNativeDpapi() { }
    static byte[] run(String mode,byte[] input) throws IOException {
        byte[] entropy="io.github.yghfv.saltneri.online/auth/v1".getBytes(StandardCharsets.UTF_8);
        try {
            return switch(mode) {
                case "protect" -> Crypt32Util.cryptProtectData(input,entropy,1,"",null);
                case "unprotect" -> Crypt32Util.cryptUnprotectData(input,entropy,1,null);
                default -> throw new IOException("Windows secure storage unavailable");
            };
        } catch(RuntimeException | LinkageError error) {throw new IOException("Windows secure storage operation failed");}
        finally {Arrays.fill(entropy,(byte)0);}
    }
}
