package internal.distilly.modules.workspace.service;

import internal.distilly.modules.workspace.repository.AuditRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;

@Service
public class AuditService {

    private final AuditRepository repository;

    private final WorkspaceAccessService access;

    public AuditService(AuditRepository repository, WorkspaceAccessService access) {
        this.repository = repository;
        this.access = access;
    }

    public void record(String projectId, String actorId, String action, String resourceId) {
        repository.insert(id(), projectId, actorId, action, resourceId, now());
    }

    public List<Map<String, Object>> auditLog(String actorId, String projectId) {
        access.require(actorId, projectId, "OWNER");
        return repository.findRecentByProject(projectId);
    }

}
