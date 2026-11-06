package io.github.yghfv.saltneri.plugin.host;

import java.util.*;

public final class WindowsMediaIdentityContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Identity "+checks); }
    static final class Port implements WindowsMediaIdentity.Port {
        String id; Map<String,String> registry=new HashMap<>();
        public String current(){return id;} public void current(String value){id=value;}
        public String read(String id,String name){return registry.get(id+"/"+name);}
        public void write(String id,String name,String value){registry.put(id+"/"+name,value);}
        public void remove(String id,String name){registry.remove(id+"/"+name);}
    }
    public static void main(String[] args) {
        Port port=new Port();
        try(var identity=new WindowsMediaIdentity(port,"C:\\Salt\\Salt.exe")) {
            check(port.id.equals(WindowsMediaIdentity.FALLBACK_ID));
            check(port.read(port.id,"DisplayName").equals("Salt Player for Windows"));
            check(port.read(port.id,"IconUri").endsWith("Salt.exe"));
            check(port.read("Salt.exe","DisplayName").equals("Salt Player for Windows"));
        }
        check(port.id==null && port.registry.isEmpty());
        port.id="Existing.Salt"; port.write(port.id,"DisplayName","Original Salt");
        try(var identity=new WindowsMediaIdentity(port,null)) { check(port.id.equals("Existing.Salt") && port.read(port.id,"DisplayName").equals("Original Salt")); }
        check(port.registry.size()==1 && port.id.equals("Existing.Salt"));
        port=new Port(); var identity=new WindowsMediaIdentity(port,null); port.write(port.id,"DisplayName","Later owner"); port.id="Later.Salt"; identity.close(); identity.close();
        check(port.id.equals("Later.Salt") && port.registry.containsValue("Later owner"));
        System.out.println("PASS: "+checks+" Windows media identity registration/ownership/restore checks; fake registry, no system changes");
    }
}
