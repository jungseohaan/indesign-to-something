package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.*;

/**
 * 스토리 내 단락/런 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
class ASTStoryConverter {

    /** 이 높이(HWPUNIT)를 넘는 인라인 이미지는 별도 단락으로 분리 (~30pt ≈ 1cm) */
    static final long IMAGE_SPLIT_THRESHOLD = 3000;

    /**
     * IDMLParagraph → ASTParagraph 변환.
     */
    static ASTParagraph convertParagraph(IDMLParagraph idmlPara,
                                         FlattenedObjectPool pool,
                                         IDMLDocument idmlDoc,
                                         ColorResolver colorResolver,
                                         ASTImageLoader imageLoader,
                                         boolean storyHasBTRuns) {
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
        if (idmlPara.firstLineIndent() != null) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(idmlPara.firstLineIndent()));
        }
        if (idmlPara.leftIndent() != null) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(idmlPara.leftIndent()));
        }
        if (idmlPara.rightIndent() != null) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(idmlPara.rightIndent()));
        }
        if (idmlPara.spaceBefore() != null) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(idmlPara.spaceBefore()));
        }
        if (idmlPara.spaceAfter() != null) {
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

        // 탭 정지점 (인라인 오버라이드 → 단락 스타일 → basedOn 체인)
        java.util.List<IDMLStyleDef.TabStop> tabStops = idmlPara.tabStops();
        if (tabStops == null || tabStops.isEmpty()) {
            // 인라인 오버라이드가 없으면 단락 스타일에서 상속
            if (paraStyleRef != null) {
                IDMLStyleDef paraStyle = resolveStyle(paraStyleRef, idmlDoc.paraStyles());
                if (paraStyle != null) {
                    tabStops = paraStyle.tabStops();
                }
            }
        }
        if (tabStops != null) {
            for (IDMLStyleDef.TabStop ts : tabStops) {
                long posHwpunits = CoordinateConverter.pointsToHwpunits(ts.position());
                String alignment = mapTabAlignment(ts.alignment());
                para.addTabStop(new ASTTabStop(posHwpunits, alignment, ts.leader()));
            }
        }

        // Character Runs → 인라인 항목
        // BT수식M 폰트 런은 그룹핑하여 ASTEquation으로 변환
        // NP 폰트 런도 그룹핑하여 NPFontEquationConverter로 ASTEquation 변환
        // BT/NP 런 사이에 끼인 짧은 일반 텍스트(변수명 등)는 "브릿지"로 수식 그룹에 포함

        // 전처리: 한국어+수식마커 혼합 런을 분리 (예: "_r를 구해" → "_r" + "를 구해")
        List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(idmlPara.characterRuns());

        // "Indent to Here" (ACE 7, U+0008) 처리
        // 원시 런 콘텐츠에서 U+0008 마커를 찾아 indent 계산 후 제거 (수식 그룹 변환 전에 처리)
        boolean hasIndentToHere = applyIndentToHere(para, runs, idmlPara, idmlDoc);

        // r\d+par → 원문자(①②③...) 전처리 (수식 그룹화 전에 실행)
        convertCircledNumberRuns(runs);

        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        List<ASTEquation> mathGroupFractions = new ArrayList<>(); // 수식 그룹 런의 인라인 분수
        List<IDMLCharacterRun> npMathGroup = new ArrayList<>(); // NP 폰트 수식 그룹
        List<ASTEquation> npMathGroupFractions = new ArrayList<>(); // NP 수식 그룹의 인라인 분수

        // 단락 또는 스토리에 BT 수식 폰트 런이 하나라도 있는지 확인
        boolean paraHasBTRuns = storyHasBTRuns;
        if (!paraHasBTRuns) {
            for (IDMLCharacterRun r : runs) {
                if (r.isBTFont() || r.grepMathFont()) { paraHasBTRuns = true; break; }
            }
        }

        // 단락에 NP 구조 런(아래첨자, 근호, 분수 등)이 있는지 확인
        boolean paraHasNPStructuralRuns = false;
        for (IDMLCharacterRun r : runs) {
            if (r.isNPFont()) {
                NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(r.npFontName());
                if (cat == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX
                        || cat == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX
                        || cat == NPFontGlyphMap.FontCategory.ROOT
                        || cat == NPFontGlyphMap.FontCategory.FRACTION_BAR
                        || cat == NPFontGlyphMap.FontCategory.INTEGRAL
                        || cat == NPFontGlyphMap.FontCategory.SUMMATION
                        || cat == NPFontGlyphMap.FontCategory.LIMIT
                        || cat == NPFontGlyphMap.FontCategory.SPECIAL_SYMBOL) {
                    paraHasNPStructuralRuns = true;
                    break;
                }
            }
        }

        for (int idx = 0; idx < runs.size(); idx++) {
            IDMLCharacterRun run = runs.get(idx);
            // 명시적으로 비BT 폰트가 지정된 순수 알파벳/숫자 런만 GREP 플래그 해제
            // 예: "1" (Helvetica Neue) → 해제, "y" (폰트 미지정, GREP으로 BT수식M 적용) → 유지
            if (run.grepMathFont() && ASTMathGrouper.isPlainAlphanumericRun(run)) {
                String ff = run.fontFamily();
                if (ff != null && !ff.contains("BT수식")) {
                    run.grepMathFont(false);
                }
            }

            // NP 수식 그룹 진입 여부 판단
            boolean enterNPMathGroup = false;
            if (run.isNPFont()) {
                enterNPMathGroup = true;
            } else if (!npMathGroup.isEmpty() && ASTMathGrouper.isNPMathBridgeRun(run, runs, idx)) {
                enterNPMathGroup = true;
            } else if (npMathGroup.isEmpty() && ASTMathGrouper.isPreNPMathRun(run, runs, idx)) {
                // NP 그룹 시작 전 비NP 수학 텍스트 (예: "y=log" 뒤에 NP_ISHS:"2" 올 때)
                enterNPMathGroup = true;
            } else if (paraHasNPStructuralRuns && !run.isNPFont() && !run.isBTFont()
                    && !run.grepMathFont() && ASTMathGrouper.isStandaloneMathRun(run)) {
                // 단락에 NP 구조 런이 있으면, 독립 수학 텍스트(x=k, 0<k<8)도 수식으로 변환
                enterNPMathGroup = true;
            }

            // BT 수식 그룹 진입 여부 판단
            boolean enterMathGroup = false;
            if (!enterNPMathGroup) {
                if ((run.isBTFont() || run.grepMathFont())
                        && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content())
                        && !ASTMathGrouper.isPlainAlphanumericRun(run)) {
                    enterMathGroup = true;
                } else if (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx)) {
                    enterMathGroup = true;
                } else if (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content())) {
                    enterMathGroup = true;
                }
            }

            if (enterNPMathGroup) {
                // BT 그룹이 열려있으면 먼저 flush
                if (!mathGroup.isEmpty()) {
                    flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere);
                    emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader);
                    mathGroup.clear();
                    mathGroupFractions.clear();
                }
                extractFractionFrames(run, idmlDoc, npMathGroupFractions);
                npMathGroup.add(run);
            } else if (enterMathGroup) {
                // NP 그룹이 열려있으면 먼저 flush
                if (!npMathGroup.isEmpty()) {
                    flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere);
                    emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader);
                    npMathGroup.clear();
                    npMathGroupFractions.clear();
                }
                // 수식 그룹에 들어가는 런의 인라인 분수 TextFrame 추출
                // (flushMathGroup은 텍스트만 처리하므로 인라인 프레임은 여기서 별도 수집)
                extractFractionFrames(run, idmlDoc, mathGroupFractions);
                mathGroup.add(run);
            } else {
                // 둘 다 종료 → 변환
                if (!mathGroup.isEmpty()) {
                    flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere);
                    emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader);
                    mathGroup.clear();
                    mathGroupFractions.clear();
                }
                if (!npMathGroup.isEmpty()) {
                    flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere);
                    emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader);
                    npMathGroup.clear();
                    npMathGroupFractions.clear();
                }
                convertCharacterRun(run, idmlPara, para, pool, idmlDoc, colorResolver, imageLoader);
            }
        }
        // 마지막 수식 그룹 처리
        if (!mathGroup.isEmpty()) {
            flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere);
            emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader);
        }
        if (!npMathGroup.isEmpty()) {
            flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere);
            emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader);
        }

        // 단락 끝의 trailing lineBreak 제거
        // 단락 맨 끝에 연속된 BREAK만 제거 (중간의 BREAK는 보존 — 수식 분할 줄바꿈 등)
        List<ASTInlineItem> items = para.items();
        while (!items.isEmpty()
                && items.get(items.size() - 1).itemType() == ASTInlineItem.ItemType.BREAK) {
            items.remove(items.size() - 1);
        }

        // 분수 수식(FRACTION_FRAME) 포함 단락: 줄간격을 PERCENT로 변경하고 여백 추가
        // 고정 줄간격에서 분수 수식이 다음 텍스트와 겹치는 것을 방지
        if (hasFractionEquation(para)) {
            para.lineSpacingType("percent");
            para.lineSpacing(200);
            if (para.spaceAfter() == null || para.spaceAfter() < 400) {
                para.spaceAfter(400L);
            }
        }

        return para;
    }

    /**
     * IDML 탭 정렬 문자열을 HWPX 탭 타입으로 매핑.
     */
    static String mapTabAlignment(String idmlAlignment) {
        if (idmlAlignment == null) return "left";
        switch (idmlAlignment) {
            case "CenterAlign": return "center";
            case "RightAlign": return "right";
            case "DecimalAlign": // IDML uses "Character" for decimal
            case "Character": return "decimal";
            default: return "left"; // LeftAlign 또는 기타
        }
    }

    /**
     * "Indent to Here" (U+0008) 마커를 원시 IDMLCharacterRun에서 제거한다.
     * U+0008은 XML에서 허용되지 않는 제어 문자이므로 반드시 제거해야 한다.
     * 수식 그룹 변환 전에 호출해야 한다 (U+0008이 수식 경로로 들어가는 것을 방지).
     *
     * 참고: HWPX의 leftMargin/firstLineIndent로 InDesign의 "Indent to Here"를
     * 재현하면 탭 정지점이 margin 기준으로 이동하여 첫 줄이 밀리는 문제가 있어,
     * 현재는 마커 제거만 수행한다.
     *
     * @return true if any U+0008 marker was found (수식 분할 여부 결정에 사용)
     */
    private static boolean applyIndentToHere(ASTParagraph para,
                                            List<IDMLCharacterRun> runs,
                                            IDMLParagraph idmlPara,
                                            IDMLDocument idmlDoc) {
        boolean found = false;
        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text == null) continue;
            if (text.indexOf('\u0008') >= 0) {
                found = true;
                run.content(text.replace("\u0008", ""));
            }
        }
        return found;
    }

    /**
     * BT수식M 글리프 코드 r\d+par를 원문자(①②③...)로 변환한다.
     * 수식 그룹화 전에 호출해야 한다 (원문자는 수식이 아닌 일반 텍스트로 처리).
     */
    private static final java.util.regex.Pattern R_N_PAR = java.util.regex.Pattern.compile("r(\\d+)par");

    private static void convertCircledNumberRuns(List<IDMLCharacterRun> runs) {
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
                                     ASTImageLoader imageLoader) {
        String text = run.content();
        List<IDMLCharacterRun.InlineAnchor> anchors = run.inlineAnchors();
        boolean useAnchors = !anchors.isEmpty();

        List<IDMLTextFrame> frames = run.inlineFrames();
        int frameIdx = 0;   // legacy mode: frame-only FFFC 인덱스
        int anchorIdx = 0;  // anchor mode: 통합 FFFC 인덱스
        Set<Integer> processedGraphicIndices = useAnchors ? new java.util.HashSet<>() : null;

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
                            para.addItem(createTextRun(run, part, parentPara, idmlDoc, colorResolver));
                        }
                        // FFFC 위치에 인라인 항목 삽입 (앵커 모드 또는 레거시 모드)
                        if (j < seg.length()) {
                            if (useAnchors && anchorIdx < anchors.size()) {
                                IDMLCharacterRun.InlineAnchor anchor = anchors.get(anchorIdx++);
                                insertAnchoredItem(anchor, run, para, idmlDoc, colorResolver,
                                        imageLoader, processedGraphicIndices);
                            } else if (!useAnchors && frameIdx < frames.size()) {
                                IDMLTextFrame inlineTf = frames.get(frameIdx++);
                                if (!Stage4_BuildAST.shouldDeferInlineFrame(inlineTf)) {
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
                        imageLoader, processedGraphicIndices);
            }
        } else {
            for (int i = frameIdx; i < frames.size(); i++) {
                IDMLTextFrame inlineTf = frames.get(i);
                if (Stage4_BuildAST.shouldDeferInlineFrame(inlineTf)) continue;
                addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader);
            }
        }

        // 앵커로 처리되지 않은 나머지 인라인 그래픽 (앵커 없는 레거시 런, GREP 분할 런 등)
        for (int i = 0; i < run.inlineGraphics().size(); i++) {
            if (processedGraphicIndices != null && processedGraphicIndices.contains(i)) continue;
            processInlineGraphic(run.inlineGraphics().get(i), para, idmlDoc, colorResolver, imageLoader);
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
                                            Set<Integer> processedGraphicIndices) {
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (anchor.index() < run.inlineFrames().size()) {
                IDMLTextFrame inlineTf = run.inlineFrames().get(anchor.index());
                if (!Stage4_BuildAST.shouldDeferInlineFrame(inlineTf)) {
                    addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader);
                }
            }
        } else {
            if (anchor.index() < run.inlineGraphics().size()) {
                processInlineGraphic(run.inlineGraphics().get(anchor.index()),
                        para, idmlDoc, colorResolver, imageLoader);
                if (processedGraphicIndices != null) {
                    processedGraphicIndices.add(anchor.index());
                }
            }
        }
    }

    /**
     * 인라인 그래픽을 처리하여 단락에 추가.
     */
    private static void processInlineGraphic(IDMLCharacterRun.InlineGraphic ig,
                                              ASTParagraph para,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver,
                                              ASTImageLoader imageLoader) {
        ASTInlineObject inlineObj = ASTInlineObjectBuilder.createInlineObjectFromGraphic(ig, imageLoader, colorResolver);
        if (inlineObj != null) {
            // 크기 0인 RENDERED_GROUP 래퍼는 추가하지 않음 (배경 사각형+텍스트프레임 구조의 Group)
            boolean isEmptyWrapper = inlineObj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                    && inlineObj.width() <= 0 && inlineObj.height() <= 0
                    && (inlineObj.imageData() == null || inlineObj.imageData().length == 0);
            if (!isEmptyWrapper) {
                para.addItem(inlineObj);
            }
            // IMAGE로 처리된 Group 중 자식 텍스트프레임이 없는 경우만 스킵
            // 자식 텍스트프레임이 있으면 이미지와 함께 텍스트도 추출 (약도+교통편 등)
            if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                    && !ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig)) {
                return;
            }
        }
        // 부모 Group의 배경 사각형에서 전체 스타일 추출 (fill, stroke, cornerRadius)
        ASTInlineObjectBuilder.GroupBackground bg = ASTInlineObjectBuilder.extractGroupBackground(ig, colorResolver);
        // 인라인 그래픽 내부의 자식 텍스트프레임 처리 (중첩 Group 포함, 재귀)
        // IMAGE 그룹인 경우: 자식 텍스트프레임을 이미지 위 오버레이로 배치
        boolean isImageGroup = inlineObj != null
                && inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                && ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig);
        if (isImageGroup) {
            ASTInlineObjectBuilder.collectOverlayFrames(
                    ig, inlineObj, idmlDoc, colorResolver, imageLoader, bg);
        } else {
            ASTInlineObjectBuilder.collectChildTextFrames(ig, para, idmlDoc, colorResolver, imageLoader, bg);
        }
    }

    /**
     * 인라인 TextFrame을 분수 수식 또는 일반 인라인 오브젝트로 변환하여 단락에 추가.
     */
    private static void addInlineFrame(IDMLTextFrame inlineTf, ASTParagraph para,
                                        IDMLDocument idmlDoc, ColorResolver colorResolver,
                                        ASTImageLoader imageLoader) {
        ASTEquation fractionEq = tryConvertFractionTextFrame(inlineTf, idmlDoc);
        if (fractionEq != null) {
            para.addItem(fractionEq);
            return;
        }
        ASTInlineObject inlineObj = createInlineObjectFromTextFrame(inlineTf, idmlDoc, colorResolver, imageLoader);
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
        textRun.text(Stage4_BuildAST.stripACEPlaceholders(text));

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
        Boolean underline = run.underline();
        Boolean strikeThrough = run.strikeThrough();

        // CharacterStyle에서 빈 속성 채우기
        if (charStyleRef != null) {
            IDMLStyleDef charStyle = resolveStyle(charStyleRef, idmlDoc.charStyles());
            if (charStyle != null) {
                if (fontFamily == null) fontFamily = charStyle.fontFamily();
                if (fontSize == null) fontSize = charStyle.fontSize();
                if (fillColor == null) fillColor = charStyle.fillColor();
                if (fontStyle == null) fontStyle = charStyle.fontStyle();
                if (tracking == null) tracking = charStyle.tracking();
                if (underline == null) underline = charStyle.underline();
                if (strikeThrough == null) strikeThrough = charStyle.strikeThrough();
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
                if (underline == null) underline = paraStyle.underline();
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
        textRun.strikeThrough(Boolean.TRUE.equals(strikeThrough));

        return textRun;
    }

    /**
     * 스타일 상속 체인(basedOn)을 따라 속성을 해결한다.
     */
    static IDMLStyleDef resolveStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
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
        merged.underline(style.underline() != null ? style.underline() : parent.underline());
        merged.strikeThrough(style.strikeThrough() != null ? style.strikeThrough() : parent.strikeThrough());
        merged.leading(style.leading() != null ? style.leading() : parent.leading());
        merged.leadingType(style.leadingType() != null ? style.leadingType() : parent.leadingType());
        merged.autoLeading(style.autoLeading() != null ? style.autoLeading() : parent.autoLeading());
        merged.tabStops(style.tabStops() != null ? style.tabStops() : parent.tabStops());
        return merged;
    }

    /**
     * 스타일 맵에서 스타일을 찾는다.
     * IDML의 basedOn 값은 접두사가 없을 수 있으므로 (예: "$ID/[No paragraph style]"),
     * 직접 조회 실패 시 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사를 붙여 재시도.
     */
    static IDMLStyleDef findStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
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
    static ASTInlineObject createInlineObjectFromTextFrame(IDMLTextFrame tf,
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

        // 인라인 스토리의 단락을 ASTParagraph로 변환 (큰 이미지는 별도 단락으로 분리)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (IDMLParagraph idmlPara : inlineStory.paragraphs()) {
            ASTParagraph astPara = convertParagraph(idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader, false);
            if (astPara != null && !astPara.items().isEmpty()) {
                for (ASTParagraph split : splitParagraphAtLargeImages(astPara)) {
                    obj.addParagraph(split);
                }
            }
        }

        // 인라인 스토리의 테이블을 ASTTable로 변환
        for (IDMLTable idmlTable : inlineStory.tables()) {
            ASTTable table = convertInlineTable(idmlTable, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                obj.addInlineTable(table);
            }
        }

        // 앵커/래핑 속성 전달
        obj.anchoredPosition(tf.anchoredPosition());
        obj.textWrapMode(tf.textWrapMode());

        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        return (hasParagraphs || hasTables) ? obj : null;
    }

    /**
     * 수식 그룹을 플러시하고, 수집된 분수 수식을 FFFC 위치에 통합.
     * hasIndentToHere가 true이면 첫 번째 FFFC 위치에서 줄바꿈하여 분수를 다음 줄에 배치.
     * hasIndentToHere가 false이면 분수를 인라인으로 통합 (줄바꿈 없음).
     * 분수가 없는 경우에도 잔존 \uFFFC를 제거한다.
     */
    private static void flushMathGroupWithFractions(List<IDMLCharacterRun> mathGroup,
                                                      ASTParagraph para,
                                                      List<ASTEquation> fractions,
                                                      boolean hasIndentToHere) {
        ASTMathGrouper.flushMathGroup(mathGroup, para);

        // flushMathGroup이 방금 추가한 마지막 수식 항목 찾기
        List<ASTInlineItem> items = para.items();
        ASTEquation lastEq = null;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.EQUATION) {
                lastEq = (ASTEquation) items.get(i);
                break;
            }
        }

        if (lastEq == null) {
            for (ASTEquation eq : fractions) {
                para.addItem(eq);
            }
            return;
        }

        String script = lastEq.hwpScript();
        if (script == null || script.indexOf('\uFFFC') < 0) {
            for (ASTEquation eq : fractions) {
                para.addItem(eq);
            }
            return;
        }

        if (!fractions.isEmpty()) {
            if (hasIndentToHere) {
                // Indent to Here가 있는 경우: 첫 번째 \uFFFC에서 줄바꿈 분할
                int firstFffc = script.indexOf('\uFFFC');
                String beforeFrac = script.substring(0, firstFffc).replace("\uFFFC", "").trim();
                String afterFrac = script.substring(firstFffc);

                lastEq.hwpScript(beforeFrac);
                para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));

                String fracLine = replaceFffcWithFractions(afterFrac, fractions);
                if (!fracLine.isEmpty()) {
                    para.addItem(new ASTEquation(fracLine, "FRACTION_FRAME"));
                }
            } else {
                // Indent to Here가 없는 경우: 인라인으로 분수 통합 (줄바꿈 없음)
                String merged = replaceFffcWithFractions(script, fractions);
                lastEq.hwpScript(merged);
            }
        } else {
            // 분수 없이 \uFFFC만 있는 경우 → 제거
            lastEq.hwpScript(script.replace("\uFFFC", ""));
        }
    }

    /**
     * NP 수식 그룹을 flush하면서 인라인 분수 TextFrame을 통합.
     * flushMathGroupWithFractions의 NP 버전.
     */
    private static void flushNPMathGroupWithFractions(List<IDMLCharacterRun> npGroup,
                                                       ASTParagraph para,
                                                       List<ASTEquation> fractions,
                                                       boolean hasIndentToHere) {
        ASTMathGrouper.flushNPMathGroup(npGroup, para);

        // flushNPMathGroup이 방금 추가한 마지막 수식 항목 찾기
        List<ASTInlineItem> items = para.items();
        ASTEquation lastEq = null;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.EQUATION) {
                lastEq = (ASTEquation) items.get(i);
                break;
            }
        }

        if (lastEq == null) {
            for (ASTEquation eq : fractions) {
                para.addItem(eq);
            }
            return;
        }

        String script = lastEq.hwpScript();
        if (script == null || script.indexOf('\uFFFC') < 0) {
            for (ASTEquation eq : fractions) {
                para.addItem(eq);
            }
            return;
        }

        if (!fractions.isEmpty()) {
            String merged = replaceFffcWithFractions(script, fractions);
            lastEq.hwpScript(merged);
        } else {
            lastEq.hwpScript(script.replace("\uFFFC", ""));
        }
    }

    /**
     * 문자열 내 \uFFFC를 분수 스크립트로 순서대로 교체하고, 남은 \uFFFC는 제거.
     */
    private static String replaceFffcWithFractions(String text, List<ASTEquation> fractions) {
        StringBuilder sb = new StringBuilder();
        int fracIdx = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFC') {
                if (fracIdx < fractions.size()) {
                    sb.append(fractions.get(fracIdx).hwpScript());
                    fracIdx++;
                }
            } else {
                sb.append(text.charAt(i));
            }
        }
        return sb.toString().trim();
    }

    /**
     * 단락에 분수 수식(FRACTION_FRAME 또는 over 포함)이 있는지 확인.
     */
    private static boolean hasFractionEquation(ASTParagraph para) {
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.EQUATION) {
                ASTEquation eq = (ASTEquation) item;
                if ("FRACTION_FRAME".equals(eq.sourceType())) return true;
                if (eq.hwpScript() != null && eq.hwpScript().contains(" over ")) return true;
            }
        }
        return false;
    }

    /**
     * 런의 인라인 TextFrame 중 분수 TextFrame을 찾아 ASTEquation으로 변환하여 리스트에 추가.
     * 수식 그룹에 들어가는 런에서 호출 (flushMathGroup은 텍스트만 처리하므로).
     */
    private static void extractFractionFrames(IDMLCharacterRun run, IDMLDocument idmlDoc,
                                               List<ASTEquation> fractionList) {
        for (IDMLTextFrame tf : run.inlineFrames()) {
            ASTEquation eq = tryConvertFractionTextFrame(tf, idmlDoc);
            if (eq != null) {
                fractionList.add(eq);
            }
        }
    }

    /**
     * 수식 그룹에 속한 런들의 인라인 그래픽을 수식 뒤에 출력.
     * flushMathGroup은 텍스트만 처리하므로, 수식 런에 포함된 인라인 그래픽(Group 등)은
     * 여기서 별도로 처리한다.
     */
    private static void emitMathGroupInlineGraphics(List<IDMLCharacterRun> mathGroup,
                                                      ASTParagraph para,
                                                      IDMLDocument idmlDoc,
                                                      ColorResolver colorResolver,
                                                      ASTImageLoader imageLoader) {
        for (IDMLCharacterRun run : mathGroup) {
            if (run.inlineGraphics().isEmpty()) continue;
            for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                processInlineGraphic(ig, para, idmlDoc, colorResolver, imageLoader);
            }
        }
    }

    /**
     * 인라인 TextFrame을 분수 HWP 수식으로 변환.
     * 감지 조건:
     *   (1) ObjectStyle에 "분수" 포함 (명시적 분수 스타일), 또는
     *   (2) 첫 단락에 RuleBelow가 설정됨 (단락 아래선 = 분수선)
     * 2개 단락 구조: 분자(para1) / 분모(para2).
     */
    static ASTEquation tryConvertFractionTextFrame(IDMLTextFrame tf, IDMLDocument idmlDoc) {
        // 1. ObjectStyle 확인
        String objStyle = tf.appliedObjectStyle();
        boolean hasFractionStyle = objStyle != null && objStyle.contains("분수");

        // 간격용 스타일 제외 (#간격, 0_분수-보기간격 등)
        if (hasFractionStyle && objStyle.contains("간격")) return null;

        // 2. 인라인 스토리 로드
        if (tf.parentStoryId() == null) return null;
        IDMLStory story = idmlDoc.getStory(tf.parentStoryId());
        if (story == null) return null;

        List<IDMLParagraph> paras = story.paragraphs();

        // 3. ObjectStyle이 분수가 아닌 경우 → 첫 단락의 RuleBelow로 분수 감지
        if (!hasFractionStyle) {
            boolean hasRuleBelow = false;
            for (IDMLParagraph p : paras) {
                if (p.ruleBelowOn()) { hasRuleBelow = true; break; }
            }
            if (!hasRuleBelow) return null;
        }

        // 4. 비어 있지 않은 단락을 찾아 분자/분모 추출
        // IDML에서 <Br/>로 인해 빈 단락이 중간에 생길 수 있으므로 건너뜀
        String numText = null;
        String denText = null;
        for (IDMLParagraph p : paras) {
            String text = extractParagraphText(p);
            if (text.isEmpty()) continue;
            if (numText == null) {
                numText = text;
            } else {
                denText = text;
                break;
            }
        }
        if (numText == null || denText == null) {
            // 분수 스타일이지만 내용 없음 → 빈 답안 상자 (□)
            if (hasFractionStyle) {
                return new ASTEquation("\u25A1", "ANSWER_BOX");
            }
            return null;
        }

        // 5. BT 수식 텍스트 → HWP 스크립트 변환
        String numScript = BTFontEquationConverter.convertRawToHwpScript(numText);
        String denScript = BTFontEquationConverter.convertRawToHwpScript(denText);

        // 6. 분수 수식 조립
        String hwpScript = "{" + numScript + "} over {" + denScript + "}";
        return new ASTEquation(hwpScript, "FRACTION_FRAME");
    }

    /**
     * 단락의 모든 런 텍스트를 연결하여 반환.
     */
    private static String extractParagraphText(IDMLParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.content() != null) {
                // \uFFFC (Object Replacement Char) → □ (빈 답안 상자 등 인라인 오브젝트 자리)
                sb.append(run.content().replace('\uFFFC', '\u25A1'));
            }
        }
        return sb.toString().trim();
    }

    /**
     * 단락 내 큰 인라인 이미지를 별도 단락으로 분리.
     * 텍스트와 큰 이미지가 같은 단락에 있으면 고정 줄간격으로 인해 겹침이 발생하므로,
     * [Text, LargeImage, Text] → [TextPara, ImagePara, TextPara] 로 분리.
     */
    static List<ASTParagraph> splitParagraphAtLargeImages(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();

        // 큰 이미지가 있는지 + 텍스트가 있는지 확인
        boolean hasLargeImage = false;
        boolean hasText = false;
        for (ASTInlineItem item : items) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (isLargeImage(obj)) hasLargeImage = true;
            } else if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                hasText = true;
            }
        }
        if (!hasLargeImage || !hasText) {
            return Collections.singletonList(para);
        }

        // 큰 이미지를 경계로 분할
        List<ASTParagraph> result = new ArrayList<>();
        List<ASTInlineItem> currentItems = new ArrayList<>();

        for (ASTInlineItem item : items) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                    && isLargeImage((ASTInlineObject) item)) {
                // 축적된 아이템을 단락으로
                if (!currentItems.isEmpty()) {
                    result.add(createSplitParagraph(para, currentItems, result.isEmpty()));
                    currentItems = new ArrayList<>();
                }
                // 큰 이미지를 독립 단락으로
                List<ASTInlineItem> imgItems = new ArrayList<>();
                imgItems.add(item);
                result.add(createSplitParagraph(para, imgItems, result.isEmpty()));
            } else {
                currentItems.add(item);
            }
        }
        // 나머지 아이템
        if (!currentItems.isEmpty()) {
            result.add(createSplitParagraph(para, currentItems, result.isEmpty()));
        }

        // 단락 간격 보존: spaceBefore → 첫 단락만, spaceAfter → 마지막 단락만
        if (result.size() > 1) {
            for (int i = 1; i < result.size(); i++) {
                result.get(i).spaceBefore(0L);
            }
            for (int i = 0; i < result.size() - 1; i++) {
                result.get(i).spaceAfter(0L);
            }
        }

        return result;
    }

    static boolean isLargeImage(ASTInlineObject obj) {
        if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE)
            return obj.height() > IMAGE_SPLIT_THRESHOLD;
        if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                && (obj.paragraphs() == null || obj.paragraphs().isEmpty()))
            return obj.height() > IMAGE_SPLIT_THRESHOLD;
        return false;
    }

    /**
     * 원본 단락의 스타일 속성을 복제하여 새 단락 생성.
     * isFirst=true일 때만 firstLineIndent 보존.
     * 이미지 단독 단락은 lineSpacing을 설정하지 않아 자동 확장.
     */
    static ASTParagraph createSplitParagraph(ASTParagraph source,
                                              List<ASTInlineItem> items,
                                              boolean isFirst) {
        ASTParagraph p = new ASTParagraph();
        p.paragraphStyleRef(source.paragraphStyleRef());
        p.alignment(source.alignment());
        p.leftMargin(source.leftMargin());
        p.rightMargin(source.rightMargin());
        p.spaceBefore(source.spaceBefore());
        p.spaceAfter(source.spaceAfter());
        if (isFirst) {
            p.firstLineIndent(source.firstLineIndent());
        }
        // 이미지 단독 단락에는 lineSpacing 미설정 (자동 확장)
        boolean isImageOnly = items.size() == 1
                && items.get(0).itemType() == ASTInlineItem.ItemType.INLINE_OBJECT;
        if (!isImageOnly) {
            p.lineSpacingType(source.lineSpacingType());
            p.lineSpacing(source.lineSpacing());
        }
        p.letterSpacing(source.letterSpacing());
        if (source.tabStops() != null) {
            for (ASTTabStop ts : source.tabStops()) {
                p.addTabStop(ts);
            }
        }
        p.shadingOn(source.shadingOn());
        p.shadingColor(source.shadingColor());
        p.shadingTint(source.shadingTint());

        for (ASTInlineItem item : items) {
            p.addItem(item);
        }
        return p;
    }

    /**
     * 인라인 스토리 내 테이블 → ASTTable 변환 (위치 정보 없이).
     */
    static ASTTable convertInlineTable(IDMLTable idmlTable,
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

            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = ASTInlineObjectBuilder.convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
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

        ASTTableSpacerMerger.merge(table);
        return table;
    }

    /**
     * 스타일 참조에서 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사 제거.
     */
    static String cleanStyleRef(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("ParagraphStyle/")) {
            return ref.substring("ParagraphStyle/".length());
        }
        if (ref.startsWith("CharacterStyle/")) {
            return ref.substring("CharacterStyle/".length());
        }
        return ref;
    }
}
