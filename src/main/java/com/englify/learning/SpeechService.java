package com.englify.learning;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Windows local text-to-speech: does not store audio or send words to a service. */
public final class SpeechService {
    private SpeechService() { }
    public static void speak(String word) {
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))return;
        Thread t=new Thread(()->{
            try {
                String script="Add-Type -AssemblyName System.Speech; " +
                    "$v=New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "$v.Speak([Console]::In.ReadToEnd());";
                Process p=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",script)
                    .redirectErrorStream(true).start();
                try(var w=new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8)){
                    w.write(word);w.flush();
                }
                p.waitFor();
            }catch(Exception ignored){ /* The OS may not have a matching voice. */ }
        },"Englify-tts");
        t.setDaemon(true);t.start();
    }
}
