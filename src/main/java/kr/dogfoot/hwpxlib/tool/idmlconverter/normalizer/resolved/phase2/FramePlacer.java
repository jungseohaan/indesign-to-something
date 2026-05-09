package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
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
                    boolean hasText = vis != null && vis.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 1;
                    int domIdInt = -1;
                    try { domIdInt = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                    boolean rendered = domIdInt >= 0 && ctx.resolvedData.isRenderedByOtherChannel(domIdInt);
                    // badge_group_child의 부모 badge_group이 inline_object로도 배치되면
                    // 해당 PNG에 텍스트가 포함되어 있으므로 플로팅 텍스트 배치 건너뜀 (중복 방지)
                    if (!rendered && domIdInt >= 0) {
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                            if (rg.id() == domIdInt && "badge_group_child".equals(rg.itemType())) {
                                int parentBadgeId = rg.badgeGroupId();
                                for (RenderedGroup prg : ctx.resolvedData.allRenderedFloatingItems()) {
                                    if (prg.id() == parentBadgeId && "inline_object".equals(prg.itemType())) {
                                        rendered = true;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                    // inline_object의 childIds 에 포함된 TextFrame 은 부모 PNG 에 시각적으로
                    // 이미 텍스트가 포함됨 → 플로팅 텍스트 배치 건너뜀 (예: "After You Read" 버튼).
                    if (!rendered && domIdInt >= 0) {
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                            if ("inline_object".equals(rg.itemType()) && rg.childIds() != null) {
                                for (int cid : rg.childIds()) {
                                    if (cid == domIdInt) { rendered = true; break; }
                                }
                                if (rendered) break;
                            }
                        }
                    }
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
            // badge_group_child는 부모 badge PNG에 텍스트가 이미 포함되어 있으므로 글상자 배치 건너뜀 (중복 방지)
            boolean skipAsBadgeChild = false;
            {
                int domIdInt2 = -1;
                try { domIdInt2 = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                if (domIdInt2 >= 0) {
                    for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                        if (rg.id() == domIdInt2 && "badge_group_child".equals(rg.itemType())) {
                            skipAsBadgeChild = true;
                            break;
                        }
                    }
                }
            }
            if (skipAsBadgeChild) continue;

            // 연결 글상자 체인: 후속 프레임은 건너뜀 (첫 프레임에서 병합 처리)
            // 단, 체인의 프레임들이 Y 방향으로 떨어져 있거나 다른 컬럼이면 병합하지 않음 (각각 배치)
            if (tf.previousFrameId() != null) {
                ResolvedTextFrame prevTf = ctx.resolvedData.getTextFrame(tf.previousFrameId());
                if (prevTf != null && prevTf.geometricBounds() != null && tf.geometricBounds() != null) {
                    double[] pgb = prevTf.geometricBounds();
                    double[] cgb = tf.geometricBounds();
                    boolean diffPage = prevTf.pageIndex() != tf.pageIndex();
                    double prevBottom = pgb[2];
                    double curTop = cgb[0];
                    double gap = curTop - prevBottom;
                    double lineH = cgb[2] - cgb[0];
                    // X 범위 겹침 비율: 컬럼이 다르면 병합 안 함
                    double xOvStart = Math.max(pgb[1], cgb[1]);
                    double xOvEnd = Math.min(pgb[3], cgb[3]);
                    double prevW = pgb[3] - pgb[1];
                    double curW = cgb[3] - cgb[1];
                    double xOverlapRatio = (xOvEnd > xOvStart && prevW > 0 && curW > 0)
                            ? (xOvEnd - xOvStart) / Math.min(prevW, curW) : 0;
                    // gap<0 (역방향) 또는 gap>lineH*0.5 또는 다른 컬럼(X 겹침 <50%)이면 독립 배치
                    if (diffPage || gap < 0 || gap > lineH * 0.5 || xOverlapRatio < 0.5) {
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
                    // X 범위 겹침 확인: 다른 컬럼이면 병합 중단
                    double xOvStart = Math.max(gb[1], ngb[1]);
                    double xOvEnd = Math.min(gb[3], ngb[3]);
                    double curW = gb[3] - gb[1];
                    double nextW = ngb[3] - ngb[1];
                    double xOverlapRatio = (xOvEnd > xOvStart && curW > 0 && nextW > 0)
                            ? (xOvEnd - xOvStart) / Math.min(curW, nextW) : 0;
                    // gap<0 (역방향) 또는 gap>lineH*0.5 또는 다른 컬럼이면 병합 중단
                    if (gap < 0 || gap > lineH * 0.5 || xOverlapRatio < 0.5) break;
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

            // 타이틀 오버레이 패턴 사전 검사: 본문 TF 의 단락이 별도 타이틀 TF 로 덮여 있으면
            // 해당 단락을 제외 후보로 수집 (paraIdx=0 이면 y/h 도 둘째 줄 기준으로 보정).
            int preDetectedSkipParas = 0;
            java.util.Set<Integer> excludedParaIndices = null;
            try {
                java.util.List<ResolvedTextFrame.ComposedLine> _cls = tf.composedLines();
                if (_cls != null && !_cls.isEmpty() && tf.paragraphEnd() >= tf.paragraphStart()) {
                    // 단락별로 line bounds 를 union 해서 단락 영역 계산
                    java.util.Map<Integer, double[]> paraBounds = new java.util.HashMap<>();
                    java.util.Map<Integer, StringBuilder> paraTexts = new java.util.HashMap<>();
                    for (ResolvedTextFrame.ComposedLine cl : _cls) {
                        if (cl == null || cl.bounds() == null) continue;
                        int pi = cl.paraIndex();
                        double[] b = cl.bounds();
                        double[] cur = paraBounds.get(pi);
                        if (cur == null) {
                            paraBounds.put(pi, new double[]{b[0], b[1], b[2], b[3]});
                        } else {
                            if (b[0] < cur[0]) cur[0] = b[0];
                            if (b[1] < cur[1]) cur[1] = b[1];
                            if (b[2] > cur[2]) cur[2] = b[2];
                            if (b[3] > cur[3]) cur[3] = b[3];
                        }
                        StringBuilder sb = paraTexts.get(pi);
                        if (sb == null) { sb = new StringBuilder(); paraTexts.put(pi, sb); }
                        if (cl.text() != null) sb.append(cl.text());
                    }

                    for (java.util.Map.Entry<Integer, double[]> e : paraBounds.entrySet()) {
                        int pi = e.getKey();
                        double[] pb = e.getValue();
                        double pT = pb[0], pL = pb[1], pB = pb[2], pR = pb[3];
                        StringBuilder sb = paraTexts.get(pi);
                        if (sb == null) continue;
                        String paraText = sb.toString().replace("\r", "").replace("\n", "").trim();
                        if (paraText.length() < 3) continue;
                        for (ResolvedTextFrame _other : ctx.resolvedData.textFrames()) {
                            if (_other == null || _other == tf) continue;
                            if (_other.pageIndex() != tf.pageIndex()) continue;
                            if (_other.id() == null || _other.id().equals(tf.id())) continue;
                            double[] _ogb = _other.geometricBounds();
                            if (_ogb == null || _ogb.length < 4) continue;
                            double _oT = _ogb[0], _oL = _ogb[1], _oB = _ogb[2], _oR = _ogb[3];
                            double _ovStart = Math.max(pT, _oT);
                            double _ovEnd = Math.min(pB, _oB);
                            double _ov = _ovEnd - _ovStart;
                            if (_ov <= 0) continue;
                            double pH = pB - pT;
                            double _otherH = _oB - _oT;
                            if (pH <= 0 || _otherH <= 0) continue;
                            if (_ov / Math.min(pH, _otherH) < 0.5) continue;
                            // X 겹침: 다른 TF 가 이 단락 영역 내에 위치해야 오버레이로 간주
                            double _xOvStart = Math.max(pL, _oL);
                            double _xOvEnd = Math.min(pR, _oR);
                            double _xOv = _xOvEnd - _xOvStart;
                            if (_xOv <= 0) continue;
                            double _otherW = _oR - _oL;
                            if (_otherW <= 0) continue;
                            // 다른 TF 의 너비 80% 이상이 이 단락 영역 안에 들어와야 함
                            if (_xOv / _otherW < 0.8) continue;
                            String _otherText = _other.frameVisibleText();
                            if (_otherText == null || _otherText.isEmpty()) continue;
                            String _otherClean = _otherText.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                            if (_otherClean.length() < 3) continue;
                            if (paraText.contains(_otherClean)) {
                                if (excludedParaIndices == null) excludedParaIndices = new java.util.HashSet<>();
                                excludedParaIndices.add(pi + tf.paragraphStart());
                                break;
                            }
                        }
                    }

                    // paraIdx=0 이 제외되면 y/h 를 첫 비제외 단락 시작으로 이동
                    if (excludedParaIndices != null && excludedParaIndices.contains(tf.paragraphStart())) {
                        Integer firstKept = null;
                        java.util.List<Integer> sortedKeys = new java.util.ArrayList<>(paraBounds.keySet());
                        java.util.Collections.sort(sortedKeys);
                        for (int pi : sortedKeys) {
                            int absPi = pi + tf.paragraphStart();
                            if (!excludedParaIndices.contains(absPi)) { firstKept = pi; break; }
                        }
                        if (firstKept != null) {
                            double[] kb = paraBounds.get(firstKept);
                            double newYTop = kb[0] - pageTop;
                            double dy = newYTop - y;
                            if (dy > 0 && dy < h) {
                                y = newYTop;
                                h -= dy;
                                preDetectedSkipParas = firstKept;
                            }
                        }
                    }
                }
            } catch (Exception eTOPre) {}

            // 배지 그룹과 같은 baseline에서 X 좌표가 겹치면 배지 우측으로 이동.
            // InDesign은 zOrder/textWrap 으로 배지 위로 텍스트가 흐르지 않게 처리하지만,
            // HWPX 글상자는 절대좌표 배치라 시각적 충돌 → 텍스트 좌측을 배지 우측+여백으로 보정.
            for (RenderedGroup rg : ctx.resolvedData.allRenderedTextFrames()) {
                if (!rg.isBadgeGroup()) continue;
                if (rg.pageIndex() != tf.pageIndex()) continue;
                double[] bb = rg.bounds();
                if (bb == null || bb.length < 4) continue;
                // 배지 자식은 자기 자신이 배지이므로 제외
                int[] childIds = rg.childTextFrameIds();
                boolean selfChild = false;
                if (childIds != null) {
                    int tfIdInt;
                    try { tfIdInt = Integer.parseInt(tf.id()); } catch (NumberFormatException e) { tfIdInt = -1; }
                    for (int cid : childIds) { if (cid == tfIdInt) { selfChild = true; break; } }
                }
                if (selfChild) continue;
                // 배지 bounds 를 page-relative 로 변환 (RenderedGroup.bounds 는 이미 page-relative pt)
                double bT = bb[0], bL = bb[1], bB = bb[2], bR = bb[3];
                // Y 겹침 비율
                double yOvStart = Math.max(y, bT);
                double yOvEnd = Math.min(y + h, bB);
                double yOv = yOvEnd - yOvStart;
                if (yOv <= 0) continue;
                double tfYExtent = h;
                double badgeYExtent = bB - bT;
                double yOvRatio = yOv / Math.min(tfYExtent, badgeYExtent);
                if (yOvRatio < 0.5) continue;
                // X 겹침: 배지 우측이 TF 좌측보다 오른쪽에 있고, TF 좌측이 배지 영역에 포함되면 보정
                // 보정 후 너비가 너무 작아지면 스킵
                if (bR > x && bL < x + w * 0.5 && bR < x + w) {
                    double margin = 4.0; // pt
                    double newX = bR + margin;
                    double delta = newX - x;
                    double remainW = w - delta;
                    if (delta > 0 && remainW > w * 0.2 && remainW > 20.0) {
                        x = newX;
                        w = remainW;
                    }
                }
            }

            // 같은 부모 Group 안의 형제 도형(fill 있는 Rectangle/Polygon/Oval) 과
            // 동일 baseline 에 있을 때 형제 우측으로 이동. (예: page 17 Theme 라벨 + 한국어 TF)
            try {
                ResolvedPageItem tfPi = ctx.resolvedData.getPageItem(tf.id());
                String tfParentId = (tfPi != null) ? tfPi.parentId() : null;
                if (tfParentId != null) {
                    for (ResolvedPageItem sib : ctx.resolvedData.pageItems()) {
                        if (sib == null) continue;
                        if (sib.id() == null || sib.id().equals(tf.id())) continue;
                        if (!tfParentId.equals(sib.parentId())) continue;
                        String st = sib.type();
                        if (!"Rectangle".equals(st) && !"Polygon".equals(st) && !"Oval".equals(st)) continue;
                        String fcn = sib.fillColorName();
                        if (fcn == null || "None".equals(fcn) || "[None]".equals(fcn)) continue;
                        double[] sgb = sib.geometricBounds();
                        if (sgb == null || sgb.length < 4) continue;
                        double sbT = sgb[0] - pageTop, sbB = sgb[2] - pageTop;
                        double sbL = sgb[1] - pageLeft, sbR = sgb[3] - pageLeft;
                        // Y 겹침 50% 이상
                        double yOvStart2 = Math.max(y, sbT);
                        double yOvEnd2 = Math.min(y + h, sbB);
                        double yOv2 = yOvEnd2 - yOvStart2;
                        if (yOv2 <= 0) continue;
                        double sibYExt = sbB - sbT;
                        double yOvRatio2 = yOv2 / Math.min(h, sibYExt);
                        if (yOvRatio2 < 0.5) continue;
                        // X 보정 — 형제가 TF 좌측을 가리고 우측 절반 안 침범
                        // 보정 후 너비가 너무 작아지면 스킵 (사실상 TF 전체가 형제로 덮인 케이스)
                        if (sbR > x && sbL < x + w * 0.5 && sbR < x + w) {
                            double margin = 4.0;
                            double newX = sbR + margin;
                            double delta = newX - x;
                            double remainW = w - delta;
                            if (delta > 0 && remainW > w * 0.2 && remainW > 20.0) {
                                x = newX;
                                w = remainW;
                            }
                        }
                    }
                }
            } catch (Exception e) {}

            if (w <= 0) continue;

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
            // 부모 Group이 Phase7 렌더 PNG로 배치되면 그 위에 올라가야 함.
            // Phase7은 zOrder=10000-pageItemIdx 로 역매핑하므로 동일한 방식으로 계산하여
            // 부모 PNG 바로 위에 배치한다.
            int tfZ = tf.zOrder();
            try {
                ResolvedPageItem tfPi = ctx.resolvedData.getPageItem(tf.id());
                String parentId = (tfPi != null) ? tfPi.parentId() : null;
                if (parentId != null) {
                    ResolvedPageItem parentPi = ctx.resolvedData.getPageItem(parentId);
                    if (parentPi != null && "Group".equals(parentPi.type())) {
                        boolean parentRenderedAsFrame = false;
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedTextFrames()) {
                            if (String.valueOf(rg.id()).equals(parentId) && !rg.isBadgeGroup()) {
                                parentRenderedAsFrame = true;
                                break;
                            }
                        }
                        if (parentRenderedAsFrame) {
                            int parentHwpxZ = (parentPi.zOrder() > 0)
                                    ? Math.max(10000 - parentPi.zOrder(), 10) : 10;
                            tfZ = parentHwpxZ + 1; // 부모 PNG 바로 위
                        }
                    }
                }
            } catch (Exception e) {}
            block.zOrder(tfZ);
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

            // 시각 속성: TF 의 fillColor / cornerRadius 를 글상자에 적용.
            // (배경 PNG 에 같은 색이 있으면 같은 색으로 덧칠되므로 시각 차이 없음.
            //  배경 PNG 에 없는 경우 — 예: page 23 cutter/stopper 같은 단어 박스 — 글상자 fill 로 표시.)
            try {
                String fillName = tf.fillColor();
                if (fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
                    String fillHex = ctx.resolvedData.resolveColorHex(fillName);
                    if (fillHex != null) {
                        block.fillColor(fillHex);
                        if (tf.fillTint() > 0 && tf.fillTint() <= 100) {
                            block.fillTint((int) tf.fillTint());
                        } else {
                            block.fillTint(100);
                        }
                    }
                }
                if (tf.cornerRadius() > 0) {
                    block.cornerRadius(tf.cornerRadius() * ctx.scaleFactor);
                }
            } catch (Exception eFill) {}

            // overflow 감지용 텍스트 길이 저장
            String visText = tf.frameVisibleText();
            if (visText != null) {
                block.frameVisibleTextLength(visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").length());
            }
            // storyTotalTextLength는 convertStories()에서 설정

            // 타이틀 오버레이로 첫 N 단락 숨김 + 본문 중간의 제외 인덱스 적용
            if (preDetectedSkipParas > 0) {
                block.skipParagraphs(preDetectedSkipParas);
            }
            if (excludedParaIndices != null) {
                for (Integer ex : excludedParaIndices) {
                    block.addExcludedParagraphIndex(ex);
                }
            }

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
        List<ASTTextFrameBlock> createdBlocks = new ArrayList<>();

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
            createdBlocks.add(block);
            section.addBlock(block);
        }

        // 빈 단락(인라인 앵커 전용 등) 흡수: 각 YGap 블록의 absParaEnd를 다음 블록의 absParaStart-1까지 확장
        // 예) g0=[18,18], g1=[20,20] → g0=[18,19], g1=[20,20]
        // 마지막 블록은 tf.paragraphEnd까지 확장
        for (int bi = 0; bi < createdBlocks.size(); bi++) {
            ASTTextFrameBlock cb = createdBlocks.get(bi);
            if (cb.composedCharStart() < 0) continue;
            int curEnd = cb.composedCharEnd();
            int nextStart;
            if (bi + 1 < createdBlocks.size()) {
                ASTTextFrameBlock nb = createdBlocks.get(bi + 1);
                nextStart = nb.composedCharStart() >= 0 ? nb.composedCharStart() : Integer.MAX_VALUE;
            } else {
                nextStart = (tf.paragraphEnd() >= 0) ? tf.paragraphEnd() + 1 : Integer.MAX_VALUE;
            }
            if (nextStart > curEnd + 1 && nextStart != Integer.MAX_VALUE) {
                cb.composedCharEnd(nextStart - 1);
            }
        }

        return true;
    }
}
