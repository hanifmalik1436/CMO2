package com.cmo2.strangeloop.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the owner changed on a page CMO² wrote: lines they added and lines they removed, compared
 * line by line after normalising whitespace and heading marks (Notion and Confluence each reformat
 * text slightly on the way back).
 */
final class PageDiff {

    static final int MAX_ADDED = 20;
    static final int MAX_REMOVED = 10;
    static final int MAX_LINE = 300;

    private PageDiff() {}

    record Changes(List<String> added, List<String> removed) {
        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    static Changes between(String written, String now) {
        Set<String> before = lines(written);
        Set<String> after = lines(now);
        List<String> added = new ArrayList<>();
        for (String line : after) {
            if (!containsIgnoringCase(before, line) && added.size() < MAX_ADDED) added.add(clip(line));
        }
        List<String> removed = new ArrayList<>();
        for (String line : before) {
            if (!containsIgnoringCase(after, line) && removed.size() < MAX_REMOVED) removed.add(clip(line));
        }
        return new Changes(added, removed);
    }

    private static Set<String> lines(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("# ")) line = line.substring(2).strip();
            line = line.replaceAll("\\s+", " ");
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    private static boolean containsIgnoringCase(Set<String> set, String line) {
        if (set.contains(line)) return true;
        String lower = line.toLowerCase(Locale.ROOT);
        for (String s : set) {
            if (s.toLowerCase(Locale.ROOT).equals(lower)) return true;
        }
        return false;
    }

    private static String clip(String s) {
        return s.length() <= MAX_LINE ? s : s.substring(0, MAX_LINE) + "…";
    }
}
