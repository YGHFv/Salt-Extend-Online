package io.github.yghfv.saltneri.plugin.library;

import java.util.List;

public final class AutoSourceMatcherContractTest {
    private static int checks;
    private static void check(boolean ok) {checks++;if(!ok)throw new AssertionError("Auto source matcher "+checks);}
    public static void main(String[] args) {
        var song=new AutoSourceMatcher.Song("God knows...","Hirano Aya",240000);
        check(AutoSourceMatcher.queries(song).equals(List.of("God knows... Hirano Aya \u65e0\u635f","Hirano Aya God knows... \u65e0\u635f","God knows... \u65e0\u635f")));
        check(AutoSourceMatcher.score(song,"GOD KNOWS! Hirano Aya","Uploader",244000)==110);
        check(AutoSourceMatcher.score(song,"God knows","Hirano Aya",255000)==102);
        check(AutoSourceMatcher.score(song,"God knows","Uploader",270000)==67);
        check(AutoSourceMatcher.score(song,"God knows","Uploader",600000)==40);
        check(!AutoSourceMatcher.acceptable(song,"God knows live","Hirano Aya",240000,220));
        check(!AutoSourceMatcher.acceptable(song,"God knows cover","Hirano Aya",240000,220));
        check(!AutoSourceMatcher.acceptable(song,"God knows remix","Hirano Aya",240000,220));
        check(!AutoSourceMatcher.acceptable(song,"God knows","Hirano Aya",1000000,220));
        check(!AutoSourceMatcher.acceptable(song,"Wrong track","Hirano Aya",240000,220));
        check(!AutoSourceMatcher.acceptable(song,"God knows","Uploader",240000,69));
        check(AutoSourceMatcher.acceptable(song,"God knows","Hirano Aya",245000,110));
        check(AutoSourceMatcher.acceptable(new AutoSourceMatcher.Song("God knows live","Hirano Aya",240000),"God knows live","Hirano Aya",240000,110));
        for(long[] test:new long[][]{{0,0},{599000,1},{600000,2},{1799000,2},{1800000,3},{3600000,4}})
            check(AutoSourceMatcher.durationFilter(test[0])==test[1]);
        check(AutoSourceMatcher.queries(new AutoSourceMatcher.Song("","",0)).isEmpty());
        System.out.println("PASS: "+checks+" reference query/scoring/duration and alternative-version gates; offline");
    }
}
