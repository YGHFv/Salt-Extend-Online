package io.github.yghfv.saltneri.plugin.auth;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Synthetic cross-version DPAPI fixture only; never reads saved accounts. */
public final class WindowsDpapiCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !args[0].equals("--explicit-synthetic-fixture"))throw new IllegalArgumentException("Explicit fixture required");
        byte[] plain="SYNTHETIC_COMPATIBILITY_NOT_AN_ACCOUNT".getBytes(StandardCharsets.US_ASCII);
        var nativeDpapi=new WindowsDpapi();
        byte[] oldCipher=legacy("protect",plain),newCipher=nativeDpapi.protect(plain);
        try {
            if(!Arrays.equals(nativeDpapi.unprotect(oldCipher),plain) || !Arrays.equals(legacy("unprotect",newCipher),plain))
                throw new AssertionError("DPAPI cross-version compatibility failed");
        } finally {Arrays.fill(oldCipher,(byte)0);Arrays.fill(newCipher,(byte)0);Arrays.fill(plain,(byte)0);}
        System.out.println("PASS: synthetic old .NET/new native CurrentUser DPAPI cross-decryption, same entropy; no saved credential or account");
    }
    private static byte[] legacy(String mode,byte[] input) throws Exception {
        String code="$ErrorActionPreference='Stop';"+
            "Add-Type -AssemblyName System.Security;"+
            "$mode=[Console]::ReadLine();$bytes=[Convert]::FromBase64String([Console]::ReadLine());"+
            "$entropy=[Text.Encoding]::UTF8.GetBytes('io.github.yghfv.saltneri.online/auth/v1');"+
            "$result=if($mode -eq 'protect'){[Security.Cryptography.ProtectedData]::Protect($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)}"+
            "else{[Security.Cryptography.ProtectedData]::Unprotect($bytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser)};"+
            "[Console]::WriteLine([Convert]::ToBase64String($result))";
        var binary=java.nio.file.Path.of(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe");
        var process=new ProcessBuilder(binary.toString(),"-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",code)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try(var stream=process.getOutputStream()) {stream.write((mode+"\n"+Base64.getEncoder().encodeToString(input)+"\n").getBytes(StandardCharsets.US_ASCII));}
            if(!process.waitFor(10,TimeUnit.SECONDS) || process.exitValue()!=0)throw new AssertionError("Legacy synthetic helper failed");
            byte[] output=process.getInputStream().readNBytes(128*1024);
            try {return Base64.getDecoder().decode(new String(output,StandardCharsets.US_ASCII).trim());}
            finally {Arrays.fill(output,(byte)0);}
        } finally {process.destroyForcibly();}
    }
}
