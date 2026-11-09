package io.github.yghfv.saltneri.plugin.auth;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.nio.file.*;
import java.util.*;

/** Discovery/policy/cookie/origin fixtures; no browser, account or real registry changes. */
public final class NativeWebLoginContractTest {
    private static int checks;
    private static void check(boolean value) {checks++;if(!value)throw new AssertionError("Native login check "+checks);}
    public static void main(String[] args) throws Exception {
        String path="C:\\Synthetic space \u6d4b\u8bd5 quote'\\msedge.exe";
        check(WindowsEdgeDiscovery.resolve(path,Map.of(),p->true).equals(Path.of(path)));
        check(WindowsEdgeDiscovery.resolve("\""+path+"\"",Map.of(),p->true).equals(Path.of(path)));
        check(WindowsEdgeDiscovery.resolve("%EDGE%\\msedge.exe",Map.of("edge","C:\\fixture"),p->true).equals(Path.of("C:\\fixture\\msedge.exe")));
        for(String invalid:List.of("","msedge.exe","C:msedge.exe","\\msedge.exe",path+" --flag","\""+path+"\" --flag",
            "C:\\fixture\\not-edge.exe",path+"\n")) check(WindowsEdgeDiscovery.resolve(invalid,Map.of(),p->true)==null);
        check(WindowsEdgeDiscovery.resolve(path,Map.of(),p->false)==null);
        for(String name:List.of("UserDataDir","RemoteDebuggingAllowed","DeveloperToolsAvailability")) {
            Object value=name.equals("UserDataDir")?"C:\\not-our-profile":name.equals("RemoteDebuggingAllowed")?0:2;
            try {WindowsEdgeDiscovery.assertPolicy((p,n)->n.equals(name)?List.of(value):List.of());throw new AssertionError("Policy ignored");}
            catch(AuthFailure safe) {check(safe.getMessage().equals("NET_WEB/BROWSER_POLICY"));}
        }
        WindowsEdgeDiscovery.assertPolicy((p,n)->n.equals("RemoteDebuggingAllowed")?List.of(1):List.of());check(true);
        try {WindowsEdgeDiscovery.assertPolicy((p,n)->{throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_POLICY);});throw new AssertionError("Policy read error ignored");}
        catch(AuthFailure safe) {check(safe.getMessage().equals("NET_WEB/BROWSER_POLICY"));}
        check(WindowsEdgeProcess.quote("space path").equals("\"space path\""));
        check(WindowsEdgeProcess.quote("C:\\end\\").equals("\"C:\\end\\\\\""));
        check(WindowsEdgeProcess.quote("a\"b").equals("\"a\\\"b\""));
        for(int pointerSize:List.of(4,8)) {
            byte[] bytes=WindowsEdgeProcess.inheritedDescriptors(pointerSize,11,22,33);
            var descriptor=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            check(bytes.length==9+5*pointerSize && descriptor.getInt()==5);
            for(byte flag:new byte[]{0x41,0x41,0x41,0x09,0x09})check(descriptor.get()==flag);
            for(long handle:new long[]{11,11,11,22,33})
                check((pointerSize==8?descriptor.getLong():Integer.toUnsignedLong(descriptor.getInt()))==handle);
        }
        for(var platform:List.of(AccountPlatform.NETEASE,AccountPlatform.QQMUSIC)) {
            String official=platform==AccountPlatform.NETEASE?"https://music.163.com/":"https://y.qq.com/";
            check(WindowsEdgeWebLogin.official(platform,official));
            for(String invalid:List.of(official.replace("https","http"),official.replace(".com",".com.evil"),official.replace("https://","https://user@"),
                    official.replace(".com/",".com:444/"),"https://evil.invalid/")) check(!WindowsEdgeWebLogin.official(platform,invalid));
        }
        var cookies=new ArrayList<Object>();
        cookies.add(Map.of("name","qm_keyst","value","SYNTHETIC","domain","y.qq.com","path","/"));
        cookies.add(Map.of("name","uin","value","o00042","domain",".qq.com","path","/"));
        cookies.add(Map.of("name","p_skey","value","NOT_IMPORTED","domain",".qq.com","path","/"));
        cookies.add(Map.of("name","tracking","value","NOT_IMPORTED","domain","y.qq.com","path","/"));
        cookies.add(Map.of("name","qqmusic_key","value","NOT_IMPORTED","domain","y.qq.com.evil","path","/"));
        var filtered=WindowsEdgeWebLogin.filterCookies(cookies,AccountPlatform.QQMUSIC);
        check(filtered.size()==2 && !WindowsEdgeWebLogin.ready(AccountPlatform.QQMUSIC,filtered));
        filtered.add(Map.of("name","tmeLoginType","value","2","domain","y.qq.com","path","/"));
        check(WindowsEdgeWebLogin.ready(AccountPlatform.QQMUSIC,filtered));
        var inconsistent=new ArrayList<>(filtered);
        inconsistent.add(Map.of("name","login_type","value","2","domain","y.qq.com","path","/"));
        check(!WindowsEdgeWebLogin.ready(AccountPlatform.QQMUSIC,inconsistent));
        check(!WindowsEdgeWebLogin.ready(AccountPlatform.QQMUSIC,List.of(filtered.getFirst())));
        check(WindowsEdgeWebLogin.filterCookies(cookies,AccountPlatform.NETEASE).isEmpty());
        var parsed=BoundedJson.parse(BoundedJson.encode(Map.of("cookies",filtered)));
        check(BoundedJson.object(parsed).containsKey("cookies"));
        System.out.println("PASS: "+checks+" native login installation/path/policy/quoting/origin/QQ-cookie/redaction fixtures; no browser/network/account");
    }
}
