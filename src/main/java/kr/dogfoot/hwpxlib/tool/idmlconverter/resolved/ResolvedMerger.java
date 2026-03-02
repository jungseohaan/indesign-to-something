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
            // AST storyId는 IDML hex 형식 ("u4f1"), resolved는 decimal ("1265")
            String decimalId = idmlIdToDecimal(storyId);
            ResolvedStory resolvedStory = resolved.getStory(decimalId);
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
        enrichParagraphProperties(astPara, resPara, resolved);

        // 런 매칭 및 보강
        enrichRuns(astPara, resPara, resolved);
    }

    private static void enrichParagraphProperties(ASTParagraph astPara,
                                                    ResolvedParagraph resPara,
                                                    ResolvedData resolved) {
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
        if (resPara.rightIndent() != null) {
            astPara.rightMargin((long) (resPara.rightIndent() * 100));
        }

        // shading (문단 배경)
        if (Boolean.TRUE.equals(resPara.shadingOn()) && resPara.shadingColor() != null) {
            astPara.shadingOn(true);
            String hex = resolved.resolveColorHex(resPara.shadingColor());
            if (hex != null) {
                astPara.shadingColor(hex);
            }
            if (resPara.shadingTint() != null) {
                astPara.shadingTint(resPara.shadingTint());
            }
        }

        // tabStops (인라인 오버라이드)
        if (resPara.hasTabStops() && !astPara.hasTabStops()) {
            for (ResolvedTabStop rts : resPara.tabStops()) {
                if (rts.position() != null) {
                    long posHwp = (long) (rts.position() * 100); // pts → hwpunits
                    String align = mapTabAlignment(rts.alignment());
                    String leader = rts.leader();
                    astPara.addTabStop(new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop(posHwp, align, leader));
                }
            }
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
     *
     * InDesign 값 (Justification prefix 제거):
     *   FULLY_JUSTIFIED → "justify" (완전 균등)
     *   CENTER_JUSTIFIED, RIGHT_JUSTIFIED → "justify"
     *   LEFT_JUSTIFIED → "left" (양쪽 정렬이지만 한글에서 LEFT 정렬이 시각적으로 근접)
     *   LEFT_ALIGN → "left"
     *   CENTER_ALIGN → "center"
     *   RIGHT_ALIGN → "right"
     */
    private static String mapJustification(String justification) {
        if (justification == null) return null;
        String upper = justification.toUpperCase();
        // 구체적 패턴 먼저 (FULLY_JUSTIFIED가 LEFT보다 먼저 매칭되어야 함)
        if (upper.contains("FULLY_JUSTIFIED") || upper.contains("FULL_JUSTIFY")) return "justify";
        if (upper.contains("CENTER_JUSTIFIED")) return "justify";
        if (upper.contains("RIGHT_JUSTIFIED")) return "justify";
        // LEFT_JUSTIFIED: InDesign 양쪽 정렬이지만, 한글 JUSTIFY와 시각 차이 존재
        // → LEFT로 매핑하여 원본에 가까운 시각적 결과 유지
        if (upper.contains("LEFT")) return "left";
        if (upper.contains("CENTER")) return "center";
        if (upper.contains("RIGHT")) return "right";
        if (upper.contains("JUSTIFY")) return "justify";
        return null;
    }

    /**
     * InDesign TabStopAlignment enum → AST tab alignment 매핑.
     */
    private static String mapTabAlignment(String alignment) {
        if (alignment == null) return "left";
        String upper = alignment.toUpperCase();
        if (upper.contains("CENTER")) return "center";
        if (upper.contains("RIGHT")) return "right";
        if (upper.contains("DECIMAL") || upper.contains("CHARACTER")) return "decimal";
        return "left";
    }

    /**
     * IDML hex ID ("u4f1") → decimal 문자열 ("1265") 변환.
     * resolved.json의 story id는 InDesign DOM decimal 형식.
     */
    private static String idmlIdToDecimal(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return idmlId;
        try {
            return String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
        } catch (NumberFormatException e) {
            return idmlId;
        }
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

        // verticalScale (세로 비율, %)
        if (resRun.verticalScale() != null) {
            astRun.verticalScale((short) Math.round(resRun.verticalScale()));
        }

        // baselineShift (pts → % of fontSize)
        if (resRun.baselineShift() != null && resRun.fontSize() != null && resRun.fontSize() > 0) {
            double pct = (resRun.baselineShift() / resRun.fontSize()) * 100.0;
            astRun.baselineShift((short) Math.round(pct));
        }

        // charStyle (InDesign GREP 해소 후 최종 문자 스타일)
        if (resRun.charStyle() != null && !resRun.charStyle().isEmpty()
                && !"[None]".equals(resRun.charStyle())) {
            astRun.characterStyleRef(resRun.charStyle());
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
