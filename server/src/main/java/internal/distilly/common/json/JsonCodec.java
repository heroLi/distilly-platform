package internal.distilly.common.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;
import static internal.distilly.common.validation.InputChecks.error;

@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception e) {
            throw error(422, "invalid_json");
        }
    }

    public Map<String, Object> decode(Object value) {
        try {
            return mapper.readValue((String) value, new TypeReference<>() {
            });
        }
        catch (Exception e) {
            throw error(422, "invalid_json");
        }
    }

}
