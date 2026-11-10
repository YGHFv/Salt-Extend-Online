package io.github.yghfv.saltneri.plugin.ui;

import io.github.yghfv.saltneri.plugin.net.BoundedJson;
import java.io.IOException;
import java.util.*;

/** Secondary settings share the original config keys and one plugin-owned config file. */
public final class SettingsCatalog {
    public enum Page {
        SIDEBAR("sidebar","侧栏管理"), AUTO_SOURCE("auto-source","自动换源"),
        QUALITY("quality","音质"), HOME("home","默认栏目");
        private final String id,title;
        Page(String id,String title){this.id=id;this.title=title;}
        public String id(){return id;} public String title(){return title;}
    }
    public record Setting(String key,String type,String title,String summary,Object defaultValue,
                          List<String> entries,List<String> values,String action) {
        public Setting {entries=List.copyOf(entries);values=List.copyOf(values);}
        public String label(Object value) {
            int index=values.indexOf(String.valueOf(value));
            if(index<0)index=values.indexOf(String.valueOf(defaultValue));
            return index<0?"":entries.get(index);
        }
    }
    private static final Map<Page,List<Setting>> ITEMS=load();
    private static Map<Page,List<Setting>> load() {
        try(var input=SettingsCatalog.class.getResourceAsStream("/settings-pages.json")) {
            if(input==null)throw new IOException("Missing settings pages");
            var root=BoundedJson.object(BoundedJson.parse(input.readAllBytes()));
            var result=new EnumMap<Page,List<Setting>>(Page.class);
            for(Page page:Page.values()) {
                var items=new ArrayList<Setting>();
                for(Object value:BoundedJson.array(root.get(page.id()))) {
                    var item=BoundedJson.object(value);
                    items.add(new Setting(text(item,"key"),text(item,"type"),text(item,"title"),text(item,"summary"),
                            item.getOrDefault("default_value",""),strings(item.get("entries")),strings(item.get("entry_values")),text(item,"action")));
                }
                result.put(page,List.copyOf(items));
            }
            return Map.copyOf(result);
        } catch(IOException failure) {throw new ExceptionInInitializerError(failure);}
    }
    private static String text(Map<String,Object> value,String key){return value.get(key) instanceof String text?text:"";}
    private static List<String> strings(Object value) throws IOException {
        if(value==null)return List.of();
        var result=new ArrayList<String>();
        for(Object item:BoundedJson.array(value))result.add(BoundedJson.string(item));
        return result;
    }
    public static List<Setting> items(Page page){return ITEMS.get(page);}
    public static Optional<Page> page(String route) {
        return Arrays.stream(Page.values()).filter(page->(SaltNativeUi.PLUGIN_ID+"/settings/"+page.id()).equals(route)).findFirst();
    }
}
