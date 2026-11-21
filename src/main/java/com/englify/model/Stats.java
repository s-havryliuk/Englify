package com.englify.model;

import java.time.LocalDate;
import java.util.Map;

public record Stats(int studiedToday, int mastered, int attempts, int correct,
                    int activeDays, int streak, int sessions, int minutes, int dueToday,
                    Map<String, Integer> levelsTotal, Map<String, Integer> levelsMastered,
                    Map<LocalDate, Integer> lastWeek) {
    public int accuracy() { return attempts == 0 ? 0 : (int) Math.round(correct * 100.0 / attempts); }
    public static String rank(int mastered) {
        if (mastered < 50) return "Новачок";
        if (mastered < 200) return "Учень";
        if (mastered < 500) return "Студент";
        if (mastered < 1000) return "Практик";
        if (mastered < 2000) return "Знавець";
        return "Профі";
    }
    public static int nextRankAt(int mastered) {
        for (int threshold : new int[]{50,200,500,1000,2000}) if (mastered < threshold) return threshold;
        return 2000;
    }
}
