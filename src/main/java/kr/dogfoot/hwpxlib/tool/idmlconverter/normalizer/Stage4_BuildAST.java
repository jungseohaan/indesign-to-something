package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.*;

/**
 * Stage 4: 스토리 우선 AST 구축.
 * IDMLDocument + FlattenedObjectPool → ASTDocument.
 */
public class Stage4_BuildAST {

    public static ASTDocument build(FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                     ConvertOptions options, String sourceFileName) {
        System.err.println("[Stage4_BuildAST] Building AST from stories...");

        ASTDocument doc = new ASTDocument();
        doc.sourceFile(sourceFileName);
        doc.sourceFormat("IDML");

        ColorResolver colorResolver = new ColorResolver(idmlDoc);
        Set<String> processedStories = new HashSet<>();

        // 이미지 로더 초기화
        ASTImageLoader imageLoader = options.includeImages()
                ? new ASTImageLoader(idmlDoc, options) : null;

        // 페이지별 섹션 구축
        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                ASTSection section = new ASTSection();
                section.pageNumber(page.pageNumber());

                // 페이지 레이아웃
                ASTPageLayout layout = new ASTPageLayout();
                layout.pageWidth(page.widthHwpunits());
                layout.pageHeight(page.heightHwpunits());
                layout.marginTop(CoordinateConverter.pointsToHwpunits(page.marginTop()));
                layout.marginBottom(CoordinateConverter.pointsToHwpunits(page.marginBottom()));
                layout.marginLeft(CoordinateConverter.pointsToHwpunits(page.marginLeft()));
                layout.marginRight(CoordinateConverter.pointsToHwpunits(page.marginRight()));
                layout.columnCount(page.columnCount());
                layout.columnGutter(CoordinateConverter.pointsToHwpunits(page.columnGutter()));
                section.layout(layout);

                // 페이지의 텍스트 프레임 수집 (위→아래, 왼→오른 정렬)
                List<FlatObject> textFrames = pool.getTextFramesOnPage(page.pageNumber());
                sortByPosition(textFrames, page);

                // 각 텍스트 프레임의 스토리 → AST 블록 변환
                for (FlatObject fo : textFrames) {
                    IDMLTextFrame tf = (IDMLTextFrame) fo.sourceObject();
                    if (tf.isEditorialNote()) continue;

                    String storyId = tf.parentStoryId();
                    if (storyId == null) continue;

                    // 연결 프레임 체인에서 첫 프레임이 아닌 경우
                    // IDML에서 "n"은 "없음"을 의미 (no previous frame)
                    String prevFrame = tf.previousTextFrame();
                    if (prevFrame != null && !prevFrame.isEmpty()
                            && !"n".equals(prevFrame) && !"null".equalsIgnoreCase(prevFrame)) {
                        continue;
                    }

                    if (processedStories.contains(storyId)) continue;
                    processedStories.add(storyId);

                    IDMLStory story = idmlDoc.getStory(storyId);
                    if (story == null || story.isEmpty()) continue;

                    // 텍스트 프레임 블록 생성
                    ASTTextFrameBlock block = createTextFrameBlock(tf, page, fo.zOrder(), colorResolver);

                    // 스토리 → 단락 변환
                    convertStoryToParagraphs(story, block, pool, idmlDoc, colorResolver, imageLoader);

                    // 테이블 처리
                    convertStoryTables(story, section, tf, page, fo.zOrder(), idmlDoc, colorResolver, imageLoader);

                    if (hasContent(block)) {
                        section.addBlock(block);
                    }
                }

                // 페이지의 이미지 프레임 → ASTFigure 블록 변환
                // 같은 이미지가 같은 위치/크기로 중복 배치된 경우 하나만 출력 (PSD 레이어 가시성 미지원 대응)
                if (imageLoader != null) {
                    List<IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);
                    Set<String> processedFrameKeys = new HashSet<>();
                    for (IDMLImageFrame imgFrame : imageFrames) {
                        String dedupKey = buildImageFrameDedupKey(imgFrame);
                        if (dedupKey != null && !processedFrameKeys.add(dedupKey)) {
                            continue;  // 중복 프레임 스킵
                        }
                        ASTFigure figure = createFigureFromImageFrame(imgFrame, page, imageLoader);
                        if (figure != null) {
                            section.addBlock(figure);
                        }
                    }
                }

                // 페이지의 벡터 도형 → ASTFigure (PNG 래스터화) 블록 변환
                if (imageLoader != null) {
                    List<IDMLVectorShape> vectorShapes = spread.getVectorShapesOnPage(page);
                    for (IDMLVectorShape shape : vectorShapes) {
                        ASTFigure figure = createFigureFromVectorShape(shape, page, imageLoader, colorResolver);
                        if (figure != null) {
                            section.addBlock(figure);
                        }
                    }
                }

                doc.addSection(section);
            }
        }

        // 메타데이터: 폰트, 스타일, 색상
        populateMetadata(doc, idmlDoc, colorResolver);

        System.err.println("[Stage4_BuildAST] Built " + doc.sections().size() + " sections.");
        return doc;
    }

    /**
     * 메타데이터 (폰트, 스타일, 색상) 채우기.
     */
    private static void populateMetadata(ASTDocument doc, IDMLDocument idmlDoc, ColorResolver colorResolver) {
        // 폰트
        int fontIdx = 0;
        for (Map.Entry<String, IDMLFontDef> entry : idmlDoc.fonts().entrySet()) {
            ASTFontDef fd = new ASTFontDef();
            fd.fontId(String.valueOf(fontIdx++));
            fd.fontFamily(entry.getValue().fontFamily());
            fd.fontType(entry.getValue().fontType());
            doc.addFont(fd);
        }

        // 단락 스타일
        for (Map.Entry<String, IDMLStyleDef> entry : idmlDoc.paraStyles().entrySet()) {
            IDMLStyleDef s = entry.getValue();
            ASTStyleDef sd = new ASTStyleDef();
            sd.styleId(entry.getKey());
            sd.styleName(s.simpleName());
            sd.basedOnStyleRef(s.basedOn());
            sd.alignment(s.textAlignment());
            sd.fontFamily(s.fontFamily());
            sd.fontStyle(s.fontStyle());
            if (s.fontSize() != null) sd.fontSizeHwpunits((int)(s.fontSize() * 100));
            if (s.fillColor() != null) sd.textColor(colorResolver.resolve(s.fillColor()));
            if (s.firstLineIndent() != null) sd.firstLineIndent((long)(s.firstLineIndent() * 100));
            if (s.leftIndent() != null) sd.leftMargin((long)(s.leftIndent() * 100));
            if (s.rightIndent() != null) sd.rightMargin((long)(s.rightIndent() * 100));
            if (s.spaceBefore() != null) sd.spaceBefore((long)(s.spaceBefore() * 100));
            if (s.spaceAfter() != null) sd.spaceAfter((long)(s.spaceAfter() * 100));
            if (s.tracking() != null) sd.letterSpacing((short) Math.round(s.tracking() / 10.0));
            // leading → lineSpacing
            if (s.leading() != null) {
                sd.lineSpacingType("fixed");
                sd.lineSpacing((int) CoordinateConverter.pointsToHwpunits(s.leading()));
            } else if ("Auto".equals(s.leadingType())) {
                sd.lineSpacingType("percent");
                double autoLeading = s.autoLeading() != null ? s.autoLeading() : 120;
                sd.lineSpacing((int) Math.round(autoLeading));
            }
            doc.addParagraphStyle(sd);
        }

        // 문자 스타일
        for (Map.Entry<String, IDMLStyleDef> entry : idmlDoc.charStyles().entrySet()) {
            IDMLStyleDef s = entry.getValue();
            ASTStyleDef sd = new ASTStyleDef();
            sd.styleId(entry.getKey());
            sd.styleName(s.simpleName());
            sd.basedOnStyleRef(s.basedOn());
            sd.fontFamily(s.fontFamily());
            sd.fontStyle(s.fontStyle());
            if (s.fontSize() != null) sd.fontSizeHwpunits((int)(s.fontSize() * 100));
            if (s.fillColor() != null) sd.textColor(colorResolver.resolve(s.fillColor()));
            if (s.tracking() != null) sd.letterSpacing((short) Math.round(s.tracking() / 10.0));
            doc.addCharacterStyle(sd);
        }

        // 색상
        for (Map.Entry<String, String> entry : idmlDoc.colors().entrySet()) {
            doc.putColor(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 텍스트 프레임 블록 생성.
     */
    private static ASTTextFrameBlock createTextFrameBlock(IDMLTextFrame tf, IDMLPage page, int zOrder, ColorResolver colorResolver) {
        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId(tf.selfId());

        // 페이지 상대 좌표 계산
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());

        block.x(CoordinateConverter.pointsToHwpunits(relPos[0]));
        block.y(CoordinateConverter.pointsToHwpunits(relPos[1]));
        block.width(CoordinateConverter.pointsToHwpunits(w));
        block.height(CoordinateConverter.pointsToHwpunits(h));
        block.zOrder(zOrder);
        block.columnCount(tf.columnCount());
        block.columnGutter(CoordinateConverter.pointsToHwpunits(tf.columnGutter()));

        if (tf.insetSpacing() != null) {
            double[] inset = tf.insetSpacing();
            block.insetTop(CoordinateConverter.pointsToHwpunits(inset[0]));
            block.insetLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
            block.insetBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
            block.insetRight(CoordinateConverter.pointsToHwpunits(inset[3]));
        }

        block.verticalJustification(tf.verticalJustification());
        block.fillColor(tf.fillColor() != null ? colorResolver.resolve(tf.fillColor()) : null);
        block.strokeColor(tf.strokeColor() != null ? colorResolver.resolve(tf.strokeColor()) : null);
        block.strokeWeight(tf.strokeWeight());
        block.strokeType(tf.strokeType());
        block.fillTint(tf.fillTint());
        block.strokeTint(tf.strokeTint());
        block.cornerRadius(tf.cornerRadius());

        return block;
    }

    /**
     * IDMLStory → ASTParagraph 리스트 변환.
     * 교사용프레임(해설) 인라인 텍스트 프레임은 본문 뒤에 배치.
     */
    private static void convertStoryToParagraphs(IDMLStory story, ASTTextFrameBlock block,
                                                   FlattenedObjectPool pool,
                                                   IDMLDocument idmlDoc,
                                                   ColorResolver colorResolver,
                                                   ASTImageLoader imageLoader) {
        // 본문 뒤로 이동할 인라인 프레임 수집
        List<IDMLTextFrame> deferredFrames = new ArrayList<>();

        for (IDMLParagraph idmlPara : story.paragraphs()) {
            // 교사용프레임 등 뒤로 이동할 인라인 프레임 수집
            for (IDMLCharacterRun run : idmlPara.characterRuns()) {
                for (IDMLTextFrame inlineTf : run.inlineFrames()) {
                    if (shouldDeferInlineFrame(inlineTf)) {
                        deferredFrames.add(inlineTf);
                    }
                }
            }
            ASTParagraph astPara = convertParagraph(idmlPara, pool, idmlDoc, colorResolver, imageLoader);
            if (astPara != null) {
                block.addParagraph(astPara);
            }
        }

        // 지연된 프레임의 스토리 내용을 본문 뒤에 추가
        for (IDMLTextFrame deferredTf : deferredFrames) {
            String deferredStoryId = deferredTf.parentStoryId();
            if (deferredStoryId == null) continue;
            IDMLStory deferredStory = idmlDoc.getStory(deferredStoryId);
            if (deferredStory == null) continue;

            for (IDMLParagraph deferredPara : deferredStory.paragraphs()) {
                ASTParagraph astPara = convertParagraph(deferredPara, pool, idmlDoc, colorResolver, imageLoader);
                if (astPara != null) {
                    block.addParagraph(astPara);
                }
            }
        }

        // 마지막 빈 단락 제거 (IDML <Br/> 이후 빈 단락 방지)
        removeTrailingEmptyParagraphs(block.paragraphs());
    }

    /**
     * 인라인 텍스트 프레임을 본문 뒤로 이동해야 하는지 판별.
     * - 교사용프레임: 해설 내용이 문제 앞에 인라인으로 삽입되어 있으므로 뒤로 이동
     * - AnchoredPosition="Anchored": 커스텀 위치 지정된 프레임
     */
    private static boolean shouldDeferInlineFrame(IDMLTextFrame inlineTf) {
        // 교사용프레임 오브젝트 스타일 체크
        String style = inlineTf.appliedObjectStyle();
        if (style != null && style.contains("교사용프레임")) {
            return true;
        }
        // AnchoredPosition="Anchored" 체크
        if ("Anchored".equals(inlineTf.anchoredPosition())) {
            return true;
        }
        return false;
    }

    /**
     * IDMLParagraph → ASTParagraph 변환.
     */
    private static ASTParagraph convertParagraph(IDMLParagraph idmlPara,
                                                   FlattenedObjectPool pool,
                                                   IDMLDocument idmlDoc,
                                                   ColorResolver colorResolver,
                                                   ASTImageLoader imageLoader) {
        ASTParagraph para = new ASTParagraph();

        // 단락 스타일
        String paraStyleRef = idmlPara.appliedParagraphStyle();
        if (paraStyleRef != null) {
            para.paragraphStyleRef(cleanStyleRef(paraStyleRef));
        }

        // 단락 속성
        if (idmlPara.justification() != null) {
            para.alignment(idmlPara.justification());
        }
        if (idmlPara.firstLineIndent() != null && idmlPara.firstLineIndent() != 0) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(idmlPara.firstLineIndent()));
        }
        if (idmlPara.leftIndent() != null && idmlPara.leftIndent() != 0) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(idmlPara.leftIndent()));
        }
        if (idmlPara.rightIndent() != null && idmlPara.rightIndent() != 0) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(idmlPara.rightIndent()));
        }
        if (idmlPara.spaceBefore() != null && idmlPara.spaceBefore() != 0) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(idmlPara.spaceBefore()));
        }
        if (idmlPara.spaceAfter() != null && idmlPara.spaceAfter() != 0) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(idmlPara.spaceAfter()));
        }
        // 줄간격 (leading)
        if (idmlPara.leading() != null) {
            para.lineSpacingType("fixed");
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(idmlPara.leading()));
        }

        // 단락 배경
        if (idmlPara.shadingOn()) {
            para.shadingOn(true);
            String shadingColor = idmlPara.shadingColor();
            if (shadingColor != null) {
                para.shadingColor(colorResolver.resolve(shadingColor));
            }
            para.shadingTint(idmlPara.shadingTint());
        }

        // Character Runs → 인라인 항목
        for (IDMLCharacterRun run : idmlPara.characterRuns()) {
            convertCharacterRun(run, idmlPara, para, pool, idmlDoc, colorResolver, imageLoader);
        }

        // 단락 끝의 trailing lineBreak 제거
        // 인라인 객체(InlineObject)가 뒤에 있어도, 마지막 텍스트런 이후의 break를 제거
        List<ASTInlineItem> items = para.items();
        int lastTextIdx = -1;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                lastTextIdx = i;
                break;
            }
        }
        for (int i = items.size() - 1; i > lastTextIdx; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.BREAK) {
                items.remove(i);
            }
        }

        return para;
    }

    /**
     * IDMLCharacterRun → ASTTextRun + ASTInlineObject + ASTBreak 변환.
     */
    private static void convertCharacterRun(IDMLCharacterRun run, IDMLParagraph parentPara,
                                              ASTParagraph para,
                                              FlattenedObjectPool pool,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver,
                                              ASTImageLoader imageLoader) {
        String text = run.content();
        if (text != null && !text.isEmpty()) {
            // 연속 줄바꿈(\n\n+)을 하나로 머지
            text = text.replaceAll("\n{2,}", "\n");
            // 줄바꿈 분리
            String[] segments = text.split("\n", -1);
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
                }
                String seg = segments[i];
                if (!seg.isEmpty()) {
                    ASTTextRun textRun = createTextRun(run, seg, parentPara, idmlDoc, colorResolver);
                    para.addItem(textRun);
                }
            }
        }

        // 인라인 텍스트 프레임 (Anchored 위치의 프레임은 본문 뒤로 이동하므로 건너뜀)
        for (IDMLTextFrame inlineTf : run.inlineFrames()) {
            if (shouldDeferInlineFrame(inlineTf)) {
                continue;
            }
            ASTInlineObject inlineObj = createInlineObjectFromTextFrame(inlineTf, idmlDoc, colorResolver, imageLoader);
            if (inlineObj != null) {
                para.addItem(inlineObj);
            }
        }

        // 인라인 그래픽
        for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
            ASTInlineObject inlineObj = createInlineObjectFromGraphic(ig, imageLoader);
            if (inlineObj != null) {
                para.addItem(inlineObj);
                // IMAGE로 처리된 Group은 자식 텍스트프레임을 별도 추출하지 않음
                // (이미지와 텍스트 오버레이가 하나의 시각 단위이므로 분리하면 겹침)
                if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
                    continue;
                }
            }
            // 인라인 그래픽 내부의 자식 텍스트프레임 처리 (중첩 Group 포함, 재귀)
            collectChildTextFrames(ig, para, idmlDoc, colorResolver, imageLoader);
        }
    }

    /**
     * InlineGraphic 내부의 TextFrame을 재귀적으로 수집하여 ASTParagraph에 추가.
     * 중첩 Group 구조에서도 모든 TextFrame을 찾아낸다.
     */
    private static void collectChildTextFrames(IDMLCharacterRun.InlineGraphic ig,
                                                 ASTParagraph para,
                                                 IDMLDocument idmlDoc,
                                                 ColorResolver colorResolver,
                                                 ASTImageLoader imageLoader) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            ASTInlineObject childObj = createInlineObjectFromTextFrame(childTf, idmlDoc, colorResolver, imageLoader);
            if (childObj != null) {
                para.addItem(childObj);
            }
        }
        // 중첩 그래픽(Group 등) 내부의 TextFrame도 재귀적으로 처리
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            collectChildTextFrames(childIg, para, idmlDoc, colorResolver, imageLoader);
        }
    }

    /**
     * ASTTextRun 생성.
     * IDML 스타일 상속을 해결하여 fontFamily/fontSize/fillColor 등을 설정.
     *
     * 해결 순서: 런 직접 속성 → 적용된 CharacterStyle → 적용된 ParagraphStyle (basedOn 체인 포함)
     */
    private static ASTTextRun createTextRun(IDMLCharacterRun run, String text,
                                              IDMLParagraph parentPara,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver) {
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(text);

        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null) {
            textRun.characterStyleRef(cleanStyleRef(charStyleRef));
        }

        // 스타일 상속 해결: 런 → CharacterStyle → ParagraphStyle
        String fontFamily = run.fontFamily();
        Double fontSize = run.fontSize();
        String fillColor = run.fillColor();
        String fontStyle = run.fontStyle();
        Double tracking = run.tracking();

        // CharacterStyle에서 빈 속성 채우기
        if (charStyleRef != null) {
            IDMLStyleDef charStyle = resolveStyle(charStyleRef, idmlDoc.charStyles());
            if (charStyle != null) {
                if (fontFamily == null) fontFamily = charStyle.fontFamily();
                if (fontSize == null) fontSize = charStyle.fontSize();
                if (fillColor == null) fillColor = charStyle.fillColor();
                if (fontStyle == null) fontStyle = charStyle.fontStyle();
                if (tracking == null) tracking = charStyle.tracking();
            }
        }

        // ParagraphStyle에서 빈 속성 채우기
        String paraStyleRef = parentPara != null ? parentPara.appliedParagraphStyle() : null;
        if (paraStyleRef != null) {
            IDMLStyleDef paraStyle = resolveStyle(paraStyleRef, idmlDoc.paraStyles());
            if (paraStyle != null) {
                if (fontFamily == null) fontFamily = paraStyle.fontFamily();
                if (fontSize == null) fontSize = paraStyle.fontSize();
                if (fillColor == null) fillColor = paraStyle.fillColor();
                if (fontStyle == null) fontStyle = paraStyle.fontStyle();
                if (tracking == null) tracking = paraStyle.tracking();
            }
        }

        textRun.fontFamily(fontFamily);
        textRun.fontStyle(fontStyle);

        if (fontSize != null) {
            textRun.fontSizeHwpunits((int) (fontSize * 100));
        }

        if (fillColor != null) {
            textRun.textColor(colorResolver.resolve(fillColor));
        }

        if (tracking != null) {
            // IDML tracking: 1/1000 em → HWPX spacing: %
            textRun.letterSpacing((short) Math.round(tracking / 10.0));
        }

        textRun.subscript(run.isSubscript());
        textRun.superscript(run.isSuperscript());

        return textRun;
    }

    /**
     * 스타일 상속 체인(basedOn)을 따라 속성을 해결한다.
     */
    private static IDMLStyleDef resolveStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
        IDMLStyleDef style = findStyle(styleRef, allStyles);
        if (style == null) return null;
        if (style.basedOn() == null || style.basedOn().isEmpty()) return style;

        // 재귀적으로 부모 해결
        IDMLStyleDef parent = resolveStyle(style.basedOn(), allStyles);
        if (parent == null) return style;

        // 병합: 자식 우선, 빈 속성은 부모에서
        IDMLStyleDef merged = new IDMLStyleDef();
        merged.selfRef(style.selfRef());
        merged.name(style.name());
        merged.fontFamily(style.fontFamily() != null ? style.fontFamily() : parent.fontFamily());
        merged.fontSize(style.fontSize() != null ? style.fontSize() : parent.fontSize());
        merged.fillColor(style.fillColor() != null ? style.fillColor() : parent.fillColor());
        merged.fontStyle(style.fontStyle() != null ? style.fontStyle() : parent.fontStyle());
        merged.bold(style.bold() != null ? style.bold() : parent.bold());
        merged.italic(style.italic() != null ? style.italic() : parent.italic());
        merged.tracking(style.tracking() != null ? style.tracking() : parent.tracking());
        merged.leading(style.leading() != null ? style.leading() : parent.leading());
        merged.leadingType(style.leadingType() != null ? style.leadingType() : parent.leadingType());
        merged.autoLeading(style.autoLeading() != null ? style.autoLeading() : parent.autoLeading());
        return merged;
    }

    /**
     * 스타일 맵에서 스타일을 찾는다.
     * IDML의 basedOn 값은 접두사가 없을 수 있으므로 (예: "$ID/[No paragraph style]"),
     * 직접 조회 실패 시 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사를 붙여 재시도.
     */
    private static IDMLStyleDef findStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
        if (styleRef == null) return null;
        IDMLStyleDef style = allStyles.get(styleRef);
        if (style != null) return style;

        // 접두사 붙여서 재시도
        for (String prefix : new String[]{"ParagraphStyle/", "CharacterStyle/"}) {
            style = allStyles.get(prefix + styleRef);
            if (style != null) {
                return style;
            }
        }
        return null;
    }

    /**
     * 인라인 텍스트 프레임 → ASTInlineObject(INLINE_TEXT_FRAME) 변환.
     * 인라인 스토리의 단락을 ASTParagraph로 재귀 변환하여 보존.
     */
    private static ASTInlineObject createInlineObjectFromTextFrame(IDMLTextFrame tf,
                                                                     IDMLDocument idmlDoc,
                                                                     ColorResolver colorResolver,
                                                                     ASTImageLoader imageLoader) {
        if (tf.parentStoryId() == null) return null;

        IDMLStory inlineStory = idmlDoc.getStory(tf.parentStoryId());
        if (inlineStory == null) return null;

        // 텍스트 내용이 있는지 확인
        boolean hasContent = false;
        for (IDMLParagraph para : inlineStory.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (run.content() != null && !run.content().trim().isEmpty()) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) break;
        }
        if (!hasContent && inlineStory.tables().isEmpty()) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        // 인라인 스토리의 단락을 ASTParagraph로 변환
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (IDMLParagraph idmlPara : inlineStory.paragraphs()) {
            ASTParagraph astPara = convertParagraph(idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader);
            if (astPara != null && !astPara.items().isEmpty()) {
                obj.addParagraph(astPara);
            }
        }

        // 인라인 스토리의 테이블을 ASTTable로 변환
        for (IDMLTable idmlTable : inlineStory.tables()) {
            ASTTable table = convertInlineTable(idmlTable, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                obj.addInlineTable(table);
            }
        }

        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        return (hasParagraphs || hasTables) ? obj : null;
    }

    /**
     * 인라인 스토리 내 테이블 → ASTTable 변환 (위치 정보 없이).
     */
    private static ASTTable convertInlineTable(IDMLTable idmlTable,
                                                 IDMLDocument idmlDoc,
                                                 ColorResolver colorResolver,
                                                 ASTImageLoader imageLoader) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            int colIdx = 0;
            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
                colIdx++;
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        return table;
    }

    /**
     * 인라인 그래픽 → ASTInlineObject 변환.
     * 이미지 링크가 있으면 이미지 데이터를 로드한다.
     */
    private static ASTInlineObject createInlineObjectFromGraphic(IDMLCharacterRun.InlineGraphic ig,
                                                                    ASTImageLoader imageLoader) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.sourceId(ig.selfId());

        long w = CoordinateConverter.pointsToHwpunits(ig.widthPoints());
        long h = CoordinateConverter.pointsToHwpunits(ig.heightPoints());
        obj.width(w);
        obj.height(h);

        // 이미지 링크가 있거나, 자식에 이미지가 있으면 IMAGE로 처리
        IDMLCharacterRun.InlineGraphic imageFrame = findImageFrame(ig);
        boolean hasDirectImage = ig.hasImage();
        boolean hasChildImage = imageFrame != null && imageFrame.hasImage();

        if ((hasDirectImage || hasChildImage) && imageLoader != null) {
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);

            // 이미지 표시 크기 계산: 계층의 스케일 팩터를 누적 적용
            IDMLCharacterRun.InlineGraphic sizeFrame = hasChildImage ? imageFrame : ig;
            double imgW = sizeFrame.widthPoints();
            double imgH = sizeFrame.heightPoints();
            double cumulativeScale = computeCumulativeScale(ig, hasChildImage ? imageFrame : null);
            imgW *= cumulativeScale;
            imgH *= cumulativeScale;

            long displayW = CoordinateConverter.pointsToHwpunits(imgW);
            long displayH = CoordinateConverter.pointsToHwpunits(imgH);
            if (displayW > 0 && displayH > 0) {
                obj.width(displayW);
                obj.height(displayH);
            }

            // 프레임 bounds (points) — 클리핑용, 실제 로컬 좌표 사용
            IDMLCharacterRun.InlineGraphic frameForBounds = hasChildImage ? imageFrame : ig;
            double[] frameBounds = frameForBounds.geometricBounds();
            if (frameBounds == null) {
                double frameW = frameForBounds.widthPoints();
                double frameH = frameForBounds.heightPoints();
                if (frameW > 0 && frameH > 0) {
                    frameBounds = new double[]{0, 0, frameH, frameW};
                }
            }

            // 이미지 transform/graphicBounds는 이미지 프레임의 것을 우선 사용
            IDMLCharacterRun.InlineGraphic imgSrc = hasChildImage ? imageFrame : ig;
            double[] imgTransform = imgSrc.imageTransform() != null
                    ? imgSrc.imageTransform() : ig.imageTransform();
            double[] graphicBounds = imgSrc.graphicBounds() != null
                    ? imgSrc.graphicBounds() : ig.graphicBounds();
            String linkURI = imgSrc.linkResourceURI() != null
                    ? imgSrc.linkResourceURI() : ig.linkResourceURI();

            ASTImageLoader.ImageResult result = imageLoader.loadImage(
                    linkURI, displayW, displayH,
                    imgTransform, frameBounds, graphicBounds);

            if (result != null) {
                obj.imageData(result.imageData);
                obj.imageFormat(result.format);
                obj.pixelWidth(result.pixelWidth);
                obj.pixelHeight(result.pixelHeight);
            }
        } else {
            obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        }

        return obj;
    }

    /**
     * 인라인 그래픽 계층에서 이미지를 직접 포함하는 자식 프레임을 찾는다.
     * 부모 Group의 linkResourceURI와 일치하는 자식을 우선 선택한다.
     * (collectInlineImageLink가 Image→PDF→EPS 순서로 검색하므로,
     * Group의 URI가 주요 콘텐츠 이미지를 가리킨다.)
     * 일치하는 자식이 없으면 가장 큰 이미지 자식을 선택한다.
     */
    private static IDMLCharacterRun.InlineGraphic findImageFrame(IDMLCharacterRun.InlineGraphic ig) {
        // 1. 부모의 linkResourceURI와 일치하는 자식을 우선 검색
        String parentURI = ig.linkResourceURI();
        if (parentURI != null && !parentURI.isEmpty()) {
            IDMLCharacterRun.InlineGraphic match = findImageFrameByURI(ig, parentURI);
            if (match != null) return match;
        }
        // 2. 일치하는 자식이 없으면 가장 큰 이미지 자식 선택
        return findLargestImageFrame(ig);
    }

    private static IDMLCharacterRun.InlineGraphic findImageFrameByURI(
            IDMLCharacterRun.InlineGraphic ig, String targetURI) {
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()
                    && targetURI.equals(child.linkResourceURI())) {
                return child;
            }
            IDMLCharacterRun.InlineGraphic found = findImageFrameByURI(child, targetURI);
            if (found != null) return found;
        }
        return null;
    }

    private static IDMLCharacterRun.InlineGraphic findLargestImageFrame(IDMLCharacterRun.InlineGraphic ig) {
        IDMLCharacterRun.InlineGraphic best = null;
        double bestArea = 0;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()) {
                double area = child.widthPoints() * child.heightPoints();
                if (area > bestArea) {
                    bestArea = area;
                    best = child;
                }
            }
            IDMLCharacterRun.InlineGraphic found = findLargestImageFrame(child);
            if (found != null) {
                double area = found.widthPoints() * found.heightPoints();
                if (area > bestArea) {
                    bestArea = area;
                    best = found;
                }
            }
        }
        return best;
    }

    /**
     * 인라인 그래픽 계층에서 루트부터 이미지 프레임까지의 누적 스케일 팩터를 계산한다.
     * 각 계층의 itemTransform[0] (scaleX)을 곱해서 반환한다 (scaleX ≈ scaleY 가정).
     */
    private static double computeCumulativeScale(IDMLCharacterRun.InlineGraphic root,
                                                   IDMLCharacterRun.InlineGraphic target) {
        if (target == null) {
            // target이 없으면 루트 자체의 스케일만
            return extractScale(root);
        }
        // 루트 → target 경로의 스케일을 누적
        double scale = extractScale(root);
        scale *= computeScaleToTarget(root, target);
        return scale;
    }

    private static double computeScaleToTarget(IDMLCharacterRun.InlineGraphic current,
                                                 IDMLCharacterRun.InlineGraphic target) {
        for (IDMLCharacterRun.InlineGraphic child : current.childGraphics()) {
            if (child == target) {
                return extractScale(child);
            }
            if (containsTarget(child, target)) {
                return extractScale(child) * computeScaleToTarget(child, target);
            }
        }
        return 1.0;
    }

    private static boolean containsTarget(IDMLCharacterRun.InlineGraphic node,
                                            IDMLCharacterRun.InlineGraphic target) {
        if (node == target) return true;
        for (IDMLCharacterRun.InlineGraphic child : node.childGraphics()) {
            if (containsTarget(child, target)) return true;
        }
        return false;
    }

    private static double extractScale(IDMLCharacterRun.InlineGraphic ig) {
        if (ig.itemTransform() == null) return 1.0;
        double scaleX = Math.abs(ig.itemTransform()[0]);
        return (scaleX > 0.01 && scaleX != 1.0) ? scaleX : 1.0;
    }

    /**
     * 스토리의 테이블 → ASTTable 변환.
     */
    private static void convertStoryTables(IDMLStory story, ASTSection section,
                                             IDMLTextFrame tf, IDMLPage page,
                                             int zOrder, IDMLDocument idmlDoc,
                                             ColorResolver colorResolver,
                                             ASTImageLoader imageLoader) {
        for (IDMLTable idmlTable : story.tables()) {
            ASTTable table = convertTable(idmlTable, tf, page, zOrder, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                section.addBlock(table);
            }
        }
    }

    /**
     * IDMLTable → ASTTable 변환.
     */
    private static ASTTable convertTable(IDMLTable idmlTable, IDMLTextFrame tf,
                                           IDMLPage page, int zOrder,
                                           IDMLDocument idmlDoc, ColorResolver colorResolver,
                                           ASTImageLoader imageLoader) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());
        table.zOrder(zOrder);

        // 테이블 위치 (텍스트 프레임 기준)
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        table.x(CoordinateConverter.pointsToHwpunits(relPos[0]));
        table.y(CoordinateConverter.pointsToHwpunits(relPos[1]));

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            // 셀 변환
            int colIdx = 0;
            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
                colIdx++;
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산 (columnWidths + rowHeights 기반)
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                // 셀 너비 = 시작 컬럼부터 colSpan만큼 합산
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                // 셀 높이 = 시작 행부터 rowSpan만큼 합산
                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        return table;
    }

    /**
     * IDMLTableCell → ASTTableCell 변환 (미니 문서).
     */
    private static ASTTableCell convertTableCell(IDMLTableCell idmlCell,
                                                   int rowIdx, int colIdx,
                                                   IDMLDocument idmlDoc,
                                                   ColorResolver colorResolver,
                                                   ASTImageLoader imageLoader) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        // 셀 스타일
        if (idmlCell.fillColor() != null) {
            cell.fillColor(colorResolver.resolve(idmlCell.fillColor()));
        }
        cell.verticalAlign(idmlCell.verticalJustification());

        // 셀 여백
        cell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        cell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        cell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        cell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 셀 테두리 (IDMLTableCell.CellBorder → ASTTableCell.CellBorder)
        cell.topBorder(convertCellBorder(idmlCell.topBorder(), colorResolver));
        cell.bottomBorder(convertCellBorder(idmlCell.bottomBorder(), colorResolver));
        cell.leftBorder(convertCellBorder(idmlCell.leftBorder(), colorResolver));
        cell.rightBorder(convertCellBorder(idmlCell.rightBorder(), colorResolver));

        // 대각선
        cell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        cell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());

        // 셀 내용 → 미니 문서 (재귀)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool(); // 셀 내 인라인은 별도 처리
        for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
            ASTParagraph astPara = convertParagraph(cellPara, emptyPool, idmlDoc, colorResolver, imageLoader);
            if (astPara != null) {
                cell.addParagraph(astPara);
            }
        }

        // 마지막 빈 단락 제거
        removeTrailingEmptyParagraphs(cell.paragraphs());

        return cell;
    }

    /**
     * IDMLTableCell.CellBorder → ASTTableCell.CellBorder 변환.
     */
    private static ASTTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder src,
                                                               ColorResolver colorResolver) {
        if (src == null || src.strokeWeight <= 0) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            border.color(colorResolver.resolve(src.strokeColor));
        }
        return border;
    }

    /**
     * 스타일 참조에서 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사 제거.
     */
    private static String cleanStyleRef(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("ParagraphStyle/")) {
            return ref.substring("ParagraphStyle/".length());
        }
        if (ref.startsWith("CharacterStyle/")) {
            return ref.substring("CharacterStyle/".length());
        }
        return ref;
    }

    /**
     * FlatObject 리스트를 페이지 내 위치 순서로 정렬 (위→아래, 왼→오른).
     */
    private static void sortByPosition(List<FlatObject> objects, IDMLPage page) {
        objects.sort((a, b) -> {
            double[] aPos = IDMLGeometry.pageRelativePosition(
                    a.geometricBounds(), a.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            double[] bPos = IDMLGeometry.pageRelativePosition(
                    b.geometricBounds(), b.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            // Y 먼저 (위→아래), 같으면 X (왼→오른)
            int cmp = Double.compare(aPos[1], bPos[1]);
            if (cmp != 0) return cmp;
            return Double.compare(aPos[0], bPos[0]);
        });
    }

    /**
     * IDMLImageFrame → ASTFigure 변환 (플로팅 이미지).
     */
    private static ASTFigure createFigureFromImageFrame(IDMLImageFrame imgFrame,
                                                          IDMLPage page,
                                                          ASTImageLoader imageLoader) {
        double[] t = imgFrame.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001);

        long wHwp, hHwp, xHwp, yHwp;

        if (hasRotOrFlip) {
            // === 회전/반전 사전 렌더링 경로 (벡터 도형과 동일) ===
            double w = IDMLGeometry.transformedWidth(imgFrame.geometricBounds(), imgFrame.itemTransform());
            double h = IDMLGeometry.transformedHeight(imgFrame.geometricBounds(), imgFrame.itemTransform());
            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);

            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    imgFrame.geometricBounds(), imgFrame.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            xHwp = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            yHwp = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);
        } else {
            // === 기존 비회전 경로 ===
            double w = IDMLGeometry.scaledWidth(imgFrame.geometricBounds(), imgFrame.itemTransform());
            double h = IDMLGeometry.scaledHeight(imgFrame.geometricBounds(), imgFrame.itemTransform());

            double[] relCenter = IDMLGeometry.pageRelativeCenter(
                    imgFrame.geometricBounds(), imgFrame.itemTransform(),
                    page.geometricBounds(), page.itemTransform());

            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);
            xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
            yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
        }

        if (wHwp <= 0 || hHwp <= 0) return null;

        // 프레임 bounds (points) — 클리핑용
        double[] frameBounds = imgFrame.geometricBounds();

        ASTImageLoader.ImageResult result = imageLoader.loadImage(
                imgFrame.linkResourceURI(), wHwp, hHwp,
                imgFrame.imageTransform(), frameBounds, imgFrame.graphicBounds());

        if (result == null || result.imageData == null) return null;

        // 회전/반전이 있으면 이미지를 픽셀 레벨에서 회전
        if (hasRotOrFlip) {
            ASTImageLoader.ImageResult rotated =
                    ASTImageLoader.preRenderRotation(result.imageData, imgFrame.itemTransform());
            if (rotated != null) {
                result = rotated;
            }
        }

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.IMAGE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(imgFrame.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.imagePath(imgFrame.linkResourceURI());

        if (!hasRotOrFlip) {
            // 비회전 경로에서만 기존 flip 처리
            if (IDMLGeometry.hasFlip(imgFrame.itemTransform())) {
                double rotation = IDMLGeometry.extractRotation(imgFrame.itemTransform());
                if (Math.abs(Math.abs(rotation) - 180) < 0.5) {
                    result.imageData = ASTImageLoader.flipVertically(result.imageData);
                    figure.imageData(result.imageData);
                } else if (Math.abs(rotation) < 0.5) {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                }
            }
        }
        // hasRotOrFlip: 회전/반전이 preRenderRotation에서 이미 처리됨

        return figure;
    }

    /**
     * IDMLVectorShape → ASTFigure 변환 (플로팅 벡터 도형 → PNG 래스터화).
     */
    private static ASTFigure createFigureFromVectorShape(IDMLVectorShape shape,
                                                          IDMLPage page,
                                                          ASTImageLoader imageLoader,
                                                          ColorResolver colorResolver) {
        // 채우기/선 색상 해석
        IDMLVectorShape renderTarget = shape.hasClippedChild() ? shape.clippedChild() : shape;
        String fillHex = resolveColorHex(renderTarget.fillColor(), colorResolver);
        String strokeHex = resolveColorHex(renderTarget.strokeColor(), colorResolver);

        // 보이지 않는 도형 스킵
        if (fillHex == null && strokeHex == null) return null;

        double[] t = shape.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001);

        long wHwp, hHwp, xHwp, yHwp;
        ASTImageLoader.ImageResult result;

        if (hasRotOrFlip) {
            // === 회전/반전 사전 렌더링 경로 ===
            // 바운딩 박스 크기 (회전 확장 포함)
            double w = IDMLGeometry.transformedWidth(shape.geometricBounds(), shape.itemTransform());
            double h = IDMLGeometry.transformedHeight(shape.geometricBounds(), shape.itemTransform());
            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);

            // 바운딩 박스 top-left 위치 (텍스트 프레임과 동일 방식)
            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    shape.geometricBounds(), shape.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            xHwp = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            yHwp = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);

            // 선 도형(GraphicLine 등)은 한 축이 0일 수 있음: stroke weight를 최소 크기로 사용
            if (wHwp <= 0 || hHwp <= 0) {
                if (shape.hasStroke() && shape.strokeWeight() > 0) {
                    long minDim = CoordinateConverter.pointsToHwpunits(shape.strokeWeight());
                    if (minDim < 100) minDim = 100;
                    if (wHwp <= 0) wHwp = minDim;
                    if (hHwp <= 0) hHwp = minDim;
                } else {
                    return null;
                }
            }

            // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

            // 회전 사전 렌더링된 래스터
            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex, shape.itemTransform());
        } else {
            // === 기존 비회전 경로 ===
            double w = IDMLGeometry.scaledWidth(shape.geometricBounds(), shape.itemTransform());
            double h = IDMLGeometry.scaledHeight(shape.geometricBounds(), shape.itemTransform());

            double[] relCenter = IDMLGeometry.pageRelativeCenter(
                    shape.geometricBounds(), shape.itemTransform(),
                    page.geometricBounds(), page.itemTransform());

            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);
            xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
            yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;

            if (wHwp <= 0 || hHwp <= 0) {
                if (shape.hasStroke() && shape.strokeWeight() > 0) {
                    long minDim = CoordinateConverter.pointsToHwpunits(shape.strokeWeight());
                    if (minDim < 100) minDim = 100;
                    if (wHwp <= 0) wHwp = minDim;
                    if (hHwp <= 0) hHwp = minDim;
                    xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
                    yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
                } else {
                    return null;
                }
            }

            // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
        }

        if (result == null || result.imageData == null) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(shape.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);

        if (!hasRotOrFlip) {
            // 비회전 경로에서만 HWPX rotation/flip 처리
            if (IDMLGeometry.hasFlip(shape.itemTransform())) {
                double rotation = IDMLGeometry.extractRotation(shape.itemTransform());
                if (Math.abs(Math.abs(rotation) - 180) < 0.5) {
                    result.imageData = ASTImageLoader.flipVertically(result.imageData);
                    figure.imageData(result.imageData);
                } else if (Math.abs(rotation) < 0.5) {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                } else {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                    figure.rotationAngle(rotation);
                }
            } else {
                double rotation = IDMLGeometry.extractRotation(shape.itemTransform());
                if (Math.abs(rotation) > 0.1) {
                    figure.rotationAngle(rotation);
                }
            }
        }
        // hasRotOrFlip인 경우: 회전/반전이 이미 래스터에 포함됨 → rotationAngle 기본값 0

        return figure;
    }

    private static String resolveColorHex(String colorRef, ColorResolver colorResolver) {
        if (colorRef == null || "None".equals(colorRef) || colorRef.contains("[None]")) return null;
        String hex = colorResolver.resolve(colorRef);
        return (hex != null && !hex.isEmpty()) ? hex : null;
    }

    /**
     * 이미지 프레임 중복 제거 키 생성.
     * 같은 이미지 URI + 같은 위치(tx, ty) + 같은 프레임 크기인 경우 같은 키를 반환.
     * PSD 레이어 가시성이 다른 중복 배치를 하나로 합치기 위함.
     */
    private static String buildImageFrameDedupKey(IDMLImageFrame frame) {
        String uri = frame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        double[] t = frame.itemTransform();
        double[] b = frame.geometricBounds();
        if (t == null || b == null) return null;

        // 위치(tx, ty)와 프레임 크기를 0.1pt 정밀도로 반올림하여 키 생성
        long tx = Math.round(t[4] * 10);
        long ty = Math.round(t[5] * 10);
        long w = Math.round((b[3] - b[1]) * 10);
        long h = Math.round((b[2] - b[0]) * 10);

        return uri + "|" + tx + "," + ty + "|" + w + "x" + h;
    }

    /**
     * 텍스트 프레임 블록에 실제 콘텐츠(텍스트 또는 인라인 객체)가 있는지 확인.
     * 스타일만 있고 텍스트가 없는 빈 단락만 포함된 블록은 제거 대상.
     */
    private static boolean hasContent(ASTTextFrameBlock block) {
        if (block.paragraphs().isEmpty()) return false;
        for (ASTParagraph para : block.paragraphs()) {
            if (!para.items().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 마지막 빈 단락 제거.
     * IDML의 마지막 &lt;Br/&gt; 이후에 생기는 빈 단락을 제거하여
     * HWPX 글상자에 불필요한 줄바꿈이 추가되지 않도록 한다.
     */
    private static void removeTrailingEmptyParagraphs(List<ASTParagraph> paragraphs) {
        while (paragraphs.size() > 1) {
            ASTParagraph last = paragraphs.get(paragraphs.size() - 1);
            if (isEffectivelyEmpty(last)) {
                paragraphs.remove(paragraphs.size() - 1);
            } else {
                break;
            }
        }
    }

    private static boolean isEffectivelyEmpty(ASTParagraph para) {
        if (para.items().isEmpty()) return true;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return false;
            } else if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                return false;
            }
            // BREAK만 있는 단락도 빈 단락으로 취급
        }
        return true;
    }
}
