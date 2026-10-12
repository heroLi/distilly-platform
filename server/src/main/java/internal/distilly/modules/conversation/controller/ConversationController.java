package internal.distilly.modules.conversation.controller;

import internal.distilly.modules.conversation.dto.ChatMessageRequest;
import internal.distilly.modules.conversation.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;
import static internal.distilly.web.support.RequestContext.bool;
import static internal.distilly.web.support.RequestContext.str;

@RestController
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/api/personas/{id}/conversations")
    public List<Map<String, Object>> conversations(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return conversationService.conversations(actor(httpRequest), id);
    }

    @PostMapping("/api/personas/{id}/conversations")
    public Map<String, Object> start(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return conversationService.start(actor(httpRequest), id,
                body.get("version_id") == null ? null : str(body, "version_id", 36));
    }

    @GetMapping("/api/conversations/{id}")
    public Map<String, Object> session(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return conversationService.session(actor(httpRequest), id);
    }

    @PostMapping("/api/conversations/{id}/messages")
    public Map<String, Object> chat(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        var request = ChatMessageRequest.from(body);
        return conversationService.chat(actor(httpRequest), id, request.message(), request.image());
    }

    @PutMapping("/api/conversations/{id}/memory")
    public void memoryEnabled(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        conversationService.memoryEnabled(actor(httpRequest), id, bool(body, "enabled"));
    }

    @DeleteMapping("/api/conversations/{id}")
    public void deleteConversation(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        conversationService.deleteConversation(actor(httpRequest), id);
    }

}
