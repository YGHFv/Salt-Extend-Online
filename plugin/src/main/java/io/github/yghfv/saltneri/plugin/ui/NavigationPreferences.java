package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import io.github.yghfv.saltneri.plugin.library.Catalog;
import java.util.*;

/** Only stable navigation identities are persisted, never stack objects or playback state. */
public final class NavigationPreferences {
    public static final String RESTORE="restore", TRACK="Track";
    private final ConfigHelper config;
    public NavigationPreferences(ConfigHelper config) {this.config=Objects.requireNonNull(config);}
    public static boolean valid(String value) {
        return value!=null && (Set.of(RESTORE,TRACK,"BILIBILI","NETEASE","QQMUSIC").contains(value)
                || value.startsWith("playlist:") && value.length()>9 && value.length()<200
                && value.chars().noneMatch(Character::isISOControl));
    }
    private String string(String key,String fallback) {Object v=config.get(key,fallback);return v instanceof String s?s:fallback;}
    public String home() {String v=string("ui.home",RESTORE);return valid(v)?v:RESTORE;}
    public String initial() {
        String value=home();
        if(!RESTORE.equals(value))return value;
        value=string("ui.lastPage",TRACK);return valid(value) && !RESTORE.equals(value)?value:TRACK;
    }
    public void remember(String value) {
        if(!valid(value) || RESTORE.equals(value) || value.equals(string("ui.lastPage","")))return;
        String previous=string("ui.lastPage",TRACK);
        config.set("ui.lastPage",value);
        if(!config.save())config.set("ui.lastPage",previous);
    }
    public Catalog.View platformHome(AccountPlatform platform) {
        return switch(string(platform.configPrefix()+".home","DISCOVER")) {
            case "MINE" -> Catalog.View.MINE;
            case "SAVED" -> Catalog.View.SAVED;
            default -> Catalog.View.DISCOVER;
        };
    }
}
