package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.GroupedFlowStackPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTabStop;
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
 * <p>{@code ResolvedToASTBuilder.placeTextFrames + isNestedInTextFrame}
 * 에서 stateless static helper로 발췌.</p>
 */
public final class FramePlacer {

    private FramePlacer() {}

    // ---- 튜닝 상수 --------------------------------------------------------
    /** 일부 문단만 text-wrap 으로 왼쪽이 밀릴 때 문단 leftIndent 를 중복 적용하지 않는 최소 wrap 폭. */
    private static final double PARTIAL_LEFT_WRAP_MIN_PT = 10.0;
    /** 일부 문단만 text-wrap 으로 왼쪽이 밀릴 때 frame 폭 대비 최소 wrap 비율. */
    private static final double PARTIAL_LEFT_WRAP_FRAME_RATIO = 0.18;
    /** HWPX drawText가 원본 fontSize보다 작은 박스로 텍스트를 SQUEEZE하지 않도록 축을 보존한다. */
    private static final double FONT_AXIS_MIN_RATIO = 1.15;
    /** 단일행 source story 안에 inline shell/line이 섞이면 HWPX 행 높이가 더 필요하다. */
    private static final double INLINE_ANCHOR_SINGLE_LINE_MIN_AXIS_RATIO = 1.60;
    /** composed line ink가 선언 font보다 작은 장식/삽화 내부 TF는 ink bounds를 기준으로 축을 보존한다. */
    private static final double COMPOSED_INK_FONT_CAP_RATIO = 1.15;
    private static final double COMPOSED_INK_MIN_PT = 4.0;
    // -----------------------------------------------------------------------

    public static void placeTextFrames(ResolvedBuildContext ctx, List<ASTSection> sections) {
        List<ResolvedTextFrame> frames = ctx.resolvedData.textFrames();

        FrameIndex idx = buildIndex(ctx.resolvedData.allRenderedFloatingItems(), frames, ctx);
        ctx.conceptDiagramTextFrameIds.addAll(collectConceptDiagramTextFrameIds(ctx, frames));

        // FP-B: title overlay 및 inline Y-조정 내부 루프에서 같은 페이지 TF만 검색하도록
        // pageIndex → TF 목록 사전 구축 (O(N²) → O(N) per TF)
        Map<Integer, List<ResolvedTextFrame>> inlineFramesByPage = new HashMap<>();
        for (ResolvedTextFrame _f : frames) {
            if (_f.isInline()) inlineFramesByPage.computeIfAbsent(_f.pageIndex(), k -> new ArrayList<>()).add(_f);
        }

        for (ResolvedTextFrame tf : frames) {
            int tfDomId = parseDomIdOrNeg(tf.id());
            ObjectPlan textPlan = ctx.findTextFrameOwnershipPlan(tfDomId);
            boolean planKnown = textPlan != null;
            boolean ownedByFloatingTextShell = ctx.isTextFrameOwnedByFloatingTextShellPlan(tfDomId);
            if (planKnown
                    && (textPlan.materialization == Materialization.HWPX_TABLE_STYLE
                    || textPlan.visualAction == VisualAction.PLACE_TABLE_STYLE)) {
                continue;
            }
            if (planKnown && textPlan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                    && !ownedByFloatingTextShell) {
                continue;
            }
            if (planKnown && textPlan.placement == Placement.INLINE && !ownedByFloatingTextShell) {
                continue;
            }
            boolean conceptDiagramTf = ctx.conceptDiagramTextFrameIds.contains(tf.id());
            boolean hwpxOwnedTextFrame = ctx.resolvedData.isHwpxOwnedTextFrame(tf.id());
            boolean plannedFloatingHwpxText =
                    ownedByFloatingTextShell
                            || (planKnown
                            ? textPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                                && textPlan.placement == Placement.FLOATING
                            : ctx.ownershipPlanPlacesFloatingHwpxText(tfDomId));
            boolean editableForHwpx = ctx.resolvedData.isEditableTextFrame(tf.id()) || hwpxOwnedTextFrame;
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) {
                continue;
            }
            if (tf.isInline()
                    && !plannedFloatingHwpxText
                    && (InlineFrameHandler.isEditableTextFrameOfPlannedInlinePngWithSeparateHwpxText(
                    ctx, tf.id())
                    || InlineFrameHandler.isEditableTextFrameOfPlannedInlineTextShell(
                    ctx, tf.id()))) {
                continue;
            }

            if (tf.isInline()) {
                if (!plannedFloatingHwpxText) {
                    continue;
                }
                ctx.setTextDisposition(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED);
            }
            boolean inlineToFloating = tf.isInline() && plannedFloatingHwpxText;
            boolean hasPlannedTextShell = hasPlannedTextShellForTextFrame(ctx, tfDomId);
            boolean hasNativeSourceTextShell = hasNativeSourceTextShellPlan(ctx, tfDomId);
            boolean plannedVisualTextOverlay = plannedFloatingHwpxText
                    && hasPlannedVisualTextOverlayForTextFrame(ctx, tfDomId);
            boolean hasRenderedVisualShell = hasPlannedTextShell && !hasNativeSourceTextShell;

            // 숨김/비인쇄 TF → 변환 불필요
            if (tf.sourceHidden()) { continue; }

            // 마스터 인스턴스 TF가 composed되지 않은 경우 (lineCount=0) → 해당 페이지에서 override됨 → skip
            if (tf.isMasterInstance() && tf.lineCount() == 0) { continue; }

            // 다른 TextFrame 안에 중첩된 프레임은 건너뜀 (부모가 배경에 포함)
            if (!inlineToFloating && isNestedInTextFrame(ctx, tf)) {
                continue;
            }
            // 배경에 포함된 legacy non-editable 프레임은 건너뜀.
            // Stage 1 ObjectPlan이 HWPX text ownership을 결정한 프레임은
            // 후속 휴리스틱으로 다시 skip하지 않는다.
            if (!inlineToFloating && !editableForHwpx && !planKnown) {
                if (shouldSkipNonEditableTf(ctx, tf, tfDomId, idx)) continue;
            }
            // badge_group_child(non-editable)는 부모 PNG가 텍스트를 포함하므로 글상자 배치 건너뜀.
            // source ownership policy: editable로 승격된 frame은 !isEditableTextFrame 가드로 보호됨 → 건너뛰지 않음
            boolean skipAsBadgeChild = !editableForHwpx
                    && tfDomId >= 0 && idx.badgeChildDomIds.contains(tfDomId);
            if (skipAsBadgeChild) { continue; }

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

            // source ownership policy: linked/threaded TextFrames do not merge geometry.
            boolean hasNextPageChain = hasNextPageChainOnDifferentPage(ctx, tf);

            LocalFrameBounds localBounds = computeLocalFrameBounds(
                    gb, pageLeft, pageTop);
            double x = localBounds.x;
            double y = localBounds.y;
            double w = localBounds.w;
            double h = localBounds.h;
            if (hasAnchoredTablePlan(ctx, tfDomId)) {
                LocalFrameBounds pageRelativeBounds = computeScaledPageRelativeFrameBounds(ctx, tf);
                if (pageRelativeBounds != null) {
                    x = pageRelativeBounds.x;
                    y = pageRelativeBounds.y;
                    w = pageRelativeBounds.w;
                    h = pageRelativeBounds.h;
                }
            }
            ASTSection section = sections.get(pageIdx);
            boolean hasVisibleText = hasVisibleTextExcludingObjectControls(tf.frameVisibleText());
            if (!hasVisibleText) {
                // Object-replacement-only carrier TFs still come from resolved page-relative
                // coordinates. Convert through the same scaleFactor contract as visible frames.
                LocalFrameBounds pageRelativeBounds = computeScaledPageRelativeFrameBounds(ctx, tf);
                if (pageRelativeBounds != null) {
                    x = pageRelativeBounds.x;
                    y = pageRelativeBounds.y;
                    w = pageRelativeBounds.w;
                    h = pageRelativeBounds.h;
                }
            }

            // 음수 좌표 클램핑
            if (x < 0) { w += x; x = 0; }
            if (y < 0) {
                double origH = h;
                h += y;
                y = 0;
                // source ownership policy: _oc 해시라 헤더 등 페이지 위쪽 경계선에 위치한 TF (예: y=-8, h=8) 는
                // 클램핑 후 h=0 이 되어 스킵됨 → 원래 높이를 복원해 페이지 상단에 배치.
                if (h <= 0 && origH > 0) h = origH;
            }
            if (w <= 0 || h <= 0) {
                continue;
            }

            if (!hasVisibleText) {
                double[] clipped = clipEmptyTextFrameToPage(x, y, w, h, rPage);
                if (clipped == null) {
                    continue;
                }
                x = clipped[0];
                y = clipped[1];
                w = clipped[2];
                h = clipped[3];
            }

            if (hasAnchoredTablePlan(ctx, tfDomId)) {
                LocalFrameBounds pageRelativeBounds = computeScaledPageRelativeFrameBounds(ctx, tf);
                if (pageRelativeBounds != null) {
                    x = pageRelativeBounds.x;
                    y = pageRelativeBounds.y;
                    w = pageRelativeBounds.w;
                    h = pageRelativeBounds.h;
                }
            }
            if (w <= 0) continue;

            ASTTextFrameBlock block = new ASTTextFrameBlock();
            // source ownership policy: master instance clones use synthetic ids like "2453_pi20" — not pure numeric.
            block.sourceId(ParagraphTextHelpers.domIdToSourceId(tf.id()));
            block.storyId(tf.storyId());
            // \uFFFC 로 시작하는 TF에 inline TF가 좌측 가장자리를 공유하는 경우
            // (예: 단락 번호 "1" TF가 본문 TF 왼쪽에 맞닿음)
            // → inline marker 는 자신의 원래 위치를 가진 별도 객체다. body TF 의 물리 bounds 를
            //    marker 까지 확장하면 번호/본문 묶음이 과도하게 커지고 원본 anchor 가 무너진다.
            //    따라서 여기서는 단락 left-indent 정책만 판단하고 x/y/w/h 는 원본 frame bounds 를 보존한다.
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
                        // IndentToHere(ACE 7/8)가 있으면 본문은 선행 인라인 번호 오른쪽에서
                        // 시작해야 한다. 이 경우 left indent 억제를 적용하면 번호와 본문이 겹친다.
                        boolean _hasIndentToHere = _fvtInl.indexOf('\u0007') >= 0
                                || _fvtInl.indexOf('\u0008') >= 0;
                        break;
                    }
                }
            }
            boolean verticalComposedText = isVerticalComposedTextFrame(tf);
            boolean fontAxisExpanded = false;
            double maxFontSizePt = maxFontSizePt(ctx, tf);
            double composedInkFontCapPt = composedInkFontCapPt(tf, maxFontSizePt, ctx.scaleFactor);
            double axisFontSizePt = composedInkFontCapPt > 0 ? composedInkFontCapPt : maxFontSizePt;
            if (axisFontSizePt > 0) {
                double minTextAxis = axisFontSizePt * FONT_AXIS_MIN_RATIO;
                if (verticalComposedText && w < minTextAxis) {
                    double delta = minTextAxis - w;
                    x -= delta / 2.0;
                    w = minTextAxis;
                    fontAxisExpanded = true;
                } else if (!verticalComposedText && h < minTextAxis) {
                    double delta = minTextAxis - h;
                    y -= delta / 2.0;
                    h = minTextAxis;
                    fontAxisExpanded = true;
                }
            }
            block.x(CoordinateConverter.pointsToHwpunits(x));
            block.y(CoordinateConverter.pointsToHwpunits(y));
            block.width(CoordinateConverter.pointsToHwpunits(w));
            block.height(CoordinateConverter.pointsToHwpunits(h));
            if (verticalComposedText) {
                block.verticalText(true);
            }
            if (GroupedFlowStackPolicy.isFlowStackTitleTextFrame(ctx, tf)) {
                block.anchoredFlowWithText(true);
            }
            block.zOrder(planKnown ? textPlan.zOrder : tf.zOrder());
            block.columnCount(tf.columnCount() > 0 ? tf.columnCount() : 1);
            // ResolvedData has already normalized TextColumnGutter to page units.
            block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));

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
            boolean textFrameStyleOwnedByVisibleShell =
                    ctx.isTextFrameStyleOwnedByVisibleTextShellPlan(tfDomId);
            if (!textFrameStyleOwnedByVisibleShell) {
                try {
                    String fillName = tf.fillColor();
                    if (fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
                        String fillHex = ctx.resolvedData.resolveTintedColorHex(fillName, tf.fillTint());
                        if (fillHex != null) {
                            block.fillColor(fillHex);
                            block.fillTint(100);
                            block.nativeGraphicsAllowed(true);
                        }
                    }
                    // TF 자체 strokeColor/strokeWeight 복사 (배지 자식 override 이전 기본값).
                    // fillColor와 동일한 방식: page_bg에 border가 없는 editable 텍스트박스는
                    // TF stroke로 HWPX 테두리를 그려야 시각적으로 border가 보임.
                    boolean hasVisibleFrameStroke = false;
                    if (tf.strokeColor() != null && !"None".equals(tf.strokeColor()) && !"[None]".equals(tf.strokeColor())
                            && tf.strokeWeight() > 0) {
                        String strokeHex = ctx.resolvedData.resolveColorHex(tf.strokeColor());
                        if (strokeHex != null) {
                            block.strokeColor(strokeHex);
                            block.strokeWeight(tf.strokeWeight());
                            block.nativeGraphicsAllowed(true);
                            hasVisibleFrameStroke = true;
                        }
                    }
                    if (tf.cornerRadius() > 0
                            && (block.fillColor() != null || hasVisibleFrameStroke)) {
                        block.cornerRadius(tf.cornerRadius() * ctx.scaleFactor);
                    }
                } catch (Exception eFill) {
                    System.err.println("[FramePlacer] fill/stroke 속성 적용 오류 tf=" + tf.id() + ": " + eFill);
                }
            }
            java.util.Set<String> releasedFillIds = releasedNativeFillChildIdsForTf(ctx, tfDomId);
            if (!hasPlannedTextShell
                    && (!hasRenderedVisualShell || hasAbsorbedTextStylePlan(ctx, tfDomId)
                    || !releasedFillIds.isEmpty())) {
                // PNG가 풀어준 도형이 있으면 그 id로만 형제 흡수를 제한(다른 장식 흡수 방지).
                applyGroupBackgroundShapeStyle(ctx, tf, block,
                        releasedFillIds.isEmpty() ? null : releasedFillIds);
                // released 경로(사이드박스 대형 배경/제목바)만 전역 정책과 무관하게 강제 fill.
                if (!releasedFillIds.isEmpty()
                        && block.fillColor() != null && block.fillColor().startsWith("#")) {
                    block.forceNativeFill(true);
                    // 흡수된 배경 도형은 별도 complex_graphic PNG로도 추출될 수 있으므로
                    // 동일 slot의 visible 실행을 막기 위해 흡수 source로 기록한다.
                    for (String fid : releasedFillIds) {
                        try { ctx.nativeFillAbsorbedIds.add(Integer.parseInt(fid)); }
                        catch (NumberFormatException ignore) { }
                    }
                }
            }
            // overflow 감지용 텍스트 길이 저장
            String visText = tf.frameVisibleText();
            if (visText != null) {
                block.frameVisibleText(visText);
                int visibleLen = visibleTextLength(visText);
                int inlineEditableLen = editableInlineTextLengthForStory(ctx, tf.storyId());
                block.frameVisibleTextLength(Math.max(visibleLen, inlineEditableLen));
            }
            boolean preserveFontSize = composedInkFontCapPt <= 0
                    && (verticalComposedText || fontAxisExpanded);
            boolean sourceSingleLineOverlay = plannedVisualTextOverlay
                    && shouldUseVisualShellNoAutoLineWrap(true, tf, block);
            boolean fixedSingleLineTitleOrLabel = isFixedSingleLineTitleOrLabel(
                    ctx, tf, hasRenderedVisualShell, plannedVisualTextOverlay);
            block.noAutoLineWrap(sourceSingleLineOverlay
                    || block.sourceComposedFixedText()
                    || fixedSingleLineTitleOrLabel
                    || (!preserveFontSize
                    && (shouldUseNoAutoLineWrap(ctx, tf, block, fixedSingleLineTitleOrLabel)
                    || shouldUseVisualShellNoAutoLineWrap(hasRenderedVisualShell, tf, block))));
            if (block.noAutoLineWrap() && !block.sourceComposedFixedText()
                    && !isSourceSingleLineTextFrame(tf)) {
                block.noAutoLineWrap(false);
            }
            // storyTotalTextLength는 convertStories()에서 설정

            if (inlineToFloating) {
                block.inlineToFloating(true);
            }
            if (plannedVisualTextOverlay || hasPlannedTextShell) {
                String shellLayer = textShellVisualLayerForTextFrame(ctx, tfDomId);
                if ((shellLayer == null || shellLayer.isEmpty()) && hasPlannedTextShell) {
                    shellLayer = VisualLayer.LABEL_BACKDROP.name();
                }
                if (shellLayer != null && !shellLayer.isEmpty()) {
                    block.plannedShellVisualLayer(shellLayer);
                }
                block.plannedVisualTextOverlay(!hasNativeSourceTextShell);
                if (hasNativeSourceTextShell) {
                    block.nativeGraphicsAllowed(false);
                    block.forceNativeFill(false);
                }
            }
            section.addBlock(block);
        }
    }

    private static String textShellVisualLayerForTextFrame(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || tfDomId < 0 || ctx.ownershipPlans == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (!containsInt(plan.ownedTextFrameIds, tfDomId)
                    && !containsInt(plan.styleSourceObjectIds, tfDomId)) {
                continue;
            }
            if (plan.visualLayer != null) return plan.visualLayer.name();
        }
        return null;
    }

    private static boolean hasPlannedVisualTextOverlayForTextFrame(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || ctx.resolvedData == null || tfDomId < 0) return false;
        if (ctx.ownershipPlans != null) {
            for (ObjectPlan plan : ctx.ownershipPlans) {
                if (plan == null) continue;
                if (!ShellRole.isTextShell(plan)) continue;
                if (plan.placement != Placement.FLOATING) continue;
                if (!containsInt(plan.ownedTextFrameIds, tfDomId)) continue;
                if (!plan.hasVisibleVisual()) continue;
                return true;
            }
        }
        List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return false;
        String tfId = String.valueOf(tfDomId);
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.file() == null || rg.file().isEmpty()) continue;
            if (!containsEditableTextFrameId(rg, tfId)) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.hasVisibleVisual()) return true;
        }
        return false;
    }

    private static boolean hasPlannedTextShellForTextFrame(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || tfDomId < 0) return false;
        if (ctx.isTextFrameOwnedByTextShellPlan(tfDomId)) return true;
        if (ctx.isTextFrameStyleOwnedByVisibleTextShellPlan(tfDomId)) return true;
        if (ctx.resolvedData == null) return false;
        List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return false;
        String tfId = String.valueOf(tfDomId);
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.file() == null || rg.file().isEmpty()) continue;
            if (!containsEditableTextFrameId(rg, tfId)) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null) continue;
            if (ShellRole.isTextShell(plan)
                    && containsInt(plan.ownedTextFrameIds, tfDomId)
                    && plan.hasVisibleVisual()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNativeSourceTextShellPlan(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || tfDomId < 0 || ctx.ownershipPlans == null) return false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.materialization != Materialization.NATIVE_SOURCE_SHAPE) continue;
            if (containsInt(plan.ownedTextFrameIds, tfDomId)) return true;
        }
        return false;
    }

    private static boolean hasNextPageChainOnDifferentPage(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.nextFrameId() == null) return false;
        String nextId = tf.nextFrameId();
        Set<String> seen = new HashSet<>();
        while (nextId != null && seen.add(nextId)) {
            ResolvedTextFrame next = ctx.resolvedData.getTextFrame(nextId);
            if (next == null) return false;
            if (next.pageIndex() != tf.pageIndex()) return true;
            nextId = next.nextFrameId();
        }
        return false;
    }

    private static boolean hasObjectReplacementText(String text) {
        return text != null && (text.indexOf('\uFFFC') >= 0 || text.indexOf('￼') >= 0);
    }

    private static boolean isDirectChildOf(ResolvedBuildContext ctx, String childId, int parentDomId) {
        if (ctx == null || ctx.resolvedData == null || childId == null) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(childId);
        return item != null && String.valueOf(parentDomId).equals(item.parentId());
    }

    /**
     * 이 TF를 owner로 하는 deco PNG가 "굽지 않고 풀어준" 대형 배경 도형 DOM ID 집합.
     * (extract_indd.jsx가 nativeFillChildIds로 명시 → Java가 네이티브 fill로 렌더)
     * 비어있지 않으면 PNG 셸이 있어도 applyGroupBackgroundShapeStyle 게이트를 열어 형제 도형 fill을 흡수.
     */
    private static java.util.Set<String> releasedNativeFillChildIdsForTf(ResolvedBuildContext ctx, int tfDomId) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (ctx == null || ctx.resolvedData == null || tfDomId < 0) return ids;
        List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return ids;
        String tfId = String.valueOf(tfDomId);
        for (RenderedGroup rg : groups) {
            if (rg == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null) continue;
            String[] editableIds = rg.editableTextFrameIds();
            if (editableIds == null || rg.nativeFillChildIds() == null) continue;
            boolean ownsTf = false;
            for (String editableId : editableIds) {
                if (tfId.equals(editableId)) { ownsTf = true; break; }
            }
            if (!ownsTf) continue;
            for (int id : rg.nativeFillChildIds()) ids.add(String.valueOf(id));
        }
        return ids;
    }

    private static boolean hasAbsorbedTextStylePlan(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || tfDomId < 0 || ctx.ownershipPlans == null) return false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.visualAction != VisualAction.ABSORB_TEXT_STYLE) continue;
            if (plan.domId == tfDomId) return true;
            if (plan.sourceObjectIds == null) continue;
            for (int sourceObjectId : plan.sourceObjectIds) {
                if (sourceObjectId == tfDomId) return true;
            }
        }
        return false;
    }

    private static boolean hasAnchoredTablePlan(ResolvedBuildContext ctx, int tfDomId) {
        return ctx != null
                && tfDomId >= 0
                && ctx.anchoredTablePlansForOwnerTextFrame(tfDomId) != null
                && !ctx.anchoredTablePlansForOwnerTextFrame(tfDomId).isEmpty();
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w > 0 && h > 0 ? w * h : 0.0;
    }

    private static double overlapArea(double[] a, double[] b) {
        double left = Math.max(a[1], b[1]);
        double top = Math.max(a[0], b[0]);
        double right = Math.min(a[3], b[3]);
        double bottom = Math.min(a[2], b[2]);
        double w = right - left;
        double h = bottom - top;
        return w > 0 && h > 0 ? w * h : 0.0;
    }

    /**
     * non-editable + non-inlineToFloating TF의 배치 여부를 결정한다.
     * true 반환 시 해당 TF를 건너뜀, false 반환 시 글상자 배치 계속.
     */
    private static boolean shouldSkipNonEditableTf(
            ResolvedBuildContext ctx, ResolvedTextFrame tf, int tfDomId, FrameIndex idx) {
        if (shouldPlaceVisualLabelTextSeparately(ctx, tf)) return false;
        if (hasEditableInlineTextHiddenGroupInStory(ctx, tf.storyId())) return false;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) return true;

        // domId=None TF: ExtendScript가 domId를 얻지 못해 editability 확인 불가.
        // storyId가 있고 비-숨김/비인쇄이면 IDML에 실제 내용이 있을 수 있으므로 배치 허용.
        if (tf.id() == null && tf.storyId() != null && !tf.sourceHidden()) return false;

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
            return false; // 글상자로 배치
        }
        return !_nonRenderedWithText; // nonRenderedWithText → 배치(false), 그 외 → 건너뜀(true)
    }

    private static boolean hasEditableInlineTextHiddenGroupInStory(
            ResolvedBuildContext ctx, String storyId) {
        return editableInlineTextLengthForStory(ctx, storyId) > 0;
    }

    private static int editableInlineTextLengthForStory(
            ResolvedBuildContext ctx, String storyId) {
        if (ctx == null || ctx.resolvedData == null || storyId == null) return 0;
        List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return 0;
        int total = 0;
        for (RenderedGroup rg : groups) {
            if (rg == null) continue;
            if (!storyId.equals(rg.parentStoryId())) continue;
            if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) continue;
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            String[] editableIds = rg.editableTextFrameIds();
            if (editableIds == null || editableIds.length == 0) continue;
            for (String editableId : editableIds) {
                ResolvedTextFrame child = ctx.resolvedData.getTextFrame(editableId);
                String text = child != null ? child.frameVisibleText() : null;
                int len = visibleTextLength(text);
                if (len > 0) total += len;
            }
        }
        return total;
    }

    private static int visibleTextLength(String text) {
        if (text == null) return 0;
        return text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0007", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
                .length();
    }

    private static boolean shouldPlaceVisualLabelTextSeparately(
            ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || tf == null || tf.id() == null) return false;
        if (tf.sourceHidden()) return false;
        int domId;
        try {
            domId = Integer.parseInt(tf.id());
        } catch (NumberFormatException e) {
            return false;
        }
        ObjectPlan plan = ctx.findTextFrameOwnershipPlan(domId);
        return plan != null && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT;
    }

    private static int parseDomIdOrNeg(String id) {
        if (id == null) return -1;
        try { return Integer.parseInt(id); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Stage 1 plan이 style source로 명시한 형제 도형만 TF block style로 흡수한다.
     * 실행 단계에서 overlap만 보고 새로운 shell/style owner를 찾지 않는다.
     */
    private static void applyGroupBackgroundShapeStyle(
            ResolvedBuildContext ctx, ResolvedTextFrame tf, ASTTextFrameBlock block,
            java.util.Set<String> allowedSourceIds) {
        if (allowedSourceIds == null || allowedSourceIds.isEmpty()) return;
        ResolvedPageItem tfItem = ctx.resolvedData.getPageItem(tf.id());
        if (tfItem == null || tfItem.parentId() == null || tf.geometricBounds() == null) return;

        double[] tfb = tf.geometricBounds();
        ResolvedPageItem parent = ctx.resolvedData.getPageItem(tfItem.parentId());
        if (isTextShellShape(parent) && overlapRatio(tfb, parent.geometricBounds()) >= 0.75
                && allowedSourceIds.contains(parent.id())) {
            applyPageItemStyleToBlock(ctx, parent, block);
            return;
        }

        ResolvedPageItem best = null;
        double bestScore = 0.0;
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || pi.id() == null || pi.id().equals(tf.id())) continue;
            if (!allowedSourceIds.contains(pi.id())) continue;
            if (!tfItem.parentId().equals(pi.parentId())) continue;
            if (!isTextShellShape(pi)) continue;
            double[] pb = pi.geometricBounds();
            if (pb == null || pb.length < 4) continue;
            double score = overlapRatio(tfb, pb);
            if (score > bestScore) {
                bestScore = score;
                best = pi;
            }
        }
        if (best == null || bestScore < 0.75) return;

        applyPageItemStyleToBlock(ctx, best, block);
    }

    private static boolean isTextShellShape(ResolvedPageItem item) {
        if (item == null) return false;
        String t = item.type();
        if (!"Rectangle".equals(t) && !"Polygon".equals(t) && !"Oval".equals(t)) return false;
        double[] gb = item.geometricBounds();
        return gb != null && gb.length >= 4;
    }

    private static void applyPageItemStyleToBlock(
            ResolvedBuildContext ctx, ResolvedPageItem source, ASTTextFrameBlock block) {
        applyPageItemStyleToBlock(ctx, source, block, false);
    }

    private static void applyPageItemStyleToBlock(
            ResolvedBuildContext ctx,
            ResolvedPageItem source,
            ASTTextFrameBlock block,
            boolean overrideExistingStyle) {
        if (source == null || block == null) return;

        String fillName = source.fillColorName();
        if ((overrideExistingStyle || isUnsetColor(block.fillColor()))
                && fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
            String fillHex = ctx.resolvedData.resolveTintedColorHex(fillName, source.fillTint());
            if (fillHex != null) {
                block.fillColor(fillHex);
                block.fillTint(100);
                block.nativeGraphicsAllowed(true);
            }
        }

        String strokeName = source.strokeColorName();
        if ((overrideExistingStyle || isUnsetColor(block.strokeColor()))
                && strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)
                && source.strokeWeight() > 0) {
            String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
            if (strokeHex != null) {
                block.strokeColor(strokeHex);
                block.strokeWeight(normalizeSourceStrokeWeightPt(ctx, source.strokeWeight()));
                block.strokeTint(ColorResolver.normalizeTint(source.strokeTint()));
                block.nativeGraphicsAllowed(true);
            }
        }

        if (block.cornerRadius() <= 0 && source.cornerRadius() > 0) {
            block.cornerRadius(source.cornerRadius());
            block.nativeGraphicsAllowed(true);
        }
    }

    private static boolean isUnsetColor(String color) {
        if (color == null) return true;
        String c = color.trim();
        return c.isEmpty() || "None".equals(c) || "[None]".equals(c) || !c.startsWith("#");
    }

    private static double normalizeSourceStrokeWeightPt(ResolvedBuildContext ctx, double strokeWeight) {
        if (strokeWeight <= 0) return strokeWeight;
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        if (Math.abs(scale - 1.0) < 0.001) return strokeWeight;
        return strokeWeight / scale;
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
            if (isRenderedInlineObject(_rgi) && _rgi.file() != null && !_rgi.file().isEmpty()) {
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

    private static boolean isOwnedTextShellPlan(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return false;
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        if (plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && ShellRole.isTextShell(plan)) {
            return true;
        }
        return false;
    }

    private static boolean containsInt(int[] values, int expected) {
        if (values == null) return false;
        for (int value : values) {
            if (value == expected) return true;
        }
        return false;
    }

    private static boolean isRenderedPageObject(RenderedGroup rg) {
        if (rg == null) return false;
        String itemType = rg.itemType();
        if (itemType == null || itemType.isEmpty()) {
            itemType = rg.type();
        }
        return "page_object".equals(itemType);
    }

    private static boolean isRenderedInlineObject(RenderedGroup rg) {
        if (rg == null) return false;
        String itemType = rg.itemType();
        if (itemType == null || itemType.isEmpty()) {
            itemType = rg.type();
        }
        return "inline_object".equals(itemType);
    }

    private static boolean containsEditableTextFrameId(RenderedGroup rg, String tfId) {
        if (rg == null || tfId == null || rg.editableTextFrameIds() == null) return false;
        for (String editableId : rg.editableTextFrameIds()) {
            if (tfId.equals(editableId)) return true;
        }
        return false;
    }

    private static boolean containsString(String[] values, String expected) {
        if (values == null || expected == null) return false;
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }

    private static boolean isOwnedTextFrameShellReason(String reason) {
        if (reason == null) return false;
        return reason.contains("text_hidden")
                || reason.contains("visual_shell")
                || reason.contains("editable_textframe_visual_shell")
                || reason.contains("image_group")
                || reason.contains("decoration_group")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("label_backdrop_group");
    }

    public static Set<String> collectConceptDiagramTextFrameIds(
            ResolvedBuildContext ctx,
            List<ResolvedTextFrame> frames) {
        Set<String> result = new HashSet<>();
        if (ctx == null || ctx.resolvedData == null || frames == null || frames.isEmpty()) return result;

        Map<String, ResolvedTextFrame> byId = new HashMap<>();
        Map<Integer, List<ResolvedTextFrame>> byPage = new HashMap<>();
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            byId.put(tf.id(), tf);
            byPage.computeIfAbsent(tf.pageIndex(), k -> new ArrayList<>()).add(tf);
        }

        Map<Integer, List<double[]>> clusterBoundsByPage = new HashMap<>();
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!isConceptDiagramShellCandidate(ctx, rg)) continue;
            String[] editableIds = rg.editableTextFrameIds();
            if (editableIds == null || editableIds.length < 3) continue;

            int shortLabels = 0;
            int longTexts = 0;
            for (String editableId : editableIds) {
                ResolvedTextFrame tf = byId.get(editableId);
                String clean = cleanVisibleText(tf);
                if (clean.isEmpty()) continue;
                if (clean.length() <= 18 && !isNumericOnlyActivityMarker(clean)) shortLabels++;
                if (clean.length() >= 18) longTexts++;
            }
            if (shortLabels < 2 || longTexts < 1) continue;

            double[] rb = rg.bounds();
            if (rb == null || rb.length < 4) continue;
            boolean hasIndependentDescription = false;
            for (ResolvedTextFrame tf : byPage.getOrDefault(rg.pageIndex(), Collections.emptyList())) {
                if (tf == null || tf.id() == null) continue;
                if (containsString(editableIds, tf.id())) continue;
                if (!isParentlessTextFrame(ctx, tf)) continue;
                String clean = cleanVisibleText(tf);
                if (clean.length() < 8) continue;
                if (looksLikeNumberedActivityPrompt(clean)) continue;
                double[] tb = boundsOf(tf);
                if (isConceptDescriptionConnected(rb, tb, ctx.scaleFactor)) {
                    hasIndependentDescription = true;
                    result.add(tf.id());
                }
            }
            if (!hasIndependentDescription) continue;

            for (String editableId : editableIds) {
                if (editableId != null) result.add(editableId);
            }
            clusterBoundsByPage.computeIfAbsent(rg.pageIndex(), k -> new ArrayList<>()).add(rb);
        }

        // Concept diagrams often have side-axis headings that are separate TFs
        // outside the visual shell. Add only close parentless headings on pages
        // where a cluster was already confirmed by shell + independent description.
        for (Map.Entry<Integer, List<double[]>> entry : clusterBoundsByPage.entrySet()) {
            int pageIndex = entry.getKey();
            double[] union = unionBounds(entry.getValue());
            if (union == null) continue;
            for (ResolvedTextFrame tf : byPage.getOrDefault(pageIndex, Collections.emptyList())) {
                if (tf == null || tf.id() == null || result.contains(tf.id())) continue;
                if (!isParentlessTextFrame(ctx, tf)) continue;
                String clean = cleanVisibleText(tf);
                if (clean.length() < 4 || clean.length() > 40) continue;
                double[] tb = boundsOf(tf);
                if (tb == null || tb.length < 4) continue;
                double horizontalGap = union[1] - tb[3];
                if (horizontalGap < -4.0 || horizontalGap > 65.0) continue;
                double yOverlap = Math.min(union[2], tb[2]) - Math.max(union[0], tb[0]);
                double tfHeight = tb[2] - tb[0];
                double tfCenterY = (tb[0] + tb[2]) / 2.0;
                boolean verticallyRelated = yOverlap > Math.max(1.0, tfHeight * 0.20)
                        || (tfCenterY >= union[0] - 8.0 && tfCenterY <= union[2] + 8.0);
                if (verticallyRelated) {
                    result.add(tf.id());
                }
            }
        }

        if (!result.isEmpty()) {
            System.err.println("[FramePlacer] concept diagram cluster TFs protected: " + result);
        }
        return result;
    }

    private static boolean isConceptDiagramShellCandidate(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return false;
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        if (plan == null) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (!ShellRole.isTextShell(plan)) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = b[2] - b[0];
        double w = b[3] - b[1];
        return h >= 8.0 && w >= 35.0;
    }

    private static boolean isNumericOnlyActivityMarker(String clean) {
        if (clean == null) return false;
        return clean.trim().matches("[0-9０-９]+");
    }

    private static boolean looksLikeNumberedActivityPrompt(String clean) {
        if (clean == null) return false;
        String s = clean.trim();
        return s.matches("^\\(?[0-9０-９]+\\)?[\\s\\t.．、,，]+.*");
    }

    private static boolean isParentlessTextFrame(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.id() == null) return false;
        ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
        return pi == null || pi.parentId() == null || pi.parentId().isEmpty();
    }

    private static boolean isInsideOrMostlyCovered(double[] shellBounds, double[] tfBounds, double scaleFactor) {
        if (shellBounds == null || shellBounds.length < 4 || tfBounds == null || tfBounds.length < 4) return false;
        if (isInsideOrMostlyCoveredSameScale(shellBounds, tfBounds)) return true;
        if (scaleFactor > 0 && Math.abs(scaleFactor - 1.0) > 0.001) {
            double[] scaled = new double[] {
                    shellBounds[0] * scaleFactor,
                    shellBounds[1] * scaleFactor,
                    shellBounds[2] * scaleFactor,
                    shellBounds[3] * scaleFactor
            };
            return isInsideOrMostlyCoveredSameScale(scaled, tfBounds);
        }
        return false;
    }

    private static boolean isConceptDescriptionConnected(double[] shellBounds, double[] tfBounds, double scaleFactor) {
        if (shellBounds == null || shellBounds.length < 4 || tfBounds == null || tfBounds.length < 4) return false;
        if (isConceptDescriptionConnectedSameScale(shellBounds, tfBounds)) return true;
        if (scaleFactor > 0 && Math.abs(scaleFactor - 1.0) > 0.001) {
            double[] scaled = new double[] {
                    shellBounds[0] * scaleFactor,
                    shellBounds[1] * scaleFactor,
                    shellBounds[2] * scaleFactor,
                    shellBounds[3] * scaleFactor
            };
            return isConceptDescriptionConnectedSameScale(scaled, tfBounds);
        }
        return false;
    }

    private static boolean isConceptDescriptionConnectedSameScale(double[] shellBounds, double[] tfBounds) {
        if (isInsideOrMostlyCoveredSameScale(shellBounds, tfBounds)) return true;
        double tfWidth = tfBounds[3] - tfBounds[1];
        double tfHeight = tfBounds[2] - tfBounds[0];
        if (tfWidth <= 0 || tfHeight <= 0) return false;
        double xOverlap = Math.min(shellBounds[3], tfBounds[3]) - Math.max(shellBounds[1], tfBounds[1]);
        if (xOverlap <= 0 || xOverlap / tfWidth < 0.60) return false;
        double yOverlap = Math.min(shellBounds[2], tfBounds[2]) - Math.max(shellBounds[0], tfBounds[0]);
        if (yOverlap > 0 && yOverlap / tfHeight >= 0.25) return true;
        double verticalGap = tfBounds[0] - shellBounds[2];
        return verticalGap >= 0 && verticalGap <= Math.max(8.0, tfHeight * 0.75);
    }

    private static boolean isInsideOrMostlyCoveredSameScale(double[] outer, double[] inner) {
        if (containsBounds(outer, inner, 1.0)) return true;
        double innerArea = area(inner);
        return innerArea > 0 && overlapArea(outer, inner) / innerArea >= 0.72;
    }

    private static double[] unionBounds(List<double[]> bounds) {
        if (bounds == null || bounds.isEmpty()) return null;
        double[] out = null;
        for (double[] b : bounds) {
            if (b == null || b.length < 4) continue;
            if (out == null) {
                out = new double[] { b[0], b[1], b[2], b[3] };
            } else {
                if (b[0] < out[0]) out[0] = b[0];
                if (b[1] < out[1]) out[1] = b[1];
                if (b[2] > out[2]) out[2] = b[2];
                if (b[3] > out[3]) out[3] = b[3];
            }
        }
        return out;
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

    private static boolean shouldUseNoAutoLineWrap(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            ASTTextFrameBlock block,
            boolean fixedSingleLineTitleOrLabel) {
        if (tf == null) return false;
        if (fixedSingleLineTitleOrLabel) return true;
        return shouldUseNoAutoLineWrap(tf.composedLines(), tf.frameVisibleText());
    }

    private static boolean isFixedSingleLineTitleOrLabel(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            boolean hasRenderedVisualShell,
            boolean plannedVisualTextOverlay) {
        if (!isSourceSingleLineTextFrame(tf)) return false;
        if (hasRenderedVisualShell || plannedVisualTextOverlay) return true;
        if (hasTextShellPlan(ctx, tf)) return true;
        return startsWithInlineAnchor(ctx, tf);
    }

    private static boolean hasTextShellPlan(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || tf == null) return false;
        int tfDomId = parseDomIdOrNeg(tf.id());
        if (tfDomId < 0) return false;
        ObjectPlan plan = ctx.findTextFrameOwnershipPlan(tfDomId);
        if (plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && ShellRole.isTextShell(plan)) {
            return true;
        }
        List<RenderedGroup> groups = ctx.resolvedData != null ? ctx.resolvedData.allRenderedFloatingItems() : null;
        if (groups == null) return false;
        for (RenderedGroup rg : groups) {
            if (rg == null || !containsEditableTextFrameId(rg, tf.id())) continue;
            ObjectPlan rgPlan = ctx.findOwnershipPlanForRendered(rg);
            if (rgPlan != null
                    && rgPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && ShellRole.isTextShell(rgPlan)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithInlineAnchor(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (startsWithObjectReplacementText(tf.frameVisibleText())) return true;
        Boolean textFlowResult = startsWithInlineAnchorFromTextFlow(ctx, tf);
        if (textFlowResult != null) return textFlowResult;
        if (ctx == null || ctx.resolvedData == null || tf.storyId() == null) return false;
        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int start = Math.max(0, tf.paragraphStart());
        if (start >= story.paragraphs().size()) start = 0;
        ResolvedParagraph paragraph = story.paragraphs().get(start);
        if (paragraph == null || paragraph.runs() == null) return false;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null) continue;
            if (run.isInlineAnchor()) return true;
            String text = run.text();
            if (text == null) continue;
            String cleaned = cleanAnchorProbeText(text);
            if (!cleaned.isEmpty()) return false;
        }
        return false;
    }

    private static Boolean startsWithInlineAnchorFromTextFlow(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        if (ctx == null || ctx.textFlowDocument == null || tf == null || tf.storyId() == null) {
            return null;
        }
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(tf.storyId());
        if (unit == null || unit.paragraphs == null || unit.paragraphs.isEmpty()) {
            return null;
        }
        int start = Math.max(0, tf.paragraphStart());
        if (start >= unit.paragraphs.size()) start = 0;
        TextFlowDocument.TextFlowParagraph paragraph = unit.paragraphs.get(start);
        if (paragraph == null || paragraph.atoms == null) {
            return null;
        }
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
            if (atom == null) continue;
            if (atom instanceof TextFlowDocument.InlineSlotAtom) return true;
            if (atom instanceof TextFlowDocument.TextAtom) {
                TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
                String cleaned = cleanAnchorProbeText(textAtom.text);
                if (!cleaned.isEmpty()) return false;
            }
        }
        return false;
    }

    private static String cleanAnchorProbeText(String text) {
        if (text == null) return "";
        return text
                .replace("\uFFFC", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\b", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private static boolean startsWithObjectReplacementText(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '￼') return true;
            if (Character.isWhitespace(ch)
                    || ch == '\u0003'
                    || ch == '\u0007'
                    || ch == '\b'
                    || ch == '\r'
                    || ch == '\n') {
                continue;
            }
            return false;
        }
        return false;
    }

    private static boolean shouldSuppressParaLeftIndentForPartialLeftWrap(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null) return false;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.size() < 3) return false;

        double[] gb = tf.geometricBounds();
        if (gb == null || gb.length < 4) return false;
        double frameW = gb[3] - gb[1];
        if (frameW <= 0) return false;
        double threshold = Math.max(PARTIAL_LEFT_WRAP_MIN_PT, frameW * PARTIAL_LEFT_WRAP_FRAME_RATIO);

        Set<Integer> visibleParas = new HashSet<>();
        Set<Integer> leftWrappedParas = new HashSet<>();
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line == null || line.paraIndex() < 0) continue;
            String text = line.text();
            if (!hasVisibleTextExcludingObjectControls(text)) continue;
            int paraIndex = line.paraIndex();
            visibleParas.add(paraIndex);
            if (line.wrapIndentLeft() > threshold) {
                leftWrappedParas.add(paraIndex);
            }
        }
        if (leftWrappedParas.isEmpty() || visibleParas.size() < 2) return false;
        if (leftWrappedParas.containsAll(visibleParas)) return false;

        ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;

        int storyParaStart = Math.max(0, tf.paragraphStart());
        for (Integer localParaIndex : leftWrappedParas) {
            int storyParaIndex = storyParaStart + localParaIndex;
            ResolvedParagraph rp = paragraphAt(story, storyParaIndex);
            if (rp == null && localParaIndex >= 0) {
                rp = paragraphAt(story, localParaIndex);
            }
            if (isSuppressiblePartialWrapParagraph(rp)) {
                return true;
            }
        }
        return false;
    }

    private static ResolvedParagraph paragraphAt(ResolvedStory story, int index) {
        if (story == null || story.paragraphs() == null) return null;
        if (index < 0 || index >= story.paragraphs().size()) return null;
        return story.paragraphs().get(index);
    }

    private static boolean isSuppressiblePartialWrapParagraph(ResolvedParagraph rp) {
        if (rp == null || rp.leftIndent() == null || rp.leftIndent() <= 0) return false;
        if (isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent())) return false;
        if (hasMeaningfulTabStopsAfterIndent(rp)) return false;
        return !startsWithInlineAnchor(rp);
    }

    private static boolean hasSuppressibleInlineAnchorLeftIndent(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.storyId() == null) return false;
        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;

        int start = Math.max(0, tf.paragraphStart());
        int end = Math.max(start, tf.paragraphEnd());
        boolean checked = false;
        for (int i = start; i <= end && i < story.paragraphs().size(); i++) {
            checked = true;
            if (isSuppressibleInlineAnchorParagraph(story.paragraphs().get(i))) {
                return true;
            }
        }
        if (!checked && start >= story.paragraphs().size()) {
            return isSuppressibleInlineAnchorParagraph(story.paragraphs().get(0));
        }
        return false;
    }

    private static boolean isSuppressibleInlineAnchorParagraph(ResolvedParagraph rp) {
        if (rp == null || rp.leftIndent() == null || rp.leftIndent() <= 0) return false;
        if (isNeutralHangingIndent(rp.leftIndent(), rp.firstLineIndent())) return false;
        return !hasMeaningfulTabStopsAfterIndent(rp);
    }

    private static boolean hasMeaningfulTabStopsAfterIndent(ResolvedParagraph rp) {
        if (rp == null || rp.tabStops() == null || rp.tabStops().isEmpty()) return false;
        double leftIndent = rp.leftIndent() != null ? rp.leftIndent() : 0.0;
        for (ResolvedTabStop tab : rp.tabStops()) {
            if (tab == null) continue;
            String leader = tab.leader();
            if (leader != null && !leader.isEmpty()) return true;
            Double pos = tab.position();
            if (pos != null && pos > leftIndent + 0.5) return true;
        }
        return false;
    }

    private static boolean isNeutralHangingIndent(Double leftIndent, Double firstLineIndent) {
        return leftIndent != null && firstLineIndent != null
                && leftIndent > 0 && firstLineIndent < 0
                && Math.abs(leftIndent + firstLineIndent) < 0.01;
    }

    private static boolean startsWithInlineAnchor(ResolvedParagraph rp) {
        if (rp == null || rp.runs() == null) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun run : rp.runs()) {
            if (run == null) continue;
            if (run.isInlineAnchor()) return true;
            String text = run.text();
            if (text == null) continue;
            String cleaned = cleanAnchorProbeText(text);
            if (!cleaned.isEmpty()) return false;
        }
        return false;
    }

    private static boolean isVerticalComposedTextFrame(ResolvedTextFrame tf) {
        if (tf == null || tf.composedLines() == null || tf.composedLines().isEmpty()) return false;
        double[] frameBounds = tf.pageRelativeBounds();
        if (frameBounds == null || frameBounds.length < 4) frameBounds = tf.geometricBounds();
        if (frameBounds == null || frameBounds.length < 4) return false;

        double frameW = Math.abs(frameBounds[3] - frameBounds[1]);
        double frameH = Math.abs(frameBounds[2] - frameBounds[0]);
        if (frameW <= 0 || frameH <= 0 || frameH <= frameW * 1.2) return false;

        int checked = 0;
        int verticalLike = 0;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            if (!hasVisibleTextExcludingObjectControls(line.text())) continue;
            double[] b = line.bounds();
            double lineW = Math.abs(b[3] - b[1]);
            double lineH = Math.abs(b[2] - b[0]);
            if (lineW <= 0 || lineH <= 0) continue;
            checked++;
            if (lineH > lineW * 1.8) {
                verticalLike++;
            }
        }
        return checked > 0 && verticalLike == checked;
    }

    private static double maxFontSizePt(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.storyId() == null) return 0.0;
        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (story == null || story.paragraphs() == null) return 0.0;

        double max = 0.0;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.fontSize() == null || run.fontSize() <= 0) continue;
                max = Math.max(max, run.fontSize());
            }
        }
        return max;
    }

    private static double composedInkFontCapPt(ResolvedTextFrame tf, double maxFontSizePt, double scaleFactor) {
        if (tf == null || maxFontSizePt <= 0) return 0.0;
        if (tf.composedLines() == null || tf.composedLines().isEmpty()) return 0.0;
        double[] frameBounds = tf.pageRelativeBounds();
        if (frameBounds == null || frameBounds.length < 4) frameBounds = tf.geometricBounds();
        if (frameBounds == null || frameBounds.length < 4) return 0.0;

        double unitScale = scaleFactor > 1.0 ? scaleFactor : 1.0;
        double frameW = Math.abs(frameBounds[3] - frameBounds[1]) / unitScale;
        double frameH = Math.abs(frameBounds[2] - frameBounds[0]) / unitScale;
        double frameMaxAxis = Math.max(frameW, frameH);
        if (frameMaxAxis <= 0 || maxFontSizePt <= frameMaxAxis * 1.20) return 0.0;

        double inkMaxAxis = 0.0;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            if (!hasVisibleTextExcludingObjectControls(line.text())) continue;
            double[] b = line.bounds();
            double lineW = Math.abs(b[3] - b[1]) / unitScale;
            double lineH = Math.abs(b[2] - b[0]) / unitScale;
            if (lineW <= 0 || lineH <= 0) continue;
            inkMaxAxis = Math.max(inkMaxAxis, Math.max(lineW, lineH));
        }
        if (inkMaxAxis <= 0 || inkMaxAxis >= maxFontSizePt * 0.90) return 0.0;
        return Math.max(COMPOSED_INK_MIN_PT, inkMaxAxis * COMPOSED_INK_FONT_CAP_RATIO);
    }

    private static boolean shouldUseVisualShellNoAutoLineWrap(
            boolean hasRenderedVisualShell,
            ResolvedTextFrame tf,
            ASTTextFrameBlock block) {
        if (!hasRenderedVisualShell || tf == null) return false;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.size() != 1) return false;
        String visibleText = tf.frameVisibleText();
        if (visibleText == null || visibleText.indexOf('\n') >= 0 || visibleText.indexOf('\r') >= 0) {
            return false;
        }
        return tf.paragraphStart() == tf.paragraphEnd();
    }

    private static String cleanVisibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private static double[] boundsOf(ResolvedTextFrame tf) {
        if (tf == null) return null;
        double[] b = tf.pageRelativeBounds();
        if (b == null || b.length < 4) b = tf.geometricBounds();
        return b != null && b.length >= 4 ? b : null;
    }

    private static LocalFrameBounds computeLocalFrameBounds(
            double[] geometricBounds,
            double pageLeft,
            double pageTop) {
        double spreadX = geometricBounds[1] - pageLeft;
        double x = (spreadX >= 0) ? spreadX : geometricBounds[1];
        double y = geometricBounds[0] - pageTop;
        return new LocalFrameBounds(
                x,
                y,
                geometricBounds[3] - geometricBounds[1],
                geometricBounds[2] - geometricBounds[0]);
    }

    private static LocalFrameBounds computePageRelativeFrameBounds(ResolvedTextFrame tf) {
        double[] local = tf.pageRelativeBounds();
        if (!isValidBounds(local)) {
            return null;
        }
        return new LocalFrameBounds(
                local[1],
                local[0],
                local[3] - local[1],
                local[2] - local[0]);
    }

    private static LocalFrameBounds computeScaledPageRelativeFrameBounds(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        LocalFrameBounds raw = computePageRelativeFrameBounds(tf);
        if (raw == null) return null;
        double scale = (ctx != null && ctx.scaleFactor > 0) ? ctx.scaleFactor : 1.0;
        return new LocalFrameBounds(
                raw.x * scale,
                raw.y * scale,
                raw.w * scale,
                raw.h * scale);
    }

    private static boolean isValidBounds(double[] bounds) {
        return bounds != null
                && bounds.length >= 4
                && Double.isFinite(bounds[0])
                && Double.isFinite(bounds[1])
                && Double.isFinite(bounds[2])
                && Double.isFinite(bounds[3])
                && bounds[3] > bounds[1]
                && bounds[2] > bounds[0];
    }

    private static double[] clipEmptyTextFrameToPage(
            double x, double y, double w, double h, ResolvedPage page) {
        if (page == null || page.width() <= 0.0 || page.height() <= 0.0) {
            return new double[] { x, y, w, h };
        }
        double left = Math.max(0.0, x);
        double top = Math.max(0.0, y);
        double right = Math.min(page.width(), x + w);
        double bottom = Math.min(page.height(), y + h);
        if (right <= left || bottom <= top) {
            return null;
        }
        return new double[] { left, top, right - left, bottom - top };
    }

    private static final class LocalFrameBounds {
        final double x;
        final double y;
        final double w;
        final double h;

        LocalFrameBounds(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static boolean containsBounds(double[] outer, double[] inner, double tolerancePt) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        return outer[0] <= inner[0] + tolerancePt
                && outer[1] <= inner[1] + tolerancePt
                && outer[2] >= inner[2] - tolerancePt
                && outer[3] >= inner[3] - tolerancePt;
    }

    private static boolean shouldUseNoAutoLineWrap(
            List<ResolvedTextFrame.ComposedLine> lines,
            String visibleText) {
        if (lines == null || lines.size() < 2) return false;
        if (!hasVisibleTextExcludingObjectControls(visibleText)) return false;

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

    private static boolean hasVisibleTextExcludingObjectControls(String visibleText) {
        if (visibleText == null) return false;
        String normalized = visibleText
                .replace("\uFFFC", "")
                .replace("\u0007", "")
                .replace("\b", "")
                .replaceAll("\\s+", "")
                .trim();
        return !normalized.isEmpty();
    }

    private static boolean isSourceSingleLineTextFrame(ResolvedTextFrame tf) {
        if (tf == null) return false;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        boolean singleComposedLine = lines != null && lines.size() == 1;
        boolean singleReportedLine = (lines == null || lines.isEmpty()) && tf.lineCount() == 1;
        if (!singleComposedLine && !singleReportedLine) return false;
        String visibleText = tf.frameVisibleText();
        if (visibleText == null) return false;
        if (visibleText.indexOf('\n') >= 0 || visibleText.indexOf('\r') >= 0) return false;
        if (tf.paragraphStart() != tf.paragraphEnd()) return false;
        if (tf.frameParaTexts() != null && tf.frameParaTexts().size() != 1) return false;
        return hasVisibleTextExcludingObjectControls(visibleText);
    }
}
