package internal.distilly.modules.workspace.controller;

import internal.distilly.common.dto.TitleRequest;
import internal.distilly.modules.workspace.dto.MemberUpdateRequest;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static internal.distilly.web.support.RequestContext.actor;

@RestController
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    private final AuditService auditService;

    public WorkspaceController(WorkspaceService workspaceService, AuditService auditService) {
        this.workspaceService = workspaceService;
        this.auditService = auditService;
    }

    @GetMapping("/api/projects")
    public List<Map<String, Object>> projects(HttpServletRequest httpRequest) {
        return workspaceService.projects(actor(httpRequest));
    }

    @PostMapping("/api/projects")
    public Map<String, Object> project(HttpServletRequest httpRequest, @RequestBody Map<String, Object> body) {
        return workspaceService.createProject(actor(httpRequest), TitleRequest.from(body).title());
    }

    @GetMapping("/api/projects/{p}/members")
    public List<Map<String, Object>> members(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return workspaceService.members(actor(httpRequest), projectId);
    }

    @PutMapping("/api/projects/{p}/members")
    public void member(HttpServletRequest httpRequest, @PathVariable("p") String projectId,
            @RequestBody Map<String, Object> body) {
        var request = MemberUpdateRequest.from(body);
        workspaceService.member(actor(httpRequest), projectId, request.userId(), request.role());
    }

    @GetMapping("/api/projects/{p}/audit")
    public List<Map<String, Object>> audit(HttpServletRequest httpRequest, @PathVariable("p") String projectId) {
        return auditService.auditLog(actor(httpRequest), projectId);
    }

}
