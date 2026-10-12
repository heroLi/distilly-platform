package internal.distilly.common.dto;

import java.util.Map;
import static internal.distilly.common.validation.InputChecks.text;

public record VersionRequest(String versionId) {
    public static VersionRequest from(Map<String, Object> body) {
        return new VersionRequest(text(body.get("version_id"), 36));
    }
}
