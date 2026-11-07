package io.github.yghfv.saltneri.plugin.netease;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Salt accepts Enhanced LRC, not raw YRC. Preserve upstream word times without interpolation. */
public final class NeteaseLyricsText {
    public static final int MAX_CHARS=262144;
    private static final long MAX_TIME=7L*24*3600*1000;
    private static final Pattern YRC=Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})](.*)$");
    private static final Pattern WORD=Pattern.compile("\\((\\d{1,9}),(\\d{1,9}),\\d+\\)([^()]*)");
    private static final Pattern TIME=Pattern.compile("\\[(\\d{1,5}):([0-5]\\d)(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET=Pattern.compile("(?im)^\\[offset:([+-]?\\d{1,9})]\\s*$");
    private record Line(long start, String text) { }
    private NeteaseLyricsText() { }
    private static String field(Map<String,Object> root,String key) throws IOException {
        if(!(root.get(key) instanceof Map<?,?> map) || !(map.get("lyric") instanceof String value)) return "";
        if(value.length()>MAX_CHARS || value.indexOf('\0')>=0) throw new IOException("Invalid lyric text");
        return value.replace("\r\n","\n").replace('\r','\n').replace("\ufeff","");
    }
    public static String convert(Map<String,Object> root) throws IOException {
        if(Boolean.TRUE.equals(root.get("nolyric"))) return null;
        List<Line> original=yrc(field(root,"yrc")); boolean words=!original.isEmpty();
        if(!words) original=lrc(field(root,"lrc"));
        if(original.isEmpty()) return null;
        String translated=words?field(root,"ytlrc"):"";
        if(translated.isBlank()) translated=field(root,"tlyric");
        Map<Long,String> translations=new HashMap<>();
        for(Line line:lrc(translated)) translations.putIfAbsent(line.start(),line.text());
        StringBuilder out=new StringBuilder();
        for(Line line:original) {
            out.append('[').append(time(line.start())).append(']').append(line.text()).append('\n');
            String translation=translations.get(line.start());
            if(translation!=null && !translation.equals(line.text())) out.append('[').append(time(line.start())).append(']').append(translation).append('\n');
            if(out.length()>MAX_CHARS) throw new IOException("Lyrics exceed host limit");
        }
        return out.toString();
    }
    private static long offset(String text) { var match=OFFSET.matcher(text); long result=0; while(match.find()) result=Long.parseLong(match.group(1)); return result; }
    private static boolean valid(long time) { return time>=0 && time<=MAX_TIME; }
    private static String time(long ms) { return String.format(Locale.ROOT,"%02d:%02d.%03d",ms/60000,ms/1000%60,ms%1000); }
    private static List<Line> lrc(String text) throws IOException {
        List<Line> result=new ArrayList<>(); long offset=offset(text);
        for(String raw:text.split("\n")) {
            var match=TIME.matcher(raw); List<Long> times=new ArrayList<>(); int end=0;
            while(match.find() && match.start()==end) {
                String fraction=match.group(3); long ms=Long.parseLong(match.group(1))*60000+Long.parseLong(match.group(2))*1000;
                if(fraction!=null) ms+=Integer.parseInt((fraction+"00").substring(0,3));
                ms+=offset; if(valid(ms)) times.add(ms); end=match.end();
            }
            String content=raw.substring(end).strip();
            if(content.isEmpty()) continue;
            for(long ms:times) result.add(new Line(ms,content));
            if(result.size()>10000) throw new IOException("Too many lyric lines");
        }
        result.sort(Comparator.comparingLong(Line::start)); return result;
    }
    private static List<Line> yrc(String text) throws IOException {
        List<Line> result=new ArrayList<>(); long offset=offset(text);
        for(String raw:text.split("\n")) {
            var line=YRC.matcher(raw); if(!line.matches()) continue;
            long start=Long.parseLong(line.group(1))+offset,end=start+Long.parseLong(line.group(2));
            if(!valid(start) || !valid(end) || end<=start) return List.of();
            String body=line.group(3); var word=WORD.matcher(body); StringBuilder content=new StringBuilder(); int pos=0,count=0; long last=start;
            while(word.find()) {
                if(word.start()!=pos || ++count>4096) return List.of();
                long at=Long.parseLong(word.group(1))+offset,until=at+Long.parseLong(word.group(2));
                if(at<last || until<at || until>end) return List.of();
                // Explicit end markers keep silent gaps from stretching the previous word.
                content.append('<').append(time(at)).append('>').append(word.group(3)).append('<').append(time(until)).append('>');
                last=until; pos=word.end();
            }
            if(pos!=body.length() || count==0) return List.of();
            result.add(new Line(start,content.toString()));
            if(result.size()>10000) throw new IOException("Too many lyric lines");
        }
        result.sort(Comparator.comparingLong(Line::start)); return result;
    }
}
