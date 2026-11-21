package com.englify.learning;

public final class UkrainianForms {
    private UkrainianForms() { }
    public static String words(int count) {
        int n = Math.abs(count) % 100;
        int one = n % 10;
        return count + " " + ((n >= 11 && n <= 14) ? "слів" : one == 1 ? "слово" : one >= 2 && one <= 4 ? "слова" : "слів");
    }
    public static String days(int count) {
        int n = Math.abs(count) % 100;
        int one = n % 10;
        return count + " " + ((n >= 11 && n <= 14) ? "днів" : one == 1 ? "день" : one >= 2 && one <= 4 ? "дні" : "днів");
    }
}
