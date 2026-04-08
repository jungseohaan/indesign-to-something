package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC-013 Phase 2: TextFrame 분류 + 좌표 → 페이지 배치.
 *
 * <p>{@code ResolvedToASTBuilder.placeTextFrames + placeByYGapSplit + isNestedInTextFrame}
 * 에서 stateless static helper로 발췌. 동작은 동일.</p>
 */
public final class FramePlacer {

    private FramePlacer() {}

    public static void placeTextFrames(ResolvedBuildContext ctx, List<ASTSection> sections) {
        List<ResolvedTextFrame> frames = ctx.resolvedData.textFrames();

        for (ResolvedTextFrame tf : frames) {
            // 인라인 프레임은 Phase 3에서 처리
            // 단, non-editable + non-rendered + story 미공유 인라인이면 플로팅 전환
            boolean inlineToFloating = false;
            if (tf.isInline()) {
                if (!ctx.resolvedData.isEditableTextFrame(tf.id())) {
                    String vis = tf.frameVisibleText();
                    boolean hasText = vis != null && vis.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 5;
                    int domIdInt = -1;
                    try { domIdInt = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                    boolean rendered = domIdInt >= 0 && ctx.resolvedData.isRenderedByOtherChannel(domIdInt);
                    boolean sharedWithEditable = false;
                    if (tf.storyId() != null) {
                        for (ResolvedTextFrame other : frames) {
                            if (tf.storyId().equals(other.storyId()) && ctx.resolvedData.isEditableTextFrame(other.id())) {
                                sharedWithEditable = true;
                                break;
                            }
                        }
                    }
                    // parentId가 있으면 다른 객체 안에 중첩 → 배경에서 부모와 함께 숨겨짐
                    boolean hasParent = false;
                    ResolvedPageItem rpi = ctx.resolvedData.getPageItem(tf.id());
                    if (rpi != null && rpi.parentId() != null) hasParent = true;
                    if (hasText && !rendered && !sharedWithEditable && hasParent) {
                        inlineToFloating = true;
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
            }

            // 다른 TextFrame 안에 중첩된 프레임은 건너뜀 (부모가 배경에 포함)
            if (!inlineToFloating && isNestedInTextFrame(ctx, tf)) continue;

            // 배경에 포함된 프레임은 건너뜀 (editable 프레임만 글상자로 배치)
            // 단, 같은 story를 editable TF와 공유하는 non-editable TF는 배치
            if (!inlineToFloating && !ctx.resolvedData.isEditableTextFrame(tf.id())) {
                boolean sharedWithEditable = false;
                if (tf.storyId() != null) {
                    for (ResolvedTextFrame other : frames) {
                        if (tf.storyId().equals(other.storyId()) && ctx.resolvedData.isEditableTextFrame(other.id())) {
                            sharedWithEditable = true;
                            break;
                        }
                    }
                }
                if (!sharedWithEditable) continue;
            }

            // 연결 글상자 체인: 후속 프레임은 건너뜀 (첫 프레임에서 병합 처리)
            // 단, 체인의 프레임들이 Y 방향으로 떨어져 있으면 병합하지 않음 (각각 배치)
            if (tf.previousFrameId() != null) {
                ResolvedTextFrame prevTf = ctx.resolvedData.getTextFrame(tf.previousFrameId());
                if (prevTf != null && prevTf.geometricBounds() != null && tf.geometricBounds() != null) {
                    // 다른 페이지에 있으면 독립 배치
                    boolean diffPage = prevTf.pageIndex() != tf.pageIndex();
                    double prevBottom = prevTf.geometricBounds()[2];
                    double curTop = tf.geometricBounds()[0];
                    double gap = curTop - prevBottom;
                    double lineH = tf.geometricBounds()[2] - tf.geometricBounds()[0];
                    // gap이 한 줄 높이 이상이거나 다른 페이지이면 독립 배치
                    if (diffPage || gap > lineH * 0.5) {
                        // 병합하지 않고 독립 배치 → continue하지 않음
                    } else {
                        continue; // 인접 → 병합 (첫 프레임에서 처리)
                    }
                } else {
                    continue;
                }
            }

            // 페이지 인덱스 결정 (document offset → section index 매핑)
            int pageIdx = ctx.toSectionIndex.applyAsInt(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 좌표 계산: geometricBounds는 spread 좌표 (applyScale 후 pt)
            // → page bounds를 빼서 page-relative로 변환
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;

            ResolvedPage rPage = (pageIdx < ctx.resolvedData.pages().size())
                    ? ctx.resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;

            // 연결 글상자 체인이면 인접한 프레임만 bounds 합산 (복사본 사용)
            if (tf.nextFrameId() != null) {
                gb = new double[]{gb[0], gb[1], gb[2], gb[3]};
                String nextId = tf.nextFrameId();
                while (nextId != null) {
                    ResolvedTextFrame next = ctx.resolvedData.getTextFrame(nextId);
                    if (next == null || next.geometricBounds() == null) break;
                    double[] ngb = next.geometricBounds();
                    // 다른 페이지이거나 Y 간격이 한 줄 높이의 50% 이상이면 합산 중단
                    if (next.pageIndex() != tf.pageIndex()) break;
                    double gap = ngb[0] - gb[2];
                    double lineH = ngb[2] - ngb[0];
                    if (gap > lineH * 0.5) break;
                    if (ngb[0] < gb[0]) gb[0] = ngb[0];
                    if (ngb[1] < gb[1]) gb[1] = ngb[1];
                    if (ngb[2] > gb[2]) gb[2] = ngb[2];
                    if (ngb[3] > gb[3]) gb[3] = ngb[3];
                    nextId = next.nextFrameId();
                }
            }

            // facing pages: InDesign geometricBounds가 이미 page-relative인 경우 감지
            // gb.left < pageBounds.left이면 spread 좌표가 아닌 page-relative 좌표
            boolean gbAlreadyPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
            double x = gbAlreadyPageRelative ? gb[1] : (gb[1] - pageLeft);
            double y = gb[0] - pageTop;
            double w = gb[3] - gb[1];
            double h = gb[2] - gb[0];

            ASTSection section = sections.get(pageIdx);

            // 음수 좌표 클램핑
            if (x < 0) { w += x; x = 0; }
            if (y < 0) { h += y; y = 0; }
            if (w <= 0 || h <= 0) continue;

            // composedLines 기반 글상자 분할
            if (tf.composedLines() != null && tf.composedLines().size() > 1) {
                // 1) wrap indent 기반 분할 (텍스트가 이미지를 비껴가는 경우)
                // placeByWrapIndent는 Phase 5에서 후처리 (Phase 3 변환 파이프라인 유지)
                // if (placeByWrapIndent(tf, section, pageLeft, pageTop)) continue;
                // 2) Y 점프 기반 분할 (큰 수직 갭이 있는 경우)
                if (placeByYGapSplit(ctx, tf, section, pageLeft, pageTop)) {
                    continue;
                }
            }

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId("u" + Integer.toHexString(Integer.parseInt(tf.id())));
            block.x(CoordinateConverter.pointsToHwpunits(x));
            block.y(CoordinateConverter.pointsToHwpunits(y));
            block.width(CoordinateConverter.pointsToHwpunits(w));
            block.height(CoordinateConverter.pointsToHwpunits(h));
            block.zOrder(tf.zOrder());
            block.columnCount(tf.columnCount() > 0 ? tf.columnCount() : 1);
            block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter() * ctx.scaleFactor));

            // 내부 여백 (insetSpacing — 이미 pt로 스케일됨)
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
                block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
                block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
                block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
            }

            // 수직 정렬
            if (tf.verticalJustification() != null) {
                block.verticalJustification(tf.verticalJustification());
            }

            if (tf.rotationAngle() != 0) {
                block.rotationAngle(tf.rotationAngle());
            }

            // 시각 속성: 배경색은 배경 PNG에 포함됨 (텍스트만 비우고 프레임은 유지)
            // HWPX 글상자에는 fillColor를 적용하지 않음 (이중 표시 방지)

            // overflow 감지용 텍스트 길이 저장
            String visText = tf.frameVisibleText();
            if (visText != null) {
                block.frameVisibleTextLength(visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").length());
            }
            // storyTotalTextLength는 convertStories()에서 설정

            section.addBlock(block);
        }
    }

    private static boolean isNestedInTextFrame(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        List<ResolvedPageItem> pageItems = ctx.resolvedData.pageItems();
        if (pageItems == null) return false;

        // 이 TextFrame의 parentId 찾기
        String parentId = null;
        for (ResolvedPageItem pi : pageItems) {
            if (tf.id().equals(String.valueOf(pi.id()))) {
                parentId = pi.parentId() != null ? String.valueOf(pi.parentId()) : null;
                break;
            }
        }

        // parentId 체인을 추적하여 TextFrame 부모 확인
        for (int depth = 0; depth < 5 && parentId != null; depth++) {
            for (ResolvedPageItem pi : pageItems) {
                if (parentId.equals(String.valueOf(pi.id()))) {
                    if ("TextFrame".equals(pi.type())) return true;
                    parentId = pi.parentId() != null ? String.valueOf(pi.parentId()) : null;
                    break;
                }
            }
        }
        return false;
    }

    private static boolean placeByYGapSplit(ResolvedBuildContext ctx, ResolvedTextFrame tf, ASTSection section,
                                             double pageLeft, double pageTop) {
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();

        // 정상 행간 계산: 처음 몇 줄의 Y 간격 중앙값
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            double[] prev = lines.get(i - 1).bounds();
            double[] curr = lines.get(i).bounds();
            if (prev == null || curr == null) continue;
            double gap = curr[0] - prev[0]; // top 차이
            if (gap > 0) gaps.add(gap);
        }
        if (gaps.isEmpty()) return false;

        java.util.Collections.sort(gaps);
        double medianGap = gaps.get(gaps.size() / 2);

        // Y 점프 분할 지점 감지 (중앙값의 3배 이상)
        List<Integer> splitPoints = new ArrayList<>(); // 분할 후 새 그룹 시작 라인 인덱스
        for (int i = 1; i < lines.size(); i++) {
            double[] prev = lines.get(i - 1).bounds();
            double[] curr = lines.get(i).bounds();
            if (prev == null || curr == null) continue;
            double gap = curr[0] - prev[0];
            if (gap > medianGap * 3) {
                splitPoints.add(i);
            }
        }

        if (splitPoints.isEmpty()) return false; // 분할 불필요

        // 분할 지점으로 라인 그룹 생성
        List<List<ResolvedTextFrame.ComposedLine>> groups = new ArrayList<>();
        int from = 0;
        for (int sp : splitPoints) {
            groups.add(lines.subList(from, sp));
            from = sp;
        }
        groups.add(lines.subList(from, lines.size()));

        String sourceIdBase = "u" + Integer.toHexString(Integer.parseInt(tf.id()));
        int charOffset = 0;

        for (int gi = 0; gi < groups.size(); gi++) {
            List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);

            // 그룹 bounds (앞뒤 빈 줄은 높이 계산에서 제외)
            int firstSubstantive = 0;
            while (firstSubstantive < group.size()) {
                String lt = group.get(firstSubstantive).text();
                if (lt != null && !lt.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim().isEmpty()) break;
                firstSubstantive++;
            }
            int lastSubstantive = group.size() - 1;
            while (lastSubstantive > firstSubstantive) {
                String lt = group.get(lastSubstantive).text();
                if (lt != null && !lt.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim().isEmpty()) break;
                lastSubstantive--;
            }
            double minLeft = Double.MAX_VALUE, minTop = Double.MAX_VALUE;
            double maxRight = -Double.MAX_VALUE, maxBottom = -Double.MAX_VALUE;
            int groupCharCount = 0;
            for (int li = 0; li < group.size(); li++) {
                ResolvedTextFrame.ComposedLine line = group.get(li);
                double[] b = line.bounds();
                // 앞뒤 빈 줄은 top/bottom 계산에서 제외
                if (li >= firstSubstantive && b[0] < minTop) minTop = b[0];
                if (b[1] < minLeft) minLeft = b[1];
                if (li <= lastSubstantive && b[2] > maxBottom) maxBottom = b[2];
                if (b[3] > maxRight) maxRight = b[3];
                if (line.text() != null) groupCharCount += line.text().length();
            }

            // normalizeToPoints() 후 bounds는 이미 pt 단위
            // 폭은 TF의 geometricBounds를 사용 (composedLine bounds는 텍스트 영역만 반영하여 좁음)
            double[] tfGb = tf.geometricBounds();
            double gx = tfGb[1] - pageLeft;
            double gy = minTop - pageTop;
            double gw = tfGb[3] - tfGb[1];
            double gh = maxBottom - minTop;

            if (gx < 0) { gw += gx; gx = 0; }
            if (gy < 0) { gh += gy; gy = 0; }
            if (gw <= 0 || gh <= 0) continue;

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId(sourceIdBase + (groups.size() > 1 ? "_g" + gi : ""));
            block.x(CoordinateConverter.pointsToHwpunits(gx));
            block.y(CoordinateConverter.pointsToHwpunits(gy));
            block.width(CoordinateConverter.pointsToHwpunits(gw));
            block.height(CoordinateConverter.pointsToHwpunits(gh));
            block.zOrder(tf.zOrder());
            block.storyId(tf.storyId());
            block.distributed(true); // 분할 블록: 연결 글상자 링크 해제
            block.frameVisibleTextLength(groupCharCount);
            // frameVisibleText 설정 (distributeParagraphs에서 텍스트 기반 분배 사용)
            StringBuilder groupText = new StringBuilder();
            for (int li = firstSubstantive; li <= lastSubstantive && li < group.size(); li++) {
                ResolvedTextFrame.ComposedLine cl = group.get(li);
                if (cl.text() != null) groupText.append(cl.text());
            }
            block.frameVisibleText(groupText.toString());
            // paraIndex를 resolved TF의 paragraphStart 기준 절대 인덱스로 변환
            int tfParaStart = tf.paragraphStart();
            int absParaStart = Integer.MAX_VALUE, absParaEnd = -1;
            for (int li = firstSubstantive; li <= lastSubstantive && li < group.size(); li++) {
                int pi = group.get(li).paraIndex();
                if (pi >= 0) {
                    int abs = tfParaStart + pi;
                    if (abs < absParaStart) absParaStart = abs;
                    if (abs > absParaEnd) absParaEnd = abs;
                }
            }
            if (absParaStart != Integer.MAX_VALUE) {
                block.composedCharStart(absParaStart);
                block.composedCharEnd(absParaEnd);
            }

            // 프레임 속성 복사 (insetSpacing은 이미 pt)
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
                block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
                block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
                block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
            }

            charOffset += groupCharCount;
            section.addBlock(block);
        }

        return true;
    }
}
