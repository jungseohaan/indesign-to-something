package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPEC-013 Phase 2: TextFrame 분류 + 좌표 → 페이지 배치.
 *
 * <p>{@code ResolvedToASTBuilder.placeTextFrames + placeByYGapSplit + isNestedInTextFrame}
 * 에서 stateless static helper로 발췌. 동작은 동일.</p>
 */
public final class FramePlacer {

    private FramePlacer() {}

    // ---- 튜닝 상수 --------------------------------------------------------
    /** 임시 비활성화: occlusion 오탐이 많아 TF 누락을 유발하므로 필터를 끈다. */
    private static final boolean ENABLE_OCCLUSION_FILTER = false;
    /** TOP_ALIGN → CENTER_ALIGN 보정: 텍스트가 프레임 높이의 이 비율 이상 아래에 있을 때 */
    private static final double TOP_ALIGN_OFFSET_RATIO = 0.15;
    /** 원본에서 한 줄인 라벨/제목형 짧은 문장은 HWP 폰트폭 차이로 두 줄이 되지 않도록 SQUEEZE를 적용한다. */
    private static final int SHORT_SINGLE_LINE_NO_WRAP_CHARS = 32;
    /** isOccludedByOpaqueShape: shape 크기가 텍스트 영역의 이 배수 미만이면 배경 도형으로 간주 */
    private static final double OCCLUDER_SIZE_RATIO = 1.2;
    /** isOccludedByOpaqueShape: Polygon/Oval AABB 면적이 텍스트 면적의 이 배수 초과이면 스크리블 */
    private static final double SCRIBBLE_AREA_RATIO = 50.0;
    /** placeByYGapSplit: 중앙값 행간의 이 배수 초과 간격이면 분할 지점으로 판단 */
    private static final double YGAP_SPLIT_FACTOR = 3.0;
    /** 공간 포함 감지 허용 오차 (pt) */
    private static final double CONTAINMENT_TOL_PT = 1.0;
    /** occlusion 감지 bounds 여유 (pt) */
    private static final double OCCLUSION_BOUNDS_TOL_PT = 1.0;
    /** 연결 글상자 체인 병합: Y 간격이 행 높이의 이 배수 이내면 동일 체인으로 간주 */
    private static final double CHAIN_GAP_RATIO     = 0.5;
    /** 연결 글상자 체인 병합: X 겹침 비율이 이 값 이상이어야 같은 컬럼으로 간주 */
    private static final double CHAIN_X_OVERLAP_MIN = 0.5;
    /** title overlay 감지: Y 겹침이 두 TF 높이 최솟값의 이 비율 이상이어야 오버레이로 간주 */
    private static final double OVERLAY_Y_OVERLAP_MIN  = 0.5;
    /** title overlay 감지: 다른 TF 너비의 이 비율 이상이 단락 영역 안에 들어와야 오버레이로 간주 */
    private static final double OVERLAY_X_COVERAGE_MIN = 0.8;
    /** X-shift 형제 감지: 형제 Y-extent 가 TF 높이의 이 배수 이상이면 배경 컨테이너 → shift 금지 */
    private static final double XSHIFT_CONTAINER_HEIGHT_RATIO = 1.3;
    /** X-shift 형제 감지: 형제 Y-extent 가 TF 높이의 이 배수 미만이면 얇은 장식 줄 → shift 금지 */
    private static final double XSHIFT_STRIP_HEIGHT_RATIO     = 0.20;
    /** X-shift 적용 후 남은 너비가 이 pt 미만이면 shift 취소 */
    private static final double XSHIFT_MIN_REMAIN_PT          = 20.0;
    // -----------------------------------------------------------------------

    public static void placeTextFrames(ResolvedBuildContext ctx, List<ASTSection> sections) {
        List<ResolvedTextFrame> frames = ctx.resolvedData.textFrames();

        FrameIndex idx = buildIndex(ctx.resolvedData.allRenderedFloatingItems(), frames, ctx);

        // FP-B: title overlay 및 inline Y-조정 내부 루프에서 같은 페이지 TF만 검색하도록
        // pageIndex → TF 목록 사전 구축 (O(N²) → O(N) per TF)
        Map<Integer, List<ResolvedTextFrame>> framesByPage = new HashMap<>();
        Map<Integer, List<ResolvedTextFrame>> inlineFramesByPage = new HashMap<>();
        for (ResolvedTextFrame _f : frames) {
            framesByPage.computeIfAbsent(_f.pageIndex(), k -> new ArrayList<>()).add(_f);
            if (_f.isInline()) inlineFramesByPage.computeIfAbsent(_f.pageIndex(), k -> new ArrayList<>()).add(_f);
        }

        // 공간 포함 감지: inner TF가 outer TF bounds 안에 완전히 들어가고 다른 story를 가진 경우.
        // InDesign "밑줄 빈칸 + 예시 답안 오버레이" 패턴: inner TF는 outer TF의 마지막 단락에 주입.
        // key=inner TF id (decimal), value=outer TF id (decimal)
        ContainmentMaps cm = buildContainmentMap(frames, idx.badgeChildDomIds);

        for (ResolvedTextFrame tf : frames) {
            // 공간 포함 inner TF: outer TF 단락에 주입될 예정 → 독립 배치 스킵
            if (cm.innerToOuter.containsKey(tf.id())) {
                continue;
            }

            int tfDomId = parseDomIdOrNeg(tf.id());
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) {
                continue;
            }

            // 인라인 프레임은 Phase 3에서 처리
            // 단, non-editable + non-rendered + story 미공유 인라인이면 플로팅 전환
            InlineToFloatingReason inlineToFloatingReason = InlineToFloatingReason.NONE;
            if (tf.isInline()) {
                if (!ctx.resolvedData.isEditableTextFrame(tf.id())) {
                    String vis = tf.frameVisibleText();
                    boolean hasText = vis != null && vis.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 1;
                    boolean rendered = tfDomId >= 0 && ctx.resolvedData.isRenderedByOtherChannel(tfDomId);
                    // badge_group_child: 부모 inline_object의 childIds에 포함된 경우에만 PNG에 텍스트가 있음.
                    // childIds가 비어있으면 inline PNG는 배경만 캡처 → 텍스트 TF는 별도 플로팅 배치 필요.
                    if (rendered && tfDomId >= 0 && idx.badgeChildDomIds.contains(tfDomId)) {
                        Integer parentBadgeId = idx.badgeChildToParentId.get(tfDomId);
                        boolean inInlinePng = false;
                        if (parentBadgeId != null) {
                            int[] pChildIds = idx.inlineObjectChildIdsMap.get(parentBadgeId);
                            if (pChildIds != null) {
                                for (int pcid : pChildIds) {
                                    if (pcid == tfDomId) { inInlinePng = true; break; }
                                }
                            }
                        }
                        if (!inInlinePng) rendered = false;
                    }
                    // inline_object의 childIds 에 포함된 TextFrame 은 부모 PNG 에 시각적으로
                    // 이미 텍스트가 포함됨 → 플로팅 텍스트 배치 건너뜀 (예: "After You Read" 버튼).
                    if (!rendered && tfDomId >= 0) {
                        rendered = idx.childrenOfInlineObjects.contains(tfDomId);
                    }
                    // 부모/조상 Group이 inline_object 이거나 inline PNG로 캡처된 경우
                    // → Phase 3가 INLINE_TEXT_FRAME으로 처리 → floating 불필요.
                    // 5 hop 조상 체인을 검사하여 중첩 Group(예: Group18498 → Group18558 → TF18579) 도 처리.
                    ResolvedPageItem _inlPi = ctx.resolvedData.getPageItem(tf.id());
                    if (!rendered && tfDomId >= 0) {
                        String _curParentId = (_inlPi != null) ? _inlPi.parentId() : null;
                        for (int _h = 0; _h < 5 && _curParentId != null && !rendered; _h++) {
                            int _pid = parseDomIdOrNeg(_curParentId);
                            if (_pid >= 0) {
                                if (ctx.resolvedData.isInlineObjectId(_pid)) rendered = true;
                                if (!rendered && idx.inlineFileGroupIds.contains(_pid)) rendered = true;
                            }
                            if (!rendered) {
                                ResolvedPageItem _nextPi = ctx.resolvedData.getPageItem(_curParentId);
                                if (_nextPi == null) break;
                                _curParentId = _nextPi.parentId();
                            }
                        }
                    }
                    boolean sharedWithEditable = tf.storyId() != null && idx.editableStoryIds.contains(tf.storyId());
                    // parentId가 있으면 다른 객체 안에 중첩 → 배경에서 부모와 함께 숨겨짐
                    boolean hasParent = _inlPi != null && _inlPi.parentId() != null;
                    if (hasText && !rendered && !sharedWithEditable && hasParent) {
                        inlineToFloatingReason = InlineToFloatingReason.NON_EDITABLE_WITH_TEXT;
                    } else {
                        continue;
                    }
                } else {
                    // SPEC-025: inline + editable. Phase 3 처리 분기:
                    // - 자기 텍스트 ≥ 2 자: Phase 3 가 인라인 텍스트 런으로 임베드 → 플로팅 스킵
                    // - 멀티 child 배지 (Phase 3 가 자손 텍스트 결합 ≥ 2자): Phase 3 가 결합 인라인 임베드 → 플로팅 스킵
                    // - 단일 1자 라벨 (예: "1", "예"): Phase 3 가 PNG 임베드 (텍스트 누락) → 플로팅으로 검색 가능 텍스트 보강
                    // 부모 Group이 inline_object이면 inline PNG가 시각적 배지 전체를 포함 → floating 불필요.
                    if (tfDomId >= 0) {
                        ResolvedPageItem _tfi2 = ctx.resolvedData.getPageItem(tf.id());
                        if (_tfi2 != null && _tfi2.parentId() != null) {
                            int parentDomId = parseDomIdOrNeg(_tfi2.parentId());
                            if (parentDomId >= 0 && ctx.resolvedData.isInlineObjectId(parentDomId)) continue; // O(1)
                        }
                    }
                    // inline+editable TF가 어떤 렌더 채널에도 없으면 Phase 3 인라인 런으로 처리.
                    // (어휘 숫자 superscript "1"/"2"/"3" 등 — PNG 없이 IDML 스토리 텍스트만 존재)
                    // 렌더된 경우에만 floating 배치 + TEXT_BLOCK_PLACED 설정.
                    boolean _isRenderedInline = tfDomId >= 0
                            && (ctx.resolvedData.isRenderedByOtherChannel(tfDomId)
                                || idx.allRenderedItemIds.contains(tfDomId));
                    if (!_isRenderedInline) {
                        // 렌더 없음 → Phase 3 가 IDML 스토리에서 인라인 텍스트 런으로 처리
                        continue;
                    }
                    // 렌더 있음 → floating 배치 + Phase 3 중복 방지
                    // (tfDomId >= 0은 _isRenderedInline 조건이 보장)
                    ctx.setDisposition(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED);
                    inlineToFloatingReason = InlineToFloatingReason.EDITABLE_RENDERED;
                }
            }
            boolean inlineToFloating = inlineToFloatingReason != InlineToFloatingReason.NONE;
            boolean hasRenderedVisualShell = hasRenderedVisualShell(ctx, tfDomId);

            // 숨김/비인쇄 TF → 변환 불필요
            if (tf.onHiddenLayer() || tf.nonprinting()) { continue; }

            // 마스터 인스턴스 TF가 composed되지 않은 경우 (lineCount=0) → 해당 페이지에서 override됨 → skip
            if (tf.isMasterInstance() && tf.lineCount() == 0) { continue; }

            // 다른 TextFrame 안에 중첩된 프레임은 건너뜀 (부모가 배경에 포함)
            if (!inlineToFloating && isNestedInTextFrame(ctx, tf)) {
                continue;
            }
            // 배경에 포함된 프레임은 건너뜀 (editable 프레임만 글상자로 배치)
            // 단, 같은 story를 editable TF와 공유하는 non-editable TF는 배치
            if (!inlineToFloating && !ctx.resolvedData.isEditableTextFrame(tf.id())) {
                if (shouldSkipNonEditableTf(ctx, tf, tfDomId, idx)) continue;
            }
            // badge_group_child(non-editable)는 부모 PNG가 텍스트를 포함하므로 글상자 배치 건너뜀.
            // SPEC-025: editable로 승격된 frame은 !isEditableTextFrame 가드로 보호됨 → 건너뛰지 않음
            boolean skipAsBadgeChild = !ctx.resolvedData.isEditableTextFrame(tf.id())
                    && tfDomId >= 0 && idx.badgeChildDomIds.contains(tfDomId);
            if (skipAsBadgeChild) { continue; }

            // SPEC-025 occlusion: 오탐으로 인한 TF 누락이 많아 임시로 비활성화.
            // 나중에 zOrder/group/background 판정을 재설계한 뒤 ENABLE_OCCLUSION_FILTER를 다시 켠다.
            if (ENABLE_OCCLUSION_FILTER && !inlineToFloating
                    && ctx.resolvedData.isEditableTextFrame(tf.id())
                    && isOccludedByOpaqueShape(ctx, tf)) {
                continue;
            }

            // 연결 글상자 체인: 후속 프레임은 건너뜀 (첫 프레임에서 병합 처리)
            // 단, 체인의 프레임들이 Y 방향으로 떨어져 있거나 다른 컬럼이면 병합하지 않음 (각각 배치)
            if (tf.previousFrameId() != null) {
                ResolvedTextFrame prevTf = ctx.resolvedData.getTextFrame(tf.previousFrameId());
                if (prevTf != null && prevTf.geometricBounds() != null && tf.geometricBounds() != null) {
                    int prevDomId = parseDomIdOrNeg(prevTf.id());
                    boolean prevHasRenderedVisualShell = hasRenderedVisualShell(ctx, prevDomId);
                    if (hasRenderedVisualShell || prevHasRenderedVisualShell) {
                        // A rendered visual shell is an independent visual unit
                        // (for example, a checkbox/underline frame). Do not merge
                        // or suppress it only because the story is threaded.
                    } else {
                    double[] pgb = prevTf.geometricBounds();
                    double[] cgb = tf.geometricBounds();
                    boolean diffPage = prevTf.pageIndex() != tf.pageIndex();
                    double prevBottom = pgb[2];
                    double curTop = cgb[0];
                    double gap = curTop - prevBottom;
                    double lineH = cgb[2] - cgb[0];
                    // X 범위 겹침 비율: 컬럼이 다르면 병합 안 함
                    double xOverlapRatio = xOverlapRatio(pgb, cgb);
                    // gap<0 (역방향) 또는 gap>lineH*CHAIN_GAP_RATIO 또는 다른 컬럼이면 독립 배치
                    if (diffPage || gap < 0 || gap > lineH * CHAIN_GAP_RATIO || xOverlapRatio < CHAIN_X_OVERLAP_MIN) {
                        // 병합하지 않고 독립 배치 → continue하지 않음
                    } else {
                        continue; // 인접 → 병합 (첫 프레임에서 처리)
                    }
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
            // hasNextPageChain: 체인 중 다른 페이지 프레임이 있으면 YGap 분할 금지
            boolean hasNextPageChain = false;
            if (tf.nextFrameId() != null) {
                gb = new double[]{gb[0], gb[1], gb[2], gb[3]};
                String nextId = tf.nextFrameId();
                while (nextId != null) {
                    ResolvedTextFrame next = ctx.resolvedData.getTextFrame(nextId);
                    if (next == null || next.geometricBounds() == null) break;
                    int nextDomId = parseDomIdOrNeg(next.id());
                    if (hasRenderedVisualShell || hasRenderedVisualShell(ctx, nextDomId)) break;
                    double[] ngb = next.geometricBounds();
                    // 다른 페이지이거나 Y 간격이 한 줄 높이의 50% 이상이면 합산 중단
                    if (next.pageIndex() != tf.pageIndex()) { hasNextPageChain = true; break; }
                    double gap = ngb[0] - gb[2];
                    double lineH = ngb[2] - ngb[0];
                    // X 범위 겹침 확인: 다른 컬럼이면 병합 중단
                    // gap<0 (역방향) 또는 gap>lineH*CHAIN_GAP_RATIO 또는 다른 컬럼이면 병합 중단
                    if (gap < 0 || gap > lineH * CHAIN_GAP_RATIO || xOverlapRatio(gb, ngb) < CHAIN_X_OVERLAP_MIN) break;
                    if (ngb[0] < gb[0]) gb[0] = ngb[0];
                    if (ngb[1] < gb[1]) gb[1] = ngb[1];
                    if (ngb[2] > gb[2]) gb[2] = ngb[2];
                    if (ngb[3] > gb[3]) gb[3] = ngb[3];
                    nextId = next.nextFrameId();
                }
            }

            // facing pages: spread 상대 x(= gb[1] - pageLeft)가 음수이면 ExtendScript가
            // page-relative 좌표를 반환한 것으로 간주 → gb[1]을 그대로 page x로 사용.
            double spreadX = gb[1] - pageLeft;
            double x = (spreadX >= 0) ? spreadX : gb[1];
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
            Set<Integer> excludedParaIndices = null;
            try {
                List<ResolvedTextFrame.ComposedLine> _cls = tf.composedLines();
                if (_cls != null && !_cls.isEmpty() && tf.paragraphEnd() >= tf.paragraphStart()) {
                    // 단락별로 line bounds 를 union 해서 단락 영역 계산
                    Map<Integer, double[]> paraBounds = new HashMap<>();
                    Map<Integer, StringBuilder> paraTexts = new HashMap<>();
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
                        paraTexts.computeIfAbsent(pi, k -> new StringBuilder())
                                .append(cl.text() != null ? cl.text() : "");
                    }

                    for (Map.Entry<Integer, double[]> e : paraBounds.entrySet()) {
                        int pi = e.getKey();
                        double[] pb = e.getValue();
                        double pT = pb[0], pL = pb[1], pB = pb[2], pR = pb[3];
                        StringBuilder sb = paraTexts.get(pi);
                        if (sb == null) continue;
                        String paraText = sb.toString().replace("\r", "").replace("\n", "").trim();
                        if (paraText.length() < 3) continue;
                        for (ResolvedTextFrame _other : framesByPage.getOrDefault(tf.pageIndex(), Collections.emptyList())) {
                            if (_other == null || _other == tf) continue;
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
                            if (_ov / Math.min(pH, _otherH) < OVERLAY_Y_OVERLAP_MIN) continue;
                            // X 겹침: 다른 TF 가 이 단락 영역 내에 위치해야 오버레이로 간주
                            double _xOvStart = Math.max(pL, _oL);
                            double _xOvEnd = Math.min(pR, _oR);
                            double _xOv = _xOvEnd - _xOvStart;
                            if (_xOv <= 0) continue;
                            double _otherW = _oR - _oL;
                            if (_otherW <= 0) continue;
                            // 다른 TF 의 너비 80% 이상이 이 단락 영역 안에 들어와야 함
                            if (_xOv / _otherW < OVERLAY_X_COVERAGE_MIN) continue;
                            String _otherText = _other.frameVisibleText();
                            if (_otherText == null || _otherText.isEmpty()) continue;
                            String _otherClean = _otherText.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
                            if (_otherClean.length() < 3) continue;
                            if (paraText.contains(_otherClean)
                                    && _otherClean.length() * 2 >= paraText.length()) {
                                if (excludedParaIndices == null) excludedParaIndices = new HashSet<>();
                                excludedParaIndices.add(pi + tf.paragraphStart());
                                break;
                            }
                        }
                    }

                    // paraIdx=0 이 제외되면 y/h 를 첫 비제외 단락 시작으로 이동
                    if (excludedParaIndices != null && excludedParaIndices.contains(tf.paragraphStart())) {
                        Integer firstKept = null;
                        List<Integer> sortedKeys = new ArrayList<>(paraBounds.keySet());
                        Collections.sort(sortedKeys);
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
            } catch (Exception eTOPre) {
                System.err.println("[FramePlacer] 타이틀 오버레이 감지 오류 tf=" + tf.id() + ": " + eTOPre);
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
                        // 형제의 상단이 TF 상단에서 절반 이상 아래에 있으면 장식 요소(말풍선 꼬리 등) →
                        // 텍스트 옆으로 shift 금지 (형제가 TF 하단부에만 겹치면 사이드 라벨 패턴이 아님)
                        if ((sbT - y) >= h * 0.5) continue;
                        // 형제의 Y-extent 가 TF 높이의 1.3배 이상이면 배경 도형(TF를 감싸는 container) →
                        // 텍스트가 배경 위에 올라타는 패턴이므로 shift 금지
                        if (sibYExt > h * XSHIFT_CONTAINER_HEIGHT_RATIO) continue;
                        // 형제의 Y-extent 가 TF 높이의 20% 미만이면 얇은 장식 줄(horizontal strip) →
                        // 텍스트가 위에 올라타는 패턴이므로 shift 금지
                        if (sibYExt < h * XSHIFT_STRIP_HEIGHT_RATIO) continue;
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
                            if (delta > 0 && remainW > w * 0.2 && remainW > XSHIFT_MIN_REMAIN_PT) {
                                x = newX;
                                w = remainW;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[FramePlacer] 형제 도형 X-shift 오류 tf=" + tf.id() + ": " + e);
            }

            if (w <= 0) continue;

            // composedLines 기반 글상자 분할
            // 단, 다른 페이지의 연결 글상자가 있으면 YGap 분할 금지:
            // YGap 블록이 생기면 distributeByComposedCharRange 경로를 타고 연결 글상자 체인 블록들이
            // 단락 배분에서 제외되어 텍스트가 다음 페이지로 흐르지 못하는 버그 발생.
            // (hasNextPageChain은 위 chain merge loop에서 이미 계산됨)
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
            block.sourceId(ParagraphTextHelpers.domIdToSourceId(tf.id()));
            block.storyId(tf.storyId());
            // \uFFFC 로 시작하는 TF에 inline TF가 좌측 가장자리를 공유하는 경우
            // (예: 단락 번호 "1" TF가 본문 TF 왼쪽에 맞닿음)
            // → x는 이동하지 않고, inline TF top이 더 위에 있으면 y를 그 위치로 올림
            {
                String _fvtInl = tf.frameVisibleText();
                if (_fvtInl != null && _fvtInl.startsWith("\uFFFC")) {
                    for (ResolvedTextFrame _itf : inlineFramesByPage.getOrDefault(tf.pageIndex(), Collections.emptyList())) {
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
                        // 인라인 TF top이 부모 TF top보다 위에 있으면 y를 그 위치로 올림
                        double _inlineTop = _igb[0] - pageTop;
                        if (_inlineTop < y) {
                            h += (y - _inlineTop);
                            y = _inlineTop;
                        }
                        // Phase 3가 이 인라인 TF를 텍스트 런으로 내장하므로 단락 leftIndent 무시
                        block.suppressParaLeftIndent(true);
                        System.err.println("[FramePlacer] ORC+inline 감지 → suppressParaLeftIndent: tf=" + tf.id() + " storyId=" + tf.storyId() + " inlineTf=" + _itf.id());
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
                if (frameH > 0 && topOffset / frameH > TOP_ALIGN_OFFSET_RATIO) {
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
                    String fillHex = ctx.resolvedData.resolveTintedColorHex(fillName, tf.fillTint());
                    if (fillHex != null) {
                        block.fillColor(fillHex);
                        block.fillTint(100);
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
            } catch (Exception eFill) {
                System.err.println("[FramePlacer] fill/stroke 속성 적용 오류 tf=" + tf.id() + ": " + eFill);
            }
            if (!hasRenderedVisualShell) {
                applyGroupBackgroundShapeStyle(ctx, tf, block);
            }

            // overflow 감지용 텍스트 길이 저장
            String visText = tf.frameVisibleText();
            if (visText != null) {
                block.frameVisibleText(visText);
                block.frameVisibleTextLength(visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").length());
            }
            block.noAutoLineWrap(shouldUseNoAutoLineWrap(tf, block)
                    || shouldUseVisualShellNoAutoLineWrap(hasRenderedVisualShell, tf, block));
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
                if (inlineToFloatingReason == InlineToFloatingReason.NON_EDITABLE_WITH_TEXT) {
                    ResolvedPageItem _ancCurPi = ctx.resolvedData.getPageItem(tf.id());
                    outer_anc:
                    for (int _ancHops = 0; _ancCurPi != null && _ancHops < 10; _ancHops++) {
                        String _ancPid = _ancCurPi.parentId();
                        if (_ancPid == null) break;
                        int _ancPidInt = parseDomIdOrNeg(_ancPid);
                        if (_ancPidInt >= 0) {
                            RenderedGroup _ancRg = idx.inlineObjectById.get(_ancPidInt);
                            if (_ancRg != null) {
                                // inline_object PNG가 있으면 inline 배치 유지 → floating 전환 등록 안 함.
                                // TF는 floating text box로 배치되어 PNG 위에 텍스트 오버레이 역할을 한다.
                                if (_ancRg.file() != null) break outer_anc;
                                ctx.setDisposition(_ancRg.id(), FrameDisposition.PNG_CONVERT_TO_FLOATING);
                                ctx.inlineObjectTfPageIndex.put(_ancRg.id(), tf.pageIndex());
                                break outer_anc;
                            }
                        }
                        _ancCurPi = ctx.resolvedData.getPageItem(_ancPid);
                    }
                }
            }
            if (hasRenderedVisualShell && block.fillColor() == null && block.strokeColor() == null) {
                // The parent InDesign PNG owns the visual shell. Route this as a
                // transparent text overlay so the 1x1 table fallback does not cover it.
                block.inlineToFloating(true);
            }
            // 공간 포함 outer TF: 자신 안에 포함된 inner TF id 기록 → StoryConverter가 inner story 주입
            String _innerFrameId = cm.outerToInner.get(tf.id());
            if (_innerFrameId != null) block.innerFrameId(_innerFrameId);

            section.addBlock(block);
        }
    }

    private static boolean hasRenderedVisualShell(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || ctx.resolvedData == null || tfDomId < 0) return false;
        List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return false;
        String tfId = String.valueOf(tfDomId);
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.file() == null || rg.file().isEmpty()) continue;
            if (!"page_object".equals(rg.type())) continue;
            if (Boolean.FALSE.equals(rg.placementAllowed())) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            String[] editableIds = rg.editableTextFrameIds();
            if (editableIds == null) continue;
            for (String editableId : editableIds) {
                if (tfId.equals(editableId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * non-editable + non-inlineToFloating TF의 배치 여부를 결정한다.
     * true 반환 시 해당 TF를 건너뜀, false 반환 시 글상자 배치 계속.
     * 부작용: _parentIsRotatedRect 시 ctx.setDisposition(tfDomId, TEXT_BLOCK_PLACED) 호출.
     */
    private static boolean shouldSkipNonEditableTf(
            ResolvedBuildContext ctx, ResolvedTextFrame tf, int tfDomId, FrameIndex idx) {
        if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) return true;

        // domId=None TF: ExtendScript가 domId를 얻지 못해 editability 확인 불가.
        // storyId가 있고 비-숨김/비인쇄이면 IDML에 실제 내용이 있을 수 있으므로 배치 허용.
        if (tf.id() == null && tf.storyId() != null && !tf.onHiddenLayer() && !tf.nonprinting()) return false;

        boolean sharedWithEditable = tf.storyId() != null && idx.editableStoryIds.contains(tf.storyId());
        if (sharedWithEditable) return false;

        // non-editable 플로팅 TF 중, 자기 story + 텍스트가 있고 PNG로 렌더됐으며
        // 부모가 회전된 Rectangle (absoluteRotationAngle≠0)인 경우 텍스트 글상자로 배치.
        // (예: 오느른/운느라/싸인 — 부모 Rectangle이 비스듬히 기울어진 TF)
        String _vis = tf.frameVisibleText();
        String _visCleaned = (_vis == null) ? "" : _vis.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
        boolean _hasOwnText = _visCleaned.length() >= 2;
        boolean _isRendered = tfDomId >= 0 && ctx.resolvedData.isRenderedByOtherChannel(tfDomId);

        boolean _parentIsRotatedRect = false;
        ResolvedPageItem _tfPi = ctx.resolvedData.getPageItem(tf.id());
        if (_hasOwnText && _isRendered && !tf.isInline()) {
            if (_tfPi != null && _tfPi.parentId() != null) {
                ResolvedPageItem _parent = ctx.resolvedData.getPageItem(_tfPi.parentId());
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
            String _anPid = (_tfPi != null) ? _tfPi.parentId() : null;
            for (int _d = 0; _d < 5 && _anPid != null && !_ancestorHasPng; _d++) {
                int _anPidInt = parseDomIdOrNeg(_anPid);
                if (_anPidInt >= 0) {
                    if (ctx.resolvedData.isInlineObjectId(_anPidInt)) { _ancestorHasPng = true; break; }
                    // inline_* 파일을 가진 그룹(배지)만 텍스트 포함 PNG로 간주.
                    // deco_*/shape_* 등 page_object 타입은 텍스트 TF 내용이 PNG에 캡처되지 않음.
                    if (idx.inlineFileGroupIds.contains(_anPidInt)) { _ancestorHasPng = true; break; }
                }
                if (!_ancestorHasPng) {
                    ResolvedPageItem _anParPi = ctx.resolvedData.getPageItem(_anPid);
                    _anPid = (_anParPi != null) ? _anParPi.parentId() : null;
                }
            }
            _nonRenderedWithText = !_ancestorHasPng;
        }

        if (_parentIsRotatedRect) {
            // Phase 7 PNG 는 이후 ctx.frameDispositions(TEXT_BLOCK_PLACED) 확인 시 건너뜀.
            ctx.setDisposition(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED);
            return false; // 글상자로 배치
        }
        return !_nonRenderedWithText; // nonRenderedWithText → 배치(false), 그 외 → 건너뜀(true)
    }

    /** 두 bounds 배열([top,left,bottom,right])의 X 겹침 비율을 반환. 겹침 없으면 0. */
    private static double xOverlapRatio(double[] a, double[] b) {
        double xOvStart = Math.max(a[1], b[1]);
        double xOvEnd   = Math.min(a[3], b[3]);
        double aW = a[3] - a[1];
        double bW = b[3] - b[1];
        return (xOvEnd > xOvStart && aW > 0 && bW > 0)
                ? (xOvEnd - xOvStart) / Math.min(aW, bW) : 0.0;
    }

    private static int parseDomIdOrNeg(String id) {
        if (id == null) return -1;
        try { return Integer.parseInt(id); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * editable TF가 배경 Rectangle/Oval/Polygon과 한 그룹 안에 있을 때, 그룹 PNG 대신
     * 검색 가능한 TF 자체에 도형의 fill/stroke를 복사한다. 말풍선처럼 "도형+텍스트"가
     * 한 그룹 PNG로 중복 렌더링되는 경우 이미지 텍스트를 제거하면서 도형은 유지한다.
     */
    private static void applyGroupBackgroundShapeStyle(
            ResolvedBuildContext ctx, ResolvedTextFrame tf, ASTTextFrameBlock block) {
        ResolvedPageItem tfItem = ctx.resolvedData.getPageItem(tf.id());
        if (tfItem == null || tfItem.parentId() == null || tf.geometricBounds() == null) return;

        ResolvedPageItem best = null;
        double bestScore = 0.0;
        double[] tfb = tf.geometricBounds();
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || pi.id() == null || pi.id().equals(tf.id())) continue;
            if (!tfItem.parentId().equals(pi.parentId())) continue;
            String t = pi.type();
            if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t)) continue;
            double[] pb = pi.geometricBounds();
            if (pb == null || pb.length < 4) continue;
            double score = overlapRatio(tfb, pb);
            if (score > bestScore) {
                bestScore = score;
                best = pi;
            }
        }
        if (best == null || bestScore < 0.75) return;

        String fillName = best.fillColorName();
        if ((block.fillColor() == null || block.fillColor().isEmpty())
                && fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
            String fillHex = ctx.resolvedData.resolveTintedColorHex(fillName, best.fillTint());
            if (fillHex != null) {
                block.fillColor(fillHex);
                block.fillTint(100);
            }
        }

        String strokeName = best.strokeColorName();
        if ((block.strokeColor() == null || block.strokeColor().isEmpty())
                && strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)
                && best.strokeWeight() > 0) {
            String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
            if (strokeHex != null) {
                block.strokeColor(strokeHex);
                block.strokeWeight(best.strokeWeight());
                block.strokeTint(ColorResolver.normalizeTint(best.strokeTint()));
            }
        }

        if (block.cornerRadius() <= 0 && best.cornerRadius() > 0) {
            block.cornerRadius(best.cornerRadius());
        }
    }

    private static double overlapRatio(double[] a, double[] b) {
        double y1 = Math.max(a[0], b[0]);
        double x1 = Math.max(a[1], b[1]);
        double y2 = Math.min(a[2], b[2]);
        double x2 = Math.min(a[3], b[3]);
        if (y2 <= y1 || x2 <= x1) return 0.0;
        double overlap = (y2 - y1) * (x2 - x1);
        double areaA = Math.max(0.0, (a[2] - a[0]) * (a[3] - a[1]));
        double areaB = Math.max(0.0, (b[2] - b[0]) * (b[3] - b[1]));
        double denom = Math.min(areaA, areaB);
        return denom > 0 ? overlap / denom : 0.0;
    }

    /** 줄 텍스트가 공백/CR/LF/U+FFFC 이외의 내용을 가지는지 판단 (placeByYGapSplit 내 2회 반복 조건). */
    private static boolean isSubstantiveLine(String lineText) {
        return lineText != null
                && !lineText.replace("\r", "").replace("\n", "").replace("\uFFFC", "").trim().isEmpty();
    }

    private enum InlineToFloatingReason {
        /** 플로팅 전환 없음 (기본값). */
        NONE,
        /** non-editable inline TF + 텍스트 있음 + 렌더 없음 + story 미공유 → floating 텍스트 박스. */
        NON_EDITABLE_WITH_TEXT,
        /** editable inline TF + 렌더 채널 있음(배지 등) → floating 텍스트 박스 + TEXT_BLOCK_PLACED. */
        EDITABLE_RENDERED
    }

    /** {@link #placeTextFrames} 에서 사전 구축하는 룩업 인덱스 집합. */
    private static final class FrameIndex {
        final Set<Integer> badgeChildDomIds;
        final Map<Integer, Integer> badgeChildToParentId;
        final Map<Integer, int[]> inlineObjectChildIdsMap;
        final Set<Integer> childrenOfInlineObjects;
        final Set<Integer> inlineFileGroupIds;
        final Set<Integer> allRenderedItemIds;
        final Set<Integer> renderedItemWithFileIds;
        final Set<String> editableStoryIds;
        /** inline_object 타입 RenderedGroup을 id → 객체로 조회 (ancestor 체인 탐색용). */
        final Map<Integer, RenderedGroup> inlineObjectById;

        FrameIndex(Set<Integer> badgeChildDomIds,
                   Map<Integer, Integer> badgeChildToParentId,
                   Map<Integer, int[]> inlineObjectChildIdsMap,
                   Set<Integer> childrenOfInlineObjects,
                   Set<Integer> inlineFileGroupIds,
                   Set<Integer> allRenderedItemIds,
                   Set<Integer> renderedItemWithFileIds,
                   Set<String> editableStoryIds,
                   Map<Integer, RenderedGroup> inlineObjectById) {
            this.badgeChildDomIds = badgeChildDomIds;
            this.badgeChildToParentId = badgeChildToParentId;
            this.inlineObjectChildIdsMap = inlineObjectChildIdsMap;
            this.childrenOfInlineObjects = childrenOfInlineObjects;
            this.inlineFileGroupIds = inlineFileGroupIds;
            this.allRenderedItemIds = allRenderedItemIds;
            this.renderedItemWithFileIds = renderedItemWithFileIds;
            this.editableStoryIds = editableStoryIds;
            this.inlineObjectById = inlineObjectById;
        }
    }

    private static FrameIndex buildIndex(List<RenderedGroup> renderedItems,
                                          List<ResolvedTextFrame> frames,
                                          ResolvedBuildContext ctx) {
        Set<Integer> badgeChildDomIds = new HashSet<>();
        Map<Integer, Integer> badgeChildToParentId = new HashMap<>();
        Map<Integer, int[]> inlineObjectChildIdsMap = new HashMap<>();
        Set<Integer> childrenOfInlineObjects = new HashSet<>();
        Set<Integer> inlineFileGroupIds = new HashSet<>();
        Set<Integer> allRenderedItemIds = new HashSet<>();
        Set<Integer> renderedItemWithFileIds = new HashSet<>();
        Map<Integer, RenderedGroup> inlineObjectById = new HashMap<>();
        for (RenderedGroup _rgi : renderedItems) {
            allRenderedItemIds.add(_rgi.id());
            if (_rgi.file() != null) renderedItemWithFileIds.add(_rgi.id());
            if ("badge_group_child".equals(_rgi.itemType())) {
                badgeChildDomIds.add(_rgi.id());
                badgeChildToParentId.put(_rgi.id(), _rgi.badgeGroupId());
            }
            if ("inline_object".equals(_rgi.itemType())) {
                inlineObjectById.put(_rgi.id(), _rgi);
                if (_rgi.childIds() != null) {
                    inlineObjectChildIdsMap.put(_rgi.id(), _rgi.childIds());
                    for (int cid : _rgi.childIds()) childrenOfInlineObjects.add(cid);
                }
            }
            if (_rgi.file() != null && _rgi.file().contains("inline_")) {
                inlineFileGroupIds.add(_rgi.id());
            }
        }
        Set<String> editableStoryIds = new HashSet<>();
        for (ResolvedTextFrame _tf : frames) {
            if (_tf.storyId() != null && ctx.resolvedData.isEditableTextFrame(_tf.id())) {
                editableStoryIds.add(_tf.storyId());
            }
        }
        return new FrameIndex(badgeChildDomIds, badgeChildToParentId, inlineObjectChildIdsMap,
                childrenOfInlineObjects, inlineFileGroupIds, allRenderedItemIds,
                renderedItemWithFileIds, editableStoryIds, inlineObjectById);
    }

    /** {@link #buildContainmentMap}의 반환값: inner→outer 맵과 그 역방향 맵. */
    private static final class ContainmentMaps {
        final Map<String, String> innerToOuter;
        final Map<String, String> outerToInner;
        ContainmentMaps(Map<String, String> innerToOuter, Map<String, String> outerToInner) {
            this.innerToOuter = innerToOuter;
            this.outerToInner = outerToInner;
        }
    }

    private static ContainmentMaps buildContainmentMap(
            List<ResolvedTextFrame> frames,
            Set<Integer> badgeChildDomIds) {
        Map<String, String> innerToOuterMap = new HashMap<>();
        final double TOL = CONTAINMENT_TOL_PT;
        // Pass 1: 각 inner TF에 대해 포함하는 outer TF 후보를 모두 수집
        Map<String, List<ResolvedTextFrame>> innerCandidates = new HashMap<>();
        for (ResolvedTextFrame outer : frames) {
            if (outer.storyId() == null) continue;
            double[] aGb = outer.geometricBounds();
            if (aGb == null || aGb.length < 4) continue;
            for (ResolvedTextFrame inner : frames) {
                if (inner == outer) continue;
                if (inner.storyId() == null || inner.storyId().equals(outer.storyId())) continue;
                // Threaded frames are a continuous text flow, not an overlay answer
                // inserted into a containing blank. Keeping them independent avoids
                // duplicating the same story both inside the outer TF and as its own TF.
                if (inner.previousFrameId() != null || inner.nextFrameId() != null) continue;
                if (inner.pageIndex() != outer.pageIndex()) continue;
                if (inner.onHiddenLayer() || outer.onHiddenLayer() || inner.nonprinting() || outer.nonprinting()) continue;
                if (inner.isInline()) continue;
                String _outerVis = outer.frameVisibleText();
                if (_outerVis == null || _outerVis.trim().isEmpty()) continue;
                double[] bGb = inner.geometricBounds();
                if (bGb == null || bGb.length < 4) continue;
                if (bGb[0] >= aGb[0] - TOL && bGb[1] >= aGb[1] - TOL
                        && bGb[2] <= aGb[2] + TOL && bGb[3] <= aGb[3] + TOL) {
                    int innerDomId = parseDomIdOrNeg(inner.id());
                    boolean isBadgeChild = innerDomId >= 0 && badgeChildDomIds.contains(innerDomId);
                    if (!isBadgeChild) {
                        innerCandidates.computeIfAbsent(inner.id(), k -> new ArrayList<>()).add(outer);
                    }
                }
            }
        }
        // Pass 2: 각 inner에 대해 가장 작은(가장 내부) outer 선택
        for (Map.Entry<String, List<ResolvedTextFrame>> e : innerCandidates.entrySet()) {
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
        Map<String, Integer> outerInnerCount = new HashMap<>();
        for (String outerId : innerToOuterMap.values()) {
            outerInnerCount.merge(outerId, 1, Integer::sum);
        }
        innerToOuterMap.entrySet().removeIf(e -> outerInnerCount.getOrDefault(e.getValue(), 0) > 1);
        // Pass 4: outer로 사용되는 프레임은 inner로 스킵하지 않음 (계층 유지)
        Set<String> usedAsOuter = new HashSet<>(innerToOuterMap.values());
        innerToOuterMap.keySet().removeIf(usedAsOuter::contains);
        if (!innerToOuterMap.isEmpty()) {
            for (Map.Entry<String, String> e : innerToOuterMap.entrySet()) {
                System.err.println("[FramePlacer] 공간 포함 오버레이: inner=" + e.getKey()
                        + " ⊂ outer=" + e.getValue());
            }
        }
        Map<String, String> outerToInnerMap = new HashMap<>();
        for (Map.Entry<String, String> e : innerToOuterMap.entrySet()) {
            outerToInnerMap.put(e.getValue(), e.getKey());
        }
        return new ContainmentMaps(innerToOuterMap, outerToInnerMap);
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
        Set<String> ancestorIds = new HashSet<>();
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
            if (pi.zOrder() <= selfZ) continue;  // 같거나 뒤쪽 도형은 가릴 수 없음 (InDesign: 큰 zOrder = 앞)
            // ancestor 도형은 자식 TextFrame 의 컨테이너 — 가린다고 보지 않음
            if (pi.id() != null && ancestorIds.contains(pi.id())) continue;
            // 같은 그룹 묶음의 도형은 말풍선/라벨 배경인 경우가 많다.
            // InDesign 그룹 내부 zOrder만 보고 occluder로 처리하면, Paper 배경 Rectangle 위의
            // editable TextFrame이 "가려진 텍스트"로 오탐되어 HWPX에서 누락된다.
            if (isGroupedBackgroundShape(ctx, tf, selfPi, pi, ancestorIds)) continue;
            // TF는 그룹 밖에 있고 배경 도형만 별도 그룹으로 묶인 말풍선도 있다.
            // 이 경우 텍스트 라인 폭보다 배경이 넓어도, TF 프레임 bounds와 거의 같으면 배경으로 본다.
            if (isNearbyFrameBackgroundShape(ctx, tf, pi)) continue;
            String t = pi.type();
            // 불투명 도형: Rectangle/Polygon/Oval + fillColor 가 None 이 아님
            if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t)) continue;
            String fc = pi.fillColorName();
            if (fc == null || "None".equals(fc) || "[None]".equals(fc)) continue;
            // opacity 50 이하면 반투명 → 텍스트 가림으로 보지 않음 (50% 이하는 배경이 충분히 비침)
            if (pi.opacity() <= 50) continue;
            // fillTint 50 미만 → 매우 연한 색 → 실질적 가림 아님
            if (pi.fillTint() >= 0 && pi.fillTint() < 50) continue;
            double[] sb = pi.geometricBounds();
            if (sb == null || sb.length < 4) continue;
            // bounds 가 텍스트 영역을 포함하는지 확인 (1pt 여유)
            if (sb[0] <= textTop + OCCLUSION_BOUNDS_TOL_PT && sb[1] <= textLeft + OCCLUSION_BOUNDS_TOL_PT
                    && sb[2] >= textBottom - OCCLUSION_BOUNDS_TOL_PT && sb[3] >= textRight - OCCLUSION_BOUNDS_TOL_PT) {
                // 제목 색바/라벨처럼 별도 도형 위에 TextFrame이 올라가는 패턴은
                // 도형이 텍스트 영역을 포함해도 occluder가 아니라 배경이다.
                if (hasFrameLikeBackgroundBounds(tf, pi, 8.0, 0.85, 4.0)) {
                    continue;
                }
                // 같은 크기의 배경 도형(말풍선 등)은 occluder 가 아니라 텍스트의 배경 → 제외.
                // shape 의 너비/높이가 텍스트 영역의 1.2 배 이상일 때만 진짜 occluder 로 간주.
                double shapeW = sb[3] - sb[1];
                double shapeH = sb[2] - sb[0];
                double textW = textRight - textLeft;
                double textH = textBottom - textTop;
                if (textW > 0 && textH > 0 && shapeW < textW * OCCLUDER_SIZE_RATIO && shapeH < textH * OCCLUDER_SIZE_RATIO) {
                    continue;
                }
                // 도형이 페이지 경계를 벗어나면 spread 배경/장식 → occluder 아님.
                // 진짜 occluder(예: 흰 직사각형으로 TF 숨김)는 페이지 내부에 위치.
                // 허용 여유: 5pt (블리드/재단선 고려).
                try {
                    List<kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage> pages =
                            ctx.resolvedData.pages();
                    if (selfPage < pages.size()) {
                        double[] pageBounds = pages.get(selfPage).bounds();
                        if (pageBounds != null && pageBounds.length >= 4) {
                            final double BLEED_TOL = 5.0;
                            if (sb[0] < pageBounds[0] - BLEED_TOL || sb[1] < pageBounds[1] - BLEED_TOL
                                    || sb[2] > pageBounds[2] + BLEED_TOL || sb[3] > pageBounds[3] + BLEED_TOL) {
                                continue; // 페이지 경계 초과 → spread 배경
                            }
                            // Polygon/Oval: AABB 가 페이지 크기의 2 배 이상이면 스윕 패스 → occluder 제외.
                            if (!"Rectangle".equals(t)) {
                                double pageW = pageBounds[3] - pageBounds[1];
                                double pageH = pageBounds[2] - pageBounds[0];
                                if ((pageW > 0 && shapeW > pageW * 2.0) || (pageH > 0 && shapeH > pageH * 2.0)) {
                                    continue;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
                // Polygon/Oval 은 불규칙 패스일 수 있어 AABB 가 실제 채움 영역보다 훨씬 큼.
                // AABB 면적이 텍스트 면적의 50 배 초과이면 스크리블/장식 패스로 간주 → occluder 제외.
                if (!"Rectangle".equals(t) && textW > 0 && textH > 0) {
                    double shapeArea = shapeW * shapeH;
                    double textArea = textW * textH;
                    if (shapeArea > textArea * SCRIBBLE_AREA_RATIO) continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isGroupedBackgroundShape(
            ResolvedBuildContext ctx, ResolvedTextFrame textFrame,
            ResolvedPageItem textFrameItem, ResolvedPageItem candidate,
            java.util.Set<String> textFrameAncestorIds) {
        if (textFrameItem == null || candidate == null) return false;
        String parentId = textFrameItem.parentId();
        String candidateParentId = candidate.parentId();
        if (parentId == null || candidateParentId == null) return false;
        boolean sameGroup = parentId.equals(candidateParentId)
                || (textFrameAncestorIds != null && textFrameAncestorIds.contains(candidateParentId));
        if (!sameGroup) return false;
        String t = candidate.type();
        if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t)) return false;
        String fc = candidate.fillColorName();
        if (fc == null || "None".equals(fc) || "[None]".equals(fc)) return false;

        // Paper/white shapes inside the same group cluster are usually speech-bubble backgrounds.
        if ("Paper".equals(fc) || "White".equals(fc)) return true;
        String hex = ctx.resolvedData.resolveColorHex(fc);
        if ("#FFFFFF".equalsIgnoreCase(hex)) return true;

        // 색상이 있는 타이틀 배지/말풍선도 같은 그룹 안에서는 TF와 거의 같은 bounds를 가진 배경일 수 있다.
        return hasFrameLikeBackgroundBounds(textFrame, candidate, 5.0, 0.90, 2.25);
    }

    private static boolean isNearbyFrameBackgroundShape(
            ResolvedBuildContext ctx, ResolvedTextFrame textFrame, ResolvedPageItem candidate) {
        if (textFrame == null || candidate == null) return false;
        String t = candidate.type();
        if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t)) return false;
        String fc = candidate.fillColorName();
        if (!isPaperOrWhite(ctx, fc)) return false;
        return hasFrameLikeBackgroundBounds(textFrame, candidate, 4.0, 0.90, 1.50);
    }

    private static boolean hasFrameLikeBackgroundBounds(
            ResolvedTextFrame textFrame, ResolvedPageItem candidate,
            double edgeTol, double minOverlapRatio, double maxAreaRatio) {
        double[] tfb = textFrame.geometricBounds();
        double[] cb = candidate.geometricBounds();
        if (tfb == null || tfb.length < 4 || cb == null || cb.length < 4) return false;

        double tfW = tfb[3] - tfb[1];
        double tfH = tfb[2] - tfb[0];
        double cW = cb[3] - cb[1];
        double cH = cb[2] - cb[0];
        if (tfW <= 0 || tfH <= 0 || cW <= 0 || cH <= 0) return false;

        double overlapW = Math.min(tfb[3], cb[3]) - Math.max(tfb[1], cb[1]);
        double overlapH = Math.min(tfb[2], cb[2]) - Math.max(tfb[0], cb[0]);
        if (overlapW <= 0 || overlapH <= 0) return false;
        double tfArea = tfW * tfH;
        double overlapRatio = (overlapW * overlapH) / tfArea;
        double areaRatio = (cW * cH) / tfArea;

        boolean aligned = Math.abs(cb[0] - tfb[0]) <= edgeTol
                && Math.abs(cb[1] - tfb[1]) <= edgeTol
                && Math.abs(cb[2] - tfb[2]) <= edgeTol
                && Math.abs(cb[3] - tfb[3]) <= edgeTol;
        return aligned && overlapRatio >= minOverlapRatio && areaRatio <= maxAreaRatio;
    }

    private static boolean isPaperOrWhite(ResolvedBuildContext ctx, String colorName) {
        if (colorName == null || "None".equals(colorName) || "[None]".equals(colorName)) return false;
        if ("Paper".equals(colorName) || "White".equals(colorName)) return true;
        String hex = ctx.resolvedData.resolveColorHex(colorName);
        return "#FFFFFF".equalsIgnoreCase(hex);
    }

    private static boolean isNestedInTextFrame(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
        if (pi == null) return false;
        String parentId = pi.parentId();
        for (int depth = 0; depth < 5 && parentId != null; depth++) {
            ResolvedPageItem parent = ctx.resolvedData.getPageItem(parentId);
            if (parent == null) break;
            if ("TextFrame".equals(parent.type())) return true;
            parentId = parent.parentId();
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

        Collections.sort(gaps);
        double medianGap = gaps.get(gaps.size() / 2);

        // Y 점프 분할 지점 감지 (중앙값의 3배 이상)
        List<Integer> splitPoints = new ArrayList<>(); // 분할 후 새 그룹 시작 라인 인덱스
        for (int i = 1; i < lines.size(); i++) {
            double[] prev = lines.get(i - 1).bounds();
            double[] curr = lines.get(i).bounds();
            if (prev == null || curr == null) continue;
            double gap = curr[0] - prev[0];
            if (gap > medianGap * YGAP_SPLIT_FACTOR) {
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

        String sourceIdBase = ParagraphTextHelpers.domIdToSourceId(tf.id());
        int n = groups.size();
        int tfParaStart = tf.paragraphStart();
        double[] tfGb = tf.geometricBounds();
        if (tfGb == null || tfGb.length < 4) return false;

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
            // 단락 인덱스가 없는 그룹(모든 line.paraIndex < 0)은 charStart/End를 설정할 수 없으므로 스킵
            if (absParaStarts[gi] == Integer.MAX_VALUE) continue;

            List<ResolvedTextFrame.ComposedLine> group = groups.get(gi);

            int firstSubstantive = 0;
            while (firstSubstantive < group.size()) {
                if (isSubstantiveLine(group.get(firstSubstantive).text())) break;
                firstSubstantive++;
            }
            int lastSubstantive = group.size() - 1;
            while (lastSubstantive > firstSubstantive) {
                if (isSubstantiveLine(group.get(lastSubstantive).text())) break;
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
            block.noAutoLineWrap(shouldUseNoAutoLineWrap(group, false, groupText.toString()));
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

    private static boolean shouldUseNoAutoLineWrap(ResolvedTextFrame tf, ASTTextFrameBlock block) {
        if (tf == null) return false;
        boolean suppressLeftIndent = block != null && block.suppressParaLeftIndent();
        if (suppressLeftIndent) return false;
        if (isShortSingleLineTextFrame(tf)) return true;
        return shouldUseNoAutoLineWrap(tf.composedLines(), false, tf.frameVisibleText());
    }

    private static boolean shouldUseVisualShellNoAutoLineWrap(
            boolean hasRenderedVisualShell,
            ResolvedTextFrame tf,
            ASTTextFrameBlock block) {
        if (!hasRenderedVisualShell || tf == null) return false;
        if (block != null && block.suppressParaLeftIndent()) return false;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.size() != 1) return false;
        String visibleText = tf.frameVisibleText();
        if (visibleText == null || visibleText.indexOf('\n') >= 0 || visibleText.indexOf('\r') >= 0) {
            return false;
        }
        return tf.paragraphStart() == tf.paragraphEnd();
    }

    private static boolean shouldUseNoAutoLineWrap(
            List<ResolvedTextFrame.ComposedLine> lines,
            boolean suppressLeftIndent,
            String visibleText) {
        if (suppressLeftIndent || lines == null || lines.size() < 2) return false;
        if (visibleText != null && visibleText.startsWith("\uFFFC")) return false;

        Set<Integer> paragraphIndices = new HashSet<>();
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line == null) return false;
            int paraIndex = line.paraIndex();
            if (paraIndex < 0 || !paragraphIndices.add(paraIndex)) {
                return false;
            }
        }
        return paragraphIndices.size() == lines.size();
    }

    private static boolean isShortSingleLineTextFrame(ResolvedTextFrame tf) {
        if (tf == null) return false;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.size() != 1) return false;
        String visibleText = tf.frameVisibleText();
        if (visibleText == null || visibleText.startsWith("\uFFFC")) return false;
        if (visibleText.indexOf('\n') >= 0 || visibleText.indexOf('\r') >= 0) return false;
        if (tf.paragraphStart() != tf.paragraphEnd()) return false;
        if (tf.frameParaTexts() != null && tf.frameParaTexts().size() != 1) return false;

        String normalized = visibleText
                .replace("\uFFFC", "")
                .replace("\u0007", "")
                .replaceAll("\\s+", "")
                .trim();
        return !normalized.isEmpty() && normalized.length() <= SHORT_SINGLE_LINE_NO_WRAP_CHARS;
    }
}
