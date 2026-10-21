package internal.distilly.modules.cost.controller;

import internal.distilly.modules.cost.service.SyntheticCostService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
@RequestMapping("/api/projects/{project}/synthetic-costs")
public class SyntheticCostController {
    private final SyntheticCostService ledger;
    public SyntheticCostController(SyntheticCostService ledger) { this.ledger = ledger; }
    private String owner(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return actor(request);
    }
    @GetMapping
    public Map<String, Object> summary(HttpServletRequest request, HttpServletResponse response, @PathVariable("project") String project) {
        return ledger.summary(owner(request, response), project);
    }
    @GetMapping("/reservations")
    public Map<String, Object> page(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("project") String project,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", defaultValue = "UNRESOLVED") String state) {
        return ledger.page(owner(request, response), project, page, size, state);
    }
    @PostMapping("/reservations")
    public Map<String, Object> reserve(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("project") String project, @RequestBody Map<String, Object> body) {
        return ledger.reserve(owner(request, response), project, body);
    }
    @GetMapping("/reservations/{id}")
    public Map<String, Object> detail(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("project") String project, @PathVariable("id") String id) {
        return ledger.detail(owner(request, response), project, id);
    }
    @PostMapping("/reservations/{id}/outcomes")
    public Map<String, Object> outcome(HttpServletRequest request, HttpServletResponse response,
            @PathVariable("project") String project, @PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ledger.outcome(owner(request, response), project, id, body);
    }
}
