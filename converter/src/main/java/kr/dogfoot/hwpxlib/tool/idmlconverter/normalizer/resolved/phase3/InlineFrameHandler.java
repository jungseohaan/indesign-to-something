package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextRunSegmenter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TableFrameOwnershipPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualCropper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import javax.imageio.ImageIO;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.InlineSemanticLabelPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 인라인 객체 / 글상자 체인 / 외부 위치 검사 (W3 Step E).
 * StoryConverter에서 분리됨.
 *
 * 책임:
 * - orderByThreadChain: 연결 글상자 체인 순서 정렬
 * - tryInlineFractionAsEquation / collectParagraphEquationText / convertRunsToHwpScript:
 *   분수 패턴을 ASTEquation 인라인으로 시도
 * - tryInlineTextFrameAsRun / createSpaceRunForEmptyAnchor / isEmptyContainer:
 *   인라인 텍스트프레임 → 텍스트 런 변환
 * - isAnchoredOutsideParent / isAnchoredOutsideParentByTextFrame / isOutsideParentBounds:
 *   앵커 객체가 부모 TextFrame 외부에 있는지 판별
 * - loadInlineObject: 인라인 객체 로드
 * - isNoneColor: 색상 헬퍼 (isEmptyContainer 의존)
 */
public class InlineFrameHandler {

    private InlineFrameHandler() {}

    /** 원본 단일행 라벨/제목형 fixed text는 HWP 폰트폭 차이로 두 줄이 되지 않도록 SQUEEZE를 적용한다. */
    private static final double INLINE_VECTOR_UNIT_MAX_SIZE_PT = 24.0;
    private static final double INLINE_VECTOR_UNIT_OVERLAP_RATIO = 0.25;

    /**
     * 스레드 체인 순서로 블록 정렬: previousFrameId=null인 첫 번째 프레임부터 순서대로.
     */
    static List<ASTTextFrameBlock> orderByThreadChain(ResolvedBuildContext ctx, List<ASTTextFrameBlock> blocks) {
        if (blocks.size() <= 1) return blocks;

        // domId → block 매핑
        Map<String, ASTTextFrameBlock> byDomId = new java.util.LinkedHashMap<String, ASTTextFrameBlock>();
        for (ASTTextFrameBlock b : blocks) {
            String sid = b.sourceId();
            if (sid == null) continue;
            String domId = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers.domIdFromSourceId(sid);
            if (domId == null) domId = sid;
            byDomId.put(domId, b);
        }

        // 첫 번째 프레임 찾기 (previousFrameId=null)
        String firstId = null;
        for (String domId : byDomId.keySet()) {
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
            if (rtf != null && rtf.previousFrameId() == null) {
                firstId = domId;
                break;
            }
        }

        if (firstId == null) return blocks; // 체인 시작을 못 찾으면 원래 순서

        // 체인 순서로 정렬
        List<ASTTextFrameBlock> ordered = new ArrayList<ASTTextFrameBlock>();
        String currentId = firstId;
        java.util.Set<String> visited = new java.util.HashSet<String>();
        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            ASTTextFrameBlock b = byDomId.get(currentId);
            if (b != null) ordered.add(b);
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(currentId);
            currentId = (rtf != null) ? rtf.nextFrameId() : null;
        }

        // 체인에 포함되지 않은 블록 추가
        for (ASTTextFrameBlock b : blocks) {
            if (!ordered.contains(b)) ordered.add(b);
        }

        return ordered;
    }

    /**
     * 인라인 앵커 객체가 짧은 텍스트(1~5자)를 가진 TextFrame이면
     * PNG 이미지 대신 ASTTextRun으로 변환 (줄간격 영향 없음, 폰트 매핑 가능).
     * @return ASTTextRun (텍스트로 변환됨) 또는 null (PNG 변환 필요)
     */
    /**
     * 인라인 TextFrame이 분수 구조(2 paragraphs = 분자/분모)이면 ASTEquation으로 변환.
     * @return ASTEquation 또는 null (분수가 아님)
     */
    static kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation tryInlineFractionAsEquation(
            ResolvedBuildContext ctx, int anchoredObjectId) {
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) return null;
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(domId)) return null;
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        // 2개 단락 = 분수 구조 (frameParaTexts[0]=분자, [1]=분모)
        ResolvedStory rs = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (rs == null || rs.paragraphs().size() != 2) return null;

        // 각 단락의 텍스트 수집 (EH 수식 폰트 포함)
        String numerator = collectParagraphEquationText(rs.paragraphs().get(0));
        String denominator = collectParagraphEquationText(rs.paragraphs().get(1));
        if (numerator == null || denominator == null) return null;
        numerator = numerator.trim();
        denominator = denominator.trim();
        if (numerator.isEmpty() || denominator.isEmpty()) return null;

        // EH 수식 런이 포함되어 있으면 EH 변환 파이프라인으로 처리
        String numScript = convertRunsToHwpScript(rs.paragraphs().get(0));
        String denomScript = convertRunsToHwpScript(rs.paragraphs().get(1));
        if (numScript == null) numScript = numerator;
        if (denomScript == null) denomScript = denominator;

        String hwpScript = "{" + numScript + "} over {" + denomScript + "}";
        return new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(hwpScript, "EH_FONT");
    }

    private static String collectParagraphEquationText(ResolvedParagraph rp) {
        if (rp.runs() == null || rp.runs().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ResolvedRun r : rp.runs()) {
            if (r.text() != null) sb.append(r.text());
        }
        return sb.toString();
    }

    private static String convertRunsToHwpScript(ResolvedParagraph rp) {
        if (rp.runs() == null || rp.runs().isEmpty()) return null;
        boolean hasEH = false;
        for (ResolvedRun r : rp.runs()) {
            if (r.fontFamily() != null && EHFontGlyphMap.isEHFontFamily(r.fontFamily())) {
                hasEH = true;
                break;
            }
        }
        if (!hasEH) return null;

        // EH 런을 IDMLCharacterRun으로 변환하여 EHFontEquationConverter로 처리
        List<IDMLCharacterRun> ehRuns = new ArrayList<>();
        for (ResolvedRun r : rp.runs()) {
            IDMLCharacterRun cr = new IDMLCharacterRun();
            cr.content(r.text());
            cr.fontFamily(r.fontFamily());
            ehRuns.add(cr);
        }
        return EHFontEquationConverter.convert(ehRuns);
    }

    /**
     * SPEC-025: Group 앵커가 다수의 시각적 박스(예: 자모 ㅍ ㅎ ㅂ ㅅ 배지)를 포함하면
     * 각 자식 TextFrame 을 개별 INLINE_TEXT_FRAME (rounded box 스타일) 로 분해하여 검색 가능한
     * 텍스트로 렌더링한다.
     *
     * 조건:
     * - 앵커 ID 가 Group (TextFrame 아님)
     * - Group 자식 중 inline + visible-text 인 TextFrame 이 2 개 이상
     * - Group descendant 에 stroke 가 있는 Rectangle 도형이 존재 (박스 데코)
     *
     * 각 TF 를 박스 데코와 매칭 (bounds overlap) 후, Rectangle 의 stroke 색/굵기/cornerRadius
     * 를 inline 박스에 복사한다.
     */
    public static java.util.List<ASTInlineObject> tryInlineGroupAsBoxList(ResolvedBuildContext ctx, int anchoredObjectId) {
        String anchorId = String.valueOf(anchoredObjectId);
        if (containsObjectReplacementTextFrameDescendant(ctx, anchorId)) {
            return null;
        }
        if (InlineSemanticLabelPolicy.isSemanticMultiTextInlineGroup(ctx.resolvedData, anchoredObjectId)) {
            return null;
        }
        if (containsConceptDiagramTextFrame(ctx, anchorId)) return null;
        ResolvedTextFrame anchorTf = ctx.resolvedData.getTextFrame(anchorId);
        if (anchorTf != null) return null;

        // 직속 자식 TF 수집 (inline + 텍스트 있음)
        java.util.List<ResolvedTextFrame> childTfs = new java.util.ArrayList<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            if (!anchorId.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            if (hasObjectReplacementText(vt)) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            if (!ctx.resolvedData.isSimpleButtonLabelTextFrame(tf.id())) {
                return null;
            }
            childTfs.add(tf);
        }
        // Phase B/C가 중첩 배지를 추가할 수 있으므로 직속 TF 수로 조기 종료하지 않음
        // 최종 result.size() < 2 이면 null 반환 (아래)

        // 읽기 순서 (Y → X) 정렬
        childTfs.sort((a, b) -> {
            double[] ab = a.geometricBounds();
            double[] bb = b.geometricBounds();
            if (ab == null || bb == null) return 0;
            if (Math.abs(ab[0] - bb[0]) > 1.0) return Double.compare(ab[0], bb[0]);
            return Double.compare(ab[1], bb[1]);
        });

        // anchorId 후손 ID 집합 — Phase A/B/C 세 루프 모두 재사용해 O(P×depth) → O(1) 조회
        java.util.Set<String> descendantIds = ctx.resolvedData.buildDescendantSet(anchorId, 5);

        // Phase B/C O(P×T) → O(1): parentId 인덱스 한 번 빌드
        java.util.Map<String, java.util.List<ResolvedPageItem>> pageItemsByParent = new java.util.HashMap<>();
        for (ResolvedPageItem _pi : ctx.resolvedData.pageItems()) {
            if (_pi == null || _pi.parentId() == null) continue;
            pageItemsByParent.computeIfAbsent(_pi.parentId(), k -> new java.util.ArrayList<>()).add(_pi);
        }
        java.util.Map<String, java.util.List<ResolvedTextFrame>> textFramesByParent = new java.util.HashMap<>();
        for (ResolvedTextFrame _tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem _tpi = ctx.resolvedData.getPageItem(_tf.id());
            if (_tpi == null || _tpi.parentId() == null) continue;
            textFramesByParent.computeIfAbsent(_tpi.parentId(), k -> new java.util.ArrayList<>()).add(_tf);
        }

        // Group 후손 중 stroke/fill 있는 Rectangle 수집
        java.util.List<ResolvedPageItem> rectangles = new java.util.ArrayList<>();
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || !descendantIds.contains(pi.id())) continue;
            if (!"Rectangle".equals(pi.type()) && !"Polygon".equals(pi.type()) && !"Oval".equals(pi.type())) continue;
            String scn = pi.strokeColorName();
            boolean hasStroke = scn != null && !"None".equals(scn) && !"[None]".equals(scn) && pi.strokeWeight() > 0;
            String fcn = pi.fillColorName();
            boolean hasFill = fcn != null && !"None".equals(fcn) && !"[None]".equals(fcn);
            if (hasStroke || hasFill) rectangles.add(pi);
        }
        if (rectangles.isEmpty()) return null;

        // 각 TF/도형 쌍을 (sortY, sortX, obj) 로 수집한 뒤 정렬
        java.util.List<double[]> sortKeys = new java.util.ArrayList<>();
        java.util.List<ASTInlineObject> unsorted = new java.util.ArrayList<>();

        // Phase A: Group 직속 TF → 가장 잘 겹치는 Rectangle 과 매칭
        for (ResolvedTextFrame childTf : childTfs) {
            double[] tfBounds = childTf.geometricBounds();
            if (tfBounds == null || tfBounds.length < 4) continue;
            ResolvedPageItem matchedRect = null;
            double bestOverlap = 0;
            for (ResolvedPageItem rect : rectangles) {
                double[] rb = rect.geometricBounds();
                if (rb == null || rb.length < 4) continue;
                double yOv = Math.min(tfBounds[2], rb[2]) - Math.max(tfBounds[0], rb[0]);
                double xOv = Math.min(tfBounds[3], rb[3]) - Math.max(tfBounds[1], rb[1]);
                if (yOv <= 0 || xOv <= 0) continue;
                double overlap = yOv * xOv;
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    matchedRect = rect;
                }
            }

            double[] elBounds = matchedRect != null ? matchedRect.geometricBounds() : tfBounds;
            double w = Math.abs(elBounds[3] - elBounds[1]);
            double h = Math.abs(elBounds[2] - elBounds[0]);
            if (w <= 0 || h <= 0) continue;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            obj.width(CoordinateConverter.pointsToHwpunits(w));
            obj.height(CoordinateConverter.pointsToHwpunits(h));
            obj.sourceId(ParagraphTextHelpers.domIdToSourceId(childTf.id()));
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, matchedRect != null));
            obj.nativeGraphicsAllowed(true);

            if (matchedRect != null) {
                String strokeName = matchedRect.strokeColorName();
                if (strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)) {
                    String hex = ctx.resolvedData.resolveColorHex(strokeName);
                    if (hex != null) {
                        obj.strokeColor(hex);
                        // applyScale 이 strokeWeight 도 곱했으므로 시각 두께는 / scaleFactor 로 되돌림
                        double sw = matchedRect.strokeWeight();
                        if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                        obj.strokeWeight(Math.max(sw, 0.6));
                    }
                }
                String fillName = matchedRect.fillColorName();
                if (fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
                    String hex = ctx.resolvedData.resolveColorHex(fillName);
                    if (hex != null) {
                        obj.fillColor(hex);
                        obj.fillTint(matchedRect.fillTint());
                    }
                }
                if (matchedRect.cornerRadius() > 0) {
                    obj.cornerRadius(matchedRect.cornerRadius());
                } else {
                    double crLookup = lookupIdmlShapeCornerRadius(ctx, matchedRect.id());
                    if (crLookup > 0) {
                        obj.cornerRadius(crLookup);
                    } else if ("Oval".equals(matchedRect.type())) {
                        obj.cornerRadius(h / 2.0);
                    } else {
                        obj.cornerRadius(h / 6.0);
                    }
                }
            }

            String jamoText = childTf.frameVisibleText().replace("￼", "").replace("\r", "").replace("\n", "").trim();
            ASTParagraph paraInner = new ASTParagraph();
            paraInner.alignment("CENTER");
            addSyntheticRunsFromTextFrame(ctx, paraInner, childTf, jamoText);
            obj.addParagraph(paraInner);
            obj.verticalJustification("CenterAlign");

            unsorted.add(obj);
            // top-Y 대신 center-Y: 높이가 다른 아이템이 같은 행에 수직 정렬될 때 별도 row로 분리 방지
            double centerYA = (elBounds[0] + elBounds[2]) / 2.0;
            sortKeys.add(new double[]{centerYA, elBounds[1]});
        }

        // Phase B: Group 후손 Rectangle/Oval 중 TF 자식이 있는 "중첩 배지"
        // (예: Group → ... → Rectangle → TF["관형어"]) — 최대 5 hop 깊이 허용
        for (ResolvedPageItem rectPi : ctx.resolvedData.pageItems()) {
            if (rectPi == null || !descendantIds.contains(rectPi.id())) continue;
            String rtype = rectPi.type();
            if (!"Rectangle".equals(rtype) && !"Oval".equals(rtype) && !"Polygon".equals(rtype)) continue;
            double[] rectBounds = rectPi.geometricBounds();
            if (rectBounds == null || rectBounds.length < 4) continue;
            double rw = Math.abs(rectBounds[3] - rectBounds[1]);
            double rh = Math.abs(rectBounds[2] - rectBounds[0]);
            if (rw <= 0 || rh <= 0) continue;

            java.util.List<ResolvedTextFrame> childTfCandidates = textFramesByParent.getOrDefault(rectPi.id(), java.util.Collections.<ResolvedTextFrame>emptyList());
            for (ResolvedTextFrame nestedTf : childTfCandidates) {
                String vt = nestedTf.frameVisibleText();
                if (vt == null) continue;
                if (hasObjectReplacementText(vt)) continue;
                String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                if (cleaned.isEmpty()) continue;

                ASTInlineObject obj = new ASTInlineObject();
                obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
                obj.width(CoordinateConverter.pointsToHwpunits(rw));
                obj.height(CoordinateConverter.pointsToHwpunits(rh));
                obj.sourceId(ParagraphTextHelpers.domIdToSourceId(nestedTf.id()));
                obj.noAutoLineWrap(shouldUseNoAutoLineWrap(nestedTf, true));
                obj.nativeGraphicsAllowed(true);

                String strokeName = rectPi.strokeColorName();
                if (strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)) {
                    String hex = ctx.resolvedData.resolveColorHex(strokeName);
                    if (hex != null) {
                        obj.strokeColor(hex);
                        double sw = rectPi.strokeWeight();
                        if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                        obj.strokeWeight(Math.max(sw, 0.6));
                    }
                }
                String fillName = rectPi.fillColorName();
                if (fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
                    String hex = ctx.resolvedData.resolveColorHex(fillName);
                    if (hex != null) {
                        obj.fillColor(hex);
                        obj.fillTint(rectPi.fillTint());
                    }
                }
                double cr = rectPi.cornerRadius();
                if (cr <= 0) cr = lookupIdmlShapeCornerRadius(ctx, rectPi.id());
                if (cr > 0) {
                    obj.cornerRadius(cr);
                } else if ("Oval".equals(rtype)) {
                    obj.cornerRadius(rh / 2.0);
                } else {
                    // Rectangle 배경: pill 대신 라운드사각형 근사 (≈17% rounding)
                    obj.cornerRadius(rh / 6.0);
                }

                ASTParagraph paraInner = new ASTParagraph();
                paraInner.alignment("CENTER");
                addSyntheticRunsFromTextFrame(ctx, paraInner, nestedTf, cleaned);
                obj.addParagraph(paraInner);
                obj.verticalJustification("CenterAlign");

                unsorted.add(obj);
                // top-Y 대신 center-Y: Phase A/C와 동일 기준으로 row 그룹핑
                double centerYB = (rectBounds[0] + rectBounds[2]) / 2.0;
                sortKeys.add(new double[]{centerYB, rectBounds[1]});
                break; // Rectangle 당 배지 TF 하나만
            }
        }

        // Phase C: descendant Group 직속 TF 중, 같은 Group 안에 배경 Rect/Oval이 있는 경우
        // (예: Group 18558 → TF "체언..." + sibling Oval 18559)
        // Phase A/B에서 이미 처리된 sourceId 는 건너뜀
        java.util.Set<String> coveredSourceIds = new java.util.HashSet<>();
        for (ASTInlineObject covObj : unsorted) {
            if (covObj.sourceId() != null) coveredSourceIds.add(covObj.sourceId());
        }
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem tfPi = ctx.resolvedData.getPageItem(tf.id());
            if (tfPi == null) continue;
            String tfParentId = tfPi.parentId();
            if (tfParentId == null || anchorId.equals(tfParentId)) continue; // Phase A 가 처리
            ResolvedPageItem parentPi = ctx.resolvedData.getPageItem(tfParentId);
            if (parentPi == null || !"Group".equals(parentPi.type())) continue; // 부모가 Group 이어야 함

            if (!descendantIds.contains(tfParentId)) continue;

            // visible text 확인
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            if (hasObjectReplacementText(vt)) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;

            // 이미 Phase A/B 에서 처리된 TF 이면 skip
            String tfSourceId = ParagraphTextHelpers.domIdToSourceId(tf.id());
            if (coveredSourceIds.contains(tfSourceId)) continue;

            // 같은 부모 Group 안에서 가장 잘 겹치는 Rect/Oval 찾기
            double[] tfBounds = tf.geometricBounds();
            if (tfBounds == null || tfBounds.length < 4) continue;
            ResolvedPageItem bestSibling = null;
            double bestOverlap = 0;
            java.util.List<ResolvedPageItem> siblings = pageItemsByParent.getOrDefault(tfParentId, java.util.Collections.<ResolvedPageItem>emptyList());
            for (ResolvedPageItem sibling : siblings) {
                if (sibling == null) continue;
                String st = sibling.type();
                boolean isGeoShape = "Rectangle".equals(st) || "Oval".equals(st) || "Polygon".equals(st);
                boolean isBorderedTf = "TextFrame".equals(st) && sibling.strokeColorName() != null
                        && !"None".equals(sibling.strokeColorName()) && !"[None]".equals(sibling.strokeColorName());
                if (!isGeoShape && !isBorderedTf) continue;
                double[] sb = sibling.geometricBounds();
                if (sb == null || sb.length < 4) continue;
                double yOv = Math.min(tfBounds[2], sb[2]) - Math.max(tfBounds[0], sb[0]);
                double xOv = Math.min(tfBounds[3], sb[3]) - Math.max(tfBounds[1], sb[1]);
                if (yOv > 0 && xOv > 0) {
                    double overlap = yOv * xOv;
                    if (overlap > bestOverlap) { bestOverlap = overlap; bestSibling = sibling; }
                }
            }
            if (bestSibling == null) continue;

            // INLINE_TEXT_FRAME 생성 (sibling 도형 사용)
            double[] elBounds = bestSibling.geometricBounds();
            double rw = Math.abs(elBounds[3] - elBounds[1]);
            double rh = Math.abs(elBounds[2] - elBounds[0]);
            if (rw <= 0 || rh <= 0) continue;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            obj.width(CoordinateConverter.pointsToHwpunits(rw));
            obj.height(CoordinateConverter.pointsToHwpunits(rh));
            obj.sourceId(tfSourceId);
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(tf, true));
            obj.nativeGraphicsAllowed(true);

            String strokeName = bestSibling.strokeColorName();
            if (strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)) {
                String hex = ctx.resolvedData.resolveColorHex(strokeName);
                if (hex != null) {
                    obj.strokeColor(hex);
                    double sw = bestSibling.strokeWeight();
                    if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                    obj.strokeWeight(Math.max(sw, 0.6));
                }
            }
            String fillName = bestSibling.fillColorName();
            if (fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
                String hex = ctx.resolvedData.resolveColorHex(fillName);
                if (hex != null) { obj.fillColor(hex); obj.fillTint(bestSibling.fillTint()); }
            }
            double cr = bestSibling.cornerRadius();
            if (cr <= 0) cr = lookupIdmlShapeCornerRadius(ctx, bestSibling.id());
            if (cr > 0) {
                obj.cornerRadius(cr);
            } else if ("Oval".equals(bestSibling.type())) {
                obj.cornerRadius(rh / 2.0);
            } else {
                // Rectangle/TextFrame 배경: 라운드사각형 근사 (≈17% rounding)
                obj.cornerRadius(rh / 6.0);
            }

            ASTParagraph paraInner = new ASTParagraph();
            paraInner.alignment("CENTER");
            addSyntheticRunsFromTextFrame(ctx, paraInner, tf, cleaned);
            obj.addParagraph(paraInner);
            obj.verticalJustification("CenterAlign");

            unsorted.add(obj);
            // top-Y 대신 center-Y 사용: 높이가 다른 아이템(텍스트 컨테이너 vs 배지)이
            // 같은 행에 있어도 top-Y 차이로 별도 row group으로 분리되는 문제 방지
            double centerY = (elBounds[0] + elBounds[2]) / 2.0;
            sortKeys.add(new double[]{centerY, elBounds[1]});
        }

        // Y → X 순으로 정렬
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < unsorted.size(); i++) indices.add(i);
        indices.sort((ia, ib) -> {
            double[] ka = sortKeys.get(ia), kb = sortKeys.get(ib);
            if (Math.abs(ka[0] - kb[0]) > 1.0) return Double.compare(ka[0], kb[0]);
            return Double.compare(ka[1], kb[1]);
        });
        java.util.List<ASTInlineObject> result = new java.util.ArrayList<>();
        java.util.List<double[]> resultKeys = new java.util.ArrayList<>();
        for (int idx : indices) {
            result.add(unsorted.get(idx));
            resultKeys.add(sortKeys.get(idx));
        }

        if (result.size() < 2) return null;

        // Y 좌표 기준 row 그룹핑 (2pt tolerance)
        // 같은 Y 행의 배지+설명을 outer container 의 단일 단락에 묶어 한 줄에 배치
        java.util.List<java.util.List<Integer>> rowGroups = new java.util.ArrayList<>();
        java.util.List<Integer> currentGroup = new java.util.ArrayList<>();
        double currentGroupY = resultKeys.get(0)[0];
        currentGroup.add(0);
        for (int i = 1; i < result.size(); i++) {
            double y = resultKeys.get(i)[0];
            if (Math.abs(y - currentGroupY) <= 2.0) {
                currentGroup.add(i);
            } else {
                rowGroups.add(currentGroup);
                currentGroup = new java.util.ArrayList<>();
                currentGroup.add(i);
                currentGroupY = y;
            }
        }
        rowGroups.add(currentGroup);

        // row 그룹이 1개뿐이면 flat 리스트 반환 (기존 단일-행 배지 동작 유지)
        if (rowGroups.size() < 2) return result;

        // Group bounds 로 outer container 크기 설정
        ResolvedPageItem groupPi = ctx.resolvedData.getPageItem(anchorId);
        double outerW = 0, outerH = 0;
        double groupXMin = 0;
        if (groupPi != null) {
            double[] gb = groupPi.geometricBounds();
            if (gb != null && gb.length >= 4) {
                outerW = Math.abs(gb[3] - gb[1]);
                outerH = Math.abs(gb[2] - gb[0]);
                groupXMin = gb[1];
            }
        }
        if (outerW <= 0) outerW = 100;
        if (outerH <= 0) outerH = 50;

        // 섹션 배지 감지: X_min 이 Group 의 X_min 에 근접 (5mm 이내)
        // 주성분/부속성분/독립성분 같은 좌측 레이블 배지를 다른 배지들보다 먼저 배치
        java.util.List<Integer> sectionBadgeIndices = new java.util.ArrayList<>();
        java.util.List<java.util.List<Integer>> regularRowGroups = new java.util.ArrayList<>();
        for (java.util.List<Integer> rowGroup : rowGroups) {
            java.util.List<Integer> regularInRow = new java.util.ArrayList<>();
            for (int ri : rowGroup) {
                double itemXMin = resultKeys.get(ri)[1];
                if (Math.abs(itemXMin - groupXMin) < 5.0) {
                    sectionBadgeIndices.add(ri);
                } else {
                    regularInRow.add(ri);
                }
            }
            if (!regularInRow.isEmpty()) regularRowGroups.add(regularInRow);
        }

        // 모든 항목이 섹션 배지로 분류된 경우 (regularRowGroups 비어 있음) → flat 반환
        if (regularRowGroups.isEmpty()) return result;

        long totalW = CoordinateConverter.pointsToHwpunits(outerW);
        long totalH = CoordinateConverter.pointsToHwpunits(outerH);

        // regularRowGroups의 첫 번째~두 번째 rowGroup Y 좌표 차로 원본 행간 계산
        // 결과가 있는 경우에만 사용, 없으면 0 (기본값 유지)
        int rowSpacingHwpunit = computeRowSpacing(regularRowGroups, resultKeys);

        if (!sectionBadgeIndices.isEmpty()) {
            // 2-column: 섹션 배지(LEFT ITF) | 나머지(RIGHT ITF)
            // StoryConverter 가 두 ITF 를 같은 단락에 추가 → 나란히 배치
            sectionBadgeIndices.sort((ia, ib) -> Double.compare(resultKeys.get(ia)[1], resultKeys.get(ib)[1]));

            long sectionBadgeW = 0;
            for (int ri : sectionBadgeIndices) {
                sectionBadgeW = Math.max(sectionBadgeW, result.get(ri).width());
            }

            // LEFT ITF: 섹션 배지
            ASTInlineObject leftITF = new ASTInlineObject();
            leftITF.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            leftITF.width(sectionBadgeW);
            leftITF.height(totalH);
            leftITF.sourceId("u" + Integer.toHexString(anchoredObjectId) + "_sb");
            leftITF.verticalJustification("CenterAlign");
            ASTParagraph sbPara = new ASTParagraph();
            sbPara.alignment("CENTER");
            for (int ri : sectionBadgeIndices) sbPara.addItem(result.get(ri));
            leftITF.addParagraph(sbPara);

            // RIGHT ITF: 나머지 행 (배지 + 설명)
            long rightW = Math.max(totalW - sectionBadgeW, 3000L);
            ASTInlineObject rightITF = new ASTInlineObject();
            rightITF.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            rightITF.width(rightW);
            rightITF.height(totalH);
            rightITF.sourceId("u" + Integer.toHexString(anchoredObjectId));
            for (java.util.List<Integer> rowGroup : regularRowGroups) {
                rowGroup.sort((ia, ib) -> Double.compare(resultKeys.get(ia)[1], resultKeys.get(ib)[1]));
                ASTParagraph rowPara = new ASTParagraph();
                if (rowSpacingHwpunit > 0) {
                    rowPara.lineSpacing(rowSpacingHwpunit);
                    rowPara.lineSpacingType("fixed");
                }
                for (int ri : rowGroup) rowPara.addItem(result.get(ri));
                rightITF.addParagraph(rowPara);
            }

            java.util.List<ASTInlineObject> twoCol = new java.util.ArrayList<>();
            twoCol.add(leftITF);
            twoCol.add(rightITF);
            return twoCol;
        }

        // 섹션 배지 없음 → 단일 outer container
        ASTInlineObject outer = new ASTInlineObject();
        outer.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        outer.width(totalW);
        outer.height(totalH);
        outer.sourceId("u" + Integer.toHexString(anchoredObjectId));
        for (java.util.List<Integer> rowGroup : regularRowGroups) {
            rowGroup.sort((ia, ib) -> Double.compare(resultKeys.get(ia)[1], resultKeys.get(ib)[1]));
            ASTParagraph rowPara = new ASTParagraph();
            if (rowSpacingHwpunit > 0) {
                rowPara.lineSpacing(rowSpacingHwpunit);
                rowPara.lineSpacingType("fixed");
            }
            for (int ri : rowGroup) rowPara.addItem(result.get(ri));
            outer.addParagraph(rowPara);
        }
        return java.util.Collections.singletonList(outer);
    }

    private static boolean hasObjectReplacementText(String text) {
        return text != null && (text.indexOf('\uFFFC') >= 0 || text.indexOf('￼') >= 0);
    }

    private static boolean containsObjectReplacementTextFrameDescendant(
            ResolvedBuildContext ctx,
            String anchorId) {
        if (ctx == null || ctx.resolvedData == null || anchorId == null) return false;
        java.util.Set<String> descendantIds = ctx.resolvedData.buildDescendantSet(anchorId, 8);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            if (!anchorId.equals(tf.id()) && !descendantIds.contains(tf.id())) continue;
            if (hasObjectReplacementText(tf.frameVisibleText())) return true;
        }
        return false;
    }

    /**
     * 인라인 shape(Rectangle/Oval/Polygon)가 editable child TF를 품고 있고,
     * Stage 1 ownership이 PLACE_TEXT_SHELL로 결정한 경우에는 shape PNG를 shell로,
     * child TF를 HWPX 텍스트로 한 몸의 INLINE_TEXT_FRAME 안에 배치한다.
     *
     * Group 전용 배지 로직과 달리, 이 경로는 원본 앵커 자체가 shape인 케이스를 담당한다.
     */
    public static ASTInlineObject tryInlineShapeWithEditableChildAsShell(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        if (anchorItem == null || !isInlineShellShape(anchorItem)) return null;

        ResolvedTextFrame childTf = null;
        String anchorId = String.valueOf(anchoredObjectId);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null || !anchorId.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) return null;
            String vt = tf.frameVisibleText();
            if (hasObjectReplacementText(vt)) return null;
            String cleaned = vt == null
                    ? ""
                    : vt.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            if (childTf != null) return null;
            childTf = tf;
        }
        if (childTf == null) return null;
        return loadRenderedTextHiddenInlineShell(ctx, anchoredObjectId, anchorItem, childTf);
    }

    /**
     * Group 앵커(예: 곡선 브래킷 도형 + 편집 텍스트 TF가 한 그룹)를 렌더 셸 PNG를 배경으로 한
     * INLINE_TEXT_FRAME으로 변환. tryInlineShapeWithEditableChildAsShell의 Group 버전.
     * 곡선/말풍선 PNG가 박스 배경(imageFill)이 되고 텍스트는 검색가능하게 그 위에 흐른다 →
     * 앞의 번호 "1"/"2"와 같은 줄에 자연스럽게 인라인 플로우.
     */
    public static ASTInlineObject tryInlineGroupShellWithEditableChild(
            ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        // 그룹은 resolved 에서 자식 TF 와 parentId 로 연결되지 않는 경우가 많다(parentId=null).
        // 대신 렌더 셸 PNG 의 editableTextFrameIds 로 편집 자식을 찾는다.
        // 곡선/말풍선 그룹은 텍스트를 숨긴 채 렌더된 셸 PNG(예: reason=*text_hidden, page_object)로 들어온다.
        RenderedGroup shell = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId || rg.file() == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            String reason = rg.reason();
            if (reason == null || !reason.contains("text_hidden")) continue;
            String[] eids = rg.editableTextFrameIds();
            if (eids == null || eids.length == 0) continue;
            shell = rg;
            break;
        }
        if (shell == null) return null;
        boolean inlineGroupAnchor = anchorItem != null && "Group".equals(anchorItem.type()) && anchorItem.isInline();
        if (!inlineGroupAnchor && !isInlineTextlessShellWithTf(shell)) return null;
        // 라벨 셸(visual_label_text_hidden_shell)이 Stage 3에서 플로팅 PLACE_TEXT_SHELL로
        // 배치될 예정이면 인라인 베이킹하지 않는다. 인라인(여기)+플로팅(Stage 3) 이중 배치를 막고
        // 플로팅 셸이 단독 소유한다. (SPEC-035 §1.2 인라인 의미 라벨 그룹은 플로팅이 소유)
        // 범위를 라벨 셸로 한정 — 이미지 섞인 mixed_group 등은 기존 인라인 동작 유지.
        if ("visual_label_text_hidden_shell".equals(shell.reason())
                && ctx.visualActionByOwnershipPlan(shell) == VisualAction.PLACE_TEXT_SHELL
                && ctx.placementByOwnershipPlan(shell) == Placement.FLOATING) {
            return null;
        }
        java.util.List<ResolvedTextFrame> children = badgeTextFramesSortedByReading(ctx, shell);
        if (children.size() != 1) return null;
        for (ResolvedTextFrame childTf : children) {
            if (childTf == null || ctx.resolvedData.isTextOwnedByIndesignPng(childTf.id())) return null;
            String vt = childTf.frameVisibleText();
            if (hasObjectReplacementText(vt)) return null;
            String cleaned = vt == null ? "" : vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) return null;
        }
        return buildInlineShellObject(ctx, anchoredObjectId, anchorItem, children.get(0), shell);
    }

    private static boolean isInlineTextlessShellWithTf(RenderedGroup shell) {
        if (shell == null) return false;
        boolean inlineShell = "inline_object".equals(shell.itemType()) || "inline_object".equals(shell.type());
        return inlineShell && "TEXTLESS_SHELL_WITH_TF".equals(shell.atomicObjectKind());
    }

    private static boolean isInlineShellShape(ResolvedPageItem item) {
        if (item == null) return false;
        if (!item.isInline()) return false;
        String type = item.type();
        return "Rectangle".equals(type) || "Oval".equals(type) || "Polygon".equals(type);
    }

    /**
     * 인라인 Group 앵커가 "배경 도형 + 단일 짧은 텍스트프레임"으로 구성된 단일 배지
     * (예: 페이지 39 "가" / "나" 캡슐 배지) → INLINE_TEXT_FRAME 으로 변환.
     *
     * 배경과 글자가 같은 인라인 단위로 묶여 한 몸으로 움직이며, 글자는 검색 가능.
     *
     * 조건:
     * - 앵커 ID 가 Group (TextFrame 아님)
     * - Group 직속 자식 중 inline + visible-text TextFrame 이 정확히 1 개
     * - Group 후손 중 fillColor 가 있는 Rectangle/Oval/Polygon 이 1 개 이상
     */
    public static ASTInlineObject tryInlineGroupAsSingleBadge(ResolvedBuildContext ctx, int anchoredObjectId) {
        String anchorId = String.valueOf(anchoredObjectId);
        if (InlineSemanticLabelPolicy.isSemanticMultiTextInlineGroup(ctx.resolvedData, anchoredObjectId)) {
            return null;
        }
        if (containsConceptDiagramTextFrame(ctx, anchorId)) return null;
        // AboveLine 앵커는 floating badge → Stage 3 visual executor가 처리, 인라인 변환 불가
        if (ctx.aboveLineAnchoredIds.contains(anchoredObjectId)) {
            return null;
        }
        ResolvedTextFrame anchorTf = ctx.resolvedData.getTextFrame(anchorId);
        if (anchorTf != null) {
            return null;
        }

        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(anchorId);
        if (anchorItem == null || !"Group".equals(anchorItem.type())) {
            return null;
        }

        // anchorId 후손 ID 집합 — 아래 세 루프 모두 재사용
        java.util.Set<String> descendantIds = ctx.resolvedData.buildDescendantSet(anchorId, 5);

        // 직속 자식 TF 1 개 (inline + 텍스트 있음)
        // hasBadgePng=true: 중첩 그룹 구조(예: Group→Group→TF)도 허용 (최대 5 hop)
        ResolvedTextFrame childTf = null;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            boolean inGroup = anchorId.equals(pi.parentId()) || descendantIds.contains(pi.parentId());
            if (!inGroup) continue;
            if (!tf.isInline()) continue;
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            if (tf.lineCount() != 1 && cleaned.length() >= 4) return null;
            if (childTf != null) return null; // 2 개 이상 → tryInlineGroupAsBoxList 가 처리
            childTf = tf;
        }
        if (childTf == null) {
            return null;
        }
        if (ctx.resolvedData.isTextOwnedByIndesignPng(childTf.id())) {
            return null;
        }

        // Group 후손 중 fill 또는 stroke 있는 도형 수집 (가장 큰 도형 = 배경)
        ResolvedPageItem bgShape = null;
        double bgArea = 0;
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null) continue;
            if (!"Rectangle".equals(pi.type()) && !"Polygon".equals(pi.type()) && !"Oval".equals(pi.type())) continue;
            String fcn = pi.fillColorName();
            boolean hasFill = fcn != null && !"None".equals(fcn) && !"[None]".equals(fcn);
            String scn = pi.strokeColorName();
            boolean hasStroke = scn != null && !"None".equals(scn) && !"[None]".equals(scn) && pi.strokeWeight() > 0;
            if (!hasFill && !hasStroke) continue;
            if (!descendantIds.contains(pi.id())) continue;
            double[] gb = pi.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            double a = Math.abs(gb[3] - gb[1]) * Math.abs(gb[2] - gb[0]);
            if (a > bgArea) { bgArea = a; bgShape = pi; }
        }
        // bgShape 없음: 비편집 채워진 TF를 배경으로 폴백
        // (예: Group → [TF(fill=gray, 빈 텍스트, 배경용) + TF(text="예")] 구조)
        if (bgShape == null) {
            for (ResolvedTextFrame tf2 : ctx.resolvedData.textFrames()) {
                if (ctx.resolvedData.isEditableTextFrame(tf2.id())) continue;
                String fcn2 = null;
                ResolvedPageItem pi2 = ctx.resolvedData.getPageItem(tf2.id());
                if (pi2 != null) fcn2 = pi2.fillColorName();
                if (fcn2 == null || "None".equals(fcn2) || "[None]".equals(fcn2)) continue;
                // 텍스트가 있으면 배경 TF가 아님
                String vt2 = tf2.frameVisibleText();
                String c2 = vt2 == null ? "" : vt2.replace("￼","").replace("\r","").replace("\n","").trim();
                if (!c2.isEmpty()) continue;
                if (!descendantIds.contains(tf2.id())) continue;
                double[] gb2 = pi2 != null ? pi2.geometricBounds() : null;
                if (gb2 == null || gb2.length < 4) continue;
                double a2 = Math.abs(gb2[3] - gb2[1]) * Math.abs(gb2[2] - gb2[0]);
                if (a2 > bgArea) { bgArea = a2; bgShape = pi2; }
            }
        }
        if (bgShape == null) {
            // Some editable inline labels are backed only by visual-only vector lines
            // (for example, a soft highlight/underline made from GraphicLine items).
            // The extractor hides the child TF before PNG export and records
            // textOwner=hwpx_tf; even if no Rectangle/Oval/Polygon shell is found,
            // Phase 3 still owns rebuilding the editable text over that PNG shell.
            ASTInlineObject textHiddenShell =
                    loadRenderedTextHiddenInlineShell(ctx, anchoredObjectId, anchorItem, childTf);
            if (textHiddenShell != null) {
                return textHiddenShell;
            }
            return null;
        }

        // Group 안에 Oval 직속 자식이 있는지 먼저 확인 (크기 보정 판단에 필요)
        boolean hasOval = false;
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || !"Oval".equals(pi.type())) continue;
            if (anchorId.equals(pi.parentId())) { hasOval = true; break; }
        }
        // TF 배경이고 Oval이 없지만 정방형 그룹이면 원형으로 처리
        if (!hasOval && "TextFrame".equals(bgShape.type())) {
            double[] grpB = anchorItem.geometricBounds();
            if (grpB != null && grpB.length >= 4) {
                double gw0 = Math.abs(grpB[3] - grpB[1]);
                double gh0 = Math.abs(grpB[2] - grpB[0]);
                if (gw0 > 0 && gh0 > 0) {
                    double ratio0 = Math.min(gw0, gh0) / Math.max(gw0, gh0);
                    if (ratio0 >= 0.85) hasOval = true; // 정방형에 가까우면 원형 배지
                }
            }
        }

        // 박스 크기 = Group 의 전체 bounds (capsule 모양 전체 영역 포함)
        double[] grpBounds = anchorItem.geometricBounds();
        if (grpBounds == null || grpBounds.length < 4) return null;
        double w = Math.abs(grpBounds[3] - grpBounds[1]);
        double h = Math.abs(grpBounds[2] - grpBounds[0]);
        if (w <= 0 || h <= 0) return null;

        // INLINE_TEXT_FRAME 높이에는 child TF bounds 사용: group bounds에는 오버행이 포함돼
        // 한글이 행간을 팽창시킴. child TF가 더 작으면 그 높이로 제한.
        // 단, child TF 가 그룹 높이의 40% 미만이면 상단 레이블 TF가 있는 컨테이너 박스로 판단.
        // (예: "안은문장" 컨테이너 박스 — 레이블 TF(9.92pt)가 그룹(33.74pt)의 29%)
        // 이 경우 inline ITF로 변환하면 문장이 박스 밖으로 나가므로, null 반환 → loadInlineObject fallback.
        double hForInline = h;
        {
            double[] ctfBounds = childTf.geometricBounds();
            if (ctfBounds != null && ctfBounds.length >= 4) {
                double ctfH = Math.abs(ctfBounds[2] - ctfBounds[0]);
                if (ctfH > 0 && ctfH < h) hForInline = ctfH;
            }
        }

        // SPEC-028: Oval 배경 배지인데 종횡비가 심하게 틀어진 경우 (ratio < 0.6),
        // Group bounds 에 TextFrame 높이까지 포함되어 비례 왜곡이 발생한 것으로 판단.
        // (예: AboveLine 앵커, 원형 배경+텍스트프레임이 상하 적층된 구조 → 6.48×12.27pt → "●" 오렌더)
        // 이 경우 INLINE_TEXT_FRAME 생성을 포기하고 space run으로 대체 (floating badge 가 시각을 담당).
        if (hasOval) {
            double ratio = w > h ? h / w : w / h; // min/max, 1.0=정방형
            if (ratio < 0.6) {
                return null;
            }
        }

        if (!ctx.resolvedData.isSimpleButtonLabelTextFrame(childTf.id())) {
            ASTInlineObject semanticLabelShell =
                    loadInlineEditableLabelShell(ctx, anchoredObjectId, anchorItem, childTf, w, h,
                            bgShape, hasOval);
            if (semanticLabelShell != null) {
                return semanticLabelShell;
            }
        }

        ASTInlineObject renderedBadge = loadRenderedInlineBadge(ctx, anchoredObjectId, w, h, childTf);
        if (renderedBadge != null) {
            return renderedBadge;
        }
        if (ctx.resolvedData.isSimpleButtonLabelTextFrame(childTf.id())) {
            return null;
        }

        // Fallback: rendered PNG가 없으면 투명 HWP rect + 텍스트로 공간만 보존한다.
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(hForInline));
        obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
        obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
        obj.nativeGraphicsAllowed(true);
        applyInlineShellShapeStyle(ctx, bgShape, obj);

        if (hasOval) {
            obj.cornerRadius(hForInline / 2.0);
        } else if (obj.cornerRadius() <= 0) {
            double crLookup = lookupIdmlShapeCornerRadius(ctx, bgShape.id());
            if (crLookup > 0) {
                obj.cornerRadius(crLookup);
            } else {
                obj.cornerRadius(hForInline / 6.0);
            }
        }

        buildBadgeParagraph(ctx, childTf, obj);
        obj.verticalJustification("CenterAlign");

        return obj;
    }

    static boolean shouldKeepAnchoredInlineByOwnershipPlan(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null) return false;
        String anchorId = String.valueOf(anchoredObjectId);
        Set<String> descendants = ctx.resolvedData != null
                ? ctx.resolvedData.buildDescendantSet(anchorId, 5)
                : java.util.Collections.emptySet();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            boolean sameAnchor = plan.domId == anchoredObjectId
                    || containsInt(plan.sourceObjectIds, anchoredObjectId);
            boolean descendantText = descendants.contains(String.valueOf(plan.domId))
                    || containsAnyStringId(descendants, plan.sourceObjectIds);
            if (!sameAnchor && !descendantText) continue;

            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && plan.visualAction == VisualAction.ABSORB_TEXT_STYLE) {
                return true;
            }
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && plan.placement == Placement.INLINE) {
                return true;
            }
            if (plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    && plan.placement == Placement.INLINE) {
                return true;
            }
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    && plan.placement == Placement.INLINE) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasTextBlockPlacedDescendant(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || anchoredObjectId < 0) return false;
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) {
            return true;
        }
        String anchorId = String.valueOf(anchoredObjectId);
        Set<String> descendants = ctx.resolvedData.buildDescendantSet(anchorId, 8);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            String parentId = pi.parentId();
            if (!anchorId.equals(parentId) && !descendants.contains(parentId)) continue;
            int tfDomId;
            try { tfDomId = Integer.parseInt(tf.id()); } catch (NumberFormatException e) { continue; }
            if (ctx.isTextDisposed(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInt(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static boolean containsAnyStringId(Set<String> ids, int[] values) {
        if (ids == null || ids.isEmpty() || values == null) return false;
        for (int value : values) {
            if (ids.contains(String.valueOf(value))) return true;
        }
        return false;
    }

    private static ASTInlineObject loadInlineEditableLabelShell(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedPageItem anchorItem,
            ResolvedTextFrame childTf,
            double widthPt,
            double heightPt,
            ResolvedPageItem bgShape,
            boolean hasOval) {
        RenderedGroup shell = findInlineEditableLabelShell(ctx, anchoredObjectId, childTf);
        if (shell == null) return null;
        try {
            double w = widthPt;
            double h = heightPt;
            if ((w <= 0 || h <= 0) && anchorItem != null && anchorItem.geometricBounds() != null) {
                double[] gb = anchorItem.geometricBounds();
                if (gb.length >= 4) {
                    w = Math.abs(gb[3] - gb[1]);
                    h = Math.abs(gb[2] - gb[0]);
                }
            }
            if (w <= 0 || h <= 0) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
            obj.width(CoordinateConverter.pointsToHwpunits(w));
            obj.height(CoordinateConverter.pointsToHwpunits(h));
            obj.nativeGraphicsAllowed(true);
            obj.keepInline(true);
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
            obj.verticalJustification("CenterAlign");
            boolean nativeStyle = applyInlineEditableLabelShellStyle(ctx, obj, bgShape, hasOval, w, h);
            applyInlineEditableLabelTextMargins(obj, anchorItem, childTf);
            if (!nativeStyle) {
                if (ctx.basePath == null || shell.file() == null) return null;
                File pngFile = new File(ctx.basePath, shell.file());
                if (!pngFile.exists() || !pngFile.isFile()) return null;
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                BufferedImage img = ImageIO.read(pngFile);
                if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
                obj.imageFillData(imageData);
            }
            buildBadgeParagraph(ctx, childTf, obj);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean applyInlineEditableLabelShellStyle(
            ResolvedBuildContext ctx,
            ASTInlineObject obj,
            ResolvedPageItem bgShape,
            boolean hasOval,
            double widthPt,
            double heightPt) {
        if (ctx == null || obj == null || bgShape == null) return false;
        boolean applied = false;

        String fillName = bgShape.fillColorName();
        if (fillName != null && !isNoneColor(fillName)) {
            String fillHex = ctx.resolvedData.resolveColorHex(fillName);
            if (fillHex != null) {
                obj.fillColor(fillHex);
                obj.fillTint(bgShape.fillTint() > 0 ? bgShape.fillTint() : 100);
                applied = true;
            }
        }

        String strokeName = bgShape.strokeColorName();
        if (strokeName != null && !isNoneColor(strokeName)) {
            String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
            if (strokeHex != null) {
                obj.strokeColor(strokeHex);
                double sw = bgShape.strokeWeight();
                if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                obj.strokeWeight(sw > 0 ? Math.max(sw, 0.5) : 0.5);
                obj.strokeTint(bgShape.strokeTint() > 0 ? bgShape.strokeTint() : 100);
                applied = true;
            }
        }

        double cr = 0;
        if (hasOval) {
            cr = heightPt / 2.0;
        } else if (bgShape.cornerRadius() > 0) {
            cr = bgShape.cornerRadius();
        } else {
            cr = lookupIdmlShapeCornerRadius(ctx, bgShape.id());
            if (cr <= 0 && "Oval".equals(bgShape.type())) {
                cr = heightPt / 2.0;
            } else if (cr <= 0 && applied) {
                // Semantic inline labels are usually pill-shaped. Use a full capsule
                // when the background is much wider than tall; otherwise keep a
                // conservative rounded rectangle.
                cr = widthPt >= heightPt * 3.0 ? heightPt / 2.0 : heightPt / 6.0;
            }
        }
        if (cr > 0) {
            obj.cornerRadius(cr);
        }

        return applied;
    }

    private static void applyInlineEditableLabelTextMargins(
            ASTInlineObject obj,
            ResolvedPageItem anchorItem,
            ResolvedTextFrame childTf) {
        if (obj == null || anchorItem == null || childTf == null) return;
        double[] groupBounds = anchorItem.geometricBounds();
        double[] textBounds = childTf.geometricBounds();
        if (groupBounds == null || textBounds == null || groupBounds.length < 4 || textBounds.length < 4) {
            return;
        }

        double left = Math.max(0, textBounds[1] - groupBounds[1]);
        double top = Math.max(0, textBounds[0] - groupBounds[0]);
        double right = Math.max(0, groupBounds[3] - textBounds[3]);
        double bottom = Math.max(0, groupBounds[2] - textBounds[2]);
        if (left + top + right + bottom < 0.1) return;

        obj.textMarginLeft(CoordinateConverter.pointsToHwpunits(left));
        obj.textMarginTop(CoordinateConverter.pointsToHwpunits(top));
        obj.textMarginRight(CoordinateConverter.pointsToHwpunits(right));
        obj.textMarginBottom(CoordinateConverter.pointsToHwpunits(bottom));
    }

    private static RenderedGroup findInlineEditableLabelShell(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedTextFrame childTf) {
        if (ctx == null || ctx.resolvedData == null || childTf == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        String childId = childTf.id();
        RenderedGroup fallback = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId || rg.file() == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if ("inline_graphic_only".equals(rg.reason()) && rg.parentStoryId() != null) {
                return rg;
            }
            if ("text_composite_editable_text_hidden".equals(rg.reason())
                    && ("hwpx_tf".equals(rg.textOwner()) || "none".equals(rg.textOwner()))
                    && containsStringId(rg.editableTextFrameIds(), childId)) {
                fallback = rg;
            }
        }
        return fallback;
    }

    private static ASTInlineObject loadRenderedInlineBadge(
            ResolvedBuildContext ctx, int anchoredObjectId,
            double widthPt, double heightPt, ResolvedTextFrame childTf) {
        if (ctx.basePath == null || childTf == null) return null;

        File pngFile = null;
        RenderedGroup matched = null;
        File atomicPngFile = null;
        RenderedGroup atomicMatched = null;
        File fallbackPngFile = null;
        RenderedGroup fallbackMatched = null;
        if (ctx.resolvedData != null && ctx.resolvedData.allRenderedFloatingItems() != null) {
            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg == null || rg.id() != anchoredObjectId || rg.file() == null) continue;
                File candidate = new File(ctx.basePath, rg.file());
                if (!candidate.exists()) continue;
                if (ctx.isCompleteInlinePngByOwnershipPlan(rg)) {
                    atomicPngFile = candidate;
                    atomicMatched = rg;
                    break;
                }
                if (isCompletePngSimpleButtonLabel(ctx, rg)) {
                    atomicPngFile = candidate;
                    atomicMatched = rg;
                    break;
                }
                if (isInlineRenderedGroupType(rg)) {
                    pngFile = candidate;
                    matched = rg;
                    break;
                }
                if (fallbackPngFile == null) {
                    fallbackPngFile = candidate;
                    fallbackMatched = rg;
                }
            }
        }
        if (atomicPngFile != null) {
            pngFile = atomicPngFile;
            matched = atomicMatched;
        }
        if (pngFile == null) {
            pngFile = fallbackPngFile;
            matched = fallbackMatched;
        }
        if (pngFile == null) {
            File candidate = new File(ctx.basePath, "rendered_frames/inline_" + anchoredObjectId + ".png");
            if (candidate.exists()) pngFile = candidate;
        }
        if (pngFile == null) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            BadgeImageData badgeImage = null;
            if (isCompletePngSimpleButtonLabel(ctx, matched)) {
                badgeImage = trimSimpleButtonBadgeImage(imageData, img);
                imageData = badgeImage.imageData;
                img = badgeImage.image;
            }
            if (matched != null && "hwpx_tf".equals(matched.textOwner())
                    && !matched.hasEditableTextHiddenFromPng()) {
                return null;
            }
            if (matched != null
                    && Boolean.TRUE.equals(matched.containsEditableText())
                    && "indesign_png".equals(matched.textOwner())
                    && "visual_marker_label_indesign_png".equals(matched.reason())
                    && !isCompletePngSimpleButtonLabel(ctx, matched)) {
                return null;
            }

            double bw = widthPt;
            double bh = heightPt;
            if (matched != null && matched.bounds() != null && matched.bounds().length >= 4) {
                double[] b = matched.bounds();
                bw = Math.abs(b[3] - b[1]) * ctx.scaleFactor;
                bh = Math.abs(b[2] - b[0]) * ctx.scaleFactor;
            }
            if (badgeImage != null) {
                bw *= badgeImage.widthScale;
                bh *= badgeImage.heightScale;
            }
            if (bw <= 0 || bh <= 0) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.INLINE_BADGE_GROUP);
            obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
            obj.imageData(imageData);
            obj.imageFormat("png");
            obj.pixelWidth(img.getWidth());
            obj.pixelHeight(img.getHeight());
            obj.width(CoordinateConverter.pointsToHwpunits(bw));
            obj.height(CoordinateConverter.pointsToHwpunits(bh));
            obj.keepInline(true);
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
            obj.verticalJustification("CenterAlign");
            if (shouldOverlayRenderedBadgeText(ctx, matched)) {
                buildBadgeParagraph(ctx, childTf, obj);
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static ASTInlineObject loadRenderedTextHiddenInlineShell(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedPageItem anchorItem,
            ResolvedTextFrame childTf) {
        RenderedGroup shell = findTextHiddenInlineShell(ctx, anchoredObjectId, childTf);
        return buildInlineShellObject(ctx, anchoredObjectId, anchorItem, childTf, shell);
    }

    /**
     * 셸 PNG(렌더된 배경) + 편집 자식 TF → INLINE_TEXT_FRAME 인라인 객체.
     * 셸 탐색 방식과 무관하게 빌드 본문을 공유한다(말풍선/배지/그래픽 셸 공통).
     */
    private static ASTInlineObject buildInlineShellObject(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedPageItem anchorItem,
            ResolvedTextFrame childTf,
            RenderedGroup shell) {
        if (childTf == null) return null;
        return buildInlineShellObject(ctx, anchoredObjectId, anchorItem,
                java.util.Collections.singletonList(childTf), shell);
    }

    private static ASTInlineObject buildInlineShellObject(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedPageItem anchorItem,
            java.util.List<ResolvedTextFrame> childTfs,
            RenderedGroup shell) {
        if (childTfs == null || childTfs.size() != 1) return null;
        if (shell == null || ctx.basePath == null) return null;
        ObjectPlan shellPlan = findInlineTextShellOwnerPlan(ctx, shell, childTfs);
        if (shellPlan == null) return null;
        if (shellPlan.file == null || shellPlan.file.isEmpty()) return null;
        if (shellPlan.bounds == null || shellPlan.bounds.length < 4) return null;
        try {
            double[] shellBounds = shellPlan.bounds;
            double w = 0;
            double h = 0;
            if (shellBounds != null && shellBounds.length >= 4) {
                w = Math.abs(shellBounds[3] - shellBounds[1]) * ctx.scaleFactor;
                h = Math.abs(shellBounds[2] - shellBounds[0]) * ctx.scaleFactor;
            }
            if ((w <= 0 || h <= 0) && anchorItem != null && anchorItem.geometricBounds() != null) {
                double[] grpBounds = anchorItem.geometricBounds();
                if (grpBounds.length >= 4) {
                    w = Math.abs(grpBounds[3] - grpBounds[1]);
                    h = Math.abs(grpBounds[2] - grpBounds[0]);
                }
            }
            if (w <= 0 || h <= 0) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
            obj.width(CoordinateConverter.pointsToHwpunits(w));
            obj.height(CoordinateConverter.pointsToHwpunits(h));
            obj.nativeGraphicsAllowed(true);
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTfs.get(0), true));
            obj.verticalJustification("CenterAlign");

            ResolvedPageItem nativeShellShape = findSimpleNativeInlineShellShape(
                    ctx, childTfs.get(0), shellPlan, anchorItem);
            if (nativeShellShape != null) {
                applyInlineEditableLabelShellStyle(ctx, obj, nativeShellShape,
                        "Oval".equals(nativeShellShape.type()), w, h);
                obj.shellShapeType(nativeShellShape.type());
            } else {
                File pngFile = new File(ctx.basePath, shellPlan.file);
                if (!pngFile.exists() || !pngFile.isFile()) return null;
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                BufferedImage img = ImageIO.read(pngFile);
                if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
                boolean preserveSourceCanvas = shouldPreserveInlineShellSourceCanvas(ctx, shellPlan);
                imageData = prepareInlineTextShellImageData(img, preserveSourceCanvas);
                obj.imageFillData(imageData);
                obj.forceImageFill(true);
            }
            for (ResolvedTextFrame childTf : childTfs) {
                buildBadgeParagraph(ctx, childTf, obj);
                try {
                    ctx.setTextDisposition(Integer.parseInt(childTf.id()), FrameDisposition.TEXT_BLOCK_PLACED);
                } catch (Exception ignored) {
                    // Non-numeric DOM ids cannot be recorded in the legacy disposition map.
                }
            }
            ctx.markRenderedVisualHandled(shell.id());
            ctx.recordRenderedDecision(shell, shellPlan, "Phase3.InlineFrameHandler",
                    "PLACE_INLINE_TEXT_SHELL",
                    "placed planned inline textless shell as INLINE_TEXT_FRAME imageFill; editable text is owned by HWPX");
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static ObjectPlan findInlineTextShellOwnerPlan(
            ResolvedBuildContext ctx,
            RenderedGroup shell,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (ctx == null || shell == null) return null;
        ObjectPlan direct = ctx.findOwnershipPlanForRendered(shell);
        if (isInlineTextShellOwnerForChildren(direct, shell, childTfs)) return direct;
        if (ctx.ownershipPlans == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (isInlineTextShellOwnerForChildren(plan, shell, childTfs)) return plan;
        }
        return null;
    }

    private static ResolvedPageItem findSimpleNativeInlineShellShape(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem) {
        if (ctx == null || ctx.resolvedData == null || childTf == null || shellPlan == null) return null;
        ResolvedPageItem direct = simpleNativeShapeForTextFrameParent(ctx, childTf);
        if (direct != null && isPlanSource(shellPlan, direct.id())) {
            return direct;
        }
        if (isSimpleNativeInlineShellShape(anchorItem)
                && isPlanSource(shellPlan, anchorItem.id())
                && containsBounds(anchorItem.geometricBounds(), childTf.geometricBounds())) {
            return anchorItem;
        }

        ResolvedPageItem best = null;
        double bestArea = Double.MAX_VALUE;
        int[] sourceIds = shellPlan.visualSourceObjectIds != null && shellPlan.visualSourceObjectIds.length > 0
                ? shellPlan.visualSourceObjectIds
                : shellPlan.sourceObjectIds;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (!isSimpleNativeInlineShellShape(item)) continue;
            if (!containsBounds(item.geometricBounds(), childTf.geometricBounds())) continue;
            double area = boundsArea(item.geometricBounds());
            if (area > 0 && area < bestArea) {
                bestArea = area;
                best = item;
            }
        }
        return best;
    }

    private static ResolvedPageItem simpleNativeShapeForTextFrameParent(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf) {
        if (ctx == null || ctx.resolvedData == null || childTf == null || childTf.id() == null) return null;
        ResolvedPageItem textFrameItem = ctx.resolvedData.getPageItem(childTf.id());
        if (textFrameItem == null || textFrameItem.parentId() == null) return null;
        ResolvedPageItem parent = ctx.resolvedData.getPageItem(textFrameItem.parentId());
        if (!isSimpleNativeInlineShellShape(parent)) return null;
        if (!containsBounds(parent.geometricBounds(), childTf.geometricBounds())) return null;
        return parent;
    }

    private static boolean isSimpleNativeInlineShellShape(ResolvedPageItem item) {
        if (item == null) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"Oval".equals(type)) return false;
        if (item.opacity() > 0 && item.opacity() < 95.0) return false;
        if (Math.abs(item.absoluteRotationAngle()) > 0.1) return false;
        if (Math.abs(item.absoluteShearAngle()) > 0.1) return false;
        if (item.hasDropShadow()) return false;
        if (item.gradientFeatherApplied()) return false;
        return hasVisibleFillOrStroke(item);
    }

    private static boolean hasVisibleFillOrStroke(ResolvedPageItem item) {
        if (item == null) return false;
        String fill = item.fillColorName();
        if (fill != null && !isNoneColor(fill)) return true;
        String stroke = item.strokeColorName();
        return stroke != null && !isNoneColor(stroke) && item.strokeWeight() > 0;
    }

    private static boolean isPlanSource(ObjectPlan plan, String idText) {
        if (plan == null || idText == null) return false;
        try {
            int id = Integer.parseInt(idText);
            return containsInt(plan.sourceObjectIds, id)
                    || containsInt(plan.visualSourceObjectIds, id)
                    || plan.domId == id;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean containsBounds(double[] outer, double[] inner) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        double tolerance = 1.5;
        return outer[0] <= inner[0] + tolerance
                && outer[1] <= inner[1] + tolerance
                && outer[2] >= inner[2] - tolerance
                && outer[3] >= inner[3] - tolerance;
    }

    private static double boundsArea(double[] bounds) {
        if (bounds == null || bounds.length < 4) return -1;
        double w = Math.abs(bounds[3] - bounds[1]);
        double h = Math.abs(bounds[2] - bounds[0]);
        return w * h;
    }

    private static boolean isInlineTextShellOwnerForChildren(
            ObjectPlan plan,
            RenderedGroup shell,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (plan == null || shell == null || childTfs == null || childTfs.isEmpty()) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.domId != shell.id() && !containsInt(plan.sourceObjectIds, shell.id())) return false;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || childTf.id() == null) return false;
            int childId;
            try {
                childId = Integer.parseInt(childTf.id());
            } catch (NumberFormatException e) {
                return false;
            }
            if (!containsInt(plan.ownedTextFrameIds, childId)) return false;
        }
        return true;
    }

    /** RGBA PNG를 흰 배경에 합성해 불투명 PNG 바이트로 반환(한글 imgBrush 알파→검정 회피). */
    private static byte[] flattenOntoWhite(BufferedImage src) throws java.io.IOException {
        BufferedImage flat = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = flat.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(flat, "png", bos);
        return bos.toByteArray();
    }

    private static byte[] prepareInlineTextShellImageData(BufferedImage img) throws Exception {
        return prepareInlineTextShellImageData(img, false);
    }

    private static byte[] prepareInlineTextShellImageData(BufferedImage img, boolean preserveSourceCanvas) throws Exception {
        BufferedImage shell = VisualCropper.knockOutPaperLikeFill(img);
        if (!preserveSourceCanvas) {
            VisualCropper.AlphaCropResult crop = VisualCropper.alphaCrop(shell);
            if (crop != null && crop.image != null) {
                shell = crop.image;
            }
        }
        return flattenOntoWhite(shell);
    }

    private static boolean shouldPreserveInlineShellSourceCanvas(
            ResolvedBuildContext ctx,
            ObjectPlan childPlan) {
        if (ctx == null || childPlan == null || ctx.ownershipPlans == null) return false;
        if (childPlan.placement != Placement.INLINE) return false;
        if (childPlan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (childPlan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        for (ObjectPlan parent : ctx.ownershipPlans) {
            if (isImageBackedCompositeParentOfInlineShell(parent, childPlan)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImageBackedCompositeParentOfInlineShell(
            ObjectPlan parent,
            ObjectPlan child) {
        if (parent == null || child == null || parent == child) return false;
        if (parent.pageIndex != child.pageIndex) return false;
        if (parent.placement != Placement.FLOATING) return false;
        if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (!containsText(parent.reason, "image_group_text_hidden")) return false;
        if (!basenameOf(parent.file).startsWith("img_")) return false;
        if (!containsAllInts(parent.sourceObjectIds, child.sourceObjectIds)) return false;
        return containsAllInts(parent.ownedTextFrameIds, child.ownedTextFrameIds);
    }

    private static boolean containsAllInts(int[] owner, int[] child) {
        if (child == null || child.length == 0) return true;
        if (owner == null || owner.length == 0) return false;
        for (int value : child) {
            if (!containsInt(owner, value)) return false;
        }
        return true;
    }

    private static boolean containsText(String value, String needle) {
        return value != null && needle != null && value.contains(needle);
    }

    private static String basenameOf(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static void applyInlineShellShapeStyle(
            ResolvedBuildContext ctx,
            ResolvedPageItem anchorItem,
            ASTInlineObject obj) {
        if (ctx == null || ctx.resolvedData == null || anchorItem == null || obj == null) return;
        String fillName = anchorItem.fillColorName();
        String fillHex = ctx.resolvedData.resolveColorHex(fillName);
        if (fillHex != null) {
            obj.fillColor(fillHex);
            obj.fillTint(anchorItem.fillTint() > 0 ? (int) anchorItem.fillTint() : 100);
        }
        String strokeName = anchorItem.strokeColorName();
        String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
        if (strokeHex != null && anchorItem.strokeWeight() > 0) {
            obj.strokeColor(strokeHex);
            double sw = anchorItem.strokeWeight();
            if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
            obj.strokeWeight(Math.max(sw, 0.5));
            obj.strokeTint(anchorItem.strokeTint() > 0 ? (int) anchorItem.strokeTint() : 100);
        }
        if (anchorItem.cornerRadius() > 0) {
            obj.cornerRadius(anchorItem.cornerRadius());
        } else {
            double[] gb = anchorItem.geometricBounds();
            if (gb != null && gb.length >= 4) {
                double h = Math.abs(gb[2] - gb[0]);
                if ("Oval".equals(anchorItem.type()) && h > 0) {
                    obj.cornerRadius(h / 2.0);
                }
            }
        }
        obj.shellShapeType(anchorItem.type());
        if (obj.fillColor() != null || obj.strokeColor() != null || obj.cornerRadius() > 0) {
            obj.nativeGraphicsAllowed(true);
        }
    }

    private static boolean hasTextHiddenInlineShell(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedTextFrame childTf) {
        return findTextHiddenInlineShell(ctx, anchoredObjectId, childTf) != null;
    }

    private static RenderedGroup findTextHiddenInlineShell(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedTextFrame childTf) {
        if (ctx == null || ctx.resolvedData == null || childTf == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        String childId = childTf.id();
        int childDomId;
        try {
            childDomId = Integer.parseInt(childId);
        } catch (NumberFormatException e) {
            childDomId = -1;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId) continue;
            if (!"inline_object".equals(rg.itemType())) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (childDomId >= 0
                    && plan != null
                    && plan.placement == Placement.INLINE
                    && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && containsInt(plan.ownedTextFrameIds, childDomId)) {
                return rg;
            }
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            if (!containsStringId(rg.editableTextFrameIds(), childId)) continue;
            return rg;
        }
        return null;
    }

    private static boolean containsStringId(String[] ids, String id) {
        if (ids == null || id == null) return false;
        for (String v : ids) {
            if (id.equals(v)) return true;
        }
        return false;
    }

    private static ResolvedTextFrame firstEditableTextFrameForRenderedGroup(
            ResolvedBuildContext ctx,
            RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return null;
        String[] ids = rg.editableTextFrameIds();
        if (ids == null || ids.length == 0) {
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
                return null;
            }
            ids = new String[plan.ownedTextFrameIds.length];
            for (int i = 0; i < plan.ownedTextFrameIds.length; i++) {
                ids[i] = String.valueOf(plan.ownedTextFrameIds[i]);
            }
        }
        ResolvedTextFrame found = null;
        for (String id : ids) {
            if (id == null) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(id);
            if (tf == null && id.startsWith("u")) {
                try {
                    tf = ctx.resolvedData.getTextFrame(String.valueOf(Integer.parseInt(id.substring(1), 16)));
                } catch (Exception ignored) {
                    // Keep scanning other ids.
                }
            }
            if (tf == null) continue;
            if (!ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) continue;
            if (found != null) return null;
            found = tf;
        }
        return found;
    }

    private static boolean shouldOverlayRenderedBadgeText(ResolvedBuildContext ctx, RenderedGroup matched) {
        if (matched == null) return true;
        if (ctx != null && ctx.isCompleteInlinePngByOwnershipPlan(matched)) return false;
        if (isCompletePngSimpleButtonLabel(ctx, matched)) return false;
        if (matched.hasEditableTextHiddenFromPng()) return true;
        if (Boolean.TRUE.equals(matched.containsEditableText())
                && "indesign_png".equals(matched.textOwner())) {
            if ("visual_marker_label_indesign_png".equals(matched.reason())) return true;
            return false;
        }
        return true;
    }

    private static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return ctx != null
                && ctx.resolvedData != null
                && ctx.resolvedData.shouldUseCompletePngForSimpleButtonLabel(rg);
    }

    public static boolean isSimpleButtonLabelAnchor(ResolvedBuildContext ctx, int anchoredObjectId) {
        return SimpleButtonLabelInlineFactory.hasPlan(ctx, anchoredObjectId);
    }

    private static ResolvedTextFrame findSimpleButtonLabelChildTextFrame(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        String anchorId = String.valueOf(anchoredObjectId);
        java.util.Set<String> descendantIds = ctx.resolvedData.buildDescendantSet(anchorId, 5);
        ResolvedTextFrame found = null;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            boolean inGroup = anchorId.equals(pi.parentId()) || descendantIds.contains(pi.parentId());
            if (!inGroup) continue;
            if (!tf.isInline()) continue;
            if (!ctx.resolvedData.isSimpleButtonLabelTextFrame(tf.id())) continue;
            if (found != null) return null;
            found = tf;
        }
        return found;
    }

    /** 배지 텍스트 단락 빌드 (CENTER 정렬, 폰트 속성 복사). INLINE_TEXT_FRAME/INLINE_BADGE_GROUP 공용. */
    private static void buildBadgeParagraph(ResolvedBuildContext ctx, ResolvedTextFrame childTf, ASTInlineObject obj) {
        if (ctx != null && ctx.resolvedData != null && childTf != null && childTf.storyId() != null) {
            ResolvedStory story = ctx.resolvedData.getStory(childTf.storyId());
            if (shouldUseResolvedParagraphsForInlineShell(story)) {
                List<ASTParagraph> paragraphs = StoryConverter.convertStoryParagraphs(ctx, story, false);
                if (paragraphs != null && !paragraphs.isEmpty()) {
                    for (ASTParagraph paragraph : paragraphs) {
                        obj.addParagraph(paragraph);
                    }
                    return;
                }
            }
        }
        String text = childTf.frameVisibleText().replace("￼", "").replace("\r", "").replace("\n", "").trim();
        ASTParagraph paraInner = new ASTParagraph();
        paraInner.alignment("CENTER");
        addSyntheticRunsFromTextFrame(ctx, paraInner, childTf, text);
        obj.addParagraph(paraInner);
    }

    private static boolean shouldUseResolvedParagraphsForInlineShell(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int visibleParagraphs = 0;
        int visibleRuns = 0;
        int visibleChars = 0;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            boolean hasText = false;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                String text = run.text().replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
                if (text.isEmpty()) continue;
                visibleRuns++;
                visibleChars += text.length();
                hasText = true;
            }
            if (hasText) visibleParagraphs++;
        }
        return visibleParagraphs > 1 || visibleRuns > 1 || visibleChars > 20;
    }

    private static void addSyntheticRunsFromTextFrame(ResolvedBuildContext ctx,
                                                      ASTParagraph paragraph,
                                                      ResolvedTextFrame textFrame,
                                                      String text) {
        if (paragraph == null || text == null || text.isEmpty()) return;
        ResolvedRun sourceRun = firstResolvedRun(ctx, textFrame);
        List<ASTTextRun> runs;
        if (sourceRun != null) {
            runs = TextRunSegmenter.fromResolvedText(
                    text,
                    sourceRun,
                    color -> RunBuilder.resolveColorToHex(ctx, color),
                    paragraph.hasTabStops(),
                    false,
                    null);
        } else {
            runs = TextRunSegmenter.fromSyntheticText(text, null, paragraph.hasTabStops());
        }
        applyIdmlRunTintFallback(ctx, textFrame, runs);
        for (ASTTextRun run : runs) {
            paragraph.addItem(run);
        }
    }

    private static ResolvedRun firstResolvedRun(ResolvedBuildContext ctx, ResolvedTextFrame textFrame) {
        if (ctx == null || ctx.resolvedData == null || textFrame == null || textFrame.storyId() == null) {
            return null;
        }
        ResolvedStory story = ctx.resolvedData.getStory(textFrame.storyId());
        if (story != null && story.paragraphs() != null) {
            for (ResolvedParagraph rp : story.paragraphs()) {
                if (rp == null || rp.runs() == null || rp.runs().isEmpty()) continue;
                for (ResolvedRun run : rp.runs()) {
                    if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                    String visible = run.text()
                            .replace("\uFFFC", "")
                            .replace("\r", "")
                            .replace("\n", "")
                            .trim();
                    if (!visible.isEmpty()) {
                        return run;
                    }
                }
            }
        }
        return null;
    }

    private static void applyIdmlRunTintFallback(ResolvedBuildContext ctx,
                                                 ResolvedTextFrame textFrame,
                                                 List<ASTTextRun> runs) {
        if (ctx == null || ctx.loadIDMLStory == null || textFrame == null
                || textFrame.storyId() == null || runs == null || runs.isEmpty()) {
            return;
        }
        IDMLCharacterRun idmlRun = firstVisibleIdmlRun(ctx, textFrame.storyId());
        if (idmlRun == null || idmlRun.fillColor() == null || idmlRun.fillTint() == null) {
            return;
        }
        String tinted = RunBuilder.resolveColorToHex(ctx, idmlRun.fillColor(), idmlRun.fillTint());
        if (tinted == null) return;
        for (ASTTextRun run : runs) {
            if (run != null) run.textColor(tinted);
        }
    }

    private static IDMLCharacterRun firstVisibleIdmlRun(ResolvedBuildContext ctx, String storyId) {
        IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
        if (idmlStory == null || idmlStory.paragraphs() == null) return null;
        for (IDMLParagraph paragraph : idmlStory.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                if (run == null || run.content() == null) continue;
                String visible = run.content()
                        .replace("\uFFFC", "")
                        .replace("\r", "")
                        .replace("\n", "")
                        .trim();
                if (!visible.isEmpty()) return run;
            }
        }
        return null;
    }

    /**
     * SPEC-025: 인라인 앵커가 빈(텍스트 없음) TextFrame 이면서 fillColor 가 있는 데코 박스
     * (예: 본문 빈칸 / 강조 박스) → INLINE_TEXT_FRAME 으로 변환.
     *
     * 조건:
     * - 앵커 ID 가 TextFrame 이고 isInline=true
     * - frameVisibleText 가 비어있음
     * - fillColor 가 None 이 아님
     * - 다른 채널로 렌더되지 않음
     */
    static ASTInlineObject tryInlineEmptyFilledBoxAsFrame(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) return null;
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null) return null;
        if (!tf.isInline()) return null;
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        // 텍스트가 있으면 적용 안 함 (tryInlineTextFrameAsRun 이 처리)
        String visText = tf.frameVisibleText();
        if (visText != null) {
            String cleaned = visText.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (!cleaned.isEmpty()) return null;
        }

        // fillColor 가 없으면 적용 안 함
        String fillName = tf.fillColor();
        if (fillName == null || "None".equals(fillName) || "[None]".equals(fillName)) return null;

        double[] sizePt = textFrameSizePoints(ctx, tf);
        if (sizePt == null) return null;
        double w = sizePt[0];
        double h = sizePt[1];
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
        obj.noAutoLineWrap(shouldUseNoAutoLineWrap(tf, true));

        String fillHex = ctx.resolvedData.resolveColorHex(fillName);
        if (fillHex != null) {
            obj.fillColor(fillHex);
            obj.fillTint(tf.fillTint() > 0 && tf.fillTint() <= 100 ? tf.fillTint() : 100);
        }
        String strokeName = tf.strokeColor();
        if (strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName) && tf.strokeWeight() > 0) {
            String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
            if (strokeHex != null) {
                obj.strokeColor(strokeHex);
                double sw = tf.strokeWeight();
                if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                obj.strokeWeight(Math.max(sw, 0.6));
            }
        }
        if (tf.cornerRadius() > 0) {
            double cr = tf.cornerRadius();
            if (ctx.scaleFactor > 0) cr = cr / ctx.scaleFactor;
            obj.cornerRadius(cr);
        }
        // 빈 단락 1개로 — 텍스트 없는 컬러 박스
        ASTParagraph emptyPara = new ASTParagraph();
        obj.addParagraph(emptyPara);

        return obj;
    }

    static ASTTextRun tryInlineTextFrameAsRun(ResolvedBuildContext ctx, int anchoredObjectId) {
        return tryInlineTextFrameAsRun(ctx, anchoredObjectId, null, null);
    }

    static List<ASTInlineItem> tryInlineTextFrameAsItems(ResolvedBuildContext ctx, int anchoredObjectId,
                                                         String previousText, String nextText) {
        // Phase 2가 floating text box로 승격한 TF → 인라인 런 중복 방지
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)
                || hasPlannedFloatingHwpxTextDescendant(ctx, anchoredObjectId)) {
            return null;
        }
        String domId = String.valueOf(anchoredObjectId);
        if (isConceptDiagramTextFrame(ctx, domId)) return null;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(domId)) return null;
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (story == null || story.paragraphs() == null || story.paragraphs().size() != 1) return null;
        ResolvedParagraph rp = story.paragraphs().get(0);
        if (rp == null || rp.runs() == null || rp.runs().isEmpty()) return null;

        boolean parentFrameUnderline = inlineTextFrameHasParagraphUnderline(ctx, tf.storyId(), story);
        boolean hasNestedAnchor = false;
        List<ASTInlineItem> items = new ArrayList<>();
        for (ResolvedRun rr : rp.runs()) {
            if (rr == null) continue;
            if (rr.isInlineAnchor()) {
                Integer childId = rr.anchoredObjectId();
                if (childId == null) continue;
                hasNestedAnchor = true;

                ASTInlineObject groupShell = tryInlineGroupShellWithEditableChild(ctx, childId);
                if (groupShell != null) {
                    items.add(groupShell);
                    continue;
                }

                List<ASTInlineObject> boxList = tryInlineGroupAsBoxList(ctx, childId);
                if (boxList != null && !boxList.isEmpty()) {
                    items.addAll(boxList);
                    continue;
                }

                ASTEquation fracEq = tryInlineFractionAsEquation(ctx, childId);
                if (fracEq != null) {
                    items.add(fracEq);
                    continue;
                }

                ASTInlineObject singleBadge = tryInlineGroupAsSingleBadge(ctx, childId);
                if (singleBadge != null) {
                    items.add(singleBadge);
                    continue;
                }

                ASTInlineObject plannedTextShell = loadPlannedInlineTextShellForTextFrame(ctx, childId);
                if (plannedTextShell != null) {
                    items.add(plannedTextShell);
                    continue;
                }

                ASTTextRun textRun = tryInlineTextFrameAsRun(ctx, childId, previousText, nextText);
                if (textRun != null) {
                    items.add(textRun);
                    continue;
                }

                ASTInlineObject inlineTableFrame = tryInlineTextFrameWithTables(ctx, childId);
                if (inlineTableFrame != null) {
                    items.add(inlineTableFrame);
                    continue;
                }

                ASTInlineObject emptyBox = tryInlineEmptyFilledBoxAsFrame(ctx, childId);
                if (emptyBox != null) {
                    items.add(emptyBox);
                    continue;
                }

                List<ASTInlineObject> childEditableBoxes = buildChildEditableBoxes(ctx, childId);
                if (childEditableBoxes != null && !childEditableBoxes.isEmpty()) {
                    items.addAll(childEditableBoxes);
                    continue;
                }

                ASTInlineObject inlineObj = loadInlineObject(ctx, childId);
                if (inlineObj != null) {
                    items.add(inlineObj);
                    continue;
                }

                ASTTextRun spaceRun = createSpaceRunForEmptyAnchor(ctx, childId);
                if (spaceRun != null) items.add(spaceRun);
                continue;
            }

            String text = rr.text();
            if (text == null || text.isEmpty()) continue;
            List<ASTTextRun> textRuns = TextRunSegmenter.fromResolvedText(
                    text,
                    rr,
                    color -> RunBuilder.resolveColorToHex(ctx, color),
                    false,
                    false,
                    null);
            for (ASTTextRun textRun : textRuns) {
                if (parentFrameUnderline) textRun.underline(true);
                items.add(textRun);
            }
        }

        if (!hasNestedAnchor || items.isEmpty()) return null;
        return items;
    }

    static ASTInlineObject tryInlineTextFrameWithTables(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.loadIDMLStory == null) return null;
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)
                && !hasPlannedInlineHwpxTextDescendant(ctx, anchoredObjectId)) {
            return null;
        }

        String domId = String.valueOf(anchoredObjectId);
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId("u" + Integer.toHexString(anchoredObjectId));

        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        double[] sizePt = tf != null ? textFrameSizePoints(ctx, tf) : null;
        if (sizePt == null) {
            ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(domId);
            double[] gb = anchorItem != null ? anchorItem.geometricBounds() : null;
            if (validBounds(gb)) {
                sizePt = new double[]{
                        Math.abs(gb[3] - gb[1]),
                        Math.abs(gb[2] - gb[0])
                };
            }
        }
        if (sizePt != null) {
            obj.width(CoordinateConverter.pointsToHwpunits(sizePt[0]));
            obj.height(CoordinateConverter.pointsToHwpunits(sizePt[1]));
        }

        if (tf != null) {
            appendInlineTablesFromTextFrame(ctx, obj, tf);
        } else {
            for (ResolvedTextFrame childTf : findInlineTableTextFrameDescendants(ctx, domId)) {
                appendInlineTablesFromTextFrame(ctx, obj, childTf);
            }
        }

        return obj.inlineTables() != null && !obj.inlineTables().isEmpty() ? obj : null;
    }

    private static java.util.List<ResolvedTextFrame> findInlineTableTextFrameDescendants(
            ResolvedBuildContext ctx, String anchorIdStr) {
        java.util.List<ResolvedTextFrame> result = new java.util.ArrayList<>();
        for (ResolvedTextFrame childTf : ctx.resolvedData.textFrames()) {
            if (childTf == null || !childTf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(childTf.id())) continue;
            if (childTf.storyId() == null) continue;
            IDMLStory story = ctx.loadIDMLStory.apply(childTf.storyId());
            if (story == null || !story.hasTables()) continue;
            String curId = childTf.id();
            int depth = 0;
            boolean isDesc = false;
            while (curId != null && depth < 8) {
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(curId);
                if (pi == null) break;
                String pid = pi.parentId();
                if (pid == null) break;
                if (anchorIdStr.equals(pid)) { isDesc = true; break; }
                curId = pid;
                depth++;
            }
            if (isDesc) result.add(childTf);
        }
        result.sort((a, b) -> {
            double[] ab = a.geometricBounds();
            double[] bb = b.geometricBounds();
            if (ab == null || bb == null) return 0;
            double ay = ab[0], by = bb[0];
            if (Math.abs(ay - by) > 1.0) return Double.compare(ay, by);
            return Double.compare(ab[1], bb[1]);
        });
        return result;
    }

    private static void appendInlineTablesFromTextFrame(
            ResolvedBuildContext ctx, ASTInlineObject obj, ResolvedTextFrame tf) {
        if (tf == null || !tf.isInline() || tf.storyId() == null) return;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) return;
        IDMLStory story = ctx.loadIDMLStory.apply(tf.storyId());
        if (story == null || !story.hasTables()) return;
        if (TableFrameOwnershipPolicy.shouldPlaceInlineTableAsPageLevel(ctx, tf, story)) return;

        for (IDMLTable idmlTable : story.tables()) {
            ASTTable table = TableBuilder.buildPreparedAstTable(ctx, idmlTable, 0, 0, 0);
            if (table != null) {
                replaceInlineTableCellTextWithResolvedStory(ctx, tf, table);
                obj.addInlineTable(table);
                if (obj.width() <= 0) obj.width(table.width());
                if (obj.height() <= 0) obj.height(table.height());
            }
        }
    }

    private static void replaceInlineTableCellTextWithResolvedStory(
            ResolvedBuildContext ctx, ResolvedTextFrame tf, ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.storyId() == null || table == null) return;
        if (replaceCellContentWithNestedTextFrame(table)) return;

        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (!shouldUseResolvedParagraphsForInlineShell(story)) return;
        if (!isSingleCellTableShell(table)) return;
        List<ASTParagraph> resolvedParagraphs = StoryConverter.convertStoryParagraphs(ctx, story, false);
        if (resolvedParagraphs == null || resolvedParagraphs.isEmpty()) return;

        String tableText = normalizeInlineTableText(plainText(table));
        String storyText = normalizeInlineTableText(resolvedStoryText(story));
        if (tableText.isEmpty() || storyText.isEmpty()) return;
        if (!tableText.equals(storyText) && !storyText.startsWith(tableText) && !tableText.startsWith(storyText)) return;

        ASTTableCell target = firstContentCell(table);
        if (target == null) return;
        target.paragraphs().clear();
        target.paragraphs().addAll(resolvedParagraphs);
    }

    /**
     * IDML 1x1 table cells often use a nested TextFrame as the real text owner
     * while the table supplies only the visual cell shell.  Keeping that nested
     * TF as an inline object makes HWPX flatten its paragraphs into one cell
     * paragraph, losing run boundaries and paragraph breaks.  Promote the nested
     * TF paragraphs to the cell content once at AST ownership time.
     */
    private static boolean replaceCellContentWithNestedTextFrame(ASTTable table) {
        if (table == null || table.rows() == null) return false;
        boolean replaced = false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null) continue;
                ASTInlineObject nested = onlySemanticNestedTextFrame(cell.paragraphs());
                if (nested == null || nested.paragraphs() == null || nested.paragraphs().isEmpty()) continue;
                cell.paragraphs().clear();
                cell.paragraphs().addAll(nested.paragraphs());
                replaced = true;
            }
        }
        return replaced;
    }

    private static ASTInlineObject onlySemanticNestedTextFrame(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.size() != 1) return null;
        ASTParagraph paragraph = paragraphs.get(0);
        if (paragraph == null || paragraph.items() == null) return null;
        ASTInlineObject nested = null;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !normalizeInlineTableText(text).isEmpty()) return null;
                continue;
            }
            if (!(item instanceof ASTInlineObject)) return null;
            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) return null;
            if (!hasAuthoritativeParagraphStructure(obj.paragraphs())) return null;
            if (nested != null) return null;
            nested = obj;
        }
        return nested;
    }

    private static boolean hasAuthoritativeParagraphStructure(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return false;
        int visibleParagraphs = 0;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            int visibleRuns = 0;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.trim().isEmpty()) visibleRuns++;
                }
            }
            if (visibleRuns > 0) visibleParagraphs++;
            if (visibleRuns > 1) return true;
        }
        return visibleParagraphs > 1;
    }

    private static ASTTableCell firstContentCell(ASTTable table) {
        if (table.rows() == null) return null;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell != null && cell.paragraphs() != null && !cell.paragraphs().isEmpty()) return cell;
            }
        }
        return null;
    }

    private static boolean isSingleCellTableShell(ASTTable table) {
        if (table == null || table.rows() == null) return false;
        int cells = 0;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            cells += row.cells().size();
            if (cells > 1) return false;
        }
        return cells == 1;
    }

    private static String plainText(ASTTable table) {
        StringBuilder sb = new StringBuilder();
        if (table.rows() == null) return "";
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null) continue;
                for (ASTParagraph paragraph : cell.paragraphs()) {
                    if (paragraph == null) continue;
                    String text = ParagraphTextHelpers.getParaPlainText(paragraph);
                    if (text != null) sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static String resolvedStoryText(ResolvedStory story) {
        StringBuilder sb = new StringBuilder();
        if (story == null || story.paragraphs() == null) return "";
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                sb.append(run.text());
            }
        }
        return sb.toString();
    }

    private static String normalizeInlineTableText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008') continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    static ASTTextRun tryInlineTextFrameAsRun(ResolvedBuildContext ctx, int anchoredObjectId,
                                             String previousText, String nextText) {
        // Phase 2가 floating text box로 승격한 TF → 인라인 런 중복 방지
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) return null;
        String domId = String.valueOf(anchoredObjectId);
        if (isConceptDiagramTextFrame(ctx, domId)) return null;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null) {
            return null;
        }
        if (!tf.isInline()) return null;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(domId)) return null;

        // 렌더 PDF 프레임으로 이미 배치된 경우 텍스트 런 변환 안 함
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;
        // IDML Story 우선. ORC anchor의 자식 텍스트는 별도 source object가 소유하므로
        // 여기서 문자열로 합치지 않는다.
        String visText = null;
        if (tf.storyId() != null) {
            IDMLStory idmlStoryRec = ctx.loadIDMLStory.apply(tf.storyId());
            if (idmlStoryRec != null) {
                String extracted = extractTextRecursive(ctx, idmlStoryRec, 0);
                if (extracted != null && !extracted.replace("\uFFFC", "").trim().isEmpty()) {
                    visText = extracted.replace("\uFFFC", "").trim();
                }
            }
        }
        // IDML 에서 못 얻으면 frameVisibleText 폴백
        if (visText == null || visText.isEmpty()) {
            visText = tf.frameVisibleText();
            if (visText != null) {
                visText = visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").trim();
            }
        }
        if (visText == null || visText.isEmpty()) {
            // 빈 답안 박스(빈칸)에 RuleBelow 가 있으면 밑줄 + 공백으로 변환 (예: page 23 "If you lose 5 ___, reset")
            if (tf.storyId() != null) {
                IDMLStory idmlStoryRule = ctx.loadIDMLStory.apply(tf.storyId());
                if (idmlStoryRule != null && !idmlStoryRule.paragraphs().isEmpty()) {
                    boolean hasRuleBelow = false;
                    for (IDMLParagraph p : idmlStoryRule.paragraphs()) {
                        if (p.ruleBelowOn()) { hasRuleBelow = true; break; }
                        String psRef = p.appliedParagraphStyle();
                        if (psRef != null && ctx.styleResolver != null) {
                            IDMLStyleDef sd = ctx.styleResolver.getResolvedParagraphStyle(psRef);
                            if (sd != null && Boolean.TRUE.equals(sd.ruleBelowOn())) { hasRuleBelow = true; break; }
                        }
                    }
                    if (hasRuleBelow) {
                        double[] gb0 = tf.geometricBounds();
                        double w0pt = (gb0 != null && gb0.length >= 4) ? (gb0[3] - gb0[1]) : 56.0;
                        // SPEC-024: 부모 컨테이너가 이미 PNG로 렌더링되어 밑줄을 포함하면
                        // 라이브 텍스트에 다시 밑줄을 그리지 않는다 (이중 밑줄 방지).
                        // 빈칸 bounds를 포함하는 inline_object PNG가 있는지 확인.
                        // 단위: TextFrame.geometricBounds()는 pt 단위, RenderedGroup.bounds()는 mm 원본 → scaleFactor 적용 필요.
                        boolean parentRenderedWithRule = false;
                        if (gb0 != null && gb0.length >= 4) {
                            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg
                                    : ctx.resolvedData.allRenderedFloatingItems()) {
                                if (!"inline_object".equals(rg.itemType())) continue;
                                if (rg.id() == anchoredObjectId) continue; // 자기 자신은 제외
                                double[] rb = rg.bounds();
                                if (rb == null || rb.length < 4) continue;
                                double rb0 = rb[0] * ctx.scaleFactor;
                                double rb1 = rb[1] * ctx.scaleFactor;
                                double rb2 = rb[2] * ctx.scaleFactor;
                                double rb3 = rb[3] * ctx.scaleFactor;
                                if (rb0 <= gb0[0] + 0.5 && rb1 <= gb0[1] + 0.5
                                        && rb2 >= gb0[2] - 0.5 && rb3 >= gb0[3] - 0.5) {
                                    parentRenderedWithRule = true;
                                    break;
                                }
                            }
                        }
                        if (!parentRenderedWithRule) {
                            // 10pt 기준 underscore 폭 ≈ 5pt → count = width / 5 (안전 마진)
                            int charCount = Math.max(3, (int) (w0pt / 5.5));
                            StringBuilder sb = new StringBuilder(charCount);
                            for (int si = 0; si < charCount; si++) sb.append('_');
                            ASTTextRun ulRun = new ASTTextRun();
                            ulRun.text(sb.toString());
                            return ulRun;
                        }
                    }
                }
            }
            return null;
        }

        // resolved story에서 런 스타일 가져오기
        ResolvedStory story = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
        ASTTextRun run = new ASTTextRun();
        boolean inlineVocabularyMarker = isInlineVocabularyMarker(ctx, anchoredObjectId, previousText, nextText);
        run.text(visText + (inlineVocabularyMarker ? "" : " ")); // 기본은 텍스트와의 간격 유지

        if (story != null && !story.paragraphs().isEmpty()) {
            ResolvedParagraph rp = story.paragraphs().get(0);
            if (rp.runs() != null && !rp.runs().isEmpty()) {
                // SPEC-025: 첫 run 이 비어 있는 경우가 있어 (placeholder/empty),
                // 실제 콘텐츠가 있는 첫 run 을 찾아 폰트/색상 추출
                ResolvedRun rr = null;
                for (ResolvedRun candidate : rp.runs()) {
                    String c = candidate.text();
                    if (c != null && c.length() > 0) {
                        // 공백만 있는 run 도 스킵 — 실제 글자 가진 첫 run
                        String trimmed = c.replace("￼", "").trim();
                        if (!trimmed.isEmpty()) { rr = candidate; break; }
                    }
                }
                if (rr == null) rr = rp.runs().get(0); // fallback
                if (rr.fontFamily() != null) run.fontFamily(rr.fontFamily());
                if (rr.fontStyle() != null) run.fontStyle(rr.fontStyle());
                if (rr.fontSize() != null && rr.fontSize() > 0) {
                    run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                }
                if (rr.fillColor() != null) run.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
                if (rr.underline() != null && rr.underline()) run.underline(true);
                if (rr.strikeThru() != null && rr.strikeThru()) run.strikeThrough(true);
            }
        }
        if (inlineVocabularyMarker) {
            Short markerShift = inlineFrameBaselineShift(tf, run, story);
            if (markerShift != null) run.baselineShift(markerShift);
        }
        // IDML CharacterStyle에서 밑줄 추론
        if (tf.storyId() != null) {
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(tf.storyId());
            if (idmlStory != null && !idmlStory.paragraphs().isEmpty()) {
                IDMLParagraph ip = idmlStory.paragraphs().get(0);
                if (!ip.characterRuns().isEmpty()) {
                    IDMLCharacterRun cr = ip.characterRuns().get(0);
                    if (cr.underline() != null && cr.underline()) run.underline(true);
                    String cs = cr.appliedCharacterStyle();
                    if (cs != null && (cs.contains("밑줄") || cs.toLowerCase().contains("underline"))) {
                        run.underline(true);
                    }
                }
                // SPEC-025: IDML 단락에 RuleBelow="true" 가 있거나 paragraph style 에 ruleBelowOn=true 면
                // 인라인 텍스트에 char-level underline 적용 (예: "소단원 도입 예(1103)" style)
                IDMLParagraph ip0 = idmlStory.paragraphs().get(0);
                boolean hasRuleBelow = ip0.ruleBelowOn();
                if (!hasRuleBelow && ctx.styleResolver != null) {
                    String psRef = ip0.appliedParagraphStyle();
                    if (psRef != null) {
                        IDMLStyleDef sd = ctx.styleResolver.getResolvedParagraphStyle(psRef);
                        if (sd != null && Boolean.TRUE.equals(sd.ruleBelowOn())) {
                            hasRuleBelow = true;
                        }
                    }
                }
                if (hasRuleBelow) run.underline(true);
            }
        }

        // 인라인 TextFrame의 ParagraphStyle에서 밑줄 추론
        // resolved story의 styleName에 "선", "답+선", "underline" 등이 포함되면 밑줄
        if (story != null && !story.paragraphs().isEmpty()) {
            String styleName = story.paragraphs().get(0).styleName();
            if (styleName != null && (styleName.contains("선") || styleName.toLowerCase().contains("underline"))) {
                run.underline(true);
            }
        }

        return run;
    }

    private static boolean inlineTextFrameHasParagraphUnderline(ResolvedBuildContext ctx, String storyId, ResolvedStory story) {
        if (story != null && story.paragraphs() != null && !story.paragraphs().isEmpty()) {
            String styleName = story.paragraphs().get(0).styleName();
            if (styleName != null && (styleName.contains("선") || styleName.toLowerCase().contains("underline"))) {
                return true;
            }
        }
        if (storyId == null) return false;

        IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
        if (idmlStory == null || idmlStory.paragraphs().isEmpty()) return false;

        IDMLParagraph ip0 = idmlStory.paragraphs().get(0);
        if (ip0.ruleBelowOn()) return true;
        if (!ip0.characterRuns().isEmpty()) {
            IDMLCharacterRun cr = ip0.characterRuns().get(0);
            if (cr.underline() != null && cr.underline()) return true;
            String cs = cr.appliedCharacterStyle();
            if (cs != null && (cs.contains("밑줄") || cs.toLowerCase().contains("underline"))) return true;
        }

        if (ctx.styleResolver != null) {
            String psRef = ip0.appliedParagraphStyle();
            if (psRef != null) {
                IDMLStyleDef sd = ctx.styleResolver.getResolvedParagraphStyle(psRef);
                if (sd != null && Boolean.TRUE.equals(sd.ruleBelowOn())) return true;
            }
        }
        return false;
    }

    static boolean isInlineVocabularyMarker(ResolvedBuildContext ctx, int anchoredObjectId,
                                            String previousText, String nextText) {
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
        if (tf == null) return false;
        String visText = tf.frameVisibleText();
        if (visText == null || cleanInlineText(visText).isEmpty()) {
            ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
            if (story != null && story.paragraphs() != null && !story.paragraphs().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ResolvedParagraph rp : story.paragraphs()) {
                    if (rp.runs() == null) continue;
                    for (ResolvedRun rr : rp.runs()) {
                        if (rr.text() != null) sb.append(rr.text());
                    }
                }
                visText = sb.toString();
            }
        }
        String marker = cleanInlineText(visText);
        if (!marker.matches("\\d{1,2}")) return false;
        if (!endsWithLayoutSpace(previousText)) return false;
        if (!startsWithHangul(nextText)) return false;
        if (!tf.isInline()) return false;

        double[] gb = tf.geometricBounds();
        if (gb != null && gb.length >= 4) {
            double w = Math.abs(gb[3] - gb[1]);
            double h = Math.abs(gb[2] - gb[0]);
            if (w > 8.0 || h > 8.0) return false;
        }

        ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (story != null && story.paragraphs() != null && !story.paragraphs().isEmpty()) {
            ResolvedParagraph rp = story.paragraphs().get(0);
            String styleName = rp.styleName();
            if (styleName != null && styleName.contains("어휘숫자")) return true;
        }

        // 소형 인라인 숫자 프레임은 본문 어휘 번호로 쓰이는 경우가 많다.
        // 스타일명이 누락된 추출본에서도 앞뒤 문맥과 크기가 맞으면 번호 뒤 공백을 만들지 않는다.
        return true;
    }

    private static String cleanInlineText(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
    }

    private static boolean endsWithLayoutSpace(String text) {
        if (text == null || text.isEmpty()) return false;
        char ch = text.charAt(text.length() - 1);
        return ch == ' ' || ch == '\t' || ch == '\u00A0' || ch == '\u2002' || ch == '\u2003';
    }

    private static boolean startsWithHangul(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == '\uFFFC') continue;
            return ch >= '\uAC00' && ch <= '\uD7A3';
        }
        return false;
    }

    private static Short inlineFrameBaselineShift(ResolvedTextFrame tf, ASTTextRun run, ResolvedStory story) {
        if (tf == null || run == null) {
            return null;
        }
        double[] gb = tf.geometricBounds();
        if (gb == null || gb.length < 4) return null;

        double frameHeightPt = Math.abs(gb[2] - gb[0]);
        double fontSizePt = run.fontSizeHwpunits() != null && run.fontSizeHwpunits() > 0
                ? run.fontSizeHwpunits() / 100.0
                : firstStoryFontSizePt(story);
        if (frameHeightPt <= 0 || fontSizePt <= 0) return null;

        // InDesign inline vocabulary markers are often tiny anchored text frames whose local
        // bottom sits on the insertion baseline. When we replace that frame with live text,
        // preserve the marker's line-top feel with a HWPX character offset.
        if (frameHeightPt > fontSizePt * 1.25) return null;

        double shiftPct = Math.max(60.0, ((fontSizePt - frameHeightPt) / fontSizePt) * 75.0);
        shiftPct = Math.max(20.0, Math.min(75.0, shiftPct));
        return (short) -Math.round(shiftPct);
    }

    private static double firstStoryFontSizePt(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return 0;
        for (ResolvedParagraph rp : story.paragraphs()) {
            if (rp == null || rp.runs() == null) continue;
            for (ResolvedRun rr : rp.runs()) {
                if (rr != null && rr.fontSize() != null && rr.fontSize() > 0) {
                    return rr.fontSize();
                }
            }
        }
        return 0;
    }

    /** IDML 경로용: resolved TextFrame bounds만으로 판별 (renderedFloatingItems 사용 안 함) */
    static boolean isAnchoredOutsideParentByTextFrame(ResolvedBuildContext ctx, int anchoredId, String parentStoryId) {
        if (isSyntheticMasterStory(parentStoryId) && hasInlineObjectRender(ctx, anchoredId)) return false;
        ResolvedTextFrame anchoredTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (anchoredTf == null) {
            // TextFrame이 아닌 인라인(Polygon/Rectangle 등): renderedFloatingItems에서 bounds 확인
            return isAnchoredOutsideParent(ctx, anchoredId, parentStoryId);
        }
        double[] aGb = anchoredTf.geometricBounds();
        if (aGb == null || aGb.length < 4) return false;
        // 다중 컬럼 스레드 스토리: 어느 한 프레임에라도 포함되면 outside가 아님
        boolean anyParentChecked = false;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                anyParentChecked = true;
                if (!isOutsideParentBounds(ctx, aGb, pGb)) return false;
            }
        }
        return anyParentChecked;
    }

    /** Resolved 경로용: resolved TextFrame + renderedFloatingItems bounds로 판별 */
    static boolean isAnchoredOutsideParent(ResolvedBuildContext ctx, int anchoredId, String parentStoryId) {
        if (isSyntheticMasterStory(parentStoryId) && hasInlineObjectRender(ctx, anchoredId)) return false;
        double[] aGb = null;
        // 1) resolved TextFrame에서 bounds
        ResolvedTextFrame anchoredTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredId));
        if (anchoredTf != null) {
            aGb = anchoredTf.geometricBounds();
        }
        // 2) resolved에 없으면 renderedFloatingItems의 inline_object bounds
        //    renderedFloatingItems bounds は mm 単位 → pt に変환이 필요
        if (aGb == null || aGb.length < 4) {
            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == anchoredId && "inline_object".equals(rg.itemType())) {
                    double[] raw = rg.bounds();
                    if (raw != null && raw.length >= 4) {
                        aGb = new double[]{raw[0] * ctx.scaleFactor, raw[1] * ctx.scaleFactor,
                                raw[2] * ctx.scaleFactor, raw[3] * ctx.scaleFactor};
                    }
                    break;
                }
            }
        }
        if (aGb == null || aGb.length < 4) return false;

        // 부모 Story의 모든 비인라인 TextFrame을 검사. 어느 한 프레임에라도 포함되면 outside가 아님.
        // (스레드 체인된 다중 컬럼 스토리에서 한쪽 컬럼에만 포함되어도 정상)
        boolean anyParentChecked = false;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (parentStoryId.equals(tf.storyId()) && !tf.isInline()) {
                double[] pGb = tf.geometricBounds();
                if (pGb == null || pGb.length < 4) continue;
                anyParentChecked = true;
                if (!isOutsideParentBounds(ctx, aGb, pGb)) return false;
            }
        }
        return anyParentChecked;
    }

    private static boolean isSyntheticMasterStory(String storyId) {
        return storyId != null && (storyId.contains("_pi") || storyId.contains("_oc"));
    }

    private static boolean hasInlineObjectRender(ResolvedBuildContext ctx, int anchoredId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredId && "inline_object".equals(rg.itemType()) && rg.file() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 인라인 객체 bounds가 부모 프레임 밖에 위치하는지 판단.
     * - 중심 X가 부모 밖 3pt 이상 → outside
     * - 오른쪽 끝이 부모 밖으로 돌출하고 폭의 50% 이상 밖 → outside (장식 그래픽)
     */
    private static boolean isOutsideParentBounds(ResolvedBuildContext ctx, double[] aGb, double[] pGb) {
        double aCenterX = (aGb[1] + aGb[3]) / 2.0;
        // 중심 X 기준 (기존 로직, 허용 오차 3pt)
        if (aCenterX > pGb[3] + 3.0 || aCenterX < pGb[1] - 3.0) return true;
        // 오른쪽 돌출 체크: 인라인 객체의 절반 이상이 부모 밖
        double aWidth = aGb[3] - aGb[1];
        if (aWidth > 0 && aGb[3] > pGb[3]) {
            double overshoot = aGb[3] - pGb[3];
            if (overshoot > aWidth * 0.5) return true;
        }
        // 왼쪽 돌출 체크
        if (aWidth > 0 && aGb[1] < pGb[1]) {
            double overshoot = pGb[1] - aGb[1];
            if (overshoot > aWidth * 0.5) return true;
        }
        return false;
    }

    /**
     * PNG/텍스트 없는 인라인 빈칸 앵커를 공백 텍스트 런으로 대체.
     * 교과서 빈칸 채우기 문제의 ( ) 안 공백 등.
     */
    static ASTTextRun createSpaceRunForEmptyAnchor(ResolvedBuildContext ctx, int anchoredObjectId) {
        // SPEC-020: 빈칸박스 TextFrame(공백 내용)은 실제 bounds 폭에 맞춰 공백 수 계산
        // + 밑줄 적용 — 배경 PNG의 "빈칸 밑줄"과 위치/길이 동조.
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
        double widthPt = 20.0;
        if (tf != null && tf.geometricBounds() != null && tf.geometricBounds().length >= 4) {
            double[] gb = tf.geometricBounds();
            widthPt = Math.max(0, gb[3] - gb[1]);
        }
        // 공백 1칸 ≈ 3pt (10.5pt 폰트 기준). 최소 4칸.
        int spaces = Math.max(4, (int) Math.round(widthPt / 3.0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spaces; i++) sb.append(' ');
        ASTTextRun run = new ASTTextRun();
        run.text(sb.toString());
        run.underline(true);
        return run;
    }

    /**
     * regularRowGroups 의 첫 두 행 간 Y 좌표 차이로 원본 행간(pt→hwpunit)을 계산.
     * 행이 1개 이하이거나 gap이 2pt 미만이면 0 반환 (기본 행간 사용).
     */
    private static int computeRowSpacing(
            java.util.List<java.util.List<Integer>> regularRowGroups,
            java.util.List<double[]> resultKeys) {
        if (regularRowGroups.size() < 2) return 0;
        double y0 = resultKeys.get(regularRowGroups.get(0).get(0))[0];
        double y1 = resultKeys.get(regularRowGroups.get(1).get(0))[0];
        double gap = Math.abs(y1 - y0);
        if (gap < 2.0) return 0;
        return (int) CoordinateConverter.pointsToHwpunits(gap);
    }

    private static boolean isNoneColor(String c) {
        return c == null || c.isEmpty() || "None".equals(c) || c.contains("[None]");
    }

    private static boolean isInlineRenderedGroupType(RenderedGroup rg) {
        if (rg == null) return false;
        return "inline_object".equals(rg.itemType()) || "inline_object".equals(rg.type());
    }

    /**
     * renderedFloatingItems에서 인라인 객체 PNG를 로드하여 ASTInlineObject로 변환.
     */
    public static ASTInlineObject loadInlineObject(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx.basePath == null) return null;

        // Phase 2 가 floating text box 로 승격한 inline TF → inline PNG 도 억제 (28pt PNG가 행간 팽창하는 것 방지).
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;

        // renderedFloatingItems에서 해당 ID의 inline_object 또는 null-type inline TF 찾기.
        // 배치 여부는 Stage 1 ObjectPlan의 PLACE_INLINE_PNG만 따른다.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() != anchoredObjectId) continue;
            boolean proceed = isInlineRenderedGroupType(rg);
            if (!proceed && rg.itemType() == null) {
                ResolvedTextFrame ancTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                proceed = ancTf != null && ancTf.isInline();
            }
            if (proceed) {
                VisualAction plannedVisualAction = ctx.visualActionByOwnershipPlan(rg);
                Placement plannedPlacement = ctx.placementByOwnershipPlan(rg);
                boolean placeInlinePng = plannedVisualAction == VisualAction.PLACE_INLINE_PNG
                        && plannedPlacement == Placement.INLINE;
                boolean placeInlineTextShell = plannedVisualAction == VisualAction.PLACE_TEXT_SHELL
                        && plannedPlacement == Placement.INLINE;
                if (!ctx.hasOwnershipPlan(rg)
                        || (!placeInlinePng && !placeInlineTextShell)) {
                    return null;
                }
                if (placeInlineTextShell) {
                    ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
                    ResolvedTextFrame childTf = firstEditableTextFrameForRenderedGroup(ctx, rg);
                    ASTInlineObject shellObject =
                            buildInlineShellObject(ctx, anchoredObjectId, anchorItem, childTf, rg);
                    if (shellObject != null) return shellObject;
                }
                boolean isNullTypeInline = rg.itemType() == null;
                // inline_object PNG를 그대로 사용 (tryInlineGroupAsSingleBadge가 먼저 INLINE_TEXT_FRAME을 시도했으므로
                // 여기 도달했다면 구조 조건 미충족 → PNG fallback이 가장 정확한 표현).
                RenderedGroup effectiveRg = rg;
                if (effectiveRg.file() == null) return null;
                File pngFile = new File(ctx.basePath, effectiveRg.file());
                if (!pngFile.exists()) return null;

                try {
                    byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                    BufferedImage img = ImageIO.read(pngFile);
                    if (img == null) return null;
                    // 2x2 이하 빈 이미지 무시
                    if (img.getWidth() <= 2 && img.getHeight() <= 2) return null;

                    ASTInlineObject obj = new ASTInlineObject();
                    obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                    obj.imageData(imageData);
                    obj.imageFormat("png");
                    obj.pixelWidth(img.getWidth());
                    obj.pixelHeight(img.getHeight());

                    // 크기: bounds [top, left, bottom, right]
                    double[] bounds = rg.bounds();
                    if (bounds != null && bounds.length >= 4) {
                        obj.boundsX(bounds[1]); // rendered X 좌표 (인라인 정렬용)
                        // SPEC-020: 페이지 상대 좌표 기록 — 같은 셀에 여러 인라인이 있을 때
                        // cellX/cellY fallback 으로 겹치는 문제를 막는다.
                        // rendered bounds는 spread 좌표일 수 있으므로 page bounds를 빼서 HWP PAPER 좌표로 정규화한다.
                        double[] pageRelative = toPageRelativeRenderedBounds(ctx, rg, bounds);
                        double pxPt = pageRelative[0] * ctx.scaleFactor;
                        double pyPt = pageRelative[1] * ctx.scaleFactor;
                        obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pxPt));
                        obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pyPt));
                        double bw = Math.abs(bounds[3] - bounds[1]) * ctx.scaleFactor; // right - left
                        double bh = Math.abs(bounds[2] - bounds[0]) * ctx.scaleFactor; // bottom - top
                        // GraphicLine: top=bottom (height=0) → strokeWeight를 높이로 사용
                        boolean isGraphicLine = false;
                        if (bh < 1.0) {
                            ResolvedPageItem _lineItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
                            if (_lineItem != null && "GraphicLine".equals(_lineItem.type())) {
                                isGraphicLine = true;
                                double sw = _lineItem.strokeWeight();
                                bh = sw > 0 ? sw : 0.5;
                            }
                        }
                        // PNG 비율로 보정 (bounds가 부정확한 경우)
                        // GraphicLine은 PNG 비율 보정 불필요 — strokeWeight가 실제 높이
                        double pngRatio = (double) img.getWidth() / img.getHeight();
                        double boundsRatio = bh > 0 ? bw / bh : Double.MAX_VALUE;
                        // bounds 비율과 PNG 비율이 다르면 PNG 비율 기준으로 보정
                        // bounds의 작은 쪽을 기준으로 맞춤 (원본 크기 초과 방지)
                        // null-type inline TF(번호 라벨 등)는 bounds 원본 크기 유지
                        if (!isNullTypeInline && !isGraphicLine && Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                            if (pngRatio < 1.0) {
                                // 세로가 더 긴 PNG → 높이 유지, 폭 축소
                                bw = bh * pngRatio;
                            } else {
                                // 가로가 더 긴 PNG → 폭 유지, 높이 축소
                                bh = bw / pngRatio;
                            }
                        }
                        obj.width(CoordinateConverter.pointsToHwpunits(bw));
                        obj.height(CoordinateConverter.pointsToHwpunits(bh));
                    } else {
                        double pw = img.getWidth(), ph = img.getHeight();
                        obj.width(CoordinateConverter.pointsToHwpunits(Math.max(pw, ph) * 72.0 / ctx.pngExportDpi));
                        obj.height(CoordinateConverter.pointsToHwpunits(Math.min(pw, ph) * 72.0 / ctx.pngExportDpi));
                    }

                    obj.sourceId("u" + Integer.toHexString(anchoredObjectId));

                    // AnchoredPosition="Anchored" 커스텀 위치 앵커: IDML에서 앵커 문자가 공간을 차지하고
                    // 이미지가 앵커 기준 오프셋에 배치되어 이미지 우측~텍스트 시작 사이에 gap이 생김.
                    // HWPX 인라인 배치 시 동일한 시각 간격을 위해 우측 여백 추가.
                    if (ctx.customAnchoredInlineIds != null && ctx.customAnchoredInlineIds.contains(anchoredObjectId)) {
                        obj.textWrapRight(200L); // 2pt 우측 여백
                    }

                    // 장식 번호 인라인(≤3자 + 큰 폰트/정사각형): 높이를 본문 줄 높이로 제한
                    // 인라인 이미지 높이가 줄간격을 벌리는 것 방지
                    // null-type inline TF는 원본 크기 유지 (자연 크기가 baseline 정렬에 필요)
                    ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                    if (!isNullTypeInline && rtf != null && obj.height() > 1500) { // 15pt 초과
                        // 프레임 가로/세로 비율이 정사각형에 가까우면 높이 제한
                        double[] rtfGb = rtf.geometricBounds();
                        if (rtfGb != null && rtfGb.length >= 4) {
                            double fw = rtfGb[3] - rtfGb[1];
                            double fh = rtfGb[2] - rtfGb[0];
                            if (fw > 0 && fh > 0 && fw / fh >= 0.7 && fw / fh <= 1.4) {
                                long maxH = 1200; // 12pt — 본문 줄 높이 이하
                                long scaledW = obj.width() * maxH / obj.height();
                                obj.height(maxH);
                                obj.width(scaledW);
                            }
                        }
                    }
                    // Group 기반 inline(rtf==null) 과대 크기 방지.
                    // 비율 0.5~4 범위의 shape-only 아이콘/배지: 14pt 상한.
                    // 실제 Image 자식을 포함한 얼굴/사진형 inline visual은 원본 authored bounds를 유지한다.
                    // scribble 외곽선(비율 4.3, 5.8 등)은 page layout 영향으로 제외.
                    if (!isNullTypeInline && rtf == null && obj.height() > 1500
                            && !hasRasterImageSource(ctx, rg)) {
                        double ar = (double) obj.width() / obj.height();
                        if (ar >= 0.5 && ar <= 4.0) {
                            long maxH = 1400;
                            long scaledW = obj.width() * maxH / obj.height();
                            obj.height(maxH);
                            obj.width(scaledW);
                        }
                    }

                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    public static ASTInlineObject loadPlannedInlineTextShellForTextFrame(
            ResolvedBuildContext ctx,
            int textFrameId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        if (ctx.isTextDisposed(textFrameId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
        if (tf == null || !tf.isInline()) return null;
        RenderedGroup shell = findTextHiddenInlineShellForTextFrame(ctx, tf.id());
        if (shell == null) return null;
        if (ctx.visualActionByOwnershipPlan(shell) != VisualAction.PLACE_TEXT_SHELL
                || ctx.placementByOwnershipPlan(shell) != Placement.INLINE) {
            return null;
        }
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(shell.id()));
        return buildInlineShellObject(ctx, shell.id(), anchorItem, tf, shell);
    }

    public static boolean shouldUsePlannedInlinePngWithSeparateHwpxText(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || ctx.resolvedData == null) return false;
        String anchorId = String.valueOf(anchoredObjectId);
        Set<String> descendants = ctx.resolvedData.buildDescendantSet(anchorId, 8);
        boolean hasInlinePngPlan = false;
        boolean hasDescendantHwpxTextPlan = false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            boolean sameAnchor = plan.domId == anchoredObjectId
                    || containsInt(plan.sourceObjectIds, anchoredObjectId);
            if (sameAnchor
                    && plan.placement == Placement.INLINE
                    && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    && plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) {
                hasInlinePngPlan = true;
            }
            boolean descendant = descendants.contains(String.valueOf(plan.domId))
                    || containsAnyStringId(descendants, plan.sourceObjectIds);
            if (descendant && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
                hasDescendantHwpxTextPlan = true;
            }
            if (hasInlinePngPlan && hasDescendantHwpxTextPlan) return true;
        }
        return false;
    }

    public static boolean isEditableTextFrameOfPlannedInlinePngWithSeparateHwpxText(
            ResolvedBuildContext ctx,
            String textFrameId) {
        if (ctx == null || ctx.resolvedData == null || textFrameId == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return false;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || !rg.hasEditableTextHiddenFromPng()) continue;
            if (!containsStringId(rg.editableTextFrameIds(), textFrameId)) continue;
            if (shouldUsePlannedInlinePngWithSeparateHwpxText(ctx, rg.id())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEditableTextFrameOfPlannedInlineTextShell(
            ResolvedBuildContext ctx,
            String textFrameId) {
        if (ctx == null || textFrameId == null) {
            return false;
        }
        if (ctx.ownershipPlans == null) return false;
        int tfDomId;
        try {
            tfDomId = Integer.parseInt(textFrameId);
        } catch (NumberFormatException e) {
            return false;
        }
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.domId == tfDomId) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (containsInt(plan.ownedTextFrameIds, tfDomId)
                    || containsInt(plan.sourceObjectIds, tfDomId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRasterImageSource(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (isRasterImageItem(ctx, rg.id())) return true;
        int[] sourceIds = rg.sourceObjectIds();
        if (sourceIds == null) return false;
        for (int sourceId : sourceIds) {
            if (isRasterImageItem(ctx, sourceId)) return true;
        }
        return false;
    }

    private static boolean isRasterImageItem(ResolvedBuildContext ctx, int domId) {
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(domId));
        return item != null && "Image".equals(item.type());
    }

    public static ASTInlineObject loadCompleteSimpleButtonLabelInlineObject(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.basePath == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        RenderedGroup completeRender = findCompleteSimpleButtonLabelRender(ctx, anchoredObjectId);
        if (completeRender == null || completeRender.file() == null) return null;
        ResolvedTextFrame childTf = findSimpleButtonLabelChildTextFrame(ctx, anchoredObjectId);
        RenderedGroup inlineRender = findInlineGraphicOnlyRender(ctx, anchoredObjectId);
        RenderedGroup placementRender = inlineRender != null ? inlineRender : completeRender;
        if (ctx.hasOwnershipPlan(completeRender)) {
            if (!ctx.shouldPlaceInlinePngByOwnershipPlan(completeRender)) {
                return null;
            }
        } else if (!shouldPlaceCompleteSimpleButtonLabelInline(ctx, childTf, placementRender)) {
            return null;
        }
        File pngFile = new File(ctx.basePath, completeRender.file());
        if (!pngFile.exists() || !pngFile.isFile()) return null;
        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            BadgeImageData badgeImage = trimSimpleButtonBadgeImage(imageData, img);
            imageData = badgeImage.imageData;
            img = badgeImage.image;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.imageData(imageData);
            obj.imageFormat("png");
            obj.pixelWidth(img.getWidth());
            obj.pixelHeight(img.getHeight());
            obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
            obj.keepInline(true);
            obj.verticalJustification("CenterAlign");

            double[] bounds = placementRender.bounds();
            if (bounds != null && bounds.length >= 4) {
                obj.boundsX(bounds[1]);
                double[] pageRelative = toPageRelativeRenderedBounds(ctx, placementRender, bounds);
                obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pageRelative[0] * ctx.scaleFactor));
                obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pageRelative[1] * ctx.scaleFactor));
                double bw = Math.abs(bounds[3] - bounds[1]) * ctx.scaleFactor;
                double bh = Math.abs(bounds[2] - bounds[0]) * ctx.scaleFactor;
                bw *= badgeImage.widthScale;
                bh *= badgeImage.heightScale;
                if (bw <= 0 || bh <= 0) return null;
                obj.width(CoordinateConverter.pointsToHwpunits(bw));
                obj.height(CoordinateConverter.pointsToHwpunits(bh));
            } else {
                obj.width(CoordinateConverter.pointsToHwpunits(img.getWidth() * 72.0 / ctx.pngExportDpi));
                obj.height(CoordinateConverter.pointsToHwpunits(img.getHeight() * 72.0 / ctx.pngExportDpi));
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static RenderedGroup findInlineGraphicOnlyRender(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId) continue;
            if (!"inline_graphic_only".equals(rg.reason())) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (rg.parentStoryId() == null || rg.parentStoryId().isEmpty()) continue;
            return rg;
        }
        return null;
    }

    private static boolean shouldPlaceCompleteSimpleButtonLabelInline(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf,
            RenderedGroup completeRender) {
        if (ctx == null || ctx.resolvedData == null || completeRender == null) {
            return false;
        }
        if (childTf == null) return isCompleteLabelAnchoredInTextStory(ctx, completeRender);
        double[] labelBounds = completeRender.bounds();
        if (labelBounds == null || labelBounds.length < 4) return false;
        double labelTop = labelBounds[0];
        double labelLeft = labelBounds[1];
        double labelBottom = labelBounds[2];
        double labelRight = labelBounds[3];
        double labelHeight = Math.max(0.1, labelBottom - labelTop);
        double labelCenterY = (labelTop + labelBottom) / 2.0;
        if (isCompleteLabelAnchoredInTextStory(ctx, completeRender)) {
            return true;
        }
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.id() == null || tf.id().equals(childTf.id())) continue;
            if (tf.pageIndex() != childTf.pageIndex()) continue;
            if (tf.sourceHidden()) continue;
            if (ctx.resolvedData.isSimpleButtonLabelTextFrame(tf.id())) continue;
            String text = tf.frameVisibleText();
            if (visibleTextLength(text) < 2) continue;
            double[] b = tf.geometricBounds();
            if (b == null || b.length < 4) continue;
            double centerY = (b[0] + b[2]) / 2.0;
            double verticalDelta = Math.abs(centerY - labelCenterY);
            if (verticalDelta > Math.max(8.0, labelHeight * 1.5)) continue;
            double rightGap = b[1] - labelRight;
            if (rightGap >= -1.0 && rightGap <= 18.0) return true;
            double leftGap = labelLeft - b[3];
            if (leftGap >= -1.0 && leftGap <= 8.0) return true;
        }
        return false;
    }

    private static boolean isCompleteLabelAnchoredInTextStory(
            ResolvedBuildContext ctx,
            RenderedGroup inlineRender) {
        if (ctx == null || ctx.resolvedData == null || inlineRender == null) return false;
        String storyId = inlineRender.parentStoryId();
        if (storyId == null || storyId.isEmpty()) return false;
        ResolvedStory story = ctx.resolvedData.getStory(storyId);
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph para : story.paragraphs()) {
            if (para == null || para.runs() == null) continue;
            for (ResolvedRun run : para.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                if (visibleTextLength(run.text()) > 0) return true;
            }
        }
        return false;
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

    private static BadgeImageData trimSimpleButtonBadgeImage(byte[] originalData, BufferedImage originalImage)
            throws java.io.IOException {
        if (originalImage == null || originalImage.getWidth() <= 0 || originalImage.getHeight() <= 0) {
            return new BadgeImageData(originalData, originalImage, 1.0, 1.0);
        }
        int[] alpha = alphaBounds(originalImage);
        if (alpha == null) {
            return new BadgeImageData(originalData, originalImage, 1.0, 1.0);
        }
        int x = alpha[0];
        int y = alpha[1];
        int w = alpha[2];
        int h = alpha[3];
        if (x <= 0 && y <= 0 && w >= originalImage.getWidth() && h >= originalImage.getHeight()) {
            return new BadgeImageData(originalData, originalImage, 1.0, 1.0);
        }
        if (w <= 1 || h <= 1) {
            return new BadgeImageData(originalData, originalImage, 1.0, 1.0);
        }

        BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cropped.createGraphics();
        try {
            g.drawImage(originalImage, 0, 0, w, h, x, y, x + w, y + h, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(cropped, "png", out);
        double widthScale = w / (double) originalImage.getWidth();
        double heightScale = h / (double) originalImage.getHeight();
        return new BadgeImageData(out.toByteArray(), cropped, widthScale, heightScale);
    }

    private static int[] alphaBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                if (alpha <= 8) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) return null;
        return new int[] { minX, minY, maxX - minX + 1, maxY - minY + 1 };
    }

    private static final class BadgeImageData {
        final byte[] imageData;
        final BufferedImage image;
        final double widthScale;
        final double heightScale;

        BadgeImageData(byte[] imageData, BufferedImage image, double widthScale, double heightScale) {
            this.imageData = imageData;
            this.image = image;
            this.widthScale = widthScale;
            this.heightScale = heightScale;
        }
    }

    private static RenderedGroup findCompleteSimpleButtonLabelRender(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        String anchorId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame childTf = findSimpleButtonLabelChildTextFrame(ctx, anchoredObjectId);
        String childTfId = childTf != null ? childTf.id() : null;
        RenderedGroup fallback = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            boolean planCompleteInline = ctx.isCompleteInlinePngByOwnershipPlan(rg);
            boolean legacyComplete = isCompletePngSimpleButtonLabel(ctx, rg);
            if (!planCompleteInline && !legacyComplete) continue;
            if (rg.id() == anchoredObjectId) {
                if (planCompleteInline) return rg;
                if (fallback == null) fallback = rg;
                continue;
            }
            String[] editableIds = rg.editableTextFrameIds();
            if (editableIds == null) continue;
            for (String editableId : editableIds) {
                if (anchorId.equals(editableId)
                        || (childTfId != null && childTfId.equals(editableId))) {
                    if (planCompleteInline) return rg;
                    if (fallback == null) fallback = rg;
                }
            }
        }
        return fallback;
    }

    /**
     * inline_object PNG로 배치된 Group의 직속 editable 자식 TF를
     * INLINE_TEXT_FRAME으로 변환한다.
     *
     * PNG에는 editable TF 내용이 포함되지 않으므로 별도로 배치해야 한다.
     */
    private static RenderedGroup findInlineBadgeRender(ResolvedBuildContext ctx, int anchoredId) {
        if (ctx.resolvedData.allRenderedFloatingItems() == null) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredId || rg.file() == null) continue;
            String r = rg.reason();
            if ("inline_badge".equals(r) || "inline_badge_baked".equals(r)) return rg;
        }
        return null;
    }

    /**
     * Stage 2b: 플로팅(page_object) 통합 배지. reason="inline_badge"인 비-인라인 배지를
     * 절대 좌표 ASTTextFrameBlock(그래픽=imageFill + 편집 텍스트를 그 위에)로 만든다.
     * 좌표/크기(HWPUNIT)는 호출측(Phase 6)이 계산해 넘긴다. inline_badge가 아니거나 편집
     * 텍스트가 없으면 null → 호출측이 기존 figure 경로로 폴백.
     */
    public static ASTTextFrameBlock buildFloatingBadge(
            ResolvedBuildContext ctx, RenderedGroup rg, long xHwp, long yHwp, long wHwp, long hHwp) {
        if (ctx == null || rg == null || !"inline_badge".equals(rg.reason())) return null;
        return buildFloatingTextShell(ctx, rg, xHwp, yHwp, wHwp, hHwp, 0);
    }

    public static ASTTextFrameBlock buildFloatingTextShell(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            long xHwp,
            long yHwp,
            long wHwp,
            long hHwp,
            int zOrder) {
        java.util.List<ASTTextFrameBlock> blocks =
                buildFloatingTextShellBlocks(ctx, rg, xHwp, yHwp, wHwp, hHwp, zOrder);
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    /** inline_badge 셸을 ASTTextFrameBlock으로 만든다. */
    public static java.util.List<ASTTextFrameBlock> buildFloatingTextShellBlocks(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            long xHwp,
            long yHwp,
            long wHwp,
            long hHwp,
            int zOrder) {
        java.util.List<ASTTextFrameBlock> out = new ArrayList<>();
        if (ctx == null || rg == null) return out;
        boolean inlineBadge = "inline_badge".equals(rg.reason());
        if (!inlineBadge) return out;
        if (wHwp <= 0 || hHwp <= 0) return out;
        java.util.List<ResolvedTextFrame> tfs = badgeTextFramesSortedByReading(ctx, rg);
        if (tfs.isEmpty()) return out;
        byte[] png = loadBadgePngFlattenedOntoWhite(ctx, rg);
        if (png == null || png.length == 0) return out;

        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.x(xHwp);
        block.y(yHwp);
        block.width(wHwp);
        block.height(hHwp);
        block.zOrder(zOrder);
        block.sourceId("page_obj_" + rg.id());
        block.imageFillData(png);
        block.nativeGraphicsAllowed(true);
        block.forceImageFill(true);
        block.inlineToFloating(true);
        block.verticalJustification("CenterAlign");
        boolean any = false;
        for (ResolvedTextFrame tf : tfs) {
            String cleaned = cleanedLabelText(tf);
            if (cleaned == null) continue;
            ASTParagraph para = new ASTParagraph();
            para.alignment("CENTER");
            ASTTextRun run = new ASTTextRun();
            run.text(cleaned);
            applyFirstRunStyle(ctx, tf, run);
            para.addItem(run);
            block.addParagraph(para);
            markTextBlockPlaced(ctx, tf);
            any = true;
        }
        if (any) out.add(block);
        return out;
    }

    private static String cleanedLabelText(ResolvedTextFrame tf) {
        String vt = tf != null ? tf.frameVisibleText() : null;
        if (vt == null) return null;
        String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static void markTextBlockPlaced(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        try {
            ctx.setTextDisposition(Integer.parseInt(tf.id()), FrameDisposition.TEXT_BLOCK_PLACED);
        } catch (Exception ignored) {
            // Non-numeric DOM ids cannot be recorded in the legacy disposition map.
        }
    }


    /** rg.editableTextFrameIds()를 ResolvedTextFrame으로 매핑 후 Y(상단)→X(좌측) 읽기 순서로 정렬. */
    private static java.util.List<ResolvedTextFrame> badgeTextFramesSortedByReading(
            ResolvedBuildContext ctx, RenderedGroup rg) {
        java.util.List<ResolvedTextFrame> out = new ArrayList<>();
        String[] ids = rg.editableTextFrameIds();
        if (ids == null) return out;
        for (String id : ids) {
            if (id == null) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(id);
            if (tf != null && tf.frameVisibleText() != null) out.add(tf);
        }
        java.util.Collections.sort(out, new java.util.Comparator<ResolvedTextFrame>() {
            public int compare(ResolvedTextFrame a, ResolvedTextFrame b2) {
                double[] ga = a.geometricBounds(), gb = b2.geometricBounds();
                double ay = ga != null ? ga[0] : 0, by = gb != null ? gb[0] : 0;
                if (Math.abs(ay - by) > 1.0) return ay < by ? -1 : 1;
                double ax = ga != null ? ga[1] : 0, bx = gb != null ? gb[1] : 0;
                return ax < bx ? -1 : (ax > bx ? 1 : 0);
            }
        });
        return out;
    }

    private static void applyFirstRunStyle(ResolvedBuildContext ctx, ResolvedTextFrame tf, ASTTextRun run) {
        ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (story == null || story.paragraphs().isEmpty()) return;
        ResolvedRun rr = firstVisibleResolvedRun(story);
        if (rr == null) return;
        applyResolvedRunStyle(ctx, rr, run);
    }

    // 배지 그래픽 PNG를 흰 배경에 합성해 로드. HWP imgBrush는 알파를 검정으로 칠하므로(투명→검정),
    // 흰 페이지 위 배지는 흰 배경 합성이 올바르다(투명 영역=페이지색).
    private static byte[] loadBadgePngFlattenedOntoWhite(ResolvedBuildContext ctx, RenderedGroup rg) {
        byte[] png = loadRenderedPngBytes(ctx, rg);
        if (png == null || png.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img != null) return flattenOntoWhite(img);
        } catch (Exception ignored) {}
        return png;
    }

    public static java.util.List<ASTInlineObject> buildChildEditableBoxes(ResolvedBuildContext ctx, int groupId) {
        if (shouldUsePlannedInlinePngWithSeparateHwpxText(ctx, groupId)) {
            return java.util.Collections.emptyList();
        }
        if (InlineSemanticLabelPolicy.isSemanticMultiTextInlineGroup(ctx.resolvedData, groupId)
                && !hasPlannedFloatingShellForSemanticInlineGroup(ctx, groupId)) {
            return java.util.Collections.emptyList();
        }
        java.util.List<ASTInlineObject> result = new ArrayList<>();
        String groupIdStr = String.valueOf(groupId);
        java.util.List<ResolvedTextFrame> editableChildren = new ArrayList<>();
        java.util.Set<String> descendantIds = ctx.resolvedData.buildDescendantSet(groupIdStr, 8);
        boolean semanticGroupWithPlannedShell = InlineSemanticLabelPolicy.isSemanticMultiTextInlineGroup(
                ctx.resolvedData, groupId)
                && hasPlannedFloatingShellForSemanticInlineGroup(ctx, groupId);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            boolean directChild = groupIdStr.equals(pi.parentId());
            if (semanticGroupWithPlannedShell) {
                if (directChild || !descendantIds.contains(tf.id())) continue;
                if (!hasTextHiddenInlineShellForTextFrame(ctx, tf.id())) continue;
            } else if (!directChild) {
                continue;
            }
            if (!tf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) continue;
            int tfDomId;
            try { tfDomId = Integer.parseInt(tf.id()); } catch (NumberFormatException e) { continue; }
            if (ctx.isTextDisposed(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED)) continue;
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            if (hasObjectReplacementText(vt)) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            double[] gb = textFrameBoundsPoints(ctx, tf);
            if (!validBounds(gb)) continue;
            editableChildren.add(tf);
        }

        for (ResolvedTextFrame tf : editableChildren) {
            RenderedGroup textFrameShell = findTextHiddenInlineShellForTextFrame(ctx, tf.id());
            RenderedGroup inlineBackdrop = textFrameShell != null
                    ? textFrameShell
                    : (editableChildren.size() == 1 ? findInlineEditableGroupBackdrop(ctx, groupId) : null);
            byte[] inlineBackdropData = loadRenderedPngBytes(ctx, inlineBackdrop);
            double[] inlineBackdropBounds = inlineBackdrop != null ? inlineBackdrop.bounds() : null;
            String vt = tf.frameVisibleText();
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            double[] gb = textFrameBoundsPoints(ctx, tf);
            if (!validBounds(gb)) continue;
            double w = Math.abs(gb[3] - gb[1]);
            double h = Math.abs(gb[2] - gb[0]);
            if (w <= 0 || h <= 0) continue;
            int tfDomId;
            try { tfDomId = Integer.parseInt(tf.id()); } catch (NumberFormatException e) { continue; }

            double boxW = w;
            double boxH = h;
            double marginTop = 0;
            double marginLeft = 0;
            double marginBottom = 0;
            double marginRight = 0;
            double[] backdropBoundsPt = renderedBoundsPoints(ctx, inlineBackdropBounds);
            if (inlineBackdropData != null && validBounds(backdropBoundsPt)) {
                boxW = Math.abs(backdropBoundsPt[3] - backdropBoundsPt[1]);
                boxH = Math.abs(backdropBoundsPt[2] - backdropBoundsPt[0]);
                if (boxW > 0 && boxH > 0) {
                    marginTop = Math.max(0, gb[0] - backdropBoundsPt[0]);
                    marginLeft = Math.max(0, gb[1] - backdropBoundsPt[1]);
                    marginBottom = Math.max(0, backdropBoundsPt[2] - gb[2]);
                    marginRight = Math.max(0, backdropBoundsPt[3] - gb[3]);
                } else {
                    boxW = w;
                    boxH = h;
                }
            }

            ASTInlineObject box = new ASTInlineObject();
            box.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            box.width(CoordinateConverter.pointsToHwpunits(boxW));
            box.height(CoordinateConverter.pointsToHwpunits(boxH));
            box.sourceId("child_u" + Integer.toHexString(tfDomId));
            box.noAutoLineWrap(shouldUseNoAutoLineWrap(tf, inlineBackdropData != null));
            if (inlineBackdropData != null) {
                box.imageFillData(inlineBackdropData);
                box.nativeGraphicsAllowed(true);
                box.textMarginTop(CoordinateConverter.pointsToHwpunits(marginTop));
                box.textMarginLeft(CoordinateConverter.pointsToHwpunits(marginLeft));
                box.textMarginBottom(CoordinateConverter.pointsToHwpunits(marginBottom));
                box.textMarginRight(CoordinateConverter.pointsToHwpunits(marginRight));
            }

            ASTParagraph paraInner = new ASTParagraph();
            paraInner.alignment("CENTER");
            ASTTextRun textRunInner = new ASTTextRun();
            textRunInner.text(cleaned);
            ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
            ResolvedRun rr = firstVisibleResolvedRun(story);
            if (rr != null) {
                applyResolvedRunStyle(ctx, rr, textRunInner);
            }
            paraInner.addItem(textRunInner);
            box.addParagraph(paraInner);
            box.verticalJustification("CenterAlign");
            result.add(box);
        }
        return result;
    }

    private static ResolvedRun firstVisibleResolvedRun(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return null;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            ResolvedRun run = firstVisibleResolvedRun(paragraph);
            if (run != null) return run;
        }
        return null;
    }

    private static ResolvedRun firstVisibleResolvedRun(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return null;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.text() == null) continue;
            String visible = run.text()
                    .replace("\uFFFC", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim();
            if (!visible.isEmpty()) return run;
        }
        return null;
    }

    private static void applyResolvedRunStyle(ResolvedBuildContext ctx, ResolvedRun rr, ASTTextRun run) {
        if (rr == null || run == null) return;
        if (rr.fontFamily() != null) run.fontFamily(rr.fontFamily());
        if (rr.fontStyle() != null) run.fontStyle(rr.fontStyle());
        if (rr.fontSize() != null && rr.fontSize() > 0) {
            run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
        }
        if (rr.fillColor() != null) run.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
        if (rr.horizontalScale() != null && rr.horizontalScale() != 0 && rr.horizontalScale() != 100) {
            run.horizontalScale((short) rr.horizontalScale().doubleValue());
        }
        if (rr.tracking() != null && rr.tracking() != 0) {
            run.letterSpacing((short) Math.round(rr.tracking() / 10.0));
        }
        if (Boolean.TRUE.equals(rr.underline())) run.underline(true);
        if (Boolean.TRUE.equals(rr.strikeThru())) run.strikeThrough(true);
    }


    private static boolean hasTextHiddenInlineShellForTextFrame(ResolvedBuildContext ctx, String textFrameId) {
        return findTextHiddenInlineShellForTextFrame(ctx, textFrameId) != null;
    }

    private static RenderedGroup findTextHiddenInlineShellForTextFrame(ResolvedBuildContext ctx, String textFrameId) {
        if (ctx == null || ctx.resolvedData == null || textFrameId == null) return null;
        java.util.List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return null;
        int tfDomId;
        try {
            tfDomId = Integer.parseInt(textFrameId);
        } catch (NumberFormatException e) {
            tfDomId = -1;
        }
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.file() == null) continue;
            if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (tfDomId >= 0
                    && plan != null
                    && plan.placement == Placement.INLINE
                    && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && containsInt(plan.ownedTextFrameIds, tfDomId)) {
                return rg;
            }
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            String[] editableIds = rg.editableTextFrameIds();
            if (editableIds == null) continue;
            for (String editableId : editableIds) {
                if (textFrameId.equals(editableId)) return rg;
            }
        }
        return null;
    }

    public static boolean hasPlannedFloatingShellForSemanticInlineGroup(
            ResolvedBuildContext ctx,
            int groupDomId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        java.util.List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return false;
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.id() != groupDomId) continue;
            if (!"page_object".equals(rg.type()) && !"page_object".equals(rg.itemType())) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            if (ctx.hasOwnershipPlan(rg)) {
                return ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg);
            }
            String reason = rg.reason();
            return reason != null && reason.contains("text_hidden");
        }
        return false;
    }

    private static RenderedGroup findInlineEditableGroupBackdrop(ResolvedBuildContext ctx, int groupId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != groupId || rg.file() == null) continue;
            if (!"inline_object".equals(rg.itemType())) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan != null
                    && plan.placement == Placement.INLINE
                    && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && plan.ownedTextFrameIds != null
                    && plan.ownedTextFrameIds.length > 0) {
                return rg;
            }
            if (!rg.hasEditableTextHiddenFromPng()) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            return rg;
        }
        return null;
    }

    private static byte[] loadRenderedPngBytes(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.basePath == null || rg == null || rg.file() == null) return null;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists() || !pngFile.isFile()) return null;
            return java.nio.file.Files.readAllBytes(pngFile.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    /** IDML story 의 자기 텍스트만 추출한다. ORC anchor 자식 텍스트는 합치지 않는다. */
    private static String extractTextRecursive(ResolvedBuildContext ctx, IDMLStory idmlStory, int depth) {
        if (idmlStory == null) return "";
        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph p : idmlStory.paragraphs()) {
            for (IDMLCharacterRun r : p.characterRuns()) {
                String content = r.content();
                if (content == null) content = "";
                String[] parts = content.split("￼", -1);
                for (String part : parts) {
                    sb.append(part);
                }
            }
        }
        return sb.toString();
    }

    public static boolean hasPlannedFloatingHwpxTextDescendant(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || anchoredObjectId < 0) return false;
        if (ctx.ownershipPlanPlacesFloatingHwpxText(anchoredObjectId)) return true;
        String anchorId = String.valueOf(anchoredObjectId);
        java.util.Set<String> descendants = ctx.resolvedData.buildDescendantSet(anchorId, 8);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            if (!descendants.contains(tf.id())) continue;
            int tfDomId;
            try { tfDomId = Integer.parseInt(tf.id()); }
            catch (NumberFormatException e) { continue; }
            if (ctx.ownershipPlanPlacesFloatingHwpxText(tfDomId)) return true;
        }
        return false;
    }

    private static boolean hasPlannedInlineHwpxTextDescendant(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || anchoredObjectId < 0) return false;
        if (ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) return true;
        String anchorId = String.valueOf(anchoredObjectId);
        java.util.Set<String> descendants = ctx.resolvedData.buildDescendantSet(anchorId, 8);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            if (!descendants.contains(tf.id())) continue;
            int tfDomId;
            try { tfDomId = Integer.parseInt(tf.id()); }
            catch (NumberFormatException e) { continue; }
            if (ctx.ownershipPlanPlacesInlineHwpxText(tfDomId)) return true;
        }
        return false;
    }


    /** resolved.json에 없는 cornerRadius를 IDML spread의 vectorShapes에서 조회. */
    private static double lookupIdmlShapeCornerRadius(ResolvedBuildContext ctx, String decimalId) {
        if (decimalId == null || ctx == null || ctx.resolvedData == null) return 0;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(decimalId);
        if (item != null && item.cornerRadius() > 0) return item.cornerRadius();
        return 0;
    }

    private static double[] toPageRelativeRenderedBounds(
            ResolvedBuildContext ctx, RenderedGroup rg, double[] bounds) {
        if (bounds == null || bounds.length < 4) return new double[]{0.0, 0.0};
        double x = bounds[1];
        double y = bounds[0];
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null || rg == null) {
            return new double[]{x, y};
        }
        for (ResolvedPage page : ctx.resolvedData.pages()) {
            if (page == null || page.index() != rg.pageIndex()) continue;
            double[] pb = page.bounds();
            if (pb == null || pb.length < 4) break;
            double pageLeft = pb[1] / ctx.scaleFactor;
            double pageTop = pb[0] / ctx.scaleFactor;
            if (bounds[3] >= pageLeft) x = bounds[1] - pageLeft;
            if (bounds[2] >= pageTop) y = bounds[0] - pageTop;
            break;
        }
        return new double[]{x, y};
    }

    private static boolean isShiftedCompleteLabelInlinePair(ResolvedBuildContext ctx, RenderedGroup inline) {
        RenderedGroup pageObject = findCompleteLabelPageObjectPair(ctx, inline);
        if (pageObject == null || inline == null || inline.bounds() == null || pageObject.bounds() == null) {
            return false;
        }
        double[] ib = inline.bounds();
        double[] pb = pageObject.bounds();
        if (ib.length < 4 || pb.length < 4) return false;
        double dx = Math.abs(ib[1] - pb[1]);
        double dy = Math.abs(ib[0] - pb[0]);
        if (dx <= 1.0 && dy <= 1.0) return false;
        double pageWidth = localPageWidth(ctx, pageObject.pageIndex());
        double pageHeight = localPageHeight(ctx, pageObject.pageIndex());
        return (pageWidth > 0.0 && Math.abs(dx - pageWidth) <= 2.0)
                || (pageHeight > 0.0 && Math.abs(dy - pageHeight) <= 2.0);
    }

    private static RenderedGroup findCompleteLabelPageObjectPair(ResolvedBuildContext ctx, RenderedGroup inline) {
        if (ctx == null || ctx.resolvedData == null || inline == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != inline.id()) continue;
            if (!"page_object".equals(rg.itemType())) continue;
            if (isCompletePngSimpleButtonLabel(ctx, rg)) return rg;
        }
        return null;
    }

    private static double[] textFrameSizePoints(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        double[] boundsPt = textFrameBoundsPoints(ctx, tf);
        if (!validBounds(boundsPt)) return null;
        return new double[]{
                Math.abs(boundsPt[3] - boundsPt[1]),
                Math.abs(boundsPt[2] - boundsPt[0])
        };
    }

    /**
     * Inline/nested TextFrame의 authored bounds는 resolved page-relative bounds를 우선한다.
     * resolved geometricBounds는 normalizeToPoints를 거친 값이지만, 일부 인라인 TF는
     * 원본 측정 단위로 들어와 HWPX rect가 scaleFactor만큼 작아질 수 있다.
     */
    private static double[] textFrameBoundsPoints(ResolvedBuildContext ctx, ResolvedTextFrame tf) {
        if (tf == null) return null;
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        double[] pageRelative = tf.pageRelativeBounds();
        if (validBounds(pageRelative)) {
            return new double[]{
                    pageRelative[0] * scale,
                    pageRelative[1] * scale,
                    pageRelative[2] * scale,
                    pageRelative[3] * scale
            };
        }
        double[] gb = tf.geometricBounds();
        if (validBounds(gb)) {
            return new double[]{gb[0], gb[1], gb[2], gb[3]};
        }
        return null;
    }

    private static double[] renderedBoundsPoints(ResolvedBuildContext ctx, double[] bounds) {
        if (!validBounds(bounds)) return null;
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        return new double[]{
                bounds[0] * scale,
                bounds[1] * scale,
                bounds[2] * scale,
                bounds[3] * scale
        };
    }

    private static boolean validBounds(double[] bounds) {
        return bounds != null && bounds.length >= 4
                && Double.isFinite(bounds[0])
                && Double.isFinite(bounds[1])
                && Double.isFinite(bounds[2])
                && Double.isFinite(bounds[3])
                && bounds[2] > bounds[0]
                && bounds[3] > bounds[1];
    }

    private static double localPageWidth(ResolvedBuildContext ctx, int pageIndex) {
        double[] bounds = pageBounds(ctx, pageIndex);
        if (bounds == null) return 0.0;
        double width = bounds[3] - bounds[1];
        return ctx.scaleFactor != 0.0 ? width / ctx.scaleFactor : width;
    }

    private static double localPageHeight(ResolvedBuildContext ctx, int pageIndex) {
        double[] bounds = pageBounds(ctx, pageIndex);
        if (bounds == null) return 0.0;
        double height = bounds[2] - bounds[0];
        return ctx.scaleFactor != 0.0 ? height / ctx.scaleFactor : height;
    }

    private static double[] pageBounds(ResolvedBuildContext ctx, int pageIndex) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return null;
        if (pageIndex < 0 || pageIndex >= ctx.resolvedData.pages().size()) return null;
        ResolvedPage page = ctx.resolvedData.pages().get(pageIndex);
        if (page == null || page.bounds() == null || page.bounds().length < 4) return null;
        return page.bounds();
    }

    private static boolean containsConceptDiagramTextFrame(ResolvedBuildContext ctx, String anchorId) {
        if (ctx == null || ctx.resolvedData == null || anchorId == null) return false;
        if (isConceptDiagramTextFrame(ctx, anchorId)) return true;
        if (ctx.conceptDiagramTextFrameIds == null || ctx.conceptDiagramTextFrameIds.isEmpty()) return false;
        Set<String> descendants = ctx.resolvedData.buildDescendantSet(anchorId, 5);
        for (String tfId : ctx.conceptDiagramTextFrameIds) {
            if (tfId == null) continue;
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tfId);
            if (pi == null) continue;
            String parentId = pi.parentId();
            if (anchorId.equals(parentId) || descendants.contains(tfId)
                    || (parentId != null && descendants.contains(parentId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConceptDiagramTextFrame(ResolvedBuildContext ctx, String tfId) {
        return ctx != null
                && ctx.conceptDiagramTextFrameIds != null
                && tfId != null
                && ctx.conceptDiagramTextFrameIds.contains(tfId);
    }

    private static boolean shouldUseNoAutoLineWrap(ResolvedTextFrame tf) {
        return shouldUseNoAutoLineWrap(tf, false);
    }

    private static boolean shouldUseNoAutoLineWrap(ResolvedTextFrame tf, boolean hasVisualShell) {
        if (tf == null) return false;
        if (isFixedSingleLineTitleOrLabel(tf, hasVisualShell)) return true;
        if (tf.composedLines() == null || tf.composedLines().size() < 2) return false;
        String visibleText = tf.frameVisibleText();
        if (!hasVisibleTextExcludingObjectControls(visibleText)) return false;

        Set<Integer> paragraphIndices = new HashSet<>();
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null) return false;
            int paraIndex = line.paraIndex();
            if (paraIndex < 0 || !paragraphIndices.add(paraIndex)) {
                return false;
            }
        }
        return paragraphIndices.size() == tf.composedLines().size();
    }

    private static boolean isFixedSingleLineTitleOrLabel(ResolvedTextFrame tf, boolean hasVisualShell) {
        if (!isSourceSingleLineTextFrame(tf)) return false;
        if (hasVisualShell) return true;
        if (startsWithObjectReplacement(tf.frameVisibleText())) return true;
        return hasOwnVisualStyle(tf);
    }

    private static boolean startsWithObjectReplacement(String visibleText) {
        if (visibleText == null) return false;
        for (int i = 0; i < visibleText.length(); i++) {
            char ch = visibleText.charAt(i);
            if (ch == '\uFFFC') return true;
            if (!Character.isWhitespace(ch) && ch != '\u200A') return false;
        }
        return false;
    }

    private static boolean hasOwnVisualStyle(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (!isNoneColor(tf.fillColor())) return true;
        return !isNoneColor(tf.strokeColor()) && tf.strokeWeight() > 0;
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
