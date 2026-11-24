package com.cmo2.studio.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Smart Schedule: suggests when each publishable draft could go out, in the customer's timezone.
 * At most one post per platform per day, at a fixed good time for each platform, over the next two
 * weeks, skipping days that already have a suggested post. Computed here, not by the AI. Only a
 * suggestion: nothing is scheduled in GoHighLevel until the owner approves and publishes the draft.
 */
final class SmartSchedule {

    record Slot(LocalTime time, boolean weekdaysOnly) {}

    /** Keyed by {@code ContentFormat.platform}. Platforms without a slot get no suggestion. */
    static final Map<String, Slot> SLOTS = Map.of(
            "LINKEDIN", new Slot(LocalTime.of(8, 30), true),
            "X", new Slot(LocalTime.of(9, 0), true),
            "GOOGLE", new Slot(LocalTime.of(10, 0), true),
            "INSTAGRAM", new Slot(LocalTime.of(12, 0), false),
            "THREADS", new Slot(LocalTime.of(12, 30), false),
            "FACEBOOK", new Slot(LocalTime.of(13, 0), false));

    static final int DAYS = 14;

    private final ZoneId zone;
    private final LocalDate first;
    private final Map<String, Set<LocalDate>> taken = new HashMap<>();

    /**
     * @param alreadySuggested platform → local dates that already have a suggested post for this customer
     */
    SmartSchedule(ZoneId zone, Instant now, Map<String, Set<LocalDate>> alreadySuggested) {
        this.zone = zone;
        this.first = now.atZone(zone).toLocalDate().plusDays(1);
        alreadySuggested.forEach((platform, dates) -> taken.put(platform, new HashSet<>(dates)));
    }

    /** The next free slot for {@code platform}, or null when it has none or the two weeks are full. */
    Instant next(String platform) {
        Slot slot = SLOTS.get(platform);
        if (slot == null) return null;
        Set<LocalDate> used = taken.computeIfAbsent(platform, p -> new HashSet<>());
        for (int i = 0; i < DAYS; i++) {
            LocalDate day = first.plusDays(i);
            if (slot.weekdaysOnly() && (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                continue;
            }
            if (used.add(day)) {
                return ZonedDateTime.of(day, slot.time(), zone).toInstant();
            }
        }
        return null;
    }

    static LocalDate localDate(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate();
    }
}
