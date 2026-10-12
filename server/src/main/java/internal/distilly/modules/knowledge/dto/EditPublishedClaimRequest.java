package internal.distilly.modules.knowledge.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.error;
import internal.distilly.common.validation.InputChecks;

public record EditPublishedClaimRequest(String versionId, String section, String text, String note) {
    public static EditPublishedClaimRequest from(Map<String, Object> body) {
        Object rawNote = body.get("note");
        if (rawNote != null && (!(rawNote instanceof String note) || note.length() > 1000))
            throw error(422, "invalid_text");
        return new EditPublishedClaimRequest(InputChecks.text(body.get("version_id"), 36), InputChecks.text(body.get("section"), 80),
                InputChecks.text(body.get("text"), 2000), rawNote == null ? "" : ((String) rawNote).strip());
    }
}
