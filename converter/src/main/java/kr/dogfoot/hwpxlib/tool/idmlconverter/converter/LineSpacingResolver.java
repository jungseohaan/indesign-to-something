package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineSpacingType;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit2;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * 단락 높이 추정 + 줄간격 자동 보정 (W4-2 Step A).
 * - estimateParagraphHeight: 인라인 객체 포함 단락의 추정 높이
 * - applyBetweenLinesSpacing: 혼합 폰트/분수 수식 단락의 줄간격 자동 확장
 * - ensureLineSpacingForInline: 특정 인라인 높이에 맞춘 줄간격 보장
 *
 * HwpxParagraphBuilder에서 분리됨.
 */
final class LineSpacingResolver {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;

    LineSpacingResolver(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
    }

    /**
     * AST 단락의 높이를 추정한다.
     * 인라인 객체 중 가장 큰 높이를 사용하고, 텍스트만 있으면 기본 행높이(500 hwpunit ≈ 5pt).
     */
    long estimateParagraphHeight(ASTParagraph para) {
        long maxInlineH = 0;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (!InlineFlowPolicy.participatesInLineSpacing(obj)) continue;
                long h = obj.height();
                // IMAGE with container: 컨테이너 높이 사용
                if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE
                        && obj.containerHeight() > 0) {
                    h = obj.containerHeight();
                }
                if (h > maxInlineH) maxInlineH = h;
            }
        }
        return maxInlineH > 0 ? maxInlineH : 500;
    }

    /**
     * 단락 내 텍스트 런의 폰트 크기가 본문 대비 1.5배 이상 차이나는지 판별.
     */
    boolean hasMixedFontSizeRuns(ASTParagraph astPara) {
        int maxFs = 0, bodyFs = 0, bodyMaxLen = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun tr =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item;
                Integer fs = tr.fontSizeHwpunits();
                if (fs == null || fs <= 0) continue;
                if (fs > maxFs) maxFs = fs;
                String text = tr.text();
                int len = (text != null) ? text.trim().length() : 0;
                if (len > bodyMaxLen) { bodyMaxLen = len; bodyFs = fs; }
            }
        }
        return bodyFs > 0 && maxFs > bodyFs * 1.5;
    }

    boolean hasSourceFixedLeading(ASTParagraph astPara, String paraPrId) {
        if (astPara != null
                && astPara.lineSpacing() != null
                && astPara.lineSpacing() > 0
                && "fixed".equals(astPara.lineSpacingType())) {
            return true;
        }
        ParaPr basePr = paragraphBuilder.findParaPrById(paraPrId);
        return basePr != null
                && basePr.lineSpacing() != null
                && basePr.lineSpacing().type() == LineSpacingType.FIXED
                && basePr.lineSpacing().value() != null
                && basePr.lineSpacing().value() > 0;
    }

    long maxInlineObjectHeight(ASTParagraph astPara) {
        long max = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (!InlineFlowPolicy.participatesInLineSpacing(obj)) continue;
                if (obj.height() > max) {
                    max = obj.height();
                }
            }
        }
        return max;
    }

    /**
     * 본문 문단에 섞인 인라인 객체가 아니라, 앵커/인라인 shell 자체를 한 줄로
     * 운반하기 위한 carrier 문단인지 판별한다. 이런 문단은 인라인 객체 높이가
     * 이미 행 높이에 참여하므로 BETWEEN_LINES 자동 여백을 덧대면 원본보다
     * shell 앞뒤 간격이 과하게 벌어진다.
     */
    boolean isInlineObjectOnlyCarrier(ASTParagraph astPara) {
        boolean hasInlineObject = false;
        for (ASTInlineItem item : astPara.items()) {
            if (item == null) continue;
            switch (item.itemType()) {
                case INLINE_OBJECT:
                    hasInlineObject = true;
                    break;
                case TEXT_RUN:
                    ASTTextRun tr = (ASTTextRun) item;
                    if (hasMeaningfulText(tr.text())) {
                        return false;
                    }
                    break;
                default:
                    return false;
            }
        }
        return hasInlineObject;
    }

    private static boolean hasMeaningfulText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isLayoutOnlyCodePoint(cp)) continue;
            return true;
        }
        return false;
    }

    private static boolean isLayoutOnlyCodePoint(int cp) {
        return Character.isWhitespace(cp)
                || Character.isSpaceChar(cp)
                || cp == 0xFFFC  // object replacement character
                || cp == 0x200B  // zero-width space
                || cp == 0x200C
                || cp == 0x200D
                || cp == 0xFEFF;
    }

    long maxFractionEquationHeight(ASTParagraph astPara) {
        long max = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.EQUATION) {
                ASTEquation eq = (ASTEquation) item;
                String script = eq.hwpScript();
                if (script != null && script.contains(" over ")) {
                    long estH = (long) (1100 * HwpxParagraphBuilder.FRACTION_HEIGHT_MULTIPLIER);
                    if (estH > max) max = estH;
                }
            }
        }
        return max;
    }

    /**
     * 인라인 객체가 있는 단락의 줄간격을 BETWEEN_LINES(여백만)으로 전환.
     * FIXED leading에서 fontSize를 빼서 줄 사이 여백만 지정하면
     * 인라인 객체 높이에 따라 줄이 자연스럽게 확장됨.
     */
    String applyBetweenLinesSpacing(String paraPrId, ASTParagraph astPara) {
        ParaPr basePr = paragraphBuilder.findParaPrById(paraPrId);
        if (basePr == null) return paraPrId;

        // 인라인 객체 최대 높이
        long maxObjH = maxInlineObjectHeight(astPara);
        // 대표 폰트 크기 결정
        int bodyFs = 0;
        int bodyMaxLen = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun tr =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item;
                Integer fs = tr.fontSizeHwpunits();
                String text = tr.text();
                int len = (text != null) ? text.trim().length() : 0;
                if (fs != null && fs > 0 && len > bodyMaxLen) { bodyMaxLen = len; bodyFs = fs; }
            }
        }
        if (bodyFs <= 0) bodyFs = 1000; // 기본 10pt
        // 여백 계산: 인라인 객체 기반 여백과 원본 leading 기반 여백 중 큰 값
        int inlineBetween = (int) Math.max((maxObjH - bodyFs) / 2, bodyFs / 3);
        // 원본 FIXED leading이 있으면 leading - bodyFs를 여백으로
        int leadingBetween = 0;
        if (basePr.lineSpacing() != null && basePr.lineSpacing().type() == LineSpacingType.FIXED) {
            leadingBetween = Math.max(basePr.lineSpacing().value() - bodyFs, 0);
        }
        if (astPara.lineSpacing() != null && "fixed".equals(astPara.lineSpacingType())) {
            leadingBetween = Math.max(astPara.lineSpacing() - bodyFs, leadingBetween);
        }
        int betweenValue = Math.max(inlineBetween, leadingBetween);

        String newId = ctx.styleRegistry.nextParaPrId();
        ParaPr newPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        newPr.copyFrom(basePr);
        newPr.id(newId);
        if (newPr.lineSpacing() == null) {
            newPr.createLineSpacing();
        }
        newPr.lineSpacing().typeAnd(LineSpacingType.BETWEEN_LINES).valueAnd(betweenValue).unit(ValueUnit2.HWPUNIT);
        return newId;
    }

    String ensureLineSpacingForInline(String paraPrId, long inlineHeight) {
        ParaPr basePr = paragraphBuilder.findParaPrById(paraPrId);
        if (basePr == null) return paraPrId;

        boolean needsExpand = false;
        if (basePr.lineSpacing() == null) {
            // lineSpacing 미지정 → 기본값(PERCENT 160 등)은 큰 인라인 객체를 수용 못함
            needsExpand = true;
        } else if (basePr.lineSpacing().type() == LineSpacingType.FIXED
                && basePr.lineSpacing().value() < (int) inlineHeight) {
            // FIXED 줄 간격이 인라인 객체보다 작으면 확장
            needsExpand = true;
        } else if (basePr.lineSpacing().type() == LineSpacingType.PERCENT) {
            // PERCENT 모드: 인라인 객체가 크면 FIXED로 전환
            // PERCENT 값은 글자 크기 기준이므로, 인라인 높이와 직접 비교 불가
            // → 인라인 높이가 충분히 크면 항상 FIXED로 전환
            needsExpand = true;
        }

        if (needsExpand) {
            String newId = ctx.styleRegistry.nextParaPrId();
            ParaPr newPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();
            newPr.copyFrom(basePr);
            newPr.id(newId);
            if (newPr.lineSpacing() == null) {
                newPr.createLineSpacing();
            }
            newPr.lineSpacing().typeAnd(LineSpacingType.FIXED).valueAnd((int) inlineHeight);
            return newId;
        }
        return paraPrId;
    }
}
