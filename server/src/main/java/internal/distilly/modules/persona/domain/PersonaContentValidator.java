package internal.distilly.modules.persona.domain;

import internal.distilly.common.json.JsonCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

public final class PersonaContentValidator {

    private PersonaContentValidator() {
    }

    public static String optional(Map<String, Object> b, String k, int max) {
        return b.get(k) == null || "".equals(b.get(k)) ? "" : text(b.get(k), max);
    }

    public static Map<String, Object> definition(Map<String, Object> b) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", text(b.get("name"), 100));
        String family = text(b.get("family"), 16);
        if (!Set.of("relationship", "celebrity", "colleague").contains(family))
            throw error(422, "invalid_family");
        out.put("family", family);
        for (String k : List.of("background", "personality", "expression", "relationship", "boundaries", "appearance"))
            out.put(k, optional(b, k, 4000));
        String origin = b.getOrDefault("origin", "original").toString();
        if (!Set.of("original", "authorized").contains(origin))
            throw error(422, "invalid_origin");
        if (origin.equals("authorized") && !Boolean.TRUE.equals(b.get("consent")))
            throw error(422, "material_authorization_required");
        out.put("origin", origin);
        out.put("consent", Boolean.TRUE.equals(b.get("consent")));
        return out;
    }

    public static Map<String, Object> versionContent(Object value, JsonCodec json) {
        if (!(value instanceof Map<?, ?>))
            throw error(422, "invalid_persona_content");
        var raw = json.decode(json.encode(value));
        var out = new LinkedHashMap<String, Object>();
        for (String key : List.of("persona_markdown", "analysis", "evidence"))
            out.put(key, raw.get(key));
        out.put("persona_markdown", text(out.get("persona_markdown"), 30000));
        Object analysis = out.get("analysis");
        if (!(analysis instanceof Map<?, ?>) || json.encode(analysis).length() > 30000)
            throw error(422, "invalid_analysis");
        return out;
    }

    public static Map<String, Object> emotion(Object value, String previous) {
        if (!(value instanceof Map<?, ?> e) || !(e.get("intensity") instanceof Number n)
                || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 1)
            throw error(422, "invalid_emotion");
        String label = text(e.get("label"), 20);
        if (!Set.of("calm", "warm", "happy", "sad", "concerned", "curious").contains(label))
            throw error(422, "invalid_emotion_label");
        return Map.of("label", label, "intensity", n.doubleValue(), "reason", text(e.get("reason"), 400), "previous",
                previous);
    }

}
