package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.Composer;
import androidx.compose.foundation.layout.PaddingValues;
import androidx.compose.ui.Modifier;
import com.xuncorp.spw.workshop.api.hook.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlin.jvm.functions.Function4;

/** Narrow native render boundaries: no global route mutation or second settings persistence. */
public final class SaltSidebarSettingsBindings implements AutoCloseable {
    public static final String SCREEN="androidx.compose.ui.\u0b8f", GROUP="androidx.compose.ui.\u042c";
    private final Method route, group, groupItems, itemKey, collapse, switchWidget, settingsScreen, settingsCaption, settingsColumn;
    private final Field configPath;
    private final Constructor<?> groupConstructor;
    private final Constructor<?> listConstructor,switchConstructor;
    private final float captionHeight;
    private final List<HookHandle> handles=new ArrayList<>();
    public SaltSidebarSettingsBindings(ClassLoader host,HookRegistrar hooks,SaltNativeUi ui) throws ReflectiveOperationException {
        Class<?> sidebar=Class.forName(SaltNativeUi.SIDEBAR,false,host);
        route=sidebar.getDeclaredMethod("\u0528",Class.forName(SaltNativeUi.ROUTE_ITEM,false,host),Composer.class,int.class);
        Class<?> screen=Class.forName(SCREEN,false,host),g=Class.forName(GROUP,false,host);
        group=screen.getDeclaredMethod("\u0528",g,String.class,Composer.class,int.class);
        groupItems=g.getMethod("\u0529");
        itemKey=Class.forName("androidx.compose.ui.\u077b$\u052b",false,host).getMethod("\u037f");
        groupConstructor=g.getConstructor(String.class,String.class,String.class,List.class);
        switchConstructor=itemKey.getDeclaringClass().getConstructor(String.class,String.class,String.class,String.class,boolean.class,String.class);
        switchWidget=Arrays.stream(Class.forName("androidx.compose.ui.\u0824",false,host).getMethods())
                .filter(m->m.getName().equals("\u037f") && m.getParameterCount()==12 && m.getParameterTypes()[0]==boolean.class)
                .findFirst().orElseThrow(()->new NoSuchMethodException("Native setting switch unavailable"));
        settingsScreen=Class.forName("androidx.compose.ui.\u0e16",false,host).getMethod("\u037f",
                Modifier.class,Function2.class,String.class,String.class,Function2.class,Function4.class,
                Class.forName("androidx.compose.ui.\u093d",false,host),Function4.class,Composer.class,int.class,int.class);
        settingsCaption=Class.forName("androidx.compose.ui.\u02bd",false,host).getMethod("\u037f",
                String.class,Modifier.class,Composer.class,int.class,int.class);
        settingsColumn=Class.forName("androidx.compose.ui.\u02bf",false,host).getMethod("\u037f",
                Modifier.class,PaddingValues.class,long.class,Function3.class,Composer.class,int.class,int.class);
        listConstructor=Class.forName("androidx.compose.ui.\u077b$\u0529",false,host).getConstructor(
                String.class,String.class,String.class,String.class,List.class,List.class,String.class,String.class);
        configPath=g.getDeclaredField("\u0529");
        collapse=Class.forName("androidx.compose.ui.\u09b8",false,host).getMethod("\u052c");
        captionHeight=(float)Class.forName("androidx.compose.ui.\u0766",false,host).getMethod("\u0529").invoke(null);
        if(!Float.isFinite(captionHeight) || captionHeight<16 || captionHeight>100)throw new NoSuchMethodException("Unsupported caption height");
        Class<?> node=Class.forName("androidx.compose.ui.node.LayoutNode",false,host);
        node.getMethod("getChildren$ui");node.getMethod("getParent$ui");
        node.getMethod("getOuterCoordinator$ui");
        node.getMethod("getModifier");node.getMethod("setModifier",androidx.compose.ui.Modifier.class);
        Class.forName("androidx.compose.ui.node.NodeCoordinator",false,host).getMethod("getLayoutNode");
        for(AccessibleObject a:List.of(route,group,configPath)) if(!a.trySetAccessible())throw new IllegalAccessException("Native sidebar settings inaccessible");
        try {
            handles.add(hooks.hookMethod("androidx.compose.ui.\u05d0","\u037f",List.of(),new MethodHook() {
                @Override public void before(MethodHookParam call) {if(ui.available())ui.sidebarViewModel(call.getThisObject());}
            }));
            handles.add(hooks.afterComposable(SaltSidebarBindings.CAPTION,"\u037f",SaltSidebarBindings.TOGGLE,
                    SaltSidebarBindings.PARAMETERS,60,
                    (ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltSidebarToggleContent")
                            .getConstructor(SaltNativeUi.class).newInstance(ui)));
            handles.add(hooks.replaceComposable(SaltNativeUi.SIDEBAR,"\u037f",SaltNativeUi.SIDEBAR_CALL,List.of(SaltNativeUi.ROUTE_ITEM),50,
                    (ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltSidebarRouteContent")
                            .getConstructor(SaltSidebarSettingsBindings.class,SaltNativeUi.class).newInstance(this,ui)));
            handles.add(hooks.replaceComposable(SCREEN,"\u037f",SCREEN+"$\u052a",List.of(GROUP,"java.lang.String"),50,
                    (ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltSidebarSettingsContent")
                            .getConstructor(SaltSidebarSettingsBindings.class,SaltNativeUi.class).newInstance(this,ui)));
            for(String setter:List.of("\u0528","\u0529")) {
                handles.add(hooks.hookMethod("androidx.compose.ui.\u09b8",setter,List.of("boolean"),new MethodHook() {
                    @Override public void before(MethodHookParam call) {
                        // Block only preview entry; hover exit still clears native state and explicit clicks remain native.
                        if(Boolean.TRUE.equals(call.getArgs()[0]) && ui.available() && ui.hiddenSidebar()) call.setResult(null);
                    }
                }));
            }
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {close();throw failure;}
    }
    private static Object invoke(Method m,Object receiver,Object... args) {
        try {return m.invoke(receiver,args);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native sidebar render failed");}
    }
    public void route(Object item,Composer composer) {invoke(route,null,item,composer,0);}
    public Object filteredGroup(Object value,String plugin,boolean optimized) {
        if(SaltNativeUi.PLUGIN_ID.equals(plugin)) {
            List<?> items=(List<?>)invoke(groupItems,value);
            List<?> filtered=items.stream().filter(item->!itemKey.getDeclaringClass().isInstance(item)
                    || !"ui.sidebar.Settings".equals(invoke(itemKey,item)) && (optimized || !"ui.hiddenSidebar".equals(invoke(itemKey,item)))).toList();
            List<Object> dynamic=new ArrayList<>();
            for(Object item:filtered) {
                if(listConstructor.getDeclaringClass().isInstance(item) && "ui.home".equals(invoke(valueMethod(item,"\u037f"),item))) {
                    List<String> labels=new ArrayList<>(List.of("\u6062\u590d\u4e0a\u6b21","\u6b4c\u66f2","Bilibili","\u7f51\u6613\u4e91"));
                    List<String> values=new ArrayList<>(List.of("restore","Track","BILIBILI","NETEASE"));
                    for(var playlist:uiPlaylists()) {labels.add("\u6b4c\u5355 \u00b7 "+playlist.title());values.add("playlist:"+playlist.id());}
                    try {item=listConstructor.newInstance("salt-home","ui.home","\u0053alt \u9996\u9875","\u542f\u52a8\u65f6\u6253\u5f00\u7684\u9875\u9762",labels,values,"restore","config.json");}
                    catch(ReflectiveOperationException failure) {throw new IllegalStateException("Home options unavailable",failure);}
                }
                dynamic.add(item);
            }
            filtered=dynamic;
            if(!filtered.equals(items)) try {
                value=groupConstructor.newInstance(invoke(value.getClass().getMethod("\u037f"),value),
                        invoke(value.getClass().getMethod("\u0528"),value),configPath.get(value),filtered);
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native settings filtering failed");}
        }
        return value;
    }
    private List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> playlistChoices=List.of();
    public void playlistChoices(List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> values) {playlistChoices=List.copyOf(values);}
    private List<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice> uiPlaylists() {return playlistChoices;}
    private static Method valueMethod(Object owner,String name) {
        try{return owner.getClass().getMethod(name);}catch(NoSuchMethodException failure){throw new IllegalStateException(failure);}
    }
    public Object sidebarGroup() {
        List<Object> switches=new ArrayList<>();
        Map<String,String> labels=Map.ofEntries(Map.entry("Track","\u6b4c\u66f2"),Map.entry("Genre","\u6d41\u6d3e"),Map.entry("Album","\u4e13\u8f91"),
                Map.entry("Artist","\u827a\u672f\u5bb6"),Map.entry("Folder","\u6587\u4ef6\u5939"),Map.entry("MusicLibrary","\u97f3\u4e50\u5e93"),Map.entry("SoundEffects","\u97f3\u6548"),
                Map.entry("NewPlaylist","\u65b0\u5efa\u6b4c\u5355"),Map.entry("ImportPlaylist","\u5bfc\u5165\u6b4c\u5355"),Map.entry("BILIBILI","Bilibili"),Map.entry("NETEASE","\u7f51\u6613\u4e91"));
        try {
            for(String button:SidebarPolicy.BUTTONS) if(!"Settings".equals(button))
                switches.add(switchConstructor.newInstance("sidebar-"+button,"ui.sidebar."+button,labels.get(button),"",true,"config.json"));
            return groupConstructor.newInstance("sidebar-managed","\u4fa7\u680f\u7ba1\u7406","config.json",switches);
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Sidebar settings unavailable",failure);}
    }
    public void group(Object value,String plugin,boolean optimized,Composer composer) {invoke(group,null,filteredGroup(value,plugin,optimized),plugin,composer,0);}
    public String label(String button) {
        try {
            for(Object item:(List<?>)invoke(groupItems,sidebarGroup()))
                if(("ui.sidebar."+button).equals(invoke(itemKey,item)))return (String)invoke(valueMethod(item,"\u0528"),item);
        } catch(RuntimeException failure) {throw failure;}
        return button;
    }
    public void switchSetting(boolean value,String label,kotlin.jvm.functions.Function1<Boolean,kotlin.Unit> action,Composer composer) {
        invoke(switchWidget,null,value,action,label,null,false,null,null,null,"",composer,0,248);
    }
    public void settingsScreen(String title,Object back,Object content,Composer composer) {
        // Same BasicScreenBox as ModConfigScreen, including theme material and player insets.
        invoke(settingsScreen,null,null,back,title,null,null,null,null,content,composer,0,121);
    }
    public void settingsCaption(String caption,Composer composer) {
        invoke(settingsCaption,null,caption,null,composer,0,2);
    }
    public void settingsColumn(Object content,Composer composer) {
        invoke(settingsColumn,null,null,null,0L,content,composer,0,7);
    }
    public void collapse(Object state) {invoke(collapse,state);}
    public float captionHeight() {return captionHeight;}
    @Override public void close() {
        for(int i=handles.size()-1;i>=0;i--)handles.get(i).close();
        handles.clear();
    }
}
