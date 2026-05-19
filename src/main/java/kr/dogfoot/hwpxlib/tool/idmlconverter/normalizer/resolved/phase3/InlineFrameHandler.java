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
import java.util.HashMap;
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
class InlineFrameHandler {

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

    static ASTTextRun tryInlineTextFrameAsRun(ResolvedBuildContext ctx, int anchoredObjectId) {
        String domId = String.valueOf(anchoredObjectId);
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || !tf.isInline()) return null;

        // rendered된 TF(badge_group 등)는 PNG로 이미 배치됨 → 텍스트 런 변환 안 함
        if (ctx.resolvedData.isRenderedByOtherChannel(anchoredObjectId)) return null;

        // frameVisibleText 또는 IDML Story에서 텍스트 가져오기
        String visText = tf.frameVisibleText();
        if (visText != null) {
            visText = visText.replace("\uFFFC", "").replace("\n", "").replace("\r", "").trim();
        }
        if (visText == null || visText.isEmpty()) {
            // IDML Story에서 폴백
            if (tf.storyId() != null) {
                IDMLStory idmlStory = ctx.loadIDMLStory.apply(tf.storyId());
                if (idmlStory != null) {
                    StringBuilder sb = new StringBuilder();
                    for (IDMLParagraph p : idmlStory.paragraphs()) {
                        for (IDMLCharacterRun r : p.characterRuns()) {
                            if (r.content() != null) sb.append(r.content());
                        }
                    }
                    visText = sb.toString().replace("\uFFFC", "").trim();
                }
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

        // 장식 번호 인라인(≤3자 + 큰 폰트 또는 정사각형 프레임) → 텍스트 런 변환하지 않음 (PNG 유지)
        if (visText.length() <= 3) {
            ResolvedStory rs = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
            boolean isDecorativeNumber = false;
            // 큰 폰트(≥16pt) 체크
            if (rs != null && !rs.paragraphs().isEmpty()) {
                ResolvedParagraph rp0 = rs.paragraphs().get(0);
                if (rp0.runs() != null && !rp0.runs().isEmpty()) {
                    Double fs = rp0.runs().get(0).fontSize();
                    if (fs != null && fs >= 16) isDecorativeNumber = true;
                }
            }
            // 정사각형에 가까운 프레임 (가로/세로 비율 0.7~1.4)
            double[] gb = tf.geometricBounds();
            if (gb != null && gb.length >= 4) {
                double fw = gb[3] - gb[1];
                double fh = gb[2] - gb[0];
                if (fw > 0 && fh > 0) {
                    double ratio = fw / fh;
                    if (ratio >= 0.7 && ratio <= 1.4) isDecorativeNumber = true;
                }
            }
            if (isDecorativeNumber) return null; // PNG 폴백 (loadInlineObject로 처리)
        }

        // resolved story에서 런 스타일 가져오기
        ResolvedStory story = (tf.storyId() != null) ? ctx.resolvedData.getStory(tf.storyId()) : null;
        ASTTextRun run = new ASTTextRun();
        run.text(visText + " "); // 뒤에 공백 추가 (텍스트와의 간격)

        if (story != null && !story.paragraphs().isEmpty()) {
            ResolvedParagraph rp = story.paragraphs().get(0);
            if (rp.runs() != null && !rp.runs().isEmpty()) {
                ResolvedRun rr = rp.runs().get(0);
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
            if (ctx.resolvedData.isEditableTextFrame(childTf.id())
                    || childTf.isInline()) {
                return null;
            }
        }

        // 같은 ID가 badge_group으로도 등록되어 있으면 badge PNG를 우선 사용.
        // (inline_object PNG는 자식 텍스트를 누락하는 경우가 있음)
        RenderedGroup badgeGroup = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredObjectId && "badge_group".equals(rg.itemType())) {
                badgeGroup = rg;
                break;
            }
        }

        // renderedFloatingItems에서 해당 ID의 inline_object 찾기 (badge_group이 있으면 그것으로 교체)
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == anchoredObjectId && "inline_object".equals(rg.itemType())) {
                if (badgeGroup != null) rg = badgeGroup;
                if (rg.file() == null) return null;
                File pngFile = new File(ctx.basePath, rg.file());
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
                        if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
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

                    // 장식 번호 인라인(≤3자 + 큰 폰트/정사각형): 높이를 본문 줄 높이로 제한
                    // 인라인 이미지 높이가 줄간격을 벌리는 것 방지
                    ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(String.valueOf(anchoredObjectId));
                    if (rtf != null && obj.height() > 1500) { // 15pt 초과
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

                    return obj;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
