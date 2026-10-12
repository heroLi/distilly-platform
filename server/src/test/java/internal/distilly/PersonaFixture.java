package internal.distilly;

import java.util.*;
import org.springframework.stereotype.Component;
import internal.distilly.modules.persona.service.PersonaService;
import internal.distilly.modules.personality.service.PersonalityService;
import internal.distilly.modules.task.service.PersonaTaskService;
import internal.distilly.modules.conversation.service.ConversationService;
import internal.distilly.modules.memory.service.MemoryService;
import internal.distilly.modules.media.service.MediaService;

@Component
class PersonaFixture {

    private final PersonaService personaService;

    private final PersonalityService personalityService;

    private final PersonaTaskService personaTaskService;

    private final ConversationService conversationService;

    private final MemoryService memoryService;

    private final MediaService mediaService;

    PersonaFixture(PersonaService personaService, PersonalityService personalityService,
            PersonaTaskService personaTaskService, ConversationService conversationService, MemoryService memoryService,
            MediaService mediaService) {
        this.personaService = personaService;
        this.personalityService = personalityService;
        this.personaTaskService = personaTaskService;
        this.conversationService = conversationService;
        this.memoryService = memoryService;
        this.mediaService = mediaService;
    }

    public List<Map<String, Object>> list(String actor, String project) {
        return personaService.list(actor, project);
    }

    public List<Map<String, Object>> personalities(String actor, String project) {
        return personalityService.personalities(actor, project);
    }

    public Map<String, Object> createPersonality(String actor, String project, Map<String, Object> b) {
        return personalityService.createPersonality(actor, project, b);
    }

    public Map<String, Object> bindPersonality(String actor, String target, String source, String version) {
        return personalityService.bindPersonality(actor, target, source, version);
    }

    public Map<String, Object> create(String actor, String project, Map<String, Object> b) {
        return personaService.create(actor, project, b);
    }

    public Map<String, Object> detail(String actor, String id) {
        return personaService.detail(actor, id);
    }

    public Map<String, Object> update(String actor, String id, Map<String, Object> b) {
        return personaService.update(actor, id, b);
    }

    public void archive(String actor, String id) {
        personaService.archive(actor, id);
    }

    public List<Map<String, Object>> archived(String actor, String project) {
        return personaService.archived(actor, project);
    }

    public void restore(String actor, String id) {
        personaService.restore(actor, id);
    }

    public Map<String, Object> generate(String actor, String id, List<String> materialIds) {
        return personaService.generate(actor, id, materialIds);
    }

    public Map<String, Object> enqueue(String actor, String persona, String conversation, String kind,
            Map<String, Object> payload) {
        return personaTaskService.enqueue(actor, persona, conversation, kind, payload);
    }

    public Map<String, Object> revise(String actor, String id, String version, Map<String, Object> b) {
        return personaService.revise(actor, id, version, b);
    }

    public void review(String actor, String id, String version, boolean approve) {
        personaService.review(actor, id, version, approve);
    }

    public void rollback(String actor, String id, String version) {
        personaService.rollback(actor, id, version);
    }

    public Map<String, Object> start(String actor, String id, String requestedVersion) {
        return conversationService.start(actor, id, requestedVersion);
    }

    public List<Map<String, Object>> conversations(String actor, String id) {
        return conversationService.conversations(actor, id);
    }

    public Map<String, Object> session(String actor, String id) {
        return conversationService.session(actor, id);
    }

    public Map<String, Object> chat(String actor, String id, String message) {
        return conversationService.chat(actor, id, message);
    }

    public Map<String, Object> chat(String actor, String id, String message, Object image) {
        return conversationService.chat(actor, id, message, image);
    }

    public List<Map<String, Object>> memories(String actor, String id) {
        return memoryService.memories(actor, id);
    }



    public void memory(String actor, String id, String memory, Map<String, Object> b) {
        memoryService.memory(actor, id, memory, b);
    }

    public void forget(String actor, String id, String memory) {
        memoryService.forget(actor, id, memory);
    }

    public void memoryEnabled(String actor, String id, boolean enabled) {
        conversationService.memoryEnabled(actor, id, enabled);
    }

    public void deleteConversation(String actor, String id) {
        conversationService.deleteConversation(actor, id);
    }

    public Map<String, Object> job(String actor, String id) {
        return personaTaskService.job(actor, id);
    }

    public void retry(String actor, String id) {
        personaTaskService.retry(actor, id);
    }

    public void cancel(String actor, String id) {
        personaTaskService.cancel(actor, id);
    }

    public List<Map<String, Object>> jobs(String actor, String id) {
        return personaTaskService.jobs(actor, id);
    }

    public Map<String, Object> claim() {
        return personaTaskService.claim();
    }

    public void heartbeat(String id, String token) {
        personaTaskService.heartbeat(id, token);
    }

    public void fail(String id, String token, String code) {
        personaTaskService.fail(id, token, code);
    }

    public void delta(String id, String token, String partial) {
        personaTaskService.delta(id, token, partial);
    }

    public Map<String, Object> complete(String id, String token, Map<String, Object> output) {
        return personaTaskService.complete(id, token, output);
    }

    public void setAvatar(String actor, String id, Object value) {
        mediaService.setAvatar(actor, id, value);
    }

    public Map<String, Object> generateImage(String actor, String id, String prompt) {
        return mediaService.generateImage(actor, id, prompt);
    }

    public Map<String, Object> media(String actor, String id, Map<String, Object> b) {
        return mediaService.media(actor, id, b);
    }

    public String export(String actor, String id) {
        return personaService.export(actor, id);
    }

}
