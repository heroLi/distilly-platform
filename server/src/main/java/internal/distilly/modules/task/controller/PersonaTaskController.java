package internal.distilly.modules.task.controller;

import java.util.Map;
import java.util.List;
import internal.distilly.modules.task.service.PersonaTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class PersonaTaskController {

    private final PersonaTaskService personaTaskService;

    public PersonaTaskController(PersonaTaskService personaTaskService) {
        this.personaTaskService = personaTaskService;
    }

    @GetMapping("/api/personas/{id}/jobs")
    public List<Map<String, Object>> jobs(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return personaTaskService.jobs(actor(httpRequest), id);
    }

    @GetMapping("/api/persona-jobs/{id}")
    public Map<String, Object> job(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        return personaTaskService.job(actor(httpRequest), id);
    }

    @PostMapping("/api/persona-jobs/{id}/retry")
    public void retry(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        personaTaskService.retry(actor(httpRequest), id);
    }

    @PostMapping("/api/persona-jobs/{id}/cancel")
    public void cancel(HttpServletRequest httpRequest, @PathVariable("id") String id) {
        personaTaskService.cancel(actor(httpRequest), id);
    }

}
