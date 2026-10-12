package internal.distilly.modules.conversation.domain;

import internal.distilly.common.search.TextRelevance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import static internal.distilly.common.validation.InputChecks.items;

/** Read only the pinned version; never turn a reusable template's evidence into a target's life. */
public final class ExperienceRecall {
    private ExperienceRecall() { }

    public static List<Map<String, Object>> retrieve(Map<String, Object> persona, String query) {
        var candidates = new ArrayList<Map<String, Object>>();
        if (persona.get("definition") instanceof Map<?, ?> definition) {
            String background = definition.get("background") instanceof String value ? value : "";
            int index = 0;
            for (String span : background.split("(?<=[。！？.!?])|\\r?\\n")) {
                if (!span.isBlank() && !span.equals("null"))
                    candidates.add(Map.of("id", "background:" + index++, "text", span,
                            "origin", "original".equals(definition.get("origin")) ? "fictional_setting" : "user_setting_unverified"));
            }
        }
        if (!persona.containsKey("binding") && persona.get("evidence") instanceof List<?>) {
            int index = 0;
            for (var source : items(persona.get("evidence"))) {
                if (source.get("quote") instanceof String quote && source.get("material_id") instanceof String material)
                    candidates.add(Map.of("id", "evidence:" + index++, "text", quote,
                            "material_id", material, "origin", "authorized_source_excerpt"));
            }
        }
        return candidates.stream().filter(row -> TextRelevance.score(query, (String) row.get("text")) > 0)
            .sorted(Comparator.<Map<String, Object>>comparingInt(row -> TextRelevance.score(query, (String) row.get("text")))
                .reversed().thenComparing(row -> (String) row.get("id")))
            .limit(8).toList();
    }
}
