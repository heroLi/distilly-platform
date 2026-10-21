package internal.distilly.modules.task.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.task.domain.TaskFailurePolicy;
import internal.distilly.modules.conversation.domain.ExperienceRecall;
import internal.distilly.modules.conversation.service.ContextAssembler;
import internal.distilly.modules.memory.service.MemoryRetrieval;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.config.PlatformProperties;
import internal.distilly.modules.knowledge.service.PersonaKnowledgeService;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.task.repository.PersonaTaskRepository;
import internal.distilly.modules.workspace.service.AuditService;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.items;
import static internal.distilly.common.validation.InputChecks.text;
import static internal.distilly.modules.media.domain.MediaPayloadValidator.audio;
import static internal.distilly.modules.media.domain.MediaPayloadValidator.avatar;
import static internal.distilly.modules.persona.domain.PersonaContentValidator.emotion;
import static internal.distilly.modules.persona.domain.PersonaContentValidator.versionContent;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class PersonaTaskService {

    private final PersonaTaskRepository repository;

    private final JsonCodec json;

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    private final AuditService audits;

    private final PersonaAccessService personaAccess;

    private final PersonaKnowledgeService knowledge;

    private final PlatformProperties properties;

    private final ContextAssembler contexts;

    private final MemoryRetrieval memories;

    private final TaskAdmissionService admission;

    public PersonaTaskService(PlatformProperties properties, PersonaTaskRepository repository, JsonCodec json,
            ResourceRepository resources, WorkspaceAccessService access, AuditService audits,
            PersonaAccessService personaAccess, PersonaKnowledgeService knowledge, ContextAssembler contexts, MemoryRetrieval memories, TaskAdmissionService admission) {
        this.properties = properties;
        this.repository = repository;
        this.json = json;
        this.resources = resources;
        this.access = access;
        this.audits = audits;
        this.personaAccess = personaAccess;
        this.knowledge = knowledge;
        this.contexts = contexts;
        this.memories = memories;
        this.admission = admission;
    }

    @Transactional
    public Map<String, Object> enqueue(String actorId, String persona, String conversation, String kind,
            Map<String, Object> payload) {
        admission.admit(actorId, false);
        String taskRow = id();
        repository.insertQueuedTask(taskRow, persona, conversation, kind, json.encode(payload), actorId, now());
        return Map.of("id", taskRow, "status", "QUEUED");
    }

    public Map<String, Object> job(String actorId, String id) {
        var taskRow = resources.one("persona_job", id);
        var personaRow = personaAccess.persona(actorId, (String) taskRow.get("persona_id"));
        if (Set.of("CHAT", "TTS", "ASR").contains(taskRow.get("kind")) && !actorId.equals(taskRow.get("creator")))
            throw error(403, "private_job");
        if (!Set.of("CHAT", "TTS", "ASR").contains(taskRow.get("kind")))
            personaAccess.edit(actorId, personaRow);
        var out = new LinkedHashMap<String, Object>();
        for (String k : List.of("id", "persona_id", "conversation_id", "kind", "status", "error_code", "attempt",
                "created_at", "partial_reply"))
            out.put(k, taskRow.get(k));
        if (taskRow.get("result") != null)
            out.put("result", json.decode(taskRow.get("result")));
        return out;
    }

    @Transactional
    public void retry(String actorId, String id) {
        job(actorId, id);
        var taskRow = resources.one("persona_job", id);
        personaAccess.lock(taskRow.get("conversation_id") == null ? "persona" : "persona_conversation",
                (String) (taskRow.get("conversation_id") == null ? taskRow.get("persona_id")
                        : taskRow.get("conversation_id")));
        personaAccess.lock("persona_job", id);
        taskRow = resources.one("persona_job", id);
        if (!"FAILED".equals(taskRow.get("status")))
            throw error(409, "job_not_failed");
        if (taskRow.get("conversation_id") != null) {
            var newer = repository.findNewerConversationTasks(taskRow.get("conversation_id"), id,
                    taskRow.get("created_at"));
            if (!newer.isEmpty())
                throw error(409, "newer_turn_exists");
        }
        admission.admit((String) taskRow.get("creator"), false);
        repository.requeueTask(id);
    }

    @Transactional
    public void cancel(String actorId, String id) {
        job(actorId, id);
        repository.cancelTask(id);
    }

    public List<Map<String, Object>> jobs(String actorId, String id) {
        var personaRow = personaAccess.persona(actorId, id);
        boolean editor = !access.role(actorId, (String) personaRow.get("project_id")).equals("VIEWER");
        return repository.findVisibleTasks(editor, id, actorId).stream().map(taskRow -> {
            var out = job(actorId, (String) taskRow.get("id"));
            if (Set.of("TTS", "ASR").contains(out.get("kind")))
                out.remove("result");
            return out;
        }).toList();
    }

    @Transactional
    public Map<String, Object> claim() {
        long timestamp = now();
        repository.failExhaustedLeases(timestamp, properties.getMaxAttempts());
        var rows = repository.findClaimableTasks(timestamp, properties.getMaxAttempts(),
                timestamp - properties.getTaskAgingSeconds() * 1000);
        for (var taskRow : rows) {
            String token = id();
            if (repository.acquireLease(token, timestamp + properties.getLeaseSeconds() * 1000, taskRow.get("id"),
                    timestamp, properties.getMaxAttempts()) != 1)
                continue;
            var payload = json.decode(taskRow.get("payload"));
            if ("CHAT".equals(taskRow.get("kind"))) {
                var conversationRow = resources.one("persona_conversation", (String) taskRow.get("conversation_id"));
                var persona = json.decode(resources.one("persona_version", (String) conversationRow.get("version_id")).get("content"));
                var history = repository.findRecentMessages(conversationRow.get("id"), payload.get("message_id"));
                long outsideWindow = Math.max(0, repository.countOtherMessages(conversationRow.get("id"), payload.get("message_id")) - history.size());
                Collections.reverse(history);
                var message = resources.one("persona_message", (String) payload.get("message_id"));
                String query = (String) message.get("body");
                String recallQuery = query;
                if (query.matches(".*(那次|那件|这件|上次的|那个地方).*"))
                    for (int i = history.size() - 1; i >= 0; i--)
                        if ("user".equals(history.get(i).get("role"))) {
                            recallQuery += " " + history.get(i).get("body"); break;
                        }
                boolean enabled = Boolean.TRUE.equals(conversationRow.get("memory_enabled"));
                var recalled = enabled ? memories.retrieve((String) taskRow.get("creator"), (String) taskRow.get("persona_id"), recallQuery) : List.<Map<String, Object>>of();
                var experiences = ExperienceRecall.retrieve(persona, recallQuery);
                var sources = knowledge.retrieve((String) taskRow.get("creator"), (String) taskRow.get("persona_id"), query);
                try {
                    var assembled = contexts.assemble(persona, (String) conversationRow.get("version_id"), query,
                            json.decode(conversationRow.get("emotion")), enabled, history, recalled, experiences, sources,
                            message.get("image") != null, outsideWindow);
                    var metadata = new LinkedHashMap<String, Object>();
                    metadata.put("message_id", payload.get("message_id"));
                    metadata.put("context_budget", assembled.get("context_budget"));
                    metadata.put("memory_ids", items(assembled.get("memories")).stream().map(x -> x.get("id")).toList());
                    metadata.put("experience_ids", items(assembled.get("experiences")).stream().map(x -> x.get("id")).toList());
                    if (repository.saveContextMetadata(json.encode(metadata), taskRow.get("id"), token) != 1
                            || repository.saveKnowledgeSnapshot(json.encode(assembled.get("knowledge")), taskRow.get("id"), token) != 1)
                        continue;
                    payload = assembled;
                    if (message.get("image") != null) payload.put("image", message.get("image"));
                }
                catch (IllegalArgumentException oversized) {
                    repository.rejectOversizedContext(taskRow.get("id"), token);
                    continue;
                }
            }
            return Map.of("id", taskRow.get("id"), "kind", taskRow.get("kind"), "payload", payload, "lease_token",
                    token, "lease_seconds", properties.getLeaseSeconds());
        }
        return null;
    }

    @Transactional
    public void release(String id, String token) {
        personaAccess.lock("persona_job", id);
        var row = resources.one("persona_job", id);
        if ("QUEUED".equals(row.get("status")) && token.equals(row.get("lease_token")) && row.get("lease_until") == null)
            return;
        if (repository.releaseLease(id, token, now()) != 1)
            throw error(409, "lease_lost");
    }

    @Transactional
    public void heartbeat(String id, String token) {
        if (repository.renewLease(now() + properties.getLeaseSeconds() * 1000, id, token, now()) != 1)
            throw error(409, "lease_lost");
    }

    @Transactional
    public void fail(String id, String token, String code) {
        code = TaskFailurePolicy.normalize(code, Set.of("MEDIA_NOT_CONFIGURED"));
        if (repository.failOrRequeue(TaskFailurePolicy.automaticAttemptLimit(code, properties.getMaxAttempts()), code, code, id, token, now()) != 1)
            throw error(409, "lease_lost");
    }

    @Transactional
    public void delta(String id, String token, String partial) {
        if (partial == null || partial.length() > 12000)
            throw error(422, "invalid_partial_reply");
        if (repository.saveReplyDelta(partial, id, token, now()) != 1)
            throw error(409, "lease_lost");
    }

    @Transactional
    public Map<String, Object> complete(String id, String token, Map<String, Object> output) {
        var taskRow = resources.one("persona_job", id);
        if (taskRow.get("conversation_id") != null)
            personaAccess.lock("persona_conversation", (String) taskRow.get("conversation_id"));
        else
            personaAccess.lock("persona", (String) taskRow.get("persona_id"));
        personaAccess.lock("persona_job", id);
        taskRow = resources.one("persona_job", id);
        if ("SUCCEEDED".equals(taskRow.get("status")) && token.equals(taskRow.get("lease_token")))
            return json.decode(taskRow.get("result"));
        if (!"RUNNING".equals(taskRow.get("status")) || !token.equals(taskRow.get("lease_token"))
                || ((Number) taskRow.get("lease_until")).longValue() < now())
            throw error(409, "lease_lost");
        var payload = json.decode(taskRow.get("payload"));
        Map<String, Object> result;
        if ("PERSONA".equals(taskRow.get("kind"))) {
            var content = versionContent(output, json);
            String versionId = id();
            content.put("definition", payload.get("definition"));
            content.put("source_material_ids",
                    ((List<?>) payload.get("sources")).stream().map(s -> ((Map<?, ?>) s).get("material_id")).toList());
            // Evidence validation is repeated on the server, not trusted solely to the
            // Worker.
            if (!(content.get("evidence") instanceof List<?> evidence) || evidence.size() > 100)
                throw error(422, "invalid_persona_evidence");
            for (Object item : evidence) {
                if (!(item instanceof Map<?, ?> e))
                    throw error(422, "invalid_persona_evidence");
                String quote = text(e.get("quote"), 300);
                if (quote.length() < 4 || ((List<?>) payload.get("sources")).stream().noneMatch(s -> {
                    var source = (Map<?, ?>) s;
                    return Objects.equals(source.get("material_id"), e.get("material_id"))
                            && ((String) source.get("text")).contains(quote);
                }))
                    throw error(422, "unverified_persona_evidence");
            }
            repository.insertCandidateVersion(versionId, taskRow.get("persona_id"), payload.get("base_id"),
                    json.encode(content), taskRow.get("creator"), now());
            result = Map.of("version_id", versionId);
        }
        else if ("CHAT".equals(taskRow.get("kind"))) {
            var sources = items(json.decode("{\"items\":" + taskRow.get("knowledge_snapshot") + "}").get("items"));
            knowledge.validateAccess((String) taskRow.get("creator"), (String) taskRow.get("persona_id"), sources);
            Object rawCitations = output.getOrDefault("citations", List.of());
            if (!(rawCitations instanceof List<?> cited) || cited.size() > 12
                    || cited.stream().anyMatch(x -> !(x instanceof String)))
                throw error(422, "invalid_knowledge_citations");
            var citations = new ArrayList<Map<String, Object>>();
            for (Object citation : new LinkedHashSet<>(cited)) {
                var source = sources.stream()
                    .filter(x -> citation.equals(x.get("id")))
                    .findFirst()
                    .orElseThrow(() -> error(422, "unverified_knowledge_citation"));
                citations.add(source);
            }
            var memoryCitations = recallCitations(output, "memory_citations", payload.get("memory_ids"));
            var experienceCitations = recallCitations(output, "experience_citations", payload.get("experience_ids"));
            String answer = text(output.get("answer"), 12000);
            var conversationRow = resources.one("persona_conversation", (String) taskRow.get("conversation_id"));
            var e = emotion(output.get("emotion"), (String) json.decode(conversationRow.get("emotion")).get("label"));
            String messageId = id();
            repository.insertAssistantMessage(messageId, conversationRow.get("id"), answer, json.encode(e), now());
            repository.saveMessageCitations(json.encode(citations), messageId);
            repository.updateConversationEmotion(json.encode(e), conversationRow.get("id"));
            if (!(output.get("memory_candidates") instanceof List<?> candidates) || candidates.size() > 3)
                throw error(422, "invalid_memories");
            if (Boolean.TRUE.equals(conversationRow.get("memory_enabled")))
                for (Object candidate : candidates) {
                    if (!(candidate instanceof Map<?, ?> mem))
                        throw error(422, "invalid_memory");
                    String body = text(mem.get("body"), 1000), quote = text(mem.get("quote"), 300);
                    String source = (String) resources.one("persona_message", (String) payload.get("message_id"))
                        .get("body");
                    if (quote.length() < 4 || !source.contains(quote))
                        throw error(422, "unverified_memory");
                    if (repository.countDuplicateMemory(taskRow.get("persona_id"), taskRow.get("creator"), body) == 0)
                        repository.insertPendingMemory(id(), taskRow.get("persona_id"), taskRow.get("creator"), body,
                                payload.get("message_id"), now());
                }
            result = Map.of("message_id", messageId, "answer", answer, "emotion", e, "version_id",
                    conversationRow.get("version_id"), "citations", citations, "memory_citations", memoryCitations,
                    "experience_citations", experienceCitations, "context_budget", payload.getOrDefault("context_budget", Map.of()));
        }
        else if ("IMAGE".equals(taskRow.get("kind"))) {
            String avatar = avatar(output.get("avatar"));
            repository.saveAvatar(avatar, taskRow.get("persona_id"));
            result = Map.of("avatar_saved", true);
        }
        else if ("TTS".equals(taskRow.get("kind"))) {
            result = Map.of("audio", audio(output.get("audio")));
        }
        else if ("ASR".equals(taskRow.get("kind"))) {
            result = Map.of("text", text(output.get("text"), 12000));
        }
        else
            throw error(422, "invalid_job_kind");
        repository.completeTask(json.encode(result), id);
        var personaRow = resources.one("persona", (String) taskRow.get("persona_id"));
        audits.record((String) personaRow.get("project_id"), "worker", "PERSONA_JOB_COMPLETED", id);
        return result;
    }

    private List<String> recallCitations(Map<String, Object> output, String key, Object selectedIds) {
        Object raw = output.getOrDefault(key, List.of());
        var allowed = selectedIds instanceof List<?> values ? values : List.of();
        if (!(raw instanceof List<?> cited) || cited.size() > 12
                || cited.stream().anyMatch(x -> !(x instanceof String) || !allowed.contains(x)))
            throw error(422, "unverified_" + key);
        return cited.stream().map(String.class::cast).distinct().toList();
    }

}
