package internal.distilly.modules.task.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import internal.distilly.modules.knowledge.domain.MaterialEvidence;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.items;
import static internal.distilly.common.validation.InputChecks.text;

public final class DistillClaims {

    private DistillClaims() {
    }

    public static List<Map<String, Object>> validate(Object output, List<Map<String, Object>> sourceChunks) {
        Map<String, Map<String, Object>> chunks = new HashMap<>();
        for (var chunk : sourceChunks)
            chunks.put((String) chunk.get("chunk_id"), chunk);
        var result = new ArrayList<Map<String, Object>>();
        for (var claim : items(output)) {
            String section = text(claim.get("section"), 80), content = text(claim.get("text"), 2000);
            var evidence = new ArrayList<Map<String, Object>>();
            for (var item : items(claim.get("evidence"))) {
                evidence.add(MaterialEvidence.validate(item, chunks.get(item.get("chunk_id"))));
            }
            if (evidence.isEmpty() || evidence.size() > 5)
                throw error(422, "evidence_required");
            result.add(Map.of("section", section, "text", content, "evidence", evidence));
        }
        return result;
    }
}
