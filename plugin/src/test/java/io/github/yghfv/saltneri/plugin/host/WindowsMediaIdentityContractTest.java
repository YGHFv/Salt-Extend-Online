package io.github.yghfv.saltneri.plugin.host;

import java.util.*;

public final class WindowsMediaIdentityContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Identity "+checks); }
    static final class Port implements WindowsMediaIdentity.Port {
        String id; Map<String,String> registry=new HashMap<>();
        String shortcutId,shortcutTitle,shortcutTarget; int registrations; boolean fail;
        public String current(){return id;}
        public String read(String id,String name){return registry.get(id+"/"+name);}
        public void write(String id,String name,String value){registry.put(id+"/"+name,value);}
        public void remove(String id,String name){registry.remove(id+"/"+name);}
        public void shortcut(String executable,String id,String title) {
            if(fail) throw new IllegalStateException("fixture shortcut failure");
            shortcutTarget=executable; shortcutId=id; shortcutTitle=title;
            registrations++;
        }
    }
    public static void main(String[] args) {
        String exe=java.nio.file.Path.of("Salt Player for Windows.exe").toAbsolutePath().toString();
        String implicit="Salt Player for Windows.exe";
        Port port=new Port();
        try(var identity=new WindowsMediaIdentity(port,exe)) {
            check(port.id==null);
            check(port.read(implicit,"DisplayName").equals("Salt Player for Windows"));
            check(port.read(implicit,"IconUri").equals(exe));
            check(port.shortcutId.equals(implicit));
            check(port.shortcutTitle.equals(WindowsMediaIdentity.DISPLAY_NAME));
            check(port.shortcutTarget.equals(exe));
        }
        check(port.id==null && port.registry.isEmpty() && port.registrations==1 && port.shortcutId.equals(implicit));
        port.id="Existing.Salt"; port.write(port.id,"DisplayName","Original Salt");
        try(var identity=new WindowsMediaIdentity(port,exe)) {
            check(port.id.equals("Existing.Salt") && port.read(port.id,"DisplayName").equals("Original Salt"));
            check(port.shortcutId.equals(port.id));
            check(port.read(implicit,"DisplayName")==null);
        }
        check(port.registry.size()==1 && port.id.equals("Existing.Salt"));
        port=new Port(); var identity=new WindowsMediaIdentity(port,exe); port.write(implicit,"DisplayName","Later owner"); port.id="Later.Salt"; identity.close(); identity.close();
        check(port.id.equals("Later.Salt") && port.registry.containsValue("Later owner") && port.registrations==1);
        port=new Port(); port.fail=true;
        try { new WindowsMediaIdentity(port,exe); throw new AssertionError("Expected failure"); }
        catch(IllegalStateException expected) { check(port.registry.isEmpty() && port.id==null); }
        port=new Port();
        for(String invalid:new String[]{null,"","relative.exe"}) {
            try { new WindowsMediaIdentity(port,invalid); throw new AssertionError("Expected invalid executable"); }
            catch(IllegalStateException expected) { check(port.registry.isEmpty() && port.shortcutId==null); }
        }
        System.out.println("PASS: "+checks+" Windows media identity/shortcut/rollback checks; fake registry, process identity unchanged");
    }
}
