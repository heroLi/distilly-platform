package internal.distilly.modules.knowledge.dto;

import java.util.List;
import java.util.Map;
import static internal.distilly.common.validation.InputChecks.error;

public record KnowledgeSelectionRequest(List<String> profileIds) {
    public KnowledgeSelectionRequest {
        profileIds = List.copyOf(profileIds);
    }

    public static KnowledgeSelectionRequest from(Map<String, Object> body) {
        if (!(body.get("profile_ids") instanceof List<?> ids) || ids.stream().anyMatch(x -> !(x instanceof String)))
            throw error(422, "invalid_knowledge_selection");
        return new KnowledgeSelectionRequest(ids.stream().map(Object::toString).toList());
    }
}
