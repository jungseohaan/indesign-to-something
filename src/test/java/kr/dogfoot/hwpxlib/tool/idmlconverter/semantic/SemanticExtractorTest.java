package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageLayout;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SchemaLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SemanticLayerWriter;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * SPEC-018 M2: SemanticExtractor 통합 테스트.
 *
 * <p>합성된 ASTDocument 에 common 스키마를 적용해 분류 결과를 검증.</p>
 */
public class SemanticExtractorTest {

    /**
     * A4 페이지 1개 + 페이지 상단 짧은 텍스트 프레임 1개 + FIGURE 1개 + 본문 프레임 1개.
     */
    private static ASTDocument buildSimpleDoc() {
        ASTDocument doc = new ASTDocument();
        doc.sourceFile("test.idml");

        ASTSection section = new ASTSection();
        section.pageNumber(1);
        ASTPageLayout layout = new ASTPageLayout();
        layout.pageWidth(1512000);   // ~A4 width in HWPUNIT
        layout.pageHeight(2139120);
        layout.marginTop(216000);
        layout.marginBottom(216000);
        layout.marginLeft(216000);
        layout.marginRight(216000);
        layout.columnCount(1);
        section.layout(layout);

        // 1. 페이지 헤더 (TOP region 짧은 텍스트)
        ASTTextFrameBlock header = new ASTTextFrameBlock();
        header.sourceId("uHeader");
        header.x(216000);
        header.y(216000);             // TOP 영역 (relY ~= 0)
        header.width(900000);
        header.height(60000);
        header.zOrder(1);
        ASTParagraph hp = new ASTParagraph();
        ASTTextRun hr = new ASTTextRun();
        hr.text("머리말");
        hr.fontSizeHwpunits(900);
        hp.items().add(hr);
        header.paragraphs().add(hp);
        section.addBlock(header);

        // 2. FIGURE
        ASTFigure fig = new ASTFigure();
        fig.sourceId("uFig");
        fig.x(300000);
        fig.y(800000);
        fig.width(800000);
        fig.height(600000);
        fig.zOrder(2);
        section.addBlock(fig);

        // 3. 본문 (MIDDLE region, 긴 텍스트)
        ASTTextFrameBlock body = new ASTTextFrameBlock();
        body.sourceId("uBody");
        body.x(216000);
        body.y(1500000);
        body.width(1080000);
        body.height(400000);
        body.zOrder(3);
        ASTParagraph bp = new ASTParagraph();
        ASTTextRun br = new ASTTextRun();
        br.text("이것은 본문 텍스트입니다. 충분히 길어서 헤더로 분류되지 않아야 합니다. 본문은 특정 룰에 매칭되지 않으면 UNKNOWN.");
        br.fontSizeHwpunits(900);
        bp.items().add(br);
        body.paragraphs().add(bp);
        section.addBlock(body);

        doc.addSection(section);
        return doc;
    }

    @Test
    public void extractsBlocksFromSimpleDoc() {
        ASTDocument doc = buildSimpleDoc();
        SemanticLayer layer = SemanticExtractor.extract(doc, new SemanticSchema());
        assertEquals(3, layer.nodes.size());
        // ID 형식 확인
        assertTrue(layer.nodes.get(0).id.startsWith("sn-"));
        // FIGURE 노드 타입
        SemanticNode figNode = findById(layer, "sn-uFig");
        assertNotNull(figNode);
        assertEquals(SemanticTypes.NodeType.FIGURE, figNode.nodeType);
    }

    @Test
    public void appliesCommonSchemaRules() throws IOException {
        ASTDocument doc = buildSimpleDoc();
        SchemaLoader loader = new SchemaLoader();
        SemanticSchema schema = loader.loadResource("semantic-schemas/common.schema.json");
        SemanticLayer layer = SemanticExtractor.extract(doc, schema);

        // FIGURE 룰 매칭 확인
        SemanticNode figNode = findById(layer, "sn-uFig");
        assertNotNull(figNode);
        assertEquals("FIGURE", figNode.label);
        assertEquals("rule-figure", figNode.appliedRule);

        // 헤더가 PAGE_HEADER 로 분류되는지 (TOP + textLength<50)
        SemanticNode headerNode = findById(layer, "sn-uHeader");
        assertNotNull(headerNode);
        assertEquals("PAGE_HEADER", headerNode.label);
    }

    @Test
    public void layerSerializesToJson() throws IOException {
        ASTDocument doc = buildSimpleDoc();
        SchemaLoader loader = new SchemaLoader();
        SemanticSchema schema = loader.loadResource("semantic-schemas/common.schema.json");
        SemanticLayer layer = SemanticExtractor.extract(doc, schema);

        String json = SemanticLayerWriter.toJson(layer);
        assertNotNull(json);
        assertTrue(json.contains("\"version\""));
        assertTrue(json.contains("\"nodes\""));
        assertTrue(json.contains("sn-uFig"));
        assertTrue(json.contains("\"FIGURE\""));
    }

    private static SemanticNode findById(SemanticLayer layer, String id) {
        for (SemanticNode n : layer.nodes) {
            if (id.equals(n.id)) return n;
        }
        return null;
    }
}
