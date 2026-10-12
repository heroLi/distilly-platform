package internal.distilly.modules.knowledge.controller;

import internal.distilly.common.dto.MaterialSelectionRequest;
import internal.distilly.common.dto.TitleRequest;
import internal.distilly.common.dto.VersionRequest;
import internal.distilly.modules.knowledge.service.KnowledgeService;
import internal.distilly.modules.knowledge.dto.DistillPreflightRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.web.support.RequestContext.actor;
import static internal.distilly.web.support.RequestContext.field;

@RestController
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/api/projects/{p}/profiles")
    public List<Map<String, Object>> profiles(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return knowledgeService.profiles(actor(httpRequest), projectId);
    }

    @GetMapping("/api/projects/{p}/archived-profiles")
    public List<Map<String, Object>> archived(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return knowledgeService.archived(actor(httpRequest), projectId);
    }

    @DeleteMapping("/api/profiles/{f}/jobs/{j}/experience")
    public void deleteTaskExperience(HttpServletRequest request, @PathVariable("f") String topicId,
            @PathVariable("j") String jobId) {
        knowledgeService.deleteTaskExperience(actor(request), topicId, jobId);
    }

    @DeleteMapping("/api/profiles/{f}")
    public void deleteProfile(HttpServletRequest httpRequest, @PathVariable("f") String topicId) {
        knowledgeService.deleteProfile(actor(httpRequest), topicId);
    }

    @PostMapping("/api/profiles/{f}/restore")
    public void restoreProfile(HttpServletRequest httpRequest, @PathVariable("f") String topicId) {
        knowledgeService.restoreProfile(actor(httpRequest), topicId);
    }

    @PostMapping("/api/projects/{p}/profiles")
    public Map<String, Object> profile(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @RequestBody Map<String, Object> body) {
        return knowledgeService.createProfile(actor(httpRequest), projectId, TitleRequest.from(body).title());
    }

    @GetMapping("/api/profiles/{f}/versions")
    public List<Map<String, Object>> versions(HttpServletRequest httpRequest, @PathVariable("f") String topicId) {
        return knowledgeService.versions(actor(httpRequest), topicId);
    }

    @PostMapping("/api/profiles/{f}/distill")
    public Map<String, Object> distill(HttpServletRequest httpRequest, @PathVariable("f") String topicId,
            @RequestBody Map<String, Object> body) {
        return knowledgeService.distill(actor(httpRequest), topicId,
                MaterialSelectionRequest.from(body, false).materialIds());
    }

    @PostMapping("/api/projects/{p}/distill/preflight")
    public Map<String, Object> preflight(HttpServletRequest request, @PathVariable("p") String projectId,
            @RequestBody Map<String, Object> body) {
        var selection = DistillPreflightRequest.from(body);
        return knowledgeService.preflight(actor(request), projectId, selection.profileId(), selection.materialIds());
    }

    @PostMapping("/api/versions/{v}/review")
    public void review(HttpServletRequest httpRequest, @PathVariable("v") String versionId,
            @RequestBody Map<String, Object> body) {
        if (!(body.get("approve") instanceof Boolean approved))
            throw error(422, "approve_required");
        knowledgeService.review(actor(httpRequest), versionId, approved, (String) body.get("note"));
    }

    @PostMapping("/api/profiles/{f}/rollback")
    public void rollback(HttpServletRequest httpRequest, @PathVariable("f") String topicId,
            @RequestBody Map<String, Object> body) {
        knowledgeService.rollback(actor(httpRequest), topicId, VersionRequest.from(body).versionId());
    }

    @GetMapping("/api/profiles/{f}/download")
    public ResponseEntity<String> download(HttpServletRequest httpRequest, @PathVariable("f") String topicId) {
        var version = knowledgeService.published(actor(httpRequest), topicId);
        String markdown = "---\nname: work-" + topicId + "\ndescription: \"审核发布的工作经验\"\nuser-invocable: true\n---\n\n"
                + version.get("work");
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=SKILL.md")
            .header("X-Profile-Version", (String) version.get("id"))
            .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
            .body(markdown);
    }

    @PostMapping("/api/profiles/{f}/questions")
    public Map<String, Object> question(HttpServletRequest httpRequest, @PathVariable("f") String topicId,
            @RequestBody Map<String, Object> body) {
        return knowledgeService.question(actor(httpRequest), topicId, field(body, "question", 4000));
    }

}
