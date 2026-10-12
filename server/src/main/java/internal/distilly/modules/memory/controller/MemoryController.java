package internal.distilly.modules.memory.controller;

import internal.distilly.modules.memory.service.MemoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/api/personas/{id}/memories")
    public List<Map<String, Object>> memories(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return memoryService.memories(actor(httpRequest), id);
    }

    @PutMapping("/api/personas/{id}/memories/{m}")
    public void memory(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @PathVariable("m") String memoryId, @RequestBody Map<String, Object> body) {
        memoryService.memory(actor(httpRequest), id, memoryId, body);
    }

    @DeleteMapping("/api/personas/{id}/memories/{m}")
    public void forget(HttpServletRequest httpRequest, @PathVariable("id") String id,
            @PathVariable("m") String memoryId) {
        memoryService.forget(actor(httpRequest), id, memoryId);
    }

}
