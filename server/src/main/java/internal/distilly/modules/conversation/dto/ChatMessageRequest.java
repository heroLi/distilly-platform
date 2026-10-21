package internal.distilly.modules.conversation.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.text;

public record ChatMessageRequest(String message, Object image, String requestId) {
    public static ChatMessageRequest from(Map<String, Object> body) {
        return new ChatMessageRequest(text(body.get("message"), 4000), body.get("image"),
                body.get("request_id") == null ? null : text(body.get("request_id"), 36));
    }
}
