package internal.distilly.modules.conversation.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.config.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** One budget shared by all text sources. Pinned identity and the current input are never truncated. */
@Service
public class ContextAssembler {
    private final JsonCodec json;
    private final PlatformProperties properties;

    public ContextAssembler(JsonCodec json, PlatformProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    public Map<String, Object> assemble(Map<String, Object> persona, String versionId, String message,
            Map<String, Object> emotion, boolean enabled, List<Map<String, Object>> history,
            List<Map<String, Object>> memories, List<Map<String, Object>> experiences,
            List<Map<String, Object>> knowledge, boolean image) {
        var out = new LinkedHashMap<String, Object>();
        var identity = new LinkedHashMap<String, Object>();
        for (String key : List.of("persona_markdown", "definition", "binding"))
            if (persona.containsKey(key)) identity.put(key, persona.get(key));
        out.put("persona", identity);
        out.put("persona_version_id", versionId);
        out.put("message", message);
        out.put("emotion", emotion);
        out.put("memory_enabled", enabled);
        out.put("history", new ArrayList<Map<String, Object>>());
        out.put("memories", new ArrayList<Map<String, Object>>());
        out.put("experiences", new ArrayList<Map<String, Object>>());
        out.put("knowledge", new ArrayList<Map<String, Object>>());
        int imageReserve = image ? 8192 : 0;
        int limit = properties.getContextBudgetUnits() - 8192 - properties.getReplyOutputTokens() - imageReserve;
        var budget = new LinkedHashMap<String, Object>();
        budget.put("strategy", "utf8-json-upper-bound-v1");
        budget.put("total_units", properties.getContextBudgetUnits());
        budget.put("system_reserve", 8192);
        budget.put("output_tokens", properties.getReplyOutputTokens());
        budget.put("image_reserve", imageReserve);
        budget.put("input_limit", limit);
        budget.put("estimated_input_units", 0);
        budget.put("omitted_history", history.size());
        budget.put("omitted_memories", memories.size());
        budget.put("omitted_experiences", experiences.size());
        budget.put("omitted_knowledge", knowledge.size());
        out.put("context_budget", budget);
        if (estimate(out) > limit) throw new IllegalArgumentException("mandatory_context_exceeds_budget");
        // Reserve some recent continuity before selecting recall; the rest of history uses the remainder.
        var byPosition = new TreeMap<Integer, Map<String, Object>>();
        for (int i = Math.max(0, history.size() - 2); i < history.size(); i++)
            if (append(out, "history", history.get(i), Math.min(limit, estimate(out) + 2048)))
                byPosition.put(i, history.get(i));
        for (var memory : memories) append(out, "memories", memory, limit);
        for (var experience : experiences) append(out, "experiences", experience, limit);
        for (var source : knowledge) append(out, "knowledge", source, limit);
        // Revisit recent turns skipped by the small continuity reservation, then older turns.
        // Large optional entries must not hide smaller ones; message positions are distinct even for equal bodies.
        for (int i = history.size() - 1; i >= 0; i--)
            if (!byPosition.containsKey(i) && append(out, "history", history.get(i), limit))
                byPosition.put(i, history.get(i));
        @SuppressWarnings("unchecked") var selected = (List<Map<String, Object>>) out.get("history");
        selected.clear();
        selected.addAll(byPosition.values());
        budget.put("omitted_history", history.size() - selected.size());
        for (String key : List.of("memories", "experiences", "knowledge"))
            budget.put("omitted_" + key, switch (key) {
                case "memories" -> memories.size(); case "experiences" -> experiences.size(); default -> knowledge.size();
            } - ((List<?>) out.get(key)).size());
        for (int i = 0; i < 3; i++) budget.put("estimated_input_units", estimate(out));
        if (estimate(out) > limit) throw new IllegalArgumentException("context_metadata_exceeds_budget");
        return out;
    }

    public int estimate(Object value) {
        // Twice compact JSON bytes bounds whitespace added by Python's JSON encoder; includes a framing margin.
        return json.encode(value).getBytes(StandardCharsets.UTF_8).length * 2 + 512;
    }

    @SuppressWarnings("unchecked")
    private boolean append(Map<String, Object> out, String key, Map<String, Object> item, int limit) {
        var selected = (List<Map<String, Object>>) out.get(key);
        selected.add(item);
        if (estimate(out) + 128 > limit) { selected.remove(selected.size() - 1); return false; }
        return true;
    }
}
