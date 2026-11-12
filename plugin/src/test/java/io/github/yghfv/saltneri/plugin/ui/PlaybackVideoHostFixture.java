package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.runtime.*;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.geometry.Rect;
import androidx.compose.foundation.layout.BoxScope;
import androidx.compose.ui.window.FrameWindowScope;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

/** Real native method transforms inside MainSurface; only the bridge registry is isolated. */
final class PlaybackVideoHostFixture implements AutoCloseable {
    private final URLClassLoader loader;
    private final Method surface;
    private final Method caption,fullscreen;
    private final List<MutableState<Rect>> regions=java.util.stream.IntStream.range(0,3)
            .mapToObj(i->SnapshotStateKt.mutableStateOf(Rect.Companion.getZero(),SnapshotStateKt.<Rect>structuralEqualityPolicy())).toList();
    final Map<String,Integer> calls;
    PlaybackVideoHostFixture(Map<String,MethodHook> hooks,Map<String,List<String>> signatures) throws Exception {
        ClassLoader host=getClass().getClassLoader();
        Class<?> transformer=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052c");
        Map<String,byte[]> transformed=new HashMap<>();
        for(var entry:signatures.entrySet()) {
            Class<?> owner=Class.forName(entry.getKey());
            Class<?>[] parameters=entry.getValue().stream().map(name->{
                try{return switch(name){case "boolean"->boolean.class;case "int"->int.class;case "long"->long.class;case "float"->float.class;default->Class.forName(name);};}
                catch(Exception error){throw new AssertionError(error);}
            }).toArray(Class[]::new);
            Method method=owner.getDeclaredMethod("\u037f",parameters);
            Object transform=transformer.getConstructor(Method.class).newInstance(method);
            byte[] original;
            try(var in=host.getResourceAsStream(entry.getKey().replace('.','/')+".class")){original=in.readAllBytes();}
            byte[] bytes=(byte[])transformer.getMethod("\u037f",String.class,byte[].class).invoke(transform,entry.getKey(),original);
            if(Arrays.equals(bytes,original))throw new AssertionError("Missing native method transform "+entry.getKey());
            transformed.put(entry.getKey(),bytes);
        }
        Path temp=Files.createTempDirectory("salt-video-transform-"),classes=Files.createDirectory(temp.resolve("classes"));
        Path frame=temp.resolve("WorkshopMethodFrame.java"),source=temp.resolve("WorkshopHookBridge.java");
        Files.writeString(frame,"""
                package com.xuncorp.voxzen.workshop.hooks;
                import com.xuncorp.spw.workshop.api.hook.*;
                import java.lang.reflect.*;
                public final class WorkshopMethodFrame extends MethodHookParam {
                    final Class<?> owner; final String name; final Object receiver; final Object[] args;
                    Object result; Throwable failure; boolean skipped;
                    WorkshopMethodFrame(Class<?> owner,String name,Object receiver,Object[] args) { this.owner=owner;this.name=name;this.receiver=receiver;this.args=args; }
                    public Method getMethod() { for(Method m:owner.getDeclaredMethods())if(m.getName().equals(name))return m;throw new AssertionError(); }
                    public Object getThisObject() { return receiver; }
                    public Object[] getArgs() { return args; }
                    public Object getResult() { return result; }
                    public Throwable getThrowable() { return failure; }
                    public boolean isOriginalSkipped() { return skipped; }
                    public void setResult(Object value) { result=value;failure=null;skipped=true; }
                    public void setThrowable(Throwable value) { failure=value;result=null;skipped=true; }
                    public Object invokeOriginal() { throw new AssertionError("Unexpected fixture bypass"); }
                }
                """);
        Files.writeString(source,"""
                package com.xuncorp.voxzen.workshop.hooks;
                import com.xuncorp.spw.workshop.api.hook.*;
                public final class WorkshopHookBridge {
                    public static java.util.Map<String,MethodHook> hooks;
                    public static java.util.Map<String,Integer> calls=new java.util.HashMap<>();
                    public static WorkshopMethodFrame enter(Class<?> owner,String name,String descriptor,Object receiver,Object[] args) throws Throwable {
                        MethodHook hook=hooks.get(owner.getName());if(hook==null)return null;
                        calls.merge(owner.getName(),1,Integer::sum);
                        WorkshopMethodFrame frame=new WorkshopMethodFrame(owner,name,receiver,args);hook.before(frame);return frame;
                    }
                    public static void exit(WorkshopMethodFrame frame,Object value,Throwable failure) throws Throwable {
                        if(!frame.skipped){frame.result=value;frame.failure=failure;}hooks.get(frame.owner.getName()).after(frame);
                    }
                }
                """);
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","-proc:none","-classpath",System.getProperty("java.class.path"),
                "-d",classes.toString(),frame.toString(),source.toString())!=0)throw new AssertionError("Video bridge fixture compile failed");
        loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},host) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                boolean internal=name.startsWith("androidx.compose.ui.") && !java.lang.reflect.Modifier.isPublic(Class.forName(name,false,host).getModifiers());
                if(!name.equals(SaltVideoBindings.TRANSPORT_CALLER) && !name.startsWith(SaltVideoBindings.TRANSPORT_CALLER+"$")
                        && !internal && !transformed.containsKey(name) && transformed.keySet().stream().noneMatch(k->name.startsWith(k+"$"))
                        && !name.startsWith("com.xuncorp.voxzen.workshop.hooks.Workshop"))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> type=findLoadedClass(name);
                    if(type==null) {
                        if(name.startsWith("com.xuncorp.voxzen.workshop.hooks.Workshop"))type=findClass(name);
                        else {
                            byte[] code=transformed.get(name);
                            if(code==null)try(var in=host.getResourceAsStream(name.replace('.','/')+".class")){code=in.readAllBytes();}
                            catch(Exception failure){throw new ClassNotFoundException(name,failure);}
                            type=defineClass(name,code,0,code.length);
                        }
                    }
                    if(resolve)resolveClass(type);return type;
                }
            }
        };
        Class<?> bridge=loader.loadClass("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge");
        bridge.getField("hooks").set(null,hooks);
        calls=(Map<String,Integer>)bridge.getField("calls").get(null);
        surface=loader.loadClass(SaltVideoBindings.TRANSPORT_CALLER).getMethod("\u037f",Class.forName("androidx.compose.ui.\u0aa0"),boolean.class,
                Class.forName("androidx.compose.ui.\u091a"),Composer.class,int.class);
        caption=loader.loadClass(SaltVideoBindings.CAPTION).getDeclaredMethod("\u037f",Class.forName("androidx.compose.ui.\u012e"),BoxScope.class,
                androidx.compose.ui.window.WindowState.class,boolean.class,FrameWindowScope.class,MutableState.class,MutableState.class,MutableState.class,Composer.class,int.class);
        caption.setAccessible(true);
        fullscreen=loader.loadClass(SaltVideoBindings.FULLSCREEN).getMethod("\u037f",Function0.class,boolean.class,Modifier.class,boolean.class,Composer.class,int.class,int.class);
    }
    void bind(SaltVideoBindings video) throws Exception {
        for(var entry:Map.of("transportControls",SaltVideoBindings.TRANSPORT,"playerScreen",SaltVideoBindings.SCREEN,
                "fullscreenButton",SaltVideoBindings.FULLSCREEN,"captionGroup",SaltVideoBindings.CAPTION,"coverContent",SaltVideoBindings.COVER,
                "floatCover",SaltVideoBindings.FLOAT_COVER).entrySet()) {
            var field=SaltVideoBindings.class.getDeclaredField(entry.getKey());field.setAccessible(true);
            Method current=(Method)field.get(video);
            Method replacement=loader.loadClass(entry.getValue()).getDeclaredMethod(current.getName(),current.getParameterTypes());
            replacement.setAccessible(true);field.set(video,replacement);
        }
    }
    void render(Object titleState,Object videoState,Composer composer) {
        try{surface.invoke(null,titleState,false,videoState,composer,0);}
        catch(Exception failure){throw new AssertionError("Native animated player surface",failure);}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    void listen(Composer composer) {
        try {
            Object navigator=composer.consume((CompositionLocal<?>)Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null));
            Object sheet=composer.consume((CompositionLocal<?>)Class.forName("androidx.compose.ui.\u041a").getMethod("\u037f").invoke(null));
            var events=(kotlinx.coroutines.flow.Flow)navigator.getClass().getMethod("getPlayerSheetEvents$Voxzen_composeApp").invoke(navigator);
            // MainWindow normally collects these requests. The fixture renders MainSurface
            // directly, so forward them to the same native suspend expand/collapse methods.
            EffectsKt.LaunchedEffect(navigator,(scope,continuation)->events.collect((event,next)->{
                try{return sheet.getClass().getMethod(event.toString().equals("Expand")?"\u037f":"\u0528",kotlin.coroutines.Continuation.class).invoke(sheet,next);}
                catch(Exception error){throw new AssertionError(error);}
            },continuation),composer,0);
        }catch(Exception error){throw new AssertionError("Native player requests",error);}
    }
    void caption(BoxScope box,FrameWindowScope window,Composer composer) {
        try {
            Object properties=composer.consume((CompositionLocal<?>)Class.forName("androidx.compose.ui.\u0689").getMethod("\u0528").invoke(null));
            Object state=composer.consume((CompositionLocal<?>)Class.forName("androidx.compose.ui.\u0372").getMethod("\u037f").invoke(null));
            caption.invoke(null,properties,box,state,true,window,regions.get(0),regions.get(1),regions.get(2),composer,0);
        }catch(Exception error){throw new AssertionError("Native caption group",error);}
    }
    boolean captionRegionsEmpty(){return regions.stream().allMatch(s->s.getValue().equals(Rect.Companion.getZero()));}
    void fullscreen(Modifier modifier,Composer composer) {
        try{fullscreen.invoke(null,(Function0<Unit>)()->Unit.INSTANCE,false,modifier,true,composer,0,0);}
        catch(Exception error){throw new AssertionError("Native fullscreen button",error);}
    }
    @Override public void close() throws Exception {loader.close();}
}
