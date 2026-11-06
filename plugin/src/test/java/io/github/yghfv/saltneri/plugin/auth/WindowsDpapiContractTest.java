package io.github.yghfv.saltneri.plugin.auth;

public final class WindowsDpapiContractTest {
    public static void main(String[] args)throws Exception{
        if(!System.getProperty("os.name").startsWith("Windows")){System.out.println("SKIP: Windows DPAPI fixture on non-Windows");return;}
        var dpapi=new WindowsDpapi();byte[] plain="SYNTHETIC_TEST_NOT_AN_ACCOUNT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] cipher=dpapi.protect(plain);
        if(java.util.Arrays.equals(plain,cipher)||!java.util.Arrays.equals(plain,dpapi.unprotect(cipher)))throw new AssertionError("DPAPI roundtrip failed");
        cipher[cipher.length-1]^=1;
        try{dpapi.unprotect(cipher);throw new AssertionError("Corrupt DPAPI accepted");}catch(java.io.IOException expected){}
        System.out.println("PASS: actual Windows CurrentUser DPAPI synthetic roundtrip/tamper; no real credential or browser accessed");
    }
}
