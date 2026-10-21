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
        return upload(actorId, projectId, filename, body, null);
    }

    @Transactional
    public Map<String, Object> upload(String actorId, String projectId, String filename, String body, String requestId) {
        access.require(actorId, projectId, "OWNER", "EDITOR");
        filename = text(filename, 180);
        if (!filename.toLowerCase(Locale.ROOT).matches(".*\\.(md|txt)$") || filename.contains("/")
                || filename.contains("\\"))
            throw error(422, "unsupported_filename");
        String normalized = text(body, 1048576);
        String rawDigest = sha256(body);
        body = normalized;
        if (requestId != null) {
            requestId = submissionId(requestId);
            resources.lock("project", projectId);
            access.require(actorId, projectId, "OWNER", "EDITOR");
            var existing = repository.findSubmission(projectId, actorId, requestId);
            if (!existing.isEmpty()) {
                var row = existing.get(0);
                if (!filename.equals(row.get("filename")) || !rawDigest.equals(row.get("raw_digest")))
                    throw error(409, "material_submission_conflict");
                var material = resources.one("material", row.get("material_id").toString());
                return accepted(material.get("id"), material.get("filename"), material.get("digest"), receipt(row));
            }
        }
        String materialId = id();
        String digest = sha256(body);
        long timestamp = now();
        repository.insert(materialId, projectId, filename, body, digest, actorId, timestamp);
        audits.record(projectId, actorId, "MATERIAL_UPLOADED", materialId);
        if (requestId != null) {
            repository.insertSubmission(projectId, actorId, requestId, filename, rawDigest, materialId, timestamp);
            return accepted(materialId, filename, digest, receipt(repository.findSubmission(projectId, actorId, requestId).get(0)));
        }
        return Map.of("id", materialId, "filename", filename, "digest", digest);
    }

    public Map<String, Object> submission(String actorId, String projectId, String requestId) {
        access.require(actorId, projectId, "OWNER", "EDITOR");
        var rows = repository.findSubmission(projectId, actorId, submissionId(requestId));
        if (rows.isEmpty()) throw error(404, "material_submission_not_found");
        return receipt(rows.get(0));
    }

    private String submissionId(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw error(422, "invalid_upload_request_id");
        return value.toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> receipt(Map<String, Object> row) {
        return Map.of("request_id", row.get("request_id"), "material_id", row.get("material_id"), "status", "ACCEPTED");
    }

    private Map<String, Object> accepted(Object id, Object filename, Object digest, Map<String, Object> receipt) {
        return Map.of("id", id, "filename", filename, "digest", digest, "submission", receipt);
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
