package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

/**
 * SPEC-013 shared: ASTParagraph 텍스트 추출 + 부분 분할 유틸.
 *
 * <p>{@code ResolvedToASTBuilder}의 {@code getParaPlainText / createSplitParagraph /
 * createContinuationParagraph}에서 stateless static helper로 발췌.</p>
 */
public final class ParagraphTextHelpers {

    private ParagraphTextHelpers() {}

    /**
     * Block sourceId 에서 ResolvedTextFrame lookup key 추출.
     *
     * <p>형식:
     * <ul>
     *   <li>{@code "u" + hex(domId)} → decimal domId (정상 케이스)</li>
     *   <li>{@code "u" + originalDomId + "_pi" + pageIdx} → 그대로 (SPEC-025 master instance clone; 별도 entry)</li>
     *   <li>{@code "u" + raw} → raw (NumberFormatException fallback)</li>
     * </ul>
     */
    public static String domIdFromSourceId(String sourceId) {
        if (sourceId == null) return null;
        String s = sourceId.startsWith("u") ? sourceId.substring(1) : sourceId;
        int us = s.indexOf('_');
        if (us >= 0) {
            // SPEC-025 master instance / off-canvas clone: "_pi"/"_oc" 접미사는 stripping 하지 않음 (별도 frame entry)
            if (us + 3 <= s.length()) {
                String suffix3 = s.substring(us, us + 3);
                if ("_pi".equals(suffix3) || "_oc".equals(suffix3)) {
                    return s; // 그대로 사용 ("originalDomId_pi<pageIdx>" or "originalDomId_oc<pageIdx>")
                }
            }
            s = s.substring(0, us);
        }
        try {
            return String.valueOf(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** decimal DOM ID 문자열을 IDML sourceId("u" + hex)로 변환. 파싱 실패 시 "u" + 원본 반환. */
    public static String domIdToSourceId(String decimalId) {
        if (decimalId == null) return null;
        try {
            return "u" + Integer.toHexString(Integer.parseInt(decimalId));
        } catch (NumberFormatException e) {
            return "u" + decimalId;
        }
    }

    /** ASTParagraph의 전체 plain text를 반환. */
    public static String getParaPlainText(ASTParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null) sb.append(t);
            } else if (item instanceof ASTInlineObject) {
                appendInlineObjectPlainText(sb, (ASTInlineObject) item);
            }
        }
        return sb.toString();
    }

    private static void appendInlineObjectPlainText(StringBuilder sb, ASTInlineObject obj) {
        if (obj == null || obj.paragraphs() == null || obj.paragraphs().isEmpty()) return;
        for (ASTParagraph childPara : obj.paragraphs()) {
            if (childPara == null) continue;
            sb.append(getParaPlainText(childPara));
        }
    }

    /**
     * 원본 단락에서 frameText에 해당하는 부분만 포함하는 분할 단락 생성.
     * 원본 단락의 스타일을 복제하고, 텍스트 런을 frameText 길이에 맞게 잘라냄.
     */
    public static ASTParagraph createSplitParagraph(ASTParagraph original, String frameText) {
        if (frameText == null || frameText.trim().isEmpty()) return null;

        ASTParagraph split = new ASTParagraph();
        // 단락 스타일 복제
        split.paragraphStyleRef(original.paragraphStyleRef());
        split.alignment(original.alignment());
        if (original.lineSpacing() != null) {
            split.lineSpacing(original.lineSpacing());
            split.lineSpacingType(original.lineSpacingType());
        }
        if (original.spaceBefore() != null) split.spaceBefore(original.spaceBefore());
        if (original.spaceAfter() != null) split.spaceAfter(original.spaceAfter());
        if (original.leftMargin() != null) split.leftMargin(original.leftMargin());
        if (original.firstLineIndent() != null) split.firstLineIndent(original.firstLineIndent());

        // 텍스트 런을 frameText 길이에 맞게 복제
        int remaining = frameText.length();
        for (ASTInlineItem item : original.items()) {
            if (remaining <= 0) break;
            if (item instanceof ASTTextRun) {
                ASTTextRun origRun = (ASTTextRun) item;
                String text = origRun.text();
                if (text == null) continue;
                if (text.length() <= remaining) {
                    split.addItem(origRun); // 전체 런 복제
                    remaining -= text.length();
                } else {
                    // 런 잘라내기
                    ASTTextRun trimmedRun = copyTextRun(origRun, text.substring(0, remaining));
                    split.addItem(trimmedRun);
                    remaining = 0;
                }
            } else {
                split.addItem(item); // 인라인 객체는 그대로
                remaining--; // U+FFFC 자리
            }
        }

        return split.items().isEmpty() ? null : split;
    }

    /**
     * 원본 단락에서 skipLen 이후의 텍스트만 포함하는 이어지기 단락 생성.
     */
    public static ASTParagraph createContinuationParagraph(ASTParagraph original, int skipLen, String expectedText) {
        ASTParagraph cont = new ASTParagraph();
        cont.paragraphStyleRef(original.paragraphStyleRef());
        cont.alignment(original.alignment());
        if (original.lineSpacing() != null) {
            cont.lineSpacing(original.lineSpacing());
            cont.lineSpacingType(original.lineSpacingType());
        }
        if (original.spaceBefore() != null) cont.spaceBefore(original.spaceBefore());
        if (original.spaceAfter() != null) cont.spaceAfter(original.spaceAfter());
        if (original.leftMargin() != null) cont.leftMargin(original.leftMargin());

        int skipped = 0;
        for (ASTInlineItem item : original.items()) {
            if (item instanceof ASTTextRun) {
                ASTTextRun origRun = (ASTTextRun) item;
                String text = origRun.text();
                if (text == null) continue;
                if (skipped + text.length() <= skipLen) {
                    skipped += text.length();
                    continue;
                }
                if (skipped < skipLen) {
                    // 런 중간에서 시작
                    int offset = skipLen - skipped;
                    ASTTextRun partialRun = copyTextRun(origRun, text.substring(offset));
                    cont.addItem(partialRun);
                    skipped = skipLen;
                } else {
                    cont.addItem(origRun);
                }
            } else {
                if (skipped >= skipLen) {
                    cont.addItem(item);
                } else {
                    skipped++;
                }
            }
        }

        return cont.items().isEmpty() ? null : cont;
    }

    private static ASTTextRun copyTextRun(ASTTextRun source, String text) {
        ASTTextRun copy = new ASTTextRun();
        copy.characterStyleRef(source.characterStyleRef());
        copy.text(text);
        copy.fontFamily(source.fontFamily());
        copy.fontStyle(source.fontStyle());
        copy.fontSizeHwpunits(source.fontSizeHwpunits());
        copy.textColor(source.textColor());
        copy.shadeColor(source.shadeColor());
        copy.letterSpacing(source.letterSpacing());
        copy.subscript(source.subscript());
        copy.superscript(source.superscript());
        copy.grepMathFont(source.grepMathFont());
        copy.underline(source.underline());
        copy.underlineColor(source.underlineColor());
        copy.underlineShape(source.underlineShape());
        copy.strikeThrough(source.strikeThrough());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.grepStyleApplied(source.grepStyleApplied());
        return copy;
    }
}
