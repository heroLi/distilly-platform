package internal.distilly.modules.task.domain;

import java.util.Map;
import static internal.distilly.common.support.Digests.sha256;

public final class ChunkInputDigest {

    private ChunkInputDigest() {
    }

    public static String of(Map<String, Object> chunk) {
        // Explicit field order, stable across process restarts and JSON map implementations.
        StringBuilder canonical = new StringBuilder("work-chunk-v1");
        for (String key : new String[] { "material_id", "chunk_id", "chunk_strategy", "start_offset", "end_offset", "text" }) {
            String value = String.valueOf(chunk.getOrDefault(key, ""));
            canonical.append('|').append(value.length()).append(':').append(value);
        }
        return sha256(canonical.toString());
    }
}
