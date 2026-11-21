package com.englify.ui;

import java.util.prefs.Preferences;

public final class AppSettings {
    private final Preferences prefs = Preferences.userRoot().node("com/englify/desktop");
    public String theme() { return prefs.get("theme", "Системна"); }
    public void theme(String v) { prefs.put("theme", v); }
    public int words() { return prefs.getInt("words", 15); }
    public void words(int v) { prefs.putInt("words", v); }
    public int minutes() { return prefs.getInt("minutes", 10); }
    public void minutes(int v) { prefs.putInt("minutes", v); }
    public int dailyGoal() { return prefs.getInt("goal", 15); }
    public void dailyGoal(int v) { prefs.putInt("goal", v); }
    public boolean ukrainianFirst() { return "UA_EN".equals(testDirection()); }
    public void ukrainianFirst(boolean v) { testDirection(v?"UA_EN":"EN_UA"); }
    public String testDirection(){return prefs.get("testDirection",prefs.getBoolean("ukrainianFirst",false)?"UA_EN":"EN_UA");}
    public void testDirection(String direction){prefs.put("testDirection",direction);}
    public String fontSize(){return prefs.get("fontSize","Середній");}
    public void fontSize(String v){prefs.put("fontSize",v);}
    public String lastLevel() { return prefs.get("lastLevel", "A1"); }
    public void lastLevel(String v) { prefs.put("lastLevel", v); }
}
