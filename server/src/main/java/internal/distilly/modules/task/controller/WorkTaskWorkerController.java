package internal.distilly.modules.task.controller;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.task.service.WorkTaskService;
import internal.distilly.modules.task.dto.ChunkCompletionRequest;
import internal.distilly.modules.task.dto.DistillExecutionRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.web.support.RequestContext.field;

@RestController
public class WorkTaskWorkerController {

    private final WorkTaskService workTaskService;

    private final JsonCodec json;

    public WorkTaskWorkerController(WorkTaskService workTaskService, JsonCodec json) {
        this.workTaskService = workTaskService;
        this.json = json;
    }

    @PostMapping("/internal/jobs/claim")
    public ResponseEntity<?> claim() {
        var job = workTaskService.claim();
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/internal/jobs/{j}/release")
    public void release(@PathVariable("j") String jobId, @RequestBody Map<String, Object> body) {
        workTaskService.release(jobId, field(body, "lease_token", 36));
    }

    @PostMapping("/internal/jobs/{j}/heartbeat")
    public void heartbeat(@PathVariable("j") String jobId, @RequestBody Map<String, Object> body) {
        workTaskService.heartbeat(jobId, field(body, "lease_token", 36));
    }

    @PostMapping("/internal/jobs/{j}/execution")
    public void execution(@PathVariable("j") String jobId, @RequestBody DistillExecutionRequest request) {
        workTaskService.bindExecution(jobId, request.lease_token(), request.fingerprint());
    }

    @PostMapping("/internal/jobs/{j}/chunks/complete")
    public Map<String, Object> chunkComplete(@PathVariable("j") String jobId,
            @RequestBody ChunkCompletionRequest request) {
        return workTaskService.completeChunk(jobId, request.lease_token(), request.chunk_id(),
                request.input_digest(), request.claims());
    }

    @PostMapping("/internal/jobs/{j}/progress")
    public void progress(@PathVariable("j") String jobId, @RequestBody Map<String, Object> body) {
        if (!(body.get("completed") instanceof Integer completed))
            throw error(422, "invalid_progress");
        workTaskService.progress(jobId, field(body, "lease_token", 36), completed);
    }

    @PostMapping("/internal/jobs/{j}/complete")
    public Map<String, Object> complete(@PathVariable("j") String jobId, @RequestBody Map<String, Object> body) {
        if (!(body.get("output") instanceof Map<?, ?>))
            throw error(422, "output_required");
        return workTaskService.complete(jobId, field(body, "lease_token", 36),
                json.decode(json.encode(body.get("output"))));
    }

    @PostMapping("/internal/jobs/{j}/fail")
    public void fail(@PathVariable("j") String jobId, @RequestBody Map<String, Object> body) {
        workTaskService.fail(jobId, field(body, "lease_token", 36), field(body, "code", 40));
    }

}
