package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase5;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-013 Phase 5: textwrap 글상자 분할 (후처리).
 *
 * <p>Phase 3에서 완성된 블록을 composedLine wrapIndent 기반으로 분할.
 * 런 분할, 인라인 처리, 폰트 매핑 등이 모두 적용된 후 실행.</p>
 *
 * <p>{@code ResolvedToASTBuilder.splitByWrapIndent + groupTextLen}에서
 * stateless static helper로 발췌. 동작은 동일하며 builder는 위임만 한다.</p>
 */
public final class WrapPhase5 {

    private WrapPhase5() {}

    public static void splitByWrapIndent(ResolvedBuildContext ctx, List<ASTSection> sections) {
        int splitCount = 0;
        for (ASTSection section : sections) {
            List<ASTBlock> newBlocks = new ArrayList<>();
            for (ASTBlock blk : section.blocks()) {
                if (!(blk instanceof ASTTextFrameBlock)) {
                    newBlocks.add(blk);
                    continue;
                }
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) blk;
                String srcId = tfb.sourceId();
                // sourceId → resolved TextFrame → composedLines
                String hexPart = srcId != null && srcId.startsWith("u")
                        ? srcId.substring(1) : null;
                if (hexPart == null) { newBlocks.add(blk); continue; }
                if (hexPart.contains("_")) hexPart = hexPart.substring(0, hexPart.indexOf('_'));
                String domId;
                try { domId = String.valueOf(Integer.parseInt(hexPart, 16)); }
                catch (NumberFormatException e) { newBlocks.add(blk); continue; }
                ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
                if (rtf == null) { newBlocks.add(blk); continue; }

                // 연결 글상자 체인이면 모든 TF의 composedLines를 합침
                List<ResolvedTextFrame.ComposedLine> allComposedLines = new ArrayList<>();
                if (rtf.composedLines() != null) allComposedLines.addAll(rtf.composedLines());
                if (rtf.nextFrameId() != null) {
                    String nextId = rtf.nextFrameId();
                    while (nextId != null) {
                        ResolvedTextFrame nextTf = ctx.resolvedData.getTextFrame(nextId);
                        if (nextTf == null) break;
                        // 다른 페이지의 연결 글상자는 wrap 분석에 포함하지 않음
                        if (nextTf.pageIndex() != rtf.pageIndex()) break;
                        if (nextTf.composedLines() != null) allComposedLines.addAll(nextTf.composedLines());
                        nextId = nextTf.nextFrameId();
                    }
                }
                if (allComposedLines.size() < 2) {
                    newBlocks.add(blk);
                    continue;
                }
                boolean isLinkedChain = allComposedLines.size() > (rtf.composedLines() != null ? rtf.composedLines().size() : 0);
                // 문단이 비어있는 블록은 분할하지 않음 (다단 분할 등으로 문단 미배분)
                if (tfb.paragraphs() == null || tfb.paragraphs().isEmpty()) {
                    newBlocks.add(blk);
                    continue;
                }

                List<ResolvedTextFrame.ComposedLine> lines = allComposedLines;

                // YGapSplit 블록: 해당 paraIndex 범위의 composedLines만 사용
                if (srcId != null && srcId.contains("_g") && tfb.composedCharStart() >= 0) {
                    int tfParaStart = rtf.paragraphStart();
                    int gParaStart = tfb.composedCharStart();
                    int gParaEnd = tfb.composedCharEnd();
                    // 절대 paraIndex → TF 내부 상대 paraIndex
                    int relStart = gParaStart - tfParaStart;
                    int relEnd = gParaEnd - tfParaStart;
                    List<ResolvedTextFrame.ComposedLine> filtered = new ArrayList<>();
                    for (ResolvedTextFrame.ComposedLine cl : lines) {
                        int pi = cl.paraIndex();
                        if (pi >= relStart && pi <= relEnd) filtered.add(cl);
                    }
                    lines = filtered;
                    if (lines.size() < 2) { newBlocks.add(blk); continue; }
                }

                // wrap 감지: 좌/우 indent > threshold 가 3행 이상 연속
                // (normalizeToPoints 후 indent는 pt 단위)
                double[] gb0 = rtf.geometricBounds();
                double frameW0 = gb0[3] - gb0[1];
                // 연결 글상자 체인은 indent가 작아도 일관되면 textwrap → threshold 낮춤
                double indentThreshold = frameW0 * (isLinkedChain ? 0.12 : 0.20);
                int indentedConsecutive = 0;
                int maxConsecutive = 0;
                for (int i = 0; i < lines.size(); i++) {
                    double indR = lines.get(i).wrapIndentRight();
                    double indL = lines.get(i).wrapIndentLeft();
                    if ((indR > indentThreshold || indL > indentThreshold) && i < lines.size() - 1) {
                        indentedConsecutive++;
                        maxConsecutive = Math.max(maxConsecutive, indentedConsecutive);
                    } else {
                        indentedConsecutive = 0;
                    }
                }
                if (maxConsecutive < 3) {
                    // 대부분의 줄에 일관된 indent가 있으면 폭을 중앙값만큼 축소 (분할 대신 shrink)
                    // 안전 장치: 최소값 사용, 프레임 폭의 15% 이상 축소 안 함
                    if (lines.size() >= 3) {
                        // SPEC-025: 행글 매달림 들여쓰기(bullet hanging indent) 감지.
                        // 각 단락의 첫 줄 wrapIndentLeft=0 이고 연속 줄들이 wrapIndentLeft>0 이면
                        // 의도된 hanging indent (bullet 구조) → obstacle wrap 으로 오인하지 말 것.
                        // 비슷하게 단락 끝 줄의 wrapIndentRight 가 일관되면 inline 아이콘 (예: emoji)
                        // 으로 인한 trailing indent → obstacle 아님.
                        boolean isHangingPattern = true;
                        int prevPi = -999;
                        for (int li = 0; li < lines.size(); li++) {
                            int curPi = lines.get(li).paraIndex();
                            boolean isParaStart = (curPi != prevPi);
                            double wL = lines.get(li).wrapIndentLeft();
                            if (isParaStart && wL > 10) { isHangingPattern = false; break; }
                            if (!isParaStart && wL <= 10) { isHangingPattern = false; break; }
                            prevPi = curPi;
                        }
                        if (isHangingPattern) {
                            // bullet/icon 구조 — shrink 적용 안 함
                            newBlocks.add(blk);
                            continue;
                        }
                        double maxShrink = frameW0 * 0.15; // 최대 축소량
                        List<Double> indRs2 = new ArrayList<>(), indLs2 = new ArrayList<>();
                        for (ResolvedTextFrame.ComposedLine cl : lines) {
                            if (cl.wrapIndentRight() > 10) indRs2.add(cl.wrapIndentRight());
                            if (cl.wrapIndentLeft() > 10) indLs2.add(cl.wrapIndentLeft());
                        }
                        if (indRs2.size() >= lines.size() / 2) {
                            java.util.Collections.sort(indRs2);
                            // 최소값 사용 (문단 끝 줄의 큰 indR 무시)
                            double shrinkR = Math.min(indRs2.get(0), maxShrink);
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(shrinkR));
                        }
                        if (indLs2.size() >= lines.size() / 2) {
                            java.util.Collections.sort(indLs2);
                            double shrinkL = Math.min(indLs2.get(0), maxShrink);
                            tfb.x(tfb.x() + CoordinateConverter.pointsToHwpunits(shrinkL));
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(shrinkL));
                        }
                    }
                    newBlocks.add(blk);
                    continue;
                }

                // indent 패턴으로 행 그룹 분할: 넓은 vs 좁은(좌/우 indent 큰) 연속 구간
                // 1~2행만 다른 패턴이면 앞/뒤 그룹에 병합 (너무 세밀한 분할 방지)
                // Step 1: 행별 narrow 판정 (좌/우 어느 쪽이든 threshold 초과)
                // narrowDir: 0=wide, 1=narrowRight, 2=narrowLeft, 3=narrowBoth
                int[] narrowDir = new int[lines.size()];
                boolean[] narrowFlags = new boolean[lines.size()];
                for (int i = 0; i < lines.size(); i++) {
                    double indR = lines.get(i).wrapIndentRight();
                    double indL = lines.get(i).wrapIndentLeft();
                    boolean isLast = (i >= lines.size() - 1);
                    // 문단 끝 줄(\r로 끝남)의 indR은 텍스트가 짧아서 생기는 빈 공간 → narrow 제외
                    String lineText = lines.get(i).text();
                    boolean isParagraphEnd = lineText != null && lineText.endsWith("\r");
                    boolean nr = indR > indentThreshold && !isLast && !isParagraphEnd;
                    boolean nl = indL > indentThreshold && !isLast;
                    narrowFlags[i] = nr || nl;
                    if (nr && nl) narrowDir[i] = 3;
                    else if (nl) narrowDir[i] = 2;
                    else if (nr) narrowDir[i] = 1;
                    else narrowDir[i] = 0;
                }
                // Step 2: 1~2행짜리 구간은 이전 구간에 병합 (좁은→넓은 방향만, 또는 마지막 구간)
                for (int i = 1; i < lines.size(); i++) {
                    if (narrowDir[i] != narrowDir[i - 1]) {
                        int runLen = 1;
                        while (i + runLen < lines.size() && narrowDir[i + runLen] == narrowDir[i]) runLen++;
                        if (runLen <= 2) {
                            boolean mergeToWide = narrowFlags[i]; // 좁은 구간이면 넓은으로
                            boolean isTrailing = (i + runLen >= lines.size()); // 마지막 구간
                            if (mergeToWide || isTrailing) {
                                for (int j = i; j < i + runLen && j < lines.size(); j++) {
                                    narrowFlags[j] = narrowFlags[i - 1];
                                    narrowDir[j] = narrowDir[i - 1];
                                }
                            }
                        }
                    }
                }
                // Step 3: 그룹 생성 (narrowDir이 다르면 새 그룹)
                List<List<ResolvedTextFrame.ComposedLine>> groups = new ArrayList<>();
                List<ResolvedTextFrame.ComposedLine> currentGroup = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    if (i > 0 && narrowDir[i] != narrowDir[i - 1] && !currentGroup.isEmpty()) {
                        groups.add(currentGroup);
                        currentGroup = new ArrayList<>();
                    }
                    currentGroup.add(lines.get(i));
                }
                if (!currentGroup.isEmpty()) groups.add(currentGroup);
                // 마지막 그룹이 실질 텍스트가 적으면 이전 그룹에 병합
                // (빈 줄, 인라인 마커, 짧은 문장 끝부분 등)
                while (groups.size() > 1) {
                    List<ResolvedTextFrame.ComposedLine> lastGrp = groups.get(groups.size() - 1);
                    String lastText = "";
                    int substantiveLines = 0;
                    for (ResolvedTextFrame.ComposedLine cl : lastGrp) {
                        String t = cl.text();
                        if (t != null) {
                            lastText += t;
                            String stripped = t.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim();
                            if (!stripped.isEmpty()) substantiveLines++;
                        }
                    }
                    lastText = lastText.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim();
                    // 실질 텍스트 줄이 1줄 이하이면 병합
                    if (substantiveLines <= 1) {
                        groups.remove(groups.size() - 1);
                        groups.get(groups.size() - 1).addAll(lastGrp);
                    } else {
                        break;
                    }
                }
                System.err.println("[WrapPhase5] tf=" + domId + " groups=" + groups.size() + " narrowDir=" + java.util.Arrays.toString(narrowDir));
                if (groups.size() <= 1) {
                    // 전체가 narrow이면 블록 폭을 일관된 indent만큼 축소
                    if (groups.size() == 1 && narrowFlags[0]) {
                        int firstLineIdx2 = 0;
                        int groupDir2 = narrowDir[firstLineIdx2];
                        // 줄별 indent를 수집하여 하위 25% 값 사용 (outlier 제거)
                        double adjIndR = 0, adjIndL = 0;
                        if (groupDir2 == 1 || groupDir2 == 3) {
                            for (ResolvedTextFrame.ComposedLine cl : lines) {
                                if (cl.wrapIndentRight() > adjIndR) adjIndR = cl.wrapIndentRight();
                            }
                        }
                        if (groupDir2 == 2 || groupDir2 == 3) {
                            for (ResolvedTextFrame.ComposedLine cl : lines) {
                                if (cl.wrapIndentLeft() > adjIndL) adjIndL = cl.wrapIndentLeft();
                            }
                        }
                        // 좁은 frame (예: 9mm 폭 라인넘버 column) 에 우측/중앙 정렬된 텍스트는
                        // wrapIndentLeft 가 frame width 에 가까운 값 → 실제 wrap 이 아니라 alignment 임.
                        // shrink 적용 시 frame 의 50% 이상 보존되어야 의미가 있음.
                        double maxShrink = frameW0 * 0.5;
                        if (adjIndR > maxShrink) adjIndR = 0;
                        if (adjIndL > maxShrink) adjIndL = 0;
                        if (adjIndR > 0) {
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(adjIndR));
                        }
                        if (adjIndL > 0) {
                            tfb.x(tfb.x() + CoordinateConverter.pointsToHwpunits(adjIndL));
                            tfb.width(tfb.width() - CoordinateConverter.pointsToHwpunits(adjIndL));
                        }
                        System.err.println("[WrapNarrow]   newW=" + tfb.width());
                    }
                    newBlocks.add(blk);
                    continue;
                }

                // 블록의 단락 텍스트를 연결하여 문자 범위 매핑
                StringBuilder storyText = new StringBuilder();
                List<int[]> paraRanges = new ArrayList<>();
                if (tfb.paragraphs() != null) {
                    for (ASTParagraph p : tfb.paragraphs()) {
                        int s = storyText.length();
                        String pt = ParagraphTextHelpers.getParaPlainText(p);
                        storyText.append(pt != null ? pt : "");
                        paraRanges.add(new int[]{s, storyText.length()});
                    }
                }

                // 각 그룹의 문자 범위 계산 (composedLine 텍스트 누적)
                double[] gb = rtf.geometricBounds();
                double frameW = gb[3] - gb[1];
                System.err.println("[WrapPhase5] tf=" + domId + " sourceId=" + tfb.sourceId() + " frameW=" + String.format("%.1f", frameW) + " lines=" + lines.size() + " maxConsR=" + maxConsecutive);
                int charOffset = 0;
                for (int gi = 0; gi < groups.size(); gi++) {
                    List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);
                    ResolvedTextFrame.ComposedLine first = group.get(0);
                    ResolvedTextFrame.ComposedLine last = group.get(group.size() - 1);

                    // 그룹의 indent 계산: narrow 그룹은 해당 방향의 최소 indent
                    // wide 그룹도 일관된 indent가 있으면 반영 (사이드바 등)
                    int firstLineIdx = lines.indexOf(group.get(0));
                    int groupDir = firstLineIdx >= 0 ? narrowDir[firstLineIdx] : 0;
                    double indR = 0;
                    double indL = 0;
                    if (groupDir != 0) {
                        // narrowRight(1) 또는 both(3) → indR 사용
                        if (groupDir == 1 || groupDir == 3) {
                            indR = Double.MAX_VALUE;
                            for (ResolvedTextFrame.ComposedLine cl : group) {
                                if (cl.wrapIndentRight() > 0) indR = Math.min(indR, cl.wrapIndentRight());
                            }
                            if (indR == Double.MAX_VALUE) indR = 0;
                        }
                        // narrowLeft(2) 또는 both(3) → indL 사용
                        if (groupDir == 2 || groupDir == 3) {
                            indL = Double.MAX_VALUE;
                            for (ResolvedTextFrame.ComposedLine cl : group) {
                                if (cl.wrapIndentLeft() > 0) indL = Math.min(indL, cl.wrapIndentLeft());
                            }
                            if (indL == Double.MAX_VALUE) indL = 0;
                        }
                    } else {
                        // wide 그룹이라도 일관된 indent가 있으면 폭에 반영
                        double minIndR = Double.MAX_VALUE;
                        double minIndL = Double.MAX_VALUE;
                        boolean allHaveR = true, allHaveL = true;
                        for (ResolvedTextFrame.ComposedLine cl : group) {
                            if (cl.wrapIndentRight() > 0) minIndR = Math.min(minIndR, cl.wrapIndentRight());
                            else allHaveR = false;
                            if (cl.wrapIndentLeft() > 0) minIndL = Math.min(minIndL, cl.wrapIndentLeft());
                            else allHaveL = false;
                        }
                        if (allHaveR && minIndR != Double.MAX_VALUE && minIndR > 10) indR = minIndR;
                        if (allHaveL && minIndL != Double.MAX_VALUE && minIndL > 10) indL = minIndL;
                    }

                    // frameW, gb, indent, bounds 모두 pt (normalizeToPoints 적용)
                    double groupW = frameW - indL - indR;
                    double groupH = last.bounds()[2] - first.bounds()[0];
                    // Y 위치: _g 블록은 tfb.y()가 이미 절대 좌표 → 그룹 내 상대 오프셋 사용
                    boolean isYGapBlock = srcId != null && srcId.contains("_g");
                    double groupY;
                    if (isYGapBlock) {
                        // _g 블록의 첫 composedLine과 필터링된 lines의 첫 줄 사이 오프셋
                        double blockFirstLineTop = lines.get(0).bounds()[0];
                        groupY = tfb.y() + CoordinateConverter.pointsToHwpunits(
                                first.bounds()[0] - blockFirstLineTop);
                    } else {
                        groupY = tfb.y() + CoordinateConverter.pointsToHwpunits(
                                first.bounds()[0] - gb[0]);
                    }

                    if (groupW <= 0 || groupH <= 0) {
                        charOffset += groupTextLen(group);
                        continue;
                    }

                    // 그룹의 문자 범위
                    int groupCharStart = charOffset;
                    int groupCharEnd = charOffset + groupTextLen(group);
                    charOffset = groupCharEnd;

                    // 새 블록 생성
                    ASTTextFrameBlock splitBlock = new ASTTextFrameBlock();
                    splitBlock.sourceId(tfb.sourceId());
                    splitBlock.x(tfb.x() + CoordinateConverter.pointsToHwpunits(indL));
                    splitBlock.y((long) groupY);
                    splitBlock.width(CoordinateConverter.pointsToHwpunits(groupW));
                    splitBlock.height(CoordinateConverter.pointsToHwpunits(groupH));
                    splitBlock.zOrder(tfb.zOrder());
                    splitBlock.columnCount(1);
                    splitBlock.insetTop(tfb.insetTop());
                    splitBlock.insetLeft(tfb.insetLeft());
                    splitBlock.insetBottom(tfb.insetBottom());
                    splitBlock.insetRight(tfb.insetRight());

                    // 문자 범위에 해당하는 단락 분배
                    if (tfb.paragraphs() != null) {
                        for (int pi = 0; pi < tfb.paragraphs().size(); pi++) {
                            int paraStart = paraRanges.get(pi)[0];
                            int paraEnd = paraRanges.get(pi)[1];
                            if (paraEnd <= groupCharStart) continue;
                            if (paraStart >= groupCharEnd) break;
                            if (paraStart >= groupCharStart && paraEnd <= groupCharEnd) {
                                splitBlock.addParagraph(tfb.paragraphs().get(pi));
                            } else if (paraStart < groupCharEnd && paraEnd > groupCharEnd) {
                                int cutLen = groupCharEnd - paraStart;
                                ASTParagraph trimmed = ParagraphTextHelpers.createSplitParagraph(tfb.paragraphs().get(pi),
                                        ParagraphTextHelpers.getParaPlainText(tfb.paragraphs().get(pi)) != null
                                                ? ParagraphTextHelpers.getParaPlainText(tfb.paragraphs().get(pi)).substring(0, Math.min(cutLen, ParagraphTextHelpers.getParaPlainText(tfb.paragraphs().get(pi)).length()))
                                                : "");
                                if (trimmed != null) splitBlock.addParagraph(trimmed);
                            } else if (paraStart < groupCharStart && paraEnd > groupCharStart) {
                                int skipLen = groupCharStart - paraStart;
                                String fullText = ParagraphTextHelpers.getParaPlainText(tfb.paragraphs().get(pi));
                                String contText = (fullText != null && skipLen < fullText.length())
                                        ? fullText.substring(skipLen) : "";
                                ASTParagraph cont = ParagraphTextHelpers.createContinuationParagraph(tfb.paragraphs().get(pi), skipLen, contText);
                                if (cont != null) splitBlock.addParagraph(cont);
                            }
                        }
                    }

                    System.err.println("[WrapSplit] group " + gi + ": w=" + splitBlock.width() + " h=" + splitBlock.height() + " y=" + splitBlock.y() + " paras=" + (splitBlock.paragraphs() != null ? splitBlock.paragraphs().size() : 0));
                    newBlocks.add(splitBlock);
                }
                splitCount++;
            }
            // 블록 리스트 교체
            section.blocks().clear();
            for (ASTBlock b : newBlocks) section.addBlock(b);
        }
        if (splitCount > 0) {
            System.err.println("[SplitByWrapIndent] " + splitCount + " blocks split");
        }
    }

    private static int groupTextLen(List<ResolvedTextFrame.ComposedLine> group) {
        int len = 0;
        for (ResolvedTextFrame.ComposedLine cl : group) {
            String t = cl.text();
            if (t != null) len += t.replace("\r", "").length();
        }
        return len;
    }
}
