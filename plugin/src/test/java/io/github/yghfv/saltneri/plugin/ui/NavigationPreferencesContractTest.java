package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.*;
import java.nio.file.Path;
import java.util.*;

/** Stable preferences and platform entry defaults; recording catalog only. */
public final class NavigationPreferencesContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Home preference "+checks);}
    private static final class Config implements ConfigHelper {
        final Map<String,Object> values=new HashMap<>();int saves;boolean fail;
        @SuppressWarnings("unchecked") public <T>T get(String key,T fallback) {return (T)values.getOrDefault(key,fallback);}
        public void set(String key,Object value) {values.put(key,value);}
        public boolean save() {saves++;return !fail;}
        public boolean reload() {return true;}
        public Path getConfigPath() {return Path.of("fixture-only.json");}
    }
    public static void main(String[] args) throws Exception {
        var config=new Config();var preferences=new NavigationPreferences(config);
        check(preferences.home().equals("restore") && preferences.initial().equals("Track"));
        for(String bad:Arrays.asList(null,"","restore/invalid","playlist:","playlist:\n","playlist:"+"x".repeat(200)))
            check(!NavigationPreferences.valid(bad));
        for(String value:List.of("Track","BILIBILI","NETEASE","playlist:one")) {
            preferences.remember(value);check(preferences.initial().equals(value));
            int saved=config.saves;preferences.remember(value);check(config.saves==saved);
            config.set("ui.home","NETEASE");check(preferences.initial().equals("NETEASE"));config.set("ui.home","restore");
        }
        config.fail=true;preferences.remember("BILIBILI");check(preferences.initial().equals("playlist:one"));config.fail=false;
        int saves=config.saves;preferences.remember(null);preferences.remember("restore");check(config.saves==saves);
        config.set("ui.home",true);config.set("ui.lastPage",true);check(preferences.initial().equals("Track"));
        for(var platform:AccountPlatform.values())for(var home:List.of(Catalog.View.DISCOVER,Catalog.View.MINE,Catalog.View.SAVED)) {
            config.set((platform==AccountPlatform.BILIBILI?"bili":"netease")+".home",home.name());
            check(preferences.platformHome(platform)==home);
            try(var library=new LibraryController(p->null,()->true,t->{throw new AssertionError("Playback");},()->{})) {
                check(library.enter(platform,home));
                check(library.snapshot(platform).get("view").equals(home.name()));
                check(!library.enter(platform,Catalog.View.DISCOVER));
                check(library.snapshot(platform).get("view").equals(home.name()));
            }
        }
        config.set("bili.home",true);config.set("netease.home","INVALID");
        check(preferences.platformHome(AccountPlatform.BILIBILI)==Catalog.View.DISCOVER);
        check(preferences.platformHome(AccountPlatform.NETEASE)==Catalog.View.DISCOVER);
        System.out.println("PASS: "+checks+" startup/last-page/playlist/platform-home validation and failed-save checks; no host files or network");
    }
}
