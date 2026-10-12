package internal.distilly.modules.knowledge.dto;

import internal.distilly.common.dto.MaterialSelectionRequest;
import java.util.List;
import java.util.Map;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

public record DistillPreflightRequest(String profileId, List<String> materialIds) {

    public static DistillPreflightRequest from(Map<String, Object> body) {
        Object topic = body.get("profile_id");
        if (topic != null && !(topic instanceof String))
            throw error(422, "invalid_profile_id");
        String topicId = topic == null || ((String) topic).isBlank() ? null : text(topic, 36);
        return new DistillPreflightRequest(topicId, MaterialSelectionRequest.from(body, false).materialIds());
    }
}
