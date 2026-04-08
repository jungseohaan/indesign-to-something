package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

/**
 * SPEC-013 shared: ASTParagraph 텍스트 추출 + 부분 분할 유틸.
 *
 * <p>{@code ResolvedToASTBuilder}의 {@code getParaPlainText / createSplitParagraph /
 * createContinuationParagraph}에서 stateless static helper로 발췌.
 * 동작은 동일하며 builder와 phase5/WrapPhase5가 공유한다.</p>
 */
public final class ParagraphTextHelpers {

    private ParagraphTextHelpers() {}

    /** ASTParagraph의 전체 plain text를 반환. */
    public static String getParaPlainText(ASTParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
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
                    ASTTextRun trimmedRun = new ASTTextRun();
                    trimmedRun.text(text.substring(0, remaining));
                    trimmedRun.fontFamily(origRun.fontFamily());
                    trimmedRun.fontStyle(origRun.fontStyle());
                    trimmedRun.fontSizeHwpunits(origRun.fontSizeHwpunits());
                    trimmedRun.textColor(origRun.textColor());
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
                    ASTTextRun partialRun = new ASTTextRun();
                    partialRun.text(text.substring(offset));
                    partialRun.fontFamily(origRun.fontFamily());
                    partialRun.fontStyle(origRun.fontStyle());
                    partialRun.fontSizeHwpunits(origRun.fontSizeHwpunits());
                    partialRun.textColor(origRun.textColor());
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
}
