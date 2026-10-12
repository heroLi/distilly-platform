package internal.distilly.web.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

public final class RequestContext {

    private RequestContext() {
    }

    public static String actor(HttpServletRequest request) {
        return (String) request.getAttribute("actor");
    }

    public static String field(Map<String, Object> body, String key, int max) {
        return text(body.get(key), max);
    }

    public static String str(Map<String, Object> body, String key, int max) {
        return text(body.get(key), max);
    }

    public static boolean bool(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof Boolean value))
            throw error(422, "boolean_required");
        return value;
    }

}
