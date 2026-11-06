package io.github.yghfv.saltneri.plugin.library;

import io.github.yghfv.saltneri.plugin.auth.AccountPlatform;
import java.time.Instant;
import java.util.List;

public final class Catalog {
    private Catalog() { }
    public enum View { DISCOVER, MINE, SAVED, FOLDER, COLLECTION, SEARCH }
    public enum Kind { FOLDER, COLLECTION, TRACK }
    public record Query(AccountPlatform platform, View view, long folder, int page, long owner, String keyword) {
        public Query(AccountPlatform platform, View view, long folder, int page) { this(platform, view, folder, page, 0, ""); }
        public Query(AccountPlatform platform, View view, long folder, int page, long owner) { this(platform, view, folder, page, owner, ""); }
        public Query {
            keyword = keyword == null ? "" : keyword.strip();
            if (platform == null || view == null || page < 1 || page > 500
                    || (view == View.FOLDER || view == View.COLLECTION ? folder <= 0 : folder != 0)
                    || (view == View.COLLECTION ? platform != AccountPlatform.BILIBILI || owner <= 0 : owner != 0)
                    || (view == View.SEARCH ? platform != AccountPlatform.BILIBILI || keyword.isEmpty() || keyword.length() > 100
                        || keyword.chars().anyMatch(c -> Character.isISOControl(c)) : !keyword.isEmpty()))
                throw new IllegalArgumentException("Invalid catalog query");
        }
        @Override public String toString() { return "CatalogQuery[" + platform + ", " + view + ", page=" + page + ", search redacted]"; }
    }
    /** No URL/cookie in the UI model. target is a logical ID, not a media address. */
    public record Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled, java.net.URI cover, long owner) {
        public Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled, java.net.URI cover) {
            this(kind, target, title, subtitle, count, enabled, cover, 0);
        }
        public Item(Kind kind, String target, String title, String subtitle, long count, boolean enabled) {
            this(kind, target, title, subtitle, count, enabled, null);
        }
        @Override public String toString() { return "CatalogItem[" + kind + ", image source redacted]"; }
    }
    public record Page(String title, List<Item> items, boolean hasMore, Instant expiresAt, int totalPages) {
        public Page(String title, List<Item> items, boolean hasMore, Instant expiresAt) { this(title, items, hasMore, expiresAt, 0); }
        public Page { items = List.copyOf(items); if (items.size() > 20 || totalPages < 0 || totalPages > 500) throw new IllegalArgumentException("Catalog page too large"); }
    }
}
