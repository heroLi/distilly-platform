package internal.distilly.modules.task.controller;

import java.util.Map;
import java.util.List;
import internal.distilly.modules.task.service.WorkTaskService;
import internal.distilly.modules.workspace.service.WorkspaceListService;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class WorkTaskController {

    private final WorkTaskService workTaskService;
    private final WorkspaceListService lists;

    public WorkTaskController(WorkTaskService workTaskService, WorkspaceListService lists) {
        this.workTaskService = workTaskService;
        this.lists = lists;
    }

    @GetMapping("/api/profiles/{f}/jobs/page")
    public Map<String, Object> topicJobs(HttpServletRequest request, @PathVariable("f") String topic,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int size,
            @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String status) {
        return lists.topicPage(actor(request), topic, page, size, q, status);
    }

    @GetMapping("/api/projects/{p}/jobs")
    public List<Map<String, Object>> jobs(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return workTaskService.jobs(actor(httpRequest), projectId);
    }

    @GetMapping("/api/jobs/{j}")
    public Map<String, Object> job(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        return workTaskService.job(actor(httpRequest), jobId);
    }

    @PostMapping("/api/jobs/{j}/pause")
    public void pause(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        workTaskService.controlJob(actor(httpRequest), jobId, "pause");
    }

    @PostMapping("/api/jobs/{j}/resume")
    public void resume(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        workTaskService.controlJob(actor(httpRequest), jobId, "resume");
    }

    @PostMapping("/api/jobs/{j}/stop")
    public void stop(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        workTaskService.controlJob(actor(httpRequest), jobId, "stop");
    }

    @DeleteMapping("/api/jobs/{j}")
    public void deleteJob(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        workTaskService.deleteJob(actor(httpRequest), jobId);
    }

    @PostMapping("/api/jobs/{j}/restore")
    public void restoreJob(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        workTaskService.restoreJob(actor(httpRequest), jobId);
    }

    @GetMapping("/api/projects/{p}/archived-jobs")
    public List<Map<String, Object>> archivedJobs(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return workTaskService.archivedJobs(actor(httpRequest), projectId);
    }

    @PostMapping("/api/jobs/{j}/retry")
    public void retry(HttpServletRequest httpRequest, @PathVariable("j") String jobId) {
        workTaskService.retry(actor(httpRequest), jobId);
    }

}
