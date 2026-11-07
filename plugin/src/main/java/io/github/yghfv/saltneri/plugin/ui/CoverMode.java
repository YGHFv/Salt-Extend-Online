package io.github.yghfv.saltneri.plugin.ui;

/** Presentation only. Source images remain untouched and FIT is the default. */
public enum CoverMode {
    FIT, CENTER, LEFT, RIGHT;
    public static CoverMode parse(String value) {
        try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch(RuntimeException invalid) { return FIT; }
    }
    public int left(int width,int height) {
        int extra=Math.max(0,width-Math.min(width,height));
        return this==LEFT?0:this==RIGHT?extra:extra/2;
    }
}
