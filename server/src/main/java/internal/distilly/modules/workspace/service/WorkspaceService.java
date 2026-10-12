package internal.distilly.modules.workspace.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.workspace.repository.WorkspaceRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

@Service
public class WorkspaceService {

    private final WorkspaceRepository repository;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    public WorkspaceService(WorkspaceRepository repository, ResourceRepository resources, WorkspaceAccessService access,
            AuditService audits) {
        this.repository = repository;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
    }

    public List<Map<String, Object>> projects(String actorId) {
        return repository.findAccessibleProjects(actorId);
    }

    @Transactional
    public Map<String, Object> createProject(String actorId, String title) {
        String projectId = id();
        repository.insertProject(projectId, text(title, 160));
        repository.insertOwner(projectId, actorId, "OWNER");
        audits.record(projectId, actorId, "PROJECT_CREATED", projectId);
        return resources.one("project", projectId);
    }

    public List<Map<String, Object>> members(String actorId, String projectId) {
        access.require(actorId, projectId, "OWNER");
        return repository.findMembers(projectId);
    }

    @Transactional
    public void member(String actorId, String projectId, String user, String role) {
        access.require(actorId, projectId, "OWNER");
        if (!List.of("OWNER", "EDITOR", "VIEWER").contains(role))
            throw error(422, "invalid_role");
        if (actorId.equals(user))
            throw error(409, "cannot_change_own_role");
        if (repository.countUser(user) == 0)
            throw error(422, "unknown_user");
        if (repository.updateMemberRole(role, projectId, user) == 0)
            repository.insertMember(projectId, user, role);
        audits.record(projectId, actorId, "MEMBER_CHANGED", projectId);
    }

}
