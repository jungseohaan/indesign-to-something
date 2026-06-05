package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HwpxTextBoxBuilder의 단락/열 분배 헬퍼 (W4 Step A).
 * - 단락 높이 추정 + 수직 공간 균등 분배
 * - 다단(컬럼) 너비 계산 + 단락 분배
 */
final class TextBoxLayoutHelpers {

    private TextBoxLayoutHelpers() {}

    /**
     * JustifyAlign 시뮬레이션: 프레임 높이에 맞게 문단 간 간격을 균등 분배.
     * HWPX에는 수직 균등 배분(vertAlign=JUSTIFY)이 없으므로,
     * 남는 수직 공간을 문단 사이의 spaceAfter로 삽입한다.
     */
    static void distributeVerticalSpace(kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock block) {
        java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph> paras = block.paragraphs();
        int paraCount = paras.size();
        if (paraCount < 2) return;

        // 프레임 내부 가용 높이 (hwpunits)
        long availableHeight = block.height() - block.insetTop() - block.insetBottom();
        if (availableHeight <= 0) return;

        // 각 문단의 텍스트 높이 추정 (lineSpacing 기반)
        long totalTextHeight = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para : paras) {
            long paraHeight = estimateParagraphHeight(para);
            totalTextHeight += paraHeight;
            // spaceBefore/After도 기존 간격으로 포함
            if (para.spaceBefore() != null) totalTextHeight += para.spaceBefore();
            if (para.spaceAfter() != null) totalTextHeight += para.spaceAfter();
        }

        long extraSpace = availableHeight - totalTextHeight;
        if (extraSpace <= 0) return;

        // 문단 사이 간격에 균등 분배 (마지막 문단 제외)
        int gaps = paraCount - 1;
        long spacePerGap = extraSpace / gaps;

        for (int i = 0; i < paraCount - 1; i++) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para = paras.get(i);
            long existing = para.spaceAfter() != null ? para.spaceAfter() : 0;
            para.spaceAfter(existing + spacePerGap);
        }
    }

    /**
     * 문단의 텍스트 높이를 추정한다 (hwpunits).
     * fixed lineSpacing이면 그 값, percent이면 폰트크기 × 비율.
     */
    static long estimateParagraphHeight(kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para) {
        // 한 줄 기준 높이 추정
        if ("fixed".equals(para.lineSpacingType()) && para.lineSpacing() > 0) {
            return para.lineSpacing();
        }
        // 폰트 크기 기반 추정
        int fontSize = 1100; // 기본 11pt
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem item : para.items()) {
            if (item.itemType() == kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem.ItemType.TEXT_RUN) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun run =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item;
                if (run.fontSizeHwpunits() != null) {
                    fontSize = run.fontSizeHwpunits();
                    break;
                }
            }
        }
        if ("percent".equals(para.lineSpacingType()) && para.lineSpacing() > 0) {
            return (long) (fontSize * para.lineSpacing() / 100.0);
        }
        // 기본: 160%
        return (long) (fontSize * 1.6);
    }

    static long[] computeColumnWidths(ASTTextFrameBlock block, int colCount) {
        long totalWidth = block.effectiveWidth();
        long gutter = block.columnGutter();
        long totalGutter = gutter * (colCount - 1);
        long contentWidth = totalWidth - totalGutter;
        long baseWidth = contentWidth / colCount;
        long[] result = new long[colCount];
        java.util.Arrays.fill(result, baseWidth);
        result[colCount - 1] = contentWidth - baseWidth * (colCount - 1); // 나머지 보정
        return result;
    }

    /**
     * 단락들을 N개 컬럼에 문자 수 기반으로 균등 분배한다.
     * 그리디 방식: 왼쪽 컬럼부터 채우고, 할당량 초과 시 다음 컬럼으로.
     */
    static java.util.List<java.util.List<ASTParagraph>> distributeParagraphs(
            java.util.List<ASTParagraph> paragraphs, int colCount) {
        java.util.List<java.util.List<ASTParagraph>> result = new java.util.ArrayList<>();
        for (int i = 0; i < colCount; i++) result.add(new java.util.ArrayList<>());

        if (paragraphs.isEmpty() || colCount <= 1) {
            result.get(0).addAll(paragraphs);
            return result;
        }

        // columnBreakAfter가 있으면 명시적 컬럼 분배
        boolean hasExplicitBreak = false;
        for (ASTParagraph p : paragraphs) {
            if (p.columnBreakAfter()) { hasExplicitBreak = true; break; }
        }

        if (hasExplicitBreak) {
            int currentCol = 0;
            for (ASTParagraph p : paragraphs) {
                if (currentCol >= colCount) currentCol = colCount - 1;
                result.get(currentCol).add(p);
                if (p.columnBreakAfter() && currentCol < colCount - 1) {
                    currentCol++;
                }
            }
            return result;
        }

        // fallback: 문자 수 기반 균등 분배
        int totalChars = 0;
        int[] paraChars = new int[paragraphs.size()];
        for (int i = 0; i < paragraphs.size(); i++) {
            paraChars[i] = countParagraphChars(paragraphs.get(i));
            totalChars += paraChars[i];
        }

        int charsPerCol = Math.max(totalChars / colCount, 1);
        int currentCol = 0;
        int currentChars = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            result.get(currentCol).add(paragraphs.get(i));
            currentChars += paraChars[i];

            if (currentChars >= charsPerCol && currentCol < colCount - 1) {
                currentCol++;
                currentChars = 0;
            }
        }

        return result;
    }

    /**
     * 단락 내 텍스트 런의 문자 수 합계.
     */
    static int countParagraphChars(ASTParagraph para) {
        int count = 0;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null) count += text.length();
            }
        }
        return Math.max(count, 1); // 빈 단락도 최소 1로 카운트
    }
}
