package internal.distilly.modules.memory.controller;

import internal.distilly.modules.memory.service.MemoryReferenceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class MemoryReferenceController {
    private final MemoryReferenceService references;
    public MemoryReferenceController(MemoryReferenceService references) { this.references = references; }

    @PostMapping(value="/api/personas/{id}/memory-references", produces=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> read(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("id") String id, @RequestBody Map<String,Object> body) {
        response.setHeader("Cache-Control", "no-store");
        return references.read(actor(request), id, body);
    }
}
