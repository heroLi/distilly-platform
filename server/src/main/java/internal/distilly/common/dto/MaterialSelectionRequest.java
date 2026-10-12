package internal.distilly.common.dto;

import java.util.List;
import java.util.Map;
import static internal.distilly.common.validation.InputChecks.error;

public record MaterialSelectionRequest(List<String> materialIds) {
    public MaterialSelectionRequest {
        materialIds = List.copyOf(materialIds);
    }

    public static MaterialSelectionRequest from(Map<String, Object> body, boolean optional) {
        Object raw = optional ? body.getOrDefault("material_ids", List.of()) : body.get("material_ids");
        if (!(raw instanceof List<?> ids) || ids.stream().anyMatch(x -> !(x instanceof String)))
            throw error(422, "invalid_material_selection");
        return new MaterialSelectionRequest(ids.stream().map(Object::toString).toList());
    }
}
