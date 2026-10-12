package internal.distilly.modules.material.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.material.repository.MaterialRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.List;
import static internal.distilly.common.support.Digests.sha256;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

@Service
public class MaterialService {

    private final MaterialRepository repository;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    public MaterialService(MaterialRepository repository, ResourceRepository resources, WorkspaceAccessService access,
            AuditService audits) {
        this.repository = repository;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
    }

    @Transactional
    public Map<String, Object> upload(String actorId, String projectId, String filename, String body) {
        access.require(actorId, projectId, "OWNER", "EDITOR");
        filename = text(filename, 180);
        if (!filename.toLowerCase(Locale.ROOT).matches(".*\\.(md|txt)$") || filename.contains("/")
                || filename.contains("\\"))
            throw error(422, "unsupported_filename");
        body = text(body, 1048576);
        String materialId = id();
        String digest = sha256(body);
        repository.insert(materialId, projectId, filename, body, digest, actorId, now());
        audits.record(projectId, actorId, "MATERIAL_UPLOADED", materialId);
        return Map.of("id", materialId, "filename", filename, "digest", digest);
    }

    public List<Map<String, Object>> materials(String actorId, String projectId) {
        access.require(actorId, projectId, "OWNER", "EDITOR");
        return repository.findMetadataByProject(projectId);
    }

    public Map<String, Object> material(String actorId, String materialId) {
        var data = resources.one("material", materialId);
        access.require(actorId, (String) data.get("project_id"), "OWNER", "EDITOR");
        return data;
    }

}
