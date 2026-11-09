package io.github.yghfv.saltneri.plugin.auth;

/* QQ JSONP data-only parsing follows mygo-qyin (AGPL-3.0).
 * No JavaScript evaluation, arbitrary callback, or diagnostic payload logging. */

import java.io.IOException;
import java.util.*;

public final class QqMusicPtui {
    private QqMusicPtui() { }
    public static List<String> parse(String callback) throws IOException {
        if(callback.length()>262144)throw invalid();
        String raw=callback.strip();if(raw.endsWith(";"))raw=raw.substring(0,raw.length()-1).stripTrailing();
        if(!raw.startsWith("ptuiCB(") || !raw.endsWith(")"))throw invalid();
        String body=raw.substring(7,raw.length()-1);
        List<String> fields=new ArrayList<>();int at=0;
        while(at<body.length()) {
            while(at<body.length() && Character.isWhitespace(body.charAt(at)))at++;
            if(at>=body.length() || body.charAt(at++)!='\'')throw invalid();
            StringBuilder value=new StringBuilder();boolean closed=false;
            while(at<body.length()) {
                char ch=body.charAt(at++);
                if(ch=='\'') {closed=true;break;}
                if(ch=='\r' || ch=='\n')throw invalid();
                if(ch=='\\') {
                    if(at>=body.length())throw invalid();
                    char escaped=body.charAt(at++);
                    ch=switch(escaped) {
                        case '\'' -> '\'';case '\\' -> '\\';case 'n' -> '\n';case 'r' -> '\r';case 't' -> '\t';
                        case 'x','u' -> {
                            int count=escaped=='x'?2:4;
                            if(at+count>body.length())throw invalid();
                            String number=body.substring(at,at+count);
                            if(!number.matches("[0-9a-fA-F]+"))throw invalid();
                            at+=count;yield (char)Integer.parseInt(number,16);
                        }
                        default -> throw invalid();
                    };
                }
                value.append(ch);
            }
            if(!closed || value.length()>16384)throw invalid();
            fields.add(value.toString());
            while(at<body.length() && Character.isWhitespace(body.charAt(at)))at++;
            if(at==body.length())break;
            if(body.charAt(at++)!=',' || fields.size()>=7 || body.substring(at).isBlank())throw invalid();
        }
        if(fields.size()<6 || fields.size()>7)throw invalid();
        return List.copyOf(fields);
    }
    private static IOException invalid() {return AuthFailure.of(AuthFailure.Stage.QQ_POLL,AuthFailure.Reason.RESPONSE);}
}
