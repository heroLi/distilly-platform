package internal.distilly.modules.persona.service;

import internal.distilly.common.persistence.ResourceRepository;
import internal.distilly.modules.workspace.service.WorkspaceAccessService;
import java.util.Map;
import org.springframework.stereotype.Service;
import static internal.distilly.common.support.Identifiers.id;
import static internal.distilly.common.validation.InputChecks.error;

@Service
public class PersonaAccessService {

    private final ResourceRepository resources;

    private final WorkspaceAccessService access;

    public PersonaAccessService(ResourceRepository resources, WorkspaceAccessService access) {
        this.resources = resources;
        this.access = access;
    }

    public Map<String, Object> persona(String actorId, String id) {
        var personaRow = resources.one("persona", id);
        access.role(actorId, (String) personaRow.get("project_id"));
        if (Boolean.TRUE.equals(personaRow.get("deleted")))
            throw error(404, "persona_deleted");
        return personaRow;
    }

    public void edit(String actorId, Map<String, Object> personaRow) {
        access.require(actorId, (String) personaRow.get("project_id"), "OWNER", "EDITOR");
    }

    public Map<String, Object> conversation(String actorId, String id) {
        var conversationRow = resources.one("persona_conversation", id);
        persona(actorId, (String) conversationRow.get("persona_id"));
        if (!actorId.equals(conversationRow.get("user_id")))
            throw error(403, "private_conversation");
        return conversationRow;
    }

    public void lock(String table, String id) {
        resources.lock(table, id);
    }

}
