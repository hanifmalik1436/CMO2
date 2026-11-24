package com.cmo2.ai.provider;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo Content Studio output for the stub provider (no AI key): ideas built from the real source
 * text, and drafts shaped like each platform's format (thread, carousel slides, script cues,
 * article…) using the idea's title, hooks and quote. Lets the whole studio flow run offline with
 * believable content; with a real provider none of this is used.
 */
final class StubContentWriter {

    private static final List<String> TYPES = List.of("INSIGHT", "STORY", "TIP", "CONTRARIAN", "HOW_TO", "STAT");
    private static final Pattern BUSINESS = Pattern.compile("(?m)^Business: (.+)$");
    private static final Set<String> DANGLING = Set.of("a", "an", "the", "and", "but", "or", "to",
            "of", "if", "is", "in", "on", "for", "with", "that", "you", "we", "they", "so", "what", "they're", "it", "why", "not", "about", "how", "when");

    private StubContentWriter() {}

    // ---- ideas ---------------------------------------------------------------------------

    /** {"ideas":[…]} with up to {@code count} ideas drawn from sentences in the source. */
    static String ideas(String userPrompt, int count) {
        String source = between(userPrompt, "\n", "\n\nFind the");
        List<String> sentences = sentences(source);
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < count && !sentences.isEmpty(); i++) {
            String sentence = sentences.get(i % sentences.size());
            String type = TYPES.get(i % TYPES.size());
            String phrase = phrase(sentence);
            ObjectNode idea = list.addObject();
            idea.put("type", type);
            idea.put("title", clip(switch (type) {
                case "INSIGHT" -> "The real point: " + phrase;
                case "STORY" -> "A story worth telling: " + phrase;
                case "TIP" -> "Quick tip: " + phrase;
                case "CONTRARIAN" -> "Unpopular opinion: " + phrase;
                case "HOW_TO" -> "How to act on it: " + phrase;
                default -> "The detail that matters: " + phrase;
            }, 200));
            idea.put("summary", clip("The speaker makes the case that " + lowerFirst(stripEnd(sentence)) + ".", 400));
            ArrayNode hooks = idea.putArray("hooks");
            hooks.add(clip(phrase + "…", 160));
            hooks.add(switch (type) {
                case "CONTRARIAN" -> "Most advice gets this backwards.";
                case "TIP" -> "Save this one for later.";
                case "STORY" -> "Here's what actually happened.";
                default -> "Most people miss this.";
            });
            idea.put("quote", clip(sentence, 400));
            idea.put("viralityScore", Math.max(40, 88 - i * 6));   // best first, like the real model
            idea.put("viralityReason", switch (type) {
                case "CONTRARIAN" -> "Contrarian take: challenges what the audience assumes, which invites replies.";
                case "STORY" -> "Story hook: a real situation the audience recognises keeps them reading.";
                case "TIP" -> "Practical tip: easy to save and share.";
                case "STAT" -> "Specific detail: concrete facts stop the scroll.";
                case "HOW_TO" -> "How-to: promises a clear payoff in the first line.";
                default -> "Curiosity gap: names a problem most people miss.";
            });
        }
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("ideas", list);
        return root.toString();
    }

    // ---- drafts --------------------------------------------------------------------------

    /** A draft for {@code format}, built from the idea (or source) in the prompt. */
    static String piece(String format, String systemPrompt, String userPrompt) {
        String business = match(BUSINESS, systemPrompt, "our team");
        String title = firstLine(userPrompt, "Title: ");
        boolean fullSource = title == null && firstLine(userPrompt, "Source: ") != null;
        if (fullSource) title = firstLine(userPrompt, "Source: ");
        if (title == null) title = "what we learned this week";
        String quote = between(userPrompt, "Supporting quote: \"", "\"");
        List<String> hooks = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^- (.+)$").matcher(userPrompt);
        while (m.find()) hooks.add(m.group(1));
        int draft = draftNumber(userPrompt);
        String topic = stripEnd(title.replaceFirst("^[^:]+: ", ""));
        if (fullSource && quote.isBlank()) {
            // No idea picked: take the point from the source itself, a different sentence per draft.
            List<String> said = sentences(between(between(userPrompt, "Source: ", "\n\nWrite the "), "\n", "\n\nWrite the "));
            if (!said.isEmpty()) quote = said.get((draft - 1) % said.size());
            hooks.add(upperFirst(topic) + ": here's what we learned.");
        }
        String hook = hooks.isEmpty() ? title : hooks.get((draft - 1) % hooks.size());
        String point = quote.isBlank() ? title : quote;

        return switch (format) {
            case "X_POST" -> clip(hook + "\n\n" + clip(point, 150) + "\n\nAgree or disagree?", 280);
            case "X_THREAD" -> "1/ " + hook + "\n\n2/ " + clip(point, 250) + "\n\n3/ Why it matters: it changes what you "
                    + "focus on this week.\n\n4/ The mistake most people make is treating this as a nice-to-have.\n\n"
                    + "5/ What " + business + " does instead: start small, measure, repeat.\n\n6/ That's it. Which part "
                    + "will you try first?";
            case "LINKEDIN_POST" -> hook + "\n\n" + point + "\n\nHere's what that looks like in practice:\n\n"
                    + "→ Pick one thing that moves the needle.\n→ Measure it every week.\n→ Drop what doesn't work.\n\n"
                    + "At " + business + " we've seen this make the difference between being busy and getting results.\n\n"
                    + "What's the one metric you watch most closely?\n\n#marketing #smallbusiness #growth";
            case "LINKEDIN_CAROUSEL", "INSTAGRAM_CAROUSEL" -> "Slide 1: " + clip(topic, 60) + "\nSlide 2: The problem\n"
                    + "Most people get this wrong.\nSlide 3: What's really going on\n" + clip(point, 120) + "\nSlide 4: "
                    + "Step one\nStart with the result you want.\nSlide 5: Step two\nMeasure it weekly.\nSlide 6: Step three\n"
                    + "Cut what doesn't work.\nSlide 7: Your move\nSave this and share it with your team.\n\nCaption: "
                    + hook + " Swipe through for the three steps." + (format.startsWith("INSTAGRAM")
                    ? "\n\n#marketingtips #smallbusiness #growth #business #entrepreneur" : "");
            case "YOUTUBE_SHORT", "INSTAGRAM_REEL", "TIKTOK_SCRIPT" -> "[HOOK 0-3s] " + hook + "\n[On screen: "
                    + clip(topic, 50) + "]\n\n[BODY] " + clip(point, 220) + " Here's the simple fix: pick one result, "
                    + "track it weekly, and cut what doesn't move it.\n[On screen: 1 result · track weekly · cut the rest]"
                    + "\n\n[CTA] Follow " + business + " for more like this.\n\n"
                    + (format.equals("YOUTUBE_SHORT") ? "Title: " + clip(topic, 60)
                    : "Caption: " + clip(hook, 100) + " #marketing #smallbusiness #tips");
            case "YOUTUBE_VIDEO", "YOUTUBE_OUTLINE" -> "[COLD OPEN] " + hook + "\n\n[INTRO] In this video: " + topic + ", and what "
                    + business + " does about it.\n\n## 1. The problem\n" + point + "\n\n## 2. Why it happens\nMost teams "
                    + "measure activity instead of results.\n\n## 3. The fix\nPick one outcome, track it weekly, cut the "
                    + "rest.\n\n## 4. A real example\nWalk through a recent customer.\n\n[CTA] Subscribe and tell us in "
                    + "the comments what you'll try first.\n\nTitle: " + clip(topic, 70) + "\nDescription: " + clip(point, 200)
                    + "\nChapters: 00:00 Intro · 00:45 The problem · 02:00 Why · 03:30 The fix · 05:15 Example";
            case "THREADS_POST", "YOUTUBE_COMMUNITY_POST", "SUBSTACK_POST" -> clip(hook + " " + clip(point, 250) + " What's your take?", 500);
            case "INSTAGRAM_CAPTION" -> "[Image: a simple graphic reading \"" + clip(topic, 40) + "\"]\n\n" + hook + "\n\n"
                    + clip(point, 300) + "\n\nThe fix is simpler than you think: one goal, measured every week.\n\n"
                    + "Tap the link in bio to talk to " + business + ".\n\n#smallbusiness #marketing #growth #tips "
                    + "#entrepreneur #localbusiness";
            case "FACEBOOK_POST" -> hook + "\n\n" + clip(point, 300) + "\n\nWe help local businesses with exactly "
                    + "this at " + business + ". Comment \"INFO\" or send us a message and we'll show you how.";
            case "GOOGLE_BUSINESS_POST" -> topic + "\n\n" + clip(point, 400) + "\n\n" + business + " helps local "
                    + "businesses put this into practice. Call us or book online to get started.";
            case "X_LONG_POST" -> hook + "\n\n" + point + "\n\nThe pattern we keep seeing: people wait, hoping it "
                    + "sorts itself out. It rarely does.\n\nThree things that help:\n1. Get the facts first.\n2. Know "
                    + "every option, not just one.\n3. Decide before a deadline decides for you.\n\nWhat would you add?";
            case "SITE_LEAD_MAGNET" -> "# " + clip(topic, 80) + ": a one-page checklist\n\nFor anyone who wants a clear "
                    + "next step.\n\n1. Write down where things stand today.\n2. Gather the paperwork you already have.\n"
                    + "3. List the questions you need answered.\n4. Note every deadline.\n5. " + clip(point, 160)
                    + "\n6. Compare your options side by side.\n7. Talk to " + business + " for a straight answer.";
            case "SITE_ARTICLE", "X_ARTICLE", "LINKEDIN_ARTICLE" -> "SEO title: " + clip(topic, 60) + "\nMeta description: " + clip(point, 150)
                    + "\n\n# " + topic + "\n\n" + hook + " " + point + "\n\n## Why this matters\nMost businesses spend "
                    + "their time on activity that doesn't move results.\n\n## What to do instead\nChoose one outcome, "
                    + "measure it weekly, and stop what doesn't help.\n\n## A simple plan for this month\n1. Pick the "
                    + "result.\n2. Set a weekly check-in.\n3. Review after four weeks.\n\n## Conclusion\nSmall, measured "
                    + "steps beat big plans. Talk to " + business + " to get started.";
            case "SUBSTACK_NEWSLETTER", "LINKEDIN_NEWSLETTER", "SITE_NEWSLETTER" -> "Subject: " + clip(topic, 60) + "\nPreview: " + clip(hook, 90) + "\n\nHi there,"
                    + "\n\nThis week I kept coming back to one idea: " + lowerFirst(stripEnd(point)) + ".\n\nHere's why "
                    + "it matters, and three things you can do about it:\n\n1. Pick one result to focus on.\n2. Check it "
                    + "every week.\n3. Cut what doesn't move it.\n\nHit reply and tell me which one you'll try.\n\n"
                    + "— The " + business + " team";
            default -> hook + "\n\n" + point;
        };
    }

    // ---- helpers -------------------------------------------------------------------------

    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.replaceAll("\\s+", " ").split("(?<=[.!?])\\s+")) {
            String t = s.strip();
            if (t.length() >= 40 && t.length() <= 260) out.add(t);
        }
        if (out.isEmpty() && !text.isBlank()) out.add(clip(text.strip(), 240));
        return out;
    }

    /** The sentence's opening clause, up to 12 words, never ending on a dangling "a", "but", "to"… */
    private static String phrase(String sentence) {
        String[] words = stripEnd(sentence).replaceAll("[\"“”]", "").split(" ");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(words.length, 12); i++) {
            String w = words[i];
            if (out.size() >= 4 && w.matches(".*[,;:]$")) {
                out.add(w.replaceAll("[,;:]$", ""));
                break;
            }
            out.add(w);
        }
        while (out.size() > 3 && DANGLING.contains(out.get(out.size() - 1).toLowerCase(Locale.ROOT))) {
            out.remove(out.size() - 1);
        }
        return upperFirst(String.join(" ", out));
    }

    private static int draftNumber(String userPrompt) {
        Matcher m = Pattern.compile("This is draft (\\d+) of").matcher(userPrompt);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static String firstLine(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).strip();
        }
        return null;
    }

    private static String between(String text, String start, String end) {
        int a = text.indexOf(start);
        if (a < 0) return "";
        a += start.length();
        int b = text.indexOf(end, a);
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    private static String match(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text == null ? "" : text);
        return m.find() ? m.group(1).strip() : fallback;
    }

    private static String stripEnd(String s) {
        return s.strip().replaceAll("[.!?…]+$", "");
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    private static String upperFirst(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
    }
}
