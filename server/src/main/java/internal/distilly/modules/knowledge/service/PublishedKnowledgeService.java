package internal.distilly.modules.knowledge.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.knowledge.domain.PublishedClaimRevision;
import internal.distilly.modules.knowledge.domain.MaterialEvidence;
import internal.distilly.modules.knowledge.dto.EditPublishedClaimRequest;
import internal.distilly.modules.knowledge.repository.KnowledgeRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.items;

@Service
public class PublishedKnowledgeService {
    private final KnowledgeRepository repository;
    private final ResourceRepository resources;
    private final WorkspaceAccessService access;
    private final JsonCodec json;
    private final AuditService audits;

    public PublishedKnowledgeService(KnowledgeRepository repository, ResourceRepository resources,
            WorkspaceAccessService access, JsonCodec json, AuditService audits) {
        this.repository = repository;
        this.resources = resources;
        this.access = access;
        this.json = json;
        this.audits = audits;
    }

    public Map<String, Object> page(String actor, String project, String topic, int page, int size, String query) {
        access.role(actor, project);
        if (page < 1 || page > 100000 || size < 1 || size > 100 || query.length() > 160)
            throw error(422, "invalid_pagination");
        if (!topic.isBlank() && !project.equals(resources.projectOf(topic)))
            throw error(403, "material_project_mismatch");
        String search = query.strip().toLowerCase(Locale.ROOT);
        var results = new ArrayList<Map<String, Object>>();
        for (var version : repository.currentVersions(project, topic)) {
            for (var claim : items(json.decode(version.get("content")).get("claims"))) {
                String searchable = version.get("profile_title") + " " + claim.get("section") + " " + claim.get("text");
                if (!searchable.toLowerCase(Locale.ROOT).contains(search))
                    continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("profile_id", version.get("profile_id"));
                row.put("profile_title", version.get("profile_title"));
                row.put("version_id", version.get("id"));
                row.put("claim_id", claim.get("id"));
                row.put("section", claim.get("section"));
                row.put("text", claim.get("text"));
                row.put("evidence", claim.get("evidence"));
                row.put("updated_at", version.get("created_at"));
                row.put("reviewer", version.get("reviewer"));
                results.add(row);
            }
        }
        int start = Math.min(results.size(), (page - 1) * size);
        return Map.of("items", results.subList(start, Math.min(results.size(), start + size)),
                "total", results.size(), "page", page, "size", size);
    }

    @Transactional
    public Map<String, Object> edit(String actor, String topicId, String claimId, EditPublishedClaimRequest request) {
        String project = resources.projectOf(topicId);
        access.require(actor, project, "OWNER");
        repository.lockTopic(topicId);
        var topic = resources.one("profile", topicId);
        if (!request.versionId().equals(topic.get("published_id")))
            throw error(409, "published_version_changed");
        var version = resources.one("profile_version", request.versionId());
        if (!"APPROVED".equals(version.get("status")) || !topicId.equals(version.get("profile_id")))
            throw error(409, "version_not_approved");
        var oldClaims = items(json.decode(version.get("content")).get("claims"));
        var claims = PublishedClaimRevision.revise(oldClaims, claimId, request.section(), request.text());
        var edited = claims.stream().filter(c -> claimId.equals(c.get("id"))).findFirst().orElseThrow();
        validateEvidence(project, edited, sourceChunks(topicId, version, claimId));
        if (oldClaims.equals(claims))
            return Map.of("version_id", request.versionId(), "claim_id", claimId, "changed", false);
        String next = id();
        var content = Map.of("claims", claims, "title", topic.get("title"));
        repository.insertRevision(next, topicId, request.versionId(), json.encode(content),
                PublishedClaimRevision.render((String) topic.get("title"), claims), actor, request.note(), now());
        if (repository.replacePublishedVersion(next, topicId, request.versionId()) != 1)
            throw error(409, "published_version_changed");
        audits.record(project, actor, "KNOWLEDGE_MANUALLY_REVISED", next);
        return Map.of("version_id", next, "claim_id", claimId, "changed", true);
    }

    private List<Map<String, Object>> sourceChunks(String topicId, Map<String, Object> version, String claimId) {
        // Manual revisions and incremental snapshots retain IDs: trace back to the introducing version.
        var origin = version;
        var visited = new HashSet<Object>();
        visited.add(origin.get("id"));
        while (origin.get("base_id") instanceof String baseId) {
            if (!visited.add(baseId))
                throw error(422, "unverified_evidence");
            var base = resources.one("profile_version", baseId);
            if (!topicId.equals(base.get("profile_id")))
                throw error(422, "unverified_evidence");
            if (items(json.decode(base.get("content")).get("claims")).stream()
                    .noneMatch(c -> claimId.equals(c.get("id"))))
                break;
            origin = base;
        }
        for (var task : repository.completedDistillationResults(topicId)) {
            if (origin.get("id").equals(json.decode(task.get("result")).get("version_id")))
                return items(json.decode(resources.one("job", (String) task.get("id")).get("payload")).get("chunks"));
        }
        throw error(422, "unverified_evidence");
    }

    private void validateEvidence(String project, Map<String, Object> claim, List<Map<String, Object>> chunks) {
        var evidence = items(claim.get("evidence"));
        if (evidence.isEmpty() || evidence.size() > 5)
            throw error(422, "unverified_evidence");
        for (var source : evidence) {
            if (!(source.get("material_id") instanceof String materialId))
                throw error(422, "unverified_evidence");
            var material = resources.one("material", materialId);
            if (!project.equals(material.get("project_id")))
                throw error(422, "unverified_evidence");
            var chunk = chunks.stream().filter(c -> c.get("chunk_id").equals(source.get("chunk_id")))
                .findFirst().orElseThrow(() -> error(422, "unverified_evidence"));
            MaterialEvidence.validate(source, chunk);
            MaterialEvidence.validateOriginal(chunk, materialId, (String) material.get("body"));
        }
    }
}
