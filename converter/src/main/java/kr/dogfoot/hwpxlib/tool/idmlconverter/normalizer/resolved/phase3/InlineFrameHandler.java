package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ConverterConstants;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedTextFlowAstConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextStyleApplicator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.DoviraSubunitMarkerPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TableFrameOwnershipPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4.TableBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualCropper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowAstMaterializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;
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

import java.awt.AlphaComposite;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        // Stage 1 is authoritative for the TEXT_SLOT. Legacy rendered-channel flags
        // may describe the visual shell, but must not suppress an inline HWPX text plan.

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
        // raw 폴백 텍스트에도 EH 해킹 글리프(ù→°, Ñ→±, Ó/Û→², Ã 폭선택자 등)가 남는다
        // (SPEC-081 클래스 Z: 4단원 90ù·BCÓ, 2단원 -bÑsqrt·Ãb2-4ac). 경로별로 디코딩이
        // 흩어져 프래그먼트만 raw 로 새므로, 통일 진입점으로 한 번 더 훑는다.
        if (numScript == null) numScript = EHFontGlyphMap.decodeStrayGlyphText(numerator, null);
        if (denomScript == null) denomScript = EHFontGlyphMap.decodeStrayGlyphText(denominator, null);

        String hwpScript = "{" + numScript + "} over {" + denomScript + "}";
        return new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(hwpScript, "INLINE_FRACTION");
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
        boolean hasHookGlyph = false;
        for (ResolvedRun r : rp.runs()) {
            if (r.fontFamily() != null && EHFontGlyphMap.isEHFontFamily(r.fontFamily())) {
                hasEH = true;
            }
            // resolved DOM 은 분수대문자 근호 갈고리(')·자리구분자(0x8C)·제곱(Û) 글리프에서
            // fontFamily 를 null 로 흘린다(실측: 1·2단원 인라인 분수 프레임의 '3/'2 =
            // √3/√2). 폰트만 보면 hasEH=false 로 raw 폴백돼 '가 그대로 노출된다
            // (SPEC-081 클래스 A/F/G). 글리프 자체로도 EH 분수대문자 문맥임을 판정한다.
            if (containsFractionUpperGlyph(r.text())) {
                hasHookGlyph = true;
            }
        }
        if (!hasEH && !hasHookGlyph) return null;

        // EH 런을 IDMLCharacterRun으로 변환하여 EHFontEquationConverter로 처리.
        // 폰트가 유실된 분수대문자 글리프 런은 EH분수대문자 폰트를 되찍어 lexFractionUpper
        // 의 HOOK(√)·digit-sep·Û(²) 디코딩 경로를 타게 한다.
        List<IDMLCharacterRun> ehRuns = new ArrayList<>();
        for (ResolvedRun r : rp.runs()) {
            IDMLCharacterRun cr = new IDMLCharacterRun();
            cr.content(r.text());
            String ff = r.fontFamily();
            if ((ff == null || !EHFontGlyphMap.isEHFontFamily(ff))
                    && containsFractionUpperGlyph(r.text())) {
                ff = "EH분수대문자";
            }
            cr.fontFamily(ff);
            ehRuns.add(cr);
        }
        return EHFontEquationConverter.convert(ehRuns);
    }

    /**
     * EH분수대문자 폰트 전용 글리프(근호 갈고리 '·"·®·¿·¾, 자리구분자 0x8C, 제곱 Û/Ü)를
     * 포함하는지. resolved DOM 이 폰트를 null 로 흘려도 이 글리프면 분수대문자 문맥이다
     * (SPEC-081). 일반 아포스트로피와 구분하려 숫자/근호 문맥까지 함께 본다.
     */
    private static boolean containsFractionUpperGlyph(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '®' || c == '¿' || c == '¾'
                    || c == '' || c == 'Û' || c == 'Ü') {
                return true;
            }
        }
        return false;
    }

    /**
     * source ownership policy: Group 앵커가 다수의 시각적 박스(예: 자모 ㅍ ㅎ ㅂ ㅅ 배지)를 포함하면
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
        if (hasStage1ObjectPlans(ctx)) return null;
        if (hasOwnershipPlanForAnchorBundle(ctx, anchoredObjectId)) return null;
        if (hasInlineTextShellForAnchor(ctx, anchoredObjectId)
                || isCoveredByInlineTextShellSourceBundle(ctx, anchoredObjectId)) {
            return null;
        }
        String anchorId = String.valueOf(anchoredObjectId);
        if (containsObjectReplacementTextFrameDescendant(ctx, anchorId)) {
            return null;
        }
        ResolvedTextFrame anchorTf = ctx.resolvedData.getTextFrame(anchorId);
        if (anchorTf != null) return null;

        // 직속 자식 TF 수집 (inline + 텍스트 있음)
        java.util.List<ResolvedTextFrame> childTfs = new java.util.ArrayList<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            if (!anchorId.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            if (isTextFrameCoveredByInlineTextShell(ctx, tf.id())) continue;
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
        java.util.Set<String> descendantIds = ctx.descendantSet(anchorId, 5);

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
            if (isTextFrameCoveredByInlineTextShell(ctx, childTf.id())) continue;
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
            obj.verticalJustification(firstChildVerticalJustification(childTfs));

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
                if (isTextFrameCoveredByInlineTextShell(ctx, nestedTf.id())) continue;
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
            if (isTextFrameCoveredByInlineTextShell(ctx, tf.id())) continue;
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
        java.util.Set<String> descendantIds = ctx.descendantSet(anchorId, 8);
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
        if (hasStage1ObjectPlans(ctx)) return null;
        if (hasOwnershipPlanForAnchorBundle(ctx, anchoredObjectId)) return null;
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        if (anchorItem == null || !isInlineShellShape(anchorItem)) return null;

        ResolvedTextFrame childTf = null;
        String anchorId = String.valueOf(anchoredObjectId);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null || !anchorId.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            if (!isHwpxEditableTextFrame(ctx, tf.id())) continue;
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
        if (hasStage1ObjectPlans(ctx)) return null;
        if (hasOwnershipPlanForAnchorBundle(ctx, anchoredObjectId)) return null;
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        // 그룹은 resolved 에서 자식 TF 와 parentId 로 연결되지 않는 경우가 많다(parentId=null).
        // 대신 렌더 셸 PNG 의 editableTextFrameIds 로 편집 자식을 찾는다.
        // 곡선/말풍선 그룹은 텍스트를 숨긴 채 렌더된 셸 PNG(예: reason=*text_hidden, page_object)로 들어온다.
        RenderedGroup shell = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId || rg.file() == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            String[] eids = editableTextFrameIds(ctx, rg);
            if (eids == null || eids.length == 0) continue;
            shell = rg;
            break;
        }
        if (shell == null) return null;
        boolean inlineGroupAnchor = anchorItem != null && "Group".equals(anchorItem.type()) && anchorItem.isInline();
        if (!inlineGroupAnchor && !isInlineTextlessShellWithTf(shell)) return null;
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
        if (hasStage1ObjectPlans(ctx)) return null;
        if (hasOwnershipPlanForAnchorBundle(ctx, anchoredObjectId)) return null;
        String anchorId = String.valueOf(anchoredObjectId);
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
        java.util.Set<String> descendantIds = ctx.descendantSet(anchorId, 5);

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

        // inline placement policy: Oval 배경 배지인데 종횡비가 심하게 틀어진 경우 (ratio < 0.6),
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
                ? ctx.descendantSet(anchorId, 5)
                : java.util.Collections.emptySet();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            boolean sameAnchor = plan.domId == anchoredObjectId
                    || containsInt(plan.sourceObjectIds, anchoredObjectId);
            boolean descendantText = descendants.contains(String.valueOf(plan.domId))
                    || containsAnyStringId(descendants, plan.sourceObjectIds);
            if (!sameAnchor && !descendantText) continue;

            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && plan.placement == Placement.INLINE) {
                return true;
            }
            if (plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    && plan.placement == Placement.INLINE) {
                return true;
            }
            if (ShellRole.isTextShell(plan)
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
        Set<String> descendants = ctx.descendantSet(anchorId, 8);
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
        ObjectPlan shellPlan = findInlineTextShellOwnerPlan(
                ctx, shell, java.util.Collections.singletonList(childTf));
        if (shellPlan != null
                && shellPlan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && extractedShellImageOwnsGeometry(shellPlan)) {
            return buildInlineShellObject(ctx, anchoredObjectId, anchorItem,
                    java.util.Collections.singletonList(childTf), shell, shellPlan);
        }
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
            obj.keepInline(true);
            obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
            obj.verticalJustification("CenterAlign");
            applyInlineEditableLabelTextMargins(obj, anchorItem, childTf);
            if (ctx.basePath == null || shell.file() == null) return null;
            File pngFile = new File(ctx.basePath, shell.file());
            if (!pngFile.exists() || !pngFile.isFile()) return null;
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            obj.imageFillData(prepareInlineTextShellImageData(img, true));
            obj.forceImageFill(true);
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
        int childDomId;
        try {
            childDomId = Integer.parseInt(childId);
        } catch (NumberFormatException e) {
            childDomId = -1;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != anchoredObjectId || rg.file() == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (childDomId >= 0
                    && plan != null
                    && plan.placement == Placement.INLINE
                    && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    && plan.textAction == TextAction.OWNED_BY_PNG
                    && containsInt(plan.sourceObjectIds, anchoredObjectId)) {
                return rg;
            }
            if (childDomId >= 0
                    && plan != null
                    && plan.placement == Placement.INLINE
                    && ShellRole.isTextShell(plan)
                    && isShellPlanWithOwnedHwpxText(ctx, plan)
                    && containsInt(plan.ownedTextFrameIds, childDomId)) {
                return rg;
            }
        }
        return null;
    }

    private static ASTInlineObject loadRenderedInlineBadge(
            ResolvedBuildContext ctx, int anchoredObjectId,
            double widthPt, double heightPt, ResolvedTextFrame childTf) {
        if (ctx.basePath == null || childTf == null) return null;

        File pngFile = null;
        RenderedGroup matched = null;
        ObjectPlan matchedPlan = null;
        File atomicPngFile = null;
        RenderedGroup atomicMatched = null;
        ObjectPlan atomicMatchedPlan = null;
        if (ctx.resolvedData != null && ctx.resolvedData.allRenderedFloatingItems() != null) {
            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg == null || rg.id() != anchoredObjectId) continue;
                ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
                if (!isExecutableInlineBadgeMaterialPlan(plan)) continue;
                File candidate = new File(ctx.basePath, plan.file);
                if (!candidate.exists()) continue;
                if (ctx.isCompleteInlinePngByOwnershipPlan(rg)) {
                    atomicPngFile = candidate;
                    atomicMatched = rg;
                    atomicMatchedPlan = plan;
                    break;
                }
                if (isCompletePngSimpleButtonLabel(ctx, rg)) {
                    atomicPngFile = candidate;
                    atomicMatched = rg;
                    atomicMatchedPlan = plan;
                    break;
                }
                if (plan.visualAction == VisualAction.PLACE_INLINE_PNG || ShellRole.isTextShell(plan)) {
                    pngFile = candidate;
                    matched = rg;
                    matchedPlan = plan;
                    break;
                }
            }
        }
        if (atomicPngFile != null) {
            pngFile = atomicPngFile;
            matched = atomicMatched;
            matchedPlan = atomicMatchedPlan;
        }
        if (pngFile == null || matched == null || matchedPlan == null) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            BadgeImageData badgeImage = null;
            if (isCompletePngSimpleButtonLabel(ctx, matched)) {
                badgeImage = trimSimpleButtonBadgeImage(imageData, img);
                imageData = badgeImage.imageData;
                img = badgeImage.image;
            } else if (isInlineCompletePngTextOwnerPlan(matchedPlan)) {
                badgeImage = trimVerticalTransparentPaddingPreserveWidth(imageData, img);
                imageData = badgeImage.imageData;
                img = badgeImage.image;
            }
            if (matchedPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    && !ShellRole.isTextShell(matchedPlan)) {
                return null;
            }
            boolean overlayText = shouldOverlayRenderedBadgeText(ctx, matched);
            if (overlayText) {
                imageData = prepareInlineTextShellImageData(img, true);
                img = ImageIO.read(new java.io.ByteArrayInputStream(imageData));
                if (img == null) return null;
            }

            double[] b = matchedPlan.bounds;
            double bw = Math.abs(b[3] - b[1]) * ctx.scaleFactor;
            double bh = Math.abs(b[2] - b[0]) * ctx.scaleFactor;
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
            if (overlayText) {
                buildBadgeParagraph(ctx, childTf, obj);
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isExecutableInlineBadgeMaterialPlan(ObjectPlan plan) {
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && plan.hasVisibleVisual()
                && plan.file != null
                && !plan.file.isEmpty()
                && plan.bounds != null
                && plan.bounds.length >= 4
                && Math.abs(plan.bounds[3] - plan.bounds[1]) > 0
                && Math.abs(plan.bounds[2] - plan.bounds[0]) > 0;
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
        return buildInlineShellObject(ctx, anchoredObjectId, anchorItem, childTfs, shell, null);
    }

    private static ASTInlineObject buildInlineShellObject(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedPageItem anchorItem,
            java.util.List<ResolvedTextFrame> childTfs,
            RenderedGroup shell,
            ObjectPlan explicitShellPlan) {
        if (childTfs == null || childTfs.isEmpty()) return null;
        if (ctx == null || ctx.basePath == null) return null;
        if (shell != null && childTfs.size() == 1 && editableTextFrameIds(ctx, shell).length > 1) return null;
        ObjectPlan shellPlan = explicitShellPlan != null
                ? explicitShellPlan
                : findInlineTextShellOwnerPlan(ctx, shell, childTfs);
        if (shellPlan == null) return null;
        if (ctx.isObjectPlanMaterialized(shellPlan)) return null;
        String shellFile = materialFile(shellPlan);
        if (shellFile == null || shellFile.isEmpty()) return null;
        if (shellPlan.bounds == null || shellPlan.bounds.length < 4) return null;
        try {
            double[] renderBounds = validBounds(shellPlan.renderSourceBounds)
                    ? shellPlan.renderSourceBounds
                    : shellPlan.bounds;
            boolean useNativeSourceShell = canUseNativeInlineTextFrameShell(shellPlan, childTfs);
            double[] shellBounds = useNativeSourceShell
                    ? shellPlan.bounds
                    : plannedInlineShellCropBounds(shellPlan, renderBounds);
            File pngFile = new File(ctx.basePath, shellFile);
            if (!pngFile.exists() || !pngFile.isFile()) return null;
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            BufferedImage shellImage = VisualCropper.knockOutPaperLikeFill(img);
            boolean plannedSourceCropApplied = !useNativeSourceShell
                    && validBounds(renderBounds)
                    && validBounds(shellBounds)
                    && boundsDiffer(renderBounds, shellBounds, 0.01);
            if (plannedSourceCropApplied) {
                shellImage = cropImageByPlannedSourceBounds(shellImage, renderBounds, shellBounds);
            }
            int visibleShellTextFrameCount = visibleShellTextFrameCount(childTfs);
            boolean separatedHwpxTextChannel = shouldAttachSeparatedHwpxTextAsOverlay(ctx, shellPlan, childTfs);
            boolean embedCompositeShellText = shouldEmbedOwnedCompositeInlineTextShell(ctx, shellPlan, childTfs);
            boolean compactSplitCanvas = shouldCompactSplitInlineTextShellCanvas(shellPlan);
            ImageCropResult splitCanvasCrop = null;
            if (compactSplitCanvas) {
                splitCanvasCrop = cropInlineTextShellAlphaPadding(shellImage, shellBounds);
                shellImage = splitCanvasCrop.image;
            }
            boolean preserveSourceCanvas = !compactSplitCanvas
                    && shouldPreserveInlineShellSourceCanvas(ctx, shellPlan);
            boolean useCroppedExecutionBounds = splitCanvasCrop != null
                    && splitCanvasCrop.sourceBounds != null
                    && !isDirectChildInlineTextShellSlot(shellPlan);
            double[] visualExecutionBounds = useCroppedExecutionBounds
                    ? splitCanvasCrop.sourceBounds
                    : shellBounds;
            double[] executionBounds = plannedInlineTextShellLayoutBounds(
                    shellPlan,
                    visualExecutionBounds);
            double w = 0;
            double h = 0;
            if (executionBounds != null && executionBounds.length >= 4) {
                w = Math.abs(executionBounds[3] - executionBounds[1]) * ctx.scaleFactor;
                h = Math.abs(executionBounds[2] - executionBounds[0]) * ctx.scaleFactor;
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
            double[] pageLocalBounds = normalizeInlineBoundsToPageLocal(ctx, shellPlan.pageIndex, executionBounds);
            if (validBounds(pageLocalBounds)) {
                obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pageLocalBounds[1] * ctx.scaleFactor));
                obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pageLocalBounds[0] * ctx.scaleFactor));
                obj.resolvedWidth(obj.width());
                obj.resolvedHeight(obj.height());
            }
            if (useNativeSourceShell || !extractedShellImageOwnsGeometry(shellPlan)) {
                applyInlineShellShapeStyle(ctx, anchorItem, obj);
                applyOwnedTextFrameShellShapeStyle(ctx, childTfs, obj);
            }
            applyPlannedInlineExecutionHints(obj, shellPlan);
            obj.noAutoLineWrap(childTfs.size() == 1 && shouldUseNoAutoLineWrap(childTfs.get(0), true));
            obj.verticalJustification(inlineTextShellVerticalJustification(ctx, shellPlan, anchorItem, childTfs));

            boolean useImageFill = !(useNativeSourceShell && obj.nativeGraphicsAllowed());
            boolean usedNativeShellStyle = !useImageFill;
            boolean transparentSparseShell = !useNativeSourceShell
                    && shouldPreserveTransparentInlineShell(shellImage);
            boolean preserveImageCanvas = (preserveSourceCanvas || plannedSourceCropApplied)
                    && !embedCompositeShellText;
            boolean forceVerticalImageTrim = compactSplitCanvas || embedCompositeShellText;
            if (transparentSparseShell) {
                imageData = prepareTransparentInlineTextShellImageData(
                        ctx, shellPlan, executionBounds, shellImage,
                        preserveImageCanvas, forceVerticalImageTrim);
            } else {
                imageData = prepareInlineTextShellImageData(
                        shellImage, preserveImageCanvas, forceVerticalImageTrim);
            }
            if (embedCompositeShellText) {
                if (useImageFill) {
                    obj.imageFillData(imageData);
                    obj.forceImageFill(true);
                }
                obj.squeezeLineWrap(shouldSqueezeCompositeInlineShellText(childTfs));
                applyCompositeInlineShellTextMargins(
                        ctx,
                        obj,
                        shellPlan.pageIndex,
                        executionBounds,
                        childTfs);
                buildCompositeInlineShellParagraph(ctx, childTfs, obj);
                for (ResolvedTextFrame childTf : childTfs) {
                    markInlineShellChildTextPlaced(ctx, childTf);
                }
            } else if (visibleShellTextFrameCount > 1 || separatedHwpxTextChannel || transparentSparseShell) {
                if (useImageFill) {
                    obj.imageFillData(imageData);
                    obj.forceImageFill(true);
                }
                boolean embeddedSingleChild = visibleShellTextFrameCount == 1
                        && embedSingleInlineShellChildText(ctx, shellPlan.pageIndex, executionBounds, childTfs, obj);
                if (!embeddedSingleChild) {
                    attachInlineShellChildTextOverlays(ctx, shellPlan.pageIndex, executionBounds, childTfs, obj);
                }
                for (ResolvedTextFrame childTf : childTfs) {
                    markInlineShellChildTextPlaced(ctx, childTf);
                }
            } else {
                if (useImageFill) {
                    obj.imageFillData(imageData);
                    obj.forceImageFill(true);
                }
                applyInlineShellTextMargins(ctx, obj, shellPlan, anchorItem, childTfs);
                for (ResolvedTextFrame childTf : childTfs) {
                    if (isOrcCarrierTextFrame(childTf)) {
                        continue;
                    }
                    buildBadgeParagraph(ctx, childTf, obj, useNativeSourceShell);
                    markInlineShellChildTextPlaced(ctx, childTf);
                }
            }
            if (shell != null) {
                ctx.markRenderedVisualHandled(shell.id());
                ctx.recordRenderedDecision(shell, shellPlan, "Phase3.InlineFrameHandler",
                        "PLACE_INLINE_TEXT_SHELL",
                        usedNativeShellStyle
                                ? "placed planned inline textless shell as INLINE_TEXT_FRAME native source shape; editable text is owned by HWPX"
                                : "placed planned inline textless shell as INLINE_TEXT_FRAME imageFill; editable text is owned by HWPX");
            }
            ctx.markObjectPlanMaterialized(shellPlan);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static ASTInlineObject buildTransparentInlineShellImageObject(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ObjectPlan shellPlan,
            double[] shellBounds,
            java.util.List<ResolvedTextFrame> childTfs,
            BufferedImage shellImage) {
        if (ctx == null || shellPlan == null || shellImage == null) return null;
        try {
            double[] bounds = shellPlan.bounds;
            if (!validBounds(bounds)) return null;
            double scale = ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
            double w = Math.abs(bounds[3] - bounds[1]) * scale;
            double h = Math.abs(bounds[2] - bounds[0]) * scale;
            if (w <= 0 || h <= 0) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
            obj.imageData(VisualCropper.encodePng(shellImage));
            obj.imageFormat("png");
            obj.pixelWidth(shellImage.getWidth());
            obj.pixelHeight(shellImage.getHeight());
            obj.width(CoordinateConverter.pointsToHwpunits(w));
            obj.height(CoordinateConverter.pointsToHwpunits(h));
            obj.resolvedWidth(obj.width());
            obj.resolvedHeight(obj.height());
            obj.keepInline(true);
            obj.noAutoLineWrap(childTfs != null
                    && childTfs.size() == 1
                    && shouldUseNoAutoLineWrap(childTfs.get(0), true));
            applyPlannedInlineExecutionHints(obj, shellPlan);

            double[] pageLocal = normalizeInlineBoundsToPageLocal(ctx, shellPlan.pageIndex, bounds);
            if (validBounds(pageLocal)) {
                obj.boundsX(pageLocal[1]);
                obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pageLocal[1] * scale));
                obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pageLocal[0] * scale));
            }

            attachInlineShellChildTextOverlays(ctx, shellPlan.pageIndex, shellBounds, childTfs, obj);
            if (obj.overlayFrames() == null || obj.overlayFrames().isEmpty()) return null;
            for (ResolvedTextFrame childTf : childTfs) {
                markInlineShellChildTextPlaced(ctx, childTf);
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean shouldAttachSeparatedHwpxTextAsOverlay(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (shellPlan == null || childTfs == null || childTfs.isEmpty()) return false;
        if (shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (!extractedShellImageOwnsGeometry(shellPlan)) return false;
        if (!hasHwpxTextOwnershipForChildren(ctx, shellPlan, childTfs)) return false;
        if (visibleShellTextFrameCount(childTfs) > 1) return true;
        return shouldAttachSingleShellTextAsOverlay(ctx, shellPlan, childTfs);
    }

    private static boolean shouldEmbedOwnedCompositeInlineTextShell(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (shellPlan == null || childTfs == null || childTfs.isEmpty()) return false;
        if (shellPlan.placement != Placement.INLINE) return false;
        if (shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (!hasHwpxTextOwnershipForChildren(ctx, shellPlan, childTfs)) return false;
        if (visibleShellTextFrameCount(childTfs) <= 1) return false;
        if (hasMultiLineInlineShellChildText(childTfs)) return false;
        return shouldSqueezeCompositeInlineShellText(childTfs);
    }

    private static boolean hasMultiLineInlineShellChildText(
            java.util.List<ResolvedTextFrame> childTfs) {
        if (childTfs == null) return false;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            String text = childTf.frameVisibleText();
            if (normalizeInlineShellText(text).isEmpty()) continue;
            if (hasMultipleVisibleTextLines(text)) return true;
        }
        return false;
    }

    private static boolean hasMultipleVisibleTextLines(String text) {
        if (text == null || text.isEmpty()) return false;
        int visibleLineCount = 0;
        String[] lines = text.split("[\\r\\n]+");
        for (String line : lines) {
            if (normalizeInlineShellText(line).isEmpty()) continue;
            visibleLineCount++;
            if (visibleLineCount > 1) return true;
        }
        return false;
    }

    private static boolean shouldSqueezeCompositeInlineShellText(
            java.util.List<ResolvedTextFrame> childTfs) {
        if (childTfs == null || childTfs.isEmpty()) return false;
        java.util.List<double[]> sourceLineBounds = new ArrayList<>();
        int visibleCount = 0;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) continue;
            visibleCount++;
            if (!shouldUseNoAutoLineWrap(childTf, true)) return false;
            double[] lineBounds = primaryComposedLineBounds(childTf);
            if (!validBounds(lineBounds)) return false;
            sourceLineBounds.add(lineBounds);
        }
        if (visibleCount < 2 || sourceLineBounds.size() != visibleCount) return false;
        return sourceLinesOccupySingleRow(sourceLineBounds);
    }

    private static double[] primaryComposedLineBounds(ResolvedTextFrame tf) {
        if (tf == null || tf.composedLines() == null || tf.composedLines().isEmpty()) return null;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || !validBounds(line.bounds())) continue;
            String text = normalizeInlineShellText(line.text());
            if (!text.isEmpty()) return line.bounds();
        }
        return null;
    }

    private static boolean sourceLinesOccupySingleRow(java.util.List<double[]> lineBounds) {
        if (lineBounds == null || lineBounds.size() < 2) return false;
        double minCenterY = Double.POSITIVE_INFINITY;
        double maxCenterY = Double.NEGATIVE_INFINITY;
        double maxHeight = 0.0;
        for (double[] b : lineBounds) {
            if (!validBounds(b)) return false;
            double h = Math.abs(b[2] - b[0]);
            if (h <= 0.0) return false;
            double centerY = (b[0] + b[2]) / 2.0;
            minCenterY = Math.min(minCenterY, centerY);
            maxCenterY = Math.max(maxCenterY, centerY);
            maxHeight = Math.max(maxHeight, h);
        }
        return maxCenterY - minCenterY <= Math.max(0.75, maxHeight * 0.55);
    }

    private static boolean shouldAttachSingleShellTextAsOverlay(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (ctx == null || shellPlan == null || childTfs == null) return false;
        ResolvedTextFrame visibleChild = null;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) continue;
            if (visibleChild != null) return false;
            visibleChild = childTf;
        }
        if (visibleChild == null) return false;
        double[] shellBounds = shellPlan.bounds;
        if (!validBounds(shellBounds)) return false;
        double[] shellPageBounds = normalizeShellBoundsToTextFramePageLocal(
                ctx, shellPlan.pageIndex, shellBounds, visibleChild);
        if (!validBounds(shellPageBounds)) shellPageBounds = shellBounds;
        double[] textBounds = normalizeTextFrameBoundsToShellPage(
                ctx, shellPlan.pageIndex, visibleChild, shellPageBounds);
        if (!validBounds(textBounds)) return false;

        double shellW = Math.abs(shellPageBounds[3] - shellPageBounds[1]);
        double shellH = Math.abs(shellPageBounds[2] - shellPageBounds[0]);
        double textW = Math.abs(textBounds[3] - textBounds[1]);
        double textH = Math.abs(textBounds[2] - textBounds[0]);
        if (shellW <= 0.0 || shellH <= 0.0 || textW <= 0.0 || textH <= 0.0) return false;

        double widthRatio = textW / shellW;
        double heightRatio = textH / shellH;
        double shellCenterX = (shellPageBounds[1] + shellPageBounds[3]) / 2.0;
        double shellCenterY = (shellPageBounds[0] + shellPageBounds[2]) / 2.0;
        double textCenterX = (textBounds[1] + textBounds[3]) / 2.0;
        double textCenterY = (textBounds[0] + textBounds[2]) / 2.0;
        double centerDx = Math.abs(textCenterX - shellCenterX) / shellW;
        double centerDy = Math.abs(textCenterY - shellCenterY) / shellH;

        return widthRatio < 0.80 || heightRatio < 0.80 || centerDx > 0.08 || centerDy > 0.08;
    }

    private static boolean embedSingleInlineShellChildText(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] shellBounds,
            java.util.List<ResolvedTextFrame> childTfs,
            ASTInlineObject shellObj) {
        if (ctx == null || shellObj == null || childTfs == null || childTfs.size() != 1) return false;
        ResolvedTextFrame childTf = childTfs.get(0);
        if (childTf == null || isOrcCarrierTextFrame(childTf)) return false;
        if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) return false;
        if (!applySingleInlineShellChildTextMargins(ctx, pageIndex, shellBounds, childTf, shellObj)) return false;

        shellObj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
        shellObj.verticalJustification(childTf.verticalJustification() != null
                ? childTf.verticalJustification()
                : "CenterAlign");
        buildBadgeParagraph(ctx, childTf, shellObj);
        return shellObj.paragraphs() != null && !shellObj.paragraphs().isEmpty();
    }

    private static boolean applySingleInlineShellChildTextMargins(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] shellBounds,
            ResolvedTextFrame childTf,
            ASTInlineObject shellObj) {
        if (ctx == null || childTf == null || shellObj == null || !validBounds(shellBounds)) return false;
        // 단위 정합 우선: plan bounds 는 추출기 원단위(mm), TF geometricBounds 는
        // normalizeToPoints 로 pt 다. plan bounds 를 pt 로 환산해 TF geometric 과
        // 같은 공간에서 직접 차분한다. 기존 page-local 정규화는 pt/mm 를 섞어
        // 셀보다 큰 유령 여백(62mm cellMargin)을 만든다 (SPEC-057, p47 라벨).
        double unitScale = ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        double[] tfGeometric = childTf.geometricBounds();
        double[] shellPageBounds;
        double[] tb;
        boolean boundsInPoints = false;
        if (boundsShareCoordinateScale(shellBounds, tfGeometric)) {
            shellPageBounds = shellBounds;
            tb = tfGeometric;
            boundsInPoints = true;
        } else {
        double[] shellBoundsPt = new double[] {
                shellBounds[0] * unitScale, shellBounds[1] * unitScale,
                shellBounds[2] * unitScale, shellBounds[3] * unitScale
        };
        if (validBounds(tfGeometric) && containsBounds(shellBoundsPt, tfGeometric)) {
            shellPageBounds = shellBoundsPt;
            tb = tfGeometric;
            boundsInPoints = true;
        } else {
            shellPageBounds = normalizeShellBoundsToTextFramePageLocal(ctx, pageIndex, shellBounds, childTf);
            if (!validBounds(shellPageBounds)) shellPageBounds = shellBounds;
            tb = normalizeTextFrameBoundsToShellPage(ctx, pageIndex, childTf, shellPageBounds);
        }
        }
        if (!validBounds(tb) || !validBounds(shellPageBounds)) return false;

        double shellW = Math.abs(shellPageBounds[3] - shellPageBounds[1]);
        double shellH = Math.abs(shellPageBounds[2] - shellPageBounds[0]);
        double textW = Math.abs(tb[3] - tb[1]);
        double textH = Math.abs(tb[2] - tb[0]);
        if (shellW <= 0.0 || shellH <= 0.0 || textW <= 0.0 || textH <= 0.0) return false;
        if (textW > shellW * 1.25 || textH > shellH * 1.25) return false;

        double scale = boundsInPoints ? 1.0 : (ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0);
        double left = Math.max(0.0, tb[1] - shellPageBounds[1]);
        double top = Math.max(0.0, tb[0] - shellPageBounds[0]);
        double right = Math.max(0.0, shellPageBounds[3] - tb[3]);
        double bottom = Math.max(0.0, shellPageBounds[2] - tb[2]);

        double[] inset = childTf.insetSpacing();
        if (inset != null && inset.length >= 4) {
            double[] insetInBoundsUnits = textFrameInsetInBoundsUnits(ctx, childTf, tb);
            top += insetInBoundsUnits[0];
            left += insetInBoundsUnits[1];
            bottom += insetInBoundsUnits[2];
            right += insetInBoundsUnits[3];
        }

        shellObj.textMarginLeft(CoordinateConverter.pointsToHwpunits(left * scale));
        shellObj.textMarginTop(CoordinateConverter.pointsToHwpunits(top * scale));
        shellObj.textMarginRight(CoordinateConverter.pointsToHwpunits(right * scale));
        shellObj.textMarginBottom(CoordinateConverter.pointsToHwpunits(bottom * scale));
        return true;
    }

    private static void attachInlineShellChildTextOverlays(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] shellBounds,
            java.util.List<ResolvedTextFrame> childTfs,
            ASTInlineObject shellObj) {
        if (ctx == null || shellObj == null || childTfs == null || childTfs.isEmpty()) return;
        if (!validBounds(shellBounds)) return;
        for (ResolvedTextFrame childTf : childTfs) {
            ASTInlineObject overlay = buildInlineShellChildTextOverlay(ctx, pageIndex, shellBounds, childTf, shellObj);
            if (overlay != null) shellObj.addOverlayFrame(overlay);
        }
    }

    private static ASTInlineObject buildInlineShellChildTextOverlay(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] shellBounds,
            ResolvedTextFrame childTf,
            ASTInlineObject shellObj) {
        if (ctx == null || childTf == null || shellObj == null) return null;
        if (isOrcCarrierTextFrame(childTf)) return null;
        String text = normalizeInlineShellText(childTf.frameVisibleText());
        if (text.isEmpty()) return null;
        double[] shellPageBounds = normalizeShellBoundsToTextFramePageLocal(ctx, pageIndex, shellBounds, childTf);
        if (!validBounds(shellPageBounds)) shellPageBounds = shellBounds;
        double[] tb = normalizeTextFrameBoundsToShellPage(ctx, pageIndex, childTf, shellPageBounds);
        if (!validBounds(tb) || !validBounds(shellPageBounds)) return null;

        double scale = ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        double textW = Math.abs(tb[3] - tb[1]) * scale;
        double textH = Math.abs(tb[2] - tb[0]) * scale;
        if (textW <= 0 || textH <= 0) return null;

        ASTInlineObject overlay = new ASTInlineObject();
        overlay.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        overlay.sourceId("u" + childTf.id());
        overlay.width(CoordinateConverter.pointsToHwpunits(textW));
        overlay.height(CoordinateConverter.pointsToHwpunits(textH));
        overlay.keepInline(false);
        overlay.isOverlay(true);
        overlay.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
        overlay.verticalJustification(childTf.verticalJustification() != null
                ? childTf.verticalJustification()
                : "CenterAlign");
        applyInlineShellOverlayTextInsets(childTf, overlay);

        long relX = CoordinateConverter.pointsToHwpunits((tb[1] - shellPageBounds[1]) * scale);
        long relY = CoordinateConverter.pointsToHwpunits((tb[0] - shellPageBounds[0]) * scale);
        overlay.overlayX(relX);
        overlay.overlayY(relY);
        overlay.overlayParentWidth(shellObj.width());
        overlay.overlayParentHeight(shellObj.height());
        double[] pageLocal = normalizeSpreadBoundsToPageLocal(ctx, pageIndex, tb);
        overlay.resolvedPageX(CoordinateConverter.pointsToHwpunits(pageLocal[1] * scale));
        overlay.resolvedPageY(CoordinateConverter.pointsToHwpunits(pageLocal[0] * scale));
        overlay.resolvedWidth(overlay.width());
        overlay.resolvedHeight(overlay.height());

        buildInlineShellOverlayParagraph(ctx, childTf, overlay);
        return overlay.paragraphs() == null || overlay.paragraphs().isEmpty() ? null : overlay;
    }

    private static void buildInlineShellOverlayParagraph(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf,
            ASTInlineObject overlay) {
        if (childTf == null || overlay == null) return;
        List<ASTParagraph> paragraphs = buildSourceStructuredShellTextParagraphs(ctx, childTf);
        if (paragraphs == null || paragraphs.isEmpty()) {
            paragraphs = buildSyntheticShellTextParagraphs(ctx, childTf);
        }
        if (paragraphs == null || paragraphs.isEmpty()) return;
        fitSingleLineInlineTextShellBoxToComposedLine(paragraphs, overlay, childTf, ctx);
        capInlineShellParagraphLeadingToFrame(paragraphs, overlay, childTf, ctx);
        for (ASTParagraph paragraph : paragraphs) {
            overlay.addParagraph(paragraph);
        }
    }

    private static void applyInlineShellOverlayTextInsets(
            ResolvedTextFrame childTf,
            ASTInlineObject overlay) {
        if (childTf == null || overlay == null) return;
        double[] inset = childTf.insetSpacing();
        if (inset == null || inset.length < 4) return;
        overlay.textMarginTop(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[0])));
        overlay.textMarginLeft(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[1])));
        overlay.textMarginBottom(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[2])));
        overlay.textMarginRight(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[3])));
    }

    private static boolean extractedShellImageOwnsGeometry(ObjectPlan plan) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.file == null || plan.file.isBlank()) return false;
        return plan.materialization == Materialization.EXTRACTED_PNG_VECTOR
                || plan.materialization == Materialization.TEXTLESS_VISUAL_FRAGMENT;
    }

    private static boolean canUseNativeInlineTextFrameShell(
            ObjectPlan plan,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (childTfs == null || childTfs.size() != 1) return false;
        ResolvedTextFrame tf = childTfs.get(0);
        if (tf == null) return false;
        int[] ownedIds = plan.ownedTextFrameIds;
        int[] sourceIds = plan.sourceObjectIds;
        if (ownedIds == null || ownedIds.length != 1 || sourceIds == null || sourceIds.length != 1) return false;
        String domId = tf.id();
        if (domId == null) return false;
        if (!domId.equals(String.valueOf(ownedIds[0])) || !domId.equals(String.valueOf(sourceIds[0]))) {
            return false;
        }
        return true;
    }

    private static void applyPlannedInlineExecutionHints(ASTInlineObject obj, ObjectPlan plan) {
        if (obj == null || plan == null) return;
        obj.plannedZOrder(plan.zOrder);
        if (plan.visualLayer != null) {
            obj.plannedVisualLayer(plan.visualLayer.name());
        }
    }

    private static String[] editableTextFrameIds(ResolvedBuildContext ctx, RenderedGroup shell) {
        if (shell == null) return new String[0];
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(shell) : null;
        if (plan != null) {
            if (ShellRole.isTextShell(plan)
                    && plan.ownedTextFrameIds != null
                    && plan.ownedTextFrameIds.length > 0
                    && (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    || plan.visualAction == VisualAction.DROP_VISUAL
                    || plan.hasVisibleVisual())
                    && hasHwpxTextOwnershipForOwnedTextFrameIds(ctx, plan)) {
                return ownedTextFrameIds(plan);
            }
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) {
                return new String[0];
            }
            return ownedTextFrameIds(plan);
        }
        if (hasStage1ObjectPlans(ctx)) {
            return new String[0];
        }
        if (!shell.hasEditableTextHiddenFromPng()) {
            return new String[0];
        }
        return renderedEditableTextFrameIds(shell);
    }

    private static String[] ownedTextFrameIds(ObjectPlan plan) {
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return new String[0];
        }
        String[] out = new String[plan.ownedTextFrameIds.length];
        for (int i = 0; i < plan.ownedTextFrameIds.length; i++) {
            out[i] = String.valueOf(plan.ownedTextFrameIds[i]);
        }
        return out;
    }

    private static String[] renderedEditableTextFrameIds(RenderedGroup shell) {
        if (shell == null) return new String[0];
        String[] direct = shell.editableTextFrameIds();
        if (direct != null && direct.length > 0) return direct;
        int[] atomic = shell.atomicOwnedTextFrameIds();
        if (atomic == null || atomic.length == 0) return new String[0];
        String[] out = new String[atomic.length];
        for (int i = 0; i < atomic.length; i++) out[i] = String.valueOf(atomic[i]);
        return out;
    }

    private static int visibleShellTextFrameCount(java.util.List<ResolvedTextFrame> childTfs) {
        if (childTfs == null) return 0;
        int count = 0;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            String text = normalizeInlineShellText(childTf.frameVisibleText());
            if (!text.isEmpty()) count++;
        }
        return count;
    }

    private static void buildCompositeInlineShellParagraph(
            ResolvedBuildContext ctx,
            java.util.List<ResolvedTextFrame> childTfs,
            ASTInlineObject obj) {
        if (ctx == null || obj == null || childTfs == null || childTfs.isEmpty()) return;
        java.util.List<ResolvedTextFrame> ordered = new ArrayList<>(childTfs);
        java.util.Collections.sort(ordered, new java.util.Comparator<ResolvedTextFrame>() {
            public int compare(ResolvedTextFrame a, ResolvedTextFrame b) {
                double[] ga = a != null ? a.geometricBounds() : null;
                double[] gb = b != null ? b.geometricBounds() : null;
                double ay = ga != null && ga.length >= 4 ? ga[0] : 0;
                double by = gb != null && gb.length >= 4 ? gb[0] : 0;
                if (Math.abs(ay - by) > 1.0) return Double.compare(ay, by);
                double ax = ga != null && ga.length >= 4 ? ga[1] : 0;
                double bx = gb != null && gb.length >= 4 ? gb[1] : 0;
                return Double.compare(ax, bx);
            }
        });
        if (!compositeInlineShellChildrenOccupySingleRow(ctx, ordered)) {
            boolean addedStructured = false;
            for (ResolvedTextFrame childTf : ordered) {
                if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
                if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) continue;
                List<ASTParagraph> paragraphs = buildSourceStructuredShellTextParagraphs(ctx, childTf);
                if (paragraphs == null || paragraphs.isEmpty()) {
                    paragraphs = buildSyntheticShellTextParagraphs(ctx, childTf);
                }
                for (ASTParagraph paragraph : paragraphs) {
                    if (paragraph == null || paragraph.items() == null || paragraph.items().isEmpty()) continue;
                    obj.addParagraph(paragraph);
                    addedStructured = true;
                }
            }
            if (addedStructured) return;
        }
        ASTParagraph para = new ASTParagraph();
        para.alignment("LEFT_JUSTIFIED");
        boolean added = false;
        for (ResolvedTextFrame childTf : ordered) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            String text = normalizeInlineShellText(childTf.frameVisibleText());
            if (text.isEmpty()) continue;
            if (added) {
                ASTTextRun spacer = new ASTTextRun();
                spacer.text(" ");
                para.addItem(spacer);
            }
            addSyntheticRunsFromTextFrame(ctx, para, childTf, text);
            added = true;
        }
        applyCompositeInlineShellLineMetrics(ctx, para, ordered);
        if (added) {
            postprocessShellTextParagraph(ctx, para);
            obj.addParagraph(para);
        }
    }

    private static boolean compositeInlineShellChildrenOccupySingleRow(
            ResolvedBuildContext ctx,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (childTfs == null || childTfs.isEmpty()) return true;
        java.util.List<double[]> sourceLineBounds = new ArrayList<>();
        int visibleCount = 0;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) continue;
            if (sourceTextFrameHasMultipleVisibleRows(ctx, childTf)) return false;
            visibleCount++;
            double[] lineBounds = primaryComposedLineBounds(childTf);
            if (!validBounds(lineBounds)) return false;
            sourceLineBounds.add(lineBounds);
        }
        return visibleCount <= 1
                || (sourceLineBounds.size() == visibleCount
                && sourceLinesOccupySingleRow(sourceLineBounds));
    }

    private static boolean sourceTextFrameHasMultipleVisibleRows(
            ResolvedBuildContext ctx,
            ResolvedTextFrame textFrame) {
        if (textFrame == null) return false;
        if (textFrame.lineCount() > 1) return true;
        if (textFrame.frameParaTexts() != null) {
            int visibleParagraphs = 0;
            for (String text : textFrame.frameParaTexts()) {
                if (normalizeInlineShellText(text).isEmpty()) continue;
                visibleParagraphs++;
                if (visibleParagraphs > 1) return true;
            }
        }
        if (ctx == null || ctx.resolvedData == null || textFrame.storyId() == null) return false;
        ResolvedStory story = ctx.resolvedData.getStory(textFrame.storyId());
        if (story == null || story.paragraphs() == null) return false;
        int visibleParagraphs = 0;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            boolean hasText = false;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                if (!normalizeInlineShellText(run.text()).isEmpty()) {
                    hasText = true;
                    break;
                }
            }
            if (!hasText) continue;
            visibleParagraphs++;
            if (visibleParagraphs > 1) return true;
        }
        return false;
    }

    private static void applyCompositeInlineShellLineMetrics(
            ResolvedBuildContext ctx,
            ASTParagraph para,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (ctx == null || para == null || childTfs == null || childTfs.isEmpty()) return;
        double[] textUnion = null;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) continue;
            double[] b = childTf.geometricBounds();
            if (!validBounds(b)) continue;
            if (textUnion == null) {
                textUnion = new double[] { b[0], b[1], b[2], b[3] };
            } else {
                textUnion[0] = Math.min(textUnion[0], b[0]);
                textUnion[1] = Math.min(textUnion[1], b[1]);
                textUnion[2] = Math.max(textUnion[2], b[2]);
                textUnion[3] = Math.max(textUnion[3], b[3]);
            }
        }
        if (!validBounds(textUnion)) return;
        double sourceLineHeight = Math.abs(textUnion[2] - textUnion[0]);
        if (sourceLineHeight <= 0 || !Double.isFinite(sourceLineHeight)) return;
        int lineHeight = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, CoordinateConverter.pointsToHwpunits(sourceLineHeight)));
        para.lineSpacing(lineHeight);
        para.lineSpacingType("fixed");
        para.spaceBefore(0L);
        para.spaceAfter(0L);
    }

    private static boolean appendFirstVisibleShellStoryRuns(
            ResolvedBuildContext ctx,
            ASTParagraph target,
            ResolvedStory story) {
        if (ctx == null || target == null || story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph rp : story.paragraphs()) {
            if (rp == null || rp.runs() == null) continue;
            boolean appended = false;
            for (ResolvedRun run : rp.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                String text = normalizeInlineShellText(run.text());
                if (text.isEmpty()) continue;
                List<ASTTextRun> runs = ResolvedTextFlowAstConverter.convertRunText(
                        text,
                        run,
                        target,
                        ResolvedTextFlowAstConverter.options()
                                .colorResolver(color -> RunBuilder.resolveColorToHex(ctx, color))
                                .truncateAtParagraphBreak(false));
                for (ASTTextRun astRun : runs) {
                    target.addItem(astRun);
                }
                appended = true;
            }
            if (appended) return true;
        }
        return false;
    }

    private static void markInlineShellChildTextPlaced(ResolvedBuildContext ctx, ResolvedTextFrame childTf) {
        if (ctx == null || childTf == null) return;
        try {
            ctx.setTextDisposition(Integer.parseInt(childTf.id()), FrameDisposition.TEXT_BLOCK_PLACED);
        } catch (Exception ignored) {
            // Non-numeric DOM ids cannot be recorded in the legacy disposition map.
        }
    }

    private static String normalizeInlineShellText(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
    }

    private static String firstChildVerticalJustification(java.util.List<ResolvedTextFrame> childTfs) {
        if (childTfs != null) {
            for (ResolvedTextFrame childTf : childTfs) {
                if (childTf != null && childTf.verticalJustification() != null
                        && !childTf.verticalJustification().isBlank()) {
                    return childTf.verticalJustification();
                }
            }
        }
        return "TopAlign";
    }

    private static String inlineTextShellVerticalJustification(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (isSimpleInlineTextShellLabel(ctx, shellPlan, anchorItem, childTfs)) {
            return "CenterAlign";
        }
        return firstChildVerticalJustification(childTfs);
    }

    private static boolean isSimpleInlineTextShellLabel(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (shellPlan == null || childTfs == null || childTfs.size() != 1) return false;
        if (shellPlan.placement != Placement.INLINE) return false;
        if (!ShellRole.isTextShell(shellPlan)) return false;
        ResolvedTextFrame childTf = childTfs.get(0);
        if (childTf == null) return false;
        if (shellPlan.visualLayer != VisualLayer.LABEL_BACKDROP) return false;
        double[] groupBounds = inlineShellMarginReferenceBoundsInTextFramePageLocal(
                ctx, shellPlan, anchorItem, childTf);
        double[] textBounds = childTf.pageRelativeBounds();
        if (!validBounds(textBounds)) {
            textBounds = normalizeTextFrameBoundsToShellPage(
                    ctx,
                    shellPlan != null ? shellPlan.pageIndex : childTf.pageIndex(),
                    childTf,
                    groupBounds);
        }
        if (!validBounds(groupBounds) || !validBounds(textBounds)) return false;
        if (!containsBounds(groupBounds, textBounds)) return false;
        double groupH = Math.abs(groupBounds[2] - groupBounds[0]);
        double textH = Math.abs(textBounds[2] - textBounds[0]);
        if (groupH <= 0 || textH <= 0 || textH > groupH * 1.25) return false;
        double top = Math.max(0, textBounds[0] - groupBounds[0]);
        double bottom = Math.max(0, groupBounds[2] - textBounds[2]);
        double tolerance = Math.max(0.35, groupH * 0.12);
        return Math.abs(top - bottom) <= tolerance;
    }

    private static void applyInlineShellTextMargins(
            ResolvedBuildContext ctx,
            ASTInlineObject obj,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (obj == null || childTfs == null || childTfs.size() != 1) return;
        ResolvedTextFrame childTf = childTfs.get(0);
        if (childTf == null) return;
        double[] groupBounds;
        double[] textBounds;
        boolean boundsInPoints = false;
        double[] directGroupBounds = inlineShellMarginReferenceBounds(ctx, shellPlan, anchorItem, childTf);
        double[] tfGeometric = childTf.geometricBounds();
        if (boundsShareCoordinateScale(directGroupBounds, tfGeometric)) {
            groupBounds = directGroupBounds;
            textBounds = tfGeometric;
            boundsInPoints = true;
        } else {
            groupBounds = inlineShellMarginReferenceBoundsInTextFramePageLocal(
                    ctx, shellPlan, anchorItem, childTf);
            textBounds = normalizeTextFrameContentBoundsToShellPage(
                    ctx,
                    shellPlan != null ? shellPlan.pageIndex : childTf.pageIndex(),
                    childTf,
                    groupBounds);
        }
        if (groupBounds == null || textBounds == null || groupBounds.length < 4 || textBounds.length < 4) {
            return;
        }
        double groupW = Math.abs(groupBounds[3] - groupBounds[1]);
        double groupH = Math.abs(groupBounds[2] - groupBounds[0]);
        double textW = Math.abs(textBounds[3] - textBounds[1]);
        double textH = Math.abs(textBounds[2] - textBounds[0]);
        if (groupW <= 0 || groupH <= 0 || textW <= 0 || textH <= 0) return;
        if (textW > groupW * 1.25 || textH > groupH * 1.25) return;

        if (shellPlanTextFrameOwnsItsShell(shellPlan, childTf)) {
            double[] inset = childTf.insetSpacing();
            if (inset != null && inset.length >= 4) {
                obj.textMarginTop(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[0])));
                obj.textMarginLeft(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[1])));
                obj.textMarginBottom(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[2])));
                obj.textMarginRight(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[3])));
            } else {
                obj.textMarginTop(0L);
                obj.textMarginLeft(0L);
                obj.textMarginBottom(0L);
                obj.textMarginRight(0L);
            }
            return;
        }

        double left = Math.max(0, textBounds[1] - groupBounds[1]);
        double top = Math.max(0, textBounds[0] - groupBounds[0]);
        double right = Math.max(0, groupBounds[3] - textBounds[3]);
        double bottom = Math.max(0, groupBounds[2] - textBounds[2]);
        if (left + top + right + bottom < 0.1) return;

        double scale = boundsInPoints ? 1.0 : (ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0);
        obj.textMarginLeft(CoordinateConverter.pointsToHwpunits(left * scale));
        obj.textMarginTop(CoordinateConverter.pointsToHwpunits(top * scale));
        obj.textMarginRight(CoordinateConverter.pointsToHwpunits(right * scale));
        obj.textMarginBottom(CoordinateConverter.pointsToHwpunits(bottom * scale));
    }

    private static boolean shellPlanTextFrameOwnsItsShell(ObjectPlan shellPlan, ResolvedTextFrame childTf) {
        if (shellPlan == null || childTf == null) return false;
        int textFrameId = parseIntOrDefault(childTf.id(), -1);
        if (textFrameId < 0) return false;
        return containsInt(shellPlan.ownedTextFrameIds, textFrameId)
                && (containsInt(shellPlan.visualSourceObjectIds, textFrameId)
                || containsInt(shellPlan.exportSourceObjectIds, textFrameId)
                || containsInt(shellPlan.styleSourceObjectIds, textFrameId));
    }

    private static void applyCompositeInlineShellTextMargins(
            ResolvedBuildContext ctx,
            ASTInlineObject obj,
            int pageIndex,
            double[] shellBounds,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (ctx == null || obj == null || !validBounds(shellBounds) || childTfs == null || childTfs.isEmpty()) return;
        double[] textUnion = null;
        double[] shellPageBounds = null;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || isOrcCarrierTextFrame(childTf)) continue;
            if (normalizeInlineShellText(childTf.frameVisibleText()).isEmpty()) continue;
            if (!validBounds(shellPageBounds)) {
                shellPageBounds = normalizeShellBoundsToTextFramePageLocal(ctx, pageIndex, shellBounds, childTf);
                if (!validBounds(shellPageBounds)) shellPageBounds = shellBounds;
            }
            double[] b = normalizeTextFrameContentBoundsToShellPage(ctx, pageIndex, childTf, shellPageBounds);
            if (!validBounds(b)) continue;
            if (textUnion == null) {
                textUnion = new double[] { b[0], b[1], b[2], b[3] };
            } else {
                textUnion[0] = Math.min(textUnion[0], b[0]);
                textUnion[1] = Math.min(textUnion[1], b[1]);
                textUnion[2] = Math.max(textUnion[2], b[2]);
                textUnion[3] = Math.max(textUnion[3], b[3]);
            }
        }
        if (!validBounds(textUnion) || !validBounds(shellPageBounds)) return;
        double shellW = Math.abs(shellPageBounds[3] - shellPageBounds[1]);
        double shellH = Math.abs(shellPageBounds[2] - shellPageBounds[0]);
        double textW = Math.abs(textUnion[3] - textUnion[1]);
        double textH = Math.abs(textUnion[2] - textUnion[0]);
        if (shellW <= 0 || shellH <= 0 || textW <= 0 || textH <= 0) return;
        if (textW > shellW * 1.25 || textH > shellH * 1.25) return;

        double left = Math.max(0, textUnion[1] - shellPageBounds[1]);
        double top = Math.max(0, textUnion[0] - shellPageBounds[0]);
        double right = Math.max(0, shellPageBounds[3] - textUnion[3]);
        double bottom = Math.max(0, shellPageBounds[2] - textUnion[2]);
        if (left + top + right + bottom < 0.1) return;

        double scale = ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        obj.textMarginLeft(CoordinateConverter.pointsToHwpunits(left * scale));
        obj.textMarginTop(CoordinateConverter.pointsToHwpunits(top * scale));
        obj.textMarginRight(CoordinateConverter.pointsToHwpunits(right * scale));
        obj.textMarginBottom(CoordinateConverter.pointsToHwpunits(bottom * scale));
    }

    private static double[] normalizeTextFrameContentBoundsToShellPage(
            ResolvedBuildContext ctx,
            int pageIndex,
            ResolvedTextFrame tf,
            double[] shellBounds) {
        double[] b = normalizeTextFrameBoundsToShellPage(ctx, pageIndex, tf, shellBounds);
        if (!validBounds(b) || tf == null) return b;
        double[] inset = tf.insetSpacing();
        if (inset == null || inset.length < 4) return b;
        double[] insetInBoundsUnits = textFrameInsetInBoundsUnits(ctx, tf, b);
        double top = insetInBoundsUnits[0];
        double left = insetInBoundsUnits[1];
        double bottom = insetInBoundsUnits[2];
        double right = insetInBoundsUnits[3];
        double contentTop = b[0] + top;
        double contentLeft = b[1] + left;
        double contentBottom = b[2] - bottom;
        double contentRight = b[3] - right;
        if (contentBottom <= contentTop || contentRight <= contentLeft) return b;
        return new double[] { contentTop, contentLeft, contentBottom, contentRight };
    }

    private static double[] textFrameInsetInBoundsUnits(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            double[] bounds) {
        double[] inset = tf != null ? tf.insetSpacing() : null;
        if (inset == null || inset.length < 4) return new double[] {0.0, 0.0, 0.0, 0.0};
        double unitScale = textFrameBoundsUseUnscaledPageRelativeUnits(ctx, tf, bounds)
                ? 1.0 / Math.max(0.000001, ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0)
                : 1.0;
        return new double[] {
                Math.max(0.0, inset[0]) * unitScale,
                Math.max(0.0, inset[1]) * unitScale,
                Math.max(0.0, inset[2]) * unitScale,
                Math.max(0.0, inset[3]) * unitScale
        };
    }

    private static boolean textFrameBoundsUseUnscaledPageRelativeUnits(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            double[] bounds) {
        if (ctx == null || ctx.scaleFactor <= 0 || tf == null || !validBounds(bounds)) return false;
        double[] pageRel = tf.pageRelativeBounds();
        if (!validBounds(pageRel)) return false;
        double tolerance = 0.5;
        return Math.abs(bounds[0] - pageRel[0]) <= tolerance
                && Math.abs(bounds[1] - pageRel[1]) <= tolerance
                && Math.abs(bounds[2] - pageRel[2]) <= tolerance
                && Math.abs(bounds[3] - pageRel[3]) <= tolerance;
    }

    private static double[] scaleBounds(double[] bounds, double scale) {
        if (!validBounds(bounds) || scale == 0.0 || !Double.isFinite(scale)) return bounds;
        return new double[] {
                bounds[0] * scale,
                bounds[1] * scale,
                bounds[2] * scale,
                bounds[3] * scale
        };
    }

    private static double[] inlineShellMarginReferenceBounds(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem,
            ResolvedTextFrame childTf) {
        double[] visualBounds = inlineShellVisualLeafBounds(ctx, shellPlan, childTf);
        if (validBounds(visualBounds)) return visualBounds;
        // 셸 plan 자체 bounds 가 라벨 텍스트를 포함하면 그것이 여백 기준이다.
        // anchor(조상 그룹)를 먼저 쓰면 라벨 크기 셸에 그룹 기준 오프셋(수십 mm)이
        // 여백으로 들어가 셀보다 큰 cellMargin 이 생긴다 (SPEC-057, p47 라벨 회색 바).
        if (shellPlan != null && validBounds(shellPlan.bounds)
                && containsBounds(shellPlan.bounds, childTf.geometricBounds())) {
            return shellPlan.bounds;
        }
        if (anchorItem != null && validBounds(anchorItem.geometricBounds())
                && containsBounds(anchorItem.geometricBounds(), childTf.geometricBounds())) {
            return anchorItem.geometricBounds();
        }
        if (shellPlan != null && validBounds(shellPlan.bounds)) {
            return shellPlan.bounds;
        }
        return null;
    }

    private static double[] inlineShellMarginReferenceBoundsInTextFramePageLocal(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem,
            ResolvedTextFrame childTf) {
        double[] bounds = inlineShellMarginReferenceBounds(ctx, shellPlan, anchorItem, childTf);
        if (!validBounds(bounds)) return bounds;
        int pageIndex = shellPlan != null ? shellPlan.pageIndex : childTf.pageIndex();
        return normalizeBoundsToTextFramePageLocal(ctx, pageIndex, bounds, childTf);
    }

    private static double[] normalizeBoundsToTextFramePageLocal(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] bounds,
            ResolvedTextFrame tf) {
        if (!validBounds(bounds) || tf == null) return bounds;
        if (boundsAreAlreadyPageLocal(ctx, pageIndex, bounds)) return bounds;
        double[] pageRelative = tf.pageRelativeBounds();
        if (!validBounds(pageRelative)) return bounds;
        if (containsBounds(bounds, pageRelative)) return bounds;

        double[] geometric = tf.geometricBounds();
        if (!validBounds(geometric)) return bounds;
        double dx = geometric[1] - pageRelative[1];
        double dy = geometric[0] - pageRelative[0];
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return bounds;
        if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return bounds;

        double[] shifted = new double[] {
                bounds[0] - dy,
                bounds[1] - dx,
                bounds[2] - dy,
                bounds[3] - dx
        };
        if (containsBounds(shifted, pageRelative)) return shifted;
        return bounds;
    }

    private static double[] inlineShellVisualLeafBounds(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedTextFrame childTf) {
        if (ctx == null || ctx.resolvedData == null || shellPlan == null || childTf == null) return null;
        double[] textBounds = childTf.geometricBounds();
        if (!validBounds(textBounds)) return null;
        double bestArea = Double.MAX_VALUE;
        double[] best = null;
        int[] sourceIds = shellPlan.visualSourceObjectIds != null && shellPlan.visualSourceObjectIds.length > 0
                ? shellPlan.visualSourceObjectIds
                : shellPlan.sourceObjectIds;
        if (sourceIds == null) return null;
        for (int sourceId : sourceIds) {
            if (String.valueOf(sourceId).equals(childTf.id())) continue;
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (!isSimpleNativeInlineShellShape(item)) continue;
            double[] bounds = item.geometricBounds();
            if (!containsBounds(bounds, textBounds)) continue;
            double area = boundsArea(bounds);
            if (area > 0 && area < bestArea) {
                bestArea = area;
                best = bounds;
            }
        }
        return best;
    }

    private static boolean isOrcCarrierTextFrame(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String visibleText = tf.frameVisibleText();
        if (!hasObjectReplacementText(visibleText)) return false;
        // A child text frame inside an inline shell may legitimately contain ORC markers
        // between visible text runs. Only treat the frame as a pure ORC carrier when,
        // after removing ORCs and whitespace controls, nothing meaningful remains.
        return normalizeInlineShellText(visibleText).isEmpty();
    }

    private static ObjectPlan findInlineTextShellOwnerPlan(
            ResolvedBuildContext ctx,
            RenderedGroup shell,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (ctx == null || shell == null) return null;
        ObjectPlan direct = ctx.findOwnershipPlanForRendered(shell);
        if (isInlineTextShellOwnerForChildren(ctx, direct, shell, childTfs)) return direct;
        if (ctx.ownershipPlans == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (isInlineTextShellOwnerForChildren(ctx, plan, shell, childTfs)) return plan;
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

    private static boolean boundsShareCoordinateScale(double[] a, double[] b) {
        if (!validBounds(a) || !validBounds(b)) return false;
        double aw = Math.abs(a[3] - a[1]);
        double ah = Math.abs(a[2] - a[0]);
        double bw = Math.abs(b[3] - b[1]);
        double bh = Math.abs(b[2] - b[0]);
        if (aw <= 0.0 || ah <= 0.0 || bw <= 0.0 || bh <= 0.0) return false;
        if (containsBounds(a, b) || containsBounds(b, a)) return true;
        double acx = (a[1] + a[3]) / 2.0;
        double acy = (a[0] + a[2]) / 2.0;
        double bcx = (b[1] + b[3]) / 2.0;
        double bcy = (b[0] + b[2]) / 2.0;
        double dx = Math.abs(acx - bcx);
        double dy = Math.abs(acy - bcy);
        double maxW = Math.max(aw, bw);
        double maxH = Math.max(ah, bh);
        double sizeRatioW = Math.max(aw, bw) / Math.max(0.000001, Math.min(aw, bw));
        double sizeRatioH = Math.max(ah, bh) / Math.max(0.000001, Math.min(ah, bh));
        return dx <= Math.max(1.5, maxW * 0.75)
                && dy <= Math.max(1.5, maxH * 0.75)
                && sizeRatioW <= 2.0
                && sizeRatioH <= 2.0;
    }

    private static double boundsArea(double[] bounds) {
        if (bounds == null || bounds.length < 4) return -1;
        double w = Math.abs(bounds[3] - bounds[1]);
        double h = Math.abs(bounds[2] - bounds[0]);
        return w * h;
    }

    private static boolean isInlineTextShellOwnerForChildren(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup shell,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (plan == null || shell == null || childTfs == null || childTfs.isEmpty()) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (!ShellRole.isTextShell(plan)) return false;
        if (!isExecutableTextlessShellCarrier(plan, shell)) return false;
        if (plan.domId != shell.id() && !containsInt(plan.sourceObjectIds, shell.id())) return false;
        if (!hasHwpxTextOwnershipForChildren(ctx, plan, childTfs)) return false;
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

    private static boolean hasHwpxTextOwnershipForChildren(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            java.util.List<ResolvedTextFrame> childTfs) {
        if (shellPlan == null || childTfs == null || childTfs.isEmpty()) return false;
        if (shellPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return true;
        if (isShellPlanWithOwnedTextFrameChannel(shellPlan)) {
            for (ResolvedTextFrame childTf : childTfs) {
                if (childTf == null || childTf.id() == null) return false;
                int childId;
                try {
                    childId = Integer.parseInt(childTf.id());
                } catch (NumberFormatException e) {
                    return false;
                }
                if (!containsInt(shellPlan.ownedTextFrameIds, childId)) return false;
            }
            return true;
        }
        if (ctx == null || ctx.ownershipPlans == null) return false;
        for (ResolvedTextFrame childTf : childTfs) {
            if (childTf == null || childTf.id() == null) return false;
            int childId;
            try {
                childId = Integer.parseInt(childTf.id());
            } catch (NumberFormatException e) {
                return false;
            }
            if (!hasHwpxTextOwnershipForTextFrame(ctx, childId)) return false;
        }
        return true;
    }

    private static boolean hasHwpxTextOwnershipForOwnedTextFrameIds(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan) {
        if (shellPlan == null || shellPlan.ownedTextFrameIds == null
                || shellPlan.ownedTextFrameIds.length == 0) {
            return false;
        }
        if (shellPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT) return true;
        if (isShellPlanWithOwnedTextFrameChannel(shellPlan)) return true;
        if (ctx == null || ctx.ownershipPlans == null) return false;
        for (int textFrameDomId : shellPlan.ownedTextFrameIds) {
            if (!hasHwpxTextOwnershipForTextFrame(ctx, textFrameDomId)) return false;
        }
        return true;
    }

    private static boolean isShellPlanWithOwnedHwpxText(
            ResolvedBuildContext ctx,
            ObjectPlan plan) {
        return plan != null
                && ShellRole.isTextShell(plan)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0
                && hasHwpxTextOwnershipForOwnedTextFrameIds(ctx, plan);
    }

    private static boolean isShellPlanWithOwnedTextFrameChannel(ObjectPlan plan) {
        return plan != null
                && ShellRole.isTextShell(plan)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL;
    }

    private static boolean hasHwpxTextOwnershipForTextFrame(ResolvedBuildContext ctx, int textFrameDomId) {
        if (ctx == null || ctx.ownershipPlans == null || textFrameDomId < 0) return false;
        java.util.LinkedHashSet<ObjectPlan> candidates = new java.util.LinkedHashSet<>();
        candidates.addAll(ctx.ownershipPlansForObjectId(textFrameDomId));
        candidates.addAll(ctx.ownershipPlansForOwnedTextFrame(textFrameDomId));
        for (ObjectPlan plan : candidates) {
            if (plan == null) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.domId == textFrameDomId || containsInt(plan.ownedTextFrameIds, textFrameDomId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExecutableTextlessShellCarrier(ObjectPlan plan, RenderedGroup shell) {
        return isExecutableTextlessShellCarrier(plan);
    }

    private static boolean isExecutableTextlessShellCarrier(ObjectPlan plan) {
        if (plan == null) return false;
        return ShellRole.isTextShell(plan)
                && plan.hasVisibleVisual();
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

    private static SourceCanvasGeometry sourceCanvasGeometryForExtractedShell(
            ObjectPlan shellPlan,
            BufferedImage shellImage) {
        if (shellPlan == null || !validBounds(shellPlan.bounds) || shellImage == null) return null;
        if (shellImage.getWidth() <= 2 || shellImage.getHeight() <= 2) return null;
        int[] visible = alphaVisibleBounds(shellImage);
        if (visible == null) return null;
        int visibleW = visible[2] - visible[0];
        int visibleH = visible[3] - visible[1];
        if (visibleW <= 0 || visibleH <= 0) return null;
        double sourceW = Math.abs(shellPlan.bounds[3] - shellPlan.bounds[1]);
        double sourceH = Math.abs(shellPlan.bounds[2] - shellPlan.bounds[0]);
        if (sourceW <= 0.0 || sourceH <= 0.0) return null;

        double canvasLeft = shellPlan.bounds[1] - ((double) visible[0] / (double) visibleW) * sourceW;
        double canvasTop = shellPlan.bounds[0] - ((double) visible[1] / (double) visibleH) * sourceH;
        double canvasRight = canvasLeft + ((double) shellImage.getWidth() / (double) visibleW) * sourceW;
        double canvasBottom = canvasTop + ((double) shellImage.getHeight() / (double) visibleH) * sourceH;
        if (!Double.isFinite(canvasLeft) || !Double.isFinite(canvasTop)
                || !Double.isFinite(canvasRight) || !Double.isFinite(canvasBottom)
                || canvasRight <= canvasLeft || canvasBottom <= canvasTop) {
            return null;
        }
        return new SourceCanvasGeometry(new double[] { canvasTop, canvasLeft, canvasBottom, canvasRight });
    }

    private static double[] plannedInlineShellCropBounds(ObjectPlan shellPlan, double[] renderBounds) {
        if (shellPlan == null) return renderBounds;
        double[] crop = shellPlan.cropSourceBounds;
        if (!validBounds(crop) || !validBounds(renderBounds)) {
            return validBounds(shellPlan.bounds) ? shellPlan.bounds : renderBounds;
        }
        if (!boundsInside(renderBounds, crop, 0.75)) {
            return validBounds(shellPlan.bounds) ? shellPlan.bounds : renderBounds;
        }
        return crop;
    }

    private static double[] plannedInlineTextShellLayoutBounds(ObjectPlan shellPlan, double[] visualBounds) {
        if (shellPlan != null
                && shellPlan.placement == Placement.INLINE
                && shellPlan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && shellPlan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && shellPlan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && validBounds(shellPlan.bounds)) {
            return shellPlan.bounds;
        }
        return visualBounds;
    }

    private static BufferedImage cropImageByPlannedSourceBounds(
            BufferedImage image,
            double[] renderBounds,
            double[] cropBounds) {
        if (image == null || image.getWidth() <= 1 || image.getHeight() <= 1) return image;
        if (!validBounds(renderBounds) || !validBounds(cropBounds)) return image;
        if (!boundsInside(renderBounds, cropBounds, 0.75)) return image;
        double renderW = renderBounds[3] - renderBounds[1];
        double renderH = renderBounds[2] - renderBounds[0];
        if (renderW <= 0.0 || renderH <= 0.0) return image;

        int sx1 = (int) Math.floor(((cropBounds[1] - renderBounds[1]) / renderW) * image.getWidth());
        int sy1 = (int) Math.floor(((cropBounds[0] - renderBounds[0]) / renderH) * image.getHeight());
        int sx2 = (int) Math.ceil(((cropBounds[3] - renderBounds[1]) / renderW) * image.getWidth());
        int sy2 = (int) Math.ceil(((cropBounds[2] - renderBounds[0]) / renderH) * image.getHeight());
        sx1 = clamp(sx1, 0, image.getWidth());
        sx2 = clamp(sx2, 0, image.getWidth());
        sy1 = clamp(sy1, 0, image.getHeight());
        sy2 = clamp(sy2, 0, image.getHeight());
        if (sx2 <= sx1 || sy2 <= sy1) return image;
        int w = sx2 - sx1;
        int h = sy2 - sy1;
        if (w >= image.getWidth() && h >= image.getHeight()) return image;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image,
                    0, 0, w, h,
                    sx1, sy1, sx2, sy2,
                    null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static boolean boundsInside(double[] outer, double[] inner, double tolerance) {
        if (!validBounds(outer) || !validBounds(inner)) return false;
        double t = Math.max(0.0, tolerance);
        return inner[0] >= outer[0] - t
                && inner[1] >= outer[1] - t
                && inner[2] <= outer[2] + t
                && inner[3] <= outer[3] + t;
    }

    private static boolean boundsDiffer(double[] a, double[] b, double eps) {
        if (!validBounds(a) || !validBounds(b)) return false;
        double e = Math.max(0.0, eps);
        return Math.abs(a[0] - b[0]) > e
                || Math.abs(a[1] - b[1]) > e
                || Math.abs(a[2] - b[2]) > e
                || Math.abs(a[3] - b[3]) > e;
    }

    private static int[] alphaVisibleBounds(BufferedImage img) {
        if (img == null) return null;
        int left = img.getWidth();
        int top = img.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (a <= 8) continue;
                if (x < left) left = x;
                if (x + 1 > right) right = x + 1;
                if (y < top) top = y;
                if (y + 1 > bottom) bottom = y + 1;
            }
        }
        if (right <= left || bottom <= top) return null;
        return new int[] { left, top, right, bottom };
    }

    private static final class SourceCanvasGeometry {
        final double[] sourceCanvasBounds;

        SourceCanvasGeometry(double[] sourceCanvasBounds) {
            this.sourceCanvasBounds = sourceCanvasBounds;
        }
    }

    private static final class ImageCropResult {
        final BufferedImage image;
        final double[] sourceBounds;

        ImageCropResult(BufferedImage image, double[] sourceBounds) {
            this.image = image;
            this.sourceBounds = sourceBounds;
        }
    }

    private static byte[] prepareInlineTextShellImageData(BufferedImage img) throws Exception {
        return prepareInlineTextShellImageData(img, false);
    }

    private static byte[] prepareInlineTextShellImageData(BufferedImage img, boolean preserveSourceCanvas) throws Exception {
        return prepareInlineTextShellImageData(img, preserveSourceCanvas, false);
    }

    private static byte[] prepareInlineTextShellImageData(
            BufferedImage img,
            boolean preserveSourceCanvas,
            boolean forceVerticalAlphaTrim) throws Exception {
        BufferedImage shell = VisualCropper.knockOutPaperLikeFill(img);
        if (!preserveSourceCanvas) {
            shell = trimInlineTextShellVerticalAlphaPadding(shell, forceVerticalAlphaTrim);
        }
        return VisualCropper.encodePng(shell);
    }

    private static byte[] prepareTransparentInlineTextShellImageData(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            double[] sourceBounds,
            BufferedImage shellImage,
            boolean preserveSourceCanvas) throws Exception {
        return prepareTransparentInlineTextShellImageData(
                ctx, shellPlan, sourceBounds, shellImage, preserveSourceCanvas, false);
    }

    private static byte[] prepareTransparentInlineTextShellImageData(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            double[] sourceBounds,
            BufferedImage shellImage,
            boolean preserveSourceCanvas,
            boolean forceVerticalAlphaTrim) throws Exception {
        if (!preserveSourceCanvas) {
            shellImage = trimInlineTextShellVerticalAlphaPadding(shellImage, forceVerticalAlphaTrim);
        }
        if (shellPlan != null && shellPlan.placement == Placement.INLINE) {
            return VisualCropper.encodePng(shellImage);
        }
        BufferedImage blended = blendTransparentShellOverPagePlane(ctx, shellPlan, sourceBounds, shellImage);
        if (blended != null) return encodeRgbPng(blended);
        return VisualCropper.encodePng(shellImage);
    }

    private static BufferedImage trimInlineTextShellVerticalAlphaPadding(BufferedImage img) {
        return trimInlineTextShellVerticalAlphaPadding(img, false);
    }

    private static BufferedImage trimInlineTextShellVerticalAlphaPadding(
            BufferedImage img,
            boolean forceSparseTrim) {
        if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return img;
        int top = img.getHeight();
        int bottom = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            boolean rowVisible = false;
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    rowVisible = true;
                    break;
                }
            }
            if (rowVisible) {
                if (top == img.getHeight()) top = y;
                bottom = y;
            }
        }
        if (bottom < top) return img;
        int visibleH = bottom - top + 1;
        int paddingH = img.getHeight() - visibleH;
        double visibleRatio = (double) visibleH / (double) img.getHeight();
        double paddingRatio = (double) paddingH / (double) img.getHeight();
        if ((!forceSparseTrim && visibleRatio < 0.45) || paddingRatio < 0.20) return img;
        if (visibleH >= img.getHeight()) return img;

        BufferedImage out = new BufferedImage(img.getWidth(), visibleH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img,
                    0, 0, img.getWidth(), visibleH,
                    0, top, img.getWidth(), bottom + 1,
                    null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage trimInlineTextShellAlphaPadding(BufferedImage img) {
        if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return img;
        int[] visible = alphaVisibleBounds(img);
        if (visible == null) return img;
        int left = visible[0];
        int top = visible[1];
        int right = visible[2];
        int bottom = visible[3];
        if (left <= 0 && top <= 0 && right >= img.getWidth() && bottom >= img.getHeight()) {
            return img;
        }
        int w = right - left;
        int h = bottom - top;
        if (w <= 1 || h <= 1) return img;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img,
                    0, 0, w, h,
                    left, top, right, bottom,
                    null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static ImageCropResult cropInlineTextShellAlphaPadding(BufferedImage img, double[] sourceBounds) {
        if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) {
            return new ImageCropResult(img, sourceBounds);
        }
        int[] visible = alphaVisibleBounds(img);
        if (visible == null) return new ImageCropResult(img, sourceBounds);
        int left = visible[0];
        int right = visible[2];
        if (left <= 0 && right >= img.getWidth()) {
            return new ImageCropResult(img, sourceBounds);
        }
        int w = right - left;
        int h = img.getHeight();
        if (w <= 1 || h <= 1) return new ImageCropResult(img, sourceBounds);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img,
                    0, 0, w, h,
                    left, 0, right, img.getHeight(),
                    null);
        } finally {
            g.dispose();
        }

        double[] croppedSourceBounds = sourceBounds;
        if (validBounds(sourceBounds)) {
            double sourceW = Math.abs(sourceBounds[3] - sourceBounds[1]);
            double sourceH = Math.abs(sourceBounds[2] - sourceBounds[0]);
            if (sourceW > 0.0 && sourceH > 0.0) {
                double sourceLeft = sourceBounds[1] + (sourceW * ((double) left / (double) img.getWidth()));
                double sourceRight = sourceBounds[1] + (sourceW * ((double) right / (double) img.getWidth()));
                if (Double.isFinite(sourceLeft) && Double.isFinite(sourceRight)
                        && sourceRight > sourceLeft) {
                    croppedSourceBounds = new double[] { sourceBounds[0], sourceLeft, sourceBounds[2], sourceRight };
                }
            }
        }
        return new ImageCropResult(out, croppedSourceBounds);
    }

    private static boolean shouldCompactSplitInlineTextShellCanvas(ObjectPlan plan) {
        if (plan == null || plan.placement != Placement.INLINE) return false;
        if (!ShellRole.isTextShell(plan)) return false;
        return shouldCompactSplitInlineTextShellFollower(plan)
                || "direct_child_shell_slot".equals(plan.slotRole);
    }

    private static boolean isDirectChildInlineTextShellSlot(ObjectPlan plan) {
        return plan != null && "direct_child_shell_slot".equals(plan.slotRole);
    }

    private static boolean shouldCompactSplitInlineTextShellFollower(ObjectPlan plan) {
        if (plan == null || plan.placement != Placement.INLINE) return false;
        if (!ShellRole.isTextShell(plan)) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) return false;
        if (plan.hiddenVisualSourceObjectIds == null || plan.hiddenVisualSourceObjectIds.length == 0) return false;
        int hiddenOrder = firstSourceIndex(plan.sourceObjectIds, plan.hiddenVisualSourceObjectIds);
        int visibleOrder = Integer.MAX_VALUE;
        visibleOrder = Math.min(visibleOrder, firstSourceIndex(plan.sourceObjectIds, plan.visualSourceObjectIds));
        visibleOrder = Math.min(visibleOrder, firstSourceIndex(plan.sourceObjectIds, plan.exportSourceObjectIds));
        visibleOrder = Math.min(visibleOrder, firstSourceIndex(plan.sourceObjectIds, plan.styleSourceObjectIds));
        visibleOrder = Math.min(visibleOrder, firstSourceIndex(plan.sourceObjectIds, plan.ownedTextFrameIds));
        return hiddenOrder != Integer.MAX_VALUE
                && visibleOrder != Integer.MAX_VALUE
                && hiddenOrder < visibleOrder;
    }

    private static BufferedImage blendTransparentShellOverPagePlane(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            double[] sourceBounds,
            BufferedImage shellImage) {
        if (ctx == null || ctx.basePath == null || shellPlan == null || shellImage == null) return null;
        double[] bounds = validBounds(sourceBounds) ? sourceBounds : shellPlan.bounds;
        if (!validBounds(bounds)) return null;
        double pageW = localPageWidth(ctx, shellPlan.pageIndex);
        double pageH = localPageHeight(ctx, shellPlan.pageIndex);
        if (pageW <= 0.0 || pageH <= 0.0) return null;

        File pagePlaneFile = new File(
                ctx.basePath,
                "rendered_frames/page_textless_plane_p" + (shellPlan.pageIndex + 1) + ".png");
        if (!pagePlaneFile.exists() || !pagePlaneFile.isFile()) return null;
        try {
            BufferedImage pagePlane = ImageIO.read(pagePlaneFile);
            if (pagePlane == null || pagePlane.getWidth() <= 0 || pagePlane.getHeight() <= 0) return null;

            double[] b = normalizeInlineBoundsToPageLocal(ctx, shellPlan.pageIndex, bounds);
            if (!validBounds(b)) return null;
            int sx1 = (int) Math.round((b[1] / pageW) * pagePlane.getWidth());
            int sy1 = (int) Math.round((b[0] / pageH) * pagePlane.getHeight());
            int sx2 = (int) Math.round((b[3] / pageW) * pagePlane.getWidth());
            int sy2 = (int) Math.round((b[2] / pageH) * pagePlane.getHeight());
            sx1 = clamp(sx1, 0, pagePlane.getWidth());
            sx2 = clamp(sx2, 0, pagePlane.getWidth());
            sy1 = clamp(sy1, 0, pagePlane.getHeight());
            sy2 = clamp(sy2, 0, pagePlane.getHeight());
            if (sx2 <= sx1 || sy2 <= sy1) return null;

            BufferedImage out = new BufferedImage(
                    shellImage.getWidth(), shellImage.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.drawImage(pagePlane, 0, 0, out.getWidth(), out.getHeight(), sx1, sy1, sx2, sy2, null);
                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(shellImage, 0, 0, out.getWidth(), out.getHeight(), null);
            } finally {
                g.dispose();
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encodeRgbPng(BufferedImage image) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static boolean shouldPreserveTransparentInlineShell(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return false;
        long total = (long) img.getWidth() * (long) img.getHeight();
        if (total <= 0) return false;

        long transparent = 0;
        long translucent = 0;
        long visible = 0;
        long opaquePaperLike = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a <= 8) {
                    transparent++;
                    continue;
                }
                visible++;
                if (a < 245) translucent++;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (a >= 245 && r >= 245 && g >= 245 && b >= 245) {
                    opaquePaperLike++;
                }
            }
        }
        if (transparent == 0 && translucent == 0) return false;

        double visibleRatio = (double) visible / (double) total;
        double paperRatio = (double) opaquePaperLike / (double) total;
        if (paperRatio >= 0.10) return false;

        // Sparse inline shells such as horizontal rules, outlines, and callout tails rely on
        // transparent canvas. Flattening them paints a white rectangle over page graphics.
        return visibleRatio > 0.002 && visibleRatio <= 0.35;
    }

    private static boolean shouldPreserveInlineShellSourceCanvas(
            ResolvedBuildContext ctx,
            ObjectPlan childPlan) {
        if (ctx == null || childPlan == null || ctx.ownershipPlans == null) return false;
        if (childPlan.placement != Placement.INLINE) return false;
        if (!ShellRole.isTextShell(childPlan)) return false;
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
        if (!ShellRole.isTextShell(parent)) return false;
        if (parent.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (!parent.hasVisibleVisual()) return false;
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

    private static ResolvedPageItem findInlineTextShellNativeStyleSource(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedPageItem anchorItem) {
        if (ctx == null || ctx.resolvedData == null || shellPlan == null) return null;
        if (shellPlan.placement != Placement.INLINE
                || shellPlan.coordinateSpace != CoordinateSpace.STORY_FLOW
                || shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL
                || (shellPlan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                    && !(shellPlan.textAction == TextAction.DROP_TEXT
                        && shellPlan.ownedTextFrameIds != null
                        && shellPlan.ownedTextFrameIds.length > 0))
                || !ShellRole.isTextShell(shellPlan)) {
            return null;
        }

        ResolvedPageItem item = findInlineTextShellNativeStyleSource(ctx, shellPlan, anchorItem, new HashSet<Integer>());
        if (item != null) return item;

        int[][] idGroups = new int[][] {
                shellPlan.styleSourceObjectIds,
                shellPlan.visualSourceObjectIds,
                shellPlan.exportSourceObjectIds,
                shellPlan.sourceObjectIds,
                shellPlan.sourceRootObjectIds
        };
        HashSet<Integer> visited = new HashSet<>();
        for (int[] ids : idGroups) {
            if (ids == null) continue;
            for (int id : ids) {
                item = findInlineTextShellNativeStyleSource(
                        ctx,
                        shellPlan,
                        ctx.resolvedData.getPageItem(String.valueOf(id)),
                        visited);
                if (item != null) return item;
            }
        }
        return null;
    }

    private static ResolvedPageItem findInlineTextShellNativeStyleSource(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            ResolvedPageItem item,
            Set<Integer> visited) {
        if (ctx == null || ctx.resolvedData == null || item == null || shellPlan == null) return null;
        int itemId = parseIntOrDefault(item.id(), -1);
        if (itemId > 0 && !visited.add(itemId)) return null;
        if (isInlineTextShellNativeStyleSource(shellPlan, item)) return item;
        int[] childIds = item.childIds();
        if (childIds == null || childIds.length == 0) return null;
        for (int childId : childIds) {
            ResolvedPageItem child = ctx.resolvedData.getPageItem(String.valueOf(childId));
            ResolvedPageItem found = findInlineTextShellNativeStyleSource(ctx, shellPlan, child, visited);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isInlineTextShellNativeStyleSource(ObjectPlan shellPlan, ResolvedPageItem item) {
        if (shellPlan == null || item == null) return false;
        int itemId = parseIntOrDefault(item.id(), -1);
        if (itemId <= 0) return false;
        if (!containsInt(shellPlan.sourceObjectIds, itemId)
                && !containsInt(shellPlan.visualSourceObjectIds, itemId)
                && !containsInt(shellPlan.styleSourceObjectIds, itemId)
                && !containsInt(shellPlan.exportSourceObjectIds, itemId)
                && !containsInt(shellPlan.sourceRootObjectIds, itemId)
                && shellPlan.domId != itemId) {
            return false;
        }
        if (!isSimpleNativeShellShape(item)) return false;
        return hasSourceShapeStyle(item);
    }

    private static boolean isSimpleNativeShellShape(ResolvedPageItem item) {
        if (item == null) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"Oval".equals(type) && !"Polygon".equals(type)) {
            return false;
        }
        if (!item.visible() || item.hiddenByParent()) return false;
        if (item.hasDropShadow() || item.gradientFeatherApplied()) return false;
        if (Math.abs(item.absoluteRotationAngle()) > 0.01 || Math.abs(item.absoluteShearAngle()) > 0.01) {
            return false;
        }
        if (item.childIds() != null && item.childIds().length > 0) return false;
        return true;
    }

    private static boolean hasSourceShapeStyle(ResolvedPageItem item) {
        if (item == null) return false;
        if (!isNoneColor(item.fillColorName())) return true;
        return !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.0;
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

    private static void applyOwnedTextFrameShellShapeStyle(
            ResolvedBuildContext ctx,
            java.util.List<ResolvedTextFrame> childTfs,
            ASTInlineObject obj) {
        if (ctx == null || ctx.resolvedData == null || childTfs == null || obj == null) return;
        for (ResolvedTextFrame tf : childTfs) {
            if (tf == null) continue;
            if (obj.fillColor() == null) {
                String fillHex = ctx.resolvedData.resolveColorHex(tf.fillColor());
                if (fillHex != null) {
                    obj.fillColor(fillHex);
                    obj.fillTint(tf.fillTint() > 0 ? (int) tf.fillTint() : 100);
                }
            }
            if (obj.strokeColor() == null && tf.strokeWeight() > 0) {
                String strokeHex = ctx.resolvedData.resolveColorHex(tf.strokeColor());
                if (strokeHex != null) {
                    obj.strokeColor(strokeHex);
                    double sw = tf.strokeWeight();
                    if (ctx.scaleFactor > 0) sw = sw / ctx.scaleFactor;
                    obj.strokeWeight(Math.max(sw, 0.5));
                }
            }
            if (obj.cornerRadius() <= 0 && tf.cornerRadius() > 0) {
                double cr = tf.cornerRadius();
                if (ctx.scaleFactor > 0) cr = cr * ctx.scaleFactor;
                obj.cornerRadius(cr);
            }
        }
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
                    && ShellRole.isTextShell(plan)
                    && isShellPlanWithOwnedHwpxText(ctx, plan)
                    && containsInt(plan.ownedTextFrameIds, childDomId)) {
                return rg;
            }
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
        String[] ids = editableTextFrameIds(ctx, rg);
        if (ids == null || ids.length == 0) return null;
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
            if (!isHwpxEditableTextFrame(ctx, tf.id())) continue;
            if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) continue;
            if (found != null) return null;
            found = tf;
        }
        return found;
    }

    private static boolean shouldOverlayRenderedBadgeText(ResolvedBuildContext ctx, RenderedGroup matched) {
        if (matched == null) return false;
        ObjectPlan plan = ctx != null ? ctx.findOwnershipPlanForRendered(matched) : null;
        if (plan != null) {
            if (plan.textAction == TextAction.OWNED_BY_PNG) return false;
            return plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                    || isShellPlanWithOwnedHwpxText(ctx, plan);
        }
        return false;
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
        java.util.Set<String> descendantIds = ctx.descendantSet(anchorId, 5);
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

    /** 배지/셸 텍스트 단락 빌드. 원본 story 문단이 있으면 짧은 라벨도 그 정렬/런 속성을 그대로 쓴다. */
    private static void buildBadgeParagraph(ResolvedBuildContext ctx, ResolvedTextFrame childTf, ASTInlineObject obj) {
        buildBadgeParagraph(ctx, childTf, obj, false);
    }

    private static void buildBadgeParagraph(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf,
            ASTInlineObject obj,
            boolean preserveSourceShellBox) {
        if (ctx != null
                && ctx.resolvedData != null
                && childTf != null
                && childTf.storyId() != null
                && canMaterializeShellTextFromWholeStory(ctx, childTf)) {
            List<ASTParagraph> paragraphs = convertShellTextParagraphs(ctx, textFlowUnitForTextFrame(ctx, childTf));
            if ((paragraphs == null || paragraphs.isEmpty())) {
                ResolvedStory story = ctx.resolvedData.getStory(childTf.storyId());
                paragraphs = convertShellTextParagraphs(ctx, story);
            }
            if (paragraphs != null && !paragraphs.isEmpty()) {
                applyIdmlRunStyleFallbackToParagraphs(ctx, childTf, paragraphs);
                if (!preserveSourceShellBox) {
                    fitSingleLineInlineTextShellBoxToComposedLine(paragraphs, obj, childTf, ctx);
                }
                capInlineShellParagraphLeadingToFrame(paragraphs, obj, childTf, ctx);
                for (ASTParagraph paragraph : paragraphs) {
                    obj.addParagraph(paragraph);
                }
                return;
            }
        }
        List<ASTParagraph> paragraphs = buildSyntheticShellTextParagraphs(ctx, childTf);
        if (paragraphs == null || paragraphs.isEmpty()) return;
        if (!preserveSourceShellBox) {
            fitSingleLineInlineTextShellBoxToComposedLine(paragraphs, obj, childTf, ctx);
        }
        capInlineShellParagraphLeadingToFrame(paragraphs, obj, childTf, ctx);
        for (ASTParagraph paragraph : paragraphs) {
            obj.addParagraph(paragraph);
        }
    }

    private static void fitSingleLineInlineTextShellBoxToComposedLine(
            List<ASTParagraph> paragraphs,
            ASTInlineObject obj,
            ResolvedTextFrame childTf,
            ResolvedBuildContext ctx) {
        if (paragraphs == null || paragraphs.isEmpty() || obj == null || childTf == null) return;
        if (obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME
                && obj.kind() != ASTInlineObject.ObjectKind.INLINE_BADGE_GROUP) {
            return;
        }
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) {
            return;
        }
        if (!isSourceSingleLineTextFrame(childTf)) return;
        long frameHeight = obj.height();
        if (frameHeight <= 0) return;
        long composedLineHeight = maxComposedLineHeightHwpunits(childTf, ctx, frameHeight);
        if (composedLineHeight <= 0) return;
        if (frameHeight <= Math.round(composedLineHeight * 1.5)) return;

        long fontHeight = 0L;
        for (ASTParagraph paragraph : paragraphs) {
            fontHeight = Math.max(fontHeight, reasonableFontSizeHwpunits(paragraph, frameHeight));
        }
        long verticalPadding = inlineShellVerticalInsetPaddingHwpunits(
                childTf, ctx, composedLineHeight, frameHeight);
        long targetHeight = Math.max(composedLineHeight, fontHeight) + verticalPadding;
        long minimumHeight = Math.max(composedLineHeight, fontHeight);
        long maximumShrinkHeight = Math.round(frameHeight * 0.45);
        targetHeight = Math.max(targetHeight, Math.max(minimumHeight, maximumShrinkHeight));
        if (targetHeight <= 0 || targetHeight >= Math.round(frameHeight * 0.95)) return;

        obj.height(targetHeight);
        if (obj.resolvedHeight() > 0) {
            obj.resolvedHeight(targetHeight);
        }

        long verticalMargins = Math.max(0L, obj.textMarginTop()) + Math.max(0L, obj.textMarginBottom());
        if (verticalMargins > Math.round(targetHeight * 0.5)) {
            long boundedMargin = Math.min(
                    Math.round(targetHeight * 0.12),
                    Math.max(0L, verticalPadding / 2));
            obj.textMarginTop(boundedMargin);
            obj.textMarginBottom(boundedMargin);
        }
    }

    private static long inlineShellVerticalInsetPaddingHwpunits(
            ResolvedTextFrame childTf,
            ResolvedBuildContext ctx,
            long composedLineHeight,
            long frameHeight) {
        if (childTf == null) return 0L;
        double[] inset = childTf.insetSpacing();
        if (inset == null || inset.length < 3) return 0L;
        double top = Math.max(0.0, inset[0]);
        double bottom = Math.max(0.0, inset[2]);
        if (!Double.isFinite(top + bottom) || top + bottom <= 0.0) return 0L;
        long padding = inlineShellSourceHeightRatioToHwpunits(childTf, top + bottom, frameHeight);
        if (padding <= 0L) {
            double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
            padding = CoordinateConverter.pointsToHwpunits((top + bottom) * scale);
        }
        if (composedLineHeight > 0) {
            padding = Math.min(padding, Math.round(composedLineHeight * 0.3));
        }
        return Math.max(0L, padding);
    }

    private static void capInlineShellParagraphLeadingToFrame(
            List<ASTParagraph> paragraphs,
            ASTInlineObject obj,
            ResolvedTextFrame childTf,
            ResolvedBuildContext ctx) {
        if (paragraphs == null || paragraphs.isEmpty() || obj == null) return;
        if (obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME
                && obj.kind() != ASTInlineObject.ObjectKind.INLINE_BADGE_GROUP) {
            return;
        }
        if (sourceTextFrameHasMultipleVisibleRows(ctx, childTf)) return;
        long frameHeight = obj.height();
        if (frameHeight <= 0) return;
        long contentHeight = frameHeight
                - Math.max(0L, obj.textMarginTop())
                - Math.max(0L, obj.textMarginBottom());
        if (contentHeight <= 0) contentHeight = frameHeight;
        if (contentHeight < Math.round(frameHeight * 0.5)) {
            contentHeight = frameHeight;
        }
        long composedLineHeight = maxComposedLineHeightHwpunits(childTf, ctx, frameHeight);

        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null) continue;
            Integer current = paragraph.lineSpacing();
            boolean currentFixed = "fixed".equals(paragraph.lineSpacingType());
            if (currentFixed && current != null && current <= contentHeight) continue;

            long minimum = Math.max(reasonableFontSizeHwpunits(paragraph, frameHeight), composedLineHeight);
            long target = contentHeight;
            if (minimum > target && minimum <= frameHeight) {
                target = minimum;
            }
            if (target <= 0) continue;
            if (currentFixed && current != null && target >= current) continue;
            paragraph.lineSpacing((int) Math.min(Integer.MAX_VALUE, target));
            paragraph.lineSpacingType("fixed");
        }
    }

    private static long reasonableFontSizeHwpunits(ASTParagraph paragraph, long frameHeight) {
        long max = 0L;
        if (paragraph == null || paragraph.items() == null) return max;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            Integer fontSize = ((ASTTextRun) item).fontSizeHwpunits();
            if (fontSize != null
                    && fontSize > max
                    && (frameHeight <= 0 || fontSize <= Math.round(frameHeight * 1.2))) {
                max = fontSize;
            }
        }
        return max;
    }

    private static long maxComposedLineHeightHwpunits(
            ResolvedTextFrame childTf,
            ResolvedBuildContext ctx,
            long frameHeight) {
        if (childTf == null || childTf.composedLines() == null) return 0L;
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        long max = 0L;
        for (ResolvedTextFrame.ComposedLine line : childTf.composedLines()) {
            if (line == null || line.bounds() == null || line.bounds().length < 3) continue;
            double rawHeight = Math.abs(line.bounds()[2] - line.bounds()[0]);
            if (!Double.isFinite(rawHeight) || rawHeight <= 0.0 || rawHeight > 100.0) continue;
            long ratioHeight = inlineShellSourceHeightRatioToHwpunits(childTf, rawHeight, frameHeight);
            if (ratioHeight > 0) {
                max = Math.max(max, ratioHeight);
            } else {
                max = Math.max(max, CoordinateConverter.pointsToHwpunits(rawHeight * scale));
            }
        }
        return max;
    }

    private static long inlineShellSourceHeightRatioToHwpunits(
            ResolvedTextFrame childTf,
            double sourceHeight,
            long frameHeight) {
        if (childTf == null || sourceHeight <= 0.0 || frameHeight <= 0) return 0L;
        double[] bounds = childTf.geometricBounds();
        if (bounds == null || bounds.length < 3) return 0L;
        double sourceFrameHeight = Math.abs(bounds[2] - bounds[0]);
        if (!Double.isFinite(sourceFrameHeight) || sourceFrameHeight <= 0.0) return 0L;
        double ratio = sourceHeight / sourceFrameHeight;
        if (!Double.isFinite(ratio) || ratio <= 0.0 || ratio > 1.5) return 0L;
        return Math.round(frameHeight * ratio);
    }

    private static boolean canMaterializeShellTextFromWholeStory(
            ResolvedBuildContext ctx,
            ResolvedTextFrame textFrame) {
        if (ctx == null || ctx.resolvedData == null || textFrame == null || textFrame.storyId() == null) {
            return false;
        }
        String frameText = normalizeInlineShellText(textFrame.frameVisibleText());
        if (frameText.isEmpty()) return false;

        ResolvedStory story = ctx.resolvedData.getStory(textFrame.storyId());
        String storyText = normalizeInlineShellStoryText(story);
        if (storyText.isEmpty()) return false;
        if (frameText.equals(storyText)) return true;
        // 괄호 빈칸 스페이서: ResolvedData 가 story 쪽 앵커 런만 NBSP 로 치환하므로
        // frameVisibleText(원문, FFFC 제거만 됨)와는 NBSP 만큼 어긋난다. NBSP 를
        // 무시하고 같으면 같은 스토리다 (BlankAnchorSpacer 참조).
        // 아래 sameTextIgnoringWhitespace 의 \\s+ 는 NBSP 를 잡지 못하므로 별도 비교다.
        if (frameText.replace("\u00A0", "").equals(storyText.replace("\u00A0", ""))) return true;
        if (sameTextIgnoringWhitespace(frameText, storyText)) return true;
        if (frameTextMatchesStoryModuloArrowGlyphs(frameText, storyText, story)) return true;
        return canMaterializeOwnedOverflowShellText(ctx, textFrame, frameText, storyText);
    }

    private static boolean sameTextIgnoringWhitespace(String a, String b) {
        if (a == null || b == null) return false;
        String compactA = a.replaceAll("\\s+", "");
        String compactB = b.replaceAll("\\s+", "");
        return !compactA.isEmpty() && compactA.equals(compactB);
    }

    /**
     * 화살표 글리프 관용 비교.
     *
     * <p>BT화살표 글리프 런은 ResolvedDataReader 가 story 쪽 텍스트만 "→" 로
     * 정규화하고, frameVisibleText 는 폰트에 저장된 원문 글자("C"/"@"/"@C"/"?C")를
     * 그대로 갖는다. 그래서 화살표가 든 반응식 프레임은 문자 그대로의 동일성
     * 비교가 항상 실패해 구조 보존 경로 대신 평탄화 경로로 떨어졌다 — 첨자·화살표
     * 소실 (실측: 과학 u1 p46 N₂+3H₂→2NH₃, p47 2H₂+O₂→2H₂O). storyText 의 "→"
     * 자리에 원문 글리프 후보만 허용해 비교한다.
     *
     * <p>주의: 스토리에 실제 BT화살표 폰트 런이 있을 때만 적용한다. 본문에 진짜
     * "→" 문자를 쓰는 문서(수학 u1)에서 자유 와일드카드로 비교하면, 내용이 정말
     * 다른 프레임까지 통짜 스토리 경로로 잘못 통과해 수식 그룹핑이 깨진다.
     */
    private static boolean frameTextMatchesStoryModuloArrowGlyphs(
            String frameText, String storyText, ResolvedStory story) {
        if (frameText == null || storyText == null) return false;
        if (!storyText.contains(BTFontGlyphMap.ARROW)) return false;
        if (!storyHasArrowGlyphRun(story)) return false;
        String[] parts = storyText.split(BTFontGlyphMap.ARROW, -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            // 실측된 화살표 원문 글리프 집합 (BTFontGlyphMap 주석 참조) + 정규화된 "→" 자신.
            // 일부 IDML은 CustomGlyph 화살표를 ACE 1a 제어 문자(U+001A) 조각으로 노출한다.
            if (i > 0) regex.append("(?:@C|\\?C|@|C|\u001A+|→)");
            if (!parts[i].isEmpty()) regex.append(java.util.regex.Pattern.quote(parts[i]));
        }
        return frameText.matches(regex.toString());
    }

    private static boolean storyHasArrowGlyphRun(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null) continue;
                if (BTFontGlyphMap.isBTArrowFont(run.fontFamily())
                        || BTFontGlyphMap.isBTArrowFontStyle(run.charStyle())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean canMaterializeOwnedOverflowShellText(
            ResolvedBuildContext ctx,
            ResolvedTextFrame textFrame,
            String frameText,
            String storyText) {
        if (ctx == null || textFrame == null || frameText == null || storyText == null) return false;
        if (!textFrame.overflows() || !textFrame.isInline()) return false;
        int textFrameDomId = parseDecimalId(textFrame.id());
        if (textFrameDomId < 0) return false;
        if (!ctx.isTextFrameOwnedByTextShellPlan(textFrameDomId)
                || !ctx.ownershipPlanPlacesInlineHwpxText(textFrameDomId)) {
            return false;
        }
        if (!isSingleOwnerTextFlow(ctx, textFrame)) return false;
        return storyText.startsWith(frameText);
    }

    private static boolean isSingleOwnerTextFlow(
            ResolvedBuildContext ctx,
            ResolvedTextFrame textFrame) {
        if (ctx == null || ctx.textFlowDocument == null || textFrame == null || textFrame.storyId() == null) {
            return false;
        }
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(textFrame.storyId());
        if (unit == null || unit.ownerTextFrameIds == null || unit.ownerTextFrameIds.size() != 1) {
            return false;
        }
        if (!"HWPX_TEXT".equals(unit.textOwner)) return false;
        return textFrame.id() != null && textFrame.id().equals(unit.ownerTextFrameIds.get(0));
    }

    private static String normalizeInlineShellStoryText(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                sb.append(run.text());
            }
            sb.append(' ');
        }
        return normalizeInlineShellText(sb.toString());
    }

    private static String shellTextAlignment(ResolvedBuildContext ctx, ResolvedTextFrame textFrame) {
        String alignment = firstTextFlowParagraphAlignment(ctx, textFrame);
        if (alignment != null && !alignment.isEmpty()) return alignment;
        alignment = firstResolvedStoryParagraphAlignment(ctx, textFrame);
        if (alignment != null && !alignment.isEmpty()) return alignment;
        return "CENTER";
    }

    private static String firstTextFlowParagraphAlignment(ResolvedBuildContext ctx, ResolvedTextFrame textFrame) {
        if (ctx == null || ctx.textFlowDocument == null || textFrame == null || textFrame.storyId() == null) {
            return null;
        }
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(textFrame.storyId());
        if (unit == null || unit.paragraphs == null) return null;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null) continue;
            if (paragraph.justification != null && !paragraph.justification.isEmpty()) {
                return paragraph.justification;
            }
            if (paragraph.styleName != null && ctx.resolvedData != null) {
                String styleJustification = ctx.resolvedData.getParagraphStyleJustification(paragraph.styleName);
                if (styleJustification != null && !styleJustification.isEmpty()) {
                    return styleJustification;
                }
            }
        }
        return null;
    }

    private static String firstResolvedStoryParagraphAlignment(ResolvedBuildContext ctx, ResolvedTextFrame textFrame) {
        if (ctx == null || ctx.resolvedData == null || textFrame == null || textFrame.storyId() == null) {
            return null;
        }
        ResolvedStory story = ctx.resolvedData.getStory(textFrame.storyId());
        if (story == null || story.paragraphs() == null) return null;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null) continue;
            if (paragraph.justification() != null && !paragraph.justification().isEmpty()) {
                return paragraph.justification();
            }
            if (paragraph.styleName() != null) {
                String styleJustification = ctx.resolvedData.getParagraphStyleJustification(paragraph.styleName());
                if (styleJustification != null && !styleJustification.isEmpty()) {
                    return styleJustification;
                }
            }
        }
        return null;
    }

    private static TextFlowDocument.TextFlowUnit textFlowUnitForTextFrame(
            ResolvedBuildContext ctx,
            ResolvedTextFrame textFrame) {
        if (ctx == null || ctx.textFlowDocument == null || textFrame == null || textFrame.storyId() == null) {
            return null;
        }
        return ctx.textFlowDocument.byStoryId(textFrame.storyId());
    }

    private static List<ASTParagraph> convertShellTextParagraphs(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowUnit unit) {
        return postprocessShellTextParagraphs(ctx, TextFlowAstMaterializer.convertUnit(
                ctx,
                unit,
                InlineFrameHandler::normalizeShellTextFlowText,
                "CENTER",
                null,
                atom -> {
                    if (atom == null || atom.anchoredObjectId == null) return null;
                    return loadPlannedInlineAnchorItems(ctx, atom.anchoredObjectId, null, null);
                },
                shellTextLineBreakLayout(ctx, unit)));
    }

    private static List<ASTParagraph> convertShellTextParagraphs(
            ResolvedBuildContext ctx,
            ResolvedStory story) {
        return postprocessShellTextParagraphs(ctx, ResolvedTextFlowAstConverter.convertStory(
                story,
                ResolvedTextFlowAstConverter.options()
                        .colorResolver(color -> RunBuilder.resolveColorToHex(ctx, color))
                        .textTransformer(InlineFrameHandler::normalizeShellTextFlowText)
                        .defaultAlignment("CENTER")
                        .copyTabStops(true)
                        .truncateAtParagraphBreak(false)
                        .skipBlankRuns(true)
                        .skipEmptyParagraphs(true)));
    }

    private static String normalizeShellTextFlowText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text
                .replace("\uFFFC", "")
                .replace('\t', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u2028', '\n');
    }

    private static TextFlowAstMaterializer.SourceLineBreakLayout shellTextLineBreakLayout(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowUnit unit) {
        if (ctx == null || ctx.resolvedData == null || unit == null
                || unit.ownerTextFrameIds == null || unit.ownerTextFrameIds.isEmpty()) {
            return null;
        }
        double indentPt = Double.NaN;
        boolean hasExplicitLineBreak = false;
        for (String textFrameId : unit.ownerTextFrameIds) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(textFrameId);
            if (tf == null || tf.composedLines() == null || tf.composedLines().size() < 2) continue;
            for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
                if (line == null) continue;
                String text = line.text();
                if (text != null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\u2028') >= 0)) {
                    hasExplicitLineBreak = true;
                }
                double lineIndent = line.wrapIndentLeft();
                if (lineIndent >= 0.5 && lineIndent <= 80.0) {
                    indentPt = Double.isFinite(indentPt) ? Math.min(indentPt, lineIndent) : lineIndent;
                }
            }
        }
        if (!hasExplicitLineBreak || !Double.isFinite(indentPt) || indentPt <= 0.0) return null;
        long indent = CoordinateConverter.pointsToHwpunits(indentPt);
        if (indent <= 0) return null;
        return new TextFlowAstMaterializer.SourceLineBreakLayout(indent, true);
    }

    private static List<ASTParagraph> buildSourceStructuredShellTextParagraphs(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf) {
        if (ctx == null || ctx.resolvedData == null || childTf == null || childTf.storyId() == null) {
            return new ArrayList<>();
        }
        TextFlowDocument.TextFlowUnit unit = textFlowUnitForTextFrame(ctx, childTf);
        if (unit != null && unit.paragraphs != null && !unit.paragraphs.isEmpty()) {
            List<ASTParagraph> flowParagraphs = convertShellTextParagraphs(ctx, unit);
            if (flowParagraphs != null && !flowParagraphs.isEmpty()) {
                return flowParagraphs;
            }
        }
        ResolvedStory story = ctx.resolvedData.getStory(childTf.storyId());
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) {
            return new ArrayList<>();
        }
        return convertShellTextParagraphs(ctx, story);
    }

    private static List<ASTParagraph> buildSyntheticShellTextParagraphs(
            ResolvedBuildContext ctx,
            ResolvedTextFrame childTf) {
        List<ASTParagraph> paragraphs = new ArrayList<>();
        String rawText = childTf != null ? childTf.frameVisibleText() : null;
        if (rawText == null) return paragraphs;
        String cleaned = rawText
                .replace("\uFFFC", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] parts = cleaned.split("\n", -1);
        for (String part : parts) {
            String text = part != null ? part.trim() : "";
            if (text.isEmpty()) continue;
            ASTParagraph paragraph = new ASTParagraph();
            paragraph.alignment(shellTextAlignment(ctx, childTf));
            addSyntheticRunsFromTextFrame(ctx, paragraph, childTf, text);
            if (paragraph.items() != null && !paragraph.items().isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
        return postprocessShellTextParagraphs(ctx, paragraphs);
    }

    private static List<ASTParagraph> postprocessShellTextParagraphs(
            ResolvedBuildContext ctx,
            List<ASTParagraph> paragraphs) {
        if (paragraphs == null) return paragraphs;
        for (ASTParagraph paragraph : paragraphs) {
            postprocessShellTextParagraph(ctx, paragraph);
        }
        return paragraphs;
    }

    private static void postprocessShellTextParagraph(ResolvedBuildContext ctx, ASTParagraph paragraph) {
        if (paragraph == null) return;
        MathProcessor.convertMathRunsInParagraph(ctx, paragraph);
        RunPostProcessor.splitOverlineRuns(paragraph);
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
        ResolvedRun sourceRun = firstResolvedRun(ctx, textFrame, text);

        // 화살표 글리프 정규화.
        //
        // 이 text 는 텍스트프레임의 원문(contents)이라 ResolvedDataReader.parseRun 의
        // 정규화를 거치지 않는다. 그래서 화살표 프레임의 "@C" 가 그대로 남았다.
        // sourceRun 의 폰트로 화살표 프레임인지 판정해 텍스트를 "→" 로 바꾼다.
        if (sourceRun != null) {
            text = BTFontGlyphMap.normalizeArrowGlyphText(
                    sourceRun.fontFamily(), sourceRun.charStyle(), text);
        }

        List<ASTTextRun> runs;
        if (sourceRun != null) {
            runs = ResolvedTextFlowAstConverter.convertRunText(
                    text,
                    sourceRun,
                    paragraph,
                    ResolvedTextFlowAstConverter.options()
                            .colorResolver(color -> RunBuilder.resolveColorToHex(ctx, color))
                            .truncateAtParagraphBreak(false));
        } else {
            runs = ResolvedTextFlowAstConverter.convertSyntheticText(text, null, paragraph);
        }
        applyIdmlRunStyleFallback(ctx, textFrame, runs);
        for (ASTTextRun run : runs) {
            paragraph.addItem(run);
        }
    }

    private static void applyIdmlRunStyleFallbackToParagraphs(ResolvedBuildContext ctx,
                                                              ResolvedTextFrame textFrame,
                                                              List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return;
        List<ASTTextRun> runs = new ArrayList<>();
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    runs.add((ASTTextRun) item);
                }
            }
        }
        applyIdmlRunStyleFallback(ctx, textFrame, runs);
    }

    private static ResolvedRun firstResolvedRun(ResolvedBuildContext ctx, ResolvedTextFrame textFrame) {
        return firstResolvedRun(ctx, textFrame, null);
    }

    private static ResolvedRun firstResolvedRun(
            ResolvedBuildContext ctx,
            ResolvedTextFrame textFrame,
            String desiredText) {
        if (ctx == null || ctx.resolvedData == null || textFrame == null || textFrame.storyId() == null) {
            return null;
        }
        String desiredKey = styleRunTextKey(desiredText);
        if (ctx.textFlowDocument != null) {
            ResolvedRun textFlowRun = firstTextFlowRunByStoryId(ctx, textFrame.storyId(), desiredKey);
            if (textFlowRun != null) return textFlowRun;
        }
        ResolvedStory story = ctx.resolvedData.getStory(textFrame.storyId());
        if (story != null && story.paragraphs() != null) {
            ResolvedRun firstVisible = null;
            for (ResolvedParagraph rp : story.paragraphs()) {
                if (rp == null || rp.runs() == null || rp.runs().isEmpty()) continue;
                for (ResolvedRun run : rp.runs()) {
                    if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                    String key = styleRunTextKey(run.text());
                    if (key.isEmpty()) continue;
                    if (!desiredKey.isEmpty() && (key.equals(desiredKey) || key.contains(desiredKey) || desiredKey.contains(key))) {
                        return run;
                    }
                    if (firstVisible == null) firstVisible = run;
                }
            }
            return firstVisible;
        }
        return null;
    }

    private static ResolvedRun firstTextFlowRunByStoryId(
            ResolvedBuildContext ctx,
            String storyId,
            String desiredKey) {
        if (ctx == null || ctx.textFlowDocument == null || storyId == null) return null;
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(storyId);
        return firstTextFlowRun(unit, desiredKey, new ResolvedRun[1]);
    }

    private static ResolvedRun firstTextFlowRun(
            TextFlowDocument.TextFlowUnit unit,
            String desiredKey,
            ResolvedRun[] firstVisible) {
        if (unit == null) return null;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null) continue;
            for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
                if (atom instanceof TextFlowDocument.TextAtom) {
                    TextFlowDocument.TextAtom textAtom = (TextFlowDocument.TextAtom) atom;
                    String key = styleRunTextKey(textAtom.text);
                    if (key.isEmpty() || textAtom.sourceRun == null) continue;
                    if (!desiredKey.isEmpty() && (key.equals(desiredKey) || key.contains(desiredKey) || desiredKey.contains(key))) {
                        return textAtom.sourceRun;
                    }
                    if (firstVisible != null && firstVisible[0] == null) {
                        firstVisible[0] = textAtom.sourceRun;
                    }
                } else if (atom instanceof TextFlowDocument.InlineSlotAtom) {
                    ResolvedRun nested = firstTextFlowRun(
                            ((TextFlowDocument.InlineSlotAtom) atom).nestedFlow,
                            desiredKey,
                            firstVisible);
                    if (nested != null) return nested;
                }
            }
        }
        return firstVisible != null ? firstVisible[0] : null;
    }

    private static String styleRunTextKey(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == 0xFFFC || cp == '\r' || cp == '\n') continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) continue;
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) continue;
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static void applyIdmlRunStyleFallback(ResolvedBuildContext ctx,
                                                  ResolvedTextFrame textFrame,
                                                  List<ASTTextRun> runs) {
        if (ctx == null || ctx.loadIDMLStory == null || textFrame == null
                || textFrame.storyId() == null || runs == null || runs.isEmpty()) {
            return;
        }
        IDMLParagraph paragraph = firstVisibleIdmlParagraph(ctx, textFrame.storyId());
        if (paragraph == null) return;
        IDMLCharacterRun idmlRun = firstVisibleIdmlRun(paragraph);
        if (idmlRun == null) return;
        ASTTextRun template = new ASTTextRun();
        TextStyleApplicator.applyIdmlStyle(
                template,
                idmlRun,
                paragraph.appliedParagraphStyle(),
                ctx.styleResolver,
                ctx.resolvedData);
        for (ASTTextRun run : runs) {
            applyMissingTextStyle(run, template);
        }
    }

    private static IDMLCharacterRun firstVisibleIdmlRun(ResolvedBuildContext ctx, String storyId) {
        IDMLParagraph paragraph = firstVisibleIdmlParagraph(ctx, storyId);
        return firstVisibleIdmlRun(paragraph);
    }

    private static IDMLParagraph firstVisibleIdmlParagraph(ResolvedBuildContext ctx, String storyId) {
        IDMLStory idmlStory = loadIdmlStoryByResolvedId(ctx, storyId);
        if (idmlStory == null || idmlStory.paragraphs() == null) return null;
        for (IDMLParagraph paragraph : idmlStory.paragraphs()) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            if (firstVisibleIdmlRun(paragraph) != null) return paragraph;
        }
        return null;
    }

    private static IDMLCharacterRun firstVisibleIdmlRun(IDMLParagraph paragraph) {
        if (paragraph == null || paragraph.characterRuns() == null) return null;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null || run.content() == null) continue;
            String visible = run.content()
                    .replace("\uFFFC", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim();
            if (!visible.isEmpty()) return run;
        }
        return null;
    }

    private static void applyMissingTextStyle(ASTTextRun target, ASTTextRun template) {
        if (target == null || template == null) return;
        if (isBlank(target.characterStyleRef()) && !isBlank(template.characterStyleRef())) {
            target.characterStyleRef(template.characterStyleRef());
        }
        if (isBlank(target.fontFamily()) && !isBlank(template.fontFamily())) {
            target.fontFamily(template.fontFamily());
        }
        if (isBlank(target.fontStyle()) && !isBlank(template.fontStyle())) {
            target.fontStyle(template.fontStyle());
        }
        if ((target.fontSizeHwpunits() == null || target.fontSizeHwpunits() <= 0)
                && template.fontSizeHwpunits() != null
                && template.fontSizeHwpunits() > 0) {
            target.fontSizeHwpunits(template.fontSizeHwpunits());
        }
        if (isBlank(target.textColor()) && !isBlank(template.textColor())) {
            target.textColor(template.textColor());
        }
        if (target.letterSpacing() == null && template.letterSpacing() != null) {
            target.letterSpacing(template.letterSpacing());
        }
        if (target.horizontalScale() == null && template.horizontalScale() != null) {
            target.horizontalScale(template.horizontalScale());
        }
        if (!target.underline() && template.underline()) {
            target.underline(true);
        }
        if (!target.strikeThrough() && template.strikeThrough()) {
            target.strikeThrough(true);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static IDMLStory loadIdmlStoryByResolvedId(ResolvedBuildContext ctx, String storyId) {
        if (ctx == null || ctx.loadIDMLStory == null || storyId == null) return null;
        IDMLStory story = ctx.loadIDMLStory.apply(storyId);
        if (story != null) return story;
        try {
            int numericId = Integer.parseInt(storyId);
            return ctx.loadIDMLStory.apply("u" + Integer.toHexString(numericId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * source ownership policy: 인라인 앵커가 빈(텍스트 없음) TextFrame 이면서 fillColor 가 있는 데코 박스
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
        if (ctx.isTextFrameOwnedByTextShellPlan(anchoredObjectId)) return null;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)
                || hasPlannedFloatingHwpxTextDescendant(ctx, anchoredObjectId)) {
            return null;
        }
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;

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

                List<ASTInlineItem> plannedItems =
                        loadPlannedInlineAnchorItems(ctx, childId, previousText, nextText);
                if (plannedItems != null) {
                    items.addAll(plannedItems);
                    continue;
                }
                if (!hasOwnershipPlanForAnchorBundle(ctx, childId)) {
                    warnUnplannedNestedInlineAnchorSkipped(ctx, anchoredObjectId, childId);
                }
                continue;
            }

            String text = rr.text();
            if (text == null || text.isEmpty()) continue;
            List<ASTTextRun> textRuns = ResolvedTextFlowAstConverter.convertRunText(
                    text,
                    rr,
                    null,
                    ResolvedTextFlowAstConverter.options()
                            .colorResolver(color -> RunBuilder.resolveColorToHex(ctx, color))
                            .truncateAtParagraphBreak(false));
            for (ASTTextRun textRun : textRuns) {
                if (parentFrameUnderline) textRun.underline(true);
                if (rr.fillColor() != null) {
                    textRun.grepStyleApplied(true);
                }
                items.add(textRun);
            }
        }

        if (items.isEmpty()) return null;
        return items;
    }

    static ASTInlineObject tryInlineTextFrameWithTables(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.loadIDMLStory == null) return null;
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        ASTInlineObject plannedAnchorTextShell = loadPlannedInlineTextShellForAnchor(ctx, anchoredObjectId);
        if (plannedAnchorTextShell != null) return plannedAnchorTextShell;
        ASTInlineObject plannedTextShell = loadPlannedInlineTextShellForTextFrame(ctx, anchoredObjectId);
        if (plannedTextShell != null) return plannedTextShell;
        if (ctx.isTextFrameOwnedByTextShellPlan(anchoredObjectId)) return null;
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
            if (!isHwpxEditableTextFrame(ctx, childTf.id())) continue;
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
        try {
            int tfDomId = Integer.parseInt(tf.id());
            if (loadPlannedInlineTextShellForTextFrame(ctx, tfDomId) != null) return;
        } catch (NumberFormatException ignored) {
            // Non-numeric ids cannot participate in source ownership lookup.
        }
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
        List<ASTParagraph> resolvedParagraphs = StoryConverter.convertStoryParagraphs(ctx, story);
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

    /**
     * SPEC-064: 렌더 그룹이 빈칸 TF 를 실제로 소유하는가 — editable/atomic 소유 TF 목록에
     * 있거나, 빈칸의 pageItems 조상 체인에 렌더 그룹 id 가 있으면 소유로 본다.
     * (기하 포함만으로 밑줄 억제하면 이웃 삽화 PNG 오탐)
     */
    private static boolean renderedGroupOwnsBlankTextFrame(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg,
            int blankTextFrameId) {
        if (ctx == null || rg == null || blankTextFrameId < 0) return false;
        String blankId = String.valueOf(blankTextFrameId);
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds != null) {
            for (String id : editableIds) {
                if (blankId.equals(id)) return true;
            }
        }
        int[] atomicOwned = rg.atomicOwnedTextFrameIds();
        if (atomicOwned != null) {
            for (int id : atomicOwned) {
                if (id == blankTextFrameId) return true;
            }
        }
        // pageItems 부모 체인에서 조상 확인
        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem cur =
                ctx.resolvedData.getPageItem(blankId);
        for (int depth = 0; depth < 16 && cur != null; depth++) {
            String parentId = cur.parentId();
            if (parentId == null || parentId.isEmpty()) return false;
            if (parentId.equals(String.valueOf(rg.id()))) return true;
            cur = ctx.resolvedData.getPageItem(parentId);
        }
        return false;
    }

    static ASTTextRun tryInlineTextFrameAsRun(ResolvedBuildContext ctx, int anchoredObjectId,
                                             String previousText, String nextText) {
        // Phase 2가 floating text box로 승격한 TF → 인라인 런 중복 방지
        if (ctx.isTextDisposed(anchoredObjectId, FrameDisposition.TEXT_BLOCK_PLACED)) return null;
        if (ctx.isTextFrameOwnedByTextShellPlan(anchoredObjectId)) return null;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) return null;
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null) {
            return null;
        }
        if (!tf.isInline()) return null;
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
                                    // SPEC-064: 기하 포함만으로는 오탐 — 옆의 삽화 PNG 가
                                    // 빈칸 rect 를 우연히 덮으면 밑줄이 지워진다 (영어 u1
                                    // p22, 텍스트가 삽화를 감싸는 배치). 렌더 그룹이 빈칸을
                                    // 실제 소유(조상 관계 또는 소유 TF 목록)할 때만 억제.
                                    if (renderedGroupOwnsBlankTextFrame(ctx, rg, anchoredObjectId)) {
                                        parentRenderedWithRule = true;
                                        break;
                                    }
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
                // source ownership policy: 첫 run 이 비어 있는 경우가 있어 (placeholder/empty),
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
                if (rr.fillColor() != null) {
                    run.textColor(RunBuilder.resolveColorToHex(ctx, rr.fillColor()));
                    run.grepStyleApplied(true);
                }
                if (rr.underline() != null && rr.underline()) run.underline(true);
                if (rr.strikeThru() != null && rr.strikeThru()) run.strikeThrough(true);
            }
        }
        applyIdmlRunStyleFallback(ctx, tf, java.util.Collections.singletonList(run));
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
                // source ownership policy: IDML 단락에 RuleBelow="true" 가 있거나 paragraph style 에 ruleBelowOn=true 면
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
        // inline anchor source policy: 빈칸박스 TextFrame(공백 내용)은 실제 bounds 폭에 맞춰 공백 수 계산
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
        ObjectPlan dropOnlyInlinePlan = findDirectDropOnlyInlinePlanForAnchor(ctx, anchoredObjectId);
        if (isRepeatedEmptyInlineTextFramePlaceholderPlan(dropOnlyInlinePlan)) {
            return null;
        }
        if (dropOnlyInlinePlan != null) {
            return createLayoutOnlyInlineSpacer(ctx, anchoredObjectId);
        }

        // renderedFloatingItems에서 해당 ID의 inline_object 또는 Stage 1이
        // 해당 anchor source에 바인딩한 rendered material을 찾는다.
        // 배치 여부는 Stage 1 ObjectPlan의 PLACE_INLINE_PNG만 따른다.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            ObjectPlan directAnchorPlan = findDirectInlineVisualPlanForRenderedAnchor(
                    ctx, anchoredObjectId, rg);
            if (directAnchorPlan != null) {
                plan = directAnchorPlan;
            }
            boolean plannedAnchorMaterial = plan != null
                    && plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                    && (plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    || plan.visualAction == VisualAction.PLACE_TEXT_SHELL)
                    && (isInlineVisualExecutionAnchor(ctx, plan, rg, anchoredObjectId)
                    || isClosedRenderedMaterialForInlineAnchor(rg, anchoredObjectId));
            if (rg.id() != anchoredObjectId && !plannedAnchorMaterial) continue;

            ObjectPlan pagePositionedOwner =
                    findPagePositionedStoryAnchorOwnerPlan(ctx, rg, anchoredObjectId);
            if (pagePositionedOwner != null) {
                return null;
            }
            boolean proceed = plannedAnchorMaterial || isInlineRenderedGroupType(rg);
            if (!proceed && rg.itemType() == null) {
                ResolvedTextFrame ancTf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                proceed = ancTf != null && ancTf.isInline();
            }
            if (proceed) {
                VisualAction plannedVisualAction = plan != null ? plan.visualAction : null;
                Placement plannedPlacement = plan != null ? plan.placement : null;
                boolean placeInlinePng = plannedVisualAction == VisualAction.PLACE_INLINE_PNG
                        && plannedPlacement == Placement.INLINE;
                boolean placeInlineTextShell = plannedVisualAction == VisualAction.PLACE_TEXT_SHELL
                        && plannedPlacement == Placement.INLINE;
                if (plan == null || (!placeInlinePng && !placeInlineTextShell)) {
                    return null;
                }
                if (placeInlinePng && !hasExplicitInlineSourceEvidence(ctx, plan, rg, anchoredObjectId)) {
                    return null;
                }
                if (isInlineTextShellCompanionForEditableText(ctx, anchoredObjectId)) {
                    return null;
                }
                // inline_object PNG를 그대로 사용 (tryInlineGroupAsSingleBadge가 먼저 INLINE_TEXT_FRAME을 시도했으므로
                // 여기 도달했다면 구조 조건 미충족 → PNG fallback이 가장 정확한 표현).
                String effectiveFile = plan.file;
                double[] effectiveBounds = plan.bounds;
                if (effectiveFile == null || effectiveFile.isEmpty()
                        || effectiveBounds == null || effectiveBounds.length < 4) return null;
                File pngFile = new File(ctx.basePath, effectiveFile);
                if (!pngFile.exists()) return null;

                try {
                    byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                    BufferedImage img = ImageIO.read(pngFile);
                    if (img == null) return null;
                    // 2x2 이하 빈 이미지 무시
                    if (img.getWidth() <= 2 && img.getHeight() <= 2) return null;
                    ASTInlineObject obj = new ASTInlineObject();
                    obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                    obj.keepInline(true);
                    obj.imageData(imageData);
                    obj.imageFormat("png");
                    obj.pixelWidth(img.getWidth());
                    obj.pixelHeight(img.getHeight());

                    // 크기: bounds [top, left, bottom, right]
                    double[] bounds = normalizeInlineBoundsToPageLocal(ctx, plan.pageIndex, effectiveBounds);
                    if (bounds != null && bounds.length >= 4) {
                        obj.boundsX(bounds[1]); // rendered X 좌표 (인라인 정렬용)
                        double pxPt = bounds[1] * ctx.scaleFactor;
                        double pyPt = bounds[0] * ctx.scaleFactor;
                        obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pxPt));
                        obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pyPt));
                        double bw = Math.abs(bounds[3] - bounds[1]) * ctx.scaleFactor; // right - left
                        double bh = Math.abs(bounds[2] - bounds[0]) * ctx.scaleFactor; // bottom - top
                        if (bw <= 0 || bh <= 0) return null;
                        // 알파 가시영역 재스케일은 작은 배지/마커 전용이다. 라벨 달린
                        // 삽화 같은 큰 통짜 PNG 는 plan bounds 크기가 원본 그대로라
                        // 재스케일하면 오히려 축소·왜곡된다 (SPEC-057, p47 실측:
                        // 210×113pt 그림이 192×128pt 로 어긋남).
                        boolean smallMarkerCanvas = bh <= 50.0 && bw <= 100.0;
                        if (isInlineCompletePngTextOwnerPlan(plan)
                                && smallMarkerCanvas
                                && img.getWidth() > 0 && img.getHeight() > 0) {
                            // Keep the source pixels intact. Transparent carrier
                            // padding must not reduce the visible marker height:
                            // scale the whole canvas so its visible alpha height
                            // matches the ObjectPlan height.
                            int[] visiblePixels = alphaBounds(img);
                            if (visiblePixels != null && visiblePixels[3] > 0) {
                                double pointsPerPixel = bh / (double) visiblePixels[3];
                                bw = img.getWidth() * pointsPerPixel;
                                bh = img.getHeight() * pointsPerPixel;
                            } else {
                                bh = bw * ((double) img.getHeight() / (double) img.getWidth());
                            }
                        }
                        long wHu = CoordinateConverter.pointsToHwpunits(bw);
                        long hHu = CoordinateConverter.pointsToHwpunits(bh);
                        obj.width(wHu);
                        obj.height(hHu);
                        obj.resolvedWidth(wHu);
                        obj.resolvedHeight(hHu);
                    }

                    obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
                    ResolvedPageItem anchorItem = findInlineAnchorSourceItem(ctx, plan, rg, anchoredObjectId);
                    applyInlineGraphicAnchorAndWrapMetadata(ctx, obj, rg, plan, anchoredObjectId);
                    if (isLargeFloatingAnchoredInlineVisual(anchorItem, obj.width(), obj.height())) {
                        if (obj.anchoredPosition() == null) obj.anchoredPosition("Anchored");
                        obj.affectsLineSpacing(false);
                    }
                    if (rg.inlineSourceTreeClosed() || isInlineMicroVectorPatternShell(plan, rg)) {
                        obj.affectsLineSpacing(false);
                    }

                    // AnchoredPosition="Anchored" 커스텀 위치 앵커: IDML에서 앵커 문자가 공간을 차지하고
                    // 이미지가 앵커 기준 오프셋에 배치되어 이미지 우측~텍스트 시작 사이에 gap이 생김.
                    // HWPX 인라인 배치 시 동일한 시각 간격을 위해 우측 여백 추가.
                    if (ctx.customAnchoredInlineIds != null && ctx.customAnchoredInlineIds.contains(anchoredObjectId)) {
                        obj.textWrapRight(200L); // 2pt 우측 여백
                    }

                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ObjectPlan findDirectInlineVisualPlanForRenderedAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            RenderedGroup rg) {
        if (ctx == null || rg == null || anchoredObjectId < 0) return null;
        ObjectPlan best = null;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectId(anchoredObjectId)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.coordinateSpace != CoordinateSpace.STORY_FLOW) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                    && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
                continue;
            }
            if (!isInlineVisualExecutionAnchor(ctx, plan, rg, anchoredObjectId)) continue;
            if (plan.file == null || !plan.file.equals(rg.file())) continue;
            if (best == null || directInlineVisualPlanPriority(plan) > directInlineVisualPlanPriority(best)) {
                best = plan;
            }
        }
        return best;
    }

    private static boolean isInlineMicroVectorPatternShell(ObjectPlan plan, RenderedGroup rg) {
        String planSlot = plan != null ? plan.slotRole : null;
        String rgSlot = rg != null ? rg.slotRole() : null;
        String rgComposite = rg != null ? rg.compositeRole() : null;
        return "inline_micro_vector_pattern_shell_slot".equals(planSlot)
                || "inline_micro_vector_pattern_shell_slot".equals(rgSlot)
                || "inline_micro_vector_pattern_shell".equals(rgComposite);
    }

    private static int directInlineVisualPlanPriority(ObjectPlan plan) {
        if (plan == null) return 0;
        int score = 0;
        if (plan.domId >= 0) score += 4;
        if (plan.renderId != null) score += 2;
        if (plan.inlineSourceTreeClosed) score += 2;
        if (plan.bounds != null && plan.bounds.length >= 4) score += 1;
        return score;
    }

    private static void applyInlineGraphicAnchorAndWrapMetadata(
            ResolvedBuildContext ctx,
            ASTInlineObject obj,
            RenderedGroup rg,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (ctx == null || obj == null) return;
        IDMLCharacterRun.InlineGraphic graphic =
                findIDMLInlineGraphicForRenderedAnchor(ctx, rg, plan, anchoredObjectId);
        if (graphic == null) return;
        String anchoredPosition = normalizeInlineAnchorValue(graphic.anchoredPosition());
        if (anchoredPosition != null) obj.anchoredPosition(anchoredPosition);
        String wrapMode = normalizeInlineAnchorValue(graphic.textWrapMode());
        if (wrapMode != null && !wrapMode.isEmpty()) obj.textWrapMode(wrapMode);
        String wrapSide = normalizeInlineAnchorValue(graphic.textWrapSide());
        if (wrapSide != null && !wrapSide.isEmpty()) obj.textWrapSide(wrapSide);
        obj.textWrapTop(CoordinateConverter.pointsToHwpunits(graphic.textWrapTop()));
        obj.textWrapLeft(CoordinateConverter.pointsToHwpunits(graphic.textWrapLeft()));
        obj.textWrapBottom(CoordinateConverter.pointsToHwpunits(graphic.textWrapBottom()));
        obj.textWrapRight(CoordinateConverter.pointsToHwpunits(graphic.textWrapRight()));
    }

    private static IDMLCharacterRun.InlineGraphic findIDMLInlineGraphicForRenderedAnchor(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (ctx == null || ctx.loadIDMLStory == null) return null;
        String parentStoryId = rg != null ? rg.parentStoryId() : null;
        if (parentStoryId == null || parentStoryId.isEmpty()) return null;
        IDMLStory story = ctx.loadIDMLStory.apply(parentStoryId);
        if (story == null) return null;
        IDMLCharacterRun.InlineGraphic graphic =
                findIDMLInlineGraphicForRenderedAnchorInParagraphs(
                        story.paragraphs(), plan, rg, anchoredObjectId);
        if (graphic != null) return graphic;
        return findIDMLInlineGraphicForRenderedAnchorInTables(
                story.tables(), plan, rg, anchoredObjectId);
    }

    private static IDMLCharacterRun.InlineGraphic findIDMLInlineGraphicForRenderedAnchorInTables(
            List<IDMLTable> tables,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (tables == null) return null;
        for (IDMLTable table : tables) {
            if (table == null || table.rows() == null) continue;
            for (IDMLTableRow row : table.rows()) {
                if (row == null || row.cells() == null) continue;
                for (IDMLTableCell cell : row.cells()) {
                    if (cell == null) continue;
                    IDMLCharacterRun.InlineGraphic graphic =
                            findIDMLInlineGraphicForRenderedAnchorInParagraphs(
                                    cell.paragraphs(), plan, rg, anchoredObjectId);
                    if (graphic != null) return graphic;
                    graphic = findIDMLInlineGraphicForRenderedAnchorInTables(
                            cell.directNestedTables(), plan, rg, anchoredObjectId);
                    if (graphic != null) return graphic;
                }
            }
        }
        return null;
    }

    private static IDMLCharacterRun.InlineGraphic findIDMLInlineGraphicForRenderedAnchorInParagraphs(
            List<IDMLParagraph> paragraphs,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (paragraphs == null) return null;
        for (IDMLParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.characterRuns() == null) continue;
            for (IDMLCharacterRun run : paragraph.characterRuns()) {
                IDMLCharacterRun.InlineGraphic graphic =
                        findIDMLInlineGraphicForRenderedAnchor(run.inlineGraphics(), plan, rg, anchoredObjectId);
                if (graphic != null) return graphic;
            }
        }
        return null;
    }

    private static IDMLCharacterRun.InlineGraphic findIDMLInlineGraphicForRenderedAnchor(
            List<IDMLCharacterRun.InlineGraphic> graphics,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (graphics == null) return null;
        for (IDMLCharacterRun.InlineGraphic graphic : graphics) {
            if (graphic == null) continue;
            int id = parseInlineSourceDomId(graphic.selfId());
            if (id == anchoredObjectId
                    || containsInt(plan != null ? plan.sourceObjectIds : null, id)
                    || containsInt(plan != null ? plan.visualSourceObjectIds : null, id)
                    || containsInt(plan != null ? plan.exportSourceObjectIds : null, id)
                    || containsInt(rg != null ? rg.sourceObjectIds() : null, id)
                    || containsInt(rg != null ? rg.exportSourceObjectIds() : null, id)) {
                return graphic;
            }
            IDMLCharacterRun.InlineGraphic child =
                    findIDMLInlineGraphicForRenderedAnchor(graphic.childGraphics(), plan, rg, anchoredObjectId);
            if (child != null) return child;
        }
        return null;
    }

    private static int parseInlineSourceDomId(String value) {
        if (value == null || value.isEmpty()) return -1;
        String s = value;
        boolean hexId = s.startsWith("u") || s.startsWith("U");
        if (hexId) s = s.substring(1);
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) break;
            end++;
        }
        if (end <= 0) return -1;
        String token = s.substring(0, end);
        try {
            return Integer.parseInt(token, hexId ? 16 : 10);
        } catch (NumberFormatException ignored) {
            return parseDecimalId(value);
        }
    }

    private static String normalizeInlineAnchorValue(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.startsWith("$ID/")) normalized = normalized.substring("$ID/".length());
        return normalized.isEmpty() ? null : normalized;
    }

    private static ResolvedPageItem findInlineAnchorSourceItem(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        ResolvedPageItem direct = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        if (direct != null) return direct;
        ResolvedPageItem planned = firstFloatingAnchoredInlineItem(ctx, plan != null ? plan.sourceObjectIds : null);
        if (planned != null) return planned;
        planned = firstFloatingAnchoredInlineItem(ctx, plan != null ? plan.visualSourceObjectIds : null);
        if (planned != null) return planned;
        planned = firstFloatingAnchoredInlineItem(ctx, plan != null ? plan.exportSourceObjectIds : null);
        if (planned != null) return planned;
        planned = firstFloatingAnchoredInlineItem(ctx, rg != null ? rg.sourceObjectIds() : null);
        if (planned != null) return planned;
        return firstFloatingAnchoredInlineItem(ctx, rg != null ? rg.exportSourceObjectIds() : null);
    }

    private static ResolvedPageItem firstFloatingAnchoredInlineItem(ResolvedBuildContext ctx, int[] sourceIds) {
        if (ctx == null || ctx.resolvedData == null || sourceIds == null) return null;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (isFloatingAnchoredInlineSource(item)) return item;
        }
        return null;
    }

    private static boolean isLargeFloatingAnchoredInlineVisual(ResolvedPageItem item, long width, long height) {
        if (!isFloatingAnchoredInlineSource(item)) return false;
        return height >= ConverterConstants.INLINE_IMAGE_HEIGHT_THRESHOLD
                || width >= ConverterConstants.INLINE_IMAGE_HEIGHT_THRESHOLD * 2L;
    }

    private static boolean isFloatingAnchoredInlineSource(ResolvedPageItem item) {
        if (item == null) return false;
        if (!item.storyTextInlineSlot() && !item.isInline()) return false;
        String storyAnchorPlacement = upper(item.storyAnchorPlacement());
        String anchoredPosition = upper(item.anchoredPosition());
        return "FLOATING_ANCHORED".equals(storyAnchorPlacement)
                || "ANCHORED".equals(anchoredPosition);
    }

    private static boolean hasExplicitInlineSourceEvidence(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null) return true;
        boolean checked = false;
        boolean foundInline = false;
        if (isExplicitInlineSource(ctx, anchoredObjectId)) foundInline = true;
        checked |= sourceMetadataExists(ctx, anchoredObjectId);
        if (plan != null) {
            InlineEvidence evidence = inlineEvidenceForIds(ctx, plan.sourceObjectIds);
            checked |= evidence.checked;
            foundInline |= evidence.inline;
            evidence = inlineEvidenceForIds(ctx, plan.visualSourceObjectIds);
            checked |= evidence.checked;
            foundInline |= evidence.inline;
            evidence = inlineEvidenceForIds(ctx, plan.exportSourceObjectIds);
            checked |= evidence.checked;
            foundInline |= evidence.inline;
        }
        if (rg != null) {
            InlineEvidence evidence = inlineEvidenceForIds(ctx, rg.sourceObjectIds());
            checked |= evidence.checked;
            foundInline |= evidence.inline;
            evidence = inlineEvidenceForIds(ctx, rg.exportSourceObjectIds());
            checked |= evidence.checked;
            foundInline |= evidence.inline;
        }
        // Legacy extraction outputs may lack source metadata. Do not drop those blindly.
        return !checked || foundInline;
    }

    private static InlineEvidence inlineEvidenceForIds(ResolvedBuildContext ctx, int[] ids) {
        InlineEvidence evidence = new InlineEvidence();
        if (ctx == null || ids == null) return evidence;
        for (int id : ids) {
            if (!sourceMetadataExists(ctx, id)) continue;
            evidence.checked = true;
            if (isExplicitInlineSource(ctx, id)) evidence.inline = true;
        }
        return evidence;
    }

    private static boolean sourceMetadataExists(ResolvedBuildContext ctx, int sourceId) {
        return ctx != null
                && ctx.resolvedData != null
                && ctx.resolvedData.getPageItem(String.valueOf(sourceId)) != null;
    }

    private static boolean isExplicitInlineSource(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        if (item == null) return false;
        if (item.storyTextInlineSlot()) return true;
        String storyAnchorPlacement = upper(item.storyAnchorPlacement());
        String anchoredPosition = upper(item.anchoredPosition());
        if ("FLOATING_ANCHORED".equals(storyAnchorPlacement) || "ANCHORED".equals(anchoredPosition)) {
            return false;
        }
        return "INLINE".equals(storyAnchorPlacement)
                || "INLINE_POSITION".equals(anchoredPosition)
                || "INLINEPOSITION".equals(anchoredPosition);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static final class InlineEvidence {
        boolean checked;
        boolean inline;
    }

    /**
     * Execute the direct Stage 1 ObjectPlan for a story inline anchor.
     *
     * Return value semantics:
     * - null: no direct executable inline ObjectPlan exists for this anchor.
     * - empty list: a direct plan exists and intentionally materializes nothing
     *   (drop/absorb/table-style) or cannot be executed; callers must not try
     *   another materialization for the same source slot.
     * - non-empty list: the planned inline material.
     */
    /**
     * ORC-only 앵커 런이 수식 답란 상자(□) 플레이스홀더인가 — 패키지 밖(공유 스토리
     * 컨버터)에서 MathProcessor 판정을 쓰기 위한 위임. 실체 시각물(콘텐츠 인라인
     * PNG plan)을 가진 앵커면 false 를 반환해 □ 삼킴을 막는다.
     */
    public static boolean isFormulaAnswerPlaceholderAnchorRun(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run) {
        return MathProcessor.isFormulaAnswerPlaceholderRun(ctx, run);
    }

    public static List<ASTInlineItem> loadPlannedInlineAnchorItems(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            String previousText,
            String nextText) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return null;
        List<ASTInlineItem> closedCarrierTextShellItems =
                loadClosedInlineCarrierTextShellItems(ctx, anchoredObjectId);
        if (closedCarrierTextShellItems != null && !closedCarrierTextShellItems.isEmpty()) {
            return closedCarrierTextShellItems;
        }
        List<ASTInlineItem> closedCarrierItems =
                loadClosedInlineCarrierFlowItems(ctx, anchoredObjectId);
        if (!ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)
                && findPagePositionedStoryAnchorOwnerPlan(
                ctx,
                findRenderedGroupByInlineAnchorId(ctx, anchoredObjectId),
                anchoredObjectId) != null) {
            return new ArrayList<>();
        }
        ObjectPlan directCompletePngPlan =
                directInlineCompletePngPlan(ctx, anchoredObjectId);
        if (directCompletePngPlan != null) {
            ASTInlineObject completePng = loadCompleteInlinePngFromPlan(
                    ctx, directCompletePngPlan, anchoredObjectId);
            List<ASTInlineItem> directCompleteItems = new ArrayList<>();
            if (completePng != null) directCompleteItems.add(completePng);
            return directCompleteItems;
        }
        ObjectPlan dropOnlyInlinePlan = findDirectDropOnlyInlinePlanForAnchor(ctx, anchoredObjectId);
        boolean repeatedEmptyInlinePlaceholder =
                isRepeatedEmptyInlineTextFramePlaceholderPlan(dropOnlyInlinePlan);
        boolean hasDirectPlan = hasDirectExecutableInlinePlan(ctx, anchoredObjectId)
                || ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)
                || repeatedEmptyInlinePlaceholder;
        if (!hasDirectPlan && (closedCarrierItems == null || closedCarrierItems.isEmpty())) return null;
        if (closedCarrierItems != null && !closedCarrierItems.isEmpty()) {
            return closedCarrierItems;
        }

        List<ASTInlineItem> items = new ArrayList<>();

        List<ASTInlineObject> inlineShells = loadPlannedInlineTextShellsForAnchor(ctx, anchoredObjectId);
        if (inlineShells != null && !inlineShells.isEmpty()) {
            items.addAll(inlineShells);
            return items;
        }

        ASTInlineObject shell = loadPlannedInlineTextShellForAnchor(ctx, anchoredObjectId);
        if (shell != null) {
            items.add(shell);
            return items;
        }

        if (isSimpleButtonLabelAnchor(ctx, anchoredObjectId)) {
            ASTInlineObject inlineLabel = SimpleButtonLabelInlineFactory.create(ctx, anchoredObjectId);
            if (inlineLabel != null) {
                inlineLabel.keepInline(true);
                items.add(inlineLabel);
                return items;
            }
        }

        ASTInlineObject ownedTextShell = loadPlannedInlineTextShellForTextFrame(ctx, anchoredObjectId);
        if (ownedTextShell != null) {
            items.add(ownedTextShell);
            return items;
        }

        ASTInlineObject ownedTextFrameShell =
                loadPlannedInlineTextShellForOwnedTextFrame(ctx, anchoredObjectId);
        if (ownedTextFrameShell != null) {
            items.add(ownedTextFrameShell);
            return items;
        }

        if (repeatedEmptyInlinePlaceholder) {
            items.add(createSpaceRunForEmptyAnchor(ctx, anchoredObjectId));
            return items;
        }
        if (dropOnlyInlinePlan != null) {
            List<ASTInlineItem> childTextItems =
                    loadLayoutOnlyInlineSlotChildTextItems(ctx, anchoredObjectId);
            if (childTextItems != null && !childTextItems.isEmpty()) {
                items.addAll(childTextItems);
                return items;
            }
            ASTInlineObject spacer = createLayoutOnlyInlineSpacer(ctx, anchoredObjectId);
            if (spacer != null) {
                items.add(spacer);
            }
            return items;
        }

        if (ctx.ownershipPlanPlacesInlineHwpxText(anchoredObjectId)) {
            ASTEquation equation = tryInlineFractionAsEquation(ctx, anchoredObjectId);
            if (equation != null) {
                items.add(equation);
                return items;
            }
            List<ASTInlineItem> textItems = tryInlineTextFrameAsItems(ctx, anchoredObjectId,
                    previousText, nextText);
            if (textItems != null && !textItems.isEmpty()) {
                items.addAll(textItems);
                return items;
            }
            ASTTextRun run = tryInlineTextFrameAsRun(ctx, anchoredObjectId, previousText, nextText);
            if (run != null) {
                items.add(run);
                return items;
            }
            ASTInlineObject tableFrame = tryInlineTextFrameWithTables(ctx, anchoredObjectId);
            if (tableFrame != null) {
                items.add(tableFrame);
                return items;
            }
        }

        ASTInlineObject image = loadInlineObject(ctx, anchoredObjectId);
        if (image != null) {
            items.add(image);
        }
        return items;
    }

    public static List<ASTInlineItem> loadPlannedCellInlineCarrierItems(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.ownershipPlans == null || anchoredObjectId < 0) {
            return null;
        }
        List<ObjectPlan> plans = findCellInlineCarrierTextShellOwnerPlans(ctx, anchoredObjectId);
        if (plans.isEmpty()) return null;

        List<ASTInlineItem> out = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
                continue;
            }
            List<ResolvedTextFrame> childTfs = new ArrayList<>();
            for (int childId : plan.ownedTextFrameIds) {
                ResolvedTextFrame childTf = ctx.resolvedData.getTextFrame(String.valueOf(childId));
                if (childTf == null) {
                    childTfs.clear();
                    break;
                }
                childTfs.add(childTf);
            }
            if (childTfs.isEmpty()) continue;

            ASTInlineObject item = null;
            if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE
                    && plan.placement == Placement.INLINE
                    && plan.coordinateSpace == CoordinateSpace.STORY_FLOW) {
                item = buildNativeInlineShellObject(ctx, plan, anchoredObjectId, childTfs);
            } else {
                RenderedGroup shell = findRenderedGroupForPlan(ctx, plan, anchoredObjectId);
                if (shell == null) {
                    item = buildSourceNativeInlineShellObject(ctx, plan, anchoredObjectId, childTfs);
                } else {
                    ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(shell.id()));
                    item = buildInlineShellObject(ctx, shell.id(), anchorItem, childTfs, shell, plan);
                }
            }
            if (item == null) continue;
            item.keepInline(true);
            out.add(item);
        }
        suppressTrailingGapBetweenInlineShellSequence(out);
        return out.isEmpty() ? null : out;
    }

    private static List<ObjectPlan> findCellInlineCarrierTextShellOwnerPlans(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        List<ObjectPlan> candidates = new ArrayList<>();
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return candidates;
        Map<String, ObjectPlan> byOwnedText = new java.util.LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (!hasHwpxTextOwnershipForOwnedTextFrameIds(ctx, plan)) continue;
            if (!isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) continue;
            if (!isExecutableTextlessShellCarrier(plan)) continue;
            if (!isCellInlineCarrierExecutableShellPlan(ctx, plan, anchoredObjectId)) continue;
            String key = ownedTextFrameKey(plan);
            ObjectPlan existing = byOwnedText.get(key);
            if (existing == null || inlineTextShellPlanPriority(plan) > inlineTextShellPlanPriority(existing)) {
                byOwnedText.put(key, plan);
            }
        }
        candidates.addAll(byOwnedText.values());
        java.util.Collections.sort(candidates, new java.util.Comparator<ObjectPlan>() {
            public int compare(ObjectPlan a, ObjectPlan b) {
                int orderA = inlineAnchorSourceOrder(ctx, anchoredObjectId, a);
                int orderB = inlineAnchorSourceOrder(ctx, anchoredObjectId, b);
                if (orderA != orderB) return Integer.compare(orderA, orderB);
                int byBounds = compareInlinePlanReadingOrder(a, b);
                if (byBounds != 0) return byBounds;
                return Integer.compare(planDepthOrder(a), planDepthOrder(b));
            }
        });
        return candidates;
    }

    private static boolean isCellInlineCarrierExecutableShellPlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (plan == null) return false;
        if (plan.placement == Placement.INLINE && plan.coordinateSpace == CoordinateSpace.STORY_FLOW) {
            return true;
        }
        if (plan.placement != Placement.FLOATING || plan.coordinateSpace != CoordinateSpace.PAGE) {
            return false;
        }
        RenderedGroup shell = findRenderedGroupForPlan(ctx, plan, anchoredObjectId);
        return hasExplicitInlineSourceEvidence(ctx, plan, shell, anchoredObjectId);
    }

    private static List<ASTInlineItem> loadLayoutOnlyInlineSlotChildTextItems(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        ObjectPlan layoutPlan = findDirectDropOnlyInlinePlanForAnchor(ctx, anchoredObjectId);
        if (!isLayoutOnlyInlineSlotPlan(layoutPlan)) return null;
        if (ctx == null || ctx.resolvedData == null) return null;
        String anchorId = String.valueOf(anchoredObjectId);
        Set<String> descendants = ctx.descendantSet(anchorId, 8);
        if (descendants == null) descendants = new HashSet<>();

        List<InlineLayoutChildText> candidates = new ArrayList<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (!isExecutableInlineLayoutChildText(ctx, tf, anchoredObjectId, descendants)) continue;
            List<ASTInlineItem> items = materializeInlineLayoutChildText(ctx, anchoredObjectId, tf);
            if (items == null || items.isEmpty()) continue;
            double[] bounds = bestTextFrameBounds(tf);
            double sortY = validBounds(bounds) ? (bounds[0] + bounds[2]) / 2.0 : 0.0;
            double sortX = validBounds(bounds) ? bounds[1] : 0.0;
            candidates.add(new InlineLayoutChildText(sortY, sortX, items));
        }
        if (candidates.isEmpty()) return null;
        java.util.Collections.sort(candidates, new java.util.Comparator<InlineLayoutChildText>() {
            public int compare(InlineLayoutChildText a, InlineLayoutChildText b) {
                if (Math.abs(a.sortY - b.sortY) > 1.0) {
                    return Double.compare(a.sortY, b.sortY);
                }
                return Double.compare(a.sortX, b.sortX);
            }
        });

        List<ASTInlineItem> out = new ArrayList<>();
        for (InlineLayoutChildText candidate : candidates) {
            out.addAll(candidate.items);
        }
        return out;
    }

    private static boolean isLayoutOnlyInlineSlotPlan(ObjectPlan plan) {
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && plan.visualAction == VisualAction.DROP_VISUAL
                && (plan.textAction == TextAction.DROP_TEXT
                || isDroppedInlineTextShellTextOwnerPlan(plan))
                && ((plan.kind != null && plan.kind.startsWith("layout_only_inline_slot:"))
                || "layout_only_inline_slot".equals(plan.slotRole)
                || "inline_editable_text_shell_composite".equals(plan.slotRole)
                || "planner_declared_layout_only_inline_slot".equals(plan.reason)
                || "page_plane_absorbed_inline_anchor_layout_slot".equals(plan.reason));
    }

    private static boolean isExecutableInlineLayoutChildText(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            int anchoredObjectId,
            Set<String> descendants) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.id() == null) return false;
        int tfDomId = parseDecimalId(tf.id());
        if (tfDomId < 0) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(tf.id());
        if (item == null) return false;
        boolean isAnchorTextFrame = tfDomId == anchoredObjectId;
        String parentId = item.parentId();
        if (!isAnchorTextFrame
                && (parentId == null
                || (!descendants.contains(tf.id()) && !descendants.contains(parentId)))) {
            return false;
        }
        if (!tf.isInline()) return false;
        if (!ctx.ownershipPlanPlacesInlineHwpxText(tfDomId)
                && !isSingleOwnerTextFlow(ctx, tf)) {
            return false;
        }
        if (ctx.isTextDisposed(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED)) return false;
        if (ctx.resolvedData.isTextOwnedByIndesignPng(tf.id())) return false;
        String visible = normalizeInlineShellText(tf.frameVisibleText());
        return !visible.isEmpty();
    }

    private static List<ASTInlineItem> materializeInlineLayoutChildText(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedTextFrame tf) {
        int tfDomId = parseDecimalId(tf.id());
        if (tfDomId < 0) return null;

        ASTInlineObject plannedShell = loadPlannedInlineTextShellForOwnedTextFrame(ctx, tfDomId);
        if (plannedShell != null) {
            List<ASTInlineItem> out = new ArrayList<>();
            out.add(plannedShell);
            return out;
        }

        ResolvedPageItem textFrameItem = ctx.resolvedData.getPageItem(tf.id());
        ASTInlineObject sourceNativeShell = buildNativeInlineShellObjectFromSourceItem(ctx, textFrameItem, tf);
        if (sourceNativeShell != null) {
            List<ASTInlineItem> out = new ArrayList<>();
            out.add(sourceNativeShell);
            return out;
        }

        ResolvedPageItem styleItem = inlineLayoutChildTextStyleItem(ctx, anchoredObjectId, tf);
        ASTInlineObject nativeShell = buildNativeInlineShellObjectFromSourceItem(ctx, styleItem, tf);
        if (nativeShell != null) {
            List<ASTInlineItem> out = new ArrayList<>();
            out.add(nativeShell);
            return out;
        }

        List<ASTInlineItem> textItems = inlineFlowItemsForTextFrame(ctx, tf);
        if (textItems == null || textItems.isEmpty()) return null;
        ctx.setTextDisposition(tfDomId, FrameDisposition.TEXT_BLOCK_PLACED);
        return textItems;
    }

    private static ResolvedPageItem inlineLayoutChildTextStyleItem(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ResolvedTextFrame tf) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.id() == null) return null;
        ResolvedPageItem textItem = ctx.resolvedData.getPageItem(tf.id());
        if (textItem == null || textItem.parentId() == null) return null;
        if (String.valueOf(anchoredObjectId).equals(textItem.parentId())) return null;
        ResolvedPageItem parent = ctx.resolvedData.getPageItem(textItem.parentId());
        if (!isNativeInlineTextShellStyleItem(parent)) return null;
        Set<String> descendants = ctx.descendantSet(String.valueOf(anchoredObjectId), 8);
        if (descendants == null || !descendants.contains(parent.id())) return null;
        return parent;
    }

    private static boolean isNativeInlineTextShellStyleItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"Oval".equals(type) && !"Polygon".equals(type)
                && !"TextFrame".equals(type)) {
            return false;
        }
        String strokeName = item.strokeColorName();
        if (strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)
                && item.strokeWeight() > 0) {
            return true;
        }
        String fillName = item.fillColorName();
        return fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName);
    }

    private static ASTInlineObject buildNativeInlineShellObjectFromSourceItem(
            ResolvedBuildContext ctx,
            ResolvedPageItem styleItem,
            ResolvedTextFrame childTf) {
        if (ctx == null || styleItem == null || childTf == null) return null;
        double[] bounds = styleItem.geometricBounds();
        if (!validBounds(bounds)) return null;
        double w = Math.abs(bounds[3] - bounds[1]);
        double h = Math.abs(bounds[2] - bounds[0]);
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId(ParagraphTextHelpers.domIdToSourceId(childTf.id()));
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.keepInline(true);
        obj.nativeGraphicsAllowed(true);
        obj.noAutoLineWrap(shouldUseNoAutoLineWrap(childTf, true));
        obj.verticalJustification("CenterAlign");
        applyInlineShellShapeStyle(ctx, styleItem, obj);
        buildBadgeParagraph(ctx, childTf, obj);
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return null;
        markInlineShellChildTextPlaced(ctx, childTf);
        return obj;
    }

    private static double[] bestTextFrameBounds(ResolvedTextFrame tf) {
        if (tf == null) return null;
        double[] bounds = tf.geometricBounds();
        if (validBounds(bounds)) return bounds;
        bounds = tf.pageRelativeBounds();
        return validBounds(bounds) ? bounds : null;
    }

    private static final class InlineLayoutChildText {
        final double sortY;
        final double sortX;
        final List<ASTInlineItem> items;

        InlineLayoutChildText(double sortY, double sortX, List<ASTInlineItem> items) {
            this.sortY = sortY;
            this.sortX = sortX;
            this.items = items;
        }
    }

    private static ObjectPlan findPagePositionedStoryAnchorOwnerPlan(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || ctx.resolvedData == null
                || anchoredObjectId < 0) {
            return null;
        }
        ObjectPlan best = null;
        int bestScore = Integer.MIN_VALUE;
        java.util.LinkedHashSet<ObjectPlan> candidates = new java.util.LinkedHashSet<>();
        candidates.addAll(ctx.ownershipPlansForObjectTree(anchoredObjectId, 8));
        if (rg != null) {
            for (ObjectPlan plan : ctx.ownershipPlans) {
                if (plan == null) continue;
                if (planSharesRenderedSource(plan, rg)) {
                    candidates.add(plan);
                }
            }
        }
        for (ObjectPlan plan : candidates) {
            if (!isPagePositionedStoryAnchorOwnerPlan(ctx, plan)) continue;
            if (rg != null && !isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)
                    && !planSharesRenderedSource(plan, rg)) {
                continue;
            }
            int score = pagePositionedStoryAnchorOwnerScore(ctx, plan, rg, anchoredObjectId);
            if (best == null || score > bestScore) {
                best = plan;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean isPagePositionedStoryAnchorOwnerPlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan) {
        if (ctx == null || ctx.resolvedData == null || plan == null) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.coordinateSpace != CoordinateSpace.PAGE) return false;
        if (!plan.hasVisibleVisual()) return false;
        return hasFloatingAnchoredInlineSource(ctx, plan.domId)
                || hasFloatingAnchoredInlineSource(ctx, plan.renderId)
                || hasFloatingAnchoredInlineSource(ctx, plan.sourceObjectIds)
                || hasFloatingAnchoredInlineSource(ctx, plan.visualSourceObjectIds)
                || hasFloatingAnchoredInlineSource(ctx, plan.exportSourceObjectIds);
    }

    private static int pagePositionedStoryAnchorOwnerScore(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        int score = 0;
        if (isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) score += 100;
        if (hasFloatingAnchoredInlineSource(ctx, anchoredObjectId)) score += 40;
        if (rg != null && plan.file != null && plan.file.equals(rg.file())) score += 20;
        if (containsInt(plan.sourceObjectIds, anchoredObjectId)
                || containsInt(plan.visualSourceObjectIds, anchoredObjectId)
                || containsInt(plan.exportSourceObjectIds, anchoredObjectId)) {
            score += 10;
        }
        return score;
    }

    private static boolean hasFloatingAnchoredInlineSource(ResolvedBuildContext ctx, Integer sourceId) {
        return sourceId != null && hasFloatingAnchoredInlineSource(ctx, sourceId.intValue());
    }

    private static boolean hasFloatingAnchoredInlineSource(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.resolvedData == null || sourceId < 0) return false;
        return isFloatingAnchoredInlineSource(
                ctx.resolvedData.getPageItem(String.valueOf(sourceId)));
    }

    private static boolean hasFloatingAnchoredInlineSource(ResolvedBuildContext ctx, int[] sourceIds) {
        if (sourceIds == null) return false;
        for (int sourceId : sourceIds) {
            if (hasFloatingAnchoredInlineSource(ctx, sourceId)) return true;
        }
        return false;
    }

    private static boolean planSharesRenderedSource(ObjectPlan plan, RenderedGroup rg) {
        if (plan == null || rg == null) return false;
        if (plan.file != null && !plan.file.isEmpty() && plan.file.equals(rg.file())) return true;
        if (plan.domId == rg.id()) return true;
        if (plan.renderId != null && plan.renderId == rg.id()) return true;
        return overlaps(plan.sourceObjectIds, rg.sourceObjectIds())
                || overlaps(plan.sourceObjectIds, rg.exportSourceObjectIds())
                || overlaps(plan.visualSourceObjectIds, rg.sourceObjectIds())
                || overlaps(plan.visualSourceObjectIds, rg.exportSourceObjectIds())
                || overlaps(plan.exportSourceObjectIds, rg.sourceObjectIds())
                || overlaps(plan.exportSourceObjectIds, rg.exportSourceObjectIds());
    }

    private static boolean overlaps(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        for (int value : a) {
            if (containsInt(b, value)) return true;
        }
        return false;
    }

    private static RenderedGroup findRenderedGroupByInlineAnchorId(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null
                || anchoredObjectId < 0) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (rg.id() == anchoredObjectId) return rg;
            if (rg.inlineAnchorSourceObjectId() == anchoredObjectId) return rg;
            if (containsInt(rg.sourceObjectIds(), anchoredObjectId)
                    || containsInt(rg.exportSourceObjectIds(), anchoredObjectId)) {
                return rg;
            }
        }
        return null;
    }

    private static List<ASTInlineItem> loadClosedInlineCarrierTextShellItems(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        ClosedInlineCarrier carrier = findClosedInlineCarrier(ctx, anchoredObjectId);
        if (carrier == null || carrier.plan == null) return null;
        ObjectPlan shellPlan = carrier.plan;
        if (shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL
                || shellPlan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                || shellPlan.placement != Placement.INLINE
                || shellPlan.coordinateSpace != CoordinateSpace.STORY_FLOW
                || shellPlan.ownedTextFrameIds == null
                || shellPlan.ownedTextFrameIds.length == 0) {
            return null;
        }
        int[] flowIds = plannedInlineFlowSourceObjectIds(shellPlan);
        if (flowIds == null || flowIds.length == 0) return null;
        ASTInlineObject leadingCompletePng = null;
        for (int sourceId : flowIds) {
            ObjectPlan completePngPlan = directInlineCompletePngPlan(ctx, sourceId);
            if (completePngPlan == null) continue;
            leadingCompletePng = loadCompleteInlinePngFromPlan(ctx, completePngPlan, sourceId);
            if (leadingCompletePng != null) break;
        }
        if (leadingCompletePng == null) return null;

        ASTInlineObject shell = loadPlannedInlineTextShellForAnchor(ctx, anchoredObjectId);
        if (shell == null || shell.paragraphs() == null || shell.paragraphs().isEmpty()) return null;
        ASTParagraph first = shell.paragraphs().get(0);
        if (first == null || first.items() == null) return null;
        first.items().add(0, leadingCompletePng);
        shell.textMarginLeft(0L);
        shell.squeezeLineWrap(true);
        shell.keepInline(true);

        List<ASTInlineItem> out = new ArrayList<>();
        out.add(shell);
        return out;
    }

    private static List<ASTInlineItem> loadClosedInlineCarrierFlowItems(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        ClosedInlineCarrier carrier = findClosedInlineCarrier(ctx, anchoredObjectId);
        if (carrier == null || carrier.rendered == null) return null;
        int[] flowIds = plannedInlineFlowSourceObjectIds(carrier.plan);
        if (flowIds == null || flowIds.length == 0) {
            warnClosedInlineCarrierFlowOrderMissing(ctx, carrier.plan);
            return null;
        }
        List<ASTInlineItem> out = new ArrayList<>();
        boolean imageAdded = false;
        boolean textAdded = false;
        boolean previousWasText = false;
        ObjectPlan previousImagePlan = null;
        for (int sourceId : flowIds) {
            ObjectPlan completePngPlan = !imageAdded
                    ? directInlineCompletePngPlan(ctx, sourceId)
                    : null;
            if (completePngPlan != null) {
                ASTInlineObject completePng =
                        loadCompleteInlinePngFromPlan(ctx, completePngPlan, sourceId);
                if (completePng == null) return null;
                if (!out.isEmpty()) appendLineBreak(out);
                out.add(completePng);
                imageAdded = true;
                previousImagePlan = completePngPlan;
                previousWasText = false;
                continue;
            }
            if (isClosedCarrierFlowTextSource(ctx, carrier.plan, sourceId)) {
                ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(sourceId));
                if (tf == null || tf.sourceHidden()) continue;
                List<ASTInlineItem> items = inlineFlowItemsForTextFrame(ctx, tf);
                if (items == null || items.isEmpty()) continue;
                if (!out.isEmpty()) {
                    if (previousWasText) appendSpaceRun(out);
                    else appendLineBreak(out);
                }
                out.addAll(items);
                try {
                    ctx.setTextDisposition(Integer.parseInt(tf.id()), FrameDisposition.TEXT_BLOCK_PLACED);
                } catch (NumberFormatException ignored) {
                    // Non-numeric ids cannot be registered in source ownership disposition.
                }
                textAdded = true;
                previousWasText = true;
                continue;
            }
            if (!imageAdded && isClosedCarrierFlowVisualSource(carrier.plan, sourceId)) {
                ASTInlineObject image = loadInlineObject(ctx, anchoredObjectId);
                if (image == null) return null;
                if (!out.isEmpty()) appendLineBreak(out);
                out.add(image);
                imageAdded = true;
                previousImagePlan = carrier.plan;
                previousWasText = false;
            }
        }
        if (!textAdded && carrier.plan.ownedTextFrameIds != null) {
            List<ResolvedTextFrame> remainingTextFrames = new ArrayList<>();
            for (int textFrameId : carrier.plan.ownedTextFrameIds) {
                ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
                if (tf != null && !tf.sourceHidden()) remainingTextFrames.add(tf);
            }
            if (!remainingTextFrames.isEmpty()) {
                if (!out.isEmpty()) {
                    if (sharesSourceRow(ctx, previousImagePlan, remainingTextFrames)) {
                        markLastInlineVisualLineNeutral(out);
                    } else {
                        appendLineBreak(out);
                    }
                }
                appendClosedCarrierTextItems(ctx, out, remainingTextFrames);
                textAdded = true;
            }
        }
        if (!textAdded || !imageAdded) return null;
        return out;
    }

    /**
     * Preserve the source row when a closed inline carrier owns a visual child and
     * editable sibling text. Bounds only execute the already-decided inline plan;
     * they never change text or visual ownership.
     */
    private static boolean sharesSourceRow(
            ResolvedBuildContext ctx,
            ObjectPlan visualPlan,
            List<ResolvedTextFrame> textFrames) {
        if (visualPlan == null || visualPlan.bounds == null
                || visualPlan.bounds.length < 4
                || textFrames == null || textFrames.isEmpty()) {
            return false;
        }
        double[] visualBounds = renderedBoundsPoints(ctx, visualPlan.bounds);
        if (visualBounds == null) return false;
        double visualTop = Math.min(visualBounds[0], visualBounds[2]);
        double visualBottom = Math.max(visualBounds[0], visualBounds[2]);
        double visualHeight = visualBottom - visualTop;
        if (visualHeight <= 0) return false;

        for (ResolvedTextFrame tf : textFrames) {
            double[] textBounds = textFrameBoundsPoints(ctx, tf);
            if (textBounds == null || textBounds.length < 4) return false;
            double textTop = Math.min(textBounds[0], textBounds[2]);
            double textBottom = Math.max(textBounds[0], textBounds[2]);
            double textHeight = textBottom - textTop;
            if (textHeight <= 0) return false;
            double overlap = Math.min(visualBottom, textBottom)
                    - Math.max(visualTop, textTop);
            if (overlap <= 0
                    || overlap / Math.min(visualHeight, textHeight) < 0.5) {
                return false;
            }
        }
        return true;
    }

    private static void markLastInlineVisualLineNeutral(List<ASTInlineItem> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTInlineObject) {
                ((ASTInlineObject) item).sourceRowLineNeutral(true);
                return;
            }
        }
    }

    private static ObjectPlan directInlineCompletePngPlan(
            ResolvedBuildContext ctx,
            int sourceId) {
        if (ctx == null || ctx.ownershipPlans == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!isExecutableCompleteSimpleButtonInlinePlan(plan)) continue;
            if (plan.domId == sourceId
                    || (plan.renderId != null && plan.renderId == sourceId)) {
                return plan;
            }
        }
        return null;
    }

    public static boolean applyClosedInlineCarrierTextAlignment(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ASTParagraph paragraph) {
        if (paragraph == null) return false;
        if (paragraph.alignment() != null && !paragraph.alignment().trim().isEmpty()) {
            return false;
        }
        ClosedInlineCarrier carrier = findClosedInlineCarrier(ctx, anchoredObjectId);
        if (carrier == null) return false;
        int[] hiddenIds = plannedHiddenVisualSourceObjectIds(carrier.plan);
        if (hiddenIds == null || hiddenIds.length == 0) return false;
        for (int hiddenId : hiddenIds) {
            if (!ctx.ownershipPlanPlacesInlineHwpxText(hiddenId)) continue;
            ResolvedTextFrame tf = ctx.resolvedData != null
                    ? ctx.resolvedData.getTextFrame(String.valueOf(hiddenId))
                    : null;
            if (tf == null || tf.sourceHidden()) continue;
            String alignment = shellTextAlignment(ctx, tf);
            if (alignment == null || alignment.isEmpty()) continue;
            paragraph.alignment(closedInlineCarrierParagraphAlignment(alignment));
            return true;
        }
        return false;
    }

    private static String closedInlineCarrierParagraphAlignment(String alignment) {
        if (alignment == null) return null;
        String normalized = alignment.toLowerCase(Locale.ROOT);
        if (normalized.contains("left")) return "LEFT_ALIGN";
        if (normalized.contains("right")) return "RIGHT_ALIGN";
        if (normalized.contains("center")) return "CENTER_ALIGN";
        return alignment;
    }

    private static ClosedInlineCarrier findClosedInlineCarrier(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || ctx.resolvedData == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null
                    || plan.placement != Placement.INLINE
                    || (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                        && plan.visualAction != VisualAction.PLACE_TEXT_SHELL)) {
                continue;
            }
            boolean direct = isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId);
            RenderedGroup rg = direct
                    ? findRenderedGroupForDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)
                    : findRenderedGroupForPlan(ctx, plan, anchoredObjectId);
            if (!direct && !isClosedRenderedMaterialForInlineAnchor(rg, anchoredObjectId)) continue;
            if (rg == null || !plan.inlineSourceTreeClosed) continue;
            return new ClosedInlineCarrier(plan, rg);
        }
        return null;
    }

    private static boolean isClosedCarrierFlowTextSource(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int sourceId) {
        return containsInt(plan != null ? plan.hiddenVisualSourceObjectIds : null, sourceId)
                && ctx != null
                && ctx.ownershipPlanPlacesInlineHwpxText(sourceId);
    }

    private static boolean isClosedCarrierFlowVisualSource(ObjectPlan plan, int sourceId) {
        if (plan == null || containsInt(plan.hiddenVisualSourceObjectIds, sourceId)) return false;
        if (containsInt(plan.exportSourceObjectIds, sourceId)) return true;
        if (containsInt(plan.visualSourceObjectIds, sourceId)) return true;
        return containsInt(plan.sourceObjectIds, sourceId);
    }

    private static void warnClosedInlineCarrierFlowOrderMissing(
            ResolvedBuildContext ctx,
            ObjectPlan plan) {
        if (ctx == null || ctx.ownershipWarningLines == null || plan == null) return;
        if (plan.hiddenVisualSourceObjectIds == null || plan.hiddenVisualSourceObjectIds.length == 0) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE3_CLOSED_INLINE_CARRIER_FLOW_ORDER_MISSING\""
                + ",\"stage\":\"stage3\",\"detail\":\"plan="
                + ObjectPlan.escape(plan.kind + ":" + plan.domId)
                + " has hiddenVisualSourceObjectIds but no inlineFlowSourceObjectIds\"}");
    }

    private static void appendClosedCarrierTextItems(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> out,
            List<ResolvedTextFrame> textFrames) {
        if (out == null || textFrames == null || textFrames.isEmpty()) return;
        boolean firstFrame = true;
        for (ResolvedTextFrame tf : textFrames) {
            List<ASTInlineItem> items = inlineFlowItemsForTextFrame(ctx, tf);
            if (items == null || items.isEmpty()) continue;
            if (!firstFrame) appendSpaceRun(out);
            out.addAll(items);
            try {
                ctx.setTextDisposition(Integer.parseInt(tf.id()), FrameDisposition.TEXT_BLOCK_PLACED);
            } catch (NumberFormatException ignored) {
                // Non-numeric ids cannot be registered in source ownership disposition.
            }
            firstFrame = false;
        }
    }

    private static List<ASTInlineItem> inlineFlowItemsForTextFrame(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf) {
        if (ctx == null || tf == null) return null;
        List<ASTParagraph> paragraphs = null;
        if (tf.storyId() != null) {
            paragraphs = convertShellTextParagraphs(ctx, textFlowUnitForTextFrame(ctx, tf));
            if (paragraphs == null || paragraphs.isEmpty()) {
                paragraphs = convertShellTextParagraphs(ctx, ctx.resolvedData.getStory(tf.storyId()));
            }
        }
        if (paragraphs == null || paragraphs.isEmpty()) {
            String text = tf.frameVisibleText();
            if (text == null) return null;
            text = text.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
            if (text.isEmpty()) return null;
            ASTParagraph paragraph = new ASTParagraph();
            addSyntheticRunsFromTextFrame(ctx, paragraph, tf, text);
            paragraphs = new ArrayList<>();
            paragraphs.add(paragraph);
        }
        List<ASTInlineItem> out = new ArrayList<>();
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null || paragraph.items().isEmpty()) continue;
            if (!out.isEmpty()) appendLineBreak(out);
            out.addAll(paragraph.items());
        }
        return out;
    }

    private static int[] plannedHiddenVisualSourceObjectIds(ObjectPlan plan) {
        return plan != null && plan.hiddenVisualSourceObjectIds != null
                && plan.hiddenVisualSourceObjectIds.length > 0
                ? plan.hiddenVisualSourceObjectIds
                : null;
    }

    private static int[] plannedInlineFlowSourceObjectIds(ObjectPlan plan) {
        return plan != null && plan.inlineFlowSourceObjectIds != null
                && plan.inlineFlowSourceObjectIds.length > 0
                ? plan.inlineFlowSourceObjectIds
                : null;
    }

    private static void appendLineBreak(List<ASTInlineItem> items) {
        if (items == null) return;
        if (!items.isEmpty() && items.get(items.size() - 1) instanceof ASTBreak) return;
        items.add(new ASTBreak(ASTBreak.BreakType.LINE));
    }

    private static void appendSpaceRun(List<ASTInlineItem> items) {
        ASTTextRun space = new ASTTextRun();
        space.text(" ");
        items.add(space);
    }

    private static final class ClosedInlineCarrier {
        final ObjectPlan plan;
        final RenderedGroup rendered;

        ClosedInlineCarrier(ObjectPlan plan, RenderedGroup rendered) {
            this.plan = plan;
            this.rendered = rendered;
        }
    }

    private static boolean hasDirectExecutableInlinePlan(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) return true;
        }
        return false;
    }

    public static boolean hasDirectOwnershipPlanForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null) continue;
            if (isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) return true;
        }
        return false;
    }

    public static boolean hasOwnershipPlanForAnchorBundle(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return false;
        return !ctx.ownershipPlansForObjectTree(anchoredObjectId, 8).isEmpty();
    }

    private static boolean hasStage1ObjectPlans(ResolvedBuildContext ctx) {
        return ctx != null && ctx.ownershipPlans != null && !ctx.ownershipPlans.isEmpty();
    }

    private static void warnUnplannedNestedInlineAnchorSkipped(
            ResolvedBuildContext ctx,
            int parentAnchoredObjectId,
            int childAnchoredObjectId) {
        if (ctx == null) return;
        ctx.ownershipWarningLines.add("{\"code\":\"STAGE2_UNPLANNED_NESTED_INLINE_ANCHOR_SKIPPED\""
                + ",\"parentAnchoredObjectId\":" + parentAnchoredObjectId
                + ",\"anchoredObjectId\":" + childAnchoredObjectId
                + ",\"detail\":\"InlineFrameHandler did not synthesize nested inline material without a Stage 1 ObjectPlan\"}");
    }

    public static boolean hasDirectDropOnlyInlinePlanForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        return findDirectDropOnlyInlinePlanForAnchor(ctx, anchoredObjectId) != null;
    }

    private static ObjectPlan findDirectDropOnlyInlinePlanForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return null;
        boolean dropOnly = false;
        ObjectPlan dropPlan = null;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (!isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) continue;
            boolean droppedTextOwnerShell = isDroppedInlineTextShellTextOwnerPlan(plan);
            if (plan.hasVisibleVisual()
                    || (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT && !droppedTextOwnerShell)
                    || (isShellPlanWithOwnedHwpxText(ctx, plan) && !droppedTextOwnerShell)) {
                return null;
            }
            if (plan.visualAction == VisualAction.DROP_VISUAL
                    && (plan.textAction == TextAction.DROP_TEXT
                    || plan.textAction == TextAction.OWNED_BY_PNG
                    || droppedTextOwnerShell)) {
                dropOnly = true;
                if (dropPlan == null) dropPlan = plan;
            }
        }
        return dropOnly ? dropPlan : null;
    }

    private static boolean isDroppedInlineTextShellTextOwnerPlan(ObjectPlan plan) {
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && plan.visualAction == VisualAction.DROP_VISUAL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && ShellRole.isTextShell(plan)
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private static boolean isRepeatedEmptyInlineTextFramePlaceholderPlan(ObjectPlan plan) {
        return plan != null
                && ("repeated_empty_inline_text_frame_placeholder".equals(plan.reason)
                || "inline_rule_below_whitespace_placeholder".equals(plan.reason));
    }

    private static ASTInlineObject createLayoutOnlyInlineSpacer(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || anchoredObjectId < 0) return null;
        // FLOATING_ANCHORED 소스는 박스가 줄 밖(페이지 좌표)에 있어 원본에서
        // 앵커 줄 높이에 기여하지 않는다 — 슬롯 예약이 줄간격만 밀어올린다
        // (실측: 과학 u1 p19 답안영역 rect 6mm 높이가 질문 줄에 17pt 유령
        // 테이블로 들어가 마커 줄과의 간격이 벌어짐).
        if (ctx.resolvedData != null && isFloatingAnchoredInlineSource(
                ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId)))) {
            return null;
        }
        ObjectPlan plan = findDirectDropOnlyInlinePlanForAnchor(ctx, anchoredObjectId);
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return null;
        double[] boundsPt = renderedBoundsPoints(ctx, plan.bounds);
        if (boundsPt == null || boundsPt.length < 4) return null;
        double w = Math.abs(boundsPt[3] - boundsPt[1]);
        double h = Math.abs(boundsPt[2] - boundsPt[0]);
        if (w <= 0 && h <= 0) return null;
        if (isVerticalFlowSpacerStyle(ctx, anchoredObjectId)) {
            w = 0.1;
        }
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.sourceId("u" + Integer.toHexString(anchoredObjectId));
        obj.width(CoordinateConverter.pointsToHwpunits(Math.max(0.1, w)));
        obj.height(CoordinateConverter.pointsToHwpunits(Math.max(0.1, h)));
        obj.textWrapMode("None");
        obj.keepInline(true);
        obj.layoutOnlyInlineSlot(true);
        return obj;
    }

    private static boolean isVerticalFlowSpacerStyle(ResolvedBuildContext ctx, int anchoredObjectId) {
        String style = inlineGraphicObjectStyle(ctx, anchoredObjectId);
        if (style == null || style.isBlank()) return false;
        String normalized = style.toLowerCase(Locale.ROOT);
        // Source metadata, not page/text/coordinate exception: these layout-only
        // inline slots reserve vertical paragraph breathing room, not horizontal advance.
        return normalized.contains("하단간격")
                || normalized.contains("bottom-spacing")
                || normalized.contains("bottom_spacing")
                || normalized.contains("bottom space");
    }

    private static String inlineGraphicObjectStyle(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.loadIDMLStory == null) return null;
        String sourceId = ParagraphTextHelpers.domIdToSourceId(String.valueOf(anchoredObjectId));
        if (sourceId == null) return null;
        for (ResolvedStory resolvedStory : ctx.resolvedData.stories()) {
            if (resolvedStory == null || resolvedStory.id() == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(resolvedStory.id());
            String style = inlineGraphicObjectStyle(idmlStory, sourceId);
            if (style != null && !style.isBlank()) return style;
        }
        return null;
    }

    private static String inlineGraphicObjectStyle(IDMLStory story, String sourceId) {
        if (story == null || sourceId == null) return null;
        for (IDMLCharacterRun.InlineGraphic graphic : story.getAllInlineGraphics()) {
            String style = inlineGraphicObjectStyle(graphic, sourceId);
            if (style != null && !style.isBlank()) return style;
        }
        return null;
    }

    private static String inlineGraphicObjectStyle(
            IDMLCharacterRun.InlineGraphic graphic,
            String sourceId) {
        if (graphic == null) return null;
        if (sourceId.equals(graphic.selfId())) return graphic.appliedObjectStyle();
        if (graphic.childGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic child : graphic.childGraphics()) {
                String style = inlineGraphicObjectStyle(child, sourceId);
                if (style != null && !style.isBlank()) return style;
            }
        }
        return null;
    }

    public static ASTInlineObject loadPlannedInlineTextShellForTextFrame(
            ResolvedBuildContext ctx,
            int textFrameId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
        if (tf == null || !tf.isInline()) return null;
        RenderedGroup shell = findTextHiddenInlineShellForTextFrame(ctx, tf.id());
        if (shell == null) return null;
        java.util.List<ResolvedTextFrame> ownedChildren = badgeTextFramesSortedByReading(ctx, shell);
        if (visibleShellTextFrameCount(ownedChildren) > 1) return null;
        VisualAction action = ctx.visualActionByOwnershipPlan(shell);
        if (ctx.placementByOwnershipPlan(shell) != Placement.INLINE
                || (action != VisualAction.PLACE_TEXT_SHELL
                && !isInlineTextShellCompanionForEditableText(ctx, shell.id()))) {
            return null;
        }
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(shell.id()));
        return buildInlineShellObject(ctx, shell.id(), anchorItem, tf, shell);
    }

    public static boolean isInlineTextShellCompanionForEditableText(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        java.util.List<RenderedGroup> groups = ctx.resolvedData.allRenderedFloatingItems();
        if (groups == null) return false;
        for (RenderedGroup rg : groups) {
            if (rg == null || rg.id() != anchoredObjectId) continue;
            if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan != null
                    && plan.placement == Placement.INLINE
                    && ShellRole.isTextShell(plan)
                    && hasHwpxTextOwnershipForOwnedTextFrameIds(ctx, plan)
                    && plan.ownedTextFrameIds != null
                    && plan.ownedTextFrameIds.length > 0) {
                return true;
            }
        }
        return false;
    }

    public static ASTInlineObject loadPlannedInlineTextShellForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        ObjectPlan plan = findInlineTextShellOwnerPlanForAnchor(ctx, anchoredObjectId);
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return null;
        }
        List<ResolvedTextFrame> childTfs = new ArrayList<>();
        for (int childId : plan.ownedTextFrameIds) {
            ResolvedTextFrame childTf = ctx.resolvedData.getTextFrame(String.valueOf(childId));
            if (childTf == null) return null;
            childTfs.add(childTf);
        }
        if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) {
            return buildNativeInlineShellObject(ctx, plan, anchoredObjectId, childTfs);
        }

        RenderedGroup shell = findRenderedGroupForDirectInlineAnchorPlan(ctx, plan, anchoredObjectId);
        if (shell == null) {
            return buildSourceNativeInlineShellObject(ctx, plan, anchoredObjectId, childTfs);
        }
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(shell.id()));
        return buildInlineShellObject(ctx, shell.id(), anchorItem, childTfs, shell, plan);
    }

    public static List<ASTInlineObject> loadPlannedInlineTextShellsForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || ctx.ownershipPlans == null || anchoredObjectId < 0) {
            return null;
        }
        List<ObjectPlan> plans = findInlineTextShellOwnerPlansForAnchor(ctx, anchoredObjectId);
        if (plans.size() <= 1) return null;

        List<ASTInlineObject> out = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
                return null;
            }
            List<ResolvedTextFrame> childTfs = new ArrayList<>();
            for (int childId : plan.ownedTextFrameIds) {
                ResolvedTextFrame childTf = ctx.resolvedData.getTextFrame(String.valueOf(childId));
                if (childTf == null) return null;
                childTfs.add(childTf);
            }

            ASTInlineObject item;
            if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) {
                item = buildNativeInlineShellObject(ctx, plan, anchoredObjectId, childTfs);
            } else {
                RenderedGroup shell = findRenderedGroupForDirectInlineAnchorPlan(ctx, plan, anchoredObjectId);
                if (shell == null) {
                    item = buildSourceNativeInlineShellObject(ctx, plan, anchoredObjectId, childTfs);
                } else {
                    ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(shell.id()));
                    item = buildInlineShellObject(ctx, shell.id(), anchorItem, childTfs, shell, plan);
                }
            }
            if (item == null) return null;
            item.keepInline(true);
            out.add(item);
        }
        suppressTrailingGapBetweenInlineShellSequence(out);
        return out.isEmpty() ? null : out;
    }

    private static void suppressTrailingGapBetweenInlineShellSequence(List<? extends ASTInlineItem> items) {
        if (items == null || items.size() <= 1) return;
        for (int i = 0; i < items.size() - 1; i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTInlineObject) {
                ((ASTInlineObject) item).suppressInlineTrailingGap(true);
            }
        }
    }

    private static ASTInlineObject buildNativeInlineShellObject(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            int anchoredObjectId,
            List<ResolvedTextFrame> childTfs) {
        if (ctx == null || ctx.resolvedData == null || shellPlan == null
                || childTfs == null || childTfs.isEmpty()) {
            return null;
        }
        if (!isExecutableNativeInlineShellPlan(shellPlan)) return null;
        ResolvedPageItem shellItem = findNativeInlineShellStyleItem(ctx, shellPlan, anchoredObjectId);
        if (shellItem == null) return null;
        double[] shellBounds = shellPlan.bounds;

        double w = Math.abs(shellBounds[3] - shellBounds[1]) * ctx.scaleFactor;
        double h = Math.abs(shellBounds[2] - shellBounds[0]) * ctx.scaleFactor;
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId("u" + Integer.toHexString(shellPlan.domId));
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.keepInline(true);
        applyInlineShellShapeStyle(ctx, shellItem, obj);
        applyOwnedTextFrameShellShapeStyle(ctx, childTfs, obj);
        applyPlannedInlineExecutionHints(obj, shellPlan);
        obj.noAutoLineWrap(childTfs.size() == 1 && shouldUseNoAutoLineWrap(childTfs.get(0), true));
        obj.verticalJustification(inlineTextShellVerticalJustification(ctx, shellPlan, shellItem, childTfs));
        applyInlineShellTextMargins(ctx, obj, shellPlan, shellItem, childTfs);
        if (visibleShellTextFrameCount(childTfs) > 1) {
            buildCompositeInlineShellParagraph(ctx, childTfs, obj);
            for (ResolvedTextFrame childTf : childTfs) {
                markInlineShellChildTextPlaced(ctx, childTf);
            }
        } else {
            for (ResolvedTextFrame childTf : childTfs) {
                if (isOrcCarrierTextFrame(childTf)) {
                    continue;
                }
                buildBadgeParagraph(ctx, childTf, obj);
                markInlineShellChildTextPlaced(ctx, childTf);
            }
        }
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return null;
        return obj;
    }

    private static ASTInlineObject buildSourceNativeInlineShellObject(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            int anchoredObjectId,
            List<ResolvedTextFrame> childTfs) {
        if (!canExecuteSourceNativeInlineShellPlan(ctx, shellPlan, anchoredObjectId, childTfs)) {
            return null;
        }
        ResolvedPageItem shellItem = findSourceNativeInlineShellStyleItem(ctx, shellPlan, anchoredObjectId);
        if (shellItem == null) return null;
        double[] shellBounds = validBounds(shellPlan.bounds) ? shellPlan.bounds : shellItem.geometricBounds();
        if (!validBounds(shellBounds)) return null;
        double w = Math.abs(shellBounds[3] - shellBounds[1]) * ctx.scaleFactor;
        double h = Math.abs(shellBounds[2] - shellBounds[0]) * ctx.scaleFactor;
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        int sourceId = anchoredObjectId >= 0 ? anchoredObjectId : shellPlan.domId;
        if (sourceId < 0 && childTfs.size() == 1) {
            sourceId = parseDecimalId(childTfs.get(0).id());
        }
        if (sourceId >= 0) {
            obj.sourceId("u" + Integer.toHexString(sourceId));
        }
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.keepInline(true);
        obj.nativeGraphicsAllowed(true);
        applyInlineShellShapeStyle(ctx, shellItem, obj);
        applyOwnedTextFrameShellShapeStyle(ctx, childTfs, obj);
        applyPlannedInlineExecutionHints(obj, shellPlan);
        obj.noAutoLineWrap(childTfs.size() == 1 && shouldUseNoAutoLineWrap(childTfs.get(0), true));
        obj.verticalJustification(inlineTextShellVerticalJustification(ctx, shellPlan, shellItem, childTfs));
        applyInlineShellTextMargins(ctx, obj, shellPlan, shellItem, childTfs);
        if (visibleShellTextFrameCount(childTfs) > 1) {
            buildCompositeInlineShellParagraph(ctx, childTfs, obj);
        } else {
            for (ResolvedTextFrame childTf : childTfs) {
                if (isOrcCarrierTextFrame(childTf)) continue;
                buildBadgeParagraph(ctx, childTf, obj);
            }
        }
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return null;
        for (ResolvedTextFrame childTf : childTfs) {
            markInlineShellChildTextPlaced(ctx, childTf);
        }
        return obj;
    }

    private static boolean canExecuteSourceNativeInlineShellPlan(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            int anchoredObjectId,
            List<ResolvedTextFrame> childTfs) {
        if (ctx == null || ctx.resolvedData == null || shellPlan == null
                || childTfs == null || childTfs.isEmpty()) {
            return false;
        }
        if (shellPlan.placement != Placement.INLINE
                || shellPlan.coordinateSpace != CoordinateSpace.STORY_FLOW
                || shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL
                || shellPlan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                || !ShellRole.isTextShell(shellPlan)
                || !hasHwpxTextOwnershipForChildren(ctx, shellPlan, childTfs)) {
            return false;
        }
        return findSourceNativeInlineShellStyleItem(ctx, shellPlan, anchoredObjectId) != null;
    }

    private static ResolvedPageItem findSourceNativeInlineShellStyleItem(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || shellPlan == null) return null;
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        if (isSourceNativeInlineShellStyleSource(shellPlan, anchorItem)) return anchorItem;
        int[] styleIds = shellPlan.styleSourceObjectIds != null && shellPlan.styleSourceObjectIds.length > 0
                ? shellPlan.styleSourceObjectIds
                : shellPlan.visualSourceObjectIds;
        if (styleIds != null) {
            for (int styleId : styleIds) {
                ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(styleId));
                if (isSourceNativeInlineShellStyleSource(shellPlan, item)) return item;
            }
        }
        ResolvedPageItem planItem = ctx.resolvedData.getPageItem(String.valueOf(shellPlan.domId));
        return isSourceNativeInlineShellStyleSource(shellPlan, planItem) ? planItem : null;
    }

    private static boolean isSourceNativeInlineShellStyleSource(ObjectPlan shellPlan, ResolvedPageItem item) {
        if (shellPlan == null || item == null || !hasVisibleFillOrStroke(item)) return false;
        int itemId = parseIntOrDefault(item.id(), -1);
        return containsInt(shellPlan.sourceObjectIds, itemId)
                || containsInt(shellPlan.visualSourceObjectIds, itemId)
                || containsInt(shellPlan.styleSourceObjectIds, itemId)
                || shellPlan.domId == itemId;
    }

    private static boolean isExecutableNativeInlineShellPlan(ObjectPlan shellPlan) {
        return shellPlan != null
                && shellPlan.placement == Placement.INLINE
                && shellPlan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && shellPlan.materialization == Materialization.NATIVE_SOURCE_SHAPE
                && ShellRole.isTextShell(shellPlan)
                && shellPlan.bounds != null
                && shellPlan.bounds.length >= 4
                && Math.abs(shellPlan.bounds[3] - shellPlan.bounds[1]) > 0
                && Math.abs(shellPlan.bounds[2] - shellPlan.bounds[0]) > 0;
    }

    private static ResolvedPageItem findNativeInlineShellStyleItem(
            ResolvedBuildContext ctx,
            ObjectPlan shellPlan,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || shellPlan == null) return null;
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(anchoredObjectId));
        if (isNativeInlineShellStyleSource(shellPlan, anchorItem)) return anchorItem;
        int[] styleIds = shellPlan.styleSourceObjectIds != null && shellPlan.styleSourceObjectIds.length > 0
                ? shellPlan.styleSourceObjectIds
                : shellPlan.visualSourceObjectIds;
        if (styleIds != null) {
            for (int styleId : styleIds) {
                ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(styleId));
                if (isNativeInlineShellStyleSource(shellPlan, item)) return item;
            }
        }
        ResolvedPageItem planItem = ctx.resolvedData.getPageItem(String.valueOf(shellPlan.domId));
        return isNativeInlineShellStyleSource(shellPlan, planItem) ? planItem : null;
    }

    private static boolean isNativeInlineShellStyleSource(ObjectPlan shellPlan, ResolvedPageItem item) {
        if (shellPlan == null || item == null) return false;
        if (shellPlan.materialization != Materialization.NATIVE_SOURCE_SHAPE) return false;
        int itemId = parseIntOrDefault(item.id(), -1);
        return containsInt(shellPlan.sourceObjectIds, itemId)
                || containsInt(shellPlan.visualSourceObjectIds, itemId)
                || containsInt(shellPlan.styleSourceObjectIds, itemId)
                || shellPlan.domId == itemId;
    }

    private static int parseIntOrDefault(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double[] normalizeTextFrameBoundsToShellPage(
            ResolvedBuildContext ctx,
            int pageIndex,
            ResolvedTextFrame tf,
            double[] shellBounds) {
        if (tf == null) return null;
        double[] b = tf.pageRelativeBounds();
        if (!validBounds(b)) b = tf.geometricBounds();
        if (!validBounds(b) || !validBounds(shellBounds)) return b;
        double[] out = new double[] { b[0], b[1], b[2], b[3] };
        double pageWidth = localPageWidth(ctx, pageIndex);
        if (pageWidth > 0.0) {
            double shift = Math.rint((out[1] - shellBounds[1]) / pageWidth) * pageWidth;
            out[1] -= shift;
            out[3] -= shift;
        }
        double pageHeight = localPageHeight(ctx, pageIndex);
        if (pageHeight > 0.0) {
            double shift = Math.rint((out[0] - shellBounds[0]) / pageHeight) * pageHeight;
            out[0] -= shift;
            out[2] -= shift;
        }
        return out;
    }

    private static double[] normalizeSpreadBoundsToPageLocal(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] bounds) {
        if (!validBounds(bounds)) return bounds;
        double[] out = new double[] { bounds[0], bounds[1], bounds[2], bounds[3] };
        double[] page = pageBounds(ctx, pageIndex);
        if (page == null || page.length < 4) return out;
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        double pageTop = page[0] / scale;
        double pageLeft = page[1] / scale;
        if (pageLeft > 1.0 && out[1] >= pageLeft - 0.5) {
            out[1] -= pageLeft;
            out[3] -= pageLeft;
        }
        if (pageTop > 1.0 && out[0] >= pageTop - 0.5) {
            out[0] -= pageTop;
            out[2] -= pageTop;
        }
        return out;
    }

    private static double[] normalizeInlineBoundsToPageLocal(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] bounds) {
        if (!validBounds(bounds)) return bounds;
        double[] page = pageBounds(ctx, pageIndex);
        if (page == null || page.length < 4) {
            return new double[] { bounds[0], bounds[1], bounds[2], bounds[3] };
        }
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        double pageWidth = Math.abs(page[3] - page[1]) / scale;
        double pageHeight = Math.abs(page[2] - page[0]) / scale;
        if (pageWidth > 0.0 && pageHeight > 0.0
                && bounds[1] >= -1.0 && bounds[3] <= pageWidth + 1.0
                && bounds[0] >= -1.0 && bounds[2] <= pageHeight + 1.0) {
            return new double[] { bounds[0], bounds[1], bounds[2], bounds[3] };
        }
        return normalizeSpreadBoundsToPageLocal(ctx, pageIndex, bounds);
    }

    private static double[] normalizeShellBoundsToTextFramePageLocal(
            ResolvedBuildContext ctx,
            int pageIndex,
            double[] shellBounds,
            ResolvedTextFrame tf) {
        if (!validBounds(shellBounds)) return shellBounds;
        if (boundsAreAlreadyPageLocal(ctx, pageIndex, shellBounds)) return shellBounds;
        double[] pageRel = tf != null ? tf.pageRelativeBounds() : null;
        double[] geom = tf != null ? tf.geometricBounds() : null;
        if (validBounds(pageRel) && validBounds(geom)) {
            double geomLeft = geom[1];
            double geomTop = geom[0];
            double dx = geomLeft - pageRel[1];
            double dy = geomTop - pageRel[0];
            if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
                return new double[] {
                        shellBounds[0] - dy,
                        shellBounds[1] - dx,
                        shellBounds[2] - dy,
                        shellBounds[3] - dx
                };
            }
        }
        return normalizeSpreadBoundsToPageLocal(ctx, pageIndex, shellBounds);
    }

    private static boolean boundsAreAlreadyPageLocal(ResolvedBuildContext ctx, int pageIndex, double[] bounds) {
        if (!validBounds(bounds)) return false;
        double pageWidth = localPageWidth(ctx, pageIndex);
        double pageHeight = localPageHeight(ctx, pageIndex);
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return false;
        double tolerance = 1.5;
        return bounds[1] >= -tolerance
                && bounds[3] <= pageWidth + tolerance
                && bounds[0] >= -tolerance
                && bounds[2] <= pageHeight + tolerance;
    }

    public static boolean hasInlineTextShellForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || anchoredObjectId < 0) return false;
        return findInlineTextShellOwnerPlanForAnchor(ctx, anchoredObjectId) != null;
    }

    public static ASTInlineObject loadPlannedInlineTextShellForOwnedTextFrame(
            ResolvedBuildContext ctx,
            int textFrameDomId) {
        if (ctx == null || ctx.resolvedData == null || ctx.ownershipPlans == null || textFrameDomId < 0) {
            return null;
        }
        ObjectPlan plan = findInlineTextShellOwnerPlanForOwnedTextFrame(ctx, textFrameDomId);
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return null;
        List<ResolvedTextFrame> childTfs = new ArrayList<>();
        for (int childId : plan.ownedTextFrameIds) {
            ResolvedTextFrame childTf = ctx.resolvedData.getTextFrame(String.valueOf(childId));
            if (childTf == null) return null;
            childTfs.add(childTf);
        }
        if (plan.materialization == Materialization.NATIVE_SOURCE_SHAPE) {
            return buildNativeInlineShellObject(ctx, plan, plan.domId, childTfs);
        }

        RenderedGroup shell = findRenderedGroupForPlan(ctx, plan, plan.domId);
        if (shell == null) {
            int anchorId = plan.domId >= 0 ? plan.domId : textFrameDomId;
            return buildSourceNativeInlineShellObject(ctx, plan, anchorId, childTfs);
        }
        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(shell.id()));
        return buildInlineShellObject(ctx, shell.id(), anchorItem, childTfs, shell);
    }

    private static ObjectPlan findInlineTextShellOwnerPlanForOwnedTextFrame(
            ResolvedBuildContext ctx,
            int textFrameDomId) {
        if (ctx == null || ctx.ownershipPlans == null || textFrameDomId < 0) return null;
        for (ObjectPlan plan : ctx.ownershipPlansForOwnedTextFrame(textFrameDomId)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (!containsInt(plan.ownedTextFrameIds, textFrameDomId)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT
                    && !hasHwpxTextOwnershipForTextFrame(ctx, textFrameDomId)) {
                continue;
            }
            RenderedGroup rendered = findRenderedGroupForPlan(ctx, plan, plan.domId);
            if (isExecutableTextlessShellCarrier(plan, rendered)) {
                return plan;
            }
        }
        return null;
    }

    private static ObjectPlan findInlineTextShellOwnerPlanForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        List<ObjectPlan> plans = findInlineTextShellOwnerPlansForAnchor(ctx, anchoredObjectId);
        return plans.isEmpty() ? null : plans.get(0);
    }

    private static List<ObjectPlan> findInlineTextShellOwnerPlansForAnchor(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        List<ObjectPlan> candidates = new ArrayList<>();
        if (ctx == null || ctx.ownershipPlans == null) return candidates;
        Map<String, ObjectPlan> byOwnedText = new java.util.LinkedHashMap<>();
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (!hasHwpxTextOwnershipForOwnedTextFrameIds(ctx, plan)) continue;
            if (!isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)
                    && !isNestedInlineTextShellPlanForAnchor(ctx, plan, anchoredObjectId)) {
                continue;
            }
            if (!isExecutableTextlessShellCarrier(plan)) continue;
            String key = ownedTextFrameKey(plan);
            ObjectPlan existing = byOwnedText.get(key);
            if (existing == null || inlineTextShellPlanPriority(plan) > inlineTextShellPlanPriority(existing)) {
                byOwnedText.put(key, plan);
            }
        }
        candidates.addAll(byOwnedText.values());
        java.util.Collections.sort(candidates, new java.util.Comparator<ObjectPlan>() {
            public int compare(ObjectPlan a, ObjectPlan b) {
                int orderA = inlineAnchorSourceOrder(ctx, anchoredObjectId, a);
                int orderB = inlineAnchorSourceOrder(ctx, anchoredObjectId, b);
                if (orderA != orderB) return Integer.compare(orderA, orderB);
                int byBounds = compareInlinePlanReadingOrder(a, b);
                if (byBounds != 0) return byBounds;
                return Integer.compare(planDepthOrder(a), planDepthOrder(b));
            }
        });
        return candidates;
    }

    private static boolean isNestedInlineTextShellPlanForAnchor(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (ctx == null || plan == null || anchoredObjectId < 0) return false;
        String anchorId = String.valueOf(anchoredObjectId);
        Set<String> descendants = ctx.descendantSet(anchorId, 8);
        if (descendants == null || descendants.isEmpty()) return false;
        return containsAnyStringId(descendants, plan.sourceObjectIds)
                || containsAnyStringId(descendants, plan.visualSourceObjectIds)
                || containsAnyStringId(descendants, plan.exportSourceObjectIds)
                || containsAnyStringId(descendants, plan.ownedTextFrameIds);
    }

    private static int inlineAnchorSourceOrder(
            ResolvedBuildContext ctx,
            int anchoredObjectId,
            ObjectPlan plan) {
        ObjectPlan carrier = findInlineAnchorCarrierPlan(ctx, anchoredObjectId);
        if (carrier == null || carrier.sourceObjectIds == null || carrier.sourceObjectIds.length == 0) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        best = Math.min(best, firstSourceIndex(carrier.sourceObjectIds, plan != null ? plan.visualSourceObjectIds : null));
        best = Math.min(best, firstSourceIndex(carrier.sourceObjectIds, plan != null ? plan.exportSourceObjectIds : null));
        best = Math.min(best, firstSourceIndex(carrier.sourceObjectIds, plan != null ? plan.styleSourceObjectIds : null));
        best = Math.min(best, firstSourceIndex(carrier.sourceObjectIds, plan != null ? plan.ownedTextFrameIds : null));
        if (best == Integer.MAX_VALUE) {
            best = firstSourceIndexExcluding(
                    carrier.sourceObjectIds,
                    plan != null ? plan.sourceObjectIds : null,
                    anchoredObjectId);
        }
        return best;
    }

    private static ObjectPlan findInlineAnchorCarrierPlan(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || anchoredObjectId < 0) return null;
        ObjectPlan best = null;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectTree(anchoredObjectId, 8)) {
            if (plan == null || plan.placement != Placement.INLINE) continue;
            if (!isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) continue;
            if (best == null || plan.sourceObjectIds.length > best.sourceObjectIds.length) {
                best = plan;
            }
        }
        return best;
    }

    private static int firstSourceIndex(int[] orderedSourceIds, int[] ids) {
        if (orderedSourceIds == null || ids == null || orderedSourceIds.length == 0 || ids.length == 0) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < orderedSourceIds.length; i++) {
            if (containsInt(ids, orderedSourceIds[i])) {
                best = i;
                break;
            }
        }
        return best;
    }

    private static int firstSourceIndexExcluding(int[] orderedSourceIds, int[] ids, int excludedId) {
        if (orderedSourceIds == null || ids == null || orderedSourceIds.length == 0 || ids.length == 0) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < orderedSourceIds.length; i++) {
            int sourceId = orderedSourceIds[i];
            if (sourceId == excludedId) continue;
            if (containsInt(ids, sourceId)) {
                best = i;
                break;
            }
        }
        return best;
    }

    private static String ownedTextFrameKey(ObjectPlan plan) {
        if (plan == null || plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) {
            return "";
        }
        int[] ids = java.util.Arrays.copyOf(plan.ownedTextFrameIds, plan.ownedTextFrameIds.length);
        java.util.Arrays.sort(ids);
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    private static int inlineTextShellPlanPriority(ObjectPlan plan) {
        if (plan == null) return 0;
        int score = 0;
        if (plan.file != null && !plan.file.isEmpty()) score += 10;
        if (plan.materialization == Materialization.EXTRACTED_PNG_VECTOR) score += 5;
        String kind = plan.kind == null ? "" : plan.kind;
        if (kind.startsWith("planner_declared_rendered:")) score += 3;
        return score;
    }

    private static int compareInlinePlanReadingOrder(ObjectPlan a, ObjectPlan b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a.bounds != null && a.bounds.length >= 4 && b.bounds != null && b.bounds.length >= 4) {
            double topA = a.bounds[0];
            double leftA = a.bounds[1];
            double topB = b.bounds[0];
            double leftB = b.bounds[1];
            if (Math.abs(topA - topB) <= 2.0) {
                int byLeft = Double.compare(leftA, leftB);
                if (byLeft != 0) return byLeft;
            }
            int byTop = Double.compare(topA, topB);
            if (byTop != 0) return byTop;
            return Double.compare(leftA, leftB);
        }
        return 0;
    }

    private static int planDepthOrder(ObjectPlan plan) {
        if (plan == null) return Integer.MAX_VALUE;
        if (plan.zOrder != Integer.MIN_VALUE) return plan.zOrder;
        return plan.domId;
    }

    private static boolean isInlineVisualExecutionAnchor(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (!isDirectInlineAnchorPlan(ctx, plan, anchoredObjectId)) return false;
        if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                || plan.placement != Placement.INLINE
                || plan.coordinateSpace != CoordinateSpace.STORY_FLOW) {
            return true;
        }
        return isInlinePngOwnerAnchor(plan, rg, anchoredObjectId);
    }

    private static boolean isInlinePngOwnerAnchor(
            ObjectPlan plan,
            RenderedGroup rg,
            int anchoredObjectId) {
        if (plan == null || anchoredObjectId < 0) return false;
        if (plan.domId == anchoredObjectId
                || (plan.renderId != null && plan.renderId == anchoredObjectId)) {
            return true;
        }
        if (rg != null) {
            if (rg.id() == anchoredObjectId) return true;
            int renderedAnchor = rg.inlineAnchorSourceObjectId();
            if (renderedAnchor > 0) return renderedAnchor == anchoredObjectId;
        }
        if (containsInt(plan.sourceRootObjectIds, anchoredObjectId)) {
            return true;
        }
        if (plan.sourceRootObjectIds != null && plan.sourceRootObjectIds.length > 0) {
            return false;
        }
        if (containsInt(plan.exportSourceObjectIds, anchoredObjectId)) {
            return true;
        }
        if (containsInt(plan.visualSourceObjectIds, anchoredObjectId)) {
            return true;
        }
        return plan.sourceObjectIds != null
                && plan.sourceObjectIds.length == 1
                && containsInt(plan.sourceObjectIds, anchoredObjectId);
    }

    private static boolean isDirectInlineAnchorPlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (plan == null || anchoredObjectId < 0) return false;
        if (plan.domId == anchoredObjectId
                || (plan.renderId != null && plan.renderId == anchoredObjectId)) {
            return true;
        }
        if (containsInt(plan.sourceObjectIds, anchoredObjectId)
                || containsInt(plan.visualSourceObjectIds, anchoredObjectId)
                || containsInt(plan.styleSourceObjectIds, anchoredObjectId)) {
            return true;
        }
        return false;
    }

    private static boolean isClosedRenderedMaterialForInlineAnchor(
            RenderedGroup rg,
            int anchoredObjectId) {
        return rg != null
                && anchoredObjectId >= 0
                && rg.inlineSourceTreeClosed()
                && rg.inlineAnchorSourceObjectId() == anchoredObjectId;
    }

    private static RenderedGroup findRenderedGroupForDirectInlineAnchorPlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || plan == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        if (plan.file == null || plan.file.isEmpty()) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            boolean same = rg.id() == anchoredObjectId
                    || rg.id() == plan.domId
                    || (plan.renderId != null && rg.id() == plan.renderId)
                    || isClosedRenderedMaterialForInlineAnchor(rg, anchoredObjectId);
            if (!same) continue;
            if (plan.file != null && !plan.file.isEmpty()
                    && plan.file.equals(rg.file())) {
                return rg;
            }
        }
        return null;
    }

    private static RenderedGroup findRenderedGroupForPlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null || plan == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        if (plan.file == null || plan.file.isEmpty()) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (plan.file != null && plan.file.equals(rg.file())) {
                return rg;
            }
        }
        return null;
    }

    private static String materialFile(ObjectPlan plan) {
        if (plan != null && plan.file != null && !plan.file.isEmpty()) return plan.file;
        return null;
    }

    private static boolean renderedGroupMatchesPlan(RenderedGroup rg, ObjectPlan plan) {
        if (rg == null || plan == null) return false;
        if (plan.renderId != null && rg.id() == plan.renderId) return true;
        return rg.id() == plan.domId;
    }

    public static boolean shouldUsePlannedInlinePngWithSeparateHwpxText(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || ctx.resolvedData == null) return false;
        String anchorId = String.valueOf(anchoredObjectId);
        Set<String> descendants = ctx.descendantSet(anchorId, 8);
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
            if (rg == null) continue;
            if (!containsStringId(editableTextFrameIds(ctx, rg), textFrameId)) continue;
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
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (containsInt(plan.ownedTextFrameIds, tfDomId)
                    || containsInt(plan.sourceObjectIds, tfDomId)) {
                return true;
            }
        }
        return false;
    }

    public static ASTInlineObject loadCompleteSimpleButtonLabelInlineObject(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.basePath == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        RenderedGroup completeRender = findCompleteSimpleButtonLabelRender(ctx, anchoredObjectId);
        ObjectPlan plan = completeRender != null ? ctx.findOwnershipPlanForRendered(completeRender) : null;
        if (!isExecutableCompleteSimpleButtonInlinePlan(plan)) return null;
        return loadCompleteInlinePngFromPlan(ctx, plan, anchoredObjectId);
    }

    private static ASTInlineObject loadCompleteInlinePngFromPlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            int sourceId) {
        if (ctx == null || ctx.basePath == null || !isExecutableCompleteSimpleButtonInlinePlan(plan)) {
            return null;
        }
        File pngFile = new File(ctx.basePath, plan.file);
        if (!pngFile.exists() || !pngFile.isFile()) return null;
        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.imageData(imageData);
            obj.imageFormat("png");
            obj.pixelWidth(img.getWidth());
            obj.pixelHeight(img.getHeight());
            obj.sourceId("u" + Integer.toHexString(sourceId));
            obj.keepInline(true);
            obj.verticalJustification("CenterAlign");

            double[] bounds = renderedBoundsPoints(ctx, plan.bounds);
            if (bounds == null || bounds.length < 4) return null;
            double bw = Math.abs(bounds[3] - bounds[1]);
            double bh = Math.abs(bounds[2] - bounds[0]);
            if (bw <= 0 || bh <= 0) return null;
            int[] visiblePixels = alphaBounds(img);
            if (visiblePixels != null && visiblePixels[3] > 0) {
                // Keep every source pixel. Scale the untouched canvas so the
                // visible ink height, rather than the transparent canvas height,
                // matches the ObjectPlan's physical height.
                double pointsPerPixel = bh / (double) visiblePixels[3];
                bw = img.getWidth() * pointsPerPixel;
                bh = img.getHeight() * pointsPerPixel;
            } else {
                bh = bw * ((double) img.getHeight() / (double) img.getWidth());
            }
            obj.width(CoordinateConverter.pointsToHwpunits(bw));
            obj.height(CoordinateConverter.pointsToHwpunits(bh));
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isExecutableCompleteSimpleButtonInlinePlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_PNG
                && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && plan.materialization == Materialization.COMPLETE_PNG
                && plan.file != null
                && !plan.file.isEmpty()
                && plan.bounds != null
                && plan.bounds.length >= 4
                && Math.abs(plan.bounds[3] - plan.bounds[1]) > 0
                && Math.abs(plan.bounds[2] - plan.bounds[0]) > 0;
    }

    private static boolean isInlineCompletePngTextOwnerPlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_PNG
                && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && plan.materialization == Materialization.COMPLETE_PNG;
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
            return new BadgeImageData(originalData, originalImage);
        }
        int[] alpha = alphaBounds(originalImage);
        if (alpha == null) {
            return new BadgeImageData(originalData, originalImage);
        }
        int x = alpha[0];
        int y = alpha[1];
        int w = alpha[2];
        int h = alpha[3];
        if (x <= 0 && y <= 0 && w >= originalImage.getWidth() && h >= originalImage.getHeight()) {
            return new BadgeImageData(originalData, originalImage);
        }
        if (w <= 1 || h <= 1) {
            return new BadgeImageData(originalData, originalImage);
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
        return new BadgeImageData(out.toByteArray(), cropped);
    }

    private static BadgeImageData trimVerticalTransparentPaddingPreserveWidth(
            byte[] originalData,
            BufferedImage originalImage) throws java.io.IOException {
        if (originalImage == null || originalImage.getWidth() <= 0 || originalImage.getHeight() <= 0) {
            return new BadgeImageData(originalData, originalImage);
        }
        int[] alpha = alphaBounds(originalImage);
        if (alpha == null) {
            return new BadgeImageData(originalData, originalImage);
        }
        int y = alpha[1];
        int h = alpha[3];
        if (y <= 0 && h >= originalImage.getHeight()) {
            return new BadgeImageData(originalData, originalImage);
        }
        if (h <= 1) {
            return new BadgeImageData(originalData, originalImage);
        }

        BufferedImage cropped = new BufferedImage(
                originalImage.getWidth(),
                h,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cropped.createGraphics();
        try {
            g.drawImage(originalImage,
                    0, 0, originalImage.getWidth(), h,
                    0, y, originalImage.getWidth(), y + h,
                    null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(cropped, "png", out);
        return new BadgeImageData(out.toByteArray(), cropped);
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

        BadgeImageData(byte[] imageData, BufferedImage image) {
            this.imageData = imageData;
            this.image = image;
        }
    }

    private static RenderedGroup findCompleteSimpleButtonLabelRender(
            ResolvedBuildContext ctx,
            int anchoredObjectId) {
        if (ctx == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        ResolvedTextFrame childTf = findSimpleButtonLabelChildTextFrame(ctx, anchoredObjectId);
        int childTfId = parseDecimalId(childTf != null ? childTf.id() : null);
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null || !ctx.isCompleteInlinePngByOwnershipPlan(rg)) continue;
            if (rg.id() == anchoredObjectId) {
                return rg;
            }
            if (planReferencesSimpleButtonAnchor(plan, anchoredObjectId, childTfId)) {
                return rg;
            }
        }
        return null;
    }

    private static boolean planReferencesSimpleButtonAnchor(
            ObjectPlan plan,
            int anchoredObjectId,
            int childTfId) {
        if (plan == null) return false;
        if (containsInt(plan.sourceObjectIds, anchoredObjectId)
                || containsInt(plan.visualSourceObjectIds, anchoredObjectId)) {
            return true;
        }
        if (childTfId >= 0) {
            return containsInt(plan.sourceObjectIds, childTfId)
                    || containsInt(plan.ownedTextFrameIds, childTfId)
                    || containsInt(plan.visualSourceObjectIds, childTfId);
        }
        return false;
    }

    private static int parseDecimalId(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
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
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan != null
                    && plan.placement == Placement.INLINE
                    && plan.hasVisibleVisual()) {
                return rg;
            }
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
        if (ctx == null || rg == null || !ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg)) return null;
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
        if (!ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg)) return out;
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


    /** ObjectPlan.ownedTextFrameIds를 ResolvedTextFrame으로 매핑 후 Y(상단)→X(좌측) 읽기 순서로 정렬. */
    private static java.util.List<ResolvedTextFrame> badgeTextFramesSortedByReading(
            ResolvedBuildContext ctx, RenderedGroup rg) {
        java.util.List<ResolvedTextFrame> out = new ArrayList<>();
        String[] ids = editableTextFrameIds(ctx, rg);
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
        if (story != null && !story.paragraphs().isEmpty()) {
            ResolvedRun rr = firstVisibleResolvedRun(story);
            if (rr != null) applyResolvedRunStyle(ctx, rr, run);
        }
        applyIdmlRunStyleFallback(ctx, tf, java.util.Collections.singletonList(run));
    }

    // 배지 그래픽 PNG를 흰 배경에 합성해 로드. HWP imgBrush는 알파를 검정으로 칠하므로(투명→검정),
    // 흰 페이지 위 배지는 흰 배경 합성이 올바르다(투명 영역=페이지색).
    private static byte[] loadBadgePngFlattenedOntoWhite(ResolvedBuildContext ctx, RenderedGroup rg) {
        byte[] png = loadRenderedPngBytes(ctx, rg);
        if (png == null || png.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img != null) {
                return prepareInlineTextShellImageData(img, true);
            }
        } catch (Exception ignored) {}
        return png;
    }

    public static java.util.List<ASTInlineObject> buildChildEditableBoxes(ResolvedBuildContext ctx, int groupId) {
        if (hasStage1ObjectPlans(ctx)) {
            return java.util.Collections.emptyList();
        }
        if (hasOwnershipPlanForAnchorBundle(ctx, groupId)) {
            return java.util.Collections.emptyList();
        }
        if (hasInlineTextShellForAnchor(ctx, groupId)
                || isCoveredByInlineTextShellSourceBundle(ctx, groupId)) {
            return java.util.Collections.emptyList();
        }
        if (shouldUsePlannedInlinePngWithSeparateHwpxText(ctx, groupId)) {
            return java.util.Collections.emptyList();
        }
        java.util.List<ASTInlineObject> result = new ArrayList<>();
        String groupIdStr = String.valueOf(groupId);
        java.util.List<ResolvedTextFrame> editableChildren = new ArrayList<>();
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (isTextFrameCoveredByInlineTextShell(ctx, tf.id())) continue;
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            boolean directChild = groupIdStr.equals(pi.parentId());
            if (!directChild) {
                continue;
            }
            if (!tf.isInline()) continue;
            if (!isHwpxEditableTextFrame(ctx, tf.id())) continue;
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
            if (isTextFrameCoveredByInlineTextShell(ctx, tf.id())) continue;
            RenderedGroup textFrameShell = findTextHiddenInlineShellForTextFrame(ctx, tf.id());
            RenderedGroup inlineBackdrop = textFrameShell != null
                    ? textFrameShell
                    : (editableChildren.size() == 1 ? findInlineEditableGroupBackdrop(ctx, groupId) : null);
            byte[] inlineBackdropData = loadInlineTextShellImageBytes(ctx, inlineBackdrop, true);
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
            applyIdmlRunStyleFallback(ctx, tf, java.util.Collections.singletonList(textRunInner));
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


    private static boolean isHwpxEditableTextFrame(ResolvedBuildContext ctx, String textFrameId) {
        if (ctx == null || ctx.resolvedData == null || textFrameId == null) return false;
        if (ctx.resolvedData.isEditableTextFrame(textFrameId)) return true;
        if (ctx.resolvedData.isHwpxOwnedTextFrame(textFrameId)) return true;
        if (ctx.ownershipPlans == null) return false;
        int tfDomId;
        try {
            tfDomId = Integer.parseInt(textFrameId);
        } catch (NumberFormatException e) {
            return false;
        }
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.domId == tfDomId || containsInt(plan.ownedTextFrameIds, tfDomId)) {
                return true;
            }
        }
        return false;
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
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (tfDomId >= 0
                    && plan != null
                    && plan.placement == Placement.INLINE
                    && ShellRole.isTextShell(plan)
                    && isShellPlanWithOwnedHwpxText(ctx, plan)
                    && containsInt(plan.ownedTextFrameIds, tfDomId)) {
                return rg;
            }
        }
        return null;
    }

    private static RenderedGroup findInlineEditableGroupBackdrop(ResolvedBuildContext ctx, int groupId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) return null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != groupId || rg.file() == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan != null
                    && plan.placement == Placement.INLINE
                    && ShellRole.isTextShell(plan)
                    && isShellPlanWithOwnedHwpxText(ctx, plan)
                    && plan.ownedTextFrameIds != null
                    && plan.ownedTextFrameIds.length > 0) {
                return rg;
            }
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

    private static byte[] loadInlineTextShellImageBytes(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            boolean preserveSourceCanvas) {
        if (ctx == null || ctx.basePath == null || rg == null || rg.file() == null) return null;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists() || !pngFile.isFile()) return null;
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) return null;
            return prepareInlineTextShellImageData(img, preserveSourceCanvas);
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
        java.util.Set<String> descendants = ctx.descendantSet(anchorId, 8);
        if (descendants == null || descendants.isEmpty()) return false;
        for (String descendantId : descendants) {
            int tfDomId;
            try { tfDomId = Integer.parseInt(descendantId); }
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
        java.util.Set<String> descendants = ctx.descendantSet(anchorId, 8);
        if (descendants == null || descendants.isEmpty()) return false;
        for (String descendantId : descendants) {
            int tfDomId;
            try { tfDomId = Integer.parseInt(descendantId); }
            catch (NumberFormatException e) { continue; }
            if (ctx.ownershipPlanPlacesInlineHwpxText(tfDomId)) return true;
        }
        return false;
    }

    private static boolean isTextFrameCoveredByInlineTextShell(
            ResolvedBuildContext ctx,
            String textFrameId) {
        if (textFrameId == null) return false;
        try {
            return isCoveredByInlineTextShellSourceBundle(ctx, Integer.parseInt(textFrameId));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isCoveredByInlineTextShellSourceBundle(
            ResolvedBuildContext ctx,
            int sourceObjectId) {
        if (ctx == null || sourceObjectId < 0) {
            return false;
        }
        if (hasStage1ObjectPlans(ctx)) {
            return plannedInlineTextShellCoversSourceBundle(ctx, sourceObjectId);
        }
        if (ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return false;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || !rg.hasEditableTextHiddenFromPng()) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(String.valueOf(rg.id()));
            boolean inline = isInlineRenderedGroupType(rg)
                    || (anchorItem != null && anchorItem.isInline());
            if (!inline) continue;
            if (containsInt(rg.sourceObjectIds(), sourceObjectId)
                    || containsInt(rg.atomicSourceObjectIds(), sourceObjectId)
                    || containsInt(rg.atomicVisualSourceObjectIds(), sourceObjectId)
                    || containsStringId(editableTextFrameIds(ctx, rg), String.valueOf(sourceObjectId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean plannedInlineTextShellCoversSourceBundle(
            ResolvedBuildContext ctx,
            int sourceObjectId) {
        if (ctx == null || ctx.ownershipPlans == null || sourceObjectId < 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.domId == sourceObjectId
                    || containsInt(plan.sourceObjectIds, sourceObjectId)
                    || containsInt(plan.visualSourceObjectIds, sourceObjectId)
                    || containsInt(plan.ownedTextFrameIds, sourceObjectId)) {
                return true;
            }
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
        ResolvedPage page = null;
        if (pageIndex >= 0 && pageIndex < ctx.resolvedData.pages().size()) {
            ResolvedPage byPosition = ctx.resolvedData.pages().get(pageIndex);
            if (byPosition != null && byPosition.index() == pageIndex) {
                page = byPosition;
            }
        }
        if (page == null) {
            for (ResolvedPage candidate : ctx.resolvedData.pages()) {
                if (candidate != null && candidate.index() == pageIndex) {
                    page = candidate;
                    break;
                }
            }
        }
        if (page == null || page.bounds() == null || page.bounds().length < 4) return null;
        return page.bounds();
    }

    private static boolean shouldUseNoAutoLineWrap(ResolvedTextFrame tf) {
        return shouldUseNoAutoLineWrap(tf, false);
    }

    private static boolean shouldUseNoAutoLineWrap(ResolvedTextFrame tf, boolean hasVisualShell) {
        if (tf == null) return false;
        if (hasSourceParagraphBreak(tf)) return false;
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

    private static boolean hasSourceParagraphBreak(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String visibleText = tf.frameVisibleText();
        if (visibleText != null && (visibleText.indexOf('\n') >= 0 || visibleText.indexOf('\r') >= 0)) {
            return true;
        }
        if (tf.paragraphStart() != tf.paragraphEnd()) return true;
        return tf.frameParaTexts() != null && tf.frameParaTexts().size() > 1;
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
