package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase2;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
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

        // FP-B: title overlay 및 inline Y-조정 내부 루프에서 같은 페이지 TF만 검색하도록
        // pageIndex → TF 목록 사전 구축 (O(N²) → O(N) per TF)
        Map<Integer, List<ResolvedTextFrame>> inlineFramesByPage = new HashMap<>();
        for (ResolvedTextFrame _f : frames) {
            if (_f.isInline()) inlineFramesByPage.computeIfAbsent(_f.pageIndex(), k -> new ArrayList<>()).add(_f);
        }

        for (ResolvedTextFrame tf : frames) {
            int tfDomId = parseDomIdOrNeg(tf.id());
            ObjectPlan textPlan = ctx.findAnyTextFrameOwnershipPlan(tfDomId);
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
            boolean hwpxOwnedTextFrame = ctx.resolvedData.isHwpxOwnedTextFrame(tf.id());
            if (shouldSkipPlanlessSyntheticCloneTextFrame(ctx, tf, planKnown, ownedByFloatingTextShell,
                    hwpxOwnedTextFrame)) {
                continue;
            }
            boolean plannedFloatingHwpxText =
                    ownedByFloatingTextShell
                            || (planKnown
                            ? textPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                                && textPlan.placement == Placement.FLOATING
                            : ctx.ownershipPlanPlacesFloatingHwpxText(tfDomId));
            boolean editableForHwpx = ctx.resolvedData.isEditableTextFrame(tf.id()) || hwpxOwnedTextFrame;
            if (!plannedFloatingHwpxText && ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) {
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
            // Stage 1 ObjectPlan 없는 non-editable TF는 실행 단계에서
            // HWPX text owner로 새로 승격하지 않는다.
            if (!inlineToFloating && !editableForHwpx && !planKnown) {
                if (shouldSkipNonEditableTf(ctx, tf)) continue;
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

            // source ownership policy: linked/threaded TextFrames do not merge geometry.
            boolean hasNextPageChain = hasNextPageChainOnDifferentPage(ctx, tf);

            LocalFrameBounds localBounds = computeLocalFrameBounds(
                    gb, pageLeft, pageTop);
            double x = localBounds.x;
            double y = localBounds.y;
            double w = localBounds.w;
            double h = localBounds.h;
            boolean usingObjectPlanBounds = false;
            ObjectPlan hwpxTextPlan = ctx.findHwpxTextFrameOwnershipPlan(tfDomId);
            LocalFrameBounds planBounds = computePlannedPageFrameBounds(ctx, hwpxTextPlan);
            if (planBounds != null) {
                x = planBounds.x;
                y = planBounds.y;
                w = planBounds.w;
                h = planBounds.h;
                usingObjectPlanBounds = true;
            } else if (plannedFloatingHwpxText) {
                warnTextPlanMissingExecutableBounds(ctx, hwpxTextPlan, tf);
                continue;
            }
            if (!usingObjectPlanBounds && hasAnchoredTablePlan(ctx, tfDomId)) {
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
            if (!usingObjectPlanBounds && !hasVisibleText) {
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

            if (!usingObjectPlanBounds && hasAnchoredTablePlan(ctx, tfDomId)) {
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
                if (isObjectReplacementOnlyInlinePngCarrier(ctx, tf)) {
                    block.insetTop(0);
                    block.insetLeft(0);
                    block.insetBottom(0);
                    block.insetRight(0);
                } else {
                    block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
                    block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
                    block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
                    block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
                }
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
            java.util.Set<String> plannedStyleSourceIds = plannedTextFrameStyleSourceIds(ctx, tfDomId);
            if (!hasPlannedTextShell && !plannedStyleSourceIds.isEmpty()) {
                applyGroupBackgroundShapeStyle(ctx, block, plannedStyleSourceIds);
                if (block.fillColor() != null && block.fillColor().startsWith("#")) {
                    block.forceNativeFill(true);
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
        return false;
    }

    private static boolean hasPlannedTextShellForTextFrame(ResolvedBuildContext ctx, int tfDomId) {
        if (ctx == null || tfDomId < 0) return false;
        if (ctx.isTextFrameOwnedByTextShellPlan(tfDomId)) return true;
        if (ctx.isTextFrameStyleOwnedByVisibleTextShellPlan(tfDomId)) return true;
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

    private static java.util.Set<String> plannedTextFrameStyleSourceIds(ResolvedBuildContext ctx, int tfDomId) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (ctx == null || tfDomId < 0 || ctx.ownershipPlans == null) return ids;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.styleSourceObjectIds == null
                    || plan.styleSourceObjectIds.length == 0) {
                continue;
            }
            if (!containsInt(plan.ownedTextFrameIds, tfDomId)
                    && plan.domId != tfDomId
                    && !containsInt(plan.sourceObjectIds, tfDomId)) {
                continue;
            }
            for (int styleSourceId : plan.styleSourceObjectIds) {
                if (styleSourceId >= 0) ids.add(String.valueOf(styleSourceId));
            }
        }
        return ids;
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
     * non-editable + non-inlineToFloating + no ObjectPlan TF는 Stage 2에서
     * 새 HWPX text owner로 승격하지 않는다.
     */
    private static boolean shouldSkipNonEditableTf(
            ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (ctx != null) {
            ctx.ownershipWarningLines.add("{\"code\":\"STAGE2_UNPLANNED_NON_EDITABLE_TEXT_FRAME_SKIPPED\""
                    + ",\"textFrameId\":\"" + ObjectPlan.escape(tf != null ? tf.id() : null) + "\""
                    + ",\"storyId\":\"" + ObjectPlan.escape(tf != null ? tf.storyId() : null) + "\""
                    + ",\"detail\":\"non-editable TextFrame without an ObjectPlan was not promoted to HWPX text by FramePlacer\"}");
        }
        return true;
    }

    private static void warnTextPlanMissingExecutableBounds(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            ResolvedTextFrame tf) {
        if (ctx == null) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE2_TEXT_PLAN_MISSING_EXECUTABLE_BOUNDS\""
                + ",\"candidateId\":\"" + ObjectPlan.escape(plan != null ? plan.candidateId : null) + "\""
                + ",\"planPassId\":\"" + ObjectPlan.escape(plan != null ? plan.planPassId : null) + "\""
                + ",\"textFrameId\":\"" + ObjectPlan.escape(tf != null ? tf.id() : null) + "\""
                + ",\"placement\":\"" + (plan != null ? plan.placement : null) + "\""
                + ",\"coordinateSpace\":\"" + (plan != null ? plan.coordinateSpace : null) + "\""
                + ",\"detail\":\"floating HWPX text placement requires a HWPX_TEXT ObjectPlan with PAGE executable bounds; FramePlacer skipped legacy TextFrame geometry\"}");
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
            String[] editableIds = plannedOrLegacyEditableTextFrameIds(ctx, rg);
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

    private static String[] plannedOrLegacyEditableTextFrameIds(
            ResolvedBuildContext ctx,
            RenderedGroup rg) {
        if (rg == null) return new String[0];
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        if (plan != null) {
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) {
                return new String[0];
            }
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
                return new String[0];
            }
            String[] out = new String[plan.ownedTextFrameIds.length];
            for (int i = 0; i < plan.ownedTextFrameIds.length; i++) {
                out[i] = String.valueOf(plan.ownedTextFrameIds[i]);
            }
            return out;
        }
        if (ctx != null && ctx.hasStage1ObjectPlans()) {
            return new String[0];
        }
        if (!rg.hasEditableTextHiddenFromPng()) {
            return new String[0];
        }
        String[] direct = rg.editableTextFrameIds();
        if (direct != null && direct.length > 0) return direct;
        int[] atomic = rg.atomicOwnedTextFrameIds();
        if (atomic == null || atomic.length == 0) return new String[0];
        String[] out = new String[atomic.length];
        for (int i = 0; i < atomic.length; i++) {
            out[i] = String.valueOf(atomic[i]);
        }
        return out;
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

    private static int parseDomIdOrNeg(String id) {
        if (id == null) return -1;
        try { return Integer.parseInt(id); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Stage 1 ownership plan이 있는 실행에서는 synthetic master/off-canvas clone TextFrame을
     * editable fallback만으로 되살리지 않는다.
     *
     * <p>"2453_pi20", "17037_oc24" 같은 clone id는 숫자 domId lookup으로는 매칭되지 않고,
     * 실행 단계가 임의로 HWPX text owner를 만들면 master-derived hidden title/header가
     * 페이지에 떠오르는 회귀가 생긴다. Stage 1이 명시적으로 floating text owner 또는
     * floating text-shell owner를 주지 않았다면 skip한다.</p>
     *
     * <p>단, source extractor가 이미 per-page folio/page number로 확정한 synthetic clone은
     * source metadata의 진실을 따라 HWPX text로 살린다. 이 예외는 page/좌표가 아니라
     * resolved masterSpecialType="pagenum" 메타데이터로만 결정한다.</p>
     */
    private static boolean shouldSkipPlanlessSyntheticCloneTextFrame(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            boolean planKnown,
            boolean ownedByFloatingTextShell,
            boolean hwpxOwnedTextFrame) {
        if (ctx == null || ctx.resolvedData == null || tf == null) return false;
        if (planKnown || ownedByFloatingTextShell || hwpxOwnedTextFrame) return false;
        if (ctx.resolvedData.ownershipPlans() == null || ctx.resolvedData.ownershipPlans().isEmpty()) {
            return false;
        }
        String textFrameId = tf.id();
        if (!isSyntheticCloneTextFrameId(textFrameId)) return false;
        if (isSyntheticPageNumberClone(tf)) return false;
        return tf.isMasterInstance() || textFrameId.contains("_oc");
    }

    private static boolean isSyntheticCloneTextFrameId(String textFrameId) {
        if (textFrameId == null || textFrameId.isEmpty()) return false;
        return textFrameId.contains("_pi") || textFrameId.contains("_oc");
    }

    private static boolean isSyntheticPageNumberClone(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String specialType = tf.masterSpecialType();
        return "pagenum".equals(specialType);
    }

    /**
     * Stage 1 plan이 style source로 명시한 형제 도형만 TF block style로 흡수한다.
     * 실행 단계에서 overlap/bounds로 새로운 shell/style owner를 고르지 않는다.
     */
    private static void applyGroupBackgroundShapeStyle(
            ResolvedBuildContext ctx, ASTTextFrameBlock block,
            java.util.Set<String> plannedStyleSourceIds) {
        if (ctx == null || ctx.resolvedData == null || plannedStyleSourceIds == null
                || plannedStyleSourceIds.isEmpty()) {
            return;
        }
        for (String sourceId : plannedStyleSourceIds) {
            ResolvedPageItem source = ctx.resolvedData.getPageItem(sourceId);
            if (!isTextShellShape(source)) continue;
            applyPageItemStyleToBlock(ctx, source, block);
        }
    }

    private static boolean isTextShellShape(ResolvedPageItem item) {
        if (item == null) return false;
        String t = item.type();
        return "Rectangle".equals(t) || "Polygon".equals(t) || "Oval".equals(t);
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

    private static boolean containsInt(int[] values, int expected) {
        if (values == null) return false;
        for (int value : values) {
            if (value == expected) return true;
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
        ObjectPlan plan = ctx.findHwpxTextFrameOwnershipPlan(tfDomId);
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && ShellRole.isTextShell(plan);
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

    private static boolean isObjectReplacementOnlyInlinePngCarrier(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        if (ctx == null || ctx.textFlowDocument == null || tf == null || tf.storyId() == null) return false;
        if (!isOnlyObjectReplacementText(tf.frameVisibleText())) return false;
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(tf.storyId());
        if (unit == null || unit.paragraphs == null) return false;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null || paragraph.atoms == null) continue;
            for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
                if (!(atom instanceof TextFlowDocument.InlineSlotAtom)) continue;
                TextFlowDocument.InlineSlotAtom slot = (TextFlowDocument.InlineSlotAtom) atom;
                if ("PLACE_INLINE_PNG".equals(slot.planVisualAction)
                        && "INLINE".equals(slot.planPlacement)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isOnlyObjectReplacementText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean sawObjectReplacement = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '￼') {
                sawObjectReplacement = true;
                continue;
            }
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
        return sawObjectReplacement;
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

    private static LocalFrameBounds computePlannedPageFrameBounds(
            ResolvedBuildContext ctx,
            ObjectPlan plan) {
        if (!isHwpxTextBoundsPlan(plan)) return null;
        if (plan == null || plan.coordinateSpace != CoordinateSpace.PAGE) return null;
        double[] bounds = plan.bounds;
        if (!isValidBounds(bounds)) return null;
        ResolvedPage page = null;
        if (ctx != null && ctx.resolvedData != null && plan.pageIndex >= 0) {
            Integer sectionIndex = ctx.pageDocOffsetToSection != null
                    ? ctx.pageDocOffsetToSection.get(plan.pageIndex)
                    : null;
            if (sectionIndex != null) {
                page = ctx.resolvedData.getPage(sectionIndex);
            }
            if (page == null) {
                page = ctx.resolvedData.getPage(plan.pageIndex);
            }
        }
        if (page == null) {
            return new LocalFrameBounds(
                    bounds[1],
                    bounds[0],
                    bounds[3] - bounds[1],
                    bounds[2] - bounds[0]);
        }
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        double[] pageBounds = page.bounds();
        if (pageBounds == null || pageBounds.length < 4) {
            return new LocalFrameBounds(
                    bounds[1],
                    bounds[0],
                    bounds[3] - bounds[1],
                    bounds[2] - bounds[0]);
        }
        double rawPageTop = pageBounds[0] / scale;
        double rawPageLeft = pageBounds[1] / scale;
        boolean pageRelativeCoords = rawPageLeft > 1.0 && bounds[1] < rawPageLeft;
        double left = pageRelativeCoords ? bounds[1] : (bounds[1] - rawPageLeft);
        double top = pageRelativeCoords ? bounds[0] : (bounds[0] - rawPageTop);
        return new LocalFrameBounds(
                left * scale,
                top * scale,
                (bounds[3] - bounds[1]) * scale,
                (bounds[2] - bounds[0]) * scale);
    }

    private static boolean isHwpxTextBoundsPlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.materialization == Materialization.HWPX_TEXT;
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
