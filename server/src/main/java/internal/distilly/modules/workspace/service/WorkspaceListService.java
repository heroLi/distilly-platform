package internal.distilly.modules.workspace.service;

import internal.distilly.modules.task.service.WorkTaskService;
import internal.distilly.modules.workspace.repository.WorkspaceListRepository;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.Map;
import internal.distilly.common.persistence.ResourceRepository;
import org.springframework.stereotype.Service;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.items;

@Service
public class WorkspaceListService {

    private final WorkspaceListRepository repository;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final WorkTaskService workTaskService;

    public WorkspaceListService(WorkspaceListRepository repository, WorkspaceAccessService access,
            WorkTaskService workTaskService, ResourceRepository resources) {
        this.repository = repository;
        this.resources = resources;
        this.access = access;
        this.workTaskService = workTaskService;
    }

    public Map<String, Object> topicPage(String actorId, String topicId, int page, int size,
            String q, String status) {
        String projectId = (String) resources.one("profile", topicId).get("project_id");
        access.require(actorId, projectId, "OWNER", "EDITOR");
        if (page < 1 || page > 100000 || size < 1 || size > 100 || q.length() > 160)
            throw error(422, "invalid_pagination");
        var result = repository.search(actorId, projectId, access.role(actorId, projectId), "jobs", page, size, q, status, topicId);
        var rows = result.items().stream().map(x -> {
            var out = workTaskService.jobView(actorId, (String) x.get("id"));
            out.put("profile_title", repository.profileTitle(out.get("profile_id")));
            return out;
        }).toList();
        return Map.of("items", rows, "total", result.total(), "page", page, "size", size);
    }

    public Map<String, Object> listPage(String actorId, String projectId, String type, int page, int size, String q,
            String status) {
        String role = access.role(actorId, projectId);
        if (page < 1 || page > 100000 || size < 1 || size > 100 || q.length() > 160)
            throw error(422, "invalid_pagination");
        boolean material = type.equals("materials");
        if (material)
            access.require(actorId, projectId, "OWNER", "EDITOR");
        var result = repository.search(actorId, projectId, role, type, page, size, q, status);
        long total = result.total();
        var rows = result.items();
        if (!material)
            rows = rows.stream().map(x -> {
                var out = workTaskService.jobView(actorId, (String) x.get("id"));
                out.put("profile_title", repository.profileTitle(out.get("profile_id")));
                return out;
            }).toList();
        return Map.of("items", rows, "total", total, "page", page, "size", size);
    }

}
