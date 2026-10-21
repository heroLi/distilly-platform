package internal.distilly.modules.cost.controller;

import internal.distilly.modules.cost.service.TextExecutionBudgetService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class TextExecutionBudgetController {
    private final TextExecutionBudgetService budget;
    public TextExecutionBudgetController(TextExecutionBudgetService budget) { this.budget = budget; }
    @PostMapping("/internal/text-budget/reservations")
    public Map<String, Object> reserve(@RequestBody Map<String, Object> body) { return budget.reserve(body); }
    @PostMapping("/internal/text-budget/reservations/{id}/dispatch")
    public Map<String, Object> dispatch(@PathVariable("id") String id, @RequestBody Map<String, Object> body) { return budget.dispatch(id, body); }
    @PostMapping("/internal/text-budget/reservations/{id}/outcomes")
    public Map<String, Object> outcome(@PathVariable("id") String id, @RequestBody Map<String, Object> body) { return budget.outcome(id, body); }
    @GetMapping("/internal/text-budget")
    public Map<String, Object> global() { return budget.summary(null, null); }
    @GetMapping("/api/projects/{project}/text-budget")
    public Map<String, Object> project(HttpServletRequest request, @PathVariable("project") String project) { return budget.summary(actor(request), project); }
}
