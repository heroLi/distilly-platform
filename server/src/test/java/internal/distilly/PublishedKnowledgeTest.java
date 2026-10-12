package internal.distilly;

import internal.distilly.common.json.JsonCodec;
import internal.distilly.modules.knowledge.service.PublishedKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import static internal.distilly.common.validation.InputChecks.items;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:published-test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.demo=true", "platform.worker-token=test-worker-token-32-characters-long" })
@AutoConfigureMockMvc
class PublishedKnowledgeTest {
    @Autowired PlatformFixture platform;
    @Autowired PublishedKnowledgeService knowledge;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    String project, topic, material, version, claim;
    final String source = "接口设计必须校验幂等键，重试不能重复扣款。";

    @BeforeEach
    void setup() {
        for (String table : List.of("job_archive", "job_progress", "profile_archive", "audit", "job",
                "profile_version", "profile", "material", "member", "project"))
            db.update("DELETE FROM " + table);
        project = (String) platform.createProject("owner", "知识分页测试").get("id");
        platform.member("owner", project, "editor", "EDITOR");
        platform.member("owner", project, "viewer", "VIEWER");
        topic = (String) platform.createProfile("owner", project, "接口经验").get("id");
        material = (String) platform.upload("owner", project, "sample.md", source).get("id");
        version = candidate(topic, 13);
        platform.review("owner", version, true, "");
        claim = (String) items(json.decode(platform.published("owner", topic).get("content")).get("claims")).get(0).get("id");
    }

    String candidate(String selectedTopic, int count) {
        platform.distill("owner", selectedTopic, List.of(material));
        var task = platform.claim();
        var claims = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++)
            claims.add(Map.of("section", "接口规范", "text", "规则 " + i + "：" + source + " RETRY",
                    "evidence", List.of(Map.of("material_id", material, "chunk_id", material + ":0", "quote", source))));
        return (String) platform.complete((String) task.get("id"), (String) task.get("lease_token"),
                Map.of("claims", claims)).get("version_id");
    }

    String editBody(String selectedVersion, String content) {
        return json.encode(Map.of("version_id", selectedVersion, "section", "重试规范", "text", content,
                "note", "人工核对原材料"));
    }

    @Test
    void publishedPagePaginatesSearchesAndExcludesOtherVersions() throws Exception {
        String draft = (String) platform.createProfile("owner", project, "未发布专题").get("id");
        candidate(draft, 1);
        String endpoint = "/api/projects/" + project + "/published-knowledge/page";
        mvc.perform(get(endpoint + "?page=2&size=10").header("Authorization", "Bearer demo-viewer"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(13))
            .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get(endpoint).param("q", "规则 12").header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        assertEquals(13, knowledge.page("viewer", project, topic, 1, 10, "retry").get("total"));
        mvc.perform(get(endpoint).param("profile_id", draft).header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(endpoint + "?size=0").header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isUnprocessableEntity());
        String other = (String) platform.createProject("owner", "其他空间").get("id");
        String otherTopic = (String) platform.createProfile("owner", other, "跨空间专题").get("id");
        mvc.perform(get(endpoint).param("profile_id", otherTopic).header("Authorization", "Bearer demo-owner"))
            .andExpect(status().isForbidden());
    }

    @Test
    void manualEditPublishesNewSnapshotAndProtectsEvidenceAndConcurrentChanges() throws Exception {
        String original = (String) platform.published("owner", topic).get("content");
        String endpoint = "/api/profiles/" + topic + "/claims/" + claim;
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-editor").contentType(MediaType.APPLICATION_JSON)
                .content(editBody(version, "人工修订内容"))).andExpect(status().isForbidden());
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-owner").contentType(MediaType.APPLICATION_JSON)
                .content(editBody(version, "接口重试应使用幂等键，避免重复扣款。")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        var current = platform.published("owner", topic);
        String next = (String) current.get("id");
        assertNotEquals(version, next);
        assertEquals(version, current.get("base_id"));
        assertEquals("APPROVED", current.get("status"));
        assertEquals(original, db.queryForObject("SELECT content FROM profile_version WHERE id=?", String.class, version));
        var revised = items(json.decode(current.get("content")).get("claims"));
        assertEquals(claim, revised.get(0).get("id"));
        assertEquals(items(json.decode(original).get("claims")).get(0).get("evidence"), revised.get(0).get("evidence"));
        assertEquals(13, revised.size());
        assertTrue(current.get("work").toString().contains("接口重试应使用幂等键"));
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-owner").contentType(MediaType.APPLICATION_JSON)
                .content(editBody(version, "迟到修改"))).andExpect(status().isConflict());
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-owner").contentType(MediaType.APPLICATION_JSON)
                .content(editBody(next, "接口重试应使用幂等键，避免重复扣款。")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
        assertEquals(2, platform.versions("owner", topic).size());
        assertEquals(1, knowledge.page("viewer", project, topic, 1, 10, "接口重试应使用幂等键").get("total"));
    }

    @Test
    void invalidEvidenceAndMissingClaimCannotBeEdited() throws Exception {
        String endpoint = "/api/profiles/" + topic + "/claims/";
        mvc.perform(put(endpoint + "missing").header("Authorization", "Bearer demo-owner")
                .contentType(MediaType.APPLICATION_JSON).content(editBody(version, "新内容")))
            .andExpect(status().isNotFound());
        var content = json.decode(platform.published("owner", topic).get("content"));
        items(items(content.get("claims")).get(0).get("evidence")).get(0).put("quote", "原文没有这句话");
        db.update("UPDATE profile_version SET content=? WHERE id=?", json.encode(content), version);
        mvc.perform(put(endpoint + claim).header("Authorization", "Bearer demo-owner")
                .contentType(MediaType.APPLICATION_JSON).content(editBody(version, "新内容")))
            .andExpect(status().isUnprocessableEntity());
        assertEquals(version, platform.published("owner", topic).get("id"));
    }

    @Test
    void longMaterialEvidenceUsesFrozenOffsetsThroughIncrementalAndManualRevisions() throws Exception {
        String quote = "重试请求必须沿用原来的幂等键。";
        String body = "最前面的依据。" + "甲".repeat(4200) + "\n\n" + quote + "\n" + "乙".repeat(4200);
        String longMaterial = (String) platform.upload("owner", project, "long.md", body).get("id");
        platform.distill("owner", topic, List.of(longMaterial));
        var job = platform.claim();
        var chunks = items(((Map<?, ?>) job.get("payload")).get("chunks"));
        var chunk = chunks.get(1);
        assertTrue(((Number) chunk.get("start_offset")).intValue() < 6000);
        assertTrue(((String) chunk.get("text")).contains(quote));
        assertFalse(body.substring(6000).contains(quote));
        String frozen = db.queryForObject("SELECT payload FROM job WHERE id=?", String.class, job.get("id"));
        String next = (String) platform.complete((String) job.get("id"), (String) job.get("lease_token"),
                Map.of("claims", List.of(Map.of("section", "长材料", "text", quote, "evidence", List.of(Map.of(
                    "material_id", longMaterial, "chunk_id", chunk.get("chunk_id"), "quote", quote)))))).get("version_id");
        platform.review("owner", next, true, "");
        var content = json.decode(platform.published("owner", topic).get("content"));
        String newClaim = (String) items(content.get("claims")).get(13).get("id");
        String endpoint = "/api/profiles/" + topic + "/claims/" + newClaim;
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-owner").contentType(MediaType.APPLICATION_JSON)
                .content(editBody(next, "第一次人工修订"))).andExpect(status().isOk());
        String incremental = candidate(topic, 1);
        platform.review("owner", incremental, true, "");
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-owner").contentType(MediaType.APPLICATION_JSON)
                .content(editBody(incremental, "增量蒸馏后的人工修订"))).andExpect(status().isOk());
        assertEquals(frozen, db.queryForObject("SELECT payload FROM job WHERE id=?", String.class, job.get("id")));
        // Full-material membership is insufficient: this quote is outside the original frozen chunk.
        var current = platform.published("owner", topic);
        var invalid = json.decode(current.get("content"));
        items(items(invalid.get("claims")).get(13).get("evidence")).get(0).put("quote", "最前面的依据。");
        db.update("UPDATE profile_version SET content=? WHERE id=?", json.encode(invalid), current.get("id"));
        mvc.perform(put(endpoint).header("Authorization", "Bearer demo-owner").contentType(MediaType.APPLICATION_JSON)
                .content(editBody((String) current.get("id"), "非法依据"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void historicalFixedAndBoundaryChunksRemainEditableWithoutRechunking() throws Exception {
        for (boolean fixed : List.of(true, false)) {
            String quote = "历史来源必须保留原始定位。";
            String body = "甲".repeat(fixed ? 6100 : 4300) + quote + "乙".repeat(5000);
            String m = (String) platform.upload("owner", project, "history.md", body).get("id");
            String t = (String) platform.createProfile("owner", project, "旧分块专题").get("id");
            String jobId = (String) platform.distill("owner", t, List.of(m)).get("id");
            var payload = json.decode(db.queryForObject("SELECT payload FROM job WHERE id=?", String.class, jobId));
            var historical = new ArrayList<Map<String, Object>>();
            int start = fixed ? 6000 : 3962;
            historical.add(Map.of("material_id", m, "chunk_id", m + ":0", "text", body.substring(0, fixed ? 6000 : 4202)));
            historical.add(fixed ? Map.of("material_id", m, "chunk_id", m + ":1", "text", body.substring(start))
                    : Map.of("material_id", m, "chunk_id", m + ":1", "text", body.substring(start),
                        "start_offset", start, "end_offset", body.length(), "chunk_strategy", "boundary-overlap-v1"));
            payload.put("chunks", historical);
            db.update("UPDATE job SET payload=? WHERE id=?", json.encode(payload), jobId);
            var job = platform.claim();
            assertEquals(jobId, job.get("id"));
            String v = (String) platform.complete(jobId, (String) job.get("lease_token"), Map.of("claims", List.of(
                    Map.of("section", "历史知识", "text", quote, "evidence", List.of(Map.of(
                        "material_id", m, "chunk_id", m + ":1", "quote", quote)))))).get("version_id");
            platform.review("owner", v, true, "");
            String c = (String) items(json.decode(platform.published("owner", t).get("content")).get("claims")).get(0).get("id");
            mvc.perform(put("/api/profiles/" + t + "/claims/" + c).header("Authorization", "Bearer demo-owner")
                    .contentType(MediaType.APPLICATION_JSON).content(editBody(v, "历史知识人工修订")))
                .andExpect(status().isOk());
        }
    }

    @Test
    void deletingOriginalTaskAlsoWithdrawsManualRevision() throws Exception {
        mvc.perform(put("/api/profiles/" + topic + "/claims/" + claim).header("Authorization", "Bearer demo-owner")
                .contentType(MediaType.APPLICATION_JSON).content(editBody(version, "人工修订经验")))
            .andExpect(status().isOk());
        String task = db.queryForObject("SELECT id FROM job WHERE result LIKE ?", String.class, "%" + version + "%");
        mvc.perform(delete("/api/profiles/" + topic + "/jobs/" + task + "/experience")
                .header("Authorization", "Bearer demo-owner")).andExpect(status().isOk());
        assertEquals(0, knowledge.page("viewer", project, "", 1, 10, "").get("total"));
    }
}
