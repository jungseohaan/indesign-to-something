package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * Flat -> AST 역변환기.
 * 3개 레이어의 평탄한 FlatDocument를 재귀적 ASTDocument 트리로 복원한다.
 * ASTToFlatConverter의 역방향으로, 라운드 트립 테스트를 통해
 * 평탄 모델이 완전한 표현임을 증명한다.
 *
 * per-node 변환은 FlatNodeAdapter에 위임한다.
 */
public class FlatToASTConverter {

    private final FlatDocumentGateway gateway;
    private final FlatNodeAdapter adapter;

    private FlatToASTConverter(FlatDocument flatDoc) {
        this.gateway = new FlatDocumentGateway(flatDoc);
        this.adapter = new FlatNodeAdapter(gateway);
    }

    /**
     * FlatDocument를 ASTDocument로 변환한다.
     *
     * @param flatDoc 변환할 평탄 문서
     * @return 재귀적 AST 문서
     */
    public static ASTDocument convert(FlatDocument flatDoc) {
        FlatToASTConverter converter = new FlatToASTConverter(flatDoc);
        return converter.doConvert(flatDoc);
    }

    private ASTDocument doConvert(FlatDocument flatDoc) {
        ASTDocument astDoc = new ASTDocument();

        // 1. Copy metadata (shared references)
        copyMetadata(flatDoc, astDoc);

        // 2. Convert each page -> ASTSection
        for (FlatPage page : flatDoc.pages()) {
            ASTSection section = convertPage(page);
            astDoc.addSection(section);
        }

        return astDoc;
    }

    // =========================================================================
    // Metadata copy
    // =========================================================================

    private void copyMetadata(FlatDocument flatDoc, ASTDocument astDoc) {
        astDoc.sourceFile(flatDoc.sourceFile());
        astDoc.sourceFormat(flatDoc.sourceFormat());

        // Fonts
        for (ASTFontDef f : flatDoc.fonts()) {
            astDoc.addFont(f);
        }

        // Paragraph styles
        for (ASTStyleDef s : flatDoc.paragraphStyles()) {
            astDoc.addParagraphStyle(s);
        }

        // Character styles
        for (ASTStyleDef s : flatDoc.characterStyles()) {
            astDoc.addCharacterStyle(s);
        }

        // Colors
        for (Map.Entry<String, String> e : flatDoc.colors().entrySet()) {
            astDoc.putColor(e.getKey(), e.getValue());
        }

        // Stories
        for (ASTStory s : flatDoc.stories()) {
            astDoc.addStory(s);
        }

        // Backgrounds
        for (ASTPageBackground bg : flatDoc.backgrounds()) {
            astDoc.addBackground(bg);
        }
    }

    // =========================================================================
    // Page -> ASTSection + ASTPageLayout
    // =========================================================================

    private ASTSection convertPage(FlatPage page) {
        ASTSection section = new ASTSection();
        section.pageNumber(page.pageNumber());

        // Page layout
        ASTPageLayout layout = new ASTPageLayout();
        layout.pageWidth(page.pageWidth());
        layout.pageHeight(page.pageHeight());
        layout.marginTop(page.marginTop());
        layout.marginBottom(page.marginBottom());
        layout.marginLeft(page.marginLeft());
        layout.marginRight(page.marginRight());
        layout.columnCount(page.columnCount());
        layout.columnGutter(page.columnGutter());
        section.layout(layout);

        // Collect ABSOLUTE-positioned node IDs in reading order (layoutNodeIds)
        // Note: zOrderedNodeIds is z-order sorted for rendering, but the original AST
        // uses reading order (layoutNodeIds). We must use reading order for round-trip fidelity.
        for (String nodeId : page.layoutNodeIds()) {
            FlatLayoutNode node = gateway.layoutNode(nodeId);
            if (node != null && node.positioning() == FlatLayoutNode.PositioningMode.ABSOLUTE) {
                ASTBlock block = convertAbsoluteNode(node);
                if (block != null) {
                    section.addBlock(block);
                }
            }
        }

        return section;
    }

    // =========================================================================
    // ABSOLUTE node -> ASTBlock (delegates to FlatNodeAdapter)
    // =========================================================================

    private ASTBlock convertAbsoluteNode(FlatLayoutNode node) {
        switch (node.nodeType()) {
            case TEXT_FRAME:
                return adapter.toTextFrameBlock(node);
            case FIGURE:
                return adapter.toFigure(node);
            case TABLE:
                return adapter.toTable(node);
            case SPACER:
                // Spacers are inline-only, skip
                return null;
            default:
                return null;
        }
    }
}
