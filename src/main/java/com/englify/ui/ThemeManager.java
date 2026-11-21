package com.englify.ui;

import javafx.scene.Parent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** System theme uses Windows AppsUseLightTheme; unsupported systems fall back to light. */
public final class ThemeManager {
    private ThemeManager() { }
    public static void apply(Parent root, String requested) {
        boolean dark = switch (requested) {
            case "Темна" -> true;
            case "Світла" -> false;
            default -> systemDark();
        };
        root.getStyleClass().removeAll("theme-dark","theme-light");
        root.getStyleClass().add(dark ? "theme-dark" : "theme-light");
    }
    public static boolean systemDark() {
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        try {
            if(os.contains("win")) {
                Process p=new ProcessBuilder("reg","query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v","AppsUseLightTheme")
                        .redirectErrorStream(true).start();
                if(!p.waitFor(2,TimeUnit.SECONDS)) {p.destroyForcibly();return false;}
                try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    return r.lines().anyMatch(x -> x.contains("AppsUseLightTheme") && x.matches(".*0x0\\s*$"));
                }
            }
            if(os.contains("mac")) {
                Process p=new ProcessBuilder("defaults","read","-g","AppleInterfaceStyle").redirectErrorStream(true).start();
                if(!p.waitFor(2,TimeUnit.SECONDS)) {p.destroyForcibly();return false;}
                try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    return r.lines().anyMatch(x->x.toLowerCase(Locale.ROOT).contains("dark"));
                }
            }
        }catch(Exception ignored) { /* Light fallback if the operating system API is unavailable. */ }
        return false;
    }
}
