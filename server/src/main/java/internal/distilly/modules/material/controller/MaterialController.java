package internal.distilly.modules.material.controller;

import java.util.Map;
import java.util.List;
import internal.distilly.modules.material.service.MaterialService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping("/api/projects/{p}/materials")
    public List<Map<String, Object>> materials(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return materialService.materials(actor(httpRequest), projectId);
    }

    @GetMapping("/api/materials/{m}")
    public Map<String, Object> material(HttpServletRequest httpRequest, @PathVariable("m") String materialId) {
        return materialService.material(actor(httpRequest), materialId);
    }

    @PostMapping("/api/projects/{p}/materials")
    public Map<String, Object> upload(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @RequestParam MultipartFile file,
            @RequestParam(name="request_id", required=false) String requestId) throws Exception {
        byte[] bytes = file.getBytes();
        if (bytes.length > 1048576)
            throw error(413, "file_too_large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        }
        catch (CharacterCodingException e) {
            throw error(422, "utf8_required");
        }
        if (text.indexOf('\0') >= 0)
            throw error(422, "binary_content");
        return materialService.upload(actor(httpRequest), projectId, file.getOriginalFilename(), text, requestId);
    }

    @GetMapping("/api/projects/{p}/material-submissions/{requestId}")
    public Map<String, Object> submission(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @PathVariable("requestId") String requestId) {
        return materialService.submission(actor(httpRequest), projectId, requestId);
    }

}
