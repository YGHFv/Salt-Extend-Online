package io.github.yghfv.saltneri.plugin.host;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.platform.win32.*;
import java.util.*;

/** Register the identity Salt's MediaPlayer already uses, rather than changing a live session's ID. */
public final class WindowsMediaIdentity implements AutoCloseable {
    static final String DISPLAY_NAME="Salt Player for Windows";
    interface Port {
        String current();
        String read(String id,String name);
        void write(String id,String name,String value);
        void remove(String id,String name);
        void shortcut(String executable,String id,String title);
    }
    private final Port port;
    private record Value(String id,String name) { }
    private final Map<Value,String> inserted=new LinkedHashMap<>();
    private boolean closed;
    WindowsMediaIdentity(Port port, String executable) {
        this.port=port;
        if(executable==null || executable.isBlank() || !java.nio.file.Path.of(executable).isAbsolute())
            throw new IllegalStateException("Executable identity unavailable");
        String previous=port.current();
        // SMTCAdapter creates WinRT MediaPlayer (not an HWND session). Its implicit source is the exe basename.
        String id=previous==null || previous.isBlank()?java.nio.file.Path.of(executable).getFileName().toString():previous;
        if(!id.matches("[A-Za-z0-9 ._-]{1,128}")) throw new IllegalStateException("Unsupported application identity");
        try {
            insert(id,"DisplayName",DISPLAY_NAME);
            insert(id,"IconUri",executable);
            // The shell's app resolver needs a Start Menu link; AppUserModelId registry values alone are insufficient.
            port.shortcut(executable,id,DISPLAY_NAME);
        } catch(RuntimeException | LinkageError failure) { close(); throw failure; }
    }
    private void insert(String identity,String name,String value) { if(port.read(identity,name)==null) { port.write(identity,name,value); inserted.put(new Value(identity,name),value); } }
    public static WindowsMediaIdentity install() {
        if(!System.getProperty("os.name","").startsWith("Windows")) return null;
        return new WindowsMediaIdentity(new NativePort(),ProcessHandle.current().info().command().orElse(null));
    }
    @Override public void close() {
        if(closed) return; closed=true;
        inserted.forEach((key,value)->{ try { if(value.equals(port.read(key.id(),key.name()))) port.remove(key.id(),key.name()); } catch(RuntimeException | LinkageError ignored) { } });
    }
    private static final class NativePort implements Port {
        private final NativeLibrary shell=NativeLibrary.getInstance("shell32");
        private String key(String id) { return "Software\\Classes\\AppUserModelId\\"+id; }
        public String current() {
            PointerByReference result=new PointerByReference();
            int hr=shell.getFunction("GetCurrentProcessExplicitAppUserModelID").invokeInt(new Object[]{result});
            Pointer value=result.getValue(); if(hr<0 || value==null) return null;
            try { return value.getWideString(0); } finally { Ole32.INSTANCE.CoTaskMemFree(value); }
        }
        public void shortcut(String executable,String id,String title) {
            PointerByReference result=new PointerByReference();
            int hr=Shell32.INSTANCE.SHGetKnownFolderPath(KnownFolders.FOLDERID_Programs,0,null,result).intValue();
            if(hr<0 || result.getValue()==null) throw new IllegalStateException("Start Menu unavailable");
            java.nio.file.Path programs;
            try { programs=java.nio.file.Path.of(result.getValue().getWideString(0)); }
            finally { Ole32.INSTANCE.CoTaskMemFree(result.getValue()); }
            WindowsAppShortcut link=WindowsAppShortcut.install(programs,java.nio.file.Path.of(executable),id,title,
                    (path,created)->shell.getFunction("SHChangeNotify").invokeVoid(new Object[]{
                            created?0x00000002:0x00000004,0x0005,new WString(path.toString()),null}));
            // Salt can initialize SMTC before plugins. Keep the app record through restart so the next
            // session can resolve its name immediately, rather than recreating the startup race every time.
            link.preserve();
        }
        public String read(String id,String name) {
            if(!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER,key(id),name)) return null;
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER,key(id),name);
        }
        public void write(String id,String name,String value) {
            Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER,key(id));
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER,key(id),name,value);
        }
        public void remove(String id,String name) { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER,key(id),name); }
    }
}
