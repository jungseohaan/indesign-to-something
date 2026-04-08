package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_5;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import java.util.List;

/**
 * SPEC-013 Phase 4.5: BulletList 스타일 자동 불릿 삽입.
 *
 * <p>InDesign의 자동 불릿은 텍스트에 포함되지 않으므로 변환 시 명시적으로 추가한다.
 * 단락 스타일 이름에 {@code •} 가 포함되면 그 단락의 첫 위치에 가운뎃점({@code ·}) 런을 삽입.</p>
 *
 * <p>{@code ResolvedToASTBuilder.insertBulletsForBulletStyles}에서 stateless static helper로 발췌.
 * 동작은 동일.</p>
 */
public final class BulletInserter {

    private BulletInserter() {}

    public static void run(ResolvedBuildContext ctx, List<ASTSection> sections) {
        int count = 0;
        for (ASTSection section : sections) {
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                if (tfb.paragraphs() == null) continue;
                for (ASTParagraph para : tfb.paragraphs()) {
                    String styleRef = para.paragraphStyleRef();
                    if (styleRef == null || !styleRef.contains("\u2022")) continue; // • = \u2022
                    // 이미 불릿으로 시작하면 건너뜀
                    List<ASTInlineItem> items = para.items();
                    if (items != null && !items.isEmpty()) {
                        ASTInlineItem first = items.get(0);
                        if (first.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                            String firstText = ((ASTTextRun) first).text();
                            if (firstText != null && (firstText.startsWith("\u2022") || firstText.startsWith("\u00B7")
                                    || firstText.startsWith("•") || firstText.startsWith("·"))) {
                                continue; // 이미 불릿 있음
                            }
                        }
                    }
                    // 불릿 런 삽입 (가장 긴 텍스트 런의 폰트/크기 상속)
                    ASTTextRun bulletRun = new ASTTextRun();
                    bulletRun.text("\u00B7 "); // middle dot + space
                    // 대표 런 결정: 가장 긴 텍스트 런
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
                        // fontSizeHwpunits가 null이면 스타일에서 가져옴
                        Integer bodyFs = bodyRun.fontSizeHwpunits();
                        if (bodyFs == null || bodyFs <= 0) {
                            // resolved에서 fontSize 확인
                            bodyFs = 1100; // 기본 11pt
                        }
                        bulletRun.fontSizeHwpunits(bodyFs);
                        bulletRun.fontStyle(bodyRun.fontStyle());
                    }
                    para.items().add(0, bulletRun);
                    count++;
                }
            }
        }
        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.5: " + count + " bullets inserted");
        }
    }
}
