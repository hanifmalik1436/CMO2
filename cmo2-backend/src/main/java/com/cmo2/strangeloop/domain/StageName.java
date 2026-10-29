package com.cmo2.strangeloop.domain;

/**
 * The five stages of the Strange Loop, in execution order.
 * Innovation → Learning → Documentation → Reporting → Optimization.
 */
public enum StageName {
    INNOVATION(1, "Innovation"),
    LEARNING(2, "Learning"),
    DOCUMENTATION(3, "Documentation"),
    REPORTING(4, "Reporting"),
    OPTIMIZATION(5, "Optimization");

    public final int number;
    public final String title;

    StageName(int number, String title) {
        this.number = number;
        this.title = title;
    }

    public static StageName ofNumber(int number) {
        for (StageName s : values()) {
            if (s.number == number) return s;
        }
        throw new IllegalArgumentException("No stage with number " + number);
    }
}
