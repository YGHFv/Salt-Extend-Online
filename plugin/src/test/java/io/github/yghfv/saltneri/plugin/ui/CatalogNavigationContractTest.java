package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import io.github.yghfv.saltneri.plugin.auth.*;
import io.github.yghfv.saltneri.plugin.library.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import kotlin.jvm.functions.Function1;

/** Temporary preferences and an isolated real Salt navigator; no installed config or audio. */
public final class CatalogNavigationContractTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok) throw new AssertionError("Catalog navigation "+checks); }
    private static final class Config implements ConfigHelper {
        final Path path; final Properties values=new Properties(); boolean fail;
        Config(Path path) { this.path=path; reload(); }
        @SuppressWarnings("unchecked") public <T> T get(String key,T fallback) { return (T)values.getOrDefault(key,fallback); }
        public void set(String key,Object value) { values.put(key,value); }
        public boolean save() { if(fail)return false; try(var out=Files.newOutputStream(path)) {values.store(out,"");return true;}catch(Exception e){throw new AssertionError(e);} }
        public boolean reload() {if(Files.exists(path))try(var in=Files.newInputStream(path)){values.load(in);}catch(Exception e){throw new AssertionError(e);}return true;}
        public Path getConfigPath() {return path;}
    }
    private static void ready(LibraryController library) throws Exception {
        for(int i=0;i<400;i++) {if(library.snapshot().get("loaded").equals(true))return;Thread.sleep(5);}throw new AssertionError("Catalog timeout");
    }
    private static String token(LibraryController library) {return (String)((Map<?,?>)((List<?>)library.snapshot().get("items")).getFirst()).get("token");}
    private static Object navigator(Object route,boolean expanded) throws Exception {
        Class<?> sheet=Class.forName("androidx.compose.ui.\u1076"), state=Class.forName("androidx.compose.ui.\u1077");
        Object raw=sheet.getConstructors()[0].newInstance(state.getField(expanded?"\u0528":"\u037f").get(null),
                androidx.compose.ui.unit.DensityKt.Density(1,1),new androidx.compose.animation.core.SpringSpec<Float>(),(Function1<Object,Boolean>)v->true);
        Class<?> player=Class.forName("com.xuncorp.voxzen.ui.component.PlayerSheetState");
        Object root=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$MusicLibrary").getField("INSTANCE").get(null);
        var stack=new androidx.navigation3.runtime.NavBackStack<androidx.navigation3.runtime.NavKey>();
        stack.add((androidx.navigation3.runtime.NavKey)root); stack.add((androidx.navigation3.runtime.NavKey)route);
        return Class.forName("com.xuncorp.voxzen.ui.navigation.Navigator").getConstructors()[0].newInstance(root,Set.of(root),stack,player.getConstructor(sheet).newInstance(raw));
    }
    public static void main(String[] args) throws Exception {
        Path path=Files.createTempDirectory("salt-layout-fixture").resolve("catalog-ui.properties");
        Config config=new Config(path); CatalogPreferences prefs=new CatalogPreferences(config);
        check(prefs.layout(AccountPlatform.BILIBILI).equals("SINGLE"));
        prefs.layout(AccountPlatform.BILIBILI,"CARDS"); prefs.layout(AccountPlatform.NETEASE,"DOUBLE");
        prefs=new CatalogPreferences(new Config(path));
        check(prefs.layout(AccountPlatform.BILIBILI).equals("CARDS") && prefs.layout(AccountPlatform.NETEASE).equals("DOUBLE"));
        config.set("layout.bilibili","bad"); check(new CatalogPreferences(config).layout(AccountPlatform.BILIBILI).equals("SINGLE"));
        config.set("layout.bilibili",true); check(new CatalogPreferences(config).layout(AccountPlatform.BILIBILI).equals("SINGLE"));
        config.fail=true;
        try {new CatalogPreferences(config).layout(AccountPlatform.NETEASE,"CARDS");throw new AssertionError("Failed save accepted");}catch(IllegalStateException expected){checks++;}
        check(new CatalogPreferences(config).layout(AccountPlatform.NETEASE).equals("DOUBLE"));
        try(var player=new OnlinePlaybackController(p->null,()->true,()->false,(a,v)->{throw new AssertionError("Played");});
            var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("Prepared");},()->{},Clock.systemUTC(),a->(q,k,s,r)->new Catalog.Page("Fixture",
                    List.of(q.view()==Catalog.View.FOLDER?new Catalog.Item(Catalog.Kind.TRACK,"BV17x411w7KC","Song","UP",1000,true,null,99):new Catalog.Item(Catalog.Kind.FOLDER,"10","Folder","UP",1,true)),false,Instant.MAX))) {
            var desk=new NativeDesk(library,player,p->new AccountLoginController.Snapshot(AccountLoginController.Phase.IDLE,"",null,""),(p,a)->{},()->true);
            try(var ui=new SaltNativeUi(CatalogNavigationContractTest.class.getClassLoader(),new NativePageRenderTest.Hooks(),desk,()->true)) {
                var navField=SaltNativeUi.class.getDeclaredField("navigator");navField.setAccessible(true);
                Object route=Class.forName(SaltNativeUi.MOD_ROUTE).getConstructor(String.class).newInstance(SaltNativeUi.PLUGIN_ID+"/native/BILIBILI");
                Object nav=navigator(route,false);navField.set(ui,nav);
                library.browse(AccountPlatform.BILIBILI,Catalog.View.MINE);ready(library);library.choose(token(library));ready(library);
                check(!ui.internalBack(nav)); Object lease=ui.pageEntered(AccountPlatform.BILIBILI);
                check(!ui.internalBack(new Object()));
                Object expanded=navigator(route,true);navField.set(ui,expanded);check(!ui.internalBack(expanded));navField.set(ui,nav);
                library.author(token(library));ready(library);check(ui.internalBack(nav));ready(library);
                check(library.snapshot().get("view").equals("FOLDER"));
                library.snapshot(AccountPlatform.NETEASE);check(ui.internalBack(nav));ready(library);
                check(library.snapshot().get("view").equals("MINE") && !ui.internalBack(nav));
                check(((List<?>)nav.getClass().getMethod("getNavBackStack").invoke(nav)).size()==2);
                nav.getClass().getMethod("back").invoke(nav);
                check(((List<?>)nav.getClass().getMethod("getNavBackStack").invoke(nav)).size()==1);
                ui.pageLeft(lease);check(!ui.internalBack(nav));
            }
        }
        System.out.println("PASS: "+checks+" persistent independent layouts, failed-save rollback, real navigator internal/root/expanded/invisible back checks; synthetic only");
    }
}
