package internal.distilly.modules.knowledge.controller;

import internal.distilly.modules.knowledge.dto.EditPublishedClaimRequest;
import internal.distilly.modules.knowledge.service.PublishedKnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class PublishedKnowledgeController {
    private final PublishedKnowledgeService knowledge;

    public PublishedKnowledgeController(PublishedKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/api/projects/{p}/published-knowledge/page")
    public Map<String, Object> page(HttpServletRequest request, @PathVariable("p") String project,
            @RequestParam(defaultValue = "") String profile_id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "") String q) {
        return knowledge.page(actor(request), project, profile_id, page, size, q);
    }

    @PutMapping("/api/profiles/{f}/claims/{c}")
    public Map<String, Object> edit(HttpServletRequest request, @PathVariable("f") String topic,
            @PathVariable("c") String claim, @RequestBody Map<String, Object> body) {
        return knowledge.edit(actor(request), topic, claim, EditPublishedClaimRequest.from(body));
    }
}
