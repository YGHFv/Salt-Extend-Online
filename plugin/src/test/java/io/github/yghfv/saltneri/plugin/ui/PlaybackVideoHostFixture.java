package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.geometry.Rect;
import androidx.compose.foundation.layout.BoxScope;
import androidx.compose.ui.window.FrameWindowScope;
import com.xuncorp.spw.workshop.api.hook.*;
import com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

/** Shipped resolver, mask analysis and transforms in an isolated test JVM.
 * Plugin lookup and the audio action are synthetic; Compose and state getters are real. */
public final class PlaybackVideoHostFixture implements AutoCloseable {
    record Site(int id,String owner,String name,String caller,List<String> parameters,ComposableHook content,boolean after) { }
    static class RegistrationOnlyHooks extends HookRegistrar {
        private static HookHandle handle(){return new HookHandle(){public void close(){}public void unhook(){}};}
        public HookHandle hookMethod(String owner,String name,List<String> parameters,MethodHook hook) {
            try{resolve(owner,name,null,parameters);}catch(Exception error){throw new AssertionError("Host rejected method hook",error);}
            return handle();
        }
        private HookHandle ui(String owner,String name,String caller,List<String> parameters,ComposableHook hook,boolean after) {
            try{site(new Site(1,owner,name,caller,parameters,hook,after));}catch(Exception error){throw new AssertionError("Host rejected UI hook",error);}
            return handle();
        }
        public HookHandle afterComposable(String owner,String name,String caller,List<String> parameters,int priority,ComposableHook hook){return ui(owner,name,caller,parameters,hook,true);}
        public HookHandle replaceComposable(String owner,String name,String caller,List<String> parameters,int priority,ComposableHook hook){return ui(owner,name,caller,parameters,hook,false);}
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S> s,List<String> p,int n,ContentHook<S> h){throw new AssertionError();}
    }
    private final Map<Class<?>,byte[]> originals=new LinkedHashMap<>();
    private final Method surface,fullscreen,frame,mainWindow;
    private Object properties;
    final Map<String,Integer> calls=Bridge.calls;
    final Set<Integer> renderedSites=Bridge.renderedSites;
    static Object resolve(String owner,String name,String caller,List<String> parameters) throws Exception {
        Class<?> type=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        return caller==null?type.getMethod("\u037f",String.class,String.class,List.class).invoke(type.getField("\u037f").get(null),owner,name,parameters):
                type.getMethod("\u037f",String.class,String.class,String.class,List.class).invoke(type.getField("\u037f").get(null),owner,name,caller,parameters);
    }
    static Object site(Site spec) throws Exception {
        Object target=resolve(spec.owner,spec.name,spec.caller,spec.parameters);
        Class<?> type=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u0529$\u052d");
        Object site=type.getConstructors()[0].newInstance(spec.id,target);
        if(!spec.after) {
            Method method=(Method)target.getClass().getMethod("\u0528").invoke(target);
            Class<?> analyzer=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u0528"),companion=Class.forName(analyzer.getName()+"$\u0528"),reader=Class.forName("androidx.compose.ui.\u0ce1");
            String descriptor=(String)Class.forName("androidx.compose.ui.\u0bd0").getMethod("\u037f",Method.class).invoke(null,method);
            Object analysis=companion.getMethod("\u037f",int.class,String.class,String.class,String.class,boolean.class,reader)
                    .invoke(analyzer.getField("\u037f").get(null),java.lang.reflect.Modifier.isStatic(method.getModifiers())?184:182,
                            method.getDeclaringClass().getName().replace('.','/'),method.getName(),descriptor,method.getDeclaringClass().isInterface(),reader.getConstructor(byte[].class).newInstance(bytes(method.getDeclaringClass())));
            Object masks=analyzer.getMethod("\u052b").invoke(analysis);
            if(masks==null)throw new AssertionError("Host refused mask analysis: "+spec);
            type.getMethod("\u037f",kotlin.ranges.IntRange.class).invoke(site,masks);
        }
        return site;
    }
    private static byte[] bytes(Class<?> type) throws Exception {
        try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){return in.readAllBytes();}
    }
    PlaybackVideoHostFixture(List<Site> sites,Map<String,MethodHook> hooks,Map<String,List<String>> signatures) throws Exception {
        attach();Bridge.sites.clear();Bridge.hooks.clear();Bridge.calls.clear();Bridge.renderedSites.clear();
        for(Site spec:sites)Bridge.sites.put(spec.id,spec);Bridge.hooks.putAll(hooks);
        Class<?> bridge=Class.forName("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge");originals.put(bridge,bytes(bridge));
        byte[] bridgeCode=new ByteBuddy().redefine(bridge).method(ElementMatchers.namedOneOf("hasReplacement","RenderReplacement","RenderAfter","enter","exit"))
                .intercept(MethodDelegation.to(Bridge.class)).make().getBytes();
        Agent.instrumentation.redefineClasses(new ClassDefinition(bridge,bridgeCode));
        Class<?> controller=Class.forName("com.xuncorp.voxzen.service.PlaybackController");originals.put(controller,bytes(controller));
        Agent.instrumentation.redefineClasses(new ClassDefinition(controller,new ByteBuddy().redefine(controller)
                .method(ElementMatchers.namedOneOf("playOrPause","play","pause")).intercept(MethodDelegation.to(AudioAction.class)).make().getBytes()));
        Map<Class<?>,byte[]> transformed=new LinkedHashMap<>();
        for(Site spec:sites) {
            Object target=site(spec);Class<?> transformer=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052d");
            Object transform=transformer.getConstructor(target.getClass()).newInstance(target);
            Class<?> caller=Class.forName(spec.caller);originals.putIfAbsent(caller,bytes(caller));
            byte[] previous=transformed.getOrDefault(caller,originals.get(caller));
            byte[] next=(byte[])transformer.getMethod("\u037f",String.class,byte[].class).invoke(transform,spec.caller,previous);
            if(Arrays.equals(previous,next))throw new AssertionError("Missing UI transform: "+spec);transformed.put(caller,next);
        }
        for(var spec:signatures.entrySet()) {
            String[] key=spec.getKey().split("#");Object target=resolve(key[0],key[1],null,spec.getValue());
            Method method=(Method)target.getClass().getMethod("\u037f").invoke(target);
            Class<?> transformer=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052c"),owner=method.getDeclaringClass();
            Object transform=transformer.getConstructor(Method.class).newInstance(method);originals.putIfAbsent(owner,bytes(owner));
            byte[] previous=transformed.getOrDefault(owner,originals.get(owner));
            transformed.put(owner,(byte[])transformer.getMethod("\u037f",String.class,byte[].class).invoke(transform,owner.getName(),previous));
        }
        Agent.instrumentation.redefineClasses(transformed.entrySet().stream().map(e->new ClassDefinition(e.getKey(),e.getValue())).toArray(ClassDefinition[]::new));
        surface=Class.forName(SaltVideoBindings.TRANSPORT_CALLER).getMethod("\u037f",Class.forName("androidx.compose.ui.\u0aa0"),boolean.class,Class.forName("androidx.compose.ui.\u091a"),Composer.class,int.class);
        fullscreen=Class.forName(SaltVideoBindings.FULLSCREEN).getMethod("\u037f",Function0.class,boolean.class,Modifier.class,boolean.class,Composer.class,int.class,int.class);
        frame=Class.forName(SaltVideoBindings.WINDOW_FRAME_CALLER).getDeclaredMethod("\u037f",FrameWindowScope.class,boolean.class,Class.forName(SaltVideoBindings.WINDOW_PROPERTIES),
                kotlin.jvm.functions.Function3.class,androidx.compose.ui.window.WindowDecoration.class,BoxScope.class,Composer.class,int.class);frame.setAccessible(true);
        mainWindow=Class.forName(SaltVideoBindings.WINDOW).getDeclaredMethod("\u037f",BoxScope.class,Composer.class,int.class);mainWindow.setAccessible(true);
    }
    void frame(BoxScope box,FrameWindowScope window,kotlin.jvm.functions.Function3<FrameWindowScope,Composer,Integer,Unit> content,Composer composer) {
        try {
            properties=composer.consume((CompositionLocal<?>)Class.forName("androidx.compose.ui.\u0689").getMethod("\u0528").invoke(null));
            frame.invoke(null,window,true,properties,content,null,box,composer,0);
        }catch(Exception failure){throw new AssertionError("Real Windows frame caller",failure);}
    }
    boolean captionEnabled() {
        try{return (boolean)properties.getClass().getMethod("\u052b").invoke(properties);}catch(Exception failure){throw new AssertionError(failure);}
    }
    Object hitTest() throws Exception {
        Object[] args=Bridge.hitTestArguments;
        if(args==null)throw new AssertionError("Missing native Windows hit test");
        Method method=Arrays.stream(Class.forName(SaltVideoBindings.WINDOW_FRAME).getDeclaredMethods())
                .filter(m->m.getName().equals("\u037f") && m.getParameterCount()==10 &&
                        m.getParameterTypes()[0]==androidx.compose.ui.window.WindowState.class).findFirst().orElseThrow();
        method.setAccessible(true);return method.invoke(null,args);
    }
    void audioAction(Runnable action){AudioAction.action=action;}
    void render(Object titleState,Object videoState,Composer composer) {
        try{surface.invoke(null,titleState,false,videoState,composer,0);}catch(Exception failure){throw new AssertionError("Native player surface",failure);}
    }
    void mainWindow(BoxScope box,Composer composer) {
        try{mainWindow.invoke(null,box,composer,0);}catch(Exception failure){throw new AssertionError("Actual MainWindow title and surface caller",failure);}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    void listen(Composer composer) {
        try {
            Object navigator=composer.consume((CompositionLocal<?>)Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null));
            Object sheet=composer.consume((CompositionLocal<?>)Class.forName("androidx.compose.ui.\u041a").getMethod("\u037f").invoke(null));
            var events=(kotlinx.coroutines.flow.Flow)navigator.getClass().getMethod("getPlayerSheetEvents$Voxzen_composeApp").invoke(navigator);
            EffectsKt.LaunchedEffect(navigator,(scope,continuation)->events.collect((event,next)->{
                try{return sheet.getClass().getMethod(event.toString().equals("Expand")?"\u037f":"\u0528",kotlin.coroutines.Continuation.class).invoke(sheet,next);}
                catch(Exception error){throw new AssertionError(error);}
            },continuation),composer,0);
        }catch(Exception error){throw new AssertionError("Native player requests",error);}
    }
    void fullscreen(Modifier modifier,Composer composer) {
        try {
            var config=Class.forName("com.xuncorp.voxzen.util.AppConfig");
            if((boolean)config.getMethod("getWindowFullScreenButton").invoke(config.getField("INSTANCE").get(null)))
                fullscreen.invoke(null,(Function0<Unit>)()->Unit.INSTANCE,false,modifier,true,composer,0,0);
        }catch(Exception error){throw new AssertionError("Native fullscreen button",error);}
    }
    @Override public void close() throws Exception {
        Agent.instrumentation.redefineClasses(originals.entrySet().stream().map(e->new ClassDefinition(e.getKey(),e.getValue())).toArray(ClassDefinition[]::new));Bridge.sites.clear();Bridge.hooks.clear();AudioAction.action=null;
    }
    public static final class AudioAction {
        static Runnable action;
        public static void playOrPause(){action.run();}
    }
    public static final class Agent {
        public static volatile Instrumentation instrumentation;
        public static void agentmain(String args,Instrumentation value){instrumentation=value;}
    }
    public static final class Attach {
        public static void main(String[] args) throws Exception {var vm=com.sun.tools.attach.VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1]);}finally{vm.detach();}}
    }
    private static void attach() throws Exception {
        if(Agent.instrumentation!=null)return;
        Path agent=Files.createTempFile("salt-video-test-agent-",".jar");var manifest=new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Agent-Class",Agent.class.getName());manifest.getMainAttributes().putValue("Can-Redefine-Classes","true");
        try(var jar=new JarOutputStream(Files.newOutputStream(agent),manifest)){jar.putNextEntry(new JarEntry(Agent.class.getName().replace('.','/')+".class"));jar.write(bytes(Agent.class));jar.closeEntry();}
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"--add-modules","jdk.attach","-cp",System.getProperty("java.class.path"),Attach.class.getName(),Long.toString(ProcessHandle.current().pid()),agent.toString()).inheritIO().start();
        if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Test agent timeout");}
        if(process.exitValue()!=0 || Agent.instrumentation==null)throw new AssertionError("Test agent attach failed");
    }
    public static final class Bridge {
        static volatile boolean enabled=true;
        static volatile Object[] hitTestArguments;
        static final Map<Integer,Site> sites=new HashMap<>();static final Map<String,MethodHook> hooks=new HashMap<>();static final Map<String,Integer> calls=new HashMap<>();
        static final Set<Integer> renderedSites=new HashSet<>();
        public static boolean hasReplacement(int id){return enabled && !sites.get(id).after;}
        public static void RenderReplacement(int id,Object receiver,Object[] args,Composer composer,int flags){if(enabled)draw(id,receiver,args,composer);}
        public static void RenderAfter(int id,Object receiver,Object[] args,Composer composer,int flags){if(enabled && sites.get(id).after)draw(id,receiver,args,composer);}
        private static void draw(int id,Object receiver,Object[] args,Composer composer) {
            Site spec=sites.get(id);calls.merge(spec.owner+"@"+spec.caller,1,Integer::sum);renderedSites.add(id);
            UiHookCall call=new UiHookCall(){public Object getThisObject(){return receiver;}public List<Object> getArguments(){return Arrays.asList(args);}public Object getArgument(String name){return null;}};
            try{spec.content.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class).invoke(spec.content,call,composer,0);}
            catch(InvocationTargetException failure){if(failure.getCause() instanceof Error error)throw error;throw new IllegalStateException(failure.getCause());}
            catch(Exception failure){throw new AssertionError(failure);}
        }
        public static WorkshopMethodFrame enter(Class<?> owner,String name,String descriptor,Object receiver,Object[] args) throws Exception {
            String key=owner.getName()+"#"+name;MethodHook hook=hooks.get(key);if(hook==null)return null;calls.merge(key,1,Integer::sum);
            if(owner.getName().equals(SaltVideoBindings.WINDOW_FRAME) && args.length==10)hitTestArguments=args.clone();
            Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);var unsafe=(sun.misc.Unsafe)uf.get(null);
            var frame=(WorkshopMethodFrame)unsafe.allocateInstance(WorkshopMethodFrame.class);
            String[] names={"thisObject","args","method","thread"};Object[] values={receiver,args,Arrays.stream(owner.getDeclaredMethods()).filter(m->m.getName().equals(name) &&
                    java.lang.invoke.MethodType.methodType(m.getReturnType(),m.getParameterTypes()).descriptorString().equals(descriptor)).findFirst().orElseThrow(),Thread.currentThread()};
            for(int i=0;i<names.length;i++){Field field=WorkshopMethodFrame.class.getDeclaredField(names[i]);field.setAccessible(true);field.set(frame,values[i]);}
            hook.before(frame);return frame;
        }
        public static void exit(WorkshopMethodFrame frame,Object result,Throwable failure) throws Throwable {
            if(frame!=null)hooks.get(frame.getMethod().getDeclaringClass().getName()+"#"+frame.getMethod().getName()).after(frame);
        }
    }
}
