package internal.distilly.modules.memory.controller;

import internal.distilly.modules.memory.service.MemorySyncConsumer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static internal.distilly.web.support.RequestContext.actor;
import static internal.distilly.common.validation.InputChecks.error;

@RestController
public class MemorySyncController {
    private final MemorySyncConsumer consumer;
    public MemorySyncController(MemorySyncConsumer consumer){this.consumer=consumer;}
    @GetMapping("/api/personas/{p}/memory-sync")
    public Map<String,Object> status(HttpServletRequest request,@PathVariable("p") String persona){return consumer.status(actor(request),persona);}
    @PostMapping("/api/personas/{p}/memories/{m}/sync/{action}")
    public Map<String,Object> action(HttpServletRequest request,@PathVariable("p") String persona,@PathVariable("m") String memory,
            @PathVariable("action") String action,@RequestBody Map<String,Object> body){
        if(!body.isEmpty())throw error(422,"invalid_memory_sync_action");
        if("advance".equals(action))return consumer.advance(actor(request),persona,memory);
        if("retry".equals(action))return consumer.retry(actor(request),persona,memory);
        throw error(404,"memory_sync_action_not_found");
    }
}
