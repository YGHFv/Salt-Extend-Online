package io.github.yghfv.saltneri.plugin.host;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.platform.win32.*;
import java.util.*;

/** Give unpackaged SMTC a resolvable app identity, without changing the executable or machine registry. */
public final class WindowsMediaIdentity implements AutoCloseable {
    static final String FALLBACK_ID="SaltExtendOnline.SaltPlayer";
    static final String DISPLAY_NAME="Salt Player for Windows";
    interface Port {
        String current();
        void current(String id);
        String read(String id,String name);
        void write(String id,String name,String value);
        void remove(String id,String name);
    }
    private final Port port;
    private final String previous, id;
    private record Value(String id,String name) { }
    private final Map<Value,String> inserted=new LinkedHashMap<>();
    private boolean closed;
    WindowsMediaIdentity(Port port, String executable) {
        this.port=port; previous=port.current(); id=previous==null || previous.isBlank()?FALLBACK_ID:previous;
        if(!id.matches("[A-Za-z0-9 ._-]{1,128}")) throw new IllegalStateException("Unsupported application identity");
        try {
            insert(id,"DisplayName",DISPLAY_NAME);
            if(executable!=null && !executable.isBlank()) {
                insert(id,"IconUri",executable);
                // Existing Salt SMTC sessions identify as the executable basename, not an explicit AUMID.
                String implicit=java.nio.file.Path.of(executable).getFileName().toString();
                if(implicit.matches("[A-Za-z0-9 ._-]{1,128}")) { insert(implicit,"DisplayName",DISPLAY_NAME); insert(implicit,"IconUri",executable); }
            }
            if(!id.equals(previous)) port.current(id);
        } catch(RuntimeException failure) { close(); throw failure; }
    }
    private void insert(String identity,String name,String value) { if(port.read(identity,name)==null) { port.write(identity,name,value); inserted.put(new Value(identity,name),value); } }
    public static WindowsMediaIdentity install() {
        if(!System.getProperty("os.name","").startsWith("Windows")) return null;
        return new WindowsMediaIdentity(new NativePort(),ProcessHandle.current().info().command().orElse(null));
    }
    @Override public void close() {
        if(closed) return; closed=true;
        // Preserve later changes made by Salt or another extension.
        try { if(!id.equals(previous) && id.equals(port.current())) port.current(previous); } catch(RuntimeException ignored) { }
        inserted.forEach((key,value)->{ try { if(value.equals(port.read(key.id(),key.name()))) port.remove(key.id(),key.name()); } catch(RuntimeException ignored) { } });
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
        public void current(String id) {
            int hr=shell.getFunction("SetCurrentProcessExplicitAppUserModelID").invokeInt(new Object[]{id==null?null:new WString(id)});
            if(hr<0) throw new IllegalStateException("Windows application identity rejected");
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
