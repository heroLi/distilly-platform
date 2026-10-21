package internal.distilly.modules.conversation.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historical per-reply counts only; never expose source IDs, bodies or model settings. */
public final class ContextScope {
    private ContextScope() { }

    public static Map<String, Object> fromBudget(Object input) {
        if (!(input instanceof Map<?, ?> budget)) return null;
        var out = new LinkedHashMap<String, Object>();
        for (String key : List.of("omitted_history", "omitted_memories", "omitted_experiences", "omitted_knowledge")) {
            Long count = count(budget.get(key));
            if (count == null) return null;
            out.put(key, count);
        }
        Long outside = count(budget.get("history_outside_window"));
        if (budget.containsKey("history_outside_window") && outside == null) return null;
        if (outside != null) out.put("history_outside_window", outside);
        out.put("history_window_limit", 24);
        return out;
    }

    private static Long count(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return null;
        long number = ((Number) value).longValue();
        return number >= 0 && number <= 9007199254740991L ? number : null;
    }
}
