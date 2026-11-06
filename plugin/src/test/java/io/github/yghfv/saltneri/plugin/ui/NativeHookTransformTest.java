package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import kotlin.jvm.functions.Function1;

/** Actual Salt Advice/ByteBuddy transform; isolated bridge fixture, no running app or accounts. */
public final class NativeHookTransformTest {
    public static final class ResolverFixture {
        public static Function1<Object, Object> build(List<String> page) { return key -> resolve(page); }
        private static Object resolve(List<String> page) { return page.getFirst(); }
    }
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Transform check " + checks); }
    public static void main(String[] args) throws Exception {
        ClassLoader host = NativeHookTransformTest.class.getClassLoader();
        String fixture = ResolverFixture.class.getName();
        Class<?> transformerType = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052c", false, host);
        Object transformer = transformerType.getConstructor(Method.class).newInstance(ResolverFixture.class.getDeclaredMethod("resolve", List.class));
        byte[] original;
        try (var input = host.getResourceAsStream(fixture.replace('.', '/') + ".class")) { original = input.readAllBytes(); }
        byte[] transformed = (byte[])transformerType.getMethod("\u037f", String.class, byte[].class).invoke(transformer, fixture, original);
        check(!Arrays.equals(original, transformed));
        Path temporary = Files.createTempDirectory("salt-native-hook-fixture-");
        Path sources = Files.createDirectories(temporary.resolve("src"));
        Path classes = Files.createDirectories(temporary.resolve("classes"));
        Path frame = sources.resolve("WorkshopMethodFrame.java"), bridge = sources.resolve("WorkshopHookBridge.java");
        Files.writeString(frame, """
                package com.xuncorp.voxzen.workshop.hooks;
                import com.xuncorp.spw.workshop.api.hook.*;
                import java.lang.reflect.*;
                public final class WorkshopMethodFrame extends MethodHookParam {
                    final Class<?> owner; final String name; final Object receiver; final Object[] args;
                    Object result; Throwable failure; boolean skipped;
                    WorkshopMethodFrame(Class<?> owner, String name, Object receiver, Object[] args) {
                        this.owner=owner; this.name=name; this.receiver=receiver; this.args=args;
                    }
                    public Method getMethod() { for (Method m:owner.getDeclaredMethods()) if(m.getName().equals(name)) return m; throw new AssertionError(); }
                    public Object getThisObject() { return receiver; }
                    public Object[] getArgs() { return args; }
                    public Object getResult() { return result; }
                    public Throwable getThrowable() { return failure; }
                    public boolean isOriginalSkipped() { return skipped; }
                    public void setResult(Object value) { result=value; failure=null; skipped=true; }
                    public void setThrowable(Throwable value) { failure=value; result=null; skipped=true; }
                    public Object invokeOriginal() { throw new AssertionError("No fixture bypass"); }
                }
                """);
        Files.writeString(bridge, """
                package com.xuncorp.voxzen.workshop.hooks;
                import com.xuncorp.spw.workshop.api.hook.*;
                public final class WorkshopHookBridge {
                    public static MethodHook hook;
                    public static WorkshopMethodFrame enter(Class<?> owner,String name,String descriptor,Object receiver,Object[] args) throws Throwable {
                        if(hook==null) return null;
                        WorkshopMethodFrame f=new WorkshopMethodFrame(owner,name,receiver,args); hook.before(f); return f;
                    }
                    public static void exit(WorkshopMethodFrame f,Object value,Throwable failure) throws Throwable {
                        if(!f.skipped) { f.result=value; f.failure=failure; } hook.after(f);
                    }
                }
                """);
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "25", "-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString(), frame.toString(), bridge.toString()) == 0);
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{classes.toUri().toURL()}, host) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(fixture) || name.equals("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge")
                            || name.equals("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame")) {
                        Class<?> value = findLoadedClass(name);
                        if (value == null) value = name.equals(fixture) ? defineClass(name, transformed, 0, transformed.length) : findClass(name);
                        if (resolve) resolveClass(value); return value;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            List<String> page = new ArrayList<>(List.of("generic"));
            @SuppressWarnings("unchecked") Function1<Object, Object> cached = (Function1<Object, Object>)isolated.loadClass(fixture)
                    .getMethod("build", List.class).invoke(null, page);
            check(cached.invoke("key").equals("generic"));
            Field callback = isolated.loadClass("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge").getField("hook");
            callback.set(null, new MethodHook() {
                @Override public void after(MethodHookParam call) { call.setResult("replacement"); }
            });
            // The shipped Advice reads and pops getResult(). Frame changes cannot replace the return.
            check(cached.invoke("key").equals("generic"));
            callback.set(null, new MethodHook() {
                @Override public void before(MethodHookParam call) {
                    @SuppressWarnings("unchecked") List<String> target = (List<String>)call.getArgs()[0];
                    target.set(0, "native");
                }
            });
            check(cached.invoke("key").equals("native"));
            callback.set(null, null);
            check(cached.invoke("key").equals("native"));
        }
        System.out.println("PASS: " + checks + " actual host method-transform checks; cached resolver/object mutation works, result override is discarded; synthetic bridge only");
    }
}
