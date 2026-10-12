package internal.distilly;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:submission-rate;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=rate-test-worker-token-at-least-32-characters",
        "platform.max-active-tasks-per-user=8", "platform.task-submissions-per-minute=2", "platform.total-task-submissions-per-minute=120"})
class TaskSubmissionRateTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;

    @Test
    void cancellationDeletionRetryAndResumeCannotRefundCommittedSubmissionsButRollbackCan() {
        String project = (String) platform.createProject("owner", "合成频率验收").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        String profile = (String) platform.createProfile("owner", project, "合成主题").get("id");
        String material = (String) platform.upload("owner", project, "fixture.md", "合成材料").get("id");
        String persona = (String) personas.create("owner", project, Map.of("name", "合成人物", "family", "relationship", "origin", "original",
                "expression", "短句", "personality", "温和", "boundaries", "不推断经历", "background", "原创人物", "consent", false)).get("id");
        String failed = (String) platform.distill("owner", profile, List.of(material)).get("id");
        var lease = platform.claim(); platform.fail(failed, (String) lease.get("lease_token"), "INVALID_MODEL_OUTPUT");
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            personas.generateImage("owner", persona, "回滚的合成任务");
            throw new IllegalStateException("synthetic rollback");
        }));
        String second = (String) platform.distill("owner", profile, List.of(material)).get("id");
        platform.controlJob("owner", second, "stop");
        var retry = assertThrows(ResponseStatusException.class, () -> platform.retry("owner", failed));
        assertEquals(429, retry.getStatusCode().value());
        assertEquals("user_task_submission_rate_limit", retry.getReason());
        assertEquals("FAILED", db.queryForObject("SELECT status FROM job WHERE id=?", String.class, failed));
        platform.deleteJob("owner", failed); platform.restoreJob("owner", failed);
        assertThrows(ResponseStatusException.class, () -> platform.distill("owner", profile, List.of(material)));
        String editor = (String) platform.distill("editor", profile, List.of(material)).get("id");
        platform.controlJob("editor", editor, "pause");
        platform.distill("editor", profile, List.of(material));
        var resume = assertThrows(ResponseStatusException.class, () -> platform.controlJob("owner", editor, "resume"));
        assertEquals(429, resume.getStatusCode().value());
        assertEquals("PAUSED", db.queryForObject("SELECT status FROM job WHERE id=?", String.class, editor));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM persona_job", Integer.class));
    }
}
