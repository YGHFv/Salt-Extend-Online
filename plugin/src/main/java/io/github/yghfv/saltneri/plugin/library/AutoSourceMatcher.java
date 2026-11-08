package io.github.yghfv.saltneri.plugin.library;

/* Scoring/query policy adapted from NeriPlayer PlayerManagerNeteaseAutoSourceSwitch.kt.
 * GPL-3.0-or-later. Version gates and bounded network work are plugin-specific. */

import java.util.*;
import java.util.regex.Pattern;

/** Shared by NetEase today and a future QQ Music adapter; never resolves credentials or URLs. */
public final class AutoSourceMatcher {
    public static final int SEARCH_LIMIT=6, MIN_SCORE=70;
    public record Song(String title,String artist,long durationMillis) { }
    private static final Pattern NON_TEXT=Pattern.compile("[^\\p{L}\\p{N}]+"), SPACE=Pattern.compile("\\s+");
    private AutoSourceMatcher() { }
    public static List<String> queries(Song song) {
        if(song.title().isBlank()) return List.of();
        return List.of(song.title()+" "+song.artist(),song.artist()+" "+song.title(),song.title()).stream()
                .map(s->SPACE.matcher(s.strip()).replaceAll(" ")).filter(s->!s.isBlank())
                .distinct().map(s->s+" \u65e0\u635f").filter(s->s.length()<=100).toList();
    }
    public static int durationFilter(long durationMillis) {
        long seconds=durationMillis/1000;
        return seconds<=0?0:seconds<600?1:seconds<1800?2:seconds<3600?3:4;
    }
    public static int score(Song song,String title,String author,long durationMillis) {
        String normalized=normalize(title);
        int score=contains(normalized,song.title())?55:overlap(normalized,song.title());
        if(!song.artist().isBlank() && (contains(normalized,song.artist()) || contains(normalize(author),song.artist())))score+=25;
        if(song.durationMillis()>0 && durationMillis>0) {
            long diff=Math.abs(durationMillis-song.durationMillis());
            score+=diff<=8000?30:diff<=20000?22:diff<=45000?12:durationMillis>song.durationMillis()*2?-15:0;
        }
        return score;
    }
    public static boolean acceptable(Song song,String title,String artist,long duration,int combinedScore) {
        // Avoid a similar-duration cover/live/remix being mistaken for the original recording.
        if(!contains(normalize(title),song.title()) || combinedScore<MIN_SCORE)return false;
        for(String tag:List.of("live","\u73b0\u573a","\u7ffb\u5531","cover","remix","\u4f34\u594f","instrumental","\u52a0\u901f","\u964d\u8c03"))
            if(contains(normalize(title),tag) && !contains(normalize(song.title()),tag))return false;
        return song.durationMillis()<=0 || duration<=0 || Math.abs(duration-song.durationMillis())<=Math.max(45000,song.durationMillis()/5);
    }
    private static String normalize(String text) {return SPACE.matcher(NON_TEXT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ")).replaceAll(" ").strip();}
    private static boolean contains(String text,String needle) {
        String compact=normalize(needle).replace(" ","");return compact.length()>=2 && text.replace(" ","").contains(compact);
    }
    private static int overlap(String text,String needle) {
        var tokens=Arrays.stream(normalize(needle).split(" ")).filter(s->s.length()>=2).toList();
        long hits=tokens.stream().filter(text::contains).count();return tokens.isEmpty()?0:hits==tokens.size()?35:hits>0?18:0;
    }
}
