package internal.distilly.modules.workspace.controller;

import java.util.Map;
import internal.distilly.modules.workspace.service.WorkspaceListService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class WorkspaceListController {

    private final WorkspaceListService workspaceListService;

    public WorkspaceListController(WorkspaceListService workspaceListService) {
        this.workspaceListService = workspaceListService;
    }

    @GetMapping("/api/projects/{p}/{type:materials|jobs|archived-jobs}/page")
    public Map<String, Object> listPage(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @PathVariable("type") String type, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String status) {
        return workspaceListService.listPage(actor(httpRequest), projectId, type, page, size, q, status);
    }

}
