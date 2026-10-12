package internal.distilly.common.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.text;

public record TitleRequest(String title) {
    public static TitleRequest from(Map<String, Object> body) {
        return new TitleRequest(text(body.get("title"), 160));
    }
}
