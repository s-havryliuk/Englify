package com.englify.learning;

import java.time.LocalDate;

/** A small, deterministic variant of SM-2 used for word review scheduling.
 *  A failed recall is due today; successful recalls move to 1, 3, and then
 *  progressively longer intervals. This is not an FSRS implementation.
 */
public final class SrsScheduler {
    private SrsScheduler() { }

    public record ReviewState(int repetitions, int intervalDays, double ease,
                              int lapses, LocalDate dueOn) { }

    public static ReviewState initial(LocalDate today) {
        return new ReviewState(0, 0, 2.5, 0, today);
    }

    public static ReviewState review(ReviewState previous, boolean recalled, LocalDate today) {
        if (previous == null) previous = initial(today);
        if (!recalled) {
            return new ReviewState(0, 0, Math.max(1.3, previous.ease() - 0.2),
                    previous.lapses() + 1, today);
        }
        int repetitions = previous.repetitions() + 1;
        int interval = repetitions == 1 ? 1 : repetitions == 2 ? 3 :
                Math.max(4, (int) Math.round(previous.intervalDays() * previous.ease()));
        double ease = Math.min(3.0, previous.ease() + 0.05);
        return new ReviewState(repetitions, interval, ease, previous.lapses(), today.plusDays(interval));
    }
}
