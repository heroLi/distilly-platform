package internal.distilly.modules.knowledge.controller;

import internal.distilly.modules.knowledge.service.RecordQuestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class RecordQuestionController {
    private final RecordQuestionService questions;
    public RecordQuestionController(RecordQuestionService questions) { this.questions = questions; }

    @PostMapping("/api/personas/{id}/record-questions")
    public Map<String, Object> ask(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        response.setHeader("Cache-Control", "no-store");
        return questions.ask(actor(request), id, body);
    }
}
