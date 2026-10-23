package internal.distilly.modules.knowledge.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.knowledge.repository.KnowledgeRepository;
import internal.distilly.modules.knowledge.domain.MaterialChunker;
import internal.distilly.modules.task.service.WorkTaskService;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

@Service
public class KnowledgeService {

    private final KnowledgeRepository repository;

    private final JsonCodec json;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    private final WorkTaskService workTaskService;

    public KnowledgeService(KnowledgeRepository repository, JsonCodec json, ResourceRepository resources,
            WorkspaceAccessService access, AuditService audits, WorkTaskService workTaskService) {
        this.repository = repository;
        this.json = json;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
        this.workTaskService = workTaskService;
    }

    @Transactional
    public Map<String, Object> createProfile(String actorId, String projectId, String title) {
        access.require(actorId, projectId, "OWNER", "EDITOR");
        String topicId = id();
        repository.insertTopic(topicId, projectId, text(title, 160));
        audits.record(projectId, actorId, "PROFILE_CREATED", topicId);
        return resources.one("profile", topicId);
    }

    public List<Map<String, Object>> profiles(String actorId, String projectId) {
        String role = access.role(actorId, projectId);
        return repository.findTopics(role, projectId);
    }

    public List<Map<String, Object>> archived(String actorId, String projectId) {
        access.require(actorId, projectId, "OWNER");
        return repository.findArchivedTopics(projectId);
    }

    @Transactional
    public void deleteProfile(String actorId, String topicId) {
        String projectId = resources.projectOf(topicId);
        access.require(actorId, projectId, "OWNER");
        // Lock the profile to serialize deletion with new task submission.
        repository.lockTopic(topicId);
        if (repository.countActiveTasks(topicId) > 0)
            throw error(409, "profile_has_active_jobs");
        repository.archiveTopic(topicId, now());
        audits.record(projectId, actorId, "PROFILE_DELETED", topicId);
    }

    @Transactional
    public void restoreProfile(String actorId, String topicId) {
        var rows = repository.findTopicProject(topicId);
        if (rows.isEmpty())
            throw error(404, "not_found");
        String projectId = (String) rows.get(0).get("project_id");
        access.require(actorId, projectId, "OWNER");
        repository.restoreTopic(topicId);
        audits.record(projectId, actorId, "PROFILE_RESTORED", topicId);
    }

    public List<Map<String, Object>> versions(String actorId, String topicId) {
        String projectId = resources.projectOf(topicId), role = access.role(actorId, projectId);
        return repository.findVersions(role, topicId).stream().map(versionId -> {
            var result = new LinkedHashMap<>(versionId);
            result.put("content", json.decode(versionId.get("content")));
            return (Map<String, Object>) result;
        }).toList();
    }

    public Map<String, Object> published(String actorId, String topicId) {
        var profile = resources.one("profile", topicId);
        access.role(actorId, (String) profile.get("project_id"));
        if (profile.get("published_id") == null)
            throw error(409, "not_published");
        return resources.one("profile_version", (String) profile.get("published_id"));
    }

    @Transactional
    public Map<String, Object> distill(String actorId, String topicId, List<String> ids) {
        repository.lockTopic(topicId);
        var profile = resources.one("profile", topicId);
        String projectId = (String) profile.get("project_id");
        access.require(actorId, projectId, "OWNER", "EDITOR");
        var plan = plan(projectId, profile, ids);
        var queued = new LinkedHashMap<>(workTaskService.enqueue(actorId, projectId, topicId, "DISTILL", plan.payload()));
        queued.put("summary", plan.summary());
        return queued;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preflight(String actorId, String projectId, String topicId, List<String> ids) {
        access.require(actorId, projectId, "OWNER", "EDITOR");
        Map<String, Object> profile;
        if (topicId == null || topicId.isBlank()) {
            profile = new LinkedHashMap<>();
            profile.put("title", "新知识专题");
            profile.put("published_id", null);
        }
        else {
            profile = resources.one("profile", topicId);
            if (!projectId.equals(profile.get("project_id")))
                throw error(403, "profile_project_mismatch");
        }
        return plan(projectId, profile, ids).summary();
    }

    private record DistillPlan(Map<String, Object> payload, Map<String, Object> summary) {
    }

    private DistillPlan plan(String projectId, Map<String, Object> profile, List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 50 || ids.size() != new HashSet<>(ids).size())
            throw error(422, "invalid_material_selection");
        var chunks = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (String m : ids) {
            var material = resources.one("material", m);
            if (!projectId.equals(material.get("project_id")))
                throw error(403, "material_project_mismatch");
            String body = (String) material.get("body");
            total += body.length();
            if (total > 200000)
                throw error(422, "task_material_limit");
            chunks.addAll(MaterialChunker.split(m, body));
        }
        var baseClaims = profile.get("published_id") == null ? List.<Map<String, Object>>of() :
                internal.distilly.common.validation.InputChecks.items(json.decode(resources.one("profile_version",
                        (String) profile.get("published_id")).get("content")).get("claims"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", profile.get("title"));
        payload.put("chunks", chunks);
        payload.put("base_id", profile.get("published_id"));
        payload.put("base_claims", baseClaims);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("material_count", ids.size());
        summary.put("character_count", total);
        summary.put("chunk_count", chunks.size());
        summary.put("base_claim_count", baseClaims.size());
        summary.put("claim_limit", 300);
        summary.put("estimated_claim_ceiling", baseClaims.size() + chunks.size() * 20);
        summary.put("base_id", profile.get("published_id"));
        return new DistillPlan(payload, summary);
    }

    @Transactional
    public Map<String, Object> question(String actorId, String topicId, String question) {
        repository.lockTopic(topicId);
        var versionId = published(actorId, topicId);
        String projectId = resources.projectOf(topicId);
        return workTaskService.enqueue(actorId, projectId, topicId, "ANSWER", Map.of("question", text(question, 4000),
                "version_id", versionId.get("id"), "claims", json.decode(versionId.get("content")).get("claims")));
    }

    @Transactional
    public void deleteTaskExperience(String actorId, String topicId, String jobId) {
        String projectId = resources.projectOf(topicId);
        access.require(actorId, projectId, "OWNER");
        repository.lockTopic(topicId);
        var task = resources.one("job", jobId);
        if (!topicId.equals(task.get("profile_id")) || !"DISTILL".equals(task.get("kind")))
            throw error(404, "not_found");
        if (!"SUCCEEDED".equals(task.get("status")) || task.get("result") == null)
            throw error(409, "experience_not_approved");
        var result = json.decode(task.get("result"));
        if (!(result.get("version_id") instanceof String versionId))
            throw error(409, "experience_not_approved");
        var version = resources.one("profile_version", versionId);
        if (!topicId.equals(version.get("profile_id")) || !"APPROVED".equals(version.get("status")))
            throw error(409, "experience_not_approved");
        if (repository.countActiveTasks(topicId) > 0)
            throw error(409, "profile_has_active_jobs");
        var removed = new HashSet<String>();
        removed.add(versionId);
        var lineage = repository.versionLineage(topicId);
        boolean changed;
        do {
            changed = false;
            for (var entry : lineage) {
                if (removed.contains(entry.get("base_id")))
                    changed |= removed.add((String) entry.get("id"));
            }
        } while (changed);
        for (String id : removed)
            repository.withdrawVersion(topicId, id);
        repository.cancelChatsForChangedKnowledge(topicId);
        workTaskService.deleteJob(actorId, jobId);
        audits.record(projectId, actorId, "DISTILL_EXPERIENCE_DELETED", jobId);
    }

    @Transactional
    public void review(String actorId, String versionId, boolean approve, String note) {
        var version = resources.one("profile_version", versionId);
        String topicId = (String) version.get("profile_id"), projectId = resources.projectOf(topicId);
        access.require(actorId, projectId, "OWNER");
        repository.lockTopic(topicId);
        version = resources.one("profile_version", versionId);
        if (!version.get("status").equals("CANDIDATE"))
            throw error(409, "version_already_reviewed");
        if (approve) {
            int changed = version.get("base_id") == null ? repository.publishFirstVersion(versionId, topicId)
                    : repository.replacePublishedVersion(versionId, topicId, version.get("base_id"));
            if (changed != 1)
                throw error(409, "published_version_changed");
        }
        if (repository.reviewCandidate(approve ? "APPROVED" : "REJECTED", actorId,
                note == null || note.isBlank() ? "" : text(note, 1000), versionId) != 1)
            throw error(409, "version_already_reviewed");
        audits.record(projectId, actorId, approve ? "VERSION_PUBLISHED" : "VERSION_REJECTED", versionId);
    }

    @Transactional
    public void rollback(String actorId, String topicId, String versionId) {
        String projectId = resources.projectOf(topicId);
        access.require(actorId, projectId, "OWNER");
        repository.lockTopic(topicId);
        var version = resources.one("profile_version", versionId);
        if (!topicId.equals(version.get("profile_id")) || !"APPROVED".equals(version.get("status")))
            throw error(422, "invalid_rollback_version");
        repository.setPublishedVersion(versionId, topicId);
        audits.record(projectId, actorId, "VERSION_ROLLED_BACK", versionId);
    }

}
