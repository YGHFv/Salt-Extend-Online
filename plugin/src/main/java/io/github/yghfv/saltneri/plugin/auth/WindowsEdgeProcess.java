package io.github.yghfv.saltneri.plugin.auth;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.BaseTSD.*;
import com.sun.jna.platform.win32.WinBase.*;
import com.sun.jna.platform.win32.WinNT.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.ByteByReference;
import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;

/** Direct Win32 Edge launch with an explicit handle allowlist and kill-on-close job. */
final class WindowsEdgeProcess implements AutoCloseable {
    @Structure.FieldOrder({"startup", "attributes"})
    public static final class StartupEx extends Structure {
        public STARTUPINFO startup = new STARTUPINFO();
        public Pointer attributes;
    }
    @Structure.FieldOrder({"processTime", "jobTime", "flags", "minimum", "maximum", "active", "affinity", "priority", "scheduling"})
    public static final class BasicLimits extends Structure {
        public long processTime, jobTime;
        public int flags;
        public SIZE_T minimum = new SIZE_T(), maximum = new SIZE_T();
        public int active;
        public ULONG_PTR affinity = new ULONG_PTR();
        public int priority, scheduling;
    }
    @Structure.FieldOrder({"readOperations", "writeOperations", "otherOperations", "readBytes", "writeBytes", "otherBytes"})
    public static final class IoCounters extends Structure {
        public long readOperations, writeOperations, otherOperations, readBytes, writeBytes, otherBytes;
    }
    @Structure.FieldOrder({"basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
    public static final class ExtendedLimits extends Structure {
        public BasicLimits basic = new BasicLimits();
        public IoCounters io = new IoCounters();
        public SIZE_T processMemory = new SIZE_T(), jobMemory = new SIZE_T(), peakProcessMemory = new SIZE_T(), peakJobMemory = new SIZE_T();
    }
    private final Kernel32 kernel = Kernel32.INSTANCE;
    private final NativeLibrary library = NativeLibrary.getInstance("kernel32");
    private final RequestScope request;
    private final Path root;
    private HANDLE input, output, process, job;
    private final ScheduledExecutorService watch;
    private final ExecutorService writer;
    private boolean closed;
    private boolean stopped;
    private boolean ready;
    private long sequence;
    private byte[] pending = new byte[0];

    private WindowsEdgeProcess(Path root, RequestScope request) {
        this.root=root;this.request=request;
        watch=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"salt-edge-native-lifetime"));
        writer=Executors.newSingleThreadExecutor(r->daemon(r,"salt-edge-native-write"));
    }
    private static Thread daemon(Runnable action,String name) {var t=new Thread(action,name);t.setDaemon(true);return t;}
    static WindowsEdgeProcess open(RequestScope request,boolean headless,String url) throws IOException {
        return open(request,headless,url,false);
    }
    static WindowsEdgeProcess open(RequestScope request,boolean headless,String url,boolean explicitPipeHandles) throws IOException {
        request.check();
        if(!System.getProperty("os.name","").startsWith("Windows")) throw failure(AuthFailure.Reason.BROWSER_MISSING);
        Path root;
        try {
            root=Files.createTempDirectory("salt-edge-web-").toAbsolutePath().normalize();
        } catch(IOException error) {throw failure(AuthFailure.Reason.PROFILE);}
        var browser=new WindowsEdgeProcess(root,request);
        try {
            browser.secureProfile();
            Path executable=WindowsEdgeDiscovery.find();
            WindowsEdgeDiscovery.assertPolicy();
            browser.start(executable,headless,url,explicitPipeHandles);
            Thread owner=Thread.currentThread();
            browser.watch.scheduleWithFixedDelay(()->{
                try {request.check();if(owner.isInterrupted()) throw new CancellationException();}
                catch(CancellationException cancelled) {browser.stopOwnedJob();}
            },0,50,TimeUnit.MILLISECONDS);
            try {browser.call("Browser.getVersion",Map.of(),null,25000);browser.ready=true;}
            catch(IOException error) {
                // call() has already stopped the owned job on failure; checking alive here invents an exit.
                if(error instanceof AuthFailure safe) throw safe;
                throw failure(AuthFailure.Reason.BROWSER_CONNECT);
            }
            return browser;
        } catch(IOException | RuntimeException | LinkageError error) {
            browser.close();
            if(error instanceof CancellationException cancelled) throw cancelled;
            if(error instanceof AuthFailure safe) throw safe;
            throw failure(AuthFailure.Reason.BROWSER_LAUNCH);
        }
    }
    private void secureProfile() throws IOException {
        try {
            var acl=Files.getFileAttributeView(root,AclFileAttributeView.class);
            if(acl==null) throw failure(AuthFailure.Reason.PROFILE);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(root))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
            Path profile=root.resolve("profile");
            Files.createDirectories(profile.resolve("Default"));
            Files.writeString(profile.resolve("Default/Preferences"),
                "{\"credentials_enable_service\":false,\"profile\":{\"password_manager_enabled\":false}}",StandardOpenOption.CREATE_NEW);
        } catch(IOException error) {throw failure(AuthFailure.Reason.PROFILE);}
    }
    private int invoke(String function,Object... arguments) {return library.getFunction(function).invokeInt(arguments);}
    private HANDLE handle(String function,Object... arguments) {
        Pointer value=(Pointer)library.getFunction(function).invoke(Pointer.class,arguments);
        return value==null?null:new HANDLE(value);
    }
    static byte[] inheritedDescriptors(int pointerSize,long nul,long read,long write) {
        if(pointerSize!=4 && pointerSize!=8)throw new IllegalArgumentException("Invalid native pointer size");
        var buffer=java.nio.ByteBuffer.allocate(4+5+5*pointerSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(5);
        buffer.put(new byte[]{0x41,0x41,0x41,0x09,0x09});
        for(long value:new long[]{nul,nul,nul,read,write}) {
            if(pointerSize==8)buffer.putLong(value);
            else buffer.putInt((int)value);
        }
        return buffer.array();
    }
    private void start(Path executable,boolean headless,String url,boolean explicitPipeHandles) throws IOException {
        HANDLE childInput=null,childOutput=null,nul=null,thread=null;
        Memory attributes=null,handles=null,descriptors=null;
        boolean attributesInitialized=false;
        try {
            var security=new SECURITY_ATTRIBUTES();security.bInheritHandle=true;security.write();
            var read=new HANDLEByReference();var write=new HANDLEByReference();
            if(!kernel.CreatePipe(read,write,security,65536)) throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
            childInput=read.getValue();input=write.getValue();
            if(!kernel.SetHandleInformation(input,1,0)) throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
            if(!kernel.CreatePipe(read,write,security,65536)) throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
            output=read.getValue();childOutput=write.getValue();
            if(!kernel.SetHandleInformation(output,1,0)) throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
            nul=kernel.CreateFile("NUL",0xC0000000,3,security,3,0,null);
            if(nul==null || WinBase.INVALID_HANDLE_VALUE.equals(nul)) {nul=null;throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);}
            var startup=new StartupEx();
            startup.startup.cb=new WinDef.DWORD(startup.size());
            startup.startup.dwFlags=0x100;
            startup.startup.hStdInput=nul;startup.startup.hStdOutput=nul;startup.startup.hStdError=nul;
            // Chromium's standard pipe transport expects CRT descriptors 3/4, not just inherited Win32 handles.
            byte[] descriptorBytes=inheritedDescriptors(Native.POINTER_SIZE,Pointer.nativeValue(nul.getPointer()),
                Pointer.nativeValue(childInput.getPointer()),Pointer.nativeValue(childOutput.getPointer()));
            descriptors=new Memory(descriptorBytes.length);descriptors.write(0,descriptorBytes,0,descriptorBytes.length);
            Arrays.fill(descriptorBytes,(byte)0);
            startup.startup.cbReserved2=new WinDef.WORD(descriptors.size());
            startup.startup.lpReserved2=new ByteByReference();startup.startup.lpReserved2.setPointer(descriptors);
            try(var size=new Memory(Native.SIZE_T_SIZE)) {
                invoke("InitializeProcThreadAttributeList",null,1,0,size);
                long length=Native.SIZE_T_SIZE==8?size.getLong(0):Integer.toUnsignedLong(size.getInt(0));
                if(length<1 || length>65536) throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
                attributes=new Memory(length);
                if(invoke("InitializeProcThreadAttributeList",attributes,1,0,size)==0) throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
                attributesInitialized=true;startup.attributes=attributes;
            }
            // Inherit these three handles only, never unrelated Salt/JVM handles.
            handles=new Memory(3L*Native.POINTER_SIZE);
            handles.setPointer(0,childInput.getPointer());handles.setPointer(Native.POINTER_SIZE,childOutput.getPointer());
            handles.setPointer(2L*Native.POINTER_SIZE,nul.getPointer());
            if(invoke("UpdateProcThreadAttribute",attributes,0,new ULONG_PTR(0x20002),handles,new SIZE_T(handles.size()),null,null)==0)
                throw nativeFailure(AuthFailure.Reason.BROWSER_CONNECT);
            job=handle("CreateJobObjectW",null,null);
            if(job==null) throw nativeFailure(AuthFailure.Reason.BROWSER_LAUNCH);
            var limits=new ExtendedLimits();limits.basic.flags=0x2000;limits.write();
            if(invoke("SetInformationJobObject",job,9,limits.getPointer(),limits.size())==0) throw nativeFailure(AuthFailure.Reason.BROWSER_LAUNCH);
            String command=quote(executable.toString())+" --user-data-dir="+quote(root.resolve("profile").toString())+
                " --remote-debugging-pipe"+
                (explicitPipeHandles?" --remote-debugging-io-pipes="+
                    Long.toUnsignedString(Pointer.nativeValue(childInput.getPointer()))+","+
                    Long.toUnsignedString(Pointer.nativeValue(childOutput.getPointer())):"")+
                " --no-first-run --no-default-browser-check --disable-sync --disable-extensions --disable-background-mode "+
                (headless?"--headless=new about:blank":"--app="+url);
            if(command.length()>30000) throw failure(AuthFailure.Reason.BROWSER_LAUNCH);
            startup.write();
            var information=new PROCESS_INFORMATION();
            try(var buffer=new Memory((command.length()+1L)*2)) {
                buffer.setWideString(0,command);
                // Suspend before assigning the job; no child can escape lifetime ownership.
                if(invoke("CreateProcessW",new WString(executable.toString()),buffer,null,null,true,0x80000|4,null,
                        new WString(root.toString()),startup.getPointer(),information.getPointer())==0)
                    throw nativeFailure(AuthFailure.Reason.BROWSER_LAUNCH);
                information.read();process=information.hProcess;thread=information.hThread;
            }
            if(invoke("AssignProcessToJobObject",job,process)==0) {
                kernel.TerminateProcess(process,1);throw nativeFailure(AuthFailure.Reason.BROWSER_LAUNCH);
            }
            if(invoke("ResumeThread",thread)==-1) throw nativeFailure(AuthFailure.Reason.BROWSER_LAUNCH);
        } finally {
            closeHandle(childInput);closeHandle(childOutput);closeHandle(nul);closeHandle(thread);
            if(attributesInitialized) invoke("DeleteProcThreadAttributeList",attributes);
            if(handles!=null) handles.close();
            if(attributes!=null) attributes.close();
            if(descriptors!=null) descriptors.close();
        }
    }
    static String quote(String argument) {
        StringBuilder result=new StringBuilder("\"");int slashes=0;
        for(char ch:argument.toCharArray()) {
            if(ch=='\\') {slashes++;continue;}
            if(ch=='"') result.append("\\".repeat(slashes*2+1)).append(ch);
            else result.append("\\".repeat(slashes)).append(ch);
            slashes=0;
        }
        return result.append("\\".repeat(slashes*2)).append('"').toString();
    }
    Map<String,Object> call(String method,Map<String,Object> parameters) throws IOException {return call(method,parameters,null,8000);}
    Map<String,Object> call(String method,Map<String,Object> parameters,String session) throws IOException {return call(method,parameters,session,8000);}
    Map<String,Object> call(String method,Map<String,Object> parameters,String session,int timeout) throws IOException {
        request.check();long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        long id=++sequence;var message=new LinkedHashMap<String,Object>();
        message.put("id",id);message.put("method",method);message.put("params",parameters);
        if(session!=null) message.put("sessionId",session);
        byte[] json=BoundedJson.encode(message),frame=Arrays.copyOf(json,json.length+1);Arrays.fill(json,(byte)0);
        if(frame.length>32768) {Arrays.fill(frame,(byte)0);throw failure(AuthFailure.Reason.BROWSER_CONNECT);}
        Future<Boolean> write=writer.submit(()->{
            try {var count=new IntByReference();return kernel.WriteFile(input,frame,frame.length,count,null) && count.getValue()==frame.length;}
            finally {Arrays.fill(frame,(byte)0);}
        });
        try {
            while(!write.isDone()) {checkDeadline(end);pause(10);}
            if(!write.get()) throw failure(AuthFailure.Reason.BROWSER_CONNECT);
            while(true) {
                checkDeadline(end);
                int delimiter=-1;
                for(int i=0;i<pending.length;i++) if(pending[i]==0) {delimiter=i;break;}
                if(delimiter>=0) {
                    byte[] response=Arrays.copyOf(pending,delimiter),next=Arrays.copyOfRange(pending,delimiter+1,pending.length);
                    Arrays.fill(pending,(byte)0);pending=next;
                    Map<String,Object> value;
                    try {value=BoundedJson.object(BoundedJson.parse(response));}
                    finally {Arrays.fill(response,(byte)0);}
                    if(value.get("id") instanceof java.math.BigDecimal number && number.longValue()==id) {
                        if(value.get("error")!=null) {
                            var error=BoundedJson.object(value.get("error"));
                            if(method.equals("Runtime.evaluate") && error.get("code") instanceof java.math.BigDecimal code
                                    && code.intValue()==-32000 && Set.of("Cannot find context with specified id",
                                        "Execution context was destroyed.").contains(error.get("message")))
                                throw new NeteaseDeviceCollector.ContextUnavailable();
                            throw failure(AuthFailure.Reason.BROWSER_CONNECT);
                        }
                        return BoundedJson.object(value.get("result"));
                    }
                    continue;
                }
                var available=new IntByReference();
                if(!kernel.PeekNamedPipe(output,null,0,null,available,null)) throw failure(AuthFailure.Reason.BROWSER_CONNECT);
                if(available.getValue()<1) {pause(10);continue;}
                byte[] buffer=new byte[Math.min(8192,available.getValue())];var count=new IntByReference();
                try {
                    if(!kernel.ReadFile(output,buffer,buffer.length,count,null) || count.getValue()<1 ||
                            pending.length+count.getValue()>131072) throw failure(AuthFailure.Reason.BROWSER_CONNECT);
                    byte[] next=Arrays.copyOf(pending,pending.length+count.getValue());
                    System.arraycopy(buffer,0,next,pending.length,count.getValue());Arrays.fill(pending,(byte)0);pending=next;
                } finally {Arrays.fill(buffer,(byte)0);}
            }
        } catch(ExecutionException error) {throw failure(AuthFailure.Reason.BROWSER_CONNECT);}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new CancellationException("Web login cancelled");}
        catch(IOException | CancellationException error) {
            IOException classified=error instanceof IOException io?io:null;
            if(classified!=null && !ready && !stopped && process!=null && !alive()) {
                var exit=new IntByReference();
                if(kernel.GetExitCodeProcess(process,exit) && exit.getValue()!=259)
                    classified=AuthFailure.code(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.BROWSER_EXIT,
                        Integer.toUnsignedLong(exit.getValue()));
            }
            if(!(error instanceof NeteaseDeviceCollector.ContextUnavailable))stopOwnedJob();
            if(classified!=null)throw classified;
            throw error;
        }
    }
    private void checkDeadline(long end) throws IOException {
        request.check();
        if(System.nanoTime()>=end) throw failure(AuthFailure.Reason.BROWSER_TIMEOUT);
    }
    static void pause(long milliseconds) {
        try {Thread.sleep(milliseconds);}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new CancellationException("Web login cancelled");}
    }
    boolean alive() {return process!=null && kernel.WaitForSingleObject(process,0)==258;}
    boolean inOwnedJob() {var result=new IntByReference();return invoke("IsProcessInJob",process,job,result)!=0 && result.getValue()!=0;}
    Path root() {return root;}
    private synchronized void stopOwnedJob() {
        if(!stopped && job!=null) {invoke("TerminateJobObject",job,1);stopped=true;}
    }
    @Override public void close() {
        synchronized(this) {if(closed) return;closed=true;}
        watch.shutdownNow();
        if(ready && alive()) {
            try {call("Browser.close",Map.of(),null,1000);} catch(IOException | CancellationException ignored) { }
        }
        stopOwnedJob();
        if(process!=null) kernel.WaitForSingleObject(process,2000);
        writer.shutdown();
        try {writer.awaitTermination(2,TimeUnit.SECONDS);}
        catch(InterruptedException error) {Thread.currentThread().interrupt();}
        closeHandle(input);closeHandle(output);closeHandle(process);closeHandle(job);
        Arrays.fill(pending,(byte)0);
        boolean interrupted=Thread.interrupted();
        try {
            for(int attempt=0;attempt<30;attempt++) {
                try(var paths=Files.walk(root)) {
                    for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                        if(!path.toAbsolutePath().normalize().startsWith(root)) return;
                        Files.deleteIfExists(path);
                    }
                    break;
                } catch(IOException ignored) {
                    try {Thread.sleep(100);}
                    catch(InterruptedException cancelled) {interrupted=true;}
                }
            }
        } finally {if(interrupted) Thread.currentThread().interrupt();}
    }
    private void closeHandle(HANDLE handle) {if(handle!=null) kernel.CloseHandle(handle);}
    private AuthFailure nativeFailure(AuthFailure.Reason reason) {return AuthFailure.code(AuthFailure.Stage.NET_WEB,reason,kernel.GetLastError());}
    private static AuthFailure failure(AuthFailure.Reason reason) {return AuthFailure.of(AuthFailure.Stage.NET_WEB,reason);}
}
