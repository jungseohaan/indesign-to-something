package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
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

        // 공간 포함 감지: inner TF가 outer TF bounds 안에 완전히 들어가고 다른 story를 가진 경우.
        // InDesign "밑줄 빈칸 + 예시 답안 오버레이" 패턴: inner TF는 outer TF의 마지막 단락에 주입.
        // key=inner TF id (decimal), value=outer TF id (decimal)
        java.util.Map<String, String> innerToOuterMap = new java.util.HashMap<>();
        {
            final double TOL = 1.0; // pt 허용 오차
            // Pass 1: 각 inner TF에 대해 포함하는 outer TF 후보를 모두 수집
            java.util.Map<String, java.util.List<ResolvedTextFrame>> innerCandidates = new java.util.HashMap<>();
            for (ResolvedTextFrame outer : frames) {
                if (outer.storyId() == null) continue;
                double[] aGb = outer.geometricBounds();
                if (aGb == null || aGb.length < 4) continue;
                for (ResolvedTextFrame inner : frames) {
                    if (inner == outer) continue;
                    if (inner.storyId() == null || inner.storyId().equals(outer.storyId())) continue;
                    if (inner.previousFrameId() != null) continue; // chain 후속 프레임 제외
                    if (inner.pageIndex() != outer.pageIndex()) continue;
                    if (inner.onHiddenLayer() || outer.onHiddenLayer()) continue;
                    // inline TF는 Phase 3 인라인 앵커 처리 대상 → 공간 포함 inner 집계에서 제외
                    // (inline TF가 count를 올려 Pass 3에서 실제 텍스트 inner TF까지 제거되는 문제 방지)
                    if (inner.isInline()) continue;
                    // outer에 가시 텍스트가 없으면 배경/장식 프레임 → 오버레이 패턴 아님
                    String _outerVis = outer.frameVisibleText();
                    if (_outerVis == null || _outerVis.trim().isEmpty()) continue;
                    double[] bGb = inner.geometricBounds();
                    if (bGb == null || bGb.length < 4) continue;
                    // inner가 outer bounds 안에 완전히 포함
                    if (bGb[0] >= aGb[0] - TOL && bGb[1] >= aGb[1] - TOL
                            && bGb[2] <= aGb[2] + TOL && bGb[3] <= aGb[3] + TOL) {
                        // badge_group_child는 Phase 7이 처리 → 포함 감지 제외
                        int innerDomId = -1;
                        try { innerDomId = Integer.parseInt(inner.id()); } catch (NumberFormatException e) {}
                        boolean isBadgeChild = false;
                        if (innerDomId >= 0) {
                            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                                if (rg.id() == innerDomId && "badge_group_child".equals(rg.itemType())) {
                                    isBadgeChild = true; break;
                                }
                            }
                        }
                        if (!isBadgeChild) {
                            innerCandidates.computeIfAbsent(inner.id(), k -> new java.util.ArrayList<>()).add(outer);
                        }
                    }
                }
            }
            // Pass 2: 각 inner에 대해 가장 작은(가장 내부) outer 선택
            for (java.util.Map.Entry<String, java.util.List<ResolvedTextFrame>> e : innerCandidates.entrySet()) {
                ResolvedTextFrame bestOuter = null;
                double minArea = Double.MAX_VALUE;
                for (ResolvedTextFrame outer : e.getValue()) {
                    double[] ob = outer.geometricBounds();
                    double area = (ob[2] - ob[0]) * (ob[3] - ob[1]);
                    if (area < minArea) { minArea = area; bestOuter = outer; }
                }
                if (bestOuter != null) innerToOuterMap.put(e.getKey(), bestOuter.id());
            }
            // Pass 3: outer 하나에 inner가 여러 개이면 오버레이 패턴이 아님 → 모두 제거
            java.util.Map<String, Integer> outerInnerCount = new java.util.HashMap<>();
            for (String outerId : innerToOuterMap.values()) {
                outerInnerCount.merge(outerId, 1, Integer::sum);
            }
            innerToOuterMap.entrySet().removeIf(e -> outerInnerCount.getOrDefault(e.getValue(), 0) > 1);
            // Pass 4: outer로 사용되는 프레임은 inner로 스킵하지 않음 (계층 유지)
            java.util.Set<String> usedAsOuter = new java.util.HashSet<>(innerToOuterMap.values());
            innerToOuterMap.keySet().removeIf(usedAsOuter::contains);
            if (!innerToOuterMap.isEmpty()) {
                for (java.util.Map.Entry<String, String> e : innerToOuterMap.entrySet()) {
                    System.err.println("[FramePlacer] 공간 포함 오버레이: inner=" + e.getKey()
                            + " ⊂ outer=" + e.getValue());
                }
            }
        }

        for (ResolvedTextFrame tf : frames) {
            // 공간 포함 inner TF: outer TF 단락에 주입될 예정 → 독립 배치 스킵
            if (innerToOuterMap.containsKey(tf.id())) continue;

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
                    // badge_group_child: 부모 inline_object의 childIds에 포함된 경우에만 PNG에 텍스트가 있음.
                    // childIds가 비어있으면 inline PNG는 배경만 캡처 → 텍스트 TF는 별도 플로팅 배치 필요.
                    if (rendered && domIdInt >= 0) {
                        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                            if (rg.id() == domIdInt && "badge_group_child".equals(rg.itemType())) {
                                int parentBadgeId = rg.badgeGroupId();
                                boolean inInlinePng = false;
                                for (RenderedGroup prg : ctx.resolvedData.allRenderedFloatingItems()) {
                                    if (prg.id() == parentBadgeId && "inline_object".equals(prg.itemType())) {
                                        int[] pChildIds = prg.childIds();
                                        if (pChildIds != null) {
                                            for (int pcid : pChildIds) {
                                                if (pcid == domIdInt) { inInlinePng = true; break; }
                                            }
                                        }
                                        break;
                                    }
                                }
                                if (!inInlinePng) rendered = false; // inline PNG에 텍스트 없음 → 플로팅 배치 허용
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
                    // 부모/조상 Group이 inline_object 이거나 inline PNG로 캡처된 경우
                    // → Phase 3가 INLINE_TEXT_FRAME으로 처리 → floating 불필요.
                    // 5 hop 조상 체인을 검사하여 중첩 Group(예: Group18498 → Group18558 → TF18579) 도 처리.
                    if (!rendered && domIdInt >= 0) {
                        ResolvedPageItem _inlPi = ctx.resolvedData.getPageItem(tf.id());
                        String _curParentId = (_inlPi != null) ? _inlPi.parentId() : null;
                        for (int _h = 0; _h < 5 && _curParentId != null && !rendered; _h++) {
                            try {
                                int _pid = Integer.parseInt(_curParentId);
                                if (ctx.resolvedData.isInlineObjectId(_pid)) {
                                    rendered = true;
                                }
                            } catch (NumberFormatException ignored) {}
                            if (!rendered) {
                                // allRenderedFloatingItems 에 inline_ 파일로 등록된 Group → 인라인 앵커 Group
                                for (RenderedGroup _rg : ctx.resolvedData.allRenderedFloatingItems()) {
                                    if (String.valueOf(_rg.id()).equals(_curParentId)
                                            && _rg.file() != null && _rg.file().contains("inline_")) {
                                        rendered = true;
                                        break;
                                    }
                                }
                            }
                            if (!rendered) {
                                ResolvedPageItem _nextPi = ctx.resolvedData.getPageItem(_curParentId);
                                if (_nextPi == null) break;
                                _curParentId = _nextPi.parentId();
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
                    // 부모 Group이 inline_object이면 inline PNG가 시각적 배지 전체를 포함 → floating 불필요.
                    if (domIdInlineEd >= 0) {
                        ResolvedPageItem _tfi2 = ctx.resolvedData.getPageItem(tf.id());
                        if (_tfi2 != null && _tfi2.parentId() != null) {
                            try {
                                int parentDomId = Integer.parseInt(_tfi2.parentId());
                                if (ctx.resolvedData.isInlineObjectId(parentDomId)) continue; // O(1)
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    // inline+editable TF가 floating으로 배치될 예정 → Phase 3 중복 방지
                    // (tryInlineTextFrameAsRun이 TEXT_BLOCK_PLACED 미설정 시 인라인 런도 생성 → 텍스트 2회 출력)
                    if (domIdInlineEd >= 0) {
                        ctx.setDisposition(domIdInlineEd, FrameDisposition.TEXT_BLOCK_PLACED);
                    }
                    inlineToFloating = true;
                }
            }

            // 숨김 레이어 TF (onHiddenLayer=true) → 변환 불필요
            if (tf.onHiddenLayer()) continue;

            // 마스터 인스턴스 TF가 composed되지 않은 경우 (lineCount=0) → 해당 페이지에서 override됨 → skip
            if (tf.isMasterInstance() && tf.lineCount() == 0) continue;

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
                    // non-editable 플로팅 TF 중, 자기 story + 텍스트가 있고 PNG로 렌더됐으며
                    // 부모가 회전된 Rectangle (absoluteRotationAngle≠0)인 경우 텍스트 글상자로 배치.
                    // (예: 오느른/운느라/싸인 — 부모 Rectangle이 비스듬히 기울어진 TF)
                    // Phase 7 PNG 는 이후 ctx.frameDispositions(TEXT_BLOCK_PLACED) 확인 시 건너뜀.
                    String _vis = tf.frameVisibleText();
                    String _visCleaned = (_vis == null) ? "" : _vis.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                    int _domId = -1;
                    try { _domId = Integer.parseInt(tf.id()); } catch (NumberFormatException e) {}
                    boolean _hasOwnText = _visCleaned.length() >= 2;
                    boolean _isRendered = _domId >= 0 && ctx.resolvedData.isRenderedByOtherChannel(_domId);
                    // 부모 Rectangle이 실제로 회전된 경우에만 텍스트 배치 (VectorShape/TextPath 는 제외)
                    boolean _parentIsRotatedRect = false;
                    if (_hasOwnText && _isRendered && !tf.isInline()) {
                        ResolvedPageItem _tfi = ctx.resolvedData.getPageItem(tf.id());
                        if (_tfi != null && _tfi.parentId() != null) {
                            ResolvedPageItem _parent = ctx.resolvedData.getPageItem(_tfi.parentId());
                            if (_parent != null && "Rectangle".equals(_parent.type())
                                    && Math.abs(_parent.absoluteRotationAngle()) > 0.5) {
                                _parentIsRotatedRect = true;
                            }
                        }
                    }
                    // PNG 렌더링 없이 자기 스토리에 텍스트만 있는 non-editable TF:
                    // 조상 Group에 PNG가 없는 경우 텍스트 글상자로 배치 (예: "새로운 단어가..." 글상자)
                    boolean _nonRenderedWithText = false;
                    if (!_parentIsRotatedRect && _hasOwnText && !_isRendered
                            && tf.storyId() != null && !tf.isInline()) {
                        boolean _ancestorHasPng = false;
                        ResolvedPageItem _anPi = ctx.resolvedData.getPageItem(tf.id());
                        String _anPid = (_anPi != null) ? _anPi.parentId() : null;
                        for (int _d = 0; _d < 5 && _anPid != null && !_ancestorHasPng; _d++) {
                            try {
                                int _anPidInt = Integer.parseInt(_anPid);
                                if (ctx.resolvedData.isInlineObjectId(_anPidInt)) { _ancestorHasPng = true; break; }
                                for (RenderedGroup _rg : ctx.resolvedData.allRenderedFloatingItems()) {
                                    if (_rg.id() == _anPidInt && _rg.file() != null) { _ancestorHasPng = true; break; }
                                }
                            } catch (NumberFormatException ignored) {}
                            if (!_ancestorHasPng) {
                                ResolvedPageItem _anParPi = ctx.resolvedData.getPageItem(_anPid);
                                _anPid = (_anParPi != null) ? _anParPi.parentId() : null;
                            }
                        }
                        _nonRenderedWithText = !_ancestorHasPng;
                    }
                    if (_parentIsRotatedRect) {
                        ctx.setDisposition(_domId, FrameDisposition.TEXT_BLOCK_PLACED);
                        // fall through → 글상자로 배치
                    } else if (_nonRenderedWithText) {
                        // fall through → 텍스트 글상자로 배치
                    } else {
                        continue;
                    }
                }
            }
            // badge_group_child(non-editable)는 부모 PNG가 텍스트를 포함하므로 글상자 배치 건너뜀.
            // SPEC-025: editable로 승격된 frame은 !isEditableTextFrame 가드로 보호됨 → 건너뛰지 않음
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
            if (y < 0) {
                double origH = h;
                h += y;
                y = 0;
                // SPEC-025: _oc 해시라 헤더 등 페이지 위쪽 경계선에 위치한 TF (예: y=-8, h=8) 는
                // 클램핑 후 h=0 이 되어 스킵됨 → 원래 높이를 복원해 페이지 상단에 배치.
                if (h <= 0 && origH > 0) h = origH;
            }
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
                            // 인라인 TF(inline badge child 등)는 본문 단락에 앵커된 객체 → 타이틀 오버레이 아님
                            if (_other.isInline()) continue;
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
                            if (paraText.contains(_otherClean)
                                    && _otherClean.length() * 2 >= paraText.length()) {
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
                        // 형제의 상단이 TF 상단에서 절반 이상 아래에 있으면 장식 요소(말풍선 꼬리 등) →
                        // 텍스트 옆으로 shift 금지 (형제가 TF 하단부에만 겹치면 사이드 라벨 패턴이 아님)
                        if ((sbT - y) >= h * 0.5) continue;
                        // 형제의 Y-extent 가 TF 높이의 1.3배 이상이면 배경 도형(TF를 감싸는 container) →
                        // 텍스트가 배경 위에 올라타는 패턴이므로 shift 금지
                        if (sibYExt > h * 1.3) continue;
                        // 형제의 Y-extent 가 TF 높이의 20% 미만이면 얇은 장식 줄(horizontal strip) →
                        // 텍스트가 위에 올라타는 패턴이므로 shift 금지
                        if (sibYExt < h * 0.20) continue;
                        // 첫 composedLine 텍스트가 형제 도형의 X 범위 안에서 시작하면 배경 도형 → shift 금지
                        // (예: 연두 배경 위 "자신이 고른 인물")
                        if (tf.composedLines() != null && !tf.composedLines().isEmpty()) {
                            double[] clBounds = tf.composedLines().get(0).bounds();
                            if (clBounds != null && clBounds.length >= 4) {
                                double clLeft = clBounds[1] - pageLeft;
                                if (clLeft >= sbL - 4.0 && clLeft <= sbR + 4.0) continue;
                            }
                        }
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
            // ￼ 로 시작하는 TF 첫줄에 inline TF가 좌측 가장자리를 공유하는 경우
            // (예: 단락 번호 "1" TF가 본문 TF 왼쪽에 맞닿음) → x를 inline TF 너비만큼 오른쪽으로 이동
            {
                String _fvtInl = tf.frameVisibleText();
                if (_fvtInl != null && _fvtInl.startsWith("￼")) {
                    for (ResolvedTextFrame _itf : frames) {
                        if (!_itf.isInline() || _itf.pageIndex() != tf.pageIndex()) continue;
                        double[] _igb = _itf.geometricBounds();
                        if (_igb == null || _igb.length < 4) continue;
                        double _iWidth = _igb[3] - _igb[1];
                        if (_iWidth < 8.5) continue; // 3mm 미만은 무시
                        // 인라인 TF 왼쪽이 이 TF의 왼쪽 spread 좌표와 일치 (5pt 허용)
                        if (Math.abs(_igb[1] - gb[1]) > 5.0) continue;
                        // 인라인 TF top이 이 TF top보다 위 또는 거의 같고, bottom이 이 TF top 이하이면 무관
                        double _iTfTop = gb[0] - pageTop;
                        if (_igb[0] - pageTop > _iTfTop + 5.0) continue;
                        if (_igb[2] - pageTop < _iTfTop) continue;
                        double _origX = x;
                        x += _iWidth;
                        w -= _iWidth;
                        if (w <= 0) { x = _origX; w = gb[3] - gb[1]; }
                        break;
                    }
                }
            }
            block.x(CoordinateConverter.pointsToHwpunits(x));
            block.y(CoordinateConverter.pointsToHwpunits(y));
            block.width(CoordinateConverter.pointsToHwpunits(w));
            block.height(CoordinateConverter.pointsToHwpunits(h));
            // 부모 Group이 Phase7 렌더 PNG로 배치되면 그 위에 올라가야 함.
            // Phase7은 zOrder=10000-pageItemIdx 로 역매핑하므로 동일한 방식으로 계산하여
            // 부모 PNG 바로 위에 배치한다.
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
            // composedLines 로 실제 텍스트 위치를 확인: TOP_ALIGN 이지만 텍스트가 프레임 상단에서
            // 15% 이상 아래에 있으면 InDesign 이 inset/padding 으로 시각적 중앙 정렬을 구현한 것.
            // HWPX TOP_ALIGN 은 inset 을 무시하므로 CENTER_ALIGN 으로 보정.
            if ("TOP_ALIGN".equals(block.verticalJustification())
                    && tf.composedLines() != null && !tf.composedLines().isEmpty()
                    && tf.geometricBounds() != null && tf.geometricBounds().length >= 4) {
                double frameH = tf.geometricBounds()[2] - tf.geometricBounds()[0];
                double lineTop = tf.composedLines().get(0).bounds() != null
                        ? tf.composedLines().get(0).bounds()[0] : tf.geometricBounds()[0];
                double topOffset = lineTop - tf.geometricBounds()[0];
                if (frameH > 0 && topOffset / frameH > 0.15) {
                    block.verticalJustification("CENTER_ALIGN");
                }
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
                // TF 자체 strokeColor/strokeWeight 복사 (배지 자식 override 이전 기본값).
                // fillColor와 동일한 방식: page_bg에 border가 없는 editable 텍스트박스는
                // TF stroke로 HWPX 테두리를 그려야 시각적으로 border가 보임.
                if (tf.strokeColor() != null && !"None".equals(tf.strokeColor()) && !"[None]".equals(tf.strokeColor())
                        && tf.strokeWeight() > 0) {
                    String strokeHex = ctx.resolvedData.resolveColorHex(tf.strokeColor());
                    if (strokeHex != null) {
                        block.strokeColor(strokeHex);
                        block.strokeWeight(tf.strokeWeight());
                    }
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

            if (inlineToFloating) {
                block.inlineToFloating(true);
                // Case 1 (non-editable inline TF with text): 조상 inline_object PNG 를
                // inline 배치에서 억제하고 Phase 7 이 floating 으로 재배치하도록 등록.
                // Case 2 (editable badge child)는 badge PNG 가 inline 앵커 그대로 유지되어야 하므로 제외.
                // Case 3 (non-editable TF inside inline_object): inline_object PNG가 이미 inline으로 배치됨.
                //   floating text 오버레이는 생성하되, inline_object는 floating 전환 금지.
                //   (전환하면 body text가 밀리지 않고 겹침)
                if (!ctx.resolvedData.isEditableTextFrame(tf.id())) {
                    ResolvedPageItem _ancCurPi = ctx.resolvedData.getPageItem(tf.id());
                    int _ancHops = 0;
                    outer_anc:
                    while (_ancCurPi != null && _ancHops < 10) {
                        String _ancPid = _ancCurPi.parentId();
                        if (_ancPid == null) break;
                        for (RenderedGroup _ancRg : ctx.resolvedData.allRenderedFloatingItems()) {
                            if (String.valueOf(_ancRg.id()).equals(_ancPid)
                                    && "inline_object".equals(_ancRg.itemType())) {
                                // inline_object PNG가 있으면 inline 배치 유지 → floating 전환 등록 안 함.
                                // TF는 floating text box로 배치되어 PNG 위에 텍스트 오버레이 역할을 한다.
                                if (_ancRg.file() != null) break outer_anc;
                                ctx.setDisposition(_ancRg.id(), FrameDisposition.PNG_CONVERT_TO_FLOATING);
                                ctx.inlineObjectTfPageIndex.put(_ancRg.id(), tf.pageIndex());
                                break outer_anc;
                            }
                        }
                        _ancCurPi = ctx.resolvedData.getPageItem(_ancPid);
                        _ancHops++;
                    }
                }
            }
            // 공간 포함 outer TF: 자신 안에 포함된 inner TF id 기록 → StoryConverter가 inner story 주입
            for (java.util.Map.Entry<String, String> _inner : innerToOuterMap.entrySet()) {
                if (_inner.getValue().equals(tf.id())) {
                    block.innerFrameId(_inner.getKey());
                    break;
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
            // opacity 50 이하면 반투명 → 텍스트 가림으로 보지 않음 (50% 이하는 배경이 충분히 비침)
            if (pi.opacity() <= 50) continue;
            // fillTint 50 미만 → 매우 연한 색 → 실질적 가림 아님
            if (pi.fillTint() >= 0 && pi.fillTint() < 50) continue;
            // 10도 이상 회전된 도형은 AABB가 실제 채움 영역보다 크게 과대평가 → occluder 제외
            if (Math.abs(pi.absoluteRotationAngle()) > 10) continue;
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
                // Polygon/Oval 은 불규칙 패스일 수 있어 AABB 가 실제 채움 영역보다 훨씬 큼.
                // AABB 면적이 텍스트 면적의 50 배 초과이면 스크리블/장식 패스로 간주 → occluder 제외.
                if (!"Rectangle".equals(t) && textW > 0 && textH > 0) {
                    double shapeArea = shapeW * shapeH;
                    double textArea = textW * textH;
                    if (shapeArea > textArea * 50.0) continue;
                    // AABB 가 페이지 크기의 2 배 이상이면 페이지 밖으로 뻗은 장식 스윕 패스 → occluder 제외.
                    // (예: 첫 페이지 배경 웨이브 Polygon 이 x: -100mm~502mm 로 페이지 220mm 를 훨씬 초과)
                    // pageBounds 는 normalizeToPoints() 후 이미 pt — scaleFactor 추가 곱셈 금지.
                    try {
                        java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage> pages =
                                ctx.resolvedData.pages();
                        if (selfPage < pages.size()) {
                            double[] pageBounds = pages.get(selfPage).bounds();
                            if (pageBounds != null && pageBounds.length >= 4) {
                                double pageW = pageBounds[3] - pageBounds[1];
                                double pageH = pageBounds[2] - pageBounds[0];
                                if ((pageW > 0 && shapeW > pageW * 2.0) || (pageH > 0 && shapeH > pageH * 2.0)) {
                                    continue;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
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
        int n = groups.size();
        int tfParaStart = tf.paragraphStart();
        double[] tfGb = tf.geometricBounds();

        // Step 1: 각 그룹의 단락 범위 사전 계산
        int[] absParaStarts = new int[n];
        int[] absParaEnds = new int[n];
        for (int gi = 0; gi < n; gi++) {
            int s = Integer.MAX_VALUE, e = -1;
            for (ResolvedTextFrame.ComposedLine cl : groups.get(gi)) {
                int pi = cl.paraIndex();
                if (pi >= 0) {
                    int abs = tfParaStart + pi;
                    if (abs < s) s = abs;
                    if (abs > e) e = abs;
                }
            }
            absParaStarts[gi] = s;
            absParaEnds[gi] = e;
        }

        // Step 2: 빈 단락 갭 흡수 (createdBlocks 2차 순회 제거)
        for (int bi = 0; bi < n; bi++) {
            if (absParaStarts[bi] == Integer.MAX_VALUE) continue;
            int nextStart = (bi + 1 < n && absParaStarts[bi + 1] != Integer.MAX_VALUE)
                    ? absParaStarts[bi + 1]
                    : (tf.paragraphEnd() >= 0 ? tf.paragraphEnd() + 1 : Integer.MAX_VALUE);
            if (nextStart > absParaEnds[bi] + 1 && nextStart != Integer.MAX_VALUE) {
                absParaEnds[bi] = nextStart - 1;
            }
        }

        // Step 3: 블록 생성 (단일 패스)
        for (int gi = 0; gi < n; gi++) {
            List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);

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
            double minTop = Double.MAX_VALUE, maxBottom = -Double.MAX_VALUE;
            int groupCharCount = 0;
            StringBuilder groupText = new StringBuilder();
            for (int li = 0; li < group.size(); li++) {
                ResolvedTextFrame.ComposedLine line = group.get(li);
                double[] b = line.bounds();
                if (b == null) continue;
                if (li >= firstSubstantive && b[0] < minTop) minTop = b[0];
                if (li <= lastSubstantive && b[2] > maxBottom) maxBottom = b[2];
                if (line.text() != null) groupCharCount += line.text().length();
                if (li >= firstSubstantive && li <= lastSubstantive && line.text() != null)
                    groupText.append(line.text());
            }

            double gx = tfGb[1] - pageLeft;
            double gy = minTop - pageTop;
            double gw = tfGb[3] - tfGb[1];
            double gh = maxBottom - minTop;

            if (gx < 0) { gw += gx; gx = 0; }
            if (gy < 0) { gh += gy; gy = 0; }
            if (gw <= 0 || gh <= 0) continue;

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            block.sourceId(sourceIdBase + (n > 1 ? "_g" + gi : ""));
            block.x(CoordinateConverter.pointsToHwpunits(gx));
            block.y(CoordinateConverter.pointsToHwpunits(gy));
            block.width(CoordinateConverter.pointsToHwpunits(gw));
            block.height(CoordinateConverter.pointsToHwpunits(gh));
            block.zOrder(tf.zOrder());
            block.storyId(tf.storyId());
            block.distributed(true);
            block.frameVisibleTextLength(groupCharCount);
            block.frameVisibleText(groupText.toString());
            if (absParaStarts[gi] != Integer.MAX_VALUE) {
                block.composedCharStart(absParaStarts[gi]);
                block.composedCharEnd(absParaEnds[gi]);
            }
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
                block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
                block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
                block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
            }
            section.addBlock(block);
        }

        return true;
    }
}
