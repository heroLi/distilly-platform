package internal.distilly;

import com.fasterxml.jackson.databind.ObjectMapper;
import internal.distilly.config.PlatformProperties;
import internal.distilly.security.repository.UserRepository;
import internal.distilly.security.service.UserAccountService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static internal.distilly.common.support.Digests.sha256;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAccountServiceTest {
    private static final String WORKER = "fixture-worker-independent-32-characters";
    private static final String FIRST = "fixture-first-user-independent-32-characters";
    private static final String SECOND = "fixture-second-user-independent-32-characters";
    private final PlatformProperties properties = new PlatformProperties();
    private final UserRepository repository = mock(UserRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final UserAccountService accounts = new UserAccountService(repository, json, properties);

    private void users(List<Map<String, String>> values) throws Exception {
        properties.setWorkerToken(WORKER);
        properties.setUsers(json.writeValueAsString(values));
    }

    @Test
    void personalTokenCannotAuthorizeInternalWorkerEndpoints() throws Exception {
        users(List.of(Map.of("id", "alice", "token", WORKER)));
        var error = assertThrows(IllegalStateException.class, accounts::configureUsers);
        assertFalse(error.getMessage().contains(WORKER));
        verifyNoInteractions(repository);
    }

    @Test
    void invalidLaterAccountCannotPartiallyRotateEarlierAccount() throws Exception {
        users(List.of(Map.of("id", "alice", "token", FIRST), Map.of("id", "bad id", "token", SECOND)));
        assertThrows(IllegalStateException.class, accounts::configureUsers);
        verifyNoInteractions(repository);
    }

    @Test
    void duplicateUserAndDuplicateTokenFailBeforeAnyWrites() throws Exception {
        users(List.of(Map.of("id", "alice", "token", FIRST), Map.of("id", "alice", "token", SECOND)));
        assertThrows(IllegalStateException.class, accounts::configureUsers);
        verifyNoInteractions(repository);
        users(List.of(Map.of("id", "alice", "token", FIRST), Map.of("id", "bob", "token", FIRST)));
        assertThrows(IllegalStateException.class, accounts::configureUsers);
        verifyNoInteractions(repository);
    }

    @Test
    void nullAccountConfigurationIsRejectedWithoutWritesOrCredentials() {
        properties.setWorkerToken(WORKER);
        properties.setUsers("null");
        var error = assertThrows(IllegalStateException.class, accounts::configureUsers);
        assertFalse(error.getMessage().contains(WORKER));
        verifyNoInteractions(repository);
    }

    @Test
    void nullLaterAccountCannotPartiallyRotateEarlierAccount() throws Exception {
        users(java.util.Arrays.asList(Map.of("id", "alice", "token", FIRST), null));
        assertThrows(IllegalStateException.class, accounts::configureUsers);
        verifyNoInteractions(repository);
    }

    @Test
    void independentAccountsRetainHashingAndRemovedAccountRevocation() throws Exception {
        users(List.of(Map.of("id", "alice", "token", FIRST), Map.of("id", "bob", "token", SECOND)));
        when(repository.findUserIds()).thenReturn(List.of("alice", "bob", "previous"));
        accounts.configureUsers();
        verify(repository).insertUser("alice", sha256(FIRST));
        verify(repository).insertUser("bob", sha256(SECOND));
        verify(repository).updateTokenHash(anyString(), eq("previous"));
    }
}
