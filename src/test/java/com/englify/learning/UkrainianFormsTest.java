package com.englify.learning;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UkrainianFormsTest {
    @Test void pluralRules() {
        assertEquals("1 слово", UkrainianForms.words(1));
        assertEquals("3 слова", UkrainianForms.words(3));
        assertEquals("12 слів", UkrainianForms.words(12));
        assertEquals("21 день", UkrainianForms.days(21));
        assertEquals("14 днів", UkrainianForms.days(14));
    }
}
