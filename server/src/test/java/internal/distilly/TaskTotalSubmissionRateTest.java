package internal.distilly;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:total-submission-rate;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=total-rate-worker-token-at-least-32-characters",
        "platform.max-active-tasks-per-user=0", "platform.task-submissions-per-minute=0", "platform.total-task-submissions-per-minute=2"})
@AutoConfigureMockMvc
class TaskTotalSubmissionRateTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired MockMvc mvc;

    @Test
    void globalFrequencyIncludesDistinctUsersAndSurvivesCancellation() throws Exception {
        String project = (String) platform.createProject("owner", "合成全局频率").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        String profile = (String) platform.createProfile("owner", project, "合成主题").get("id");
        String material = (String) platform.upload("owner", project, "fixture.md", "合成材料").get("id");
        String persona = (String) personas.create("owner", project, Map.of("name", "合成人物", "family", "relationship", "origin", "original",
                "expression", "短句", "personality", "温和", "boundaries", "不推断经历", "background", "原创人物", "consent", false)).get("id");
        for (String actor : List.of("owner", "editor")) {
            String task = (String) platform.distill(actor, profile, List.of(material)).get("id");
            platform.controlJob(actor, task, "stop");
        }
        mvc.perform(post("/api/personas/" + persona + "/image").header("Authorization", "Bearer demo-owner")
                .contentType("application/json").content("{\"prompt\":\"合成测试\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("platform_task_submission_rate_limit"));
    }
}
