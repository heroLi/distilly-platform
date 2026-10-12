package internal.distilly.modules.personality.controller;

import internal.distilly.modules.personality.dto.PersonalityBindingRequest;
import internal.distilly.modules.personality.service.PersonalityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class PersonalityController {

    private final PersonalityService personalityService;

    public PersonalityController(PersonalityService personalityService) {
        this.personalityService = personalityService;
    }

    @GetMapping("/api/projects/{p}/personalities")
    public List<Map<String, Object>> personalities(HttpServletRequest httpRequest,
            @PathVariable("p") String projectId) {
        return personalityService.personalities(actor(httpRequest), projectId);
    }

    @PostMapping("/api/projects/{p}/personalities")
    public Map<String, Object> createPersonality(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @RequestBody Map<String, Object> body) {
        return personalityService.createPersonality(actor(httpRequest), projectId, body);
    }

    @PutMapping("/api/personas/{id}/personality")
    public Map<String, Object> bindPersonality(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        var request = PersonalityBindingRequest.from(body);
        return personalityService.bindPersonality(actor(httpRequest), id, request.personalityId(), request.versionId());
    }

}
