package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import com.xuncorp.spw.workshop.api.hook.ComposableHook;
import com.xuncorp.spw.workshop.api.hook.UiHookCall;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/** Real host UI transformer and real caption caller; only the global bridge registry is isolated. */
final class SidebarHookTransformFixture implements AutoCloseable {
    private final URLClassLoader isolated;
    private final Class<?> bridge;
    private final Method toggle;
    private final byte[] transformed;
    private final ComposableHook overlay;
    private final Method overlayDraw;
    SidebarHookTransformFixture(ComposableHook content, ComposableHook overlay) throws Exception {
        this(content,overlay,null);
    }
    SidebarHookTransformFixture(ComposableHook content, ComposableHook overlay,ComposableHook toggleContent) throws Exception {
        this.overlay = overlay;
        overlayDraw = overlay.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class);
        ClassLoader host = getClass().getClassLoader();
        Class<?> resolver = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object target = resolver.getMethod("\u037f",String.class,String.class,String.class,List.class).invoke(resolver.getField("\u037f").get(null),
                SaltSidebarBindings.CAPTION,"\u037f",SaltSidebarBindings.TOGGLE,SaltSidebarBindings.PARAMETERS);
        Class<?> siteType = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u0529$\u052d");
        Object site = siteType.getConstructors()[0].newInstance(271828,target);
        // Match the registrar's resolved default-mask position. Replacement must be skipped for 880.
        siteType.getMethod("\u037f",kotlin.ranges.IntRange.class).invoke(site,new kotlin.ranges.IntRange(12,12));
        Class<?> transformer = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052d");
        Object transform = transformer.getConstructor(siteType).newInstance(site);
        byte[] original;
        try(var in=host.getResourceAsStream(SaltSidebarBindings.TOGGLE.replace('.','/')+".class")) { original=in.readAllBytes(); }
        byte[] captionTransform=(byte[])transformer.getMethod("\u037f",String.class,byte[].class).invoke(transform,SaltSidebarBindings.TOGGLE,original);
        transformed=captionTransform;
        if(Arrays.equals(original,transformed)) throw new AssertionError("Missing real host transform");
        Object rootTarget = resolver.getMethod("\u037f",String.class,String.class,String.class,List.class).invoke(resolver.getField("\u037f").get(null),
                SaltSidebarBindings.TITLEBAR,"\u037f",SaltSidebarBindings.WINDOW,SaltSidebarBindings.TITLE_PARAMETERS);
        Object rootSite = siteType.getConstructors()[0].newInstance(271829,rootTarget);
        Object rootTransform = transformer.getConstructor(siteType).newInstance(rootSite);
        byte[] rootOriginal;
        try(var in=host.getResourceAsStream(SaltSidebarBindings.WINDOW.replace('.','/')+".class")){rootOriginal=in.readAllBytes();}
        byte[] rootResult=(byte[])transformer.getMethod("\u037f",String.class,byte[].class).invoke(rootTransform,SaltSidebarBindings.WINDOW,rootOriginal);
        if(Arrays.equals(rootOriginal,rootResult)) throw new AssertionError("Missing full-width titlebar append");
        Path temp=Files.createTempDirectory("salt-sidebar-transform-");
        Path source=temp.resolve("WorkshopHookBridge.java"), classes=Files.createDirectory(temp.resolve("classes"));
        Files.writeString(source,"""
                package com.xuncorp.voxzen.workshop.hooks;
                import androidx.compose.runtime.Composer;
                import kotlin.Unit;
                import kotlin.jvm.functions.Function3;
                public final class WorkshopHookBridge {
                    public static Function3<Object[],Composer,Integer,Unit> after;
                    public static int replacements, calls;
                    public static boolean hasReplacement(int id) { return true; }
                    public static void RenderReplacement(int id,Object receiver,Object[] args,Composer c,int flags) { replacements++; }
                    public static void RenderAfter(int id,Object receiver,Object[] args,Composer c,int flags) { calls++; after.invoke(args,c,flags); }
                }
                """);
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","-proc:none","-classpath",System.getProperty("java.class.path"),
                "-d",classes.toString(),source.toString())!=0) throw new AssertionError("Bridge fixture compile failed");
        isolated=new URLClassLoader(new URL[]{classes.toUri().toURL()},host) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.equals(SaltSidebarBindings.TOGGLE) && !name.startsWith(SaltSidebarBindings.TOGGLE+"$")
                        && !name.equals("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge")) return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> c=findLoadedClass(name);
                    if(c==null) {
                        if(name.equals("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge")) c=findClass(name);
                        else {
                            byte[] bytes=transformed;
                            if(!name.equals(SaltSidebarBindings.TOGGLE)) try(var in=host.getResourceAsStream(name.replace('.','/')+".class")) {bytes=in.readAllBytes();}
                            catch(Exception e){throw new ClassNotFoundException(name,e);}
                            c=defineClass(name,bytes,0,bytes.length);
                        }
                    }
                    if(resolve)resolveClass(c); return c;
                }
            }
        };
        bridge=isolated.loadClass("com.xuncorp.voxzen.workshop.hooks.WorkshopHookBridge");
        Method hideDraw=toggleContent==null?null:toggleContent.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class);
        Method draw=content.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class);
        bridge.getField("after").set(null,(Function3<Object[],Composer,Integer,Unit>)(args,c,f)->{
            UiHookCall call=new UiHookCall() {
                public Object getThisObject(){return null;}
                public List<Object> getArguments(){return Arrays.asList(args);}
                public Object getArgument(String name){return null;}
            };
            if(args.length!=10 || !Boolean.FALSE.equals(args[4]) || !Float.valueOf(0f).equals(args[6]))
                throw new AssertionError("Expected untouched default-argument placeholders from actual host caller");
            try{if(hideDraw!=null)hideDraw.invoke(toggleContent,call,c,0);draw.invoke(content,call,c,0);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
            return Unit.INSTANCE;
        });
        toggle=isolated.loadClass(SaltSidebarBindings.TOGGLE).getMethod("\u037f",Modifier.class,Composer.class,int.class,int.class);
    }
    void render(Modifier modifier,Composer composer) {
        try {
            Class<?> size=Class.forName("androidx.compose.foundation.layout.SizeKt");
            Modifier root=(Modifier)size.getMethod("height-3ABfNKs",Modifier.class,float.class).invoke(null,
                    androidx.compose.foundation.layout.SizeKt.fillMaxWidth(Modifier.Companion,1f),40f);
            Modifier left=(Modifier)size.getMethod("width-3ABfNKs",Modifier.class,float.class).invoke(null,Modifier.Companion,260f);
            left=(Modifier)size.getMethod("height-3ABfNKs",Modifier.class,float.class).invoke(null,left,40f);
            left=androidx.compose.ui.draw.ClipKt.clipToBounds(left);
            Modifier offset=(Modifier)Class.forName("androidx.compose.foundation.layout.OffsetKt")
                    .getMethod("offset-VpY3zN4",Modifier.class,float.class,float.class).invoke(null,Modifier.Companion,48f,4f);
            offset=(Modifier)size.getMethod("height-3ABfNKs",Modifier.class,float.class).invoke(null,offset,40f);
            final Modifier clipped=left;
            final Modifier captionRow=offset;
            androidx.compose.foundation.layout.BoxKt.Box(root,androidx.compose.ui.Alignment.Companion.getTopStart(),false,(scope,c,f)->{
                androidx.compose.foundation.layout.BoxKt.Box(clipped,androidx.compose.ui.Alignment.Companion.getTopStart(),false,(s,c2,f2)->{
                    // Native titlebar centers the caption container vertically. A zero-height
                    // hidden caption moves the anchor down by half the row height.
                    androidx.compose.foundation.layout.RowKt.Row(captionRow,androidx.compose.foundation.layout.Arrangement.INSTANCE.getStart(),
                            androidx.compose.ui.Alignment.Companion.getCenterVertically(),(s3,c3,f3)->{
                        try{toggle.invoke(null,modifier,c3,0,0);}catch(ReflectiveOperationException e){throw new AssertionError(e);}return Unit.INSTANCE;
                    },c2,0,0);return Unit.INSTANCE;
                },c,0,0);
                UiHookCall call=new UiHookCall() {
                    public Object getThisObject(){return null;}
                    public List<Object> getArguments(){return List.of();}
                    public Object getArgument(String name){return null;}
                };
                try{overlayDraw.invoke(overlay,call,c,0);}catch(ReflectiveOperationException e){throw new AssertionError(e);}return Unit.INSTANCE;
            },composer,0,0);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    int afterCalls() throws Exception {return bridge.getField("calls").getInt(null);}
    int replacementCalls() throws Exception {return bridge.getField("replacements").getInt(null);}
    @Override public void close() throws Exception {isolated.close();}
}
