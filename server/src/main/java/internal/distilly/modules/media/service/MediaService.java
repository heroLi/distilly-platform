package internal.distilly.modules.media.service;

import internal.distilly.modules.media.repository.MediaRepository;
import internal.distilly.modules.persona.service.PersonaAccessService;
import internal.distilly.modules.task.service.PersonaTaskService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;
import static internal.distilly.modules.media.domain.MediaPayloadValidator.audio;
import static internal.distilly.modules.media.domain.MediaPayloadValidator.avatar;

@Service
@org.springframework.context.annotation.DependsOn("personaMigration")
public class MediaService {

    private final MediaRepository repository;

    private final PersonaAccessService personaAccess;

    private final PersonaTaskService personaTaskService;

    public MediaService(MediaRepository repository, PersonaAccessService personaAccess,
            PersonaTaskService personaTaskService) {
        this.repository = repository;
        this.personaAccess = personaAccess;
        this.personaTaskService = personaTaskService;
    }

    @Transactional
    public void setAvatar(String actorId, String id, Object value) {
        var personaRow = personaAccess.persona(actorId, id);
        personaAccess.edit(actorId, personaRow);
        repository.updateAvatar(avatar(value), id);
    }

    @Transactional
    public Map<String, Object> generateImage(String actorId, String id, String prompt) {
        var personaRow = personaAccess.persona(actorId, id);
        personaAccess.edit(actorId, personaRow);
        return personaTaskService.enqueue(actorId, id, null, "IMAGE", Map.of("prompt", text(prompt, 2000)));
    }

    @Transactional
    public Map<String, Object> media(String actorId, String id, Map<String, Object> body) {
        var personaRow = personaAccess.persona(actorId, id);
        if (personaRow.get("published_id") == null)
            personaAccess.edit(actorId, personaRow);
        String operation = text(body.get("operation"), 8);
        Map<String, Object> payload;
        if (operation.equals("TTS"))
            payload = Map.of("operation", operation, "text", text(body.get("text"), 12000), "emotion",
                    body.getOrDefault("emotion", "calm"));
        else if (operation.equals("ASR"))
            payload = Map.of("operation", operation, "audio", audio(body.get("audio")));
        else
            throw error(422, "invalid_media_operation");
        return personaTaskService.enqueue(actorId, id, null, operation, payload);
    }

}
