package internal.distilly;

import internal.distilly.modules.knowledge.domain.MaterialChunker;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MaterialChunkerTest {

    @Test
    void prefersParagraphBoundaryAndKeepsCrossBoundaryContext() {
        String body = "甲".repeat(4200) + "\n\n" + "乙".repeat(4200);
        var chunks = checked(body);
        assertEquals(4202, chunks.get(0).get("end_offset"));
        assertEquals(3962, chunks.get(1).get("start_offset"));
        assertTrue(((String) chunks.get(1).get("text")).contains("\n\n"));
    }

    @Test
    void keepsFencesTablesAndListsWholeWhenTheyFit() {
        for (String block : List.of("```java\n" + "return value;\n".repeat(100) + "```\n",
                "~~~~python\n" + "print(value)\n".repeat(100) + "~~~~\n",
                "| 字段 | 含义 |\n| --- | --- |\n" + "| key | 幂等键 |\n".repeat(100),
                "- 先校验条件\n" + "  详细处理步骤\n".repeat(100) + "- 最后确认结果\n")) {
            String body = "前".repeat(5400) + "\n\n" + block + "\n" + "后".repeat(4000);
            var chunks = checked(body);
            assertTrue(chunks.stream().anyMatch(c -> ((String) c.get("text")).contains(block)), "split block");
        }
    }

    @Test
    void keepsHeadingWithItsContentAndRecognizesCrLfBlankLines() {
        String block = "## 故障处理\r\n\r\n```java\r\n" + "retry();\r\n".repeat(80) + "```\r\n";
        String body = "前".repeat(5700) + "\r\n  \r\n" + block + "后".repeat(4000);
        var chunks = checked(body);
        assertEquals(5706, chunks.get(0).get("end_offset"));
        assertTrue(chunks.stream().anyMatch(c -> ((String) c.get("text")).contains(block)));
        var paragraphs = checked("甲".repeat(4200) + "\r\n  \r\n" + "乙".repeat(4000));
        assertEquals(4206, paragraphs.get(0).get("end_offset"));
    }

    @Test
    void overlapStartsAtSentenceBoundaryWhenAvailable() {
        String body = "甲".repeat(3950) + "。" + "乙".repeat(249) + "\n\n" + "丙".repeat(4200);
        var chunks = checked(body);
        assertEquals(3951, chunks.get(1).get("start_offset"));
    }

    @Test
    void preservesShortPrefixAndUnclosedOrOversizedBlocksWithoutStalling() {
        for (String body : List.of("\r\n```\n" + "x".repeat(5990) + "\n```\n结束",
                "前\n```\n" + "line();\n".repeat(3000),
                "| 标题 | 值 |\n" + "| 内容 | 数值 |\n".repeat(2000),
                "## 标题\n" + "长".repeat(15000), "```\n" + "x".repeat(25000))) {
            var chunks = checked(body);
            assertTrue(chunks.size() < 20);
        }
    }

    @Test
    void coversOriginalTextWithBoundedSpansAndIntactUnicode() {
        for (String body : new String[] { "", "短材料", "甲".repeat(200000), "甲".repeat(5999) + "😀".repeat(5000),
                "甲".repeat(5999) + "\r\n" + "乙".repeat(12000),
                ("甲".repeat(3400) + "。" + "乙".repeat(900) + "\n").repeat(4) })
            checked(body);
    }

    @Test
    void mixedStructureSpansCoverEveryOriginalCharacter() {
        var random = new Random(20261011);
        String[] units = { "一段正文。", "😀", "\r\n", "\n\n", "## 小节\n", "```js\ncall();\n```\n",
                "- 步骤一\n- 步骤二\n", "| 列 | 值 |\n| --- | --- |\n| a | b |\n", "x".repeat(900) };
        for (int round = 0; round < 30; round++) {
            var body = new StringBuilder();
            for (int i = 0; i < 200; i++)
                body.append(units[random.nextInt(units.length)]);
            checked(body.toString());
        }
    }

    private List<Map<String, Object>> checked(String body) {
        var chunks = MaterialChunker.split("m", body);
        int covered = 0, previousStart = -1;
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            int start = (int) chunk.get("start_offset"), end = (int) chunk.get("end_offset");
            String text = (String) chunk.get("text");
            assertTrue(start <= covered && end > covered && start > previousStart);
            assertTrue(covered - start <= 480);
            assertTrue(text.length() <= 6000);
            assertEquals(body.substring(start, end), text);
            assertEquals("m:" + index, chunk.get("chunk_id"));
            assertEquals("structure-overlap-v2", chunk.get("chunk_strategy"));
            assertFalse(Character.isLowSurrogate(text.charAt(0)));
            assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
            assertFalse(start > 0 && body.charAt(start - 1) == '\r' && body.charAt(start) == '\n');
            assertFalse(end < body.length() && body.charAt(end - 1) == '\r' && body.charAt(end) == '\n');
            covered = end;
            previousStart = start;
        }
        assertEquals(body.length(), covered);
        assertEquals(chunks, MaterialChunker.split("m", body));
        return chunks;
    }
}
