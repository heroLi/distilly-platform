package internal.distilly.modules.conversation.service;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.conversation.repository.ConversationRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.support.Identifiers.now;
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
        out.put("messages", repository.findMessages(id).stream().map(m -> {
            if (m.get("emotion") != null)
                m.put("emotion", json.decode(m.get("emotion")));
            m.put("citations", json.decode("{\"items\":" + m.get("citations") + "}").get("items"));
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
        var c = personaAccess.conversation(actor, id);
        personaAccess.lock("persona_conversation", id);
        c = personaAccess.conversation(actor, id);
        if (repository.countActiveTasks(id) > 0)
            throw error(409, "conversation_busy");
        String m = id();
        long time = now();
        message = text(message, 4000);
        String attachment = image == null ? null : avatar(image);
        repository.insertUserMessage(m, id, message, time, attachment);
        repository.nameFirstMessage(message.substring(0, Math.min(40, message.length())), id);
        return personaTaskService.enqueue(actor, (String) c.get("persona_id"), id, "CHAT", Map.of("message_id", m));
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
        repository.clearTaskPayloads(id);
        repository.deleteDerivedMemories(id);
        repository.deleteTasks(id);
        repository.deleteMessages(id);
        repository.deleteConversation(id);
    }

}
