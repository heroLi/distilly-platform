package internal.distilly.modules.persona.controller;

import internal.distilly.common.dto.MaterialSelectionRequest;
import internal.distilly.common.dto.VersionRequest;
import internal.distilly.modules.persona.service.PersonaService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;
import static internal.distilly.web.support.RequestContext.bool;

@RestController
public class PersonaController {

    private final PersonaService personaService;

    public PersonaController(PersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping("/api/projects/{p}/personas")
    public List<Map<String, Object>> list(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return personaService.list(actor(httpRequest), projectId);
    }

    @GetMapping("/api/projects/{p}/archived-personas")
    public List<Map<String, Object>> archived(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return personaService.archived(actor(httpRequest), projectId);
    }

    @PostMapping("/api/personas/{id}/restore")
    public void restore(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        personaService.restore(actor(httpRequest), id);
    }

    @PostMapping("/api/projects/{p}/personas")
    public Map<String, Object> create(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @RequestBody Map<String, Object> body) {
        return personaService.create(actor(httpRequest), projectId, body);
    }

    @GetMapping("/api/personas/{id}")
    public Map<String, Object> detail(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return personaService.detail(actor(httpRequest), id);
    }

    @PutMapping("/api/personas/{id}")
    public Map<String, Object> update(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return personaService.update(actor(httpRequest), id, body);
    }

    @DeleteMapping("/api/personas/{id}")
    public void archive(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        personaService.archive(actor(httpRequest), id);
    }

    @PostMapping("/api/personas/{id}/generate")
    public Map<String, Object> generate(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return personaService.generate(actor(httpRequest), id, MaterialSelectionRequest.from(body, true).materialIds());
    }

    @PostMapping("/api/personas/{id}/versions/{v}/revise")
    public Map<String, Object> revise(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @PathVariable("v") String versionId, @RequestBody Map<String, Object> body) {
        return personaService.revise(actor(httpRequest), id, versionId, body);
    }

    @PostMapping("/api/personas/{id}/versions/{v}/review")
    public void review(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @PathVariable("v") String versionId, @RequestBody Map<String, Object> body) {
        personaService.review(actor(httpRequest), id, versionId, bool(body, "approve"));
    }

    @PostMapping("/api/personas/{id}/rollback")
    public void rollback(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        personaService.rollback(actor(httpRequest), id, VersionRequest.from(body).versionId());
    }

    @GetMapping("/api/personas/{id}/export")
    public ResponseEntity<String> export(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=SKILL.md")
            .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
            .body(personaService.export(actor(httpRequest), id));
    }

}
