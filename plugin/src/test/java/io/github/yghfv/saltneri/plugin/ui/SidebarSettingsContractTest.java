package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Actual native groups/state and resolver, with recording hooks; no host user settings. */
public final class SidebarSettingsContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Sidebar settings "+checks);}
    private static final class Hooks extends NativeHooks {
        final Map<String,MethodHook> methods=new HashMap<>();
        final List<String[]> sites=new ArrayList<>();
        boolean rejectSettings;
        @Override public HookHandle hookMethod(String c,String m,List<String> t,MethodHook h) {
            try {
                Class<?> resolver=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
                check(resolver.getMethod("\u037f",String.class,String.class,List.class)
                        .invoke(resolver.getField("\u037f").get(null),c,m,t)!=null);
            } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
            methods.put(c+"#"+m,h);return handle();
        }
        @Override public HookHandle replaceComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) {
            try {
                Class<?> resolver=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
                check(resolver.getMethod("\u037f",String.class,String.class,String.class,List.class)
                        .invoke(resolver.getField("\u037f").get(null),c,m,i,t)!=null);
            } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
            if(rejectSettings && c.equals(SaltSidebarSettingsBindings.SCREEN))throw new IllegalArgumentException("Fixture rejected settings");
            sites.add(new String[]{c,m,i});return handle();
        }
    }
    private static class NativeHooks extends HookRegistrar {
        HookHandle handle() {return new HookHandle() {public void close(){}public void unhook(){}};}
        public HookHandle hookMethod(String c,String m,List<String> t,MethodHook h) {return handle();}
        public HookHandle afterComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) {return handle();}
        public HookHandle replaceComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) {return handle();}
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S>s,List<String>t,int p,ContentHook<S>h) {throw new AssertionError();}
    }
    private static final class Call extends MethodHookParam {
        boolean skipped;final Object owner;final Object[] args;
        Call(Object owner,Object...args) {this.owner=owner;this.args=args;}
        public Method getMethod(){return null;} public Object getThisObject(){return owner;} public Object[] getArgs(){return args;}
        public Object getResult(){return null;} public Throwable getThrowable(){return null;} public boolean isOriginalSkipped(){return skipped;}
        public void setResult(Object value){check(value==null);skipped=true;} public void setThrowable(Throwable t){throw new AssertionError();}
        public Object invokeOriginal(){throw new AssertionError();}
    }
    public static void main(String[] args) throws Throwable {
        Class<?> resolver=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        try {
            resolver.getMethod("\u037f",String.class,String.class,List.class).invoke(resolver.getField("\u037f").get(null),
                    SaltSidebarBindings.TOGGLE,"\u037f",List.of("androidx.compose.ui.Modifier","androidx.compose.runtime.Composer","int","int"));
            throw new AssertionError("Host must reject method advice on Compose");
        } catch(InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException && expected.getCause().getMessage().startsWith("Use a Compose hook"));
        }
        var policy=new SidebarPolicy();check(!policy.hidden() && SidebarPolicy.BUTTONS.stream().allMatch(policy::visible));
        policy.update(false,true,Set.of("MusicLibrary"));check(!policy.hidden() && !policy.visible("MusicLibrary") && policy.visible("NETEASE"));
        policy.update(true,true,Set.of());check(policy.hidden());
        policy.update(true,true,Set.copyOf(SidebarPolicy.BUTTONS));
        check(policy.visible("Settings") && !policy.visible("Track"));
        var hooks=new Hooks();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        ClassLoader host=SidebarSettingsContractTest.class.getClassLoader();
        try(var player=new OnlinePlaybackController(p->null,allowed::get,()->false,(a,v)->{throw new AssertionError();});
            var library=new LibraryController(p->null,allowed::get,t->{throw new AssertionError();},()->{});
            var ui=new SaltNativeUi(host,hooks,new NativeDesk(library,player,p->null,(p,a)->{},allowed::get),allowed::get);
            var bindings=new SaltSidebarSettingsBindings(host,new NativeHooks(),ui)) {
            check(hooks.sites.size()==2);
            check(ui.nativeSecondaryHeaderAvailable());
            ui.sidebarSettings(false,false,true,Set.of());check(ui.hiddenSidebarToggle() && !ui.hiddenSidebar());
            ui.sidebarSettings(false,false,false,Set.of());check(!ui.hiddenSidebarToggle());
            var sw=Class.forName("androidx.compose.ui.\u077b$\u052b").getConstructor(String.class,String.class,String.class,String.class,boolean.class,String.class);
            Object optimization=sw.newInstance("opt","ui.sidebarOptimization","Optimization","",false,"config.json");
            Object hidden=sw.newInstance("hide","ui.hiddenSidebar","Hidden","",false,"config.json");
            Class<?> group=Class.forName(SaltSidebarSettingsBindings.GROUP);
            Object config=group.getConstructor(String.class,String.class,String.class,List.class).newInstance("fixture","Settings","config.json",List.of(optimization,hidden));
            Object filtered=bindings.filteredGroup(config,SaltNativeUi.PLUGIN_ID,false);
            check(((List<?>)group.getMethod("\u0529").invoke(filtered)).equals(List.of(optimization)));
            check(bindings.filteredGroup(config,SaltNativeUi.PLUGIN_ID,true)==config);
            check(bindings.filteredGroup(config,"another.plugin",false)==config);
            Object settings=sw.newInstance("settings","ui.sidebar.Settings","Settings","",true,"config.json");
            config=group.getConstructor(String.class,String.class,String.class,List.class).newInstance("fixture","Settings","config.json",List.of(optimization,hidden,settings));
            check(((List<?>)group.getMethod("\u0529").invoke(bindings.filteredGroup(config,SaltNativeUi.PLUGIN_ID,true))).equals(List.of(optimization,hidden)));
            var managed=(List<?>)group.getMethod("\u0529").invoke(bindings.sidebarGroup());
            check(managed.size()==11);
            var keys=new HashSet<String>();
            for(var item:managed)keys.add((String)item.getClass().getMethod("\u037f").invoke(item));
            check(!keys.contains("ui.sidebar.Settings") && keys.contains("ui.sidebar.NETEASE"));
            var list=Class.forName("androidx.compose.ui.\u077b$\u0529");
            Object home=list.getConstructor(String.class,String.class,String.class,String.class,List.class,List.class,String.class,String.class)
                    .newInstance("home","ui.home","Home","",List.of("Restore"),List.of("restore"),"restore","config.json");
            config=group.getConstructor(String.class,String.class,String.class,List.class).newInstance("fixture","Settings","config.json",List.of(home));
            bindings.playlistChoices(List.of(new io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice("one","Playlist one",new Object()),
                    new io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice("two","Playlist two",new Object())));
            var options=((List<?>)group.getMethod("\u0529").invoke(bindings.filteredGroup(config,SaltNativeUi.PLUGIN_ID,true))).getFirst();
            check(list.getMethod("\u052b").invoke(options).equals(List.of("restore","Track","BILIBILI","NETEASE","playlist:one","playlist:two")));
            check(((List<?>)list.getMethod("\u052a").invoke(options)).size()==6);
            Object settingsRoute=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.SIDEBAR_CONFIG);
            check(ui.settingsPage(settingsRoute) && ui.platform(settingsRoute)==null);
            Class<?> stateType=Class.forName("androidx.compose.ui.\u09b8");
            Object state=stateType.getConstructor(boolean.class,Function1.class).newInstance(false,(Function1<Boolean,Unit>)v->Unit.INSTANCE);
            ui.sidebarSettings(true,true,Set.of("Track"));
            for(String setter:List.of("\u0528","\u0529")) {
                var call=new Call(state,true);hooks.methods.get(stateType.getName()+"#"+setter).before(call);check(call.skipped);
                call=new Call(state,false);hooks.methods.get(stateType.getName()+"#"+setter).before(call);check(!call.skipped);
            }
            stateType.getMethod("\u052b").invoke(state);check((boolean)stateType.getMethod("\u037f").invoke(state));
            stateType.getMethod("\u052b").invoke(state);check(!(boolean)stateType.getMethod("\u037f").invoke(state));
            ui.sidebarSettings(false,true,Set.of());
            var call=new Call(state,true);hooks.methods.get(stateType.getName()+"#\u0528").before(call);check(!call.skipped);
            stateType.getMethod("\u0528",boolean.class).invoke(state,false);
            stateType.getMethod("\u0528",boolean.class).invoke(state,true);check((boolean)stateType.getMethod("\u052a").invoke(state));
            ui.sidebarSettings(true,true,Set.of());ui.collapseHiddenSidebar(state);check(!(boolean)stateType.getMethod("\u052a").invoke(state));
            allowed.set(false);call=new Call(state,true);hooks.methods.get(stateType.getName()+"#\u0528").before(call);check(!call.skipped);
        }
        allowed.set(true);var rejecting=new Hooks();rejecting.rejectSettings=true;
        try(var player=new OnlinePlaybackController(p->null,allowed::get,()->false,(a,v)->{throw new AssertionError();});
            var library=new LibraryController(p->null,allowed::get,t->{throw new AssertionError();},()->{});
            var ui=new SaltNativeUi(host,rejecting,new NativeDesk(library,player,p->null,(p,a)->{},allowed::get),allowed::get)) {
            javax.swing.SwingUtilities.invokeAndWait(()->{});
            check(ui.available() && ui.diagnostics().contains("bound=true"));
            check(ui.diagnostics().contains("settings-hooks/IllegalArgumentException"));
            var route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class);
            for(var platform:AccountPlatform.values())
                check(ui.platform(route.newInstance(SaltNativeUi.PLUGIN_ID+"/native/"+platform.name()))==platform);
            rejecting.rejectSettings=false;ui.repairSidebar();
            check(ui.available() && ui.diagnostics().contains("sidebarSettings=none"));
        }
        System.out.println("PASS: "+checks+" native sidebar visibility/settings conditional/hover guard/explicit click/restore/revoke and exact call-site checks");
    }
}
