package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
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

    /** 원본에서 한 줄인 라벨/제목형 짧은 문장은 HWP 폰트폭 차이로 두 줄이 되지 않도록 SQUEEZE를 적용한다. */
    private static final int SHORT_SINGLE_LINE_NO_WRAP_CHARS = 32;
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
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
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
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf));
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
            ASTTextRun textRun = new ASTTextRun();
            textRun.text(jamoText);
            ResolvedStory story = childTf.storyId() != null ? ctx.resolvedData.getStory(childTf.storyId()) : null;
            if (story != null && !story.paragraphs().isEmpty()) {
                ResolvedParagraph rp = story.paragraphs().get(0);
                if (rp.runs() != null && !rp.runs().isEmpty()) {
                    ResolvedRun rr = rp.runs().get(0);
                    if (rr.fontFamily() != null) textRun.fontFamily(rr.fontFamily());
                    if (rr.fontStyle() != null) textRun.fontStyle(rr.fontStyle());
                    if (rr.fontSize() != null && rr.fontSize() > 0) {
                        textRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                    }
                    if (rr.fillColor() != null) textRun.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
                }
            }
            paraInner.addItem(textRun);
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
                String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                if (cleaned.isEmpty()) continue;

                ASTInlineObject obj = new ASTInlineObject();
                obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
                obj.width(CoordinateConverter.pointsToHwpunits(rw));
                obj.height(CoordinateConverter.pointsToHwpunits(rh));
                obj.sourceId(ParagraphTextHelpers.domIdToSourceId(nestedTf.id()));
                obj.noAutoLineWrap(shouldUseNoAutoLineWrap(nestedTf));
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
                ASTTextRun textRun = new ASTTextRun();
                textRun.text(cleaned);
                ResolvedStory story = nestedTf.storyId() != null ? ctx.resolvedData.getStory(nestedTf.storyId()) : null;
                if (story != null && !story.paragraphs().isEmpty()) {
                    ResolvedParagraph rp = story.paragraphs().get(0);
                    if (rp.runs() != null && !rp.runs().isEmpty()) {
                        ResolvedRun rr = rp.runs().get(0);
                        if (rr.fontFamily() != null) textRun.fontFamily(rr.fontFamily());
                        if (rr.fontStyle() != null) textRun.fontStyle(rr.fontStyle());
                        if (rr.fontSize() != null && rr.fontSize() > 0) {
                            textRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                        }
                        if (rr.fillColor() != null) textRun.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
                    }
                }
                paraInner.addItem(textRun);
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
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(tf));
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
            ASTTextRun textRun = new ASTTextRun();
            textRun.text(cleaned);
            ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
            if (story != null && !story.paragraphs().isEmpty()) {
                ResolvedParagraph rp = story.paragraphs().get(0);
                if (rp.runs() != null && !rp.runs().isEmpty()) {
                    ResolvedRun rr = rp.runs().get(0);
                    if (rr.fontFamily() != null) textRun.fontFamily(rr.fontFamily());
                    if (rr.fontStyle() != null) textRun.fontStyle(rr.fontStyle());
                    if (rr.fontSize() != null && rr.fontSize() > 0) {
                        textRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                    }
                    if (rr.fillColor() != null) textRun.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
                }
            }
            paraInner.addItem(textRun);
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
        if (containsConceptDiagramTextFrame(ctx, anchorId)) return null;
        // AboveLine 앵커는 floating badge → Phase 7 이 처리, 인라인 변환 불가
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
                ctx.inlineEditableLabelShellIds.add(anchoredObjectId);
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
        obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf));

        // 배경 fill 색 (없으면 stroke 색 적용)
        String fillName = bgShape.fillColorName();
        String fillHex = ctx.resolvedData.resolveColorHex(fillName);
        if (fillHex != null) {
            obj.fillColor(fillHex);
            int tint = bgShape.fillTint() > 0 ? (int) bgShape.fillTint() : 100;
            obj.fillTint(tint);
        } else {
            String strokeName = bgShape.strokeColorName();
            String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
            if (strokeHex != null) {
                obj.strokeColor(strokeHex);
                double sw = bgShape.strokeWeight();
                if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                obj.strokeWeight(Math.max(sw, 0.5));
            }
        }

        if (hasOval) {
            obj.cornerRadius(hForInline / 2.0);
        } else if (bgShape.cornerRadius() > 0) {
            obj.cornerRadius(bgShape.cornerRadius());
        } else {
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
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf));
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
                if ("inline_object".equals(rg.itemType())) {
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
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf));
            obj.verticalJustification("CenterAlign");
            if (isCompletePngSimpleButtonLabel(ctx, matched)) {
                ctx.inlineCompleteSimpleButtonLabelIds.add(anchoredObjectId);
                ctx.inlineCompleteSimpleButtonLabelIds.add(matched.id());
            }
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
        if (shell == null || ctx.basePath == null || shell.file() == null) return null;
        File pngFile = new File(ctx.basePath, shell.file());
        if (!pngFile.exists() || !pngFile.isFile()) return null;
        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;

            double[] shellBounds = shell.bounds();
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
            obj.imageFillData(imageData);
            obj.nativeGraphicsAllowed(true);
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf));
            obj.verticalJustification("CenterAlign");
            buildBadgeParagraph(ctx, childTf, obj);
            return obj;
        } catch (Exception e) {
            return null;
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
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId) continue;
            if (!"inline_object".equals(rg.itemType())) continue;
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
        if (ctx == null || ctx.resolvedData == null) return false;
        if (ctx.resolvedData.isSimpleButtonLabelTextFrame(String.valueOf(anchoredObjectId))) {
            return true;
        }
        if (findCompleteSimpleButtonLabelRender(ctx, anchoredObjectId) != null) {
            return true;
        }
        return findSimpleButtonLabelChildTextFrame(ctx, anchoredObjectId) != null;
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
        String text = childTf.frameVisibleText().replace("￼", "").replace("\r", "").replace("\n", "").trim();
        ASTParagraph paraInner = new ASTParagraph();
        paraInner.alignment("CENTER");
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(text);
        ResolvedStory story = childTf.storyId() != null ? ctx.resolvedData.getStory(childTf.storyId()) : null;
        if (story != null && !story.paragraphs().isEmpty()) {
            ResolvedParagraph rp = story.paragraphs().get(0);
            if (rp.runs() != null && !rp.runs().isEmpty()) {
                ResolvedRun rr = rp.runs().get(0);
                if (rr.fontFamily() != null) textRun.fontFamily(rr.fontFamily());
                if (rr.fontStyle() != null) textRun.fontStyle(rr.fontStyle());
                if (rr.fontSize() != null && rr.fontSize() > 0) {
                    textRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                }
                if (rr.fillColor() != null) textRun.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
            }
        }
        paraInner.addItem(textRun);
        obj.addParagraph(paraInner);
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

        double[] gb = tf.geometricBounds();
        if (gb == null || gb.length < 4) return null;
        double w = Math.abs(gb[3] - gb[1]);
        double h = Math.abs(gb[2] - gb[0]);
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
        obj.noAutoLineWrap(shouldUseNoAutoLineWrap(tf));

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

                ASTTextRun textRun = tryInlineTextFrameAsRun(ctx, childId, previousText, nextText);
                if (textRun != null) {
                    items.add(textRun);
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
            text = text.replace("\r", "").replace("\n", "");
            if (text.isEmpty()) continue;
            ASTTextRun textRun = new ASTTextRun();
            textRun.text(text);
            applyResolvedRunStyle(ctx, textRun, rr);
            if (parentFrameUnderline) textRun.underline(true);
            items.add(textRun);
        }

        if (!hasNestedAnchor || items.isEmpty()) return null;
        return items;
    }

    static ASTTextRun tryInlineTextFrameAsRun(ResolvedBuildContext ctx, int anchoredObjectId,
                                             String previousText, String nextText) {
        // Phase 2가 floating text box로 승격한 TF → 인라인 런 중복 방지
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        String domId = String.valueOf(anchoredObjectId);
        if (isConceptDiagramTextFrame(ctx, domId)) return null;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null) {
            // SPEC-025: anchoredId 가 Group (TextFrame 아님) 인 경우, 자손 중 inline+editable TF 텍스트를 합쳐 임베드.
            // 단일 1자 (예: "1", "예") 는 시각 PNG 유지 우선 → 임베드 안 함.
            // 그러나 다중 1자 자손 (예: jamo 배지 ㅍㅎ, ㅂㅅ) 은 결합 텍스트가 의미 있으므로 합쳐서 임베드.
            java.util.List<ResolvedTextFrame> inlineDescs = findInlineEditableDescendants(ctx, domId);
            if (!inlineDescs.isEmpty()) {
                StringBuilder _sb = new StringBuilder();
                for (ResolvedTextFrame d : inlineDescs) {
                    if (ctx.resolvedData.isTextOwnedByIndesignPng(d.id())) continue;
                    String t = d.frameVisibleText();
                    String c = t == null ? "" : t.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                    if (!c.isEmpty()) _sb.append(c);
                }
                String combined = _sb.toString();
                if (combined.length() >= 2) {
                    ASTTextRun run = new ASTTextRun();
                    run.text(combined);
                    return run;
                }
            }
            return null;
        }
        if (!tf.isInline()) return null;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(domId)) return null;

        // 렌더 PDF 프레임으로 이미 배치된 경우 텍스트 런 변환 안 함
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;
        // SPEC-025: IDML Story 우선 + 중첩 인라인 앵커 재귀 처리
        // (예: 페이지 10 frame 15359 의 anchored Group 안에 frame 15568 "예" 가 있음 →
        //  Java 가 ORC 를 만나면 anchored 객체의 텍스트를 재귀로 가져와 inline 위치에 임베드)
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
                    if (ctx.ensureIdmlInfra != null) ctx.ensureIdmlInfra.run();
                    kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDoc =
                            ctx.idmlDocumentSupplier != null ? ctx.idmlDocumentSupplier.get() : null;
                    for (IDMLParagraph p : idmlStoryRule.paragraphs()) {
                        if (p.ruleBelowOn()) { hasRuleBelow = true; break; }
                        String psRef = p.appliedParagraphStyle();
                        if (psRef != null && idmlDoc != null) {
                            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef sd = idmlDoc.getParagraphStyle(psRef);
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
                if (!hasRuleBelow && ctx.idmlDocumentSupplier != null) {
                    if (ctx.ensureIdmlInfra != null) ctx.ensureIdmlInfra.run();
                    kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idoc = ctx.idmlDocumentSupplier.get();
                    if (idoc != null) {
                        String psRef = ip0.appliedParagraphStyle();
                        if (psRef != null) {
                            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef sd = idoc.getParagraphStyle(psRef);
                            if (sd != null && Boolean.TRUE.equals(sd.ruleBelowOn())) hasRuleBelow = true;
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

    private static void applyResolvedRunStyle(ResolvedBuildContext ctx, ASTTextRun run, ResolvedRun rr) {
        if (run == null || rr == null) return;
        if (rr.fontFamily() != null) run.fontFamily(rr.fontFamily());
        if (rr.fontStyle() != null) run.fontStyle(rr.fontStyle());
        if (rr.fontSize() != null && rr.fontSize() > 0) {
            run.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
        }
        if (rr.fillColor() != null) run.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
        if (rr.underline() != null && rr.underline()) run.underline(true);
        if (rr.strikeThru() != null && rr.strikeThru()) run.strikeThrough(true);
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

        if (ctx.idmlDocumentSupplier != null) {
            if (ctx.ensureIdmlInfra != null) ctx.ensureIdmlInfra.run();
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idoc = ctx.idmlDocumentSupplier.get();
            if (idoc != null) {
                String psRef = ip0.appliedParagraphStyle();
                if (psRef != null) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef sd = idoc.getParagraphStyle(psRef);
                    if (sd != null && Boolean.TRUE.equals(sd.ruleBelowOn())) return true;
                }
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

    /**
     * SPEC-020: 빈 컨테이너 = fill/stroke 모두 None 인 inline TextFrame.
     * 이런 프레임은 PNG 안에 그려진 일러스트/외곽선의 텍스트 입력란이므로
     * inline_object PNG 로드 결정에서 "텍스트 중복" 폐기 사유로 보지 않는다.
     */
    private static boolean isEmptyContainer(ResolvedTextFrame tf) {
        return isNoneColor(tf.fillColor()) && isNoneColor(tf.strokeColor());
    }

    private static boolean isOwnedByPlacedVisualRender(ResolvedBuildContext ctx, int objectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return false;
        }
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(objectId));
        String itemType = item != null ? item.type() : null;
        if (!"Polygon".equals(itemType) && !"Rectangle".equals(itemType)) {
            return false;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() == objectId) continue;
            if (rg.file() == null || rg.file().isEmpty()) continue;
            if (Boolean.FALSE.equals(rg.placementAllowed())) continue;
            if (!containsId(rg.visualOnlyChildIds(), objectId)
                    && !containsId(rg.tfInlineVisualIds(), objectId)) continue;

            String type = rg.type();
            if (type != null && !"page_object".equals(type)) continue;

            String visualOwner = rg.visualOwner();
            if (visualOwner != null && !"indesign_png".equals(visualOwner)) continue;

            return true;
        }
        return false;
    }

    private static boolean containsId(int[] ids, int id) {
        if (ids == null) return false;
        for (int candidate : ids) {
            if (candidate == id) return true;
        }
        return false;
    }

    /**
     * 원본에서는 하나의 시각 단위인 vector-only inline 조각들이 별도 Polygon/Rectangle으로
     * 흩어진 경우 하나의 PNG로 합성한다. 예: 체크박스 외곽 + 체크 표시.
     */
    private static ASTInlineObject loadMergedInlineVectorUnit(ResolvedBuildContext ctx, int anchoredObjectId) {
        RenderedGroup anchor = findInlineRenderedGroup(ctx, anchoredObjectId);
        if (!isMergeableInlineVector(ctx, anchor)) return null;

        List<RenderedGroup> parts = new ArrayList<RenderedGroup>();
        parts.add(anchor);
        double[] union = copyBounds(anchor.bounds());
        if (union == null) return null;

        for (RenderedGroup candidate : ctx.resolvedData.allRenderedFloatingItems()) {
            if (candidate == null || candidate.id() == anchoredObjectId) continue;
            if (!isMergeableInlineVector(ctx, candidate)) continue;
            if (ctx.isRenderedDisposed(candidate.id(), FrameDisposition.TEXT_BLOCK_PLACED)) continue;
            if (candidate.pageIndex() != anchor.pageIndex()) continue;
            if (!boundsOverlapEnough(anchor.bounds(), candidate.bounds())) continue;
            parts.add(candidate);
            union = unionBounds(union, candidate.bounds());
        }
        if (parts.size() <= 1) return null;

        try {
            BufferedImage merged = renderMergedInlineParts(ctx, parts, union);
            if (merged == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(merged, "png", out);

            for (RenderedGroup part : parts) {
                if (part.id() != anchoredObjectId) {
                    ctx.setRenderedDisposition(part.id(), FrameDisposition.TEXT_BLOCK_PLACED);
                }
            }

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.imageData(out.toByteArray());
            obj.imageFormat("png");
            obj.pixelWidth(merged.getWidth());
            obj.pixelHeight(merged.getHeight());
            obj.boundsX(union[1]);

            double[] pageRelative = toPageRelativeRenderedBounds(ctx, anchor, union);
            obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pageRelative[0] * ctx.scaleFactor));
            obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pageRelative[1] * ctx.scaleFactor));
            obj.width(CoordinateConverter.pointsToHwpunits(Math.abs(union[3] - union[1]) * ctx.scaleFactor));
            obj.height(CoordinateConverter.pointsToHwpunits(Math.abs(union[2] - union[0]) * ctx.scaleFactor));
            obj.sourceId("u" + Integer.toHexString(anchoredObjectId) + "_vector_unit");
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static RenderedGroup findInlineRenderedGroup(ResolvedBuildContext ctx, int id) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg != null && rg.id() == id && isInlineRenderedGroupType(rg)) return rg;
        }
        return null;
    }

    private static boolean isMergeableInlineVector(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || rg == null || !isInlineRenderedGroupType(rg)) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        if (w <= 0 || h <= 0 || w > INLINE_VECTOR_UNIT_MAX_SIZE_PT || h > INLINE_VECTOR_UNIT_MAX_SIZE_PT) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(rg.id()));
        if (item == null) return false;
        String type = item.type();
        return "Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type) || "GraphicLine".equals(type);
    }

    private static boolean isInlineRenderedGroupType(RenderedGroup rg) {
        if (rg == null) return false;
        return "inline_object".equals(rg.itemType()) || "inline_object".equals(rg.type());
    }

    private static BufferedImage renderMergedInlineParts(
            final ResolvedBuildContext ctx, List<RenderedGroup> parts, double[] union) throws Exception {
        if (parts == null || parts.isEmpty() || union == null || union.length < 4) return null;

        List<RenderedGroup> sorted = new ArrayList<RenderedGroup>(parts);
        sorted.sort(new java.util.Comparator<RenderedGroup>() {
            @Override
            public int compare(RenderedGroup a, RenderedGroup b) {
                ResolvedPageItem ia = ctx.resolvedData.getPageItem(String.valueOf(a.id()));
                ResolvedPageItem ib = ctx.resolvedData.getPageItem(String.valueOf(b.id()));
                int za = ia != null ? ia.zOrder() : a.zOrder();
                int zb = ib != null ? ib.zOrder() : b.zOrder();
                return Integer.compare(za, zb);
            }
        });

        double scale = pixelsPerPoint(ctx, sorted.get(0));
        int width = Math.max(1, (int) Math.ceil(Math.abs(union[3] - union[1]) * scale));
        int height = Math.max(1, (int) Math.ceil(Math.abs(union[2] - union[0]) * scale));
        BufferedImage merged = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = merged.createGraphics();
        try {
            for (RenderedGroup part : sorted) {
                File file = new File(ctx.basePath, part.file());
                BufferedImage img = ImageIO.read(file);
                if (img == null) continue;
                double[] b = part.bounds();
                int x = (int) Math.round((b[1] - union[1]) * scale);
                int y = (int) Math.round((b[0] - union[0]) * scale);
                int w = Math.max(1, (int) Math.round(Math.abs(b[3] - b[1]) * scale));
                int h = Math.max(1, (int) Math.round(Math.abs(b[2] - b[0]) * scale));
                g.drawImage(img, x, y, w, h, null);
            }
        } finally {
            g.dispose();
        }
        return merged;
    }

    private static double pixelsPerPoint(ResolvedBuildContext ctx, RenderedGroup rg) throws Exception {
        if (ctx != null && ctx.pngExportDpi > 0) return ctx.pngExportDpi / 72.0;
        if (ctx == null || rg == null || rg.file() == null || rg.bounds() == null) return 4.0;
        BufferedImage img = ImageIO.read(new File(ctx.basePath, rg.file()));
        double wPt = Math.abs(rg.bounds()[3] - rg.bounds()[1]);
        if (img != null && wPt > 0) return img.getWidth() / wPt;
        return 4.0;
    }

    private static boolean boundsOverlapEnough(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        double top = Math.max(a[0], b[0]);
        double left = Math.max(a[1], b[1]);
        double bottom = Math.min(a[2], b[2]);
        double right = Math.min(a[3], b[3]);
        double iw = right - left;
        double ih = bottom - top;
        if (iw <= 0 || ih <= 0) return false;
        double overlap = iw * ih;
        double areaA = Math.abs((a[2] - a[0]) * (a[3] - a[1]));
        double areaB = Math.abs((b[2] - b[0]) * (b[3] - b[1]));
        double smaller = Math.min(areaA, areaB);
        return smaller > 0 && overlap / smaller >= INLINE_VECTOR_UNIT_OVERLAP_RATIO;
    }

    private static double[] copyBounds(double[] b) {
        if (b == null || b.length < 4) return null;
        return new double[]{b[0], b[1], b[2], b[3]};
    }

    private static double[] unionBounds(double[] a, double[] b) {
        return new double[]{
                Math.min(a[0], b[0]),
                Math.min(a[1], b[1]),
                Math.max(a[2], b[2]),
                Math.max(a[3], b[3])
        };
    }

    /**
     * renderedFloatingItems에서 인라인 객체 PNG를 로드하여 ASTInlineObject로 변환.
     */
    static ASTInlineObject loadInlineObject(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx.basePath == null) return null;

        // Phase 2 가 이 inline_object 의 자손 TF 를 floating 으로 전환했으면
        // inline PNG 는 Phase 7 이 floating ASTFigure 로 재배치 → 여기서 억제.
        if (ctx.isInlineDisposed(anchoredObjectId, FrameDisposition.PNG_CONVERT_TO_FLOATING)) return null;
        // Phase 2 가 floating text box 로 승격한 inline TF → inline PNG 도 억제 (28pt PNG가 행간 팽창하는 것 방지).
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;

        ASTInlineObject completeSimpleButton =
                loadCompleteSimpleButtonLabelInlineObject(ctx, anchoredObjectId);
        if (completeSimpleButton != null) return completeSimpleButton;

        // 부모 rendered PNG가 이미 소유한 visual-only 인라인 그래픽은 Story 흐름에 다시 넣지 않는다.
        // 예: 말풍선 curly-brace, 답안 밑줄, 작은 bullet/badge 배경.
        // 이 객체들은 부모 PNG의 원본 좌표가 정답이고, 인라인으로 중복 배치하면 줄바꿈/수직정렬 문제가 생긴다.
        if (isOwnedByPlacedVisualRender(ctx, anchoredObjectId)) return null;

        ASTInlineObject mergedVectorUnit = loadMergedInlineVectorUnit(ctx, anchoredObjectId);
        if (mergedVectorUnit != null) return mergedVectorUnit;

        // 자식/자손 TextFrame이 플로팅 텍스트박스로 배치될 예정이면
        // inline_object PNG를 로드하지 않는다 (이미지 + 글상자 중복 방지).
        // Rectangle은 childIds가 비어있고 자식이 parentId로만 참조하므로 textFrames를 훑는다.
        String anchorIdStr = String.valueOf(anchoredObjectId);
        for (ResolvedTextFrame childTf : ctx.resolvedData.textFrames()) {
            // childTf의 조상 중에 anchorId가 있는지 확인
            boolean isDescendant = false;
            String curId = childTf.id();
            int depth = 0;
            while (curId != null && depth < 8) {
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(curId);
                if (pi == null) break;
                String pid = pi.parentId();
                if (pid == null) break;
                if (anchorIdStr.equals(pid)) { isDescendant = true; break; }
                curId = pid;
                depth++;
            }
            if (!isDescendant) continue;
            String vt = childTf.frameVisibleText();
            boolean hasText = vt != null && vt.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 1;
            if (!hasText) continue;
            // SPEC-020: 빈 컨테이너(fill=None, stroke=None)는 텍스트 입력란이며,
            // PNG는 그 입력란을 둘러싼 시각적 배경(일러스트/라운드 외곽선)만 담는다.
            // 텍스트는 별도 오버레이되므로 PNG를 폐기하면 안 됨.
            if (isEmptyContainer(childTf)) continue;
            // inline+editable 배지 자식: Phase 3가 INLINE_TEXT_FRAME으로 전담 처리 →
            // 이 TF의 텍스트는 INLINE_TEXT_FRAME 내부에 포함됨 → loadInlineObject PNG 폐기.
            if (childTf.isInline() && ctx.resolvedData.isEditableTextFrame(childTf.id())) {
                return null;
            }
            if (ctx.resolvedData.isEditableTextFrame(childTf.id())) {
                return null;
            }
            // inline + non-editable: Phase 3 가 이 child TF 를 별도로 처리할 수 있으므로 PNG 폐기.
            if (childTf.isInline()) {
                return null;
            }
        }

        // renderedFloatingItems에서 해당 ID의 inline_object 또는 null-type inline TF 찾기.
        // null-type: renderable inline TF (예: 번호 라벨 "1", "가") — Phase 7 floating 대신
        // inline PNG로 임베드하여 텍스트 baseline과 수평 정렬 보장.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() != anchoredObjectId) continue;
            boolean proceed = "inline_object".equals(rg.itemType());
            if (!proceed && rg.itemType() == null) {
                ResolvedTextFrame ancTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                proceed = ancTf != null && ancTf.isInline();
            }
            if (proceed) {
                if (ctx.hasOwnershipPlan(rg) && !ctx.shouldPlaceInlinePngByOwnershipPlan(rg)) {
                    return null;
                }
                if (isDoviraSubunitMarkerRender(ctx, rg)) return null;
                if ("inline_object".equals(rg.itemType())
                        && findCompleteLabelPageObjectPair(ctx, rg) != null
                        && !shouldPlaceCompleteLabelPairInline(ctx, rg, anchoredObjectId)) {
                    return null;
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
                        // 전체 폭 배경 데코레이션 감지: 가로/세로 비율 > 8 이면서 폭 > 100pt 이면
                        // 인라인 배치 시 한 줄을 전부 차지하여 이후 텍스트를 밀어냄 → 인라인 스킵.
                        // Phase 7이 floating ASTFigure로 배치하도록 위임.
                        // (예: 주황색 라운드사각형 배경 AR=16 — 텍스트 배경이지 인라인 문자가 아님)
                        // AR=6~7 정도의 라벨 박스(예: "최근 사회·문화적 맥락" 36mm×6mm)는 인라인 유지.
                        // GraphicLine(인라인 선)은 제외.
                        if (!isGraphicLine && bw > bh * 8.0 && bw > 100.0) {
                            ctx.setInlineDisposition(anchoredObjectId, FrameDisposition.PNG_CONVERT_TO_FLOATING);
                            return null;
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
                    // 비율 0.5~4 범위 아이콘/배지: 14pt 상한.
                    // scribble 외곽선(비율 4.3, 5.8 등)은 page layout 영향으로 제외.
                    if (!isNullTypeInline && rtf == null && obj.height() > 1500) {
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
            ctx.inlineCompleteSimpleButtonLabelIds.add(anchoredObjectId);
            ctx.inlineCompleteSimpleButtonLabelIds.add(completeRender.id());
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
            if (tf.onHiddenLayer() || tf.nonprinting()) continue;
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

    private static boolean isDoviraSubunitMarkerRender(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        String storyId = rg.parentStoryId();
        if (storyId == null || storyId.isEmpty()) return false;
        return DoviraSubunitMarkerPolicy.isDuplicateMarkerStory(ctx.resolvedData, storyId);
    }

    /**
     * inline_object PNG로 배치된 Group의 직속 editable 자식 TF를
     * INLINE_TEXT_FRAME으로 변환한다.
     *
     * PNG에는 editable TF 내용이 포함되지 않으므로 별도로 배치해야 한다.
     */
    public static java.util.List<ASTInlineObject> buildChildEditableBoxes(ResolvedBuildContext ctx, int groupId) {
        java.util.List<ASTInlineObject> result = new ArrayList<>();
        String groupIdStr = String.valueOf(groupId);
        java.util.List<ResolvedTextFrame> editableChildren = new ArrayList<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null || !groupIdStr.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) continue;
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            editableChildren.add(tf);
        }

        RenderedGroup inlineBackdrop = editableChildren.size() == 1
                ? findInlineEditableGroupBackdrop(ctx, groupId)
                : null;
        byte[] inlineBackdropData = loadRenderedPngBytes(ctx, inlineBackdrop);
        double[] inlineBackdropBounds = inlineBackdrop != null ? inlineBackdrop.bounds() : null;

        for (ResolvedTextFrame tf : editableChildren) {
            String vt = tf.frameVisibleText();
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            double[] gb = tf.geometricBounds();
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
            if (inlineBackdropData != null && inlineBackdropBounds != null && inlineBackdropBounds.length >= 4) {
                boxW = Math.abs(inlineBackdropBounds[3] - inlineBackdropBounds[1]);
                boxH = Math.abs(inlineBackdropBounds[2] - inlineBackdropBounds[0]);
                if (boxW > 0 && boxH > 0) {
                    marginTop = Math.max(0, gb[0] - inlineBackdropBounds[0]);
                    marginLeft = Math.max(0, gb[1] - inlineBackdropBounds[1]);
                    marginBottom = Math.max(0, inlineBackdropBounds[2] - gb[2]);
                    marginRight = Math.max(0, inlineBackdropBounds[3] - gb[3]);
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
            box.noAutoLineWrap(shouldUseNoAutoLineWrap(tf));
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
            if (story != null && !story.paragraphs().isEmpty()) {
                ResolvedParagraph rp = story.paragraphs().get(0);
                if (rp.runs() != null && !rp.runs().isEmpty()) {
                    ResolvedRun rr = rp.runs().get(0);
                    if (rr.fontFamily() != null) textRunInner.fontFamily(rr.fontFamily());
                    if (rr.fontStyle() != null) textRunInner.fontStyle(rr.fontStyle());
                    if (rr.fontSize() != null && rr.fontSize() > 0) {
                        textRunInner.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
                    }
                    if (rr.fillColor() != null) textRunInner.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
                }
            }
            paraInner.addItem(textRunInner);
            box.addParagraph(paraInner);
            box.verticalJustification("CenterAlign");
            result.add(box);
        }
        return result;
    }

    private static RenderedGroup findInlineEditableGroupBackdrop(ResolvedBuildContext ctx, int groupId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != groupId || rg.file() == null) continue;
            if (!"inline_object".equals(rg.itemType())) continue;
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

    /**
     * SPEC-025: IDML story 의 모든 텍스트를 재귀로 추출한다. ORC(￼) 위치에서
     * inline 앵커된 TextFrame 또는 Group 내부 TextFrame 의 텍스트도 in-order 로 포함.
     *
     * <p>예: 박현숙 1단원 페이지 10 의 frame 15359 story 는
     * {@code "[Group{Oval, TextFrame(예)}] 적절한 근거를..."} 구조라서, 이 함수는
     * "예 적절한 근거를..." 식으로 재귀 임베드해 반환한다.</p>
     *
     * <p>{@code depth} 는 무한 재귀 방지용 (최대 4단계).</p>
     */
    private static String extractTextRecursive(ResolvedBuildContext ctx, IDMLStory idmlStory, int depth) {
        if (idmlStory == null || depth >= 4) return "";
        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph p : idmlStory.paragraphs()) {
            for (IDMLCharacterRun r : p.characterRuns()) {
                String content = r.content();
                if (content == null) content = "";
                String[] parts = content.split("￼", -1);
                java.util.List<IDMLCharacterRun.InlineAnchor> anchors = r.inlineAnchors();
                for (int pi = 0; pi < parts.length; pi++) {
                    sb.append(parts[pi]);
                    if (pi < parts.length - 1) {
                        // ORC 위치 — inline 앵커 텍스트 재귀 추출
                        if (anchors != null && pi < anchors.size()) {
                            IDMLCharacterRun.InlineAnchor anc = anchors.get(pi);
                            String inlineText = resolveAnchorText(ctx, r, anc, depth + 1);
                            if (inlineText != null) sb.append(inlineText);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /** anchor 가 가리키는 TextFrame/InlineGraphic 의 텍스트를 재귀로 가져온다. */
    private static String resolveAnchorText(ResolvedBuildContext ctx, IDMLCharacterRun run,
                                             IDMLCharacterRun.InlineAnchor anchor, int depth) {
        if (depth >= 4) return "";
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (run.inlineFrames() == null || anchor.index() >= run.inlineFrames().size()) return "";
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame tf = run.inlineFrames().get(anchor.index());
            if (tf == null || tf.parentStoryId() == null) return "";
            // SPEC-025: 앵커 TF 가 별도 PNG 로 렌더되는 경우 (예: 번호 마커 "1") 인라인 임베드는 중복 → 스킵.
            if (isRenderedAsImage(ctx, tf.selfId())) return "";
            IDMLStory childStory = ctx.loadIDMLStory.apply(tf.parentStoryId());
            return extractTextRecursive(ctx, childStory, depth);
        }
        // InlineAnchorType.GRAPHIC: Group 내부 TextFrame 들의 텍스트 합치기
        if (run.inlineGraphics() == null || anchor.index() >= run.inlineGraphics().size()) return "";
        IDMLCharacterRun.InlineGraphic ig = run.inlineGraphics().get(anchor.index());
        return extractGraphicText(ctx, ig, depth);
    }

    /** IDML selfId (hex, "uXXXX") 로 표기된 TextFrame 이 renderedFloatingItems 에 PNG 로 등록됐는지 확인. */
    private static boolean isRenderedAsImage(ResolvedBuildContext ctx, String idmlSelfId) {
        if (ctx == null || ctx.resolvedData == null || idmlSelfId == null) return false;
        if (!idmlSelfId.startsWith("u")) return false;
        int domId;
        try { domId = Integer.parseInt(idmlSelfId.substring(1), 16); }
        catch (NumberFormatException e) { return false; }
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == domId && rg.file() != null && !rg.file().isEmpty()) return true;
        }
        return false;
    }

    /** 주어진 anchoredId 그룹의 자손 중 inline+editable TF 들을 모두 찾는다 (visual 순서대로 가능한 범위). */
    private static java.util.List<ResolvedTextFrame> findInlineEditableDescendants(ResolvedBuildContext ctx, String anchorIdStr) {
        java.util.List<ResolvedTextFrame> result = new java.util.ArrayList<>();
        for (ResolvedTextFrame childTf : ctx.resolvedData.textFrames()) {
            if (childTf == null || !childTf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(childTf.id())) continue;
            String vt = childTf.frameVisibleText();
            if (vt == null || vt.replace("￼", "").trim().isEmpty()) continue;
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
        // Order by Y then X (top-to-bottom, left-to-right reading order).
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

    /** 주어진 anchoredId 그룹의 자손 중 첫 inline+editable TF (텍스트 길이 ≥ 1) 를 찾는다. */

    /** InlineGraphic(Group/Rectangle/Polygon) 내부의 모든 TextFrame 텍스트를 재귀로 합쳐 반환. */
    private static String extractGraphicText(ResolvedBuildContext ctx, IDMLCharacterRun.InlineGraphic ig, int depth) {
        if (ig == null || depth >= 4) return "";
        // 그래픽 자체가 renderedFloatingItems로 소유권을 가진 경우, ORC 재귀 텍스트 추출에서
        // 자식 TF 텍스트를 문자열로 복사하지 않는다. 삽입 단계에서 INLINE_TEXT_FRAME/PNG가
        // 시각 단위로 배치되므로 여기서 텍스트를 반환하면 "예 예..." 같은 중복이 생긴다.
        if (isRenderedAsImage(ctx, ig.selfId())) return "";
        StringBuilder sb = new StringBuilder();
        // 그래픽 자체에 임베드된 텍스트
        if (ig.embeddedText() != null && !ig.embeddedText().isEmpty()) {
            sb.append(ig.embeddedText());
        }
        // Group 자식 TextFrame
        if (ig.childTextFrames() != null) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame ctf : ig.childTextFrames()) {
                if (ctf == null || ctf.parentStoryId() == null) continue;
                if (isRenderedAsImage(ctx, ctf.selfId())) continue;
                IDMLStory cs = ctx.loadIDMLStory.apply(ctf.parentStoryId());
                String t = extractTextRecursive(ctx, cs, depth + 1);
                if (t != null && !t.isEmpty()) sb.append(t);
            }
        }
        // Group 자식 그래픽 (재귀)
        if (ig.childGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
                String t = extractGraphicText(ctx, child, depth + 1);
                if (t != null && !t.isEmpty()) sb.append(t);
            }
        }
        return sb.toString();
    }

    /** resolved.json에 없는 cornerRadius를 IDML spread의 vectorShapes에서 조회. */
    private static double lookupIdmlShapeCornerRadius(ResolvedBuildContext ctx, String decimalId) {
        if (decimalId == null || ctx.idmlDocumentSupplier == null) return 0;
        if (ctx.ensureIdmlInfra != null) ctx.ensureIdmlInfra.run();
        IDMLDocument idoc = ctx.idmlDocumentSupplier.get();
        if (idoc == null) return 0;
        String hexId;
        try { hexId = "u" + Integer.toHexString(Integer.parseInt(decimalId)); }
        catch (NumberFormatException e) { return 0; }
        for (IDMLSpread spread : idoc.spreads()) {
            for (IDMLVectorShape shape : spread.vectorShapes()) {
                if (hexId.equals(shape.selfId())) return shape.cornerRadius();
            }
        }
        for (IDMLSpread master : idoc.masterSpreads().values()) {
            for (IDMLVectorShape shape : master.vectorShapes()) {
                if (hexId.equals(shape.selfId())) return shape.cornerRadius();
            }
        }
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

    private static boolean shouldPlaceCompleteLabelPairInline(
            ResolvedBuildContext ctx,
            RenderedGroup inline,
            int anchoredObjectId) {
        RenderedGroup pageObject = findCompleteLabelPageObjectPair(ctx, inline);
        if (pageObject == null) return true;
        ResolvedTextFrame childTf = findSimpleButtonLabelChildTextFrame(ctx, anchoredObjectId);
        boolean placeInline = shouldPlaceCompleteSimpleButtonLabelInline(ctx, childTf, pageObject);
        if (placeInline) {
            ctx.inlineCompleteSimpleButtonLabelIds.add(anchoredObjectId);
            ctx.inlineCompleteSimpleButtonLabelIds.add(pageObject.id());
        }
        return placeInline;
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
        if (tf == null) return false;
        if (isShortSingleLineTextFrame(tf)) return true;
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

    private static String normalizeVisibleText(String visibleText) {
        if (visibleText == null) return "";
        return visibleText
                .replace("\uFFFC", "")
                .replace("\u0007", "")
                .replace("\b", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static boolean isShortSingleLineTextFrame(ResolvedTextFrame tf) {
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

        String normalized = normalizeVisibleText(visibleText);
        return !normalized.isEmpty() && normalized.length() <= SHORT_SINGLE_LINE_NO_WRAP_CHARS;
    }

}
