package internal.distilly.modules.personality.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.text;

public record PersonalityBindingRequest(String personalityId, String versionId) {
    public static PersonalityBindingRequest from(Map<String, Object> body) {
        return new PersonalityBindingRequest(text(body.get("personality_id"), 36), text(body.get("version_id"), 36));
    }
}
