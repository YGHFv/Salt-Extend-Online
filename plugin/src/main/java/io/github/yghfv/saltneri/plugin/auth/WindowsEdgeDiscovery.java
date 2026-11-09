package io.github.yghfv.saltneri.plugin.auth;

import com.sun.jna.platform.win32.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.*;

/** Read-only installation discovery and mandatory Edge policy checks. */
final class WindowsEdgeDiscovery {
    interface Registry {List<Object> values(String path,String name) throws AuthFailure;}
    private WindowsEdgeDiscovery() { }
    static Path find() throws AuthFailure {
        var environment=new HashMap<>(System.getenv());
        // Shell folder registry values remain usable when launcher environment is incomplete.
        var directories=new ArrayList<String>();
        for(String name:List.of("ProgramFiles(x86)","ProgramFiles","ProgramW6432","LOCALAPPDATA")) directories.add(environment.get(name));
        var registry=nativeRegistry(false);
        for(Object value:registry.values("Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders","Local AppData"))
            if(value instanceof String text) directories.add(expand(text,environment));
        for(String value:List.of("ProgramFilesDir","ProgramFilesDir (x86)","ProgramW6432Dir"))
            for(Object entry:registry.values("Software\\Microsoft\\Windows\\CurrentVersion",value))
                if(entry instanceof String text) directories.add(expand(text,environment));
        for(String directory:directories) {
            if(directory==null || directory.isBlank()) continue;
            try {Path candidate=resolve(Path.of(directory,"Microsoft/Edge/Application/msedge.exe").toString(),environment,Files::isRegularFile);
                if(candidate!=null) return candidate;} catch(InvalidPathException ignored) { }
        }
        for(Object value:registry.values("Software\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe","")) {
            Path candidate=resolve(value,environment,Files::isRegularFile);if(candidate!=null) return candidate;
        }
        throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_MISSING);
    }
    static Path resolve(Object candidate,Map<String,String> environment,Predicate<Path> exists) {
        if(!(candidate instanceof String text) || text.isBlank() || text.chars().anyMatch(c->c<32)) return null;
        text=expand(text.trim(),environment);
        if(text.startsWith("\"") && text.endsWith("\"") && text.length()>2) text=text.substring(1,text.length()-1);
        if(text.contains("\"") || text.chars().anyMatch(c->c<32) || !text.matches("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+[\\\\/]).*")) return null;
        try {
            Path path=Path.of(text).toAbsolutePath().normalize();
            return path.getFileName().toString().equalsIgnoreCase("msedge.exe") && exists.test(path)?path:null;
        } catch(InvalidPathException | SecurityException error) {return null;}
    }
    static String expand(String value,Map<String,String> environment) {
        Matcher matcher=Pattern.compile("%([^%]+)%").matcher(value);StringBuilder result=new StringBuilder();
        while(matcher.find()) {
            String replacement=environment.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(matcher.group(1)))
                .map(Map.Entry::getValue).findFirst().orElse(matcher.group());
            matcher.appendReplacement(result,Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(result).toString();
    }
    static void assertPolicy() throws AuthFailure {assertPolicy(nativeRegistry(true));}
    static void assertPolicy(Registry registry) throws AuthFailure {
        String path="Software\\Policies\\Microsoft\\Edge";
        for(Object value:registry.values(path,"UserDataDir")) if(!String.valueOf(value).isBlank()) throw policy();
        for(Object value:registry.values(path,"RemoteDebuggingAllowed")) if(String.valueOf(value).equals("0")) throw policy();
        for(Object value:registry.values(path,"DeveloperToolsAvailability")) if(String.valueOf(value).equals("2")) throw policy();
    }
    private static Registry nativeRegistry(boolean required) {
        return (path,name)->{
            var results=new ArrayList<Object>();
            for(var hive:List.of(WinReg.HKEY_CURRENT_USER,WinReg.HKEY_LOCAL_MACHINE))
                for(int view:new int[]{0x100,0x200}) {
                    try {Object value=Advapi32Util.registryGetValues(hive,path,view).get(name);if(value!=null) results.add(value);}
                    catch(Win32Exception error) {
                        if(error.getErrorCode()!=2 && error.getErrorCode()!=3 && required) throw policy();
                    }
                }
            return results;
        };
    }
    private static AuthFailure policy() {return AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_POLICY);}
}
