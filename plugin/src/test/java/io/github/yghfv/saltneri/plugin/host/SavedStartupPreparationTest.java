package io.github.yghfv.saltneri.plugin.host;

import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.ToolProvider;

/** Real Salt restore coroutine and method Advice; isolated recording player, no native audio. */
public final class SavedStartupPreparationTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Throwable{
        ClassLoader host=SavedStartupPreparationTest.class.getClassLoader();
        Path temporary=Files.createTempDirectory("salt-startup-restore-");
        Path sources=Files.createDirectories(temporary.resolve("src")),classes=Files.createDirectories(temporary.resolve("classes"));
        var files=new ArrayList<String>();
        write(sources,files,"PiscesPlayer.java","""
                package com.xuncorp.pisces;
                public class PiscesPlayer {
                    public int selections,prepares;
                    public Object \\u0528(PiscesMediaItem item){selections++;return kotlin.Unit.INSTANCE;}
                    public Object \\u037f(long position){prepares++;throw new IllegalStateException("Unknown error");}
                    public static Object \\u037f(PiscesPlayer player){return player.\\u037f(0L);}
                }
                """);
        write(sources,files,"PlaybackService.java","""
                package com.xuncorp.voxzen.service;
                public class PlaybackService extends androidx.compose.ui.\\u0aae {
                    public final com.xuncorp.pisces.PiscesPlayer player=new com.xuncorp.pisces.RecordingPlayer();
                    public com.xuncorp.pisces.PiscesPlayer getPlayer(){return player;}
                }
                """);
        write(sources,files,"RecordingPlayer.java","""
                package com.xuncorp.pisces;
                public class RecordingPlayer extends PiscesPlayer {}
                """);
        write(sources,files,"\u0aae.java","""
                package androidx.compose.ui;
                public class \\u0aae {
                    public com.xuncorp.pisces.PiscesPlayer getPlayer(){throw new AssertionError("Base player");}
                }
                """);
        write(sources,files,"WorkshopMethodFrame.java","""
                package com.xuncorp.voxzen.workshop.hooks;
                import com.xuncorp.spw.workshop.api.hook.*;
                import java.lang.reflect.*;
                public final class WorkshopMethodFrame extends MethodHookParam {
                    final String owner;final Object receiver;final Object[] args;Object result;Throwable failure;boolean skipped;
                    WorkshopMethodFrame(String owner,Object receiver,Object[] args){this.owner=owner;this.receiver=receiver;this.args=args;}
                    public Method getMethod(){return null;}
                    public Object getThisObject(){return receiver;}
                    public Object[] getArgs(){return args;}
                    public Object getResult(){return result;}
                    public Throwable getThrowable(){return failure;}
                    public boolean isOriginalSkipped(){return skipped;}
                    public void setResult(Object value){result=value;failure=null;skipped=true;}
                    public void setThrowable(Throwable value){throw new AssertionError("Error replaced");}
                    public Object invokeOriginal(){throw new AssertionError("Original repeated");}
                }
                """);
        write(sources,files,"WorkshopHookBridge.java","""
                package com.xuncorp.voxzen.workshop.hooks;
                import java.util.*;
                import com.xuncorp.spw.workshop.api.hook.*;
                public final class WorkshopHookBridge {
                    public static final Map<String,MethodHook> hooks=new HashMap<>();
                    public static WorkshopMethodFrame enter(Class<?> owner,String name,String descriptor,Object receiver,Object[] args)throws Throwable{
                        MethodHook hook=hooks.get(owner.getName());if(hook==null)return null;
                        WorkshopMethodFrame frame=new WorkshopMethodFrame(owner.getName(),receiver,args);hook.before(frame);return frame;
                    }
                    public static void exit(WorkshopMethodFrame frame,Object value,Throwable failure)throws Throwable{
                        if(!frame.skipped){frame.result=value;frame.failure=failure;}
                        MethodHook hook=hooks.get(frame.owner);if(hook!=null)hook.after(frame);
                    }
                }
                """);
        var compilerArgs=new ArrayList<>(List.of("--release","25","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        compilerArgs.addAll(files);
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,compilerArgs.toArray(String[]::new))==0,"Fixture compilation");
        byte[] restoreBytes;
        try(var in=host.getResourceAsStream(SavedStartupPreparation.RESTORE.replace('.','/')+".class")){restoreBytes=in.readAllBytes();}
        byte[] restore=transform(host,Class.forName(SavedStartupPreparation.RESTORE,false,host).getMethod("invokeSuspend",Object.class),restoreBytes);
        byte[] prepare=transform(host,Class.forName(Salt1193EngineLink.PLAYER,false,host).getMethod("\u037f",long.class),
                Files.readAllBytes(classes.resolve("com/xuncorp/pisces/PiscesPlayer.class")));
        try(var isolated=new URLClassLoader(new URL[]{classes.toUri().toURL()},host){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                synchronized(getClassLoadingLock(name)){
                    if(name.equals(SavedStartupPreparation.RESTORE)||name.equals(Salt1193EngineLink.PLAYER)
                            ||name.equals(Salt1193EngineLink.SERVICE)||name.equals("com.xuncorp.pisces.RecordingPlayer")
                            ||name.equals("androidx.compose.ui.\u0aae")
                            ||name.startsWith("com.xuncorp.voxzen.workshop.hooks.Workshop")){
                        Class<?> found=findLoadedClass(name);
                        if(found==null)found=name.equals(SavedStartupPreparation.RESTORE)?defineClass(name,restore,0,restore.length):
                                name.equals(Salt1193EngineLink.PLAYER)?defineClass(name,prepare,0,prepare.length):findClass(name);
                        if(resolve)resolveClass(found);return found;
                    }
                    return super.loadClass(name,resolve);
                }
            }
        }){
            String hash="a".repeat(64),id="saltneri:saved:"+hash,path=temporary.resolve(hash+".flac").toString();
            var profile=Salt1193Probe.inspect(host,"1.19.3","Steam");
            Object item=profile.mediaFactory().create(id,"fixture","fixture","","",path);
            Class<?> lambda=isolated.loadClass(SavedStartupPreparation.RESTORE);
            var constructor=lambda.getDeclaredConstructors()[0];constructor.setAccessible(true);
            Object service=isolated.loadClass(Salt1193EngineLink.SERVICE).getConstructor().newInstance();
            Object player=service.getClass().getMethod("getPlayer").invoke(service);
            Method invoke=lambda.getMethod("invokeSuspend",Object.class);invoke.setAccessible(true);
            fails(()->invoke.invoke(constructor.newInstance(service,item,null),kotlin.Unit.INSTANCE));
            check(count(player,"prepares")==1&&count(player,"selections")==1,"Old restore did not prepare artwork");
            @SuppressWarnings("unchecked") Map<String,MethodHook> hooks=(Map<String,MethodHook>)isolated
                    .loadClass("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge").getField("hooks").get(null);
            var active=new AtomicBoolean(true);
            var nativeHost=SavedStartupPreparation.nativeHost(isolated);
            try(var guard=new SavedStartupPreparation(temporary,nativeHost,(type,name,params,callback)->{
                hooks.put(type,callback);return new HookHandle(){public void unhook(){hooks.remove(type);}};
            },active::get)){
                Object current=constructor.newInstance(service,item,null);
                check(nativeHost.selection(current).equals(new SavedStartupPreparation.Selection(id,path)),"Native selection ABI");
                Object result=invoke.invoke(current,kotlin.Unit.INSTANCE);
                check(result!=null&&result.getClass().getName().equals("kotlinx.coroutines.channels.ChannelResult"),"Restore lost boxed result");
                check(count(player,"prepares")==1&&count(player,"selections")==2,"Startup still prepared audio or lost selection");
                // Real Advice discards replacement returns. Skipped Prepare returns unboxed
                // success(null), while the original restore coroutine keeps its boxed result.
                check(result.getClass().getMethod("unbox-impl").invoke(result)==null,"Unexpected command result");
                fails(()->player.getClass().getMethod("\u037f",long.class).invoke(player,0L));
                check(count(player,"prepares")==2,"Explicit prepare was suppressed");
                for(var selection:List.of(
                        new SavedStartupPreparation.Selection("local",path),
                        new SavedStartupPreparation.Selection(id,temporary.resolve("b".repeat(64)+".flac").toString()),
                        new SavedStartupPreparation.Selection(id,temporary.resolve("child/../"+hash+".flac").toString()),
                        new SavedStartupPreparation.Selection(id,hash+".flac"))){
                    Object foreign=profile.mediaFactory().create(selection.id(),"fixture","fixture","","",selection.path());
                    fails(()->invoke.invoke(constructor.newInstance(service,foreign,null),kotlin.Unit.INSTANCE));
                }
                active.set(false);
                fails(()->invoke.invoke(constructor.newInstance(service,item,null),kotlin.Unit.INSTANCE));
                active.set(true);
                fails(()->player.getClass().getMethod("\u037f",long.class).invoke(player,0L));
                check(hooks.size()==2,"Duplicate startup hooks");
            }
            check(hooks.isEmpty(),"Unloaded startup hooks retained");
            fails(()->invoke.invoke(constructor.newInstance(service,item,null),kotlin.Unit.INSTANCE));
        }
        SavedPlaybackRecoveryTest.main(args);
        System.out.println("PASS: "+checks+" real host startup coroutine/Advice, explicit playback, foreign-track and unload checks");
    }
    private static int count(Object player,String name)throws Exception{return player.getClass().getField(name).getInt(player);}
    private static void write(Path directory,List<String> files,String name,String code)throws Exception{
        Path file=directory.resolve(name);Files.writeString(file,code);files.add(file.toString());
    }
    private static byte[] transform(ClassLoader host,Method method,byte[] input)throws Exception{
        Class<?> type=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052c",false,host);
        Object transformer=type.getConstructor(Method.class).newInstance(method);
        return(byte[])type.getMethod("\u037f",String.class,byte[].class).invoke(transformer,method.getDeclaringClass().getName(),input);
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void fails(Action action)throws Exception{
        try{action.run();}catch(InvocationTargetException failure){
            check(failure.getCause() instanceof IllegalStateException&&"Unknown error".equals(failure.getCause().getMessage()),"Unexpected failure");return;
        }
        throw new AssertionError("Original audio error was hidden");
    }
}
