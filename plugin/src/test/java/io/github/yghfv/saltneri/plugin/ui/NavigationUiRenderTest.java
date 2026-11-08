package io.github.yghfv.saltneri.plugin.ui;

import androidx.compose.runtime.*;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.unit.*;
import com.xuncorp.spw.workshop.api.config.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.nio.file.Files;
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
    private static final String CAPTION="\u8bbe\u7f6e\u5165\u53e3\u59cb\u7ec8\u4fdd\u7559\uff0c\u5176\u4ed6\u6309\u94ae\u53ef\u81ea\u7531\u663e\u9690\u3002";
    private static void invoke(Method method,Object... args) {
        try {method.invoke(null,args);}
        catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }
    private static java.awt.image.BufferedImage pixels(ImageComposeScene scene,String name) throws Exception {
        try(var image=scene.render(System.nanoTime());var data=image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG,100,0)) {
            String directory=System.getProperty("salt.test.renderDir");
            if(directory!=null) {Files.createDirectories(Path.of(directory));Files.write(Path.of(directory,name+".png"),data.getBytes());}
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data.getBytes()));
        }
    }
    @SuppressWarnings("unchecked")
    private static void styleParity(SaltNativeUi ui,Object nav,Object route,Function3<Object,Composer,Integer,Object> cached,
                                    ProvidableCompositionLocal<Object> local) throws Exception {
        var theme=Class.forName("androidx.compose.ui.\u02b6").getMethod("\u037f",boolean.class,Function2.class,Composer.class,int.class);
        var screen=Class.forName("androidx.compose.ui.\u0e16").getMethod("\u037f",
                androidx.compose.ui.Modifier.class,Function2.class,String.class,String.class,Function2.class,Function4.class,
                Class.forName("androidx.compose.ui.\u093d"),Function4.class,Composer.class,int.class,int.class);
        var caption=Class.forName("androidx.compose.ui.\u02bd").getMethod("\u037f",String.class,androidx.compose.ui.Modifier.class,Composer.class,int.class,int.class);
        var column=Class.forName("androidx.compose.ui.\u02bf").getMethod("\u037f",androidx.compose.ui.Modifier.class,
                androidx.compose.foundation.layout.PaddingValues.class,long.class,Function3.class,Composer.class,int.class,int.class);
        var lazy=Arrays.stream(androidx.compose.foundation.lazy.LazyDslKt.class.getMethods())
                .filter(m->m.getName().equals("LazyColumn") && m.getParameterCount()==13).findFirst().orElseThrow();
        Function2<Composer,Integer,Unit> back=(c,f)->{ui.secondaryBack(()->{ui.settingsBack();return Unit.INSTANCE;},androidx.compose.ui.Modifier.Companion,c);return Unit.INSTANCE;};
        Function3<androidx.compose.foundation.layout.ColumnScope,Composer,Integer,Unit> switches=(scope,c,f)->{
            for(String button:SidebarPolicy.BUTTONS)if(!button.equals("Settings"))ui.sidebarSwitch(button,ui.sidebarVisible(button),c);
            return Unit.INSTANCE;
        };
        Function4<androidx.compose.foundation.layout.BoxScope,androidx.compose.foundation.layout.PaddingValues,Composer,Integer,Unit> body=(scope,insets,c,f)->{
            Function1<androidx.compose.foundation.lazy.LazyListScope,Unit> items=list->{
                list.item(null,null,(item,composer,flags)->{invoke(caption,CAPTION,null,composer,0,2);return Unit.INSTANCE;});
                list.item(null,null,(item,composer,flags)->{invoke(column,null,null,0L,switches,composer,0,7);return Unit.INSTANCE;});
                return Unit.INSTANCE;
            };
            invoke(lazy,androidx.compose.foundation.layout.SizeKt.fillMaxSize(androidx.compose.ui.Modifier.Companion,1f),
                    null,insets,false,null,null,null,false,null,items,c,0,506);
            return Unit.INSTANCE;
        };
        for(var dimensions:new int[][]{{1280,900,100},{520,950,100},{1140,1500,150},{520,420,100}}) {
            int width=dimensions[0],height=dimensions[1];float density=dimensions[2]/100f;
            for(boolean dark:new boolean[]{false,true}) {
                var scenes=new ArrayList<ImageComposeScene>();
                String name=width+"x"+height+"-"+(dark?"dark":"light");
                try {
                    for(boolean reference:new boolean[]{false,true}) {
                        scenes.add(new ImageComposeScene(width,height,DensityKt.Density(density,1f),LayoutDirection.Ltr,Dispatchers.getUnconfined(),(c,f)->{
                            Function2<Composer,Integer,Unit> content=(composer,flags)->{
                                if(reference)invoke(screen,null,back,"\u4fa7\u680f\u7ba1\u7406",null,null,null,null,body,composer,0,121);
                                else cached.invoke(route,composer,flags);
                                return Unit.INSTANCE;
                            };
                            CompositionLocalKt.CompositionLocalProvider(new ProvidedValue[]{local.provides(nav)},(composer,flags)->{
                                invoke(theme,dark,content,composer,0);return Unit.INSTANCE;
                            },c,0);return Unit.INSTANCE;
                        }));
                    }
                    for(var scene:scenes)render(scene);
                    var actual=scenes.get(0);var expected=scenes.get(1);
                    var a=pixels(actual,name+"-sidebar");var b=pixels(expected,name+"-native");
                    int different=0;
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(a.getRGB(x,y)!=b.getRGB(x,y))different++;
                    if(different>width*height/1000)throw new AssertionError("Native settings style mismatch "+name+": "+different+" pixels");
                    checks++;
                    var label=find(actual,CAPTION,SemanticsActions.INSTANCE.getGetTextLayoutResult());
                    var nativeLabel=find(expected,CAPTION,SemanticsActions.INSTANCE.getGetTextLayoutResult());
                    check(label!=null && nativeLabel!=null && label.getBoundsInRoot().equals(nativeLabel.getBoundsInRoot()));
                    // Salt's desktop material lets the app background show through, not a white page fill.
                    if(height>420)check((a.getRGB(width/2,height-2)>>>24)==0);
                    if(height==420) {
                        var scroll=find(actual,null,SemanticsActions.INSTANCE.getScrollBy());
                        check(scroll!=null);
                        check(Boolean.TRUE.equals(scroll.getConfig().get(SemanticsActions.INSTANCE.getScrollBy()).getAction().invoke(0f,10000f)));
                        render(actual);
                        var last=find(actual,"\u7f51\u6613\u4e91",SemanticsProperties.INSTANCE.getToggleableState());
                        check(last!=null && last.getBoundsInRoot().getBottom()<=height);
                        var title=find(actual,"\u4fa7\u680f\u7ba1\u7406",SemanticsActions.INSTANCE.getGetTextLayoutResult());
                        check(title!=null && title.getBoundsInRoot().getTop()<56*density);
                    }
                } finally {for(var scene:scenes)scene.close();}
            }
        }
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
            ui.openSidebarConfiguration();SwingUtilities.invokeAndWait(()->{});
            SwingUtilities.invokeAndWait(()->{
                try {styleParity(ui,nav,stack.getLast(),cached,local);}
                catch(Exception failure) {throw new AssertionError(failure);}
            });
            ui.settingsBack();
            config.set("ui.home","restore");ui.observeNavigation(nav);SwingUtilities.invokeAndWait(()->{});
            check(preferences.initial().equals("BILIBILI"));
            permitted.set(false);check(!ui.settingsPage(stack.getLast()));
        }
        System.out.println("PASS: "+checks+" startup/settings/native switch/failed-save/back/light-dark-DPI-native-style/scroll render checks; no installed config");
    }
}
