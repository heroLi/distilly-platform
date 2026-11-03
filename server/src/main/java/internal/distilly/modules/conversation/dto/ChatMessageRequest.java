package internal.distilly.modules.conversation.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.text;
import static internal.distilly.common.validation.InputChecks.error;

public record ChatMessageRequest(String message, Object image, String requestId) {
    public static ChatMessageRequest from(Map<String, Object> body) {
        if (body.containsKey("mode") || body.containsKey("answer_mode"))
            throw error(422, "use_explicit_mode_endpoint");
        return new ChatMessageRequest(text(body.get("message"), 4000), body.get("image"),
                body.get("request_id") == null ? null : text(body.get("request_id"), 36));
    }
}
