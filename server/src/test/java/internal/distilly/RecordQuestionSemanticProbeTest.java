package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.knowledge.service.PersonaKnowledgeService;
import internal.distilly.modules.knowledge.service.RecordQuestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh narrow probes: not the missing historical evaluation corpus or model-quality acceptance. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:fresh-semantic-probe;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "platform.demo=true", "platform.worker-token=semantic-synthetic-worker-token-32-characters" })
class RecordQuestionSemanticProbeTest {
    @Autowired PlatformFixture platform;
    @Autowired PersonaFixture personas;
    @Autowired PersonaKnowledgeService knowledge;
    @Autowired RecordQuestionService questions;
    @Autowired JsonCodec json;

    @Test
    void freshEquivalenceProbesExposeCurrentLexicalLimitWithoutClaimingHistoricalRecovery() throws Exception {
        String project = platform.createProject("owner", "全新合成语义探针").get("id").toString();
        String persona = personas.create("owner", project, Map.of("name", "合成探针助手", "family", "relationship",
                "origin", "original", "personality", "简洁", "consent", false)).get("id").toString();
        personas.generate("owner", persona, List.of());
        var job = personas.claim();
        String pv = personas.complete(job.get("id").toString(), job.get("lease_token").toString(),
                Map.of("persona_markdown", "# 合成探针助手", "analysis", Map.of("inferences", List.of()),
                    "evidence", List.of())).get("version_id").toString();
        personas.review("owner", persona, pv, true);
        var pairs = new LinkedHashMap<String, String>();
        pairs.put("摄影", "拍照"); pairs.put("散心", "漫步"); pairs.put("coffee", "咖啡"); pairs.put("安静", "宁静");
        var topics = new ArrayList<String>();
        int index = 0;
        for (String source : pairs.keySet()) {
            String body = "合成记录：" + source;
            String topic = platform.createProfile("owner", project, "探针条目" + (++index)).get("id").toString();
            String material = platform.upload("owner", project, "synthetic.txt", body).get("id").toString();
            platform.distill("owner", topic, List.of(material));
            var task = platform.claim();
            String version = platform.complete(task.get("id").toString(), task.get("lease_token").toString(), Map.of(
                "claims", List.of(Map.of("section", "合成", "text", body, "evidence", List.of(Map.of(
                    "material_id", material, "chunk_id", material + ":0", "quote", body)))))).get("version_id").toString();
            platform.review("owner", version, true, "新探针，非历史材料");
            topics.add(topic);
        }
        knowledge.configure("owner", persona, topics);
        var results = new ArrayList<Map<String, Object>>();
        for (var pair : pairs.entrySet()) {
            var control = questions.ask("owner", persona, Map.of("question", pair.getKey()));
            var probe = questions.ask("owner", persona, Map.of("question", pair.getValue()));
            boolean literal = !items(control.get("record_cards")).isEmpty();
            boolean semantic = !items(probe.get("record_cards")).isEmpty();
            assertTrue(literal, "literal positive control must retrieve");
            results.add(Map.of("source_term", pair.getKey(), "query_term", pair.getValue(),
                    "literal_control_matched", literal, "semantic_expected_match", true,
                    "current_matched", semantic, "status", semantic ? "MATCHED_NEW_PROBE" : "UNRESOLVED"));
        }
        // Record observed outcomes, rather than converting expected semantic failure into a quality pass.
        var output = Map.of("corpus", "FRESH_NARROW_SYNTHETIC_PROBES_V1", "historical_corpus_restored", false,
                "path", "deterministic_record_cards", "model_calls", 0, "results", results);
        Path report = Path.of("target", "record-semantic-probe.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, json.encode(output) + "\n");
    }
}
