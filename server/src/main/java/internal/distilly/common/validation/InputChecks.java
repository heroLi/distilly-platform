package internal.distilly.common.validation;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class InputChecks {

    private InputChecks() {
    }

    public static ResponseStatusException error(int code, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(code), message);
    }

    public static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max)
            throw error(422, "invalid_text");
        return s.strip();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 300 || list.stream().anyMatch(x -> !(x instanceof Map)))
            throw error(422, "invalid_items");
        return (List<Map<String, Object>>) value;
    }

}
