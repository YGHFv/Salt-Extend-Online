package io.github.yghfv.saltneri.plugin.ui;

import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.util.*;

/** UI-only storage, separate from credentials and playback quality preferences. */
public final class CatalogPreferences {
    private final ConfigHelper config;
    private final Map<AccountPlatform,String> memory=new EnumMap<>(AccountPlatform.class);
    public CatalogPreferences() { this(null); }
    public CatalogPreferences(ConfigHelper config) { this.config=config; }
    private static String key(AccountPlatform platform) { return "layout."+platform.name().toLowerCase(Locale.ROOT); }
    private static boolean valid(String value) { return Set.of("SINGLE","DOUBLE","CARDS").contains(value); }
    public synchronized String layout(AccountPlatform platform) {
        Object raw=config==null?memory.getOrDefault(platform,"CARDS"):config.get(key(platform),(Object)"CARDS");
        return raw instanceof String value && valid(value)?value:"CARDS";
    }
    public synchronized void layout(AccountPlatform platform,String value) {
        if(value==null || !valid(value)) throw new IllegalArgumentException("Invalid catalog layout");
        if(config!=null) {
            String previous=layout(platform); config.set(key(platform),value);
            if(!config.save()) { config.set(key(platform),previous); throw new IllegalStateException("Catalog layout could not be saved"); }
        } else memory.put(platform,value);
    }
}
