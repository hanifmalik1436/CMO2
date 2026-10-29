package com.cmo2.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small JSON Schema helpers for {@link AiService#generateJson}: builders for the subset CMO² uses,
 * a validator for that subset, and the provider copy of a schema.
 *
 * <p>Providers' structured-output modes accept only part of JSON Schema and differ on length and
 * count limits, so they get the schema without those ({@link #forProvider}); the full schema,
 * limits included, is checked here on every answer.
 */
public final class JsonSchemas {

    /** Checked locally, never sent to a provider. */
    static final Set<String> LOCAL_ONLY = Set.of("maxLength", "minLength", "maxItems", "minItems", "minimum", "maximum");

    private JsonSchemas() {}

    // ---- builders --------------------------------------------------------------------------

    /** An object whose properties are all required and that allows nothing else (what strict modes need). */
    public static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.copyOf(properties.keySet()));
        s.put("additionalProperties", false);
        return s;
    }

    public static Map<String, Object> string(int maxLength, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        s.put("maxLength", maxLength);
        if (description != null) s.put("description", description);
        return s;
    }

    public static Map<String, Object> array(Map<String, Object> items, int maxItems, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "array");
        s.put("items", items);
        s.put("maxItems", maxItems);
        if (description != null) s.put("description", description);
        return s;
    }

    public static Map<String, Object> enumOf(List<String> values, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        s.put("enum", List.copyOf(values));
        if (description != null) s.put("description", description);
        return s;
    }

    public static Map<String, Object> number(double minimum, double maximum, String description) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "number");
        s.put("minimum", minimum);
        s.put("maximum", maximum);
        if (description != null) s.put("description", description);
        return s;
    }

    // ---- provider copy ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> forProvider(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        schema.forEach((key, value) -> {
            if (LOCAL_ONLY.contains(key)) return;
            if (value instanceof Map<?, ?> m) {
                if ("properties".equals(key)) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    ((Map<String, Object>) m).forEach((name, sub) -> props.put(name, forProvider((Map<String, Object>) sub)));
                    out.put(key, props);
                } else {
                    out.put(key, forProvider((Map<String, Object>) m));
                }
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    // ---- validation ------------------------------------------------------------------------

    /** Problems with {@code value} against {@code schema}, as "path: problem"; empty when it matches. */
    public static List<String> validate(Map<String, Object> schema, JsonNode value) {
        List<String> problems = new ArrayList<>();
        check(schema, value, "$", problems);
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, JsonNode v, String path, List<String> problems) {
        if (problems.size() >= 20) return;
        String type = (String) schema.get("type");
        if (type != null && !hasType(v, type)) {
            problems.add(path + ": expected " + type);
            return;
        }
        Object en = schema.get("enum");
        if (en instanceof List<?> allowed && !allowed.contains(v.asText())) {
            problems.add(path + ": must be one of " + allowed);
        }
        if (v.isTextual()) {
            int len = v.asText().length();
            if (schema.get("maxLength") instanceof Number max && len > max.intValue()) problems.add(path + ": longer than " + max);
            if (schema.get("minLength") instanceof Number min && len < min.intValue()) problems.add(path + ": shorter than " + min);
        }
        if (v.isNumber()) {
            double d = v.asDouble();
            if (schema.get("maximum") instanceof Number max && d > max.doubleValue()) problems.add(path + ": above " + max);
            if (schema.get("minimum") instanceof Number min && d < min.doubleValue()) problems.add(path + ": below " + min);
        }
        if (v.isArray()) {
            if (schema.get("maxItems") instanceof Number max && v.size() > max.intValue()) problems.add(path + ": more than " + max + " items");
            if (schema.get("minItems") instanceof Number min && v.size() < min.intValue()) problems.add(path + ": fewer than " + min + " items");
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < v.size(); i++) check((Map<String, Object>) items, v.get(i), path + "[" + i + "]", problems);
            }
        }
        if (v.isObject()) {
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            if (schema.get("required") instanceof List<?> required) {
                for (Object name : required) {
                    if (!v.has(String.valueOf(name))) problems.add(path + "." + name + ": missing");
                }
            }
            var fields = v.fieldNames();
            while (fields.hasNext()) {
                String name = fields.next();
                Object sub = props.get(name);
                if (sub instanceof Map<?, ?> m) {
                    check((Map<String, Object>) m, v.get(name), path + "." + name, problems);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    problems.add(path + "." + name + ": not allowed");
                }
            }
        }
    }

    private static boolean hasType(JsonNode v, String type) {
        return switch (type) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "null" -> v.isNull();
            default -> true;
        };
    }
}
