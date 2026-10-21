package internal.distilly.modules.conversation.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.conversation.repository.ConversationRepository;
import internal.distilly.modules.conversation.domain.ContextScope;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
import static internal.distilly.common.support.Digests.sha256;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;
import static internal.distilly.modules.media.domain.MediaPayloadValidator.avatar;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class ConversationService {

    private final ConversationRepository repository;

    private final JsonCodec json;

    private final ResourceRepository resources;

    private final PersonaAccessService personaAccess;

    private final PersonaTaskService personaTaskService;

    public ConversationService(ConversationRepository repository, JsonCodec json, ResourceRepository resources,
            PersonaAccessService personaAccess, PersonaTaskService personaTaskService) {
        this.repository = repository;
        this.json = json;
        this.resources = resources;
        this.personaAccess = personaAccess;
        this.personaTaskService = personaTaskService;
    }

    @Transactional
    public Map<String, Object> start(String actor, String id, String requestedVersion) {
        var p = personaAccess.persona(actor, id);
        personaAccess.lock("persona", id);
        p = personaAccess.persona(actor, id);
        String version = requestedVersion == null ? (String) p.get("published_id") : requestedVersion;
        if (version == null)
            throw error(409, "persona_not_published");
        var v = resources.one("persona_version", version);
        if (!id.equals(v.get("persona_id")) || "REJECTED".equals(v.get("status")))
            throw error(422, "invalid_version");
        if (!"APPROVED".equals(v.get("status")))
            personaAccess.edit(actor, p);
        String c = id();
        repository.insertConversation(c, id, version, actor, "新的交流",
                json.encode(Map.of("label", "calm", "intensity", 0.3, "reason", "初次交流", "previous", "calm")), now());
        return session(actor, c);
    }

    public List<Map<String, Object>> conversations(String actor, String id) {
        personaAccess.persona(actor, id);
        return repository.findByPersonaAndUser(id, actor);
    }

    public Map<String, Object> session(String actor, String id) {
        var c = personaAccess.conversation(actor, id);
        var out = new LinkedHashMap<>(c);
        out.put("emotion", json.decode(c.get("emotion")));
        // A visible completed reply and its task result commit atomically. Read
        // messages first so a newly visible reply cannot precede its scope read.
        var messages = repository.findMessages(id);
        var scopes = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : repository.findCompletedChatResults(id, actor)) {
            var result = json.decode(row.get("result"));
            var scope = ContextScope.fromBudget(result.get("context_budget"));
            if (scope != null && result.get("message_id") instanceof String messageId) scopes.put(messageId, scope);
        }
        out.put("messages", messages.stream().map(m -> {
            if (m.get("emotion") != null)
                m.put("emotion", json.decode(m.get("emotion")));
            m.put("citations", json.decode("{\"items\":" + m.get("citations") + "}").get("items"));
            if ("assistant".equals(m.get("role")) && scopes.containsKey(m.get("id")))
                m.put("context_scope", scopes.get(m.get("id")));
            return m;
        }).toList());
        out.put("active_job", repository.findActiveTasks(id));
        var latest = repository.findLatestChatTask(id);
        out.put("latest_chat_job", latest.isEmpty() ? null : latest.get(0));
        return out;
    }

    @Transactional
    public Map<String, Object> chat(String actor, String id, String message) {
        return chat(actor, id, message, null);
    }

    @Transactional
    public Map<String, Object> chat(String actor, String id, String message, Object image) {
        return chat(actor, id, message, image, null);
    }

    @Transactional
    public Map<String, Object> chat(String actor, String id, String message, Object image, String requestId) {
        var c = personaAccess.conversation(actor, id);
        personaAccess.lock("persona_conversation", id);
        c = personaAccess.conversation(actor, id);
        message = text(message, 4000);
        String attachment = image == null ? null : avatar(image);
        String digest = sha256(json.encode(java.util.Arrays.asList(message, attachment)));
        if (requestId != null) {
            requestId = submissionId(requestId);
            var previous = repository.findSubmission(id, requestId);
            if (!previous.isEmpty()) {
                var row = previous.get(0);
                if (!actor.equals(row.get("actor_id"))) throw error(403, "private_conversation");
                if (!digest.equals(row.get("input_digest"))) throw error(409, "submission_conflict");
                var job = new LinkedHashMap<>(personaTaskService.job(actor, row.get("job_id").toString()));
                job.put("submission", receipt(row));
                return job;
            }
        }
        if (repository.countActiveTasks(id) > 0)
            throw error(409, "conversation_busy");
        String m = id();
        long time = now();
        repository.insertUserMessage(m, id, message, time, attachment);
        repository.nameFirstMessage(message.substring(0, Math.min(40, message.length())), id);
        var job = new LinkedHashMap<>(personaTaskService.enqueue(actor, (String) c.get("persona_id"), id, "CHAT", Map.of("message_id", m)));
        if (requestId != null) {
            repository.insertSubmission(id, requestId, actor, digest, m, job.get("id").toString(), time);
            job.put("submission", receipt(repository.findSubmission(id, requestId).get(0)));
        }
        return job;
    }

    public Map<String, Object> submission(String actor, String conversationId, String requestId) {
        personaAccess.conversation(actor, conversationId);
        var rows = repository.findSubmission(conversationId, submissionId(requestId));
        if (rows.isEmpty()) throw error(404, "submission_not_found");
        if (!actor.equals(rows.get(0).get("actor_id"))) throw error(403, "private_conversation");
        return receipt(rows.get(0));
    }

    private String submissionId(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw error(422, "invalid_submission_id");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private Map<String, Object> receipt(Map<String, Object> row) {
        return Map.of("request_id", row.get("request_id"), "message_id", row.get("message_id"),
                "job_id", row.get("job_id"), "status", "ACCEPTED");
    }

    @Transactional
    public void memoryEnabled(String actor, String id, boolean enabled) {
        personaAccess.conversation(actor, id);
        personaAccess.lock("persona_conversation", id);
        repository.updateMemoryEnabled(enabled, id);
        repository.cancelActiveTasks(id);
    }

    @Transactional
    public void deleteConversation(String actor, String id) {
        personaAccess.conversation(actor, id);
        personaAccess.lock("persona_conversation", id);
        repository.deleteSubmissions(id);
        repository.clearTaskPayloads(id);
        repository.deleteDerivedMemories(id);
        repository.deleteTasks(id);
        repository.deleteMessages(id);
        repository.deleteConversation(id);
    }

}
