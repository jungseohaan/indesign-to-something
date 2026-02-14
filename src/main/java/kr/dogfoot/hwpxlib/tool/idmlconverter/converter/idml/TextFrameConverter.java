package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.IDMLEquationExtractor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IDMLPageRenderer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.StyleMapper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.textfit.TextFitter;

import java.io.IOException;
import java.util.*;
import java.util.Base64;

/**
 * IDML 텍스트 프레임을 IntermediateFrame으로 변환한다.
 *
 * 담당 영역:
 * - 텍스트 프레임 좌표/크기 계산
 * - 컬럼 정보 변환
 * - Story 내용 (단락/런) 변환
 * - 연결된 프레임 체인 처리
 */
public class TextFrameConverter {

    private final IDMLDocument idmlDoc;
    private final Map<String, String> paraStyleRefToId;
    private final Map<String, String> charStyleRefToId;
    private final ColorResolver colorResolver;
    private final List<String> warnings;

    // 연결된 프레임 체인 캐시
    private final Map<String, List<String>> linkedFrameTextCache;
    private final Map<String, Integer> linkedFrameIndexCache;

    // 인라인 그래픽 PNG 렌더링용 (옵션)
    private IDMLPageRenderer inlineGraphicRenderer;

    public TextFrameConverter(IDMLDocument idmlDoc,
                              Map<String, String> paraStyleRefToId,
                              Map<String, String> charStyleRefToId,
                              ColorResolver colorResolver,
                              List<String> warnings) {
        this.idmlDoc = idmlDoc;
        this.paraStyleRefToId = paraStyleRefToId;
        this.charStyleRefToId = charStyleRefToId;
        this.colorResolver = colorResolver;
        this.warnings = warnings;
        this.linkedFrameTextCache = new HashMap<>();
        this.linkedFrameIndexCache = new HashMap<>();
    }

    /**
     * 인라인 그래픽 PNG 렌더러를 설정한다.
     */
    public void setInlineGraphicRenderer(IDMLPageRenderer renderer) {
        this.inlineGraphicRenderer = renderer;
    }

    /**
     * 스프레드 모드에서 텍스트 프레임을 변환한다.
     */
    public IntermediateFrame convertForSpread(IDMLTextFrame tf, IDMLStory story,
                                               IDMLPage page, double[] pageTopLeft, int zOrder) {
        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("frame_" + tf.selfId());
        iFrame.frameType("text");

        // 프레임 좌표 계산 (페이지 상대 → 스프레드 상대)
        setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());

        // 디버그: 좌표 계산 추적
        long pageRelX = iFrame.x();
        long pageRelY = iFrame.y();

        iFrame.x(iFrame.x() + CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
        iFrame.y(iFrame.y() + CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
        iFrame.zOrder(zOrder);

        // 텍스트 방향 설정 (세로쓰기 여부)
        iFrame.verticalText(story.isVertical());

        // 첫 번째 단락의 텍스트 미리보기 (디버그용)
        String textPreview = "";
        if (!story.paragraphs().isEmpty() && !story.paragraphs().get(0).characterRuns().isEmpty()) {
            textPreview = story.paragraphs().get(0).characterRuns().get(0).content();
            if (textPreview != null && textPreview.length() > 20) {
                textPreview = textPreview.substring(0, 20);
            }
        }

        // 디버그 출력
        double[] fb = tf.geometricBounds();
        System.err.printf("[DEBUG] TextFrame %s: \"%s\"%n", tf.selfId(), textPreview);
        System.err.printf("  IDML bounds: [%.2f, %.2f, %.2f, %.2f] (top,left,bottom,right)%n",
                fb[0], fb[1], fb[2], fb[3]);
        System.err.printf("  IDML size: %.2f x %.2f pts (%.2f x %.2f mm)%n",
                fb[3]-fb[1], fb[2]-fb[0], (fb[3]-fb[1])*0.3528, (fb[2]-fb[0])*0.3528);
        System.err.printf("  pageTopLeft offset: [%.2f, %.2f] pts%n", pageTopLeft[0], pageTopLeft[1]);
        System.err.printf("  페이지 상대 좌표: (%d, %d) HWPUNIT = (%.2f, %.2f) mm%n",
                pageRelX, pageRelY, pageRelX/283.465, pageRelY/283.465);
        System.err.printf("  최종 스프레드 좌표: (%d, %d) HWPUNIT = (%.2f, %.2f) mm%n",
                iFrame.x(), iFrame.y(), iFrame.x()/283.465, iFrame.y()/283.465);
        System.err.printf("  최종 크기: %d x %d HWPUNIT = %.2f x %.2f mm%n%n",
                iFrame.width(), iFrame.height(), iFrame.width()/283.465, iFrame.height()/283.465);

        // 컬럼 정보 설정
        setColumnInfo(iFrame, tf);

        // Story 내용 변환 (수식 없이 텍스트만)
        for (IDMLParagraph para : story.paragraphs()) {
            IntermediateParagraph iPara = new IntermediateParagraph();

            String paraStyleRef = para.appliedParagraphStyle();
            if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
            }

            // 인라인 단락 속성 (로컬 오버라이드)
            setInlineParagraphProperties(para, iPara);

            convertTextRuns(para, iPara);
            iFrame.addParagraph(iPara);
        }

        return iFrame;
    }

    /**
     * Story 내용을 IntermediateFrame에 변환한다.
     */
    public void convertStory(IDMLStory story, IntermediateFrame iFrame,
                             Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations) {
        List<IDMLEquationExtractor.ExtractedEquation> equations =
                storyEquations.get(story.selfId());

        // 단락 인덱스별 수식 매핑 생성
        Map<Integer, List<IDMLEquationExtractor.ExtractedEquation>> equationsByPara = new HashMap<>();
        if (equations != null) {
            for (IDMLEquationExtractor.ExtractedEquation eq : equations) {
                equationsByPara.computeIfAbsent(eq.paragraphIndex, k -> new ArrayList<>()).add(eq);
            }
        }

        for (int paraIdx = 0; paraIdx < story.paragraphs().size(); paraIdx++) {
            IDMLParagraph para = story.paragraphs().get(paraIdx);
            IntermediateParagraph iPara = new IntermediateParagraph();

            // 단락 스타일 참조
            String paraStyleRef = para.appliedParagraphStyle();
            if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                iPara.paragraphStyleRef(paraStyleRefToId.get(paraStyleRef));
            }

            // 인라인 단락 속성 (로컬 오버라이드)
            setInlineParagraphProperties(para, iPara);

            List<IDMLEquationExtractor.ExtractedEquation> paraEquations = equationsByPara.get(paraIdx);

            if (para.hasEquationContent() && paraEquations != null && !paraEquations.isEmpty()) {
                // 혼합 단락: NP 런 경계를 기준으로 텍스트/수식 교차 배치
                convertMixedParagraph(para, iPara, paraEquations);
            } else {
                // 일반 텍스트 단락
                convertTextRuns(para, iPara);
            }

            // 빈 단락도 추가 (줄바꿈 역할)
            iFrame.addParagraph(iPara);
        }
    }

    /**
     * 프레임의 페이지 상대 좌표를 설정한다 (스케일, 회전 변환 적용).
     */
    public void setFramePosition(IntermediateFrame iFrame,
                                  double[] frameBounds, double[] frameTransform,
                                  double[] pageBounds, double[] pageTransform) {
        setFramePosition(iFrame, frameBounds, frameTransform, pageBounds, pageTransform, -1);
    }

    /**
     * 프레임의 페이지 상대 좌표를 설정한다 (스케일, 회전 변환 적용).
     *
     * @param containerWidthHwpunits 컨테이너 너비 (HWPUNIT), -1이면 페이지 너비 사용
     */
    public void setFramePosition(IntermediateFrame iFrame,
                                  double[] frameBounds, double[] frameTransform,
                                  double[] pageBounds, double[] pageTransform,
                                  long containerWidthHwpunits) {
        if (frameBounds == null) {
            iFrame.x(0);
            iFrame.y(0);
            iFrame.width(0);
            iFrame.height(0);
            return;
        }

        // 프레임의 변환된 bounding box 계산
        double[] transformedBox = IDMLGeometry.getTransformedBoundingBox(frameBounds, frameTransform);

        // 페이지 절대 좌표
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(pageBounds, pageTransform);

        // 페이지 상대 좌표
        double relX = transformedBox[0] - pageAbs[0];
        double relY = transformedBox[1] - pageAbs[1];
        double width = transformedBox[2] - transformedBox[0];
        double height = transformedBox[3] - transformedBox[1];

        // 컨테이너 너비 계산 (HWPUNIT으로 변환된 값 사용)
        long widthHwpunits = CoordinateConverter.pointsToHwpunits(width);
        long xHwpunits = CoordinateConverter.pointsToHwpunits(relX);

        // 페이지 오른쪽 여백 경계 계산
        if (containerWidthHwpunits <= 0 && pageBounds != null && pageBounds.length >= 4) {
            // 페이지 너비: right - left
            double pageWidth = pageBounds[3] - pageBounds[1];
            // 기본 오른쪽 여백 (60pt = 약 21mm)
            double rightMargin = 60.0;
            // 오른쪽 경계 = 페이지 너비 - 오른쪽 여백
            double rightEdge = pageWidth - rightMargin;
            // 최대 너비 = 오른쪽 경계 - x좌표
            long rightEdgeHwpunits = CoordinateConverter.pointsToHwpunits(rightEdge);
            long maxWidth = rightEdgeHwpunits - xHwpunits;
            if (maxWidth > 0 && widthHwpunits > maxWidth) {
                widthHwpunits = maxWidth;
            }
        } else if (containerWidthHwpunits > 0) {
            // 명시적으로 지정된 컨테이너 너비 사용
            long maxWidth = containerWidthHwpunits - xHwpunits;
            if (maxWidth > 0 && widthHwpunits > maxWidth) {
                widthHwpunits = maxWidth;
            }
        }

        iFrame.x(xHwpunits);
        iFrame.y(CoordinateConverter.pointsToHwpunits(relY));
        iFrame.width(widthHwpunits);
        iFrame.height(CoordinateConverter.pointsToHwpunits(height));

        // 회전 각도 추출 및 설정
        double rotation = IDMLGeometry.extractRotation(frameTransform);
        iFrame.rotationAngle(rotation);

        // 회전이 있는 경우 디버그 출력
        if (Math.abs(rotation) > 0.1) {
            System.err.println("[DEBUG] Frame rotation: " + iFrame.frameId() + " = "
                    + CoordinateConverter.fmt(rotation) + "°");
        }
    }

    /**
     * 텍스트 프레임의 컬럼 정보를 설정한다.
     */
    public void setColumnInfo(IntermediateFrame iFrame, IDMLTextFrame tf) {
        iFrame.columnCount(tf.columnCount() > 0 ? tf.columnCount() : 1);

        if (tf.columnCount() > 1) {
            iFrame.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));
        }

        if (tf.insetSpacing() != null) {
            double[] inset = tf.insetSpacing();
            iFrame.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
            iFrame.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
            iFrame.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
            iFrame.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
        }

        // 컬럼 상세 속성
        iFrame.columnType(tf.columnType());
        if (tf.columnFixedWidth() > 0) {
            iFrame.columnFixedWidth(CoordinateConverter.pointsToHwpunits(tf.columnFixedWidth()));
        }
        if (tf.columnWidths() != null) {
            double[] srcWidths = tf.columnWidths();
            long[] dstWidths = new long[srcWidths.length];
            for (int i = 0; i < srcWidths.length; i++) {
                dstWidths[i] = CoordinateConverter.pointsToHwpunits(srcWidths[i]);
            }
            iFrame.columnWidths(dstWidths);
        }

        // 수직 정렬
        String vJust = tf.verticalJustification();
        if (vJust != null) {
            if (vJust.contains("Top")) iFrame.verticalJustification("top");
            else if (vJust.contains("Center")) iFrame.verticalJustification("center");
            else if (vJust.contains("Bottom")) iFrame.verticalJustification("bottom");
            else if (vJust.contains("Justify")) iFrame.verticalJustification("justify");
        }

        // 텍스트 감싸기 무시
        iFrame.ignoreWrap(tf.ignoreWrap());

        // 단 경계선 (Column Rule)
        iFrame.useColumnRule(tf.useColumnRule());
        if (tf.useColumnRule()) {
            iFrame.columnRuleWidth(CoordinateConverter.pointsToHwpunits(tf.columnRuleWidth()));
            iFrame.columnRuleType(tf.columnRuleType());
            // 색상 변환 (Color 참조 → HEX)
            String ruleColor = tf.columnRuleColor();
            if (ruleColor != null && !ruleColor.contains("None")) {
                String hexColor = idmlDoc.getColor(ruleColor);
                iFrame.columnRuleColor(hexColor != null ? hexColor : ruleColor);
            }
            iFrame.columnRuleTint(tf.columnRuleTint());
            iFrame.columnRuleOffset(CoordinateConverter.pointsToHwpunits(tf.columnRuleOffset()));
            iFrame.columnRuleInsetWidth(CoordinateConverter.pointsToHwpunits(tf.columnRuleInsetWidth()));
        }

        // 스트로크/아웃라인 속성 설정
        setTextFrameStrokeInfo(iFrame, tf);
    }

    /**
     * 텍스트 프레임의 스트로크/아웃라인 속성을 설정한다.
     */
    private void setTextFrameStrokeInfo(IntermediateFrame iFrame, IDMLTextFrame tf) {
        // 획 색상 (Color 참조를 HEX로 변환)
        String strokeColor = tf.strokeColor();
        if (strokeColor != null && !strokeColor.contains("None")) {
            String hexColor = idmlDoc.getColor(strokeColor);
            iFrame.strokeColor(hexColor != null ? hexColor : strokeColor);
        }

        // 획 두께
        iFrame.strokeWeight(tf.strokeWeight());

        // 모서리 둥글기
        iFrame.cornerRadius(tf.cornerRadius());
        if (tf.cornerRadii() != null) {
            iFrame.cornerRadii(tf.cornerRadii());
        }

        // 불투명도
        iFrame.fillTint(tf.fillTint());
        iFrame.strokeTint(tf.strokeTint());

        // 채우기 색상 (Color 참조를 HEX로 변환)
        String fillColor = tf.fillColor();
        if (fillColor != null && !fillColor.contains("None")) {
            String hexColor = idmlDoc.getColor(fillColor);
            iFrame.textFrameFillColor(hexColor != null ? hexColor : fillColor);
        }
    }

    /**
     * IDML 단락의 인라인 속성(로컬 오버라이드)을 IntermediateParagraph에 설정한다.
     * 글상자와 테이블 셀 모두에서 사용된다.
     */
    public void setInlineParagraphProperties(IDMLParagraph para, IntermediateParagraph iPara) {
        // 정렬
        if (para.justification() != null) {
            iPara.inlineAlignment(StyleMapper.mapAlignment(para.justification()));
        }
        // 첫 줄 들여쓰기
        if (para.firstLineIndent() != null) {
            iPara.inlineFirstLineIndent(CoordinateConverter.pointsToHwpunits(para.firstLineIndent()));
        }
        // 왼쪽 여백
        if (para.leftIndent() != null) {
            iPara.inlineLeftMargin(CoordinateConverter.pointsToHwpunits(para.leftIndent()));
        }
        // 오른쪽 여백
        if (para.rightIndent() != null) {
            iPara.inlineRightMargin(CoordinateConverter.pointsToHwpunits(para.rightIndent()));
        }
        // 단락 앞 간격
        if (para.spaceBefore() != null) {
            iPara.inlineSpaceBefore(CoordinateConverter.pointsToHwpunits(para.spaceBefore()));
        }
        // 단락 뒤 간격
        if (para.spaceAfter() != null) {
            iPara.inlineSpaceAfter(CoordinateConverter.pointsToHwpunits(para.spaceAfter()));
        }
        // 줄간격 (leading → 백분율로 변환, 단락의 실제 폰트 크기 기준)
        if (para.leading() != null) {
            double baseFontSizePt = getDominantFontSizePt(para);
            int lineSpacingPercent = (int) Math.round(para.leading() / baseFontSizePt * 100.0);
            lineSpacingPercent = Math.max(100, Math.min(300, lineSpacingPercent));
            iPara.inlineLineSpacing(lineSpacingPercent);
        }
        // 자간 (tracking → HWPX spacing 변환)
        if (para.tracking() != null) {
            short letterSpacing = (short) Math.round(para.tracking() / 10.0);
            letterSpacing = (short) Math.max(-50, Math.min(50, letterSpacing));
            iPara.inlineLetterSpacing(letterSpacing);
        }
        // 단락 음영 (Paragraph Shading)
        if (para.shadingOn()) {
            iPara.shadingOn(true);
            if (para.shadingColor() != null) {
                String resolvedColor = StyleMapper.resolveColor(para.shadingColor(), idmlDoc.colors());
                if (para.shadingTint() != null && para.shadingTint() < 100.0) {
                    resolvedColor = applyTintToColor(resolvedColor, para.shadingTint());
                }
                iPara.shadingColor(resolvedColor);
            }
            iPara.shadingTint(para.shadingTint());
            if (para.shadingOffsetLeft() != null) {
                iPara.shadingOffsetLeft(CoordinateConverter.pointsToHwpunits(para.shadingOffsetLeft()));
            }
            if (para.shadingOffsetRight() != null) {
                iPara.shadingOffsetRight(CoordinateConverter.pointsToHwpunits(para.shadingOffsetRight()));
            }
            if (para.shadingOffsetTop() != null) {
                iPara.shadingOffsetTop(CoordinateConverter.pointsToHwpunits(para.shadingOffsetTop()));
            }
            if (para.shadingOffsetBottom() != null) {
                iPara.shadingOffsetBottom(CoordinateConverter.pointsToHwpunits(para.shadingOffsetBottom()));
            }
        }
    }

    /**
     * 단락의 주요 폰트 크기(pt)를 반환한다.
     * 런 중 최대값 → 단락 스타일 → 기본 10pt 순으로 결정.
     */
    public double getDominantFontSizePt(IDMLParagraph para) {
        double maxSize = 0;
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.fontSize() != null && run.fontSize() > maxSize) {
                maxSize = run.fontSize();
            }
        }
        if (maxSize > 0) return maxSize;

        String styleRef = para.appliedParagraphStyle();
        if (styleRef != null) {
            IDMLStyleDef styleDef = idmlDoc.getParagraphStyle(styleRef);
            if (styleDef != null && styleDef.fontSize() != null) {
                return styleDef.fontSize();
            }
        }
        return 10.0;
    }

    /**
     * 일반 텍스트 런만 있는 단락을 변환한다.
     */
    public void convertTextRuns(IDMLParagraph para, IntermediateParagraph iPara) {
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.isNPFont()) continue;

            boolean hasInlineFrames = (run.inlineFrames() != null && !run.inlineFrames().isEmpty());
            boolean hasInlineGraphics = (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty());

            // 텍스트 런을 먼저 추가 (인라인 그래픽은 텍스트 뒤에 위치)
            IntermediateTextRun iRun = createTextRun(run);
            if (iRun != null) {
                if (iRun.letterSpacing() == null && iPara.inlineLetterSpacing() != null) {
                    iRun.letterSpacing(iPara.inlineLetterSpacing());
                }
                iPara.addRun(iRun);
            }

            // 인라인 객체(TextFrame) 처리 - Story 내 그래픽이 있으면 PNG로 렌더링
            if (hasInlineFrames) {
                for (IDMLTextFrame inlineFrame : run.inlineFrames()) {
                    processInlineTextFrame(inlineFrame, iPara);
                }
            }

            // 인라인 그래픽(Rectangle, Polygon 등) 처리 - IntermediateFrame으로 변환
            if (hasInlineGraphics) {
                for (IDMLCharacterRun.InlineGraphic graphic : run.inlineGraphics()) {
                    IntermediateFrame inlineFrame = createInlineGraphicFrame(graphic);
                    if (inlineFrame != null) {
                        iPara.addInlineFrame(inlineFrame);
                    }
                }
            }
        }
    }

    /**
     * 인라인 TextFrame 객체를 처리한다.
     * Story가 그래픽만 포함하면 PNG로 렌더링하여 인라인 이미지로 변환한다.
     */
    private void processInlineTextFrame(IDMLTextFrame inlineFrame, IntermediateParagraph iPara) {
        // 인라인 TextFrame이 참조하는 Story 조회
        String parentStoryId = inlineFrame.parentStoryId();
        IDMLStory referencedStory = (parentStoryId != null) ? idmlDoc.getStory(parentStoryId) : null;

        if (referencedStory != null && inlineGraphicRenderer != null) {
            // 1. Story에 직접 그래픽이 있는 경우
            List<IDMLCharacterRun.InlineGraphic> graphics = referencedStory.getAllInlineGraphics();
            if (!graphics.isEmpty()) {
                for (IDMLCharacterRun.InlineGraphic graphic : graphics) {
                    IntermediateFrame imageFrame = createInlineGraphicFrame(graphic);
                    if (imageFrame != null) {
                        iPara.addInlineFrame(imageFrame);
                    }
                }
                return;
            }

            // 2. Story에 테이블이 있고, 테이블 셀에 그래픽이 있는 경우 (중첩 테이블 내 인라인 그래픽)
            // 재귀적으로 모든 중첩 테이블의 그래픽을 추출하여 렌더링
            if (referencedStory.hasTables()) {
                List<IDMLCharacterRun.InlineGraphic> tableGraphics = extractGraphicsFromStoryTables(referencedStory);
                if (!tableGraphics.isEmpty()) {
                    System.err.println("[DEBUG-NESTED-TABLE-INLINE] Found " + tableGraphics.size() +
                            " graphics in nested tables of " + inlineFrame.selfId());
                    // 각 그래픽을 PNG로 렌더링하여 인라인 이미지로 추가
                    for (IDMLCharacterRun.InlineGraphic graphic : tableGraphics) {
                        System.err.println("[DEBUG-NESTED-TABLE-INLINE] Rendering: " + graphic.selfId() +
                                " type=" + graphic.type());
                        IntermediateFrame imageFrame = createInlineGraphicFrame(graphic);
                        if (imageFrame != null) {
                            iPara.addInlineFrame(imageFrame);
                        }
                    }
                    return;
                }
            }
        }

        // 인라인 TextFrame의 텍스트 내용 추출
        if (referencedStory != null) {
            String textContent = extractStoryText(referencedStory);
            if (textContent != null && !textContent.trim().isEmpty()) {
                // 텍스트 내용을 인라인 텍스트 프레임으로 변환
                IntermediateFrame textFrameResult = createInlineTextFrameWithContent(inlineFrame, referencedStory);
                if (textFrameResult != null) {
                    iPara.addInlineFrame(textFrameResult);
                    return;
                }
            }
        }

        // 내용이 없으면 공백 삽입
        IntermediateTextRun iRun = new IntermediateTextRun();
        iRun.text(" ");
        iPara.addRun(iRun);
    }

    /**
     * Story의 텍스트 내용을 추출한다.
     */
    private String extractStoryText(IDMLStory story) {
        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (run.content() != null) {
                    sb.append(run.content());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 인라인 TextFrame을 텍스트 내용과 함께 IntermediateFrame으로 변환한다.
     */
    private IntermediateFrame createInlineTextFrameWithContent(IDMLTextFrame inlineFrame, IDMLStory story) {
        double[] bounds = inlineFrame.geometricBounds();
        if (bounds == null || bounds.length < 4) return null;

        double widthPts = bounds[3] - bounds[1];
        double heightPts = bounds[2] - bounds[0];

        // ItemTransform 스케일 적용
        double[] transform = inlineFrame.itemTransform();
        if (transform != null && transform.length >= 4) {
            double scaleX = Math.abs(transform[0]);
            double scaleY = Math.abs(transform[3]);
            if (scaleX > 0.001) widthPts *= scaleX;
            if (scaleY > 0.001) heightPts *= scaleY;
        }

        if (widthPts <= 0 || heightPts <= 0) return null;

        long width = CoordinateConverter.pointsToHwpunits(widthPts);
        long height = CoordinateConverter.pointsToHwpunits(heightPts);

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("inline_tf_" + inlineFrame.selfId());
        iFrame.frameType("text");
        iFrame.isInline(true);
        iFrame.width(width);
        iFrame.height(height);
        iFrame.x(0);
        iFrame.y(0);

        // Story의 단락을 IntermediateParagraph로 변환
        for (IDMLParagraph para : story.paragraphs()) {
            IntermediateParagraph iPara = new IntermediateParagraph();
            iPara.paragraphStyleRef(para.appliedParagraphStyle());

            for (IDMLCharacterRun run : para.characterRuns()) {
                String content = run.content();
                // 단락 끝 \n 제거 (IDMLParagraph로 이미 단락 분리됨)
                if (content != null && content.endsWith("\n")) {
                    content = content.substring(0, content.length() - 1);
                }
                if (content != null && !content.isEmpty()) {
                    IntermediateTextRun iRun = new IntermediateTextRun();
                    iRun.text(content);
                    iRun.characterStyleRef(run.appliedCharacterStyle());
                    iRun.fontFamily(run.fontFamily());
                    if (run.fontSize() != null) {
                        iRun.fontSizeHwpunits(CoordinateConverter.fontSizeToHeight(run.fontSize()));
                    }
                    iRun.textColor(colorResolver.resolve(run.fillColor()));
                    iPara.addRun(iRun);
                }
            }

            if (!iPara.runs().isEmpty()) {
                iFrame.addParagraph(iPara);
            }
        }

        return iFrame.paragraphs().isEmpty() ? null : iFrame;
    }

    /**
     * 이중 인라인 객체용 플레이스홀더 프레임 생성.
     */
    private IntermediateFrame createPlaceholderFrame(IDMLTextFrame textFrame) {
        double[] bounds = textFrame.geometricBounds();
        if (bounds == null || bounds.length < 4) return null;

        double widthPts = bounds[3] - bounds[1];
        double heightPts = bounds[2] - bounds[0];
        if (widthPts <= 0 || heightPts <= 0) return null;

        long width = CoordinateConverter.pointsToHwpunits(widthPts);
        long height = CoordinateConverter.pointsToHwpunits(heightPts);

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("placeholder_" + textFrame.selfId());
        iFrame.frameType("shape");
        iFrame.shapeType("rectangle");
        iFrame.isInline(true);
        iFrame.width(width);
        iFrame.height(height);
        iFrame.x(0);
        iFrame.y(0);
        // 연한 회색 배경으로 플레이스홀더 표시
        iFrame.fillColor("#EEEEEE");
        return iFrame;
    }

    /**
     * 인라인 TextFrame 전체를 PNG 이미지로 렌더링한다.
     * TextFrame의 geometricBounds를 사용하여 크기 계산.
     */
    private IntermediateFrame renderInlineTextFrameAsImage(IDMLTextFrame textFrame,
                                                           List<IDMLCharacterRun.InlineGraphic> graphics) {
        double[] bounds = textFrame.geometricBounds();
        if (bounds == null || bounds.length < 4) return null;

        // TextFrame 크기 계산 (points)
        double widthPts = bounds[3] - bounds[1];  // right - left
        double heightPts = bounds[2] - bounds[0]; // bottom - top
        if (widthPts <= 0 || heightPts <= 0) return null;

        System.err.println("[DEBUG-INLINE-TF] Rendering TextFrame " + textFrame.selfId() +
                " bounds=[" + String.format("%.1f,%.1f,%.1f,%.1f", bounds[0], bounds[1], bounds[2], bounds[3]) +
                "] size=" + String.format("%.1fx%.1f", widthPts, heightPts) + " pts" +
                " with " + graphics.size() + " graphics");

        // 크기 (HWPUNIT)
        long width = CoordinateConverter.pointsToHwpunits(widthPts);
        long height = CoordinateConverter.pointsToHwpunits(heightPts);

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("inline_tf_" + textFrame.selfId());
        iFrame.isInline(true);
        iFrame.width(width);
        iFrame.height(height);
        iFrame.x(0);
        iFrame.y(0);

        // PNG 렌더링 시도
        try {
            IDMLPageRenderer.RenderResult result =
                    inlineGraphicRenderer.renderInlineTextFrameToPng(textFrame, graphics);
            if (result != null && result.pngData() != null && result.pngData().length > 0) {
                iFrame.frameType("image");

                IntermediateImage img = new IntermediateImage();
                img.imageId("inline_tf_" + textFrame.selfId());
                img.format("png");
                img.base64Data(Base64.getEncoder().encodeToString(result.pngData()));
                img.pixelWidth(result.pixelWidth());
                img.pixelHeight(result.pixelHeight());
                img.displayWidth(width);
                img.displayHeight(height);
                iFrame.image(img);

                return iFrame;
            }
        } catch (IOException e) {
            System.err.println("[WARNING] Failed to render inline TextFrame: " + textFrame.selfId());
        }

        return null;
    }

    /**
     * Story의 테이블 셀에서 모든 그래픽을 추출한다 (이중 인라인 처리용).
     * 테이블 셀 내의 TextFrame 참조를 따라가 그래픽을 수집한다.
     * 중첩 테이블도 재귀적으로 탐색한다.
     * 중복 그래픽은 제거된다.
     */
    private List<IDMLCharacterRun.InlineGraphic> extractGraphicsFromStoryTables(IDMLStory story) {
        Set<String> visitedStories = new HashSet<>();
        Set<String> seenGraphicIds = new HashSet<>();
        List<IDMLCharacterRun.InlineGraphic> allGraphics = extractGraphicsFromStoryTablesRecursive(story, visitedStories);

        // 중복 그래픽 제거
        List<IDMLCharacterRun.InlineGraphic> uniqueGraphics = new ArrayList<>();
        for (IDMLCharacterRun.InlineGraphic g : allGraphics) {
            if (g.selfId() != null && !seenGraphicIds.contains(g.selfId())) {
                seenGraphicIds.add(g.selfId());
                uniqueGraphics.add(g);
            }
        }
        return uniqueGraphics;
    }

    /**
     * 재귀적으로 Story의 테이블 셀에서 그래픽을 추출한다.
     * visited 집합으로 순환 참조를 방지한다.
     */
    private List<IDMLCharacterRun.InlineGraphic> extractGraphicsFromStoryTablesRecursive(
            IDMLStory story, Set<String> visited) {
        List<IDMLCharacterRun.InlineGraphic> graphics = new ArrayList<>();

        if (story == null || story.selfId() == null) return graphics;

        // 순환 참조 방지
        if (visited.contains(story.selfId())) {
            return graphics;
        }
        visited.add(story.selfId());

        for (IDMLTable table : story.tables()) {
            for (IDMLTableRow row : table.rows()) {
                for (IDMLTableCell cell : row.cells()) {
                    // 셀의 TextFrame 참조에서 그래픽 추출 (재귀적으로)
                    for (String storyRef : cell.textFrameStoryRefs()) {
                        IDMLStory refStory = idmlDoc.getStory(storyRef);
                        if (refStory != null) {
                            // 참조된 Story의 직접 그래픽
                            graphics.addAll(refStory.getAllInlineGraphics());
                            // 참조된 Story의 테이블 내 그래픽 (재귀)
                            graphics.addAll(extractGraphicsFromStoryTablesRecursive(refStory, visited));
                        }
                    }
                    // 셀 단락에서 직접 그래픽 추출
                    for (IDMLParagraph para : cell.paragraphs()) {
                        for (IDMLCharacterRun run : para.characterRuns()) {
                            if (run.inlineGraphics() != null) {
                                graphics.addAll(run.inlineGraphics());
                            }
                        }
                    }
                }
            }
        }

        return graphics;
    }

    /**
     * 인라인 그래픽(Rectangle, Polygon 등)을 IntermediateFrame으로 변환한다.
     * 렌더러가 설정된 경우 PNG 이미지로 렌더링하여 저장한다.
     */
    private IntermediateFrame createInlineGraphicFrame(IDMLCharacterRun.InlineGraphic graphic) {
        // 그룹 내 내장 텍스트 로깅
        if ("group".equals(graphic.type()) && graphic.embeddedText() != null && !graphic.embeddedText().isEmpty()) {
            System.err.println("[INFO-EMBEDDED-TEXT] Group " + graphic.selfId() +
                    " contains embedded text: \"" + graphic.embeddedText() + "\"" +
                    (graphic.embeddedTextFont() != null ? " (font: " + graphic.embeddedTextFont() + ")" : ""));
        }

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("inline_" + graphic.selfId());
        iFrame.isInline(true);

        // 크기 설정 (points → HWPUNIT)
        long width = CoordinateConverter.pointsToHwpunits(graphic.widthPoints());
        long height = CoordinateConverter.pointsToHwpunits(graphic.heightPoints());
        iFrame.width(width);
        iFrame.height(height);

        // 인라인 객체는 상대 좌표 (0, 0) - 텍스트 흐름에 따라 배치됨
        iFrame.x(0);
        iFrame.y(0);

        // 렌더러가 설정된 경우 PNG 이미지로 렌더링
        if (inlineGraphicRenderer != null) {
            try {
                IDMLPageRenderer.RenderResult result = inlineGraphicRenderer.renderInlineGraphicToPng(graphic);
                if (result != null && result.pngData() != null && result.pngData().length > 0) {
                    // 이미지 프레임으로 설정
                    iFrame.frameType("image");

                    IntermediateImage img = new IntermediateImage();
                    img.imageId("inline_graphic_" + graphic.selfId());
                    img.format("png");
                    img.base64Data(Base64.getEncoder().encodeToString(result.pngData()));
                    img.pixelWidth(result.pixelWidth());
                    img.pixelHeight(result.pixelHeight());
                    img.displayWidth(width);
                    img.displayHeight(height);
                    iFrame.image(img);

                    return iFrame;
                }
            } catch (IOException e) {
                System.err.println("[WARNING] Failed to render inline graphic: " + graphic.selfId());
            }
        }

        // 렌더러 없거나 렌더링 실패 시: 인라인 shape은 HWPX에서 지원이 불안정하므로 건너뜀
        // (인라인 벡터 도형을 shape으로 내보내면 파일 손상 발생)
        System.err.println("[WARNING] Skipping inline graphic (PNG rendering failed): " + graphic.selfId() +
                " type=" + graphic.type() + " width=" + graphic.widthPoints() + " height=" + graphic.heightPoints());
        return null;
    }

    /**
     * 인라인 그래픽 마커용 텍스트 런 생성 (하위 호환).
     */
    private IntermediateTextRun createInlineGraphicRun(IDMLCharacterRun.InlineGraphic graphic) {
        IntermediateTextRun iRun = new IntermediateTextRun();
        iRun.text("<인라인객체>");
        iRun.inlineObjectType(graphic.type());
        iRun.inlineObjectId(graphic.selfId());
        return iRun;
    }

    /**
     * IDMLCharacterRun에서 IntermediateTextRun을 생성한다.
     */
    public IntermediateTextRun createTextRun(IDMLCharacterRun run) {
        String content = run.content();
        if (content == null || content.isEmpty()) return null;

        // <Br/> 태그의 \n이 단락 끝에 남아있으면 제거
        // 단락 경계는 IDMLParagraph 레벨에서 이미 처리되므로,
        // 끝의 \n은 HWPX 변환 시 빈 단락을 중복 생성함
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
            if (content.isEmpty()) return null;
        }

        IntermediateTextRun iRun = new IntermediateTextRun();
        iRun.text(content);

        // 문자 스타일 참조
        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null && charStyleRefToId.containsKey(charStyleRef)) {
            iRun.characterStyleRef(charStyleRefToId.get(charStyleRef));
        }

        // 런 레벨 오버라이드
        if (run.fontFamily() != null) {
            iRun.fontFamily(run.fontFamily());
        }
        if (run.fontSize() != null) {
            iRun.fontSizeHwpunits(CoordinateConverter.fontSizeToHeight(run.fontSize()));
        }
        if (run.fillColor() != null) {
            String color = colorResolver.resolve(run.fillColor());
            if (color != null) {
                iRun.textColor(color);
            }
        }
        if (run.fontStyle() != null) {
            String style = run.fontStyle().toLowerCase();
            if (style.contains("bold")) {
                iRun.bold(true);
            }
            if (style.contains("italic") || style.contains("oblique")) {
                iRun.italic(true);
            }
        }
        // 런 레벨 자간 (CharacterStyleRange의 Tracking 속성)
        // 기본값: -10% (빽빽하게 표시)
        short letterSpacing = -10;
        if (run.tracking() != null) {
            letterSpacing = (short) Math.round(run.tracking() / 10.0);
            letterSpacing = (short) Math.max(-50, Math.min(50, letterSpacing));
        }
        iRun.letterSpacing(letterSpacing);

        return iRun;
    }

    /**
     * 수식과 텍스트가 혼합된 단락을 변환한다.
     */
    private void convertMixedParagraph(IDMLParagraph para, IntermediateParagraph iPara,
                                       List<IDMLEquationExtractor.ExtractedEquation> equations) {
        int eqIdx = 0;
        boolean inNPRun = false;

        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.isNPFont()) {
                if (!inNPRun) {
                    // NP 런 연속 구간 시작 → 수식 삽입
                    inNPRun = true;
                    if (eqIdx < equations.size()) {
                        iPara.addEquation(new IntermediateEquation(
                                equations.get(eqIdx).hwpScript, "NP_FONT"));
                        eqIdx++;
                    }
                }
                // NP 런 내용은 수식에 포함되므로 건너뜀
            } else {
                inNPRun = false;

                // 텍스트 런을 먼저 추가 (인라인 그래픽은 텍스트 뒤에 위치)
                IntermediateTextRun iRun = createTextRun(run);
                if (iRun != null) {
                    if (iRun.letterSpacing() == null && iPara.inlineLetterSpacing() != null) {
                        iRun.letterSpacing(iPara.inlineLetterSpacing());
                    }
                    iPara.addRun(iRun);
                }

                // 인라인 객체(TextFrame) 처리
                if (run.inlineFrames() != null && !run.inlineFrames().isEmpty()) {
                    for (IDMLTextFrame inlineFrame : run.inlineFrames()) {
                        processInlineTextFrame(inlineFrame, iPara);
                    }
                }

                // 인라인 그래픽(Rectangle, Polygon 등) 처리
                if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) {
                    for (IDMLCharacterRun.InlineGraphic graphic : run.inlineGraphics()) {
                        IntermediateFrame inlineFrame = createInlineGraphicFrame(graphic);
                        if (inlineFrame != null) {
                            iPara.addInlineFrame(inlineFrame);
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // 연결된 텍스트 프레임 체인 처리
    // ========================================================================

    /**
     * 연결된 프레임 체인의 텍스트 분할을 준비하고 캐시에 저장한다.
     */
    public void prepareLinkedFrameChain(IDMLTextFrame firstFrame, IDMLSpread spread, IDMLStory story) {
        String storyId = story.selfId();
        if (linkedFrameTextCache.containsKey(storyId)) {
            return;  // 이미 처리됨
        }

        // 1. 체인 수집
        List<IDMLTextFrame> frameChain = collectLinkedFrameChain(firstFrame, spread);

        // 2. 전체 Story 텍스트 추출
        StringBuilder fullText = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!run.isNPFont() && run.content() != null) {
                    fullText.append(run.content());
                }
            }
            fullText.append("\n");
        }
        String storyText = fullText.toString().trim();

        // 3. 프레임 크기 정보 수집
        List<TextFitter.FrameInfo> frameInfos = new ArrayList<>();
        for (IDMLTextFrame tf : frameChain) {
            frameInfos.add(TextFitter.FrameInfo.fromPoints(tf.widthPoints(), tf.heightPoints()));
        }

        // 4. 텍스트 분할
        double defaultFontSize = 10.0;
        double lineSpacingRatio = 1.6;
        List<String> splitTexts = TextFitter.fitText(storyText, frameInfos, defaultFontSize, lineSpacingRatio);

        System.err.println("[INFO] 연결된 프레임 " + frameChain.size() + "개의 텍스트 분할 준비 (Story: " + storyId + ")");
        for (int i = 0; i < splitTexts.size(); i++) {
            String text = splitTexts.get(i);
            System.err.println("[INFO]   프레임 " + (i + 1) + " (" + frameChain.get(i).selfId() + "): " + text.length() + "자");
        }

        // 5. 캐시에 저장
        linkedFrameTextCache.put(storyId, splitTexts);
        for (int i = 0; i < frameChain.size(); i++) {
            linkedFrameIndexCache.put(frameChain.get(i).selfId(), i);
        }
    }

    /**
     * 캐시된 텍스트를 사용하여 연결된 프레임의 IntermediateFrame을 생성한다.
     */
    public IntermediateFrame createLinkedFrameForPage(IDMLTextFrame tf, String storyId,
                                                       IDMLStory story, IDMLPage page, int zOrder) {
        Integer frameIndex = linkedFrameIndexCache.get(tf.selfId());
        List<String> splitTexts = linkedFrameTextCache.get(storyId);

        if (frameIndex == null || splitTexts == null) {
            return null;
        }

        String frameText = (frameIndex < splitTexts.size()) ? splitTexts.get(frameIndex) : "";

        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("frame_" + tf.selfId());
        iFrame.frameType("text");

        // 프레임 좌표 계산 (해당 페이지 기준)
        setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        iFrame.zOrder(zOrder);

        // 컬럼 정보 설정
        setColumnInfo(iFrame, tf);

        // 텍스트 방향 설정 (세로쓰기 여부)
        if (story != null) {
            iFrame.verticalText(story.isVertical());
        }

        // 단락 생성 (줄바꿈으로 분리하여 각 단락에 스타일 적용)
        if (!frameText.isEmpty()) {
            // 기본 단락 스타일 ID 결정
            String defaultParaStyleId = null;
            if (story != null && !story.paragraphs().isEmpty()) {
                String paraStyleRef = story.paragraphs().get(0).appliedParagraphStyle();
                if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                    defaultParaStyleId = paraStyleRefToId.get(paraStyleRef);
                }
            }

            // 줄바꿈으로 분리하여 각각 단락으로 생성
            String[] lines = frameText.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String lineText = lines[i];
                // 마지막 빈 줄은 건너뛰기
                if (i == lines.length - 1 && lineText.isEmpty()) {
                    continue;
                }

                IntermediateParagraph iPara = new IntermediateParagraph();

                // 단락 스타일 적용
                if (defaultParaStyleId != null) {
                    iPara.paragraphStyleRef(defaultParaStyleId);
                }

                if (!lineText.isEmpty()) {
                    IntermediateTextRun iRun = new IntermediateTextRun();
                    iRun.text(lineText);
                    iPara.addRun(iRun);
                }
                iFrame.addParagraph(iPara);
            }
        }

        return iFrame;
    }

    /**
     * 연결된 텍스트 프레임 체인을 수집한다.
     */
    public List<IDMLTextFrame> collectLinkedFrameChain(IDMLTextFrame firstFrame, IDMLSpread spread) {
        List<IDMLTextFrame> chain = new ArrayList<>();
        chain.add(firstFrame);

        IDMLTextFrame current = firstFrame;
        while (current.nextTextFrame() != null
                && !current.nextTextFrame().isEmpty()
                && !"n".equals(current.nextTextFrame())) {
            // 먼저 현재 스프레드에서 검색
            IDMLTextFrame next = spread.findTextFrameById(current.nextTextFrame());
            // 없으면 전체 문서에서 검색
            if (next == null) {
                next = findTextFrameInDocument(current.nextTextFrame());
            }
            if (next == null) break;
            chain.add(next);
            current = next;
        }

        return chain;
    }

    /**
     * 전체 문서에서 TextFrame을 ID로 찾는다.
     */
    private IDMLTextFrame findTextFrameInDocument(String frameId) {
        if (frameId == null || "n".equals(frameId)) return null;
        for (IDMLSpread s : idmlDoc.spreads()) {
            IDMLTextFrame tf = s.findTextFrameById(frameId);
            if (tf != null) return tf;
        }
        return null;
    }

    /**
     * 연결된 텍스트 프레임 체인의 텍스트를 분할하여 여러 IntermediateFrame을 생성한다.
     */
    public List<IntermediateFrame> convertLinkedTextFrames(List<IDMLTextFrame> frameChain,
                                                            IDMLStory story,
                                                            IDMLPage page,
                                                            int startZOrder,
                                                            Map<String, List<IDMLEquationExtractor.ExtractedEquation>> storyEquations) {
        List<IntermediateFrame> result = new ArrayList<>();

        // 1. 전체 Story 텍스트 추출
        StringBuilder fullText = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!run.isNPFont() && run.content() != null) {
                    fullText.append(run.content());
                }
            }
            fullText.append("\n");
        }
        String storyText = fullText.toString().trim();

        // 2. 프레임 크기 정보 수집
        List<TextFitter.FrameInfo> frameInfos = new ArrayList<>();
        for (IDMLTextFrame tf : frameChain) {
            frameInfos.add(TextFitter.FrameInfo.fromPoints(tf.widthPoints(), tf.heightPoints()));
        }

        // 3. 텍스트 분할
        double defaultFontSize = 10.0;
        double lineSpacingRatio = 1.6;
        List<String> splitTexts = TextFitter.fitText(storyText, frameInfos, defaultFontSize, lineSpacingRatio);

        System.err.println("[INFO] 연결된 프레임 " + frameChain.size() + "개를 텍스트 분할 방식으로 변환");
        for (int i = 0; i < splitTexts.size(); i++) {
            String text = splitTexts.get(i);
            System.err.println("[INFO]   프레임 " + (i + 1) + ": " + text.length() + "자"
                    + (text.length() > 30 ? " - \"" + text.substring(0, 30) + "...\"" : ""));
        }

        // 4. 각 프레임별로 IntermediateFrame 생성
        for (int i = 0; i < frameChain.size(); i++) {
            IDMLTextFrame tf = frameChain.get(i);
            String frameText = (i < splitTexts.size()) ? splitTexts.get(i) : "";

            IntermediateFrame iFrame = new IntermediateFrame();
            iFrame.frameId("frame_" + tf.selfId());
            iFrame.frameType("text");

            // 프레임 좌표 계산 (페이지 상대)
            setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            iFrame.zOrder(startZOrder + i);

            // 컬럼 정보 설정
            setColumnInfo(iFrame, tf);

            // 단락 생성 (단순 텍스트)
            if (!frameText.isEmpty()) {
                IntermediateParagraph iPara = new IntermediateParagraph();
                IntermediateTextRun iRun = new IntermediateTextRun();
                iRun.text(frameText);
                iPara.addRun(iRun);
                iFrame.addParagraph(iPara);
            }

            result.add(iFrame);
        }

        return result;
    }

    /**
     * 연결된 텍스트 프레임 체인을 스프레드 좌표계로 변환한다.
     */
    public List<IntermediateFrame> convertLinkedTextFramesForSpread(List<IDMLTextFrame> frameChain,
                                                                     IDMLStory story,
                                                                     List<IDMLPage> pages,
                                                                     double spreadMinX,
                                                                     double spreadMinY,
                                                                     int startZOrder,
                                                                     PageLocator pageLocator) {
        List<IntermediateFrame> result = new ArrayList<>();

        // 1. 전체 Story 텍스트 추출
        StringBuilder fullText = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!run.isNPFont() && run.content() != null) {
                    fullText.append(run.content());
                }
            }
            fullText.append("\n");
        }
        String storyText = fullText.toString().trim();

        // 2. 프레임 크기 정보 수집
        List<TextFitter.FrameInfo> frameInfos = new ArrayList<>();
        for (IDMLTextFrame tf : frameChain) {
            frameInfos.add(TextFitter.FrameInfo.fromPoints(tf.widthPoints(), tf.heightPoints()));
        }

        // 3. 텍스트 분할
        double defaultFontSize = 10.0;
        double lineSpacingRatio = 1.6;
        List<String> splitTexts = TextFitter.fitText(storyText, frameInfos, defaultFontSize, lineSpacingRatio);

        System.err.println("[INFO] 연결된 프레임 " + frameChain.size() + "개를 텍스트 분할 방식으로 변환 (스프레드)");

        // 4. 각 프레임별로 IntermediateFrame 생성 (스프레드 좌표계)
        for (int i = 0; i < frameChain.size(); i++) {
            IDMLTextFrame tf = frameChain.get(i);
            String frameText = (i < splitTexts.size()) ? splitTexts.get(i) : "";

            // 프레임이 속한 페이지 찾기
            IDMLPage targetPage = pageLocator.findPageForTextFrame(tf, pages);
            if (targetPage == null) continue;

            double[] pageTopLeft = pageLocator.getPageTopLeft(targetPage, spreadMinX, spreadMinY);

            IntermediateFrame iFrame = new IntermediateFrame();
            iFrame.frameId("frame_" + tf.selfId());
            iFrame.frameType("text");

            // 프레임 좌표 계산 (페이지 상대 → 스프레드 상대)
            setFramePosition(iFrame, tf.geometricBounds(), tf.itemTransform(),
                    targetPage.geometricBounds(), targetPage.itemTransform());
            iFrame.x(iFrame.x() + CoordinateConverter.pointsToHwpunits(pageTopLeft[0]));
            iFrame.y(iFrame.y() + CoordinateConverter.pointsToHwpunits(pageTopLeft[1]));
            iFrame.zOrder(startZOrder + i);

            // 컬럼 정보 설정
            setColumnInfo(iFrame, tf);

            // 기본 단락 스타일 ID 결정
            String defaultParaStyleId = null;
            if (!story.paragraphs().isEmpty()) {
                String paraStyleRef = story.paragraphs().get(0).appliedParagraphStyle();
                if (paraStyleRef != null && paraStyleRefToId.containsKey(paraStyleRef)) {
                    defaultParaStyleId = paraStyleRefToId.get(paraStyleRef);
                }
            }

            // 단락 생성 (줄바꿈으로 분리)
            if (!frameText.isEmpty()) {
                String[] lines = frameText.split("\n", -1);
                for (int li = 0; li < lines.length; li++) {
                    String lineText = lines[li];
                    if (li == lines.length - 1 && lineText.isEmpty()) {
                        continue;
                    }

                    IntermediateParagraph iPara = new IntermediateParagraph();
                    if (defaultParaStyleId != null) {
                        iPara.paragraphStyleRef(defaultParaStyleId);
                    }

                    if (!lineText.isEmpty()) {
                        IntermediateTextRun iRun = new IntermediateTextRun();
                        iRun.text(lineText);
                        iPara.addRun(iRun);
                    }
                    iFrame.addParagraph(iPara);
                }
            }

            result.add(iFrame);
        }

        return result;
    }

    /**
     * 텍스트 프레임이 연결된 체인의 첫 번째 프레임인지 확인한다.
     */
    public boolean isFirstInLinkedChain(IDMLTextFrame tf) {
        String prev = tf.previousTextFrame();
        return prev == null || prev.isEmpty() || "n".equals(prev);
    }

    /**
     * 텍스트 프레임이 연결된 체인의 일부인지 확인한다.
     */
    public boolean hasLinkedFrames(IDMLTextFrame tf) {
        String next = tf.nextTextFrame();
        return next != null && !next.isEmpty() && !"n".equals(next);
    }

    /**
     * 연결된 프레임 캐시에 해당 프레임이 있는지 확인한다.
     */
    public boolean isLinkedFrameInCache(String frameId) {
        return linkedFrameIndexCache.containsKey(frameId);
    }

    /**
     * 색상에 Tint를 적용한다.
     */
    private String applyTintToColor(String hexColor, double tint) {
        if (hexColor == null || !hexColor.startsWith("#") || hexColor.length() < 7) {
            return hexColor;
        }
        try {
            int r = Integer.parseInt(hexColor.substring(1, 3), 16);
            int g = Integer.parseInt(hexColor.substring(3, 5), 16);
            int b = Integer.parseInt(hexColor.substring(5, 7), 16);
            double factor = tint / 100.0;
            r = (int) Math.round(255 - (255 - r) * factor);
            g = (int) Math.round(255 - (255 - g) * factor);
            b = (int) Math.round(255 - (255 - b) * factor);
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (NumberFormatException e) {
            return hexColor;
        }
    }

    /**
     * 페이지 위치 찾기를 위한 인터페이스.
     */
    public interface PageLocator {
        IDMLPage findPageForTextFrame(IDMLTextFrame tf, List<IDMLPage> pages);
        double[] getPageTopLeft(IDMLPage page, double spreadMinX, double spreadMinY);
    }
}
