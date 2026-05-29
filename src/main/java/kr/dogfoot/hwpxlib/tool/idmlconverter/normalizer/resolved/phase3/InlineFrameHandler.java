package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        if (childTfs.size() < 2) return null;

        // 읽기 순서 (Y → X) 정렬
        childTfs.sort((a, b) -> {
            double[] ab = a.geometricBounds();
            double[] bb = b.geometricBounds();
            if (ab == null || bb == null) return 0;
            if (Math.abs(ab[0] - bb[0]) > 1.0) return Double.compare(ab[0], bb[0]);
            return Double.compare(ab[1], bb[1]);
        });

        // Group 후손 중 stroke 가 있는 Rectangle 수집
        java.util.List<ResolvedPageItem> rectangles = new java.util.ArrayList<>();
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null) continue;
            if (!"Rectangle".equals(pi.type()) && !"Polygon".equals(pi.type()) && !"Oval".equals(pi.type())) continue;
            String scn = pi.strokeColorName();
            boolean hasStroke = scn != null && !"None".equals(scn) && !"[None]".equals(scn) && pi.strokeWeight() > 0;
            String fcn = pi.fillColorName();
            boolean hasFill = fcn != null && !"None".equals(fcn) && !"[None]".equals(fcn);
            if (!hasStroke && !hasFill) continue;
            String curParent = pi.parentId();
            int hops = 0;
            boolean inGroup = false;
            while (curParent != null && hops < 5) {
                if (anchorId.equals(curParent)) { inGroup = true; break; }
                ResolvedPageItem next = ctx.resolvedData.getPageItem(curParent);
                if (next == null) break;
                curParent = next.parentId();
                hops++;
            }
            if (inGroup) rectangles.add(pi);
        }
        if (rectangles.isEmpty()) return null;

        // 각 TF 를 가장 잘 겹치는 Rectangle 과 매칭하여 INLINE_TEXT_FRAME 생성
        java.util.List<ASTInlineObject> result = new java.util.ArrayList<>();
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
            try {
                obj.sourceId("u" + Integer.toHexString(Integer.parseInt(childTf.id())));
            } catch (NumberFormatException nfe) {
                obj.sourceId("u" + childTf.id());
            }

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
                        obj.fillTint(100);
                    }
                }
                if (matchedRect.cornerRadius() > 0) {
                    obj.cornerRadius(matchedRect.cornerRadius());
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
            // 박스 안 텍스트는 가운데 정렬
            obj.verticalJustification("CenterAlign");

            result.add(obj);
        }

        return result.size() >= 2 ? result : null;
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
    static ASTInlineObject tryInlineGroupAsSingleBadge(ResolvedBuildContext ctx, int anchoredObjectId) {
        String anchorId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame anchorTf = ctx.resolvedData.getTextFrame(anchorId);
        if (anchorTf != null) return null;

        ResolvedPageItem anchorItem = ctx.resolvedData.getPageItem(anchorId);
        if (anchorItem == null || !"Group".equals(anchorItem.type())) return null;

        // 이미 inline_object 또는 badge_group PNG로 렌더링된 경우 → loadInlineObject 에 위임.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredObjectId && "inline_object".equals(rg.itemType()) && rg.file() != null) {
                return null; // PNG 경로에 위임
            }
        }
        // renderedTextFrames에 badge_group PNG가 있으면 → Phase 7 floating 배치에 위임
        for (RenderedGroup rg : ctx.resolvedData.allRenderedTextFrames()) {
            if (rg.id() == anchoredObjectId && rg.isBadgeGroup() && rg.file() != null) {
                return null;
            }
        }

        // 직속 자식 TF 1 개 (inline + 텍스트 있음)
        ResolvedTextFrame childTf = null;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null) continue;
            if (!anchorId.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            // 너무 긴 텍스트(>=4자) 는 배지가 아닐 가능성 → 제외
            if (cleaned.length() >= 4) return null;
            if (childTf != null) return null; // 2 개 이상 → tryInlineGroupAsBoxList 가 처리
            childTf = tf;
        }
        if (childTf == null) return null;

        // Group 후손 중 fill 색 있는 도형 수집 (가장 큰 fill 도형 = 배경)
        ResolvedPageItem bgShape = null;
        double bgArea = 0;
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null) continue;
            if (!"Rectangle".equals(pi.type()) && !"Polygon".equals(pi.type()) && !"Oval".equals(pi.type())) continue;
            String fcn = pi.fillColorName();
            boolean hasFill = fcn != null && !"None".equals(fcn) && !"[None]".equals(fcn);
            if (!hasFill) continue;
            String curParent = pi.parentId();
            int hops = 0;
            boolean inGroup = false;
            while (curParent != null && hops < 5) {
                if (anchorId.equals(curParent)) { inGroup = true; break; }
                ResolvedPageItem next = ctx.resolvedData.getPageItem(curParent);
                if (next == null) break;
                curParent = next.parentId();
                hops++;
            }
            if (!inGroup) continue;
            double[] gb = pi.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            double a = Math.abs(gb[3] - gb[1]) * Math.abs(gb[2] - gb[0]);
            if (a > bgArea) { bgArea = a; bgShape = pi; }
        }
        if (bgShape == null) return null;

        // Group 안에 Oval 직속 자식이 있는지 먼저 확인 (크기 보정 판단에 필요)
        boolean hasOval = false;
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || !"Oval".equals(pi.type())) continue;
            if (anchorId.equals(pi.parentId())) { hasOval = true; break; }
        }

        // 박스 크기 = Group 의 전체 bounds (capsule 모양 전체 영역 포함)
        double[] grpBounds = anchorItem.geometricBounds();
        if (grpBounds == null || grpBounds.length < 4) return null;
        double w = Math.abs(grpBounds[3] - grpBounds[1]);
        double h = Math.abs(grpBounds[2] - grpBounds[0]);
        if (w <= 0 || h <= 0) return null;

        // SPEC-028: Oval 배경 배지인데 종횡비가 심하게 틀어진 경우 (ratio < 0.6),
        // Group bounds 에 TextFrame 높이까지 포함되어 비례 왜곡이 발생한 것으로 판단.
        // (예: AboveLine 앵커, 원형 배경+텍스트프레임이 상하 적층된 구조 → 6.48×12.27pt → "●" 오렌더)
        // 이 경우 INLINE_TEXT_FRAME 생성을 포기하고 space run으로 대체 (floating badge 가 시각을 담당).
        if (hasOval) {
            double ratio = w > h ? h / w : w / h; // min/max, 1.0=정방형
            if (ratio < 0.6) return null;
        }

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.sourceId("u" + Integer.toHexString(anchoredObjectId));

        // 배경 fill 색
        String fillName = bgShape.fillColorName();
        String fillHex = ctx.resolvedData.resolveColorHex(fillName);
        if (fillHex != null) {
            obj.fillColor(fillHex);
            obj.fillTint(100);
        }

        if (hasOval) {
            obj.cornerRadius(h / 2.0);
        } else if (bgShape.cornerRadius() > 0) {
            obj.cornerRadius(bgShape.cornerRadius());
        }

        // 텍스트 단락 빌드 (배지 내부는 좌우 가운데 정렬)
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
        obj.verticalJustification("CenterAlign");

        return obj;
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
        if (ctx.resolvedData.isSimpleBadgeChild(domId)) return null;

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
        // Phase 2가 floating text box로 승격한 TF → 인라인 런 중복 방지
        if (ctx.renderedTfPlacedAsText != null && ctx.renderedTfPlacedAsText.contains(anchoredObjectId)) {
            return null;
        }
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null) {
            // SPEC-025: anchoredId 가 Group (TextFrame 아님) 인 경우, 자손 중 inline+editable TF 텍스트를 합쳐 임베드.
            // 단일 1자 (예: "1", "예") 는 시각 PNG 유지 우선 → 임베드 안 함.
            // 그러나 다중 1자 자손 (예: jamo 배지 ㅍㅎ, ㅂㅅ) 은 결합 텍스트가 의미 있으므로 합쳐서 임베드.
            java.util.List<ResolvedTextFrame> inlineDescs = findInlineEditableDescendants(ctx, domId);
            if (!inlineDescs.isEmpty()) {
                // Badge group children are placed as floating overlay textboxes by Phase 2 — do not inline them
                java.util.Iterator<ResolvedTextFrame> _remIt = inlineDescs.iterator();
                while (_remIt.hasNext()) {
                    ResolvedTextFrame _d = _remIt.next();
                    int _did;
                    try { _did = Integer.parseInt(_d.id()); } catch (NumberFormatException e) { continue; }
                    for (RenderedGroup _rg : ctx.resolvedData.allRenderedTextFrames()) {
                        if (!_rg.isBadgeGroup()) continue;
                        int[] _cids = _rg.childTextFrameIds();
                        if (_cids == null) continue;
                        boolean _found = false;
                        for (int _cid : _cids) if (_cid == _did) { _found = true; break; }
                        if (_found) { _remIt.remove(); break; }
                    }
                }
                StringBuilder _sb = new StringBuilder();
                for (ResolvedTextFrame d : inlineDescs) {
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

        // rendered된 TF(badge_group 등)는 PNG로 이미 배치됨 → 텍스트 런 변환 안 함
        // 단, itemType=null 인 inline TF 는 Phase 7 이 continue 로 skip → 여기서 처리해야 함.
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rtCheck =
                    ctx.resolvedData.getRenderedTextFrameByDomId(String.valueOf(anchoredObjectId));
            boolean isNullTypeInlineTf = rtCheck != null && rtCheck.itemType() == null && tf.isInline();
            if (!isNullTypeInlineTf) return null;
            // null-type inline TF: Phase 7 는 건너뜀 → 여기서 ASTTextRun 으로 변환 진행.
        }
        // SPEC-025: 단순 배지 자식 (Phase 2 가 별도 글상자로 배치) → 인라인 임베드 중복 방지.
        if (ctx.resolvedData.isSimpleBadgeChild(domId)) return null;

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
        run.text(visText + " "); // 뒤에 공백 추가 (텍스트와의 간격)

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

    /** IDML 경로용: resolved TextFrame bounds만으로 판별 (renderedFloatingItems 사용 안 함) */
    static boolean isAnchoredOutsideParentByTextFrame(ResolvedBuildContext ctx, int anchoredId, String parentStoryId) {
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

    /**
     * renderedFloatingItems에서 인라인 객체 PNG를 로드하여 ASTInlineObject로 변환.
     */
    static ASTInlineObject loadInlineObject(ResolvedBuildContext ctx, int anchoredObjectId) {
        if (ctx.basePath == null) return null;

        // Phase 2 가 이 inline_object 의 자손 TF 를 floating 으로 전환했으면
        // inline PNG 는 Phase 7 이 floating ASTFigure 로 재배치 → 여기서 억제.
        if (ctx.inlineObjectsToConvertToFloating.contains(anchoredObjectId)) return null;
        // Phase 2 가 floating text box 로 승격한 inline TF → inline PNG 도 억제 (28pt PNG가 행간 팽창하는 것 방지).
        if (ctx.renderedTfPlacedAsText != null && ctx.renderedTfPlacedAsText.contains(anchoredObjectId)) return null;

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
            // SPEC-025: inline+editable 배지 자식은 Phase 2 가 플로팅 배치 스킵 →
            // PNG 가 유일한 시각 표현. loadInlineObject 가 PNG 폐기하면 배지 자체가 사라짐 → 통과시킴.
            if (childTf.isInline() && ctx.resolvedData.isEditableTextFrame(childTf.id())) {
                continue;
            }
            if (ctx.resolvedData.isEditableTextFrame(childTf.id())) {
                return null;
            }
            // inline + non-editable: 부모가 badge_group(PNG에 시각이 이미 포함됨)이면 계속 사용.
            // 아닐 경우 Phase 3 가 이 child TF 를 별도로 처리할 수 있으므로 PNG 폐기.
            if (childTf.isInline()) {
                boolean parentIsBadgeGroup = false;
                for (RenderedGroup cand : ctx.resolvedData.allRenderedTextFrames()) {
                    if (cand.id() == anchoredObjectId && cand.isBadgeGroup()) {
                        parentIsBadgeGroup = true;
                        break;
                    }
                }
                if (!parentIsBadgeGroup) return null;
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
                boolean isNullTypeInline = rg.itemType() == null;
                // badge_group PNG가 있으면 inline_object PNG 대신 사용.
                // 단, badge_group PNG가 사실상 빈 이미지(가시 픽셀 < 10%)인 경우 inline_object PNG 유지.
                // (badge_group PNG 추출 실패 시 노이즈 픽셀만 남는 현상 방어)
                RenderedGroup effectiveRg = rg;
                RenderedGroup badgeCandidate = ctx.resolvedData.getBadgeGroupByDomId(anchoredObjectId); // O(1)
                if (badgeCandidate != null && badgeCandidate.file() != null) {
                    File bgFile = new File(ctx.basePath, badgeCandidate.file());
                    if (bgFile.exists()) {
                        try {
                            BufferedImage bgImg = ImageIO.read(bgFile);
                            if (bgImg != null && isBadgePngValid(bgImg)) {
                                effectiveRg = badgeCandidate;
                            }
                        } catch (Exception ignored) {}
                    }
                }
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
                        // SPEC-020: 페이지 절대 좌표 기록 — 같은 셀에 여러 인라인이 있을 때
                        // cellX/cellY fallback 으로 겹치는 문제를 막는다.
                        double pxPt = bounds[1] * ctx.scaleFactor;
                        double pyPt = bounds[0] * ctx.scaleFactor;
                        obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(pxPt));
                        obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(pyPt));
                        double bw = Math.abs(bounds[3] - bounds[1]) * ctx.scaleFactor; // right - left
                        double bh = Math.abs(bounds[2] - bounds[0]) * ctx.scaleFactor; // bottom - top
                        // PNG 비율로 보정 (bounds가 부정확한 경우)
                        double pngRatio = (double) img.getWidth() / img.getHeight();
                        double boundsRatio = bw / bh;
                        // bounds 비율과 PNG 비율이 다르면 PNG 비율 기준으로 보정
                        // bounds의 작은 쪽을 기준으로 맞춤 (원본 크기 초과 방지)
                        // null-type inline TF(번호 라벨 등)는 bounds 원본 크기 유지
                        if (!isNullTypeInline && Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
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
                        if (bw > bh * 8.0 && bw > 100.0) {
                            ctx.inlineObjectsToConvertToFloating.add(anchoredObjectId);
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

    /**
     * inline_object PNG로 배치된 Group의 직속 editable 자식 TF를
     * INLINE_TEXT_FRAME으로 변환한다.
     *
     * PNG에는 editable TF 내용이 포함되지 않으므로 별도로 배치해야 한다.
     */
    static java.util.List<ASTInlineObject> buildChildEditableBoxes(ResolvedBuildContext ctx, int groupId) {
        java.util.List<ASTInlineObject> result = new ArrayList<>();
        String groupIdStr = String.valueOf(groupId);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            ResolvedPageItem pi = ctx.resolvedData.getPageItem(tf.id());
            if (pi == null || !groupIdStr.equals(pi.parentId())) continue;
            if (!tf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            String vt = tf.frameVisibleText();
            if (vt == null) continue;
            String cleaned = vt.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (cleaned.isEmpty()) continue;
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            double w = Math.abs(gb[3] - gb[1]);
            double h = Math.abs(gb[2] - gb[0]);
            if (w <= 0 || h <= 0) continue;
            int tfDomId;
            try { tfDomId = Integer.parseInt(tf.id()); } catch (NumberFormatException e) { continue; }

            ASTInlineObject box = new ASTInlineObject();
            box.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
            box.width(CoordinateConverter.pointsToHwpunits(w));
            box.height(CoordinateConverter.pointsToHwpunits(h));
            box.sourceId("child_u" + Integer.toHexString(tfDomId));

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
            // SPEC-025: 앵커 TF 가 단순 배지 자식 (Phase 2 가 별도 글상자로 배치) 인 경우도 인라인 임베드 스킵 → 중복 방지.
            if (isSimpleBadgeChild(ctx, tf.selfId())) return "";
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
    private static ResolvedTextFrame findInlineEditableDescendant(ResolvedBuildContext ctx, String anchorIdStr) {
        for (ResolvedTextFrame childTf : ctx.resolvedData.textFrames()) {
            if (childTf == null || !childTf.isInline()) continue;
            if (!ctx.resolvedData.isEditableTextFrame(childTf.id())) continue;
            String vt = childTf.frameVisibleText();
            if (vt == null || vt.replace("￼", "").trim().isEmpty()) continue;
            String curId = childTf.id();
            int depth = 0;
            while (curId != null && depth < 8) {
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(curId);
                if (pi == null) break;
                String pid = pi.parentId();
                if (pid == null) break;
                if (anchorIdStr.equals(pid)) return childTf;
                curId = pid;
                depth++;
            }
        }
        return null;
    }

    /** IDML selfId 의 TextFrame 이 단순 scribble 배지 자식 (Phase 2 가 글상자로 배치) 인지 확인. */
    private static boolean isSimpleBadgeChild(ResolvedBuildContext ctx, String idmlSelfId) {
        if (ctx == null || ctx.resolvedData == null || idmlSelfId == null) return false;
        if (!idmlSelfId.startsWith("u")) return false;
        int domId;
        try { domId = Integer.parseInt(idmlSelfId.substring(1), 16); }
        catch (NumberFormatException e) { return false; }
        return ctx.resolvedData.isSimpleBadgeChild(String.valueOf(domId));
    }

    /** InlineGraphic(Group/Rectangle/Polygon) 내부의 모든 TextFrame 텍스트를 재귀로 합쳐 반환. */
    private static String extractGraphicText(ResolvedBuildContext ctx, IDMLCharacterRun.InlineGraphic ig, int depth) {
        if (ig == null || depth >= 4) return "";
        StringBuilder sb = new StringBuilder();
        // 그래픽 자체에 임베드된 텍스트
        if (ig.embeddedText() != null && !ig.embeddedText().isEmpty()) {
            sb.append(ig.embeddedText());
        }
        // Group 자식 TextFrame
        if (ig.childTextFrames() != null) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame ctf : ig.childTextFrames()) {
                if (ctf == null || ctf.parentStoryId() == null) continue;
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

    /** badge_group PNG가 실질적 내용을 담고 있는지 확인 (가시 픽셀 > 10%). */
    private static boolean isBadgePngValid(BufferedImage img) {
        int total = img.getWidth() * img.getHeight();
        if (total == 0) return false;
        int threshold = total / 10;
        int vis = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >> 24) & 0xFF) > 50) {
                    if (++vis > threshold) return true;
                }
            }
        }
        return false;
    }
}
