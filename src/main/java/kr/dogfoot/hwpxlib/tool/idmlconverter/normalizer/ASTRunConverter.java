package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

import java.util.List;
import java.util.Set;

/**
 * 문자런 변환 + FFFC 기반 인라인 항목 인터리빙.
 * ASTStoryConverter에서 분리됨.
 */
class ASTRunConverter {

    private static final java.util.regex.Pattern R_N_PAR = java.util.regex.Pattern.compile("r(\\d+)par");

    /**
     * 원문자(r1par → ①) 변환.
     * 수식 그룹화 전에 호출해야 한다 (원문자는 수식이 아닌 일반 텍스트로 처리).
     */
    static void convertCircledNumberRuns(List<IDMLCharacterRun> runs) {
        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text == null || !text.contains("par")) continue;
            if (!run.isBTFont() && !run.grepMathFont()) continue;

            java.util.regex.Matcher m = R_N_PAR.matcher(text);
            if (!m.find()) continue;

            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) {
                int num = Integer.parseInt(m.group(1));
                String repl = (num >= 1 && num <= 20)
                        ? String.valueOf((char) (0x2460 + num - 1))
                        : "(" + num + ")";
                m.appendReplacement(sb, repl);
            }
            m.appendTail(sb);
            run.content(sb.toString());

            // 원문자만 남았으면 수식폰트 해제 (라틴 문자/숫자가 없으면)
            String newText = sb.toString();
            boolean hasLatinOrDigit = false;
            for (int i = 0; i < newText.length(); i++) {
                char c = newText.charAt(i);
                if (Character.isLetterOrDigit(c)) { hasLatinOrDigit = true; break; }
            }
            if (!hasLatinOrDigit) {
                run.grepMathFont(false);
            }
        }
    }

    /**
     * IDMLCharacterRun → ASTTextRun + ASTInlineObject + ASTBreak 변환.
     * 인라인 앵커(InlineAnchor)를 사용하여 TextFrame과 InlineGraphic을
     * 문서 순서(FFFC 위치)에 맞게 인터리빙한다.
     */
    static void convertCharacterRun(IDMLCharacterRun run, IDMLParagraph parentPara,
                                     ASTParagraph para,
                                     FlattenedObjectPool pool,
                                     IDMLDocument idmlDoc,
                                     ColorResolver colorResolver,
                                     ASTImageLoader imageLoader,
                                     ResolvedData resolvedData) {
        String text = run.content();
        List<IDMLCharacterRun.InlineAnchor> anchors = run.inlineAnchors();
        boolean useAnchors = !anchors.isEmpty();

        List<IDMLTextFrame> frames = run.inlineFrames();
        int frameIdx = 0;   // legacy mode: frame-only FFFC 인덱스
        int anchorIdx = 0;  // anchor mode: 통합 FFFC 인덱스
        Set<Integer> processedGraphicIndices = useAnchors ? new java.util.HashSet<Integer>() : null;

        if (text != null && !text.isEmpty()) {
            // NP 폰트 글리프 → 유니코드 변환
            if (run.isNPFont()) {
                text = kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap
                        .convertRunToUnicode(run.npFontName(), text);
                if (text.isEmpty()) return; // 분수 괄호 등 변환 후 빈 텍스트
            }
            // 연속 줄바꿈(\n\n+)을 하나로 머지
            text = text.replaceAll("\n{2,}", "\n");
            // 줄바꿈 분리
            String[] segments = text.split("\n", -1);
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
                }
                String seg = segments[i];
                // FFFC 위치에서 텍스트를 분할하여 인라인 항목과 인터리빙
                int start = 0;
                for (int j = 0; j <= seg.length(); j++) {
                    if (j == seg.length() || seg.charAt(j) == '\uFFFC') {
                        // FFFC 이전 (또는 세그먼트 끝) 텍스트 추가
                        if (j > start) {
                            String part = seg.substring(start, j);
                            ASTTextRun textRun = createTextRun(run, part, parentPara, idmlDoc, colorResolver);
                            // 장식 선(GraphicLine)의 stroke 색상 → underline으로 전파
                            if (para.pendingUnderlineColor() != null && !textRun.underline()) {
                                textRun.underline(true);
                                textRun.underlineColor(para.pendingUnderlineColor());
                            }
                            para.addItem(textRun);
                        }
                        // FFFC 위치에 인라인 항목 삽입 (앵커 모드 또는 레거시 모드)
                        if (j < seg.length()) {
                            if (useAnchors && anchorIdx < anchors.size()) {
                                IDMLCharacterRun.InlineAnchor anchor = anchors.get(anchorIdx++);
                                insertAnchoredItem(anchor, run, para, idmlDoc, colorResolver,
                                        imageLoader, processedGraphicIndices, resolvedData);
                            } else if (!useAnchors && frameIdx < frames.size()) {
                                IDMLTextFrame inlineTf = frames.get(frameIdx++);
                                if (!ASTPageProcessor.shouldDeferInlineFrame(inlineTf)) {
                                    addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader);
                                }
                            } else {
                                // \uFFFC without inline object = forced line break (ACE 8)
                                para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
                            }
                        }
                        start = j + 1;
                    }
                }
            }
        }

        // 텍스트에서 소비되지 않은 나머지 앵커 / 프레임
        if (useAnchors) {
            for (int i = anchorIdx; i < anchors.size(); i++) {
                insertAnchoredItem(anchors.get(i), run, para, idmlDoc, colorResolver,
                        imageLoader, processedGraphicIndices, resolvedData);
            }
        } else {
            for (int i = frameIdx; i < frames.size(); i++) {
                IDMLTextFrame inlineTf = frames.get(i);
                if (ASTPageProcessor.shouldDeferInlineFrame(inlineTf)) continue;
                addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader);
            }
        }

        // 앵커로 처리되지 않은 나머지 인라인 그래픽 (앵커 없는 레거시 런, GREP 분할 런 등)
        for (int i = 0; i < run.inlineGraphics().size(); i++) {
            if (processedGraphicIndices != null && processedGraphicIndices.contains(i)) continue;
            processInlineGraphic(run.inlineGraphics().get(i), para, idmlDoc, colorResolver, imageLoader, resolvedData);
        }
    }

    /**
     * 앵커 타입에 따라 인라인 프레임 또는 인라인 그래픽을 단락에 삽입.
     */
    private static void insertAnchoredItem(IDMLCharacterRun.InlineAnchor anchor,
                                            IDMLCharacterRun run,
                                            ASTParagraph para,
                                            IDMLDocument idmlDoc,
                                            ColorResolver colorResolver,
                                            ASTImageLoader imageLoader,
                                            Set<Integer> processedGraphicIndices,
                                            ResolvedData resolvedData) {
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (anchor.index() < run.inlineFrames().size()) {
                IDMLTextFrame inlineTf = run.inlineFrames().get(anchor.index());
                if (!ASTPageProcessor.shouldDeferInlineFrame(inlineTf)) {
                    addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader);
                }
            }
        } else {
            if (anchor.index() < run.inlineGraphics().size()) {
                processInlineGraphic(run.inlineGraphics().get(anchor.index()),
                        para, idmlDoc, colorResolver, imageLoader, resolvedData);
                if (processedGraphicIndices != null) {
                    processedGraphicIndices.add(anchor.index());
                }
            }
        }
    }

    /**
     * 인라인 그래픽을 처리하여 단락에 추가.
     */
    static void processInlineGraphic(IDMLCharacterRun.InlineGraphic ig,
                                              ASTParagraph para,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver,
                                              ASTImageLoader imageLoader,
                                              ResolvedData resolvedData) {
        ASTInlineObject inlineObj = ASTInlineObjectBuilder.createInlineObjectFromGraphic(ig, imageLoader, colorResolver);
        if (inlineObj != null) {
            // 인라인 수평 GraphicLine → 언더라인 탭으로 변환 (빈칸 밑줄선)
            if (tryConvertGraphicLineToUnderlineTab(ig, inlineObj, para, colorResolver, idmlDoc)) {
                return;
            }
            // 크기 0인 RENDERED_GROUP 래퍼는 추가하지 않음 (배경 사각형+텍스트프레임 구조의 Group)
            boolean isEmptyWrapper = inlineObj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                    && inlineObj.width() <= 0 && inlineObj.height() <= 0
                    && (inlineObj.imageData() == null || inlineObj.imageData().length == 0);
            if (!isEmptyWrapper) {
                para.addItem(inlineObj);
            } else if (ig.hasVectorShape()
                    && ig.vectorShape().shapeType() == IDMLVectorShape.ShapeType.GRAPHIC_LINE) {
                // 수평 장식 선 → stroke 색상을 후속 텍스트 런에 underline으로 전파
                IDMLVectorShape shape = ig.vectorShape();
                String strokeHex = ASTInlineObjectBuilder.resolveColorHex(
                        shape.strokeColor(), colorResolver);
                if (strokeHex != null) {
                    double tint = shape.strokeTint() / 100.0;
                    String blended = ASTInlineObjectBuilder.blendColorWithWhite(strokeHex, tint);
                    para.pendingUnderlineColor(blended);
                }
            }
            // IMAGE로 처리된 그래픽: 자식 텍스트프레임에 실제 텍스트가 있는 경우만 오버레이 처리
            // (장식용 도형만 가진 빈 TextFrame은 오버레이 불필요 — 체크박스 등)
            if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                    && !hasChildTextFramesWithContent(ig, idmlDoc)) {
                return;
            }
        }
        // 부모 Group의 배경 사각형에서 전체 스타일 추출 (fill, stroke, cornerRadius)
        ASTInlineObjectBuilder.GroupBackground bg = ASTInlineObjectBuilder.extractGroupBackground(ig, colorResolver);
        // IDML 직접 탐색으로 못 찾은 경우 → resolved 데이터에서 fill 조회 (깊은 중첩 Group 대응)
        if (bg == null && resolvedData != null) {
            bg = ASTInlineObjectBuilder.extractGroupBackgroundFromResolved(ig, resolvedData);
            if (bg != null) {
                System.err.println("[BG-WRAPPER] resolved fallback: fill=" + bg.fillHex
                        + " id=" + ig.selfId());
            }
        }
        // 인라인 그래픽 내부의 자식 텍스트프레임 처리 (중첩 Group 포함, 재귀)
        // IMAGE 그룹인 경우: 자식 텍스트프레임을 이미지 위 오버레이로 배치
        boolean isImageGroup = inlineObj != null
                && inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                && ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig);
        if (isImageGroup) {
            ASTInlineObjectBuilder.collectOverlayFrames(
                    ig, inlineObj, idmlDoc, colorResolver, imageLoader, bg);
        } else {
            // 배경 있는 그룹 + 자식 텍스트프레임 → 단일 래퍼 글상자로 변환
            // (벡터 화살표 등은 소실되지만 텍스트+배경 스타일은 보존)
            if (bg != null && ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig)) {
                ASTInlineObject wrapper = ASTOverlayBuilder.createBackgroundGroupWrapper(
                        ig, bg, idmlDoc, colorResolver, imageLoader);
                if (wrapper != null) {
                    System.err.println("[BG-WRAPPER] SUCCESS: fill=" + bg.fillHex
                            + " paras=" + wrapper.paragraphs().size()
                            + " w=" + wrapper.width() + " h=" + wrapper.height());
                    // 이전에 추가한 RENDERED_GROUP 래퍼를 제거하고 래퍼 글상자로 교체
                    if (inlineObj != null) {
                        para.items().remove(inlineObj);
                    }
                    para.addItem(wrapper);
                    return;
                }
            }
            // 그리드 테이블 감지 시도 (2×2 이상의 TextFrame 그리드 → ASTTable)
            ASTTable gridTable = ASTTableConverter.tryBuildGridTable(
                    ig, idmlDoc, colorResolver, imageLoader, bg);
            if (gridTable != null) {
                if (inlineObj == null) {
                    inlineObj = new ASTInlineObject();
                    inlineObj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
                // 래퍼 크기를 그리드 테이블 크기로 설정
                if (inlineObj.width() <= 0) inlineObj.width(gridTable.width());
                if (inlineObj.height() <= 0) inlineObj.height(gridTable.height());
                inlineObj.addInlineTable(gridTable);
                if (!para.items().contains(inlineObj)) {
                    para.addItem(inlineObj);
                }
            } else {
                ASTInlineObjectBuilder.collectChildTextFrames(ig, para, idmlDoc, colorResolver, imageLoader, bg);
            }
        }
    }

    /**
     * 인라인 그래픽의 자식 텍스트프레임 중 실제 텍스트 콘텐츠가 있는 것이 있는지 확인.
     * 장식용 도형(체크마크 PathGeometry 등)만 포함하는 빈 TextFrame은 false.
     */
    private static boolean hasChildTextFramesWithContent(IDMLCharacterRun.InlineGraphic ig,
                                                          IDMLDocument idmlDoc) {
        if (idmlDoc == null) return ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig);
        return hasChildTextFramesWithContentRecursive(ig, idmlDoc);
    }

    private static boolean hasChildTextFramesWithContentRecursive(IDMLCharacterRun.InlineGraphic ig,
                                                                    IDMLDocument idmlDoc) {
        if (ig.childTextFrames() != null) {
            for (IDMLTextFrame tf : ig.childTextFrames()) {
                String storyId = tf.parentStoryId();
                if (storyId == null) continue;
                IDMLStory story = idmlDoc.getStory(storyId);
                if (story == null) continue;
                if (story.hasTables()) return true;
                for (IDMLParagraph p : story.paragraphs()) {
                    for (IDMLCharacterRun run : p.characterRuns()) {
                        String c = run.content();
                        if (c != null && !c.trim().isEmpty()) return true;
                    }
                }
            }
        }
        if (ig.childGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
                if (hasChildTextFramesWithContentRecursive(child, idmlDoc)) return true;
            }
        }
        return false;
    }

    private static boolean tryConvertGraphicLineToUnderlineTab(
            IDMLCharacterRun.InlineGraphic ig, ASTInlineObject inlineObj,
            ASTParagraph para, ColorResolver colorResolver, IDMLDocument idmlDoc) {
        if (!ig.hasVectorShape()) return false;
        if (ig.vectorShape().shapeType() != IDMLVectorShape.ShapeType.GRAPHIC_LINE) return false;
        if (inlineObj.height() > 200 || inlineObj.width() <= 0) return false;

        IDMLVectorShape shape = ig.vectorShape();
        String strokeHex = ASTInlineObjectBuilder.resolveColorHex(shape.strokeColor(), colorResolver);
        double tint = shape.strokeTint();

        // 직접 속성 없으면 AppliedObjectStyle에서 stroke 색상 조회
        if (strokeHex == null && ig.appliedObjectStyle() != null && idmlDoc != null) {
            String[] objStyle = idmlDoc.getObjectStyle(ig.appliedObjectStyle());
            if (objStyle != null) {
                strokeHex = ASTInlineObjectBuilder.resolveColorHex(objStyle[0], colorResolver);
                if (objStyle[2] != null) {
                    try { tint = Double.parseDouble(objStyle[2]); } catch (NumberFormatException e) { /* ignore */ }
                }
            }
        }
        if (strokeHex == null) return false;

        // IDML tint: -1 = 기본값(100%), 0~100 = 퍼센트
        double tintRatio = (tint < 0) ? 1.0 : tint / 100.0;
        String blended = ASTInlineObjectBuilder.blendColorWithWhite(strokeHex, tintRatio);

        // stroke 유형 힌트 → underline shape ("DOT", "DASH", null=SOLID)
        String ulShape = null;
        if (shape.strokeTypeHint() != null) {
            if ("dot".equals(shape.strokeTypeHint())) ulShape = "DOT";
            else if ("dash".equals(shape.strokeTypeHint())) ulShape = "DASH";
        }

        // 선행 텍스트 길이로 변환 방식 결정
        int textCharsBefore = 0;
        int lastFontSize = 1350; // default 13.5pt
        String lastFontFamily = null;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                ASTTextRun tr = (ASTTextRun) item;
                if (tr.text() != null) textCharsBefore += tr.text().length();
                if (tr.fontSizeHwpunits() != null) lastFontSize = tr.fontSizeHwpunits();
                if (tr.fontFamily() != null) lastFontFamily = tr.fontFamily();
            }
        }

        if (textCharsBefore < 10) {
            // 탭+리더 방식: 선행 텍스트가 짧은 경우 (e.g., "1. \t[___]")
            long lastTabPos = 0;
            if (para.hasTabStops()) {
                for (ASTTabStop ts : para.tabStops()) {
                    if (ts.position() > lastTabPos) lastTabPos = ts.position();
                }
            }
            // 안전 마진 200 HWPUNIT(2pt): 탭 리더가 셀 경계에 닿으면 한글이 줄바꿈하므로
            long tabPos = lastTabPos + inlineObj.width() - 200;
            if (tabPos < lastTabPos + 100) tabPos = lastTabPos + 100;
            para.addTabStop(new ASTTabStop(tabPos, "left", "_"));

            ASTTextRun tabRun = new ASTTextRun();
            tabRun.text("\t");
            // 탭 리더("_" → SOLID)가 밑줄 역할을 하므로 문자 밑줄은 설정하지 않는다.
            // 둘 다 설정하면 이중 밑줄이 나타남.
            tabRun.textColor(blended);
            para.addItem(tabRun);
        } else {
            // 고정폭 공백 방식: 본문 중간의 빈칸 밑줄 (e.g., "I like [___] the most")
            // en-space (U+2002) = 0.5em 폭으로 정밀한 너비 제어
            int enSpaceWidth = lastFontSize / 2;
            if (enSpaceWidth <= 0) enSpaceWidth = 675;
            int count = Math.max(1, (int) Math.round((double) inlineObj.width() / enSpaceWidth));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) sb.append('\u2002');

            ASTTextRun spaceRun = new ASTTextRun();
            spaceRun.text(sb.toString());
            spaceRun.underline(true);
            spaceRun.underlineColor(blended);
            spaceRun.underlineShape(ulShape);
            spaceRun.textColor(blended);
            spaceRun.fontSizeHwpunits(lastFontSize);
            if (lastFontFamily != null) spaceRun.fontFamily(lastFontFamily);
            para.addItem(spaceRun);
        }
        return true;
    }

    /**
     * 인라인 TextFrame을 분수 수식 또는 일반 인라인 오브젝트로 변환하여 단락에 추가.
     */
    private static void addInlineFrame(IDMLTextFrame inlineTf, ASTParagraph para,
                                        IDMLDocument idmlDoc, ColorResolver colorResolver,
                                        ASTImageLoader imageLoader) {
        ASTEquation fractionEq = ASTStoryConverter.tryConvertFractionTextFrame(inlineTf, idmlDoc);
        if (fractionEq != null) {
            para.addItem(fractionEq);
            return;
        }
        ASTInlineObject inlineObj = ASTStoryConverter.createInlineObjectFromTextFrame(inlineTf, idmlDoc, colorResolver, imageLoader);
        if (inlineObj == null) {
            // 분수 스타일이지만 내용 없는 빈 답안 상자 → 테두리 있는 빈 사각형
            inlineObj = createAnswerBox(inlineTf, colorResolver);
        }
        if (inlineObj == null) {
            // 채색된 도형 (불릿 아이콘 등) → 색칠된 인라인 사각형
            inlineObj = createColoredShape(inlineTf, colorResolver);
        }
        if (inlineObj != null) {
            para.addItem(inlineObj);
        }
    }

    /**
     * 빈 답안 상자(분수 스타일 TextFrame, 내용 없음) → 테두리 있는 인라인 사각형.
     */
    private static ASTInlineObject createAnswerBox(IDMLTextFrame tf, ColorResolver colorResolver) {
        String objStyle = tf.appliedObjectStyle();
        if (objStyle == null || !objStyle.contains("분수")) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        if (tf.strokeColor() != null) {
            obj.strokeColor(colorResolver.resolve(tf.strokeColor()));
        }
        obj.strokeWeight(tf.strokeWeight());
        if (tf.fillColor() != null) {
            obj.fillColor(colorResolver.resolve(tf.fillColor()));
        }
        obj.cornerRadius(tf.cornerRadius());

        return obj;
    }

    /**
     * 채색된 인라인 도형 (불릿 아이콘, 장식 도형 등) → 색칠된 인라인 사각형.
     * FillColor가 있고 스토리 내용이 없는 TextFrame을 변환.
     */
    private static ASTInlineObject createColoredShape(IDMLTextFrame tf, ColorResolver colorResolver) {
        if (tf.fillColor() == null) return null;
        // "Swatch/None" 또는 "Color/Paper"이면 실질적 색상 없음
        String fc = tf.fillColor();
        if (fc.contains("None") || fc.contains("Paper")) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        obj.fillColor(colorResolver.resolve(tf.fillColor()));
        if (tf.fillTint() > 0) {
            obj.fillTint(tf.fillTint());
        }
        if (tf.strokeColor() != null && !tf.strokeColor().contains("None")) {
            obj.strokeColor(colorResolver.resolve(tf.strokeColor()));
        }
        obj.strokeWeight(tf.strokeWeight());

        return obj;
    }

    /**
     * ASTTextRun 생성.
     * IDML 스타일 상속을 해결하여 fontFamily/fontSize/fillColor 등을 설정.
     *
     * 해결 순서: 런 직접 속성 → 적용된 CharacterStyle → 적용된 ParagraphStyle (basedOn 체인 포함)
     */
    static ASTTextRun createTextRun(IDMLCharacterRun run, String text,
                                     IDMLParagraph parentPara,
                                     IDMLDocument idmlDoc,
                                     ColorResolver colorResolver) {
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(ASTPageProcessor.stripACEPlaceholders(text));

        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null) {
            textRun.characterStyleRef(ASTStoryConverter.cleanStyleRef(charStyleRef));
        }

        // 스타일 상속 해결: 런 → CharacterStyle → ParagraphStyle
        String fontFamily = run.fontFamily();
        Double fontSize = run.fontSize();
        String fillColor = run.fillColor();
        String fontStyle = run.fontStyle();
        Double tracking = run.tracking();
        Boolean underline = run.underline();
        String underlineType = run.underlineType();
        Boolean strikeThrough = run.strikeThrough();

        // CharacterStyle에서 빈 속성 채우기
        if (charStyleRef != null) {
            IDMLStyleDef charStyle = ASTStoryConverter.resolveStyle(charStyleRef, idmlDoc.charStyles());
            if (charStyle != null) {
                if (fontFamily == null) fontFamily = charStyle.fontFamily();
                if (fontSize == null) fontSize = charStyle.fontSize();
                if (fillColor == null) fillColor = charStyle.fillColor();
                if (fontStyle == null) fontStyle = charStyle.fontStyle();
                if (tracking == null) tracking = charStyle.tracking();
                if (underline == null) underline = charStyle.underline();
                if (underlineType == null) underlineType = charStyle.underlineType();
                if (strikeThrough == null) strikeThrough = charStyle.strikeThrough();
            }
        }

        // ParagraphStyle에서 빈 속성 채우기
        String paraStyleRef = parentPara != null ? parentPara.appliedParagraphStyle() : null;
        if (paraStyleRef != null) {
            IDMLStyleDef paraStyle = ASTStoryConverter.resolveStyle(paraStyleRef, idmlDoc.paraStyles());
            if (paraStyle != null) {
                if (fontFamily == null) fontFamily = paraStyle.fontFamily();
                if (fontSize == null) fontSize = paraStyle.fontSize();
                if (fillColor == null) fillColor = paraStyle.fillColor();
                if (fontStyle == null) fontStyle = paraStyle.fontStyle();
                if (tracking == null) tracking = paraStyle.tracking();
                if (underline == null) underline = paraStyle.underline();
                if (underlineType == null) underlineType = paraStyle.underlineType();
                if (strikeThrough == null) strikeThrough = paraStyle.strikeThrough();
            }
        }

        // GREP 수식 폰트가 적용된 런: 스타일 상속 대신 BT수식M Italic 적용
        if (run.grepMathFont() && (fontFamily == null || !fontFamily.contains("BT수식"))) {
            fontFamily = "BT수식M";
            fontStyle = "Italic";
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
        textRun.grepMathFont(run.grepMathFont());
        textRun.underline(Boolean.TRUE.equals(underline));
        // 밑줄 타입 매핑 (IDML "StrokeStyle/$ID/Wavy" → AST "WAVE")
        if (Boolean.TRUE.equals(underline) && underlineType != null) {
            String lower = underlineType.toLowerCase();
            if (lower.contains("wavy") || lower.contains("wave")) {
                textRun.underlineShape("WAVE");
            } else if (lower.contains("dashed") || lower.contains("dash")) {
                textRun.underlineShape("DASH");
            } else if (lower.contains("dotted") || lower.contains("dot")) {
                textRun.underlineShape("DOT");
            }
        }
        // 밑줄 틴트가 있으면 Black을 틴트 비율로 흰색과 블렌딩하여 밑줄 색상 계산
        if (Boolean.TRUE.equals(underline) && run.underlineTint() != null) {
            double tint = run.underlineTint() / 100.0;
            int gray = (int) Math.round(255 * (1.0 - tint));
            textRun.underlineColor(String.format("#%02X%02X%02X", gray, gray, gray));
        }
        textRun.strikeThrough(Boolean.TRUE.equals(strikeThrough));

        return textRun;
    }
}
