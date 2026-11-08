package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.unit.*;
import com.xuncorp.spw.workshop.api.config.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import kotlin.Unit;
import kotlin.jvm.functions.*;
import kotlinx.coroutines.Dispatchers;
import static io.github.yghfv.saltneri.plugin.ui.HeaderSearchRenderTest.*;

/** Real secondary-page header/switches/navigation with in-memory configuration only. */
public final class NavigationUiRenderTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Navigation UI "+checks);}
    private static final class Config implements ConfigHelper {
        final Map<String,Object> values=new HashMap<>();boolean fail;int saves;
        @SuppressWarnings("unchecked") public <T>T get(String key,T fallback) {return (T)values.getOrDefault(key,fallback);}
        public void set(String key,Object value) {values.put(key,value);}
        public boolean save() {saves++;return !fail;}
        public boolean reload() {return true;}
        public Path getConfigPath() {return Path.of("fixture-only.json");}
    }
    private static Object navigator() throws Exception {
        Constructor<?> wrapper=Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState").getConstructors()[0];
        Constructor<?> state=wrapper.getParameterTypes()[0].getConstructors()[0];
        Object sheet=wrapper.newInstance(state.newInstance(state.getParameterTypes()[0].getEnumConstants()[0],DensityKt.Density(1f,1f),
                new androidx.compose.animation.core.TweenSpec<Float>(0,0,androidx.compose.animation.core.EasingKt.getLinearEasing()),
                (Function1<Object,Boolean>)v->true));
        Object track=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$Track").getField("INSTANCE").get(null);
        var stack=new androidx.navigation3.runtime.NavBackStack<androidx.navigation3.runtime.NavKey>();
        stack.add((androidx.navigation3.runtime.NavKey)track);
        return Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator").getConstructors()[0].newInstance(track,Set.of(track),stack,sheet);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var config=new Config();var preferences=new NavigationPreferences(config);
        var manager=(ConfigManager)Proxy.newProxyInstance(ConfigManager.class.getClassLoader(),new Class<?>[]{ConfigManager.class},(p,m,a)->{
            if(m.getName().equals("getConfig"))return config;
            if(m.getName().contains("ConfigChangeListener"))return null;
            throw new AssertionError(m);
        });
        var permitted=new AtomicBoolean(true);
        try(var player=new OnlinePlaybackController(p->null,permitted::get,()->false,(a,s)->{throw new AssertionError("Playback");});
            var library=new LibraryController(p->null,permitted::get,t->{throw new AssertionError("Playback");},()->{});
            var ui=new SaltNativeUi(NavigationUiRenderTest.class.getClassLoader(),new NativePageRenderTest.Hooks(),
                    new NativeDesk(library,player,p->null,(p,a)->{},permitted::get),permitted::get)) {
            ui.configuration(manager,preferences);
            Object nav=navigator();var stack=(List<Object>)nav.getClass().getMethod("getNavBackStack").invoke(nav);
            var field=SaltNativeUi.class.getDeclaredField("navigator");field.setAccessible(true);field.set(ui,nav);
            config.set("ui.home","BILIBILI");
            SwingUtilities.invokeAndWait(()->{ui.observeNavigation(nav);check(stack.size()==1);});
            SwingUtilities.invokeAndWait(()->{});SwingUtilities.invokeAndWait(()->{});
            check(ui.platform(stack.getLast())==io.github.yghfv.saltneri.plugin.auth.AccountPlatform.BILIBILI);
            ui.openSidebarConfiguration();SwingUtilities.invokeAndWait(()->{});
            check(ui.settingsPage(stack.getLast()));
            var local=(ProvidableCompositionLocal<Object>)Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null);
            var singleton=Class.forName(SaltPageContentBinding.SINGLETON);
            var cached=(Function3<Object,Composer,Integer,Object>)singleton.getMethod(SaltPageContentBinding.GETTER).invoke(singleton.getField("INSTANCE").get(null));
            SwingUtilities.invokeAndWait(()->{
                var scene=new ImageComposeScene(760,1000,DensityKt.Density(1f,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(c,f)->{
                    CompositionLocalKt.CompositionLocalProvider(new ProvidedValue[]{local.provides(nav)},(composer,flags)->{
                        cached.invoke(stack.getLast(),composer,flags);return Unit.INSTANCE;
                    },c,0);return Unit.INSTANCE;
                });
                try {
                    render(scene);
                    check(find(scene,"\u4fa7\u680f\u7ba1\u7406",null)!=null);
                    check(find(scene,"Bilibili",SemanticsProperties.INSTANCE.getToggleableState())!=null);
                    check(find(scene,"\u8bbe\u7f6e",SemanticsProperties.INSTANCE.getToggleableState())==null);
                    click(scene,"Bilibili");check(config.get("ui.sidebar.BILIBILI",true)==false && !ui.sidebarVisible("BILIBILI"));
                    config.fail=true;click(scene,"Bilibili");
                    check(config.get("ui.sidebar.BILIBILI",true)==false && !ui.sidebarVisible("BILIBILI"));
                    config.fail=false;click(scene,"Bilibili");check(ui.sidebarVisible("BILIBILI"));
                    ui.settingsBack();check(!ui.settingsPage(stack.getLast()));
                }catch(Exception failure){throw new AssertionError(failure);}
                finally {scene.close();}
            });
            config.set("ui.home","restore");ui.observeNavigation(nav);SwingUtilities.invokeAndWait(()->{});
            check(preferences.initial().equals("BILIBILI"));
            permitted.set(false);check(!ui.settingsPage(stack.getLast()));
        }
        System.out.println("PASS: "+checks+" deferred startup/secondary-settings/native switch/failed-save/back/last-page render checks; no installed config");
    }
}
