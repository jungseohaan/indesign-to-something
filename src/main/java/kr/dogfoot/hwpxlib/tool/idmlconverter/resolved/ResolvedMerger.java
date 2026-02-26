package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * resolved.json 데이터를 AST에 병합하는 후처리기.
 * AST 빌드 후 호출하여, InDesign DOM이 계산한 최종값으로 AST를 보강한다.
 *
 * 매칭 전략:
 * - 스토리: storyId 직접 비교
 * - 문단: 인덱스 기반 (styleName sanity check)
 * - 런: 문자 오프셋 기반 정렬 (런 경계 차이 허용)
 */
public class ResolvedMerger {

    /**
     * AST 문서를 resolved 데이터로 보강한다.
     * resolved 값이 있는 속성만 덮어씌우고, 없는 속성은 기존 AST 값 유지.
     */
    public static void enrich(ASTDocument astDoc, ResolvedData resolved) {
        // 스토리별 매칭
        Map<String, List<ASTParagraph>> parasByStory = collectParagraphsByStory(astDoc);

        for (Map.Entry<String, List<ASTParagraph>> entry : parasByStory.entrySet()) {
            String storyId = entry.getKey();
            ResolvedStory resolvedStory = resolved.getStory(storyId);
            if (resolvedStory == null) continue;

            List<ASTParagraph> astParas = entry.getValue();
            List<ResolvedParagraph> resolvedParas = resolvedStory.paragraphs();

            int count = Math.min(astParas.size(), resolvedParas.size());
            for (int i = 0; i < count; i++) {
                enrichParagraph(astParas.get(i), resolvedParas.get(i), resolved);
            }
        }
    }

    /**
     * ASTDocument의 모든 섹션에서 TextFrameBlock을 찾아
     * storyId 별로 문단 리스트를 수집한다.
     * 동일 storyId의 여러 프레임(연결 글상자)은 문단이 합쳐진다.
     */
    private static Map<String, List<ASTParagraph>> collectParagraphsByStory(ASTDocument astDoc) {
        Map<String, List<ASTParagraph>> result = new LinkedHashMap<>();
        for (ASTSection section : astDoc.sections()) {
            collectFromBlocks(section.blocks(), result);
        }
        return result;
    }

    private static void collectFromBlocks(List<ASTBlock> blocks,
                                           Map<String, List<ASTParagraph>> result) {
        for (ASTBlock block : blocks) {
            if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
                String sid = tfb.storyId();
                if (sid != null) {
                    result.computeIfAbsent(sid, k -> new ArrayList<>())
                            .addAll(tfb.paragraphs());
                }
            }
        }
    }

    // ─── 문단 보강 ────────────────────────────────────────

    private static void enrichParagraph(ASTParagraph astPara,
                                         ResolvedParagraph resPara,
                                         ResolvedData resolved) {
        // 문단 속성 보강
        enrichParagraphProperties(astPara, resPara);

        // 런 매칭 및 보강
        enrichRuns(astPara, resPara, resolved);
    }

    private static void enrichParagraphProperties(ASTParagraph astPara,
                                                    ResolvedParagraph resPara) {
        // leading
        Double fixedLeading = resPara.fixedLeading();
        if (fixedLeading != null) {
            astPara.lineSpacingType("fixed");
            astPara.lineSpacing((int) (fixedLeading * 100)); // pts → hwpunits
        } else if (resPara.isAutoLeading() && resPara.autoLeading() != null) {
            astPara.lineSpacingType("percent");
            astPara.lineSpacing((int) Math.round(resPara.autoLeading()));
        }

        // spaceBefore / spaceAfter
        if (resPara.spaceBefore() != null) {
            astPara.spaceBefore((long) (resPara.spaceBefore() * 100));
        }
        if (resPara.spaceAfter() != null) {
            astPara.spaceAfter((long) (resPara.spaceAfter() * 100));
        }

        // firstLineIndent / leftIndent
        if (resPara.firstLineIndent() != null) {
            astPara.firstLineIndent((long) (resPara.firstLineIndent() * 100));
        }
        if (resPara.leftIndent() != null) {
            astPara.leftMargin((long) (resPara.leftIndent() * 100));
        }

        // justification → alignment
        String justification = resPara.justification();
        if (justification != null) {
            String alignment = mapJustification(justification);
            if (alignment != null) {
                astPara.alignment(alignment);
            }
        }
    }

    /**
     * InDesign Justification enum → AST alignment 매핑.
     */
    private static String mapJustification(String justification) {
        if (justification == null) return null;
        // InDesign enum 값: "Justification.LEFT_ALIGN", "Justification.CENTER_ALIGN" 등
        String upper = justification.toUpperCase();
        if (upper.contains("LEFT")) return "left";
        if (upper.contains("CENTER")) return "center";
        if (upper.contains("RIGHT")) return "right";
        if (upper.contains("FULLY_JUSTIFIED") || upper.contains("FULL_JUSTIFY")) return "justify";
        if (upper.contains("JUSTIFY") || upper.contains("LEFT_JUSTIFIED")) return "justify";
        return null;
    }

    // ─── 런 보강 ──────────────────────────────────────────

    /**
     * 문자 오프셋 기반 런 매칭.
     * AST와 resolved의 런 경계가 다를 수 있으므로,
     * 전체 텍스트를 오프셋 기반으로 정렬하여 매칭한다.
     */
    private static void enrichRuns(ASTParagraph astPara,
                                    ResolvedParagraph resPara,
                                    ResolvedData resolved) {
        List<ResolvedRun> resolvedRuns = resPara.runs();
        if (resolvedRuns.isEmpty()) return;

        // resolved 런의 오프셋 인덱스 구축
        int[] resRunStarts = new int[resolvedRuns.size()];
        int offset = 0;
        for (int i = 0; i < resolvedRuns.size(); i++) {
            resRunStarts[i] = offset;
            String text = resolvedRuns.get(i).text();
            offset += (text != null ? text.length() : 0);
        }
        int resolvedTotalLen = offset;

        // AST 텍스트런 순회 — 오프셋 커서로 resolved 런 찾기
        int astCursor = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) continue;

            ASTTextRun astRun = (ASTTextRun) item;
            String astText = astRun.text();
            if (astText == null || astText.isEmpty()) continue;

            // AST 런의 중간 지점에 해당하는 resolved 런 찾기
            int midPoint = astCursor + astText.length() / 2;
            if (midPoint >= resolvedTotalLen) {
                // resolved 범위 초과 — 마지막 런 사용
                midPoint = resolvedTotalLen - 1;
            }
            if (midPoint < 0) midPoint = 0;

            int resRunIdx = findRunAtOffset(resRunStarts, midPoint, resolvedRuns.size());
            if (resRunIdx >= 0 && resRunIdx < resolvedRuns.size()) {
                applyRunOverrides(astRun, resolvedRuns.get(resRunIdx), resolved);
            }

            astCursor += astText.length();
        }
    }

    /**
     * 오프셋 위치에 해당하는 resolved 런 인덱스를 이진 탐색으로 찾는다.
     */
    private static int findRunAtOffset(int[] starts, int offset, int count) {
        int lo = 0, hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return hi; // starts[hi] <= offset < starts[hi+1]
    }

    /**
     * resolved 런의 속성으로 AST 런을 보강한다.
     */
    private static void applyRunOverrides(ASTTextRun astRun,
                                            ResolvedRun resRun,
                                            ResolvedData resolved) {
        // grepMathFont 보호: 수식 폰트 런은 fontFamily를 덮어씌우지 않음
        boolean protectFont = astRun.grepMathFont();

        // fillColor → textColor (resolved hex)
        if (resRun.fillColor() != null) {
            String hex = resolved.resolveColorHex(resRun.fillColor());
            if (hex != null) {
                astRun.textColor(hex);
            }
        }

        // fontFamily (GREP 스타일 결과 반영)
        if (!protectFont && resRun.fontFamily() != null) {
            astRun.fontFamily(resRun.fontFamily());
        }

        // fontStyle
        if (!protectFont && resRun.fontStyle() != null) {
            astRun.fontStyle(resRun.fontStyle());
        }

        // fontSize → fontSizeHwpunits
        if (resRun.fontSize() != null) {
            astRun.fontSizeHwpunits((int) (resRun.fontSize() * 100));
        }

        // tracking → letterSpacing (1/1000 em → HWPX %)
        if (resRun.tracking() != null) {
            astRun.letterSpacing((short) Math.round(resRun.tracking() / 10.0));
        }

        // horizontalScale (장평, %)
        if (resRun.horizontalScale() != null) {
            astRun.horizontalScale((short) Math.round(resRun.horizontalScale()));
        }

        // underline
        if (resRun.underline() != null) {
            astRun.underline(resRun.underline());
        }

        // strikeThru
        if (resRun.strikeThru() != null) {
            astRun.strikeThrough(resRun.strikeThru());
        }

        // position → subscript/superscript
        if (resRun.position() != null) {
            String pos = resRun.position().toUpperCase();
            if (pos.contains("SUPERSCRIPT")) {
                astRun.superscript(true);
                astRun.subscript(false);
            } else if (pos.contains("SUBSCRIPT")) {
                astRun.subscript(true);
                astRun.superscript(false);
            } else if (pos.contains("NORMAL")) {
                astRun.superscript(false);
                astRun.subscript(false);
            }
        }
    }
}
