package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.nio.file.*;
import java.util.*;

/** Explicit anonymous official-page probe: no QR creation, login, saved account or token output. */
public final class WindowsNeteaseDeviceProbe {
    private static Set<Path> profiles() throws Exception {
        var result=new HashSet<Path>();
        try(var entries=Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),"salt-edge-web-*")) {entries.forEach(result::add);}
        return result;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !args[0].equals("--explicit-public-device"))throw new IllegalArgumentException("Explicit anonymous device probe required");
        Set<Path> before=profiles();
        for(int run=0;run<3;run++) {
            try(var request=new RequestScope(()->true,90000)) {
                var snapshot=NeteaseDeviceSnapshot.load(request);
                if(snapshot.token().isEmpty() || snapshot.deviceId().isEmpty() || snapshot.cookies().stream()
                    .anyMatch(c->c.name().equals("MUSIC_U") || !AccountPlatform.NETEASE.allows(c.domain())))
                    throw new AssertionError("Official anonymous device context not isolated");
            }
            if(!profiles().equals(before))throw new AssertionError("Official device profile not cleaned");
            System.out.println("PASS: official anonymous device preparation "+(run+1)+"/3; valid token/device/cookie scope, no QR/account/secret output, private profile cleaned");
        }
    }
}
