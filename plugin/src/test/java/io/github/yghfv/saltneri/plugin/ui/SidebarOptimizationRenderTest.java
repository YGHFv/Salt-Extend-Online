package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.unit.*;
import androidx.compose.ui.semantics.*;
import com.xuncorp.spw.workshop.api.hook.*;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.*;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.flow.*;

/** Real native caption, icons, snapshot toggle and navigator; synthetic database/queue only. */
public final class SidebarOptimizationRenderTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Sidebar check " + checks); }
    private static final class Hooks extends HookRegistrar {
        int installed, closed;
        private HookHandle handle() { return new HookHandle() {
            boolean done;
            public void unhook() { if (!done) { done = true; closed++; } }
            public void close() { unhook(); }
        }; }
        public HookHandle hookMethod(String c,String m,List<String> t,MethodHook h) { return handle(); }
        public HookHandle afterComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) { return handle(); }
        public HookHandle replaceComposable(String c,String m,String i,List<String> t,int p,ComposableHook h) {
            check(c.equals(SaltSidebarBindings.CAPTION) && i.equals(SaltSidebarBindings.TOGGLE) && t.equals(SaltSidebarBindings.PARAMETERS));
            installed++; return handle();
        }
        public <S> HookHandle appendContent(String c,String m,String i,String a,Class<S> s,List<String> t,int p,ContentHook<S> h) { throw new AssertionError(); }
    }
    private static Object sheet() throws Exception {
        Constructor<?> wrapper = Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState").getConstructors()[0];
        Constructor<?> state = wrapper.getParameterTypes()[0].getConstructors()[0];
        Object initial = state.getParameterTypes()[0].getEnumConstants()[0];
        var tween = new androidx.compose.animation.core.TweenSpec<Float>(0,0,androidx.compose.animation.core.EasingKt.getLinearEasing());
        return wrapper.newInstance(state.newInstance(initial, DensityKt.Density(1f,1f), tween, (Function1<Object,Boolean>)v -> true));
    }
    private static Object playlist(String id,String title) throws Exception {
        return Class.forName("com.xuncorp.voxzen.data.entity.Playlist").getConstructor(String.class,String.class,long.class,boolean.class,
                String.class,long.class,int.class,int.class,boolean.class,String.class,String.class,byte[].class,byte[].class)
                .newInstance(id,title,0L,false,"",0L,0,0,false,"","",new byte[0],new byte[0]);
    }
    private static UiHookCall call(List<Object> args) { return new UiHookCall() {
        public Object getThisObject() { return null; }
        public List<Object> getArguments() { return args; }
        public Object getArgument(String name) { return null; }
    }; }
    @SuppressWarnings("unchecked")
    public static void main(String[] ignored) throws Exception {
        ClassLoader host = SidebarOptimizationRenderTest.class.getClassLoader();
        Class<?> resolver = Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        check(resolver.getMethod("\u037f",String.class,String.class,String.class,List.class).invoke(resolver.getField("\u037f").get(null),
                SaltSidebarBindings.CAPTION,"\u037f",SaltSidebarBindings.TOGGLE,SaltSidebarBindings.PARAMETERS) != null);
        var permitted = new AtomicBoolean(true); var remote = new AtomicInteger(); var subscription = new AtomicInteger();
        var flow = StateFlowKt.MutableStateFlow(List.of(playlist("fixture-one", "Fixture playlist")));
        try (OnlinePlaybackController player = new OnlinePlaybackController(p->null,permitted::get,()->false,(a,s)->{throw new AssertionError("Playback");});
             LibraryController library = new LibraryController(p->null,permitted::get,t->{throw new AssertionError("Prepare");},()->{},Clock.systemUTC(),
                     p->(q,v,s,r)->{remote.incrementAndGet(); throw new AssertionError("Network");});
             SaltNativeUi ui = new SaltNativeUi(host,new Hooks(),new NativeDesk(library,player,
                     p->new AccountLoginController.Snapshot(AccountLoginController.Phase.IDLE,"",null,""),(p,a)->{},permitted::get),permitted::get);
             SaltSidebarBindings binding = new SaltSidebarBindings(host,()->{subscription.incrementAndGet(); return flow;})) {
            var hooks = new Hooks();
            binding.install(hooks,ui); binding.install(hooks,ui); check(hooks.installed==1);
            check(!binding.enabled()); check(binding.entries().size()==10); check(subscription.get()==0);
            var hidden = new ClassLoader(host) {
                @Override protected Class<?> loadClass(String n,boolean resolve) throws ClassNotFoundException {
                    if(n.equals(SaltSidebarBindings.CAPTION)) throw new ClassNotFoundException("Fixture"); return super.loadClass(n,resolve);
                }
            };
            try { new SaltSidebarBindings(hidden); throw new AssertionError("Missing capability"); } catch(ClassNotFoundException expected) { checks++; }
            Class<?> stateType = Class.forName("androidx.compose.ui.\u09b8");
            AtomicInteger toggles = new AtomicInteger();
            Object state = stateType.getConstructor(boolean.class,Function1.class).newInstance(true,(Function1<Boolean,Unit>)v->{toggles.incrementAndGet();return Unit.INSTANCE;});
            Function0<Unit> toggle = ()->{try {stateType.getMethod("\u052b").invoke(state);return Unit.INSTANCE;}catch(Exception e){throw new AssertionError(e);}};
            var stack = new androidx.navigation3.runtime.NavBackStack<androidx.navigation3.runtime.NavKey>();
            Object initial = binding.entries().getFirst().route(); stack.add((androidx.navigation3.runtime.NavKey)initial);
            Object nav = Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator").getConstructors()[0].newInstance(initial,
                    new HashSet<>(binding.entries().stream().map(SaltSidebarBindings.Entry::route).toList()),stack,sheet());
            var localState = (ProvidableCompositionLocal<Object>)Class.forName("androidx.compose.ui.\u10eb").getMethod("\u037f").invoke(null);
            var localNav = (ProvidableCompositionLocal<Object>)Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null);
            ComposableHook content = (ComposableHook)Class.forName("io.github.yghfv.saltneri.plugin.ui.SaltSidebarContent")
                    .getConstructor(SaltSidebarBindings.class,SaltNativeUi.class).newInstance(binding,ui);
            Method draw = content.getClass().getMethod("Content",UiHookCall.class,Composer.class,int.class);
            Map<Object,String> nativeLabels = new LinkedHashMap<>();
            SwingUtilities.invokeAndWait(()->{
                try {
                    for(int width : new int[]{1280,520}) {
                        binding.enabled(false); stateType.getMethod("\u037f",boolean.class).invoke(state,true);
                        var scene = new ImageComposeScene(width,100,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(composer,flags)->{
                            CompositionLocalKt.CompositionLocalProvider(new ProvidedValue[]{localState.provides(state),localNav.provides(nav)},(c,f)->{
                                for(var entry:binding.entries()) nativeLabels.put(entry.route(),binding.label(entry,c));
                                Modifier modifier=SemanticsModifierKt.semantics(Modifier.Companion,false,s->{SemanticsPropertiesKt.setContentDescription(s,"Fixture toggle");return Unit.INSTANCE;});
                                UiHookCall call=call(Arrays.asList(toggle,"Fixture toggle",binding.painter(binding.entries().getFirst(),c),modifier,
                                        false,null,0f,ui.palette(c).secondary(),null,null));
                                try {draw.invoke(content,call,c,0);}catch(Exception e){throw new AssertionError(e);}
                                return Unit.INSTANCE;
                            },composer,0); return Unit.INSTANCE;
                        });
                        try {
                            HeaderSearchRenderTest.render(scene);
                            check(HeaderSearchRenderTest.find(scene,"Bilibili",SemanticsActions.INSTANCE.getOnClick())==null);
                            int before=subscription.get(); binding.enabled(true); HeaderSearchRenderTest.render(scene);
                            check(subscription.get()==before); check(binding.expanded(state));
                            HeaderSearchRenderTest.click(scene,"Fixture toggle"); check(!binding.expanded(state));
                            check(ui.sidebarStyle()!=null); check(subscription.get()>before);
                            var bili=HeaderSearchRenderTest.find(scene,"Bilibili",SemanticsActions.INSTANCE.getOnClick()); check(bili!=null);
                            if(width==1280) {
                                var toggleNode=HeaderSearchRenderTest.find(scene,"Fixture toggle",SemanticsActions.INSTANCE.getOnClick());
                                check(bili.getBoundsInRoot().getLeft()>=toggleNode.getBoundsInRoot().getRight());
                                check(Math.abs(bili.getBoundsInRoot().getTop()-toggleNode.getBoundsInRoot().getTop())<2);
                                float right=toggleNode.getBoundsInRoot().getRight();
                                for(var entry:binding.entries()) {
                                    String label=nativeLabels.get(entry.route());
                                    var node=HeaderSearchRenderTest.find(scene,label,SemanticsActions.INSTANCE.getOnClick()); check(node!=null);
                                    check(node.getBoundsInRoot().getLeft()>=right); right=node.getBoundsInRoot().getRight();
                                    check(node.getBoundsInRoot().getWidth()==40f);
                                    check(!node.getConfig().contains(SemanticsProperties.INSTANCE.getText()));
                                    HeaderSearchRenderTest.click(scene,label); check(stack.getLast().equals(entry.route()));
                                    node=HeaderSearchRenderTest.find(scene,label,SemanticsActions.INSTANCE.getOnClick());
                                    check(node.getConfig().get(SemanticsProperties.INSTANCE.getSelected()));
                                    check(!binding.expanded(state));
                                }
                                check(HeaderSearchRenderTest.find(scene,"Fixture playlist",SemanticsActions.INSTANCE.getOnClick())!=null);
                                HeaderSearchRenderTest.click(scene,"Fixture playlist");
                                check(stack.getLast().getClass().getSimpleName().equals("PlaylistContent"));
                                check(!binding.expanded(state));
                                HeaderSearchRenderTest.click(scene,"Bilibili"); check(ui.platform(stack.getLast())==AccountPlatform.BILIBILI);
                                HeaderSearchRenderTest.click(scene,"\u7f51\u6613\u4e91"); check(ui.platform(stack.getLast())==AccountPlatform.NETEASE);
                                flow.setValue(List.of()); HeaderSearchRenderTest.render(scene);
                                check(HeaderSearchRenderTest.find(scene,"Fixture playlist",SemanticsActions.INSTANCE.getOnClick())==null);
                            }
                            var scroll=HeaderSearchRenderTest.find(scene,null,SemanticsActions.INSTANCE.getScrollBy()); check(scroll!=null);
                            check(scroll.getBoundsInRoot().getRight()<=width-320+1);
                            binding.enabled(false); HeaderSearchRenderTest.render(scene);
                            check(!binding.expanded(state)); check(HeaderSearchRenderTest.find(scene,"Bilibili",SemanticsActions.INSTANCE.getOnClick())==null);
                            binding.enabled(true); HeaderSearchRenderTest.render(scene);
                            check(HeaderSearchRenderTest.find(scene,"Bilibili",SemanticsActions.INSTANCE.getOnClick())!=null);
                            HeaderSearchRenderTest.click(scene,"Fixture toggle"); check(binding.expanded(state));
                            check(HeaderSearchRenderTest.find(scene,"Bilibili",SemanticsActions.INSTANCE.getOnClick())==null);
                            HeaderSearchRenderTest.click(scene,"Fixture toggle");
                            if(width==520) {
                                permitted.set(false); HeaderSearchRenderTest.render(scene);
                                check(HeaderSearchRenderTest.find(scene,"Bilibili",SemanticsActions.INSTANCE.getOnClick())==null);
                            }
                        } finally { scene.close(); }
                    }
                }catch(Exception e){throw new AssertionError(e);}
            });
            binding.close(); binding.close(); check(hooks.closed==1 && !binding.enabled());
            check(toggles.get()>=6); check(remote.get()==0);
        }
        System.out.println("PASS: "+checks+" native sidebar checks; toggle/restore, opt-in, selection/navigation, playlists, overflow and permission loss; no user data or playback");
    }
}
