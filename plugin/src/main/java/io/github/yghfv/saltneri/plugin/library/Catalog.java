package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.time.Instant;
import java.util.List;

public final class Catalog {
    private Catalog() { }
    public enum View { DISCOVER, MUSIC_HOT, MUSIC_ORIGINAL, MINE, SAVED, FOLDER, COLLECTION, SEARCH, AUTHOR_VIDEOS, AUTHOR_COLLECTIONS }
    public enum Kind { FOLDER, COLLECTION, TRACK }
    public enum Sort { DEFAULT, REVERSE, TITLE, ARTIST, DURATION, PLAY_COUNT }
    public record Query(AccountPlatform platform, View view, long folder, int page, long owner, String keyword, boolean reverse) {
        public Query(AccountPlatform platform, View view, long folder, int page, long owner, String keyword) { this(platform,view,folder,page,owner,keyword,false); }
        public Query(AccountPlatform platform, View view, long folder, int page) { this(platform, view, folder, page, 0, ""); }
        public Query(AccountPlatform platform, View view, long folder, int page, long owner) { this(platform, view, folder, page, owner, ""); }
        public Query {
            keyword = keyword == null ? "" : keyword.strip();
            if (platform == null || view == null || page < 1 || page > 500
                    || (view == View.MUSIC_HOT || view == View.MUSIC_ORIGINAL) && platform != AccountPlatform.BILIBILI
                    || (view == View.FOLDER || view == View.COLLECTION ? folder <= 0 : folder != 0)
                    || (view == View.COLLECTION || view == View.AUTHOR_VIDEOS || view == View.AUTHOR_COLLECTIONS ? platform != AccountPlatform.BILIBILI || owner <= 0 : owner != 0)
                    || reverse && view!=View.COLLECTION
                    || (view == View.SEARCH ? keyword.isEmpty() || keyword.length() > 100
                        || keyword.chars().anyMatch(c -> Character.isISOControl(c)) : !keyword.isEmpty()))
                throw new IllegalArgumentException("Invalid catalog query");
        }
        @Override public String toString() { return "CatalogQuery[" + platform + ", " + view + ", page=" + page + ", search redacted]"; }
    }
    public static String duration(long millis) {
        if(millis<=0) return "--:--";
        long seconds=millis/1000;
        return seconds>=3600 ? String.format(java.util.Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
                : String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
    /** No URL/cookie in the UI model. target is a logical ID, not a media address. */
    public record Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled, java.net.URI cover, long owner, long playCount) {
        public Item { playCount = Math.max(-1, playCount); }
        public Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled, java.net.URI cover, long owner) {
            this(kind, target, title, subtitle, count, enabled, cover, owner, -1);
        }
        public Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled, java.net.URI cover) {
            this(kind, target, title, subtitle, count, enabled, cover, 0);
        }
        public Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled) {
            this(kind, target, title, subtitle, count, enabled, null);
        }
        @Override public String toString() { return "CatalogItem[" + kind + ", image source redacted]"; }
    }
    public record Page(String title, List<Item> items, boolean hasMore, Instant expiresAt, int totalPages, long totalCount) {
        public Page(String title, List<Item> items, boolean hasMore, Instant expiresAt) { this(title, items, hasMore, expiresAt, 0); }
        public Page(String title, List<Item> items, boolean hasMore, Instant expiresAt, int totalPages) { this(title, items, hasMore, expiresAt, totalPages, -1); }
        public Page { items = List.copyOf(items); if (items.size() > 20 || totalPages < 0 || totalPages > 500) throw new IllegalArgumentException("Catalog page too large"); }
    }
}
