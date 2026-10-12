package internal.distilly.modules.task.dto;

import java.util.List;
import java.util.Map;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

public record ChunkCompletionRequest(String lease_token, String chunk_id, String input_digest,
        List<Map<String, Object>> claims) {

    public ChunkCompletionRequest {
        lease_token = text(lease_token, 36);
        chunk_id = text(chunk_id, 100);
        input_digest = text(input_digest, 64);
        if (!input_digest.matches("[a-f0-9]{64}") || claims == null || claims.size() > 20)
            throw error(422, "invalid_chunk_output");
    }
}
