package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SchemaLoader;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * SPEC-018 M2: SchemaLoader 단위 테스트.
 */
public class SchemaLoaderTest {

    @Test
    public void parsesCommonSchemaFromClasspath() throws IOException {
        SchemaLoader loader = new SchemaLoader();
        SemanticSchema schema = loader.loadResource("semantic-schemas/common.schema.json");
        assertEquals("common", schema.schemaId);
        assertEquals("공통", schema.schemaName);
        // 라벨 9개 (PAGE_HEADER, PAGE_FOOTER, SECTION_TITLE, BODY_TEXT, FIGURE,
        //          CAPTION, TABLE, BACKGROUND, DECORATION)
        assertEquals(9, schema.labels.size());
        // 룰 6개
        assertEquals(6, schema.rules.size());
        // 관계 룰 2개
        assertEquals(2, schema.relationRules.size());
    }

    @Test
    public void parsesMathReferenceSchemaFromClasspath() throws IOException {
        SchemaLoader loader = new SchemaLoader();
        SemanticSchema schema = loader.loadResource("semantic-schemas/math-reference.schema.json");
        assertEquals("math-reference-v1", schema.schemaId);
        assertFalse(schema.labels.isEmpty());
        assertFalse(schema.rules.isEmpty());
    }

    @Test
    public void mergesParentSchemaViaExtends() {
        SchemaLoader loader = new SchemaLoader();
        // 부모: 라벨 1개, 룰 1개 (priority 10)
        loader.loadFromJson("{"
                + "\"schemaId\":\"parent\","
                + "\"schemaName\":\"Parent\","
                + "\"version\":\"1.0.0\","
                + "\"labels\":[{\"id\":\"L1\",\"name\":\"L1\",\"category\":\"content\"}],"
                + "\"rules\":[{\"id\":\"R1\",\"label\":\"L1\",\"priority\":10,\"confidence\":0.5,\"conditions\":[]}],"
                + "\"relationRules\":[],\"layoutHints\":[]"
                + "}");
        // 자식: 자기 라벨 1개 + 룰 1개 (priority 5)
        loader.loadFromJson("{"
                + "\"schemaId\":\"child\","
                + "\"schemaName\":\"Child\","
                + "\"version\":\"1.0.0\","
                + "\"extends\":\"parent\","
                + "\"labels\":[{\"id\":\"L2\",\"name\":\"L2\",\"category\":\"content\"}],"
                + "\"rules\":[{\"id\":\"R2\",\"label\":\"L2\",\"priority\":5,\"confidence\":0.7,\"conditions\":[]}],"
                + "\"relationRules\":[],\"layoutHints\":[]"
                + "}");

        SemanticSchema resolved = loader.get("child");
        assertNotNull(resolved);
        assertEquals(2, resolved.labels.size());
        // 룰 머지 후 priority 오름차순 → R2(5), R1(10)
        assertEquals(2, resolved.rules.size());
        assertEquals("R2", resolved.rules.get(0).id);
        assertEquals("R1", resolved.rules.get(1).id);
    }

    @Test
    public void unknownSchemaReturnsNull() {
        SchemaLoader loader = new SchemaLoader();
        assertNull(loader.get("nonexistent"));
    }
}
