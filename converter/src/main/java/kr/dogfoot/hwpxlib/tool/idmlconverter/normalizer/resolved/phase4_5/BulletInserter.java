package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_5;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import java.util.List;

/**
 * SPEC-013 Phase 4.5: BulletList 스타일 자동 불릿 삽입.
 *
 * <p>InDesign의 자동 불릿은 텍스트에 포함되지 않으므로 변환 시 명시적으로 추가한다.
 * 단락 스타일 이름에 \u2022 가 포함되면 그 단락의 첫 위치에 원본 불릿 런을 삽입.
 * TextFrame block + 표 셀 단락 모두 처리.</p>
 */
public final class BulletInserter {

    private BulletInserter() {}

    public static void run(ResolvedBuildContext ctx, List<ASTSection> sections) {
        int[] count = new int[]{0};
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                processBlock(blk, count);
            }
        }
        if (count[0] > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.5: " + count[0] + " bullets inserted");
        }
    }

    private static void processBlock(ASTBlock blk, int[] count) {
        if (blk instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
            if (tfb.paragraphs() != null) {
                for (ASTParagraph para : tfb.paragraphs()) {
                    insertBulletIfNeeded(para, count);
                }
            }
        } else if (blk instanceof ASTTable) {
            ASTTable tbl = (ASTTable) blk;
            if (tbl.rows() != null) {
                for (ASTTableRow row : tbl.rows()) {
                    if (row.cells() == null) continue;
                    for (ASTTableCell cell : row.cells()) {
                        if (cell.paragraphs() == null) continue;
                        for (ASTParagraph para : cell.paragraphs()) {
                            insertBulletIfNeeded(para, count);
                        }
                    }
                }
            }
        }
    }

    private static void insertBulletIfNeeded(ASTParagraph para, int[] count) {
        String styleRef = para.paragraphStyleRef();
        if (styleRef == null || !styleRef.contains("\u2022")) return; // \u2022 = bullet
        List<ASTInlineItem> items = para.items();
        if (items != null && !items.isEmpty()) {
            ASTInlineItem first = items.get(0);
            if (first.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String firstText = ((ASTTextRun) first).text();
                if (firstText != null && (firstText.startsWith("\u2022") || firstText.startsWith("\u00B7"))) {
                    return;
                }
            }
        }
        ASTTextRun bulletRun = new ASTTextRun();
        bulletRun.text("\u2022 ");
        ASTTextRun bodyRun = null;
        int bodyMaxLen = 0;
        if (items != null) {
            for (ASTInlineItem it : items) {
                if (it.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    ASTTextRun tr = (ASTTextRun) it;
                    int len = (tr.text() != null) ? tr.text().trim().length() : 0;
                    if (len > bodyMaxLen) { bodyMaxLen = len; bodyRun = tr; }
                }
            }
        }
        if (bodyRun != null) {
            bulletRun.fontFamily(bodyRun.fontFamily());
            Integer bodyFs = bodyRun.fontSizeHwpunits();
            if (bodyFs == null || bodyFs <= 0) bodyFs = 1100;
            bulletRun.fontSizeHwpunits(bodyFs);
            bulletRun.fontStyle(bodyRun.fontStyle());
        }
        para.items().add(0, bulletRun);
        count[0]++;
    }
}
