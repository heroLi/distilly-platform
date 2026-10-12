package internal.distilly.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import internal.distilly.config.PlatformProperties;
import internal.distilly.security.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static internal.distilly.common.support.Digests.sha256;

@Service
public class UserAccountService {

    private final UserRepository repository;

    private final ObjectMapper json;

    private final PlatformProperties properties;

    public UserAccountService(UserRepository repository, ObjectMapper json, PlatformProperties properties) {
        this.repository = repository;
        this.json = json;
        this.properties = properties;
    }

    @PostConstruct
    public void configureUsers() {
        if (properties.getWorkerToken().length() < 32)
            throw new IllegalStateException("PLATFORM_WORKER_TOKEN must contain at least 32 characters");
        List<Map<String, String>> configured;
        try {
            configured = json.readValue(properties.getUsers(), new TypeReference<>() {
            });
        }
        catch (Exception e) {
            throw new IllegalStateException("PLATFORM_USERS must be valid JSON");
        }
        if (properties.isDemo())
            configured = List.of(Map.of("id", "owner", "token", "demo-owner"),
                    Map.of("id", "editor", "token", "demo-editor"), Map.of("id", "viewer", "token", "demo-viewer"));
        if (configured == null || configured.isEmpty())
            throw new IllegalStateException("Configure PLATFORM_USERS or explicitly enable PLATFORM_DEMO");
        Set<String> activeUsers = new HashSet<>();
        Set<String> activeTokens = new HashSet<>();
        String workerHash = sha256(properties.getWorkerToken());
        // Validate the entire configuration before changing any existing account.
        for (var u : configured) {
            if (u == null)
                throw new IllegalStateException("invalid configured user");
            String id = u.get("id"), token = u.get("token");
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,100}") || token == null
                    || (!properties.isDemo() && token.length() < 32))
                throw new IllegalStateException("invalid configured user");
            if (!activeUsers.add(id))
                throw new IllegalStateException("duplicate configured user");
            String hash = sha256(token);
            if (workerHash.equals(hash))
                throw new IllegalStateException("user and worker credentials must be independent");
            if (!activeTokens.add(hash))
                throw new IllegalStateException("duplicate configured credential");
        }
        for (var u : configured) {
            String id = u.get("id"), token = u.get("token");
            if (repository.updateTokenHash(sha256(token), id) == 0)
                repository.insertUser(id, sha256(token));
        }
        // Leaving demo mode or removing an account must revoke its old token.
        // Keep the row so historical audit and membership references survive.
        for (String previous : repository.findUserIds()) {
            if (!activeUsers.contains(previous))
                repository.updateTokenHash(sha256("revoked:" + UUID.randomUUID()), previous);
        }
    }

    public String actorForHeader(String header) {
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        var ids = repository.findByTokenHash(sha256(token));
        return ids.isEmpty() ? null : ids.get(0);
    }

}
