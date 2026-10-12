package internal.distilly.modules.knowledge.controller;

import internal.distilly.modules.knowledge.dto.KnowledgeSelectionRequest;
import internal.distilly.modules.knowledge.service.PersonaKnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class PersonaKnowledgeController {

    private final PersonaKnowledgeService knowledge;

    public PersonaKnowledgeController(PersonaKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/api/personas/{id}/knowledge")
    public List<Map<String, Object>> knowledge(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return knowledge.configuration(actor(httpRequest), id);
    }

    @PutMapping("/api/personas/{id}/knowledge")
    public void configureKnowledge(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        knowledge.configure(actor(httpRequest), id, KnowledgeSelectionRequest.from(body).profileIds());
    }

}
