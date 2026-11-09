package io.github.yghfv.saltneri.plugin.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

/** Fixed bootstrap diagnostics only; never returns raw process output or environment paths. */
final class WindowsWebLoginHelper {
    private WindowsWebLoginHelper() { }
    static Path findBinary(Map<String,String> environment, Predicate<Path> exists) throws AuthFailure {
        for(String variable:List.of("SystemRoot","windir")) {
            String directory=environment.get(variable);
            if(directory==null || directory.isBlank()) continue;
            try {
                Path root=Path.of(directory);
                if(!root.isAbsolute()) continue;
                // A 32-bit JVM on 64-bit Windows can explicitly reach native PowerShell.
                for(String system:List.of("Sysnative","System32")) {
                    Path candidate=root.resolve(system).resolve("WindowsPowerShell/v1.0/powershell.exe").normalize();
                    if(exists.test(candidate)) return candidate;
                }
            } catch(InvalidPathException | SecurityException ignored) { }
        }
        throw AuthFailure.of(AuthFailure.Stage.NET_WEB,AuthFailure.Reason.HELPER_MISSING);
    }
    static List<String> command(Path binary,Path script) {
        return List.of(binary.toString(),"-NoLogo","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-Command",loader(script));
    }
    static String loader(Path script) {
        String file=script.toString().replace("'","''");
        // A short plain command avoids encoded-command filtering and the 32K limit.
        // Language restrictions are detected before .NET calls, never bypassed.
        return "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';"+
            "if($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage'){Write-Output '{\"error\":\"HELPER_POLICY\"}';exit 0};"+
            "if($PSVersionTable.PSVersion.Major -lt 5 -or ($PSVersionTable.PSVersion.Major -eq 5 -and $PSVersionTable.PSVersion.Minor -lt 1)){"+
            "Write-Output '{\"error\":\"HELPER_VERSION\"}';exit 0};"+
            "try{& ([ScriptBlock]::Create([IO.File]::ReadAllText('"+file+"',[Text.Encoding]::UTF8)))}"+
            "catch{$id=$_.FullyQualifiedErrorId;$type=$_.Exception.GetType().Name;"+
            "$code=if($type -eq 'PSSecurityException' -or $id -match 'ScriptContainedMaliciousContent|UnauthorizedAccess|MethodInvocationNotSupportedInConstrainedLanguage|CannotCreateTypeConstrainedLanguage|ScriptsNotAllowed'){"+
            "'HELPER_POLICY'}elseif($type -in @('IOException','FileNotFoundException','DirectoryNotFoundException')){'HELPER_IO'}else{'HELPER_SCRIPT'};"+
            "[Console]::WriteLine('{\"error\":\"'+$code+'\"}')}";
    }
    static AuthFailure exited(int code,byte[] stderr) {
        // Match fixed PowerShell identifiers only. Localized prose and raw details stay private.
        String error=new String(stderr,StandardCharsets.UTF_8);
        var reason=error.contains("ScriptContainedMaliciousContent") || error.contains("PSSecurityException") ||
            error.contains("MethodInvocationNotSupportedInConstrainedLanguage") || error.contains("CannotCreateTypeConstrainedLanguage") ||
            error.contains("AuthorizationManagerCheckFailed") || error.contains("ScriptsNotAllowed")
            ?AuthFailure.Reason.HELPER_POLICY:AuthFailure.Reason.HELPER_EXIT;
        return AuthFailure.code(AuthFailure.Stage.NET_WEB,reason,code);
    }
}
