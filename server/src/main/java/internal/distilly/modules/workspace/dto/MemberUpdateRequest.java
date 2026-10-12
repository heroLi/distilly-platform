package internal.distilly.modules.workspace.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.text;

public record MemberUpdateRequest(String userId, String role) {
    public static MemberUpdateRequest from(Map<String, Object> body) {
        return new MemberUpdateRequest(text(body.get("user_id"), 100), text(body.get("role"), 10));
    }
}
