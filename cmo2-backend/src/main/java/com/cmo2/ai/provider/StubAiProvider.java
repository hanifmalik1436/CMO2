package com.cmo2.ai.provider;

import com.cmo2.ai.AiMessages.AiRequest;
import com.cmo2.ai.AiMessages.AiResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Default provider: produces realistic, structured marketing copy with no network
 * call, so the whole generate → translate → deploy pipeline runs without an API key.
 * Token counts are estimated from text length so metering/CostGuard behave realistically.
 * Set {@code cmo2.ai.provider=anthropic} (+ an API key) to use real Claude generation.
 */
@Component
public class StubAiProvider implements AiProvider {

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public AiResult generate(AiRequest request, String modelId) {
        String kind = request.kind() == null ? "" : request.kind();
        String text;
        if (request.jsonSchema() != null) {
            text = sampleJson(request.jsonSchema(), "answer").toString();
        } else if (kind.startsWith("STRAT:")) {
            text = strategistDeliverable(kind.substring("STRAT:".length()));
        } else {
            text = switch (kind) {
                case "EMAIL_SEQUENCE" -> emailSequence();
                case "TRANSLATION" -> translate(request.userPrompt());
                case "SMS_CAMPAIGN" -> smsCampaign();
                case "SOCIAL_MEDIA" -> socialPack();
                default -> genericAsset(kind);
            };
        }
        long inTokens = estimateTokens(request.systemPrompt()) + estimateTokens(request.userPrompt());
        long outTokens = estimateTokens(text);
        return new AiResult(text, inTokens, outTokens, modelId + " (demo)");
    }

    /** Demo strategist deliverables; Market Analysis returns the TAM/SAM/SOM exit-criteria set. */
    private String strategistDeliverable(String agentCode) {
        if ("MARKET_ANALYSIS".equals(agentCode)) {
            return "# Market Analysis — Deliverables\n\n"
                    + "## TAM / SAM / SOM\n- TAM: $4.2B\n- SAM: $640M\n- SOM (yr 1): $18M\n\n"
                    + "## Segment heatmap\n- Agencies (3–50 staff): 🔥 high fit\n- Multi-brand operators: 🔥 high\n- Fractional CMOs: 🟧 medium\n- Growth SMBs: 🟧 medium\n\n"
                    + "## Jobs-to-be-done\n- \"Run all my marketing from one place.\"\n- \"Know what's working before the budget burns.\"\n- \"Produce on-brand content without a big team.\"\n\n"
                    + "## Audience hypotheses\n- Buyers feel pain from tool sprawl and manual reporting.\n\n"
                    + "## Top-10 keywords\nmarketing command center, agency dashboard, multi-brand marketing, "
                    + "marketing automation platform, CMO software, GoHighLevel alternative, AI marketing assistant, "
                    + "campaign analytics, lead generation software, marketing reporting tool\n";
        }
        return "# " + agentCode.replace('_', ' ') + " — Deliverables\n\n"
                + "Structured, on-brand recommendations covering each expected deliverable, "
                + "with concrete next steps and owners. (Demo output — connect a real AI provider for live strategy.)\n";
    }

    private long estimateTokens(String s) {
        return s == null ? 0 : Math.max(1, s.length() / 4);
    }

    private String emailSequence() {
        StringBuilder sb = new StringBuilder("# 7-Email Welcome Sequence\n\n");
        String[] themes = {"Welcome & quick win", "The big problem", "Your story", "Social proof",
                "Handle the objection", "The offer", "Last call"};
        for (int i = 0; i < themes.length; i++) {
            sb.append("## Email ").append(i + 1).append(" — ").append(themes[i]).append("\n");
            sb.append("Subject: ").append(themes[i]).append(" (open me)\n\n");
            sb.append("Hi {{first_name}},\n\nHere's a short, punchy message about \"")
              .append(themes[i].toLowerCase())
              .append("\" that moves the reader one step closer to booking. ")
              .append("Clear CTA at the end.\n\nTalk soon,\n{{sender}}\n\n---\n\n");
        }
        return sb.toString();
    }

    private String smsCampaign() {
        return "# SMS Drip (5 messages)\n"
                + "1. Hi {{first_name}}! Quick question — still interested in {{offer}}? Reply YES.\n"
                + "2. Spots are filling up for {{offer}}. Want me to hold one? 📅\n"
                + "3. Here's the link to grab your spot: {{link}}\n"
                + "4. Last chance for the {{offer}} bonus — ends tonight.\n"
                + "5. Doors closing. Reply STOP to opt out, or {{link}} to join.\n";
    }

    private String socialPack() {
        return "# Social Pack (5 posts)\n"
                + "1. [Hook] The one mistake costing you leads → ...\n"
                + "2. [Story] How {{company}} 3x'd bookings in 60 days.\n"
                + "3. [Tip] Steal this 2-line CTA we use everywhere.\n"
                + "4. [Proof] \"Best decision we made this year.\" — happy client.\n"
                + "5. [CTA] Comment 'INFO' and we'll send the playbook.\n";
    }

    private String genericAsset(String kind) {
        String label = kind == null ? "Asset" : kind.replace('_', ' ');
        return "# " + label + "\n\n"
                + "## Headline\nA compelling, benefit-led headline for {{company}}.\n\n"
                + "## Body\nThree tight paragraphs of on-brand copy tailored to the audience, "
                + "leading naturally to a single clear call to action.\n\n"
                + "## Call to action\nBook your free consultation today → {{link}}\n";
    }

    private String translate(String body) {
        // Demo translation: marks the text as Spanish without a real translation engine.
        return "[ES] (traducción de demostración)\n\n" + (body == null ? "" : body);
    }

    /** A small value that matches {@code schema}: one array item, the first enum value, labelled strings. */
    @SuppressWarnings("unchecked")
    static JsonNode sampleJson(Map<String, Object> schema, String name) {
        String type = String.valueOf(schema.get("type"));
        switch (type) {
            case "object" -> {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
                props.forEach((field, sub) -> o.set(field, sampleJson((Map<String, Object>) sub, field)));
                return o;
            }
            case "array" -> {
                ArrayNode a = JsonNodeFactory.instance.arrayNode();
                if (schema.get("items") instanceof Map<?, ?> items) a.add(sampleJson((Map<String, Object>) items, name));
                return a;
            }
            case "number", "integer" -> {
                double min = schema.get("minimum") instanceof Number n ? n.doubleValue() : 0;
                return "integer".equals(type) ? JsonNodeFactory.instance.numberNode((long) min) : JsonNodeFactory.instance.numberNode(min);
            }
            case "boolean" -> {
                return JsonNodeFactory.instance.booleanNode(true);
            }
            default -> {
                if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
                    return JsonNodeFactory.instance.textNode(String.valueOf(values.get(0)));
                }
                String text = "Demo " + name.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
                if (schema.get("maxLength") instanceof Number max && text.length() > max.intValue()) {
                    text = text.substring(0, max.intValue());
                }
                return JsonNodeFactory.instance.textNode(text);
            }
        }
    }
}
