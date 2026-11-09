package io.github.yghfv.saltneri.plugin.qqmusic;

/* QRC/text and nearest translation behavior adapted from mygo-qyin (AGPL-3.0).
 * Salt serialization, bounded XML handling and explicit gap markers are local. */

import io.github.yghfv.saltneri.plugin.netease.NeteaseLyricsText;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

public final class QqMusicLyricsText {
    private static final long MAX_TIME=7L*24*3600*1000;
    private static final Pattern CONTENT=Pattern.compile("\\bLyricContent\\s*=\\s*([\"'])(.*?)\\1",Pattern.DOTALL);
    private static final Pattern LINE=Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})](.*)$");
    private static final Pattern WORD=Pattern.compile("\\((\\d{1,9}),(\\d{1,9})\\)");
    private static final Pattern OFFSET=Pattern.compile("(?im)^\\[offset:([+-]?\\d{1,9})]\\s*$");
    private static final Pattern TIME=Pattern.compile("^\\[(\\d{1,5}):([0-5]\\d)(?:[.:](\\d{1,3}))?]");
    private static final Pattern ENTITY=Pattern.compile("&(#x[0-9A-Fa-f]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos);");
    private record Word(long start,long end,String text) { }
    private record Line(long start,long end,String text,List<Word> words) { }
    private QqMusicLyricsText() { }
    public static String convert(String original,String translation) throws IOException {
        original=normalize(original);translation=normalize(translation);
        List<Line> lines=qrc(original);
        if(lines.isEmpty())return NeteaseLyricsText.convert(Map.of("lrc",Map.of("lyric",cleanTranslation(original)),
                "tlyric",Map.of("lyric",cleanTranslation(translation))));
        List<Line> translated=qrc(translation);
        if(translated.isEmpty())translated=lrc(translation);
        Map<Line,String> paired=new HashMap<>();
        for(Line t:translated) {
            int left=0,right=lines.size();
            while(left<right) {
                int middle=(left+right)>>>1;
                if(lines.get(middle).start()<t.start())left=middle+1;else right=middle;
            }
            Line best=null;long distance=1500;
            for(int index=Math.max(0,left-1);index<=left && index<lines.size();index++) {
                Line line=lines.get(index);long gap=Math.abs(line.start()-t.start());
                if(gap<distance) {distance=gap;best=line;}
            }
            if(best!=null && !paired.containsKey(best) && !t.text().equals(best.text()))paired.put(best,t.text());
        }
        StringBuilder out=new StringBuilder();
        for(Line line:lines) {
            out.append('[').append(time(line.start())).append(']');
            for(Word word:line.words())out.append('<').append(time(word.start())).append('>')
                    .append(word.text()).append('<').append(time(word.end())).append('>');
            // Empty timestamp units preserve pauses and the QRC line's declared tail.
            out.append('<').append(time(line.end())).append(">\n");
            String sub=paired.get(line);
            if(sub!=null)out.append('[').append(time(line.start())).append(']').append(sub).append('\n');
            if(out.length()>NeteaseLyricsText.MAX_CHARS)throw invalid();
        }
        return out.toString();
    }
    public static boolean hasWords(String original) throws IOException {return !qrc(normalize(original)).isEmpty();}
    private static List<Line> qrc(String input) throws IOException {
        String text=input;
        if(text.stripLeading().startsWith("<")) {
            if(text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw invalid();
            var content=CONTENT.matcher(text);
            if(!content.find())return List.of();
            text=unescape(content.group(2));
            if(content.find())throw invalid();
        }
        List<Line> result=new ArrayList<>();long offset=offset(text);
        for(String raw:text.split("\n")) {
            var line=LINE.matcher(raw.strip());if(!line.matches())continue;
            long start=Long.parseLong(line.group(1))+offset,end=start+Long.parseLong(line.group(2));
            if(!valid(start) || !valid(end) || end<start)return List.of();
            String body=line.group(3);var match=WORD.matcher(body);
            List<Word> words=new ArrayList<>();StringBuilder plain=new StringBuilder();int position=0;long previous=start;
            while(match.find()) {
                if(words.size()>=4096)return List.of();
                String token=body.substring(position,match.start());position=match.end();
                long at=Long.parseLong(match.group(1))+offset,until=at+Long.parseLong(match.group(2));
                if(!valid(at) || !valid(until) || at<previous || until<at)return List.of();
                end=Math.max(end,until);previous=until;
                if(token.isEmpty())continue;
                // Raw angle timestamps would be interpreted by the host as invented word timings.
                if(token.matches("(?s).*<\\d{1,5}:[0-5]\\d(?:[.:]\\d{1,3})?>.*"))return List.of();
                words.add(new Word(at,until,token));plain.append(token);
            }
            if(!body.substring(position).isBlank())return List.of();
            if(words.isEmpty() || plain.toString().isBlank() || instrumental(plain.toString()))continue;
            result.add(new Line(start,end,plain.toString().strip(),List.copyOf(words)));
            if(result.size()>10000)throw invalid();
        }
        result.sort(Comparator.comparingLong(Line::start));
        return result;
    }
    private static List<Line> lrc(String text) throws IOException {
        List<Line> lines=new ArrayList<>();long offset=offset(text);
        for(String raw:cleanTranslation(text).split("\n")) {
            List<Long> times=new ArrayList<>();int end=0;
            while(true) {
                var match=TIME.matcher(raw.substring(end));if(!match.find())break;
                String frac=match.group(3);
                long at=Long.parseLong(match.group(1))*60000+Long.parseLong(match.group(2))*1000+offset;
                if(frac!=null)at+=Integer.parseInt((frac+"00").substring(0,3));
                if(valid(at))times.add(at);end+=match.end();
            }
            String content=raw.substring(end).strip();
            if(content.isBlank() || instrumental(content))continue;
            for(long at:times)lines.add(new Line(at,at,content,List.of()));
            if(lines.size()>10000)throw invalid();
        }
        lines.sort(Comparator.comparingLong(Line::start));return lines;
    }
    private static String normalize(String text) throws IOException {
        if(text==null)return "";
        if(text.length()>NeteaseLyricsText.MAX_CHARS || text.indexOf('\0')>=0)throw invalid();
        return text.replace("\r\n","\n").replace('\r','\n').replace("\ufeff","");
    }
    private static String cleanTranslation(String text) {
        return text.replaceAll("(?m)^\\[[^]]+]\\s*//\\s*$","")
                .replaceAll("(?m)^.*\u6b64\u6b4c\u66f2\u4e3a\u6ca1\u6709\u586b\u8bcd\u7684\u7eaf\u97f3\u4e50.*$","");
    }
    private static boolean instrumental(String text) {return text.contains("\u6b64\u6b4c\u66f2\u4e3a\u6ca1\u6709\u586b\u8bcd\u7684\u7eaf\u97f3\u4e50");}
    private static long offset(String text) {var matcher=OFFSET.matcher(text);long offset=0;while(matcher.find())offset=Long.parseLong(matcher.group(1));return offset;}
    private static boolean valid(long at) {return at>=0 && at<=MAX_TIME;}
    private static String time(long at) {return String.format(Locale.ROOT,"%02d:%02d.%03d",at/60000,at/1000%60,at%1000);}
    private static String unescape(String input) throws IOException {
        var matcher=ENTITY.matcher(input);StringBuilder result=new StringBuilder();int end=0;
        while(matcher.find()) {
            String between=input.substring(end,matcher.start());if(between.indexOf('&')>=0)throw invalid();
            result.append(between);end=matcher.end();String entity=matcher.group(1);
            String replacement=switch(entity) {case "amp" -> "&";case "lt" -> "<";case "gt" -> ">";case "quot" -> "\"";case "apos" -> "'";default -> null;};
            if(replacement!=null)result.append(replacement);
            else {
                int point=Integer.parseInt(entity.substring(entity.startsWith("#x")?2:1),entity.startsWith("#x")?16:10);
                if(!Character.isValidCodePoint(point) || point==0 || point>=0xd800 && point<=0xdfff)throw invalid();
                result.appendCodePoint(point);
            }
        }
        String tail=input.substring(end);if(tail.indexOf('&')>=0)throw invalid();return result.append(tail).toString();
    }
    private static IOException invalid() {return new IOException("Invalid QQ lyric text");}
}
