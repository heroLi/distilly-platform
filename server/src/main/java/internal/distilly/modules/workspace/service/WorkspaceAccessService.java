package internal.distilly.modules.workspace.service;

import internal.distilly.modules.workspace.repository.WorkspaceAccessRepository;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.List;
import org.springframework.stereotype.Service;
import static internal.distilly.common.validation.InputChecks.error;

@Service
public class WorkspaceAccessService {

    private final WorkspaceAccessRepository repository;

    public WorkspaceAccessService(WorkspaceAccessRepository repository) {
        this.repository = repository;
    }

    public String role(String actorId, String projectId) {
        var roles = repository.findRole(actorId, projectId);
        if (roles.isEmpty())
            throw error(403, "project_access_denied");
        return roles.get(0);
    }

    public void require(String actorId, String projectId, String... allowed) {
        if (!List.of(allowed).contains(role(actorId, projectId)))
            throw error(403, "role_denied");
    }

}
