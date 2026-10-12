package internal.distilly.modules.task.controller;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.web.support.RequestContext.str;

@RestController
public class PersonaTaskWorkerController {

    private final PersonaTaskService personaTaskService;

    private final JsonCodec json;

    public PersonaTaskWorkerController(PersonaTaskService personaTaskService, JsonCodec json) {
        this.personaTaskService = personaTaskService;
        this.json = json;
    }

    @PostMapping("/internal/persona-jobs/claim")
    public ResponseEntity<?> claim() {
        var job = personaTaskService.claim();
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/internal/persona-jobs/{id}/release")
    public void release(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        personaTaskService.release(id, str(body, "lease_token", 36));
    }

    @PostMapping("/internal/persona-jobs/{id}/heartbeat")
    public void heartbeat(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        personaTaskService.heartbeat(id, str(body, "lease_token", 36));
    }

    @PostMapping("/internal/persona-jobs/{id}/delta")
    public void delta(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        Object value = body.get("partial");
        if (!(value instanceof String partial))
            throw error(422, "invalid_partial_reply");
        personaTaskService.delta(id, str(body, "lease_token", 36), partial);
    }

    @PostMapping("/internal/persona-jobs/{id}/fail")
    public void fail(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        personaTaskService.fail(id, str(body, "lease_token", 36), str(body, "code", 40));
    }

    @PostMapping("/internal/persona-jobs/{id}/complete")
    public Map<String, Object> complete(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        if (!(body.get("output") instanceof Map<?, ?>))
            throw error(422, "output_required");
        return personaTaskService.complete(id, str(body, "lease_token", 36),
                json.decode(json.encode(body.get("output"))));
    }

}
