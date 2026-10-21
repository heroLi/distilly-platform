package internal.distilly;

import internal.distilly.modules.task.domain.TaskFailurePolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextBudgetFailurePolicyTest {
    @Test void unverifiedBudgetIsTerminalAndCannotBecomeAutomaticRetry() {
        assertEquals("MODEL_BUDGET_NOT_VERIFIED",TaskFailurePolicy.normalize("MODEL_BUDGET_NOT_VERIFIED",Set.of()));
        assertEquals(0,TaskFailurePolicy.automaticAttemptLimit("MODEL_BUDGET_NOT_VERIFIED",3));
        assertEquals(3,TaskFailurePolicy.automaticAttemptLimit("MODEL_UNAVAILABLE",3));
    }
}
