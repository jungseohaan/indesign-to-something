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
                    // SPEC-025: inline + editable. Phase 3 처리 분기:
                    // - 자기 텍스트 ≥ 2 자: Phase 3 가 인라인 텍스트 런으로 임베드 → 플로팅 스킵
                    // - 멀티 child 배지 (Phase 3 가 자손 텍스트 결합 ≥ 2자): Phase 3 가 결합 인라인 임베드 → 플로팅 스킵
                    // - 단일 1자 라벨 (예: "1", "예"): Phase 3 가 PNG 임베드 (텍스트 누락) → 플로팅으로 검색 가능 텍스트 보강
                    int domIdInlineEd = -1;
                    try { domIdInlineEd = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                    boolean inAnyBadge = false;
                    boolean inMultiChildBadge = false;
                    int badgeGroupId = -1;
                    if (domIdInlineEd >= 0) {
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedTextFrames()) {
                            if (!rg.isBadgeGroup()) continue;
                            int[] cTfIds = rg.childTextFrameIds();
                            if (cTfIds == null) continue;
                            boolean isChild = false;
                            int editableSiblings = 0;
                            for (int cid : cTfIds) {
                                if (cid == domIdInlineEd) isChild = true;
                                if (ctx.resolvedData.isEditableTextFrame(String.valueOf(cid))) editableSiblings++;
                            }
                            if (isChild) {
                                inAnyBadge = true;
                                badgeGroupId = rg.id();
                                if (editableSiblings >= 2) inMultiChildBadge = true;
                                break;
                            }
                        }
                    }
                    // Phase 3 reachable: 해당 badge_group 이 inline_object 로도 등록되어 있으면
                    // 본문 inline 앵커 처리 경로에서 도달 가능 → tryInlineGroupAsSingleBadge 가 처리.
                    // 그렇지 않으면 (예: 큰 원형 배지 안에 중첩된 작은 배지) Phase 3 가 도달 못 함 →
                    // 플로팅 텍스트박스로 보강해야 검색 가능 텍스트가 살아남음.
                    boolean phase3Reachable = false;
                    if (badgeGroupId >= 0) {
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                            if (rg.id() == badgeGroupId && "inline_object".equals(rg.itemType())) {
                                phase3Reachable = true;
                                break;
                            }
                        }
                    }
                    if (inAnyBadge && phase3Reachable) {
                        String vt0 = tf.frameVisibleText();
                        String cleaned0 = vt0 == null ? "" : vt0.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                        // 자기 텍스트가 ≥1자 또는 멀티 child 배지 → Phase 3 가 인라인 텍스트박스로 임베드 → 플로팅 스킵.
                        if (cleaned0.length() >= 1 || inMultiChildBadge) {
                            continue;
                        }
                    } else if (inAnyBadge) {
                        // Phase 3 unreachable: 멀티-child 배지만 스킵 (Phase 3 tryInlineGroupAsBoxList 가 처리).
                        // 단일-child 배지는 텍스트 길이와 무관하게 플로팅 글상자로 배치.
                        // (이전: ≥2 자 스킵 → "제1항" 같은 레이블이 본문에 인라인 삽입되는 버그)
                        if (inMultiChildBadge) {
                            continue;
                        }
                    }
                    inlineToFloating = true;
                }
            }

            // 다른 TextFrame 안에 중첩된 프레임은 건너뜀 (부모가 배경에 포함)
            if (!inlineToFloating && isNestedInTextFrame(ctx, tf)) {
                continue;
            }

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
                if (!sharedWithEditable) {
                    continue;
                }
            }
            // badge_group_child는 부모 badge PNG에 텍스트가 이미 포함되어 있으므로 글상자 배치 건너뜀 (중복 방지)
            // SPEC-025: editable 로 승격된 frame 은 page_bg 에서 숨겨지므로 PNG 중복 우려 없음 → 건너뛰지 않음
            boolean skipAsBadgeChild = false;
            if (!ctx.resolvedData.isEditableTextFrame(tf.id())) {
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

            // SPEC-025 occlusion: editable 로 승격됐지만 앞쪽(zOrder 작은) 불투명 도형에 가려져
            // PDF 에 안 보이는 TextFrame 은 HWPX 에도 배치하지 않음 (시각 중복 방지).
            // inlineToFloating 프레임은 배지 배경 타원/도형이 같은 그룹 안에 있어 occluder 오탐 가능 → 스킵.
            if (!inlineToFloating && ctx.resolvedData.isEditableTextFrame(tf.id()) && isOccludedByOpaqueShape(ctx, tf)) {
                continue;
            }

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
            if (pageIdx < 0 || pageIdx >= sections.size()) {
                continue;
            }

            // 좌표 계산: geometricBounds는 spread 좌표 (applyScale 후 pt)
            // → page bounds를 빼서 page-relative로 변환
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) {
                continue;
            }

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
            if (w <= 0 || h <= 0) {
                continue;
            }

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
                // X 겹침: 배지가 TF 의 좌측 끝(첫 20%) 영역과 겹칠 때만 shift
                // (배지가 TF 안쪽 깊숙이 있는 경우 — 예: 줄 끝 inline 배지 — 는 shift 금지)
                // 보정 후 너비가 너무 작아지면 스킵
                if (bR > x && bL < x + w * 0.2 && bR < x + w) {
                    // SPEC-025: 인라인 앵커된 작은 배지는 frame 안에 임베드되므로 shift 하지 않음.
                    // 배지가 frame 의 텍스트 흐름 내부에 anchor 되어 있는지 확인.
                    boolean badgeIsInlineAnchored = false;
                    if (childIds != null && tf.storyId() != null) {
                        // 배지의 자식 TextFrame 의 parentStory 가 우리 frame 의 story 또는 그 자식 story 인지 확인
                        for (int cid : childIds) {
                            ResolvedTextFrame childTf = ctx.resolvedData.getTextFrame(String.valueOf(cid));
                            if (childTf == null) continue;
                            if (childTf.isInline()) { badgeIsInlineAnchored = true; break; }
                        }
                    }
                    if (badgeIsInlineAnchored) continue;
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
            // 단, 다른 페이지의 연결 글상자가 있으면 YGap 분할 금지:
            // YGap 블록이 생기면 distributeByComposedCharRange 경로를 타고 연결 글상자 체인 블록들이
            // 단락 배분에서 제외되어 텍스트가 다음 페이지로 흐르지 못하는 버그 발생.
            boolean hasNextPageChain = false;
            if (tf.nextFrameId() != null) {
                ResolvedTextFrame nextTfCheck = ctx.resolvedData.getTextFrame(tf.nextFrameId());
                if (nextTfCheck != null && nextTfCheck.pageIndex() != tf.pageIndex()) {
                    hasNextPageChain = true;
                }
            }
            if (!hasNextPageChain && tf.composedLines() != null && tf.composedLines().size() > 1) {
                // 1) wrap indent 기반 분할 (텍스트가 이미지를 비껴가는 경우)
                // placeByWrapIndent는 Phase 5에서 후처리 (Phase 3 변환 파이프라인 유지)
                // if (placeByWrapIndent(tf, section, pageLeft, pageTop)) continue;
                // 2) Y 점프 기반 분할 (큰 수직 갭이 있는 경우)
                if (placeByYGapSplit(ctx, tf, section, pageLeft, pageTop)) {
                    continue;
                }
            }

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            // SPEC-025: master instance clones use synthetic ids like "2453_pi20" — not pure numeric.
            String _srcId;
            try {
                _srcId = "u" + Integer.toHexString(Integer.parseInt(tf.id()));
            } catch (NumberFormatException nfe) {
                _srcId = "u" + tf.id();  // 그대로 prefix 만 붙임 (Phase 3 가 sourceId parse 시 분기 처리)
            }
            block.sourceId(_srcId);
            // SPEC-025: editable 로 승격된 badge_group_child 는 부모 배지 visual 영역으로 확장 +
            // 배지 자식 도형의 fill/stroke/corner 를 frame 에 복사 (PNG 제거 후 시각 배지 재현).
            // badge_group_child 는 ResolvedDataReader 가 filterOut 해서 allRenderedTextFrames 에 없음
            // → 부모 badge_group 의 childTextFrameIds 에 우리 frame.id 가 있는지 확인
            String _badgeFill = null, _badgeStroke = null;
            double _badgeStrokeW = 0, _badgeCorner = 0;
            boolean _isBadgeChild = false;
            try {
                int domIdInt5 = -1;
                try { domIdInt5 = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                if (domIdInt5 >= 0) {
                    RenderedGroup parentBadge = null;
                    for (RenderedGroup rg : ctx.resolvedData.allRenderedTextFrames()) {
                        if (!rg.isBadgeGroup()) continue;
                        int[] cTfIds = rg.childTextFrameIds();
                        if (cTfIds == null) continue;
                        boolean isChild = false;
                        for (int cid : cTfIds) { if (cid == domIdInt5) { isChild = true; break; } }
                        if (isChild) { parentBadge = rg; break; }
                    }
                    if (parentBadge != null && parentBadge.bounds() != null && parentBadge.bounds().length >= 4) {
                        _isBadgeChild = true;
                        // parentBadge bounds 는 normalizeToPoints 후 이미 pt 이며 extract_indd.jsx 가 이미 page-relative 로
                        // 변환했음 (line 773-779). pageTop/pageLeft 재차감 금지.
                        double[] pb = parentBadge.bounds();
                        double pbT = pb[0];
                        double pbL = pb[1];
                        double pbB = pb[2];
                        double pbR = pb[3];
                        double groupArea = (pbB > pbT && pbR > pbL) ? (pbR - pbL) * (pbB - pbT) : 0;
                        // 배지 내 모든 editable child TF 의 총 면적 합산.
                        // - 합산 > 50%: "simple" 배지 — PNG 를 흰 텍스트박스 로 대체 (1자/jamo 다중 등 포함)
                        // - 합산 < 50%: "illustrated" 배지 (예: 선인장 + 작은 라벨) — PNG 유지 + 라벨 위에 흰 박스 오버레이
                        double sumEditableChildArea = 0;
                        int editableChildCount = 0;
                        int[] cTfIdsAll = parentBadge.childTextFrameIds();
                        if (cTfIdsAll != null) {
                            for (int cid : cTfIdsAll) {
                                if (!ctx.resolvedData.isEditableTextFrame(String.valueOf(cid))) continue;
                                ResolvedTextFrame childTf = ctx.resolvedData.getTextFrame(String.valueOf(cid));
                                double[] cb = childTf == null ? null : childTf.geometricBounds();
                                if (cb == null || cb.length < 4) continue;
                                double carea = Math.abs((cb[3] - cb[1]) * (cb[2] - cb[0]));
                                sumEditableChildArea += carea;
                                editableChildCount++;
                            }
                        }
                        boolean isSimpleBadge = (groupArea > 0 && sumEditableChildArea / groupArea > 0.5);
                        // SPEC-025: extract_indd.jsx 가 PNG export 시 editable TF 를 숨기므로,
                        // illustrated 배지 (예: 선인장, QR, "1" 인라인 등) 자식 텍스트도 Phase 2 가 floating 으로 배치해야 검색 가능.
                        // (이전 PNG 가 텍스트 포함 시 _skipIllustratedBadgeChild 가 중복 방지 → 이제 불필요.)
                        // 배지 구조 검사: 모든 자손 도형(non-TF) 의 수 ≥ 2 면 데코 보존이 필요한 "decorative" 배지.
                        // (예: 단어 이해하기 — Polygon stroke + Rectangle fill 별도, 말하는 이의 소망 — 이중 Oval)
                        // → PNG 유지 + 텍스트만 투명 오버레이.
                        String _badgeGroupIdStr = String.valueOf(parentBadge.id());
                        int totalShapeDescendantCount = 0;
                        for (ResolvedPageItem cpi2 : ctx.resolvedData.pageItems()) {
                            if (cpi2 == null || cpi2.id() == null) continue;
                            String t = cpi2.type();
                            if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t) && !"GraphicLine".equals(t)) continue;
                            // 조상 chain 확인
                            String pid2 = cpi2.parentId();
                            int hops2 = 0;
                            while (pid2 != null && hops2 < 5) {
                                if (_badgeGroupIdStr.equals(pid2)) { totalShapeDescendantCount++; break; }
                                ResolvedPageItem parent2 = ctx.resolvedData.getPageItem(pid2);
                                if (parent2 == null) break;
                                pid2 = parent2.parentId();
                                hops2++;
                            }
                        }
                        boolean isDecorativeBadge = isSimpleBadge && totalShapeDescendantCount >= 2;
                        // (이전: illustrated 배지 _skipIllustratedBadgeChild → 제거됨. PNG 가 텍스트를 더 이상 베이크하지 않음.)
                        if (isDecorativeBadge) {
                            // PNG 유지 (Phase 7 가 배치) + 텍스트만 투명 오버레이 (검색 가능 + 시각 데코 보존).
                            // simpleBadgeChild 표시 안 함 → Phase 7 가 PNG 를 건너뛰지 않음.
                            isSimpleBadge = false;
                        }
                        // 단일 child 가 그룹을 거의 채우면 bounds 를 그룹 전체로 확장 (스크리블 배지 visual 흡수).
                        // 다중 child 는 각자 자기 bounds 유지 (확장하면 서로 겹침).
                        boolean expandToGroup = isSimpleBadge && editableChildCount == 1
                                && pbB > pbT && pbR > pbL;
                        if (expandToGroup) {
                            x = pbL; y = pbT;
                            w = pbR - pbL; h = pbB - pbT;
                        }
                        if (isSimpleBadge) {
                            // 단순 배지의 모든 editable 자식을 simple 로 표시 → Phase 7 에서 PNG 한 번만 건너뜀.
                            if (cTfIdsAll != null) {
                                for (int cid : cTfIdsAll) {
                                    if (ctx.resolvedData.isEditableTextFrame(String.valueOf(cid))) {
                                        ctx.resolvedData.markSimpleBadgeChild(String.valueOf(cid));
                                    }
                                }
                            }
                        }
                        // 흰 배경: simple 배지만 적용 (PNG visual 을 텍스트박스가 완전히 대체).
                        // illustrated 배지는 PNG 가 유지되므로 흰 박스가 PNG 텍스트와 시각 충돌 → 투명 유지하고
                        // editable 텍스트만 PNG 의 글자 위에 오버레이 (검색 가능).
                        if (isSimpleBadge) {
                            _badgeFill = "Paper";
                        }
                        if (isSimpleBadge) {
                            // 단순 배지: fill / stroke / cornerRadius 를 각각 가장 적합한 sibling 도형에서 추출.
                            // - fill: fill 을 가진 가장 큰 도형
                            // - stroke: stroke 를 가진 가장 큰 도형 (별도 추적)
                            // - cornerRadius: fill 또는 stroke 도형 중 가장 큰 것
                            String parentBadgeIdStr = String.valueOf(parentBadge.id());
                            double bestFillArea = 0, bestStrokeArea = 0, bestAnyArea = 0;
                            for (ResolvedPageItem cpi : ctx.resolvedData.pageItems()) {
                                if (cpi == null || cpi.id() == null) continue;
                                String pid = cpi.parentId();
                                boolean isInBadge = false;
                                int hops = 0;
                                while (pid != null && hops < 5) {
                                    if (parentBadgeIdStr.equals(pid)) { isInBadge = true; break; }
                                    ResolvedPageItem parent = ctx.resolvedData.getPageItem(pid);
                                    if (parent == null) break;
                                    pid = parent.parentId();
                                    hops++;
                                }
                                if (!isInBadge) continue;
                                if (cpi.id().equals(tf.id())) continue;
                                String ctype = cpi.type();
                                if (!"Rectangle".equals(ctype) && !"Polygon".equals(ctype) && !"Oval".equals(ctype) && !"TextFrame".equals(ctype)) continue;
                                String fcn = cpi.fillColorName();
                                String scn = cpi.strokeColorName();
                                boolean hasFill = fcn != null && !"None".equals(fcn) && !"[None]".equals(fcn);
                                boolean hasStroke = scn != null && !"None".equals(scn) && !"[None]".equals(scn) && cpi.strokeWeight() > 0;
                                if (!hasFill && !hasStroke) continue;
                                double[] cgb = cpi.geometricBounds();
                                if (cgb == null || cgb.length < 4) continue;
                                double carea = Math.abs((cgb[3] - cgb[1]) * (cgb[2] - cgb[0]));
                                if (hasFill && carea > bestFillArea) {
                                    bestFillArea = carea;
                                    _badgeFill = fcn;
                                }
                                if (hasStroke && carea > bestStrokeArea) {
                                    bestStrokeArea = carea;
                                    _badgeStroke = scn;
                                    _badgeStrokeW = cpi.strokeWeight();
                                }
                                if (carea > bestAnyArea) {
                                    bestAnyArea = carea;
                                    _badgeCorner = cpi.cornerRadius();
                                }
                            }
                        }
                    }
                }
            } catch (Exception eBadge) {}
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
                // SPEC-025 일러스트 배지: PNG 가 유지되는 경우 그 위에 라벨 텍스트박스가 와야 함.
                // simpleBadgeChild 가 아닌 editable badge_group 자식 → Phase 7 에서 PNG 배치 → 텍스트 z 를 PNG z + 1 로.
                // 단, 인라인-앵커 배지 (inline_object 로도 등록된) 는 PNG 가 body TF 내부에 inline 으로 들어가므로
                // 이 override 를 적용하면 body TF z(>10) 보다 작아져 가려짐 → 원래 tf.zOrder() 유지.
                if (ctx.resolvedData.isEditableTextFrame(tf.id())
                        && !ctx.resolvedData.isSimpleBadgeChild(tf.id())) {
                    int domIdInt6 = -1;
                    try { domIdInt6 = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                    if (domIdInt6 >= 0) {
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedTextFrames()) {
                            if (!rg.isBadgeGroup()) continue;
                            int[] cTfIds = rg.childTextFrameIds();
                            if (cTfIds == null) continue;
                            boolean isChild = false;
                            for (int cid : cTfIds) { if (cid == domIdInt6) { isChild = true; break; } }
                            if (!isChild) continue;
                            // 인라인-앵커 배지 검사: 같은 ID 가 renderedFloatingItems 에 inline_object 로 등록?
                            boolean badgeIsInlineAnchored = false;
                            for (RenderedGroup rg2 : ctx.resolvedData.allRenderedFloatingItems()) {
                                if (rg2.id() == rg.id() && "inline_object".equals(rg2.itemType())) {
                                    badgeIsInlineAnchored = true;
                                    break;
                                }
                            }
                            if (badgeIsInlineAnchored) break; // tf.zOrder() 그대로 사용
                            int badgeHwpxZ = (rg.zOrder() > 0) ? Math.max(10000 - rg.zOrder(), 10) : 10;
                            tfZ = badgeHwpxZ + 1;
                            break;
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
            // SPEC-025: 배지 자식 (badge_group child) 텍스트는 HWPX cell-height vs fontSize 의 좁은 여유 때문에
            // BOTTOM_ALIGN 적용 시 텍스트가 셀 밖으로 밀려나는 현상 발생 → CENTER_ALIGN 으로 강제하여 안정 배치.
            if (_isBadgeChild) {
                block.verticalJustification("CENTER_ALIGN");
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
                // SPEC-025: badge_group_child 의 styling 을 frame 에 복사 (PNG 제거 대신 styled box 로 렌더)
                if (_badgeFill != null) {
                    String fh = ctx.resolvedData.resolveColorHex(_badgeFill);
                    if (fh != null) {
                        block.fillColor(fh);
                        block.fillTint(100);
                    }
                    // _badgeFill 이 "Paper" 일 때만 cornerRadius=0 (illustrated 배지 흰 오버레이 — pill 방지).
                    // sibling 도형에서 색 fill 을 가져온 경우 (simple 배지 컬러 pill) 는 tf.cornerRadius 유지.
                    if ("Paper".equals(_badgeFill)) {
                        block.cornerRadius(0);
                    }
                }
                if (_badgeStroke != null && _badgeStrokeW > 0) {
                    String sh = ctx.resolvedData.resolveColorHex(_badgeStroke);
                    if (sh != null) {
                        block.strokeColor(sh);
                        block.strokeWeight(_badgeStrokeW);
                    }
                }
                if (_badgeCorner > 0 && block.cornerRadius() == 0) {
                    block.cornerRadius(_badgeCorner * ctx.scaleFactor);
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

    /**
     * SPEC-025 occlusion 감지: editable 로 승격됐지만 앞쪽(InDesign zOrder 작은) 불투명 도형에
     * 텍스트 영역이 완전히 가려진 TextFrame 은 PDF 에 보이지 않는다 — HWPX 에도 배치하지 않음.
     * 같은 페이지의 zOrder 작은 도형들 중 fill 이 있고 bounds 가 텍스트 라인 bounds 를 포함하는지 확인.
     */
    private static boolean isOccludedByOpaqueShape(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (tf == null) return false;
        // 텍스트 위치: composedLines 가 있으면 line bounds union, 없으면 frame bounds
        double textTop, textLeft, textBottom, textRight;
        if (tf.composedLines() != null && !tf.composedLines().isEmpty()) {
            textTop = Double.MAX_VALUE; textLeft = Double.MAX_VALUE;
            textBottom = -Double.MAX_VALUE; textRight = -Double.MAX_VALUE;
            for (ResolvedTextFrame.ComposedLine cl : tf.composedLines()) {
                double[] b = cl.bounds();
                if (b == null || b.length < 4) continue;
                if (b[0] < textTop) textTop = b[0];
                if (b[1] < textLeft) textLeft = b[1];
                if (b[2] > textBottom) textBottom = b[2];
                if (b[3] > textRight) textRight = b[3];
            }
            if (textTop == Double.MAX_VALUE) return false;
        } else {
            double[] b = tf.geometricBounds();
            if (b == null || b.length < 4) return false;
            textTop = b[0]; textLeft = b[1]; textBottom = b[2]; textRight = b[3];
        }
        // 본인 zOrder 조회
        ResolvedPageItem selfPi = ctx.resolvedData.getPageItem(tf.id());
        if (selfPi == null) return false;
        int selfZ = selfPi.zOrder();
        int selfPage = tf.pageIndex();
        // 본인의 ancestor 체인 수집 — ancestor 도형은 자식을 가릴 수 없음 (visual container 패턴)
        java.util.Set<String> ancestorIds = new java.util.HashSet<>();
        String pid = selfPi.parentId();
        int hops = 0;
        while (pid != null && hops < 10) {
            ancestorIds.add(pid);
            ResolvedPageItem par = ctx.resolvedData.getPageItem(pid);
            if (par == null) break;
            pid = par.parentId();
            hops++;
        }
        List<ResolvedPageItem> items = ctx.resolvedData.pageItems();
        if (items == null) return false;
        for (ResolvedPageItem pi : items) {
            if (pi == null) continue;
            if (pi.pageIndex() != selfPage) continue;
            if (pi.zOrder() >= selfZ) continue;  // 같거나 뒤쪽 도형은 가릴 수 없음 (InDesign: 작은 zOrder = 앞)
            // ancestor 도형은 자식 TextFrame 의 컨테이너 — 가린다고 보지 않음
            if (pi.id() != null && ancestorIds.contains(pi.id())) continue;
            String t = pi.type();
            // 불투명 도형: Rectangle/Polygon/Oval + fillColor 가 None 이 아님
            if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t)) continue;
            String fc = pi.fillColorName();
            if (fc == null || "None".equals(fc) || "[None]".equals(fc)) continue;
            // opacity 가 50 미만이면 반투명 → 텍스트 보임
            if (pi.opacity() < 50) continue;
            double[] sb = pi.geometricBounds();
            if (sb == null || sb.length < 4) continue;
            // bounds 가 텍스트 영역을 포함하는지 확인 (1pt 여유)
            if (sb[0] <= textTop + 1 && sb[1] <= textLeft + 1
                    && sb[2] >= textBottom - 1 && sb[3] >= textRight - 1) {
                // 같은 크기의 배경 도형(말풍선 등)은 occluder 가 아니라 텍스트의 배경 → 제외.
                // shape 의 너비/높이가 텍스트 영역의 1.2 배 이상일 때만 진짜 occluder 로 간주.
                double shapeW = sb[3] - sb[1];
                double shapeH = sb[2] - sb[0];
                double textW = textRight - textLeft;
                double textH = textBottom - textTop;
                if (textW > 0 && textH > 0 && shapeW < textW * 1.2 && shapeH < textH * 1.2) {
                    continue;
                }
                return true;
            }
        }
        return false;
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

        String sourceIdBase;
        try {
            sourceIdBase = "u" + Integer.toHexString(Integer.parseInt(tf.id()));
        } catch (NumberFormatException nfe) {
            sourceIdBase = "u" + tf.id();
        }
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
