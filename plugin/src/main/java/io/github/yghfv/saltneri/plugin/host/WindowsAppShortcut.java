package io.github.yghfv.saltneri.plugin.host;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.BiConsumer;

/** A real Shell Link with IPropertyStore metadata; never edits the user's existing Salt shortcuts. */
final class WindowsAppShortcut implements AutoCloseable {
    private static final String LINK_CLASS="{00021401-0000-0000-C000-000000000046}";
    private static final String LINK_IID="{000214F9-0000-0000-C000-000000000046}";
    private static final String STORE_IID="{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}";
    private static final String FILE_IID="{0000010B-0000-0000-C000-000000000046}";
    private static final String APP_ID_KEY="{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}";
    private static final String TITLE_KEY="{F29F85E0-4FF9-1068-AB91-08002B27B3D9}";
    private final Path directory, path;
    private final BiConsumer<Path,Boolean> notifyShell;
    private byte[] owned;
    private boolean ownDirectory;

    private WindowsAppShortcut(Path programs,BiConsumer<Path,Boolean> notifyShell) throws IOException {
        directory=programs.toRealPath().resolve("Salt Extend Online");
        path=directory.resolve("Salt Player for Windows.lnk");
        this.notifyShell=notifyShell;
    }

    static WindowsAppShortcut install(Path programs,Path executable,String id,String title,BiConsumer<Path,Boolean> notifyShell) {
        WindowsAppShortcut result=null;
        try {
            result=new WindowsAppShortcut(programs,notifyShell);
            try { Files.createDirectory(result.directory); result.ownDirectory=true; }
            catch(FileAlreadyExistsException exists) {
                if(!Files.isDirectory(result.directory,LinkOption.NOFOLLOW_LINKS)
                        || !result.directory.toRealPath().equals(result.directory))
                    throw new IOException("Unsafe shortcut directory");
            }
            if(Files.exists(result.path,LinkOption.NOFOLLOW_LINKS)) {
                if(!Files.isRegularFile(result.path,LinkOption.NOFOLLOW_LINKS) || Files.size(result.path)>65536)
                    throw new IOException("Shortcut path already occupied");
                Metadata existing=read(result.path);
                if(!existing.id().equals(id) || !existing.title().equals(title)
                        || !Path.of(existing.target()).equals(executable))
                    throw new IOException("Existing shortcut belongs to another owner");
                // A pre-existing matching link (e.g. after an unclean exit) is reusable, but not ours to delete.
                return result;
            }
            Path temporary=Files.createTempFile(result.directory,".salt-media-",".tmp");
            try {
                write(temporary,executable,id,title);
                if(Files.size(temporary)>65536) throw new IOException("Shortcut unexpectedly large");
                byte[] bytes=Files.readAllBytes(temporary);
                Files.move(temporary,result.path); // Same directory, no REPLACE_EXISTING: never publish a partial link.
                result.owned=bytes;
            } finally { Files.deleteIfExists(temporary); }
            notifyShell.accept(result.path,true);
            return result;
        } catch(IOException | RuntimeException | LinkageError failure) {
            if(result!=null) result.close();
            throw new IllegalStateException("Windows media shortcut registration failed",failure);
        }
    }

    /** Commit the shell registration across Salt exits; close remains rollback for failed installs/tests. */
    void preserve() { owned=null; ownDirectory=false; }

    @Override public void close() {
        if(owned!=null) {
            try {
                if(directory.toRealPath().equals(directory) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                        && Files.size(path)==owned.length && Arrays.equals(owned,Files.readAllBytes(path))) {
                    Files.delete(path);
                    notifyShell.accept(path,false);
                }
            } catch(IOException | RuntimeException | LinkageError ignored) { }
            owned=null;
        }
        if(ownDirectory) {
            // Non-recursive: leave any files subsequently added by the user untouched.
            try { Files.delete(directory); } catch(IOException ignored) { }
            ownDirectory=false;
        }
    }

    private static void write(Path file,Path executable,String id,String title) {
        try(Apartment apartment=new Apartment(); Com link=Com.create();
                Com store=link.query(STORE_IID); Com persist=link.query(FILE_IID)) {
            link.call(20,new WString(executable.toString())); // IShellLinkW::SetPath
            link.call(9,new WString(executable.getParent().toString()));
            link.call(7,new WString("Salt Player for Windows - Salt Extend Online media identity"));
            link.call(17,new WString(executable.toString()),0);
            set(store,APP_ID_KEY,5,id);
            set(store,TITLE_KEY,2,title);
            store.call(7); // IPropertyStore::Commit before IPersistFile::Save
            persist.call(6,new WString(file.toString()),1);
        }
    }

    record Metadata(String target,String id,String title,String icon,int iconIndex) { }

    static Metadata read(Path file) {
        try(Apartment apartment=new Apartment(); Com link=Com.create();
                Com persist=link.query(FILE_IID); Com store=link.query(STORE_IID)) {
            persist.call(5,new WString(file.toString()),0); // Load, STGM_READ; never Resolve or launch
            char[] target=new char[32768], icon=new char[32768];
            link.call(3,target,target.length,null,4); // SLGP_RAWPATH
            var index=new com.sun.jna.ptr.IntByReference();
            link.call(16,icon,icon.length,index);
            return new Metadata(Native.toString(target),get(store,APP_ID_KEY,5),get(store,TITLE_KEY,2),
                    Native.toString(icon),index.getValue());
        }
    }

    private static Memory key(String guid,int pid) {
        Guid.GUID format=new Guid.GUID(guid); format.write();
        Memory key=new Memory(20);
        key.write(0,format.getPointer().getByteArray(0,16),0,16); key.setInt(16,pid);
        return key;
    }
    private static void set(Com store,String guid,int pid,String text) {
        try(Memory key=key(guid,pid); Memory value=new Memory(24); Memory string=new Memory((text.length()+1L)*Native.WCHAR_SIZE)) {
            string.setWideString(0,text);
            value.clear(); value.setShort(0,(short)31); value.setPointer(8,string); // PROPVARIANT VT_LPWSTR
            store.call(6,key,value);
        }
    }
    private static String get(Com store,String guid,int pid) {
        try(Memory key=key(guid,pid); Memory value=new Memory(24)) {
            value.clear();
            try {
                store.call(5,key,value);
                return value.getShort(0)==31 && value.getPointer(8)!=null?value.getPointer(8).getWideString(0):"";
            } finally { NativeLibrary.getInstance("ole32").getFunction("PropVariantClear").invokeInt(new Object[]{value}); }
        }
    }
    private static void check(int hr) {
        if(hr<0) throw new IllegalStateException("Shell Link HRESULT 0x"+Integer.toHexString(hr));
    }
    private static final class Apartment implements AutoCloseable {
        final int hr=Ole32.INSTANCE.CoInitializeEx(null,Ole32.COINIT_APARTMENTTHREADED).intValue();
        Apartment() { if(hr!=0x80010106) check(hr); } // RPC_E_CHANGED_MODE: use caller's existing apartment
        public void close() { if(hr>=0) Ole32.INSTANCE.CoUninitialize(); }
    }
    private static final class Com implements AutoCloseable {
        final Pointer pointer;
        Com(Pointer pointer) { this.pointer=pointer; }
        static Com create() {
            PointerByReference result=new PointerByReference();
            check(Ole32.INSTANCE.CoCreateInstance(new Guid.GUID(LINK_CLASS),null,WTypes.CLSCTX_INPROC_SERVER,
                    new Guid.GUID(LINK_IID),result).intValue());
            return new Com(result.getValue());
        }
        Com query(String iid) {
            Guid.GUID guid=new Guid.GUID(iid); guid.write();
            PointerByReference result=new PointerByReference(); call(0,guid.getPointer(),result);
            return new Com(result.getValue());
        }
        void call(int slot,Object... arguments) { check(invoke(slot,arguments)); }
        private int invoke(int slot,Object... arguments) {
            Object[] all=new Object[arguments.length+1]; all[0]=pointer;
            System.arraycopy(arguments,0,all,1,arguments.length);
            return Function.getFunction(pointer.getPointer(0).getPointer((long)slot*Native.POINTER_SIZE),
                    Function.ALT_CONVENTION).invokeInt(all);
        }
        public void close() { invoke(2); }
    }
}
