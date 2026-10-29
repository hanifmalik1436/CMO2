package com.cmo2.integrations.core;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Picks a customer's timezone from what's known about them. */
public final class Timezones {

    private Timezones() {}

    /** The first candidate that is a valid IANA zone id, else UTC. */
    public static ZoneId firstValid(String... candidates) {
        for (String candidate : candidates) {
            ZoneId zone = parse(candidate);
            if (zone != null) return zone;
        }
        return ZoneOffset.UTC;
    }

    /** The zone, or null when {@code id} is blank or not a zone id. */
    public static ZoneId parse(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }
}
