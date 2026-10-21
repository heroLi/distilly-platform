package internal.distilly.modules.knowledge.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.knowledge.repository.RecordQuestionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

/** Deterministic current-record reading. No model, task, conversation or memory writes. */
@Service
public class RecordQuestionService {
    private final PersonaKnowledgeService knowledge;
    private final ResourceRepository resources;
    private final RecordQuestionRepository repository;

    public RecordQuestionService(PersonaKnowledgeService knowledge, ResourceRepository resources,
            RecordQuestionRepository repository) {
        this.knowledge = knowledge;
        this.resources = resources;
        this.repository = repository;
    }

    @Transactional
    public Map<String, Object> ask(String actor, String personaId, Map<String, Object> body) {
        if (body == null || !body.keySet().equals(java.util.Set.of("question")))
            throw error(422, "invalid_record_question");
        String question = text(body.get("question"), 4000);
        var initial = knowledge.persona(actor, personaId);
        // Serializes role changes, selection changes and published-record revisions.
        // All selected profiles are locked in ID order; never acquire a persona after a profile.
        repository.lockMembership(actor, initial.get("project_id"));
        resources.lock("persona", personaId);
        var persona = knowledge.persona(actor, personaId);
        if (persona.get("published_id") == null)
            throw error(403, "persona_not_published");
        var version = resources.one("persona_version", persona.get("published_id").toString());
        if (!personaId.equals(version.get("persona_id")) || !"APPROVED".equals(version.get("status")))
            throw error(403, "persona_not_published");
        for (String profile : repository.selectedProfiles(personaId))
            resources.lock("profile", profile);
        var cards = new ArrayList<Map<String, Object>>();
        // retrieve joins the current published pointer, APPROVED status, selection,
        // same workspace and archive exclusion in one query. Old approved snapshots are excluded.
        for (var source : knowledge.retrieve(actor, personaId, question)) {
            var card = new LinkedHashMap<String, Object>(source);
            String composite = source.get("id").toString();
            card.put("claim_id", composite.substring(composite.lastIndexOf('/') + 1));
            card.put("origin", "published_workspace_record");
            card.put("label", "已审核工作资料，不证明人物亲历");
            cards.add(card);
        }
        return Map.of("contract", "RECORD_CARDS_V1", "persona_id", personaId,
                "persona_version_id", persona.get("published_id"), "question", question,
                "answer", cards.isEmpty() ? "目前没有匹配的已审核资料记录。可以先核对资料或补充信息。"
                    : "找到以下当前可读记录，请核对来源；这些资料不证明人物亲历。",
                "record_cards", cards, "model_calls", 0);
    }
}
