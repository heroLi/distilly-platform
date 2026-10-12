package internal.distilly.modules.task.dto;

import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

public record DistillExecutionRequest(String lease_token, String fingerprint) {

    public DistillExecutionRequest {
        lease_token = text(lease_token, 36);
        fingerprint = text(fingerprint, 64);
        if (!fingerprint.matches("[a-f0-9]{64}"))
            throw error(422, "invalid_execution_fingerprint");
    }
}
