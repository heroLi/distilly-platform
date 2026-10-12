package internal.distilly.modules.knowledge.domain;

import java.util.Map;
import java.util.Objects;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

/** Evidence always refers to the task's frozen chunk, never a newly computed split. */
public final class MaterialEvidence {
    private MaterialEvidence() {
    }

    public static Map<String, Object> validate(Map<String, Object> evidence, Map<String, Object> chunk) {
        String quote = text(evidence.get("quote"), 300), chunkId = text(evidence.get("chunk_id"), 100);
        if (quote.length() < 4 || chunk == null || !chunkId.equals(chunk.get("chunk_id"))
                || !Objects.equals(evidence.get("material_id"), chunk.get("material_id"))
                || !(chunk.get("text") instanceof String body) || !body.contains(quote))
            throw error(422, "unverified_evidence");
        return Map.of("material_id", chunk.get("material_id"), "chunk_id", chunkId, "quote", quote);
    }

    public static void validateOriginal(Map<String, Object> chunk, String materialId, String body) {
        if (!materialId.equals(chunk.get("material_id")) || !(chunk.get("text") instanceof String frozen))
            throw error(422, "unverified_evidence");
        long start, end;
        if (chunk.get("start_offset") instanceof Number first && chunk.get("end_offset") instanceof Number last) {
            start = first.longValue();
            end = last.longValue();
        }
        else {
            // Pre-boundary tasks used fixed 6000-unit slices. Only their frozen payload takes this path.
            String chunkId = String.valueOf(chunk.get("chunk_id"));
            if (chunk.containsKey("chunk_strategy") || !chunkId.startsWith(materialId + ":"))
                throw error(422, "unverified_evidence");
            try {
                start = (long) Integer.parseInt(chunkId.substring(materialId.length() + 1)) * 6000;
                end = Math.min(start + 6000, body.length());
            }
            catch (NumberFormatException invalid) {
                throw error(422, "unverified_evidence");
            }
        }
        if (start < 0 || end <= start || end > body.length() || !body.substring((int) start, (int) end).equals(frozen))
            throw error(422, "unverified_evidence");
    }
}
