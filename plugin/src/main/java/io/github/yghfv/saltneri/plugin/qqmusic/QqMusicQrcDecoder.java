package io.github.yghfv.saltneri.plugin.qqmusic;

/* QRC protocol adapted from mygo-qyin (AGPL-3.0); DES tables derive from
 * Copyright 2010 The Go Authors, BSD-3-Clause. See META-INF/licenses.
 * This is a legacy lyric wire decoder, not a general cryptography API. */

import io.github.yghfv.saltneri.plugin.net.RequestScope;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

public final class QqMusicQrcDecoder {
    public static final int MAX_BYTES=262144;
    private QqMusicQrcDecoder() { }
    public static String decode(String encoded,RequestScope request) throws IOException {
        request.check();
        if(encoded==null || encoded.isEmpty())return "";
        if(encoded.length()>MAX_BYTES*2 || (encoded.length()&15)!=0)throw invalid();
        byte[] bytes;
        try {bytes=HexFormat.of().parseHex(encoded);}
        catch(IllegalArgumentException bad) {throw invalid();}
        for(int at=0;at<bytes.length;at+=8) {
            if((at&1023)==0)request.check();
            long block=swap(ByteBuffer.wrap(bytes,at,8).getLong());
            block=crypt(block,KEYS[2],true);
            block=crypt(block,KEYS[1],false);
            block=crypt(block,KEYS[0],true);
            ByteBuffer.wrap(bytes,at,8).putLong(swap(block));
        }
        Inflater inflater=new Inflater();
        try(var out=new ByteArrayOutputStream()) {
            inflater.setInput(bytes);
            byte[] buffer=new byte[4096];
            while(!inflater.finished()) {
                request.check();
                int read=inflater.inflate(buffer);
                if(read==0 && !inflater.finished())throw invalid();
                if(out.size()+read>MAX_BYTES)throw invalid();
                out.write(buffer,0,read);
            }
            if(inflater.getRemaining()>7)throw invalid();
            return utf8(out.toByteArray());
        } catch(DataFormatException bad) {throw invalid();}
        finally {inflater.end();Arrays.fill(bytes,(byte)0);}
    }
    static String utf8(byte[] bytes) throws IOException {
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException bad) {throw invalid();}
    }
    private static IOException invalid() {return new IOException("Invalid QQ lyric payload");}
    private static long swap(long value) {
        return Integer.toUnsignedLong(Integer.reverseBytes((int)(value>>>32)))<<32
                | Integer.toUnsignedLong(Integer.reverseBytes((int)value));
    }
    private static long permute(long value,int[] positions) {
        long result=0;
        for(int position:positions)result=result<<1 | (value>>>position)&1;
        return result;
    }
    private static long crypt(long block,long[] keys,boolean decrypt) {
        long v=permute(block,IP);
        int left=(int)(v>>>32),right=(int)v;
        for(int round=0;round<16;round++) {
            long expanded=permute(Integer.toUnsignedLong(right),EXPANSION)^keys[decrypt?15-round:round];
            int substituted=0;
            for(int box=0;box<8;box++) {
                int part=(int)(expanded>>>(42-6*box))&63;
                int row=(part&32)>>>4 | part&1;
                substituted=substituted<<4 | S_BOXES[box][row][part>>>1&15];
            }
            int next=left^(int)permute(Integer.toUnsignedLong(substituted),P);
            left=right;right=next;
        }
        return permute(Integer.toUnsignedLong(right)<<32 | Integer.toUnsignedLong(left),FP);
    }
    private static long[][] keys() {
        byte[] key="!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.US_ASCII);
        long[][] result=new long[3][16];
        for(int index=0;index<3;index++) {
            long value=permute(swap(ByteBuffer.wrap(key,index*8,8).getLong()),PC1);
            int left=(int)(value>>>28),right=(int)value&0xfffffff;
            for(int round=0;round<16;round++) {
                int shift=ROTATIONS[round];
                left=(left<<shift | left>>>(28-shift))&0xfffffff;
                right=(right<<shift | right>>>(28-shift))&0xfffffff;
                long subkey=0;
                for(int j=0;j<48;j++) {
                    int position=PC2[j];
                    // QQ's D-half selection is one bit below standard DES PC-2.
                    int bit=j<24?(left>>>(position-28))&1:position>0?(right>>>(position-1))&1:0;
                    subkey=subkey<<1 | bit;
                }
                result[index][round]=subkey;
            }
        }
        return result;
    }
    private static final int[] IP={
	6, 14, 22, 30, 38, 46, 54, 62,
	4, 12, 20, 28, 36, 44, 52, 60,
	2, 10, 18, 26, 34, 42, 50, 58,
	0, 8, 16, 24, 32, 40, 48, 56,
	7, 15, 23, 31, 39, 47, 55, 63,
	5, 13, 21, 29, 37, 45, 53, 61,
	3, 11, 19, 27, 35, 43, 51, 59,
	1, 9, 17, 25, 33, 41, 49, 57,
};
    private static final int[] FP={
	24, 56, 16, 48, 8, 40, 0, 32,
	25, 57, 17, 49, 9, 41, 1, 33,
	26, 58, 18, 50, 10, 42, 2, 34,
	27, 59, 19, 51, 11, 43, 3, 35,
	28, 60, 20, 52, 12, 44, 4, 36,
	29, 61, 21, 53, 13, 45, 5, 37,
	30, 62, 22, 54, 14, 46, 6, 38,
	31, 63, 23, 55, 15, 47, 7, 39,
};
    private static final int[] EXPANSION={
	0, 31, 30, 29, 28, 27, 28, 27,
	26, 25, 24, 23, 24, 23, 22, 21,
	20, 19, 20, 19, 18, 17, 16, 15,
	16, 15, 14, 13, 12, 11, 12, 11,
	10, 9, 8, 7, 8, 7, 6, 5,
	4, 3, 4, 3, 2, 1, 0, 31,
};
    private static final int[] P={
	16, 25, 12, 11, 3, 20, 4, 15,
	31, 17, 9, 6, 27, 14, 1, 22,
	30, 24, 8, 18, 0, 5, 29, 23,
	13, 19, 2, 26, 10, 21, 28, 7,
};
    private static final int[] PC1={
	7, 15, 23, 31, 39, 47, 55, 63,
	6, 14, 22, 30, 38, 46, 54, 62,
	5, 13, 21, 29, 37, 45, 53, 61,
	4, 12, 20, 28, 1, 9, 17, 25,
	33, 41, 49, 57, 2, 10, 18, 26,
	34, 42, 50, 58, 3, 11, 19, 27,
	35, 43, 51, 59, 36, 44, 52, 60,
};
    private static final int[] PC2={
	42, 39, 45, 32, 55, 51, 53, 28,
	41, 50, 35, 46, 33, 37, 44, 52,
	30, 48, 40, 49, 29, 36, 43, 54,
	15, 4, 25, 19, 9, 1, 26, 16,
	5, 11, 23, 8, 12, 7, 17, 0,
	22, 3, 10, 14, 6, 20, 27, 24,
};
    private static final int[][][] S_BOXES={

	{
		{14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7},
		{0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8},
		{4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0},
		{15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13},
	},

	{
		{15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10},
		{3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5},
		{0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15},
		{13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9},
	},

	{
		{10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8},
		{13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1},
		{13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7},
		{1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12},
	},

	{
		{7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15},
		{13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9},
		{10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4},
		{3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14},
	},

	{
		{2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9},
		{14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6},
		{4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14},
		{11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3},
	},

	{
		{12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11},
		{10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8},
		{9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6},
		{4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13},
	},

	{
		{4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1},
		{13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6},
		{1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2},
		{6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12},
	},

	{
		{13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7},
		{1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2},
		{7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8},
		{2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11},
	},
};
    private static final int[] ROTATIONS={1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
    private static final long[][] KEYS=keys();
}
