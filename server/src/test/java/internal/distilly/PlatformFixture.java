package internal.distilly;

import java.util.*;
import org.springframework.stereotype.Component;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceService;
import internal.distilly.modules.material.service.MaterialService;
import internal.distilly.modules.knowledge.service.KnowledgeService;
import internal.distilly.modules.workspace.service.WorkspaceListService;
import internal.distilly.modules.task.service.WorkTaskService;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.common.json.JsonCodec;

@Component
class PlatformFixture {

    private final WorkspaceAccessService workspaceAccessService;

    private final AuditService auditService;

    private final WorkspaceService workspaceService;

    private final MaterialService materialService;

    private final KnowledgeService knowledgeService;

    private final WorkspaceListService workspaceListService;

    private final WorkTaskService workTaskService;

    private final ResourceRepository resources;

    private final JsonCodec json;

    PlatformFixture(WorkspaceAccessService workspaceAccessService, AuditService auditService,
            WorkspaceService workspaceService, MaterialService materialService, KnowledgeService knowledgeService,
            WorkspaceListService workspaceListService, WorkTaskService workTaskService, ResourceRepository resources,
            JsonCodec json) {
        this.workspaceAccessService = workspaceAccessService;
        this.auditService = auditService;
        this.workspaceService = workspaceService;
        this.materialService = materialService;
        this.knowledgeService = knowledgeService;
        this.workspaceListService = workspaceListService;
        this.workTaskService = workTaskService;
        this.resources = resources;
        this.json = json;
    }

    public String role(String actor, String project) {
        return workspaceAccessService.role(actor, project);
    }

    public void require(String actor, String project, String... allowed) {
        workspaceAccessService.require(actor, project, allowed);
    }

    public void audit(String project, String actor, String action, String resource) {
        auditService.record(project, actor, action, resource);
    }

    public List<Map<String, Object>> projects(String actor) {
        return workspaceService.projects(actor);
    }

    public Map<String, Object> createProject(String actor, String title) {
        return workspaceService.createProject(actor, title);
    }

    public List<Map<String, Object>> members(String actor, String p) {
        return workspaceService.members(actor, p);
    }

    public void member(String actor, String p, String user, String role) {
        workspaceService.member(actor, p, user, role);
    }

    public Map<String, Object> upload(String actor, String p, String filename, String body) {
        return materialService.upload(actor, p, filename, body);
    }

    public List<Map<String, Object>> materials(String actor, String p) {
        return materialService.materials(actor, p);
    }

    public Map<String, Object> material(String actor, String m) {
        return materialService.material(actor, m);
    }

    public Map<String, Object> createProfile(String actor, String p, String title) {
        return knowledgeService.createProfile(actor, p, title);
    }

    public List<Map<String, Object>> profiles(String actor, String p) {
        return knowledgeService.profiles(actor, p);
    }

    public List<Map<String, Object>> archived(String actor, String p) {
        return knowledgeService.archived(actor, p);
    }

    public void deleteProfile(String actor, String f) {
        knowledgeService.deleteProfile(actor, f);
    }

    public void restoreProfile(String actor, String f) {
        knowledgeService.restoreProfile(actor, f);
    }

    public Map<String, Object> listPage(String actor, String p, String type, int page, int size, String q,
            String status) {
        return workspaceListService.listPage(actor, p, type, page, size, q, status);
    }

    public List<Map<String, Object>> jobs(String actor, String p) {
        return workTaskService.jobs(actor, p);
    }

    public void progress(String j, String token, int completed) {
        workTaskService.progress(j, token, completed);
    }

    public List<Map<String, Object>> versions(String actor, String f) {
        return knowledgeService.versions(actor, f);
    }

    public Map<String, Object> published(String actor, String f) {
        return knowledgeService.published(actor, f);
    }

    public Map<String, Object> distill(String actor, String f, List<String> ids) {
        return knowledgeService.distill(actor, f, ids);
    }

    public Map<String, Object> enqueue(String actor, String p, String f, String kind, Map<String, Object> payload) {
        return workTaskService.enqueue(actor, p, f, kind, payload);
    }

    public Map<String, Object> question(String actor, String f, String question) {
        return knowledgeService.question(actor, f, question);
    }

    public Map<String, Object> job(String actor, String j) {
        return workTaskService.job(actor, j);
    }

    public Map<String, Object> jobView(String actor, String j) {
        return workTaskService.jobView(actor, j);
    }

    public void controlJob(String actor, String j, String action) {
        workTaskService.controlJob(actor, j, action);
    }

    public void deleteJob(String actor, String j) {
        workTaskService.deleteJob(actor, j);
    }

    public void restoreJob(String actor, String j) {
        workTaskService.restoreJob(actor, j);
    }

    public List<Map<String, Object>> archivedJobs(String actor, String p) {
        return workTaskService.archivedJobs(actor, p);
    }

    public void retry(String actor, String j) {
        workTaskService.retry(actor, j);
    }

    public Map<String, Object> claim() {
        return workTaskService.claim();
    }

    public void heartbeat(String j, String token) {
        workTaskService.heartbeat(j, token);
    }

    public Map<String, Object> complete(String j, String token, Map<String, Object> output) {
        return workTaskService.complete(j, token, output);
    }

    public void fail(String j, String token, String code) {
        workTaskService.fail(j, token, code);
    }

    public void review(String actor, String v, boolean approve, String note) {
        knowledgeService.review(actor, v, approve, note);
    }

    public void rollback(String actor, String f, String v) {
        knowledgeService.rollback(actor, f, v);
    }

    public List<Map<String, Object>> auditLog(String actor, String p) {
        return auditService.auditLog(actor, p);
    }

    public Map<String, Object> one(String t, String id) {
        return resources.one(t, id);
    }

    public Map<String, Object> decode(Object v) {
        return json.decode(v);
    }

    public String encode(Object v) {
        return json.encode(v);
    }

    public static List<Map<String, Object>> items(Object value) {
        return internal.distilly.common.validation.InputChecks.items(value);
    }

}
