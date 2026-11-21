package com.englify.learning;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class SrsSchedulerTest {
    private final LocalDate today = LocalDate.of(2026, 11, 21);

    @Test void successfulRecallExtendsIntervals() {
        var first = SrsScheduler.review(null, true, today);
        assertEquals(today.plusDays(1), first.dueOn());
        var second = SrsScheduler.review(first, true, today.plusDays(1));
        assertEquals(today.plusDays(4), second.dueOn());
        var third = SrsScheduler.review(second, true, second.dueOn());
        assertTrue(third.intervalDays() >= 4);
    }

    @Test void failedRecallIsDueAgainToday() {
        var learned = SrsScheduler.review(null, true, today);
        var failed = SrsScheduler.review(learned, false, today.plusDays(1));
        assertEquals(0, failed.repetitions());
        assertEquals(1, failed.lapses());
        assertEquals(today.plusDays(1), failed.dueOn());
    }
}
