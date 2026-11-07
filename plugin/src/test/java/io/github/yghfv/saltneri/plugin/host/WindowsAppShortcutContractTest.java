package io.github.yghfv.saltneri.plugin.host;

import java.nio.file.*;
import java.util.*;

/** Uses real Shell Link COM in a temporary test directory, NOT the actual Start Menu or registry. */
public final class WindowsAppShortcutContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Shortcut "+checks); }
    public static void main(String[] args) throws Exception {
        if(!System.getProperty("os.name","").startsWith("Windows")) {
            System.out.println("SKIP: Windows Shell Link COM requires Windows"); return;
        }
        Path root=Files.createTempDirectory("salt-shortcut-contract-");
        Path directory=root.resolve("Salt Extend Online"), link=directory.resolve("Salt Player for Windows.lnk");
        Path executable=Path.of(System.getProperty("java.home"),"bin","java.exe");
        List<Boolean> notifications=new ArrayList<>();
        try {
            for(String id:List.of("Salt Player for Windows.exe","Existing.Salt")) {
                try(var registration=WindowsAppShortcut.install(root,executable,id,WindowsMediaIdentity.DISPLAY_NAME,
                        (path,created)->{check(path.equals(link)); notifications.add(created);})) {
                    check(Files.isRegularFile(link));
                    var actual=WindowsAppShortcut.read(link);
                    check(Path.of(actual.target()).equals(executable));
                    check(actual.id().equals(id));
                    check(actual.title().equals("Salt Player for Windows"));
                    check(Path.of(actual.icon()).equals(executable) && actual.iconIndex()==0);
                    byte[] original=Files.readAllBytes(link);
                    try(var duplicate=WindowsAppShortcut.install(root,executable,id,WindowsMediaIdentity.DISPLAY_NAME,(p,c)->{throw new AssertionError("Duplicate notification");})) {
                        check(Arrays.equals(original,Files.readAllBytes(link)));
                    }
                    check(Files.exists(link));
                    try { WindowsAppShortcut.install(root,executable,"Other.Salt",WindowsMediaIdentity.DISPLAY_NAME,(p,c)->{}); throw new AssertionError("Conflict overwritten"); }
                    catch(IllegalStateException expected) { check(Arrays.equals(original,Files.readAllBytes(link))); }
                }
                check(!Files.exists(directory));
            }
            check(notifications.equals(List.of(true,false,true,false)));
            // Normal app shutdown must retain the record BEFORE Salt creates its next SMTC session.
            var persistent=WindowsAppShortcut.install(root,executable,"Salt.exe","Salt",(p,c)->{});
            persistent.preserve(); persistent.close();
            check(Files.exists(link) && WindowsAppShortcut.read(link).id().equals("Salt.exe"));
            try(var restart=WindowsAppShortcut.install(root,executable,"Salt.exe","Salt",(p,c)->{throw new AssertionError("Startup rewrote link");})) {
                check(Files.exists(link));
            }
            check(Files.exists(link));
            Files.delete(link); Files.delete(directory);
            // Replacing our link later transfers ownership; stop must not delete the replacement or directory.
            var changed=WindowsAppShortcut.install(root,executable,"Salt.exe","Salt \u97f3\u4e50",(p,c)->{});
            check(WindowsAppShortcut.read(link).title().equals("Salt \u97f3\u4e50"));
            byte[] user={1,2,3}; Files.write(link,user);
            changed.close(); changed.close();
            check(Arrays.equals(user,Files.readAllBytes(link)));
            Files.delete(link); Files.delete(directory);
            // A occupied, non-link path must stay untouched, even when COM Load fails.
            Files.createDirectory(directory); Files.write(link,user);
            try { WindowsAppShortcut.install(root,executable,"Salt.exe","Salt",(p,c)->{}); throw new AssertionError("User file overwritten"); }
            catch(IllegalStateException expected) { check(Arrays.equals(user,Files.readAllBytes(link))); }
            Files.delete(link); Files.delete(directory);
            try { WindowsAppShortcut.install(root,executable,"Salt.exe","Salt",(p,c)->{throw new IllegalStateException("notify failure");}); throw new AssertionError("Expected notify failure"); }
            catch(IllegalStateException expected) { check(!Files.exists(directory)); }
        } finally {
            Files.deleteIfExists(link); Files.deleteIfExists(directory); Files.delete(root);
        }
        System.out.println("PASS: "+checks+" real Windows Shell Link persisted ID/title/target/icon/ownership checks; no Start Menu, registry or audio changes");
    }
}
