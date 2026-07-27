package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaRenderer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaStyle;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaStyleResolver;

/**
 * 인라인 객체 dispatch + Break + Equation 변환 (W4-2 Step D).
 * - addInlineObject: ASTInlineObject 종류별 dispatch (TextFrame/Image/Group/Equation 등)
 * - shouldFlattenInlineTextFrame / flattenInlineTextFrame: 인라인 텍스트프레임 평탄화
 * - addBreak: ASTBreak → hp:lineBreak/columnBreak/pageBreak
 * - addEquationRun: ASTEquation → hp:equation (MathML)
 *
 * 4중 Builder(textBoxBuilder/tableBuilder/imageBuilder) 의존 — paragraphBuilder 참조 통해 액세스.
 * HwpxParagraphBuilder에서 분리됨.
 */
final class InlineItemDispatcher {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;

    InlineItemDispatcher(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
    }


    // ── 인라인 객체 디스패치 ──

    void addInlineObject(Para para, ASTInlineObject obj) {
        addInlineObject(para, obj, true);
    }

    void addInlineObject(Para para, ASTInlineObject obj, boolean hasMeaningfulFollowingContent) {
        if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_BADGE_GROUP) {
            if (isImageOnlyInlineBadgeGroup(obj)) {
                paragraphBuilder.imageBuilder.addInlineImage(para, obj);
            } else {
                paragraphBuilder.imageBuilder.addInlineBadgeGroup(para, obj,
                        paragraphBuilder.textBoxBuilder, paragraphBuilder);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) {
            if (isRasterizedInlineTextFrame(obj)) {
                paragraphBuilder.imageBuilder.addInlineImage(para, obj);
                return;
            }
            if (isTableOnlyInlineTextFrame(obj)) {
                for (ASTTable table : obj.inlineTables()) {
                    paragraphBuilder.tableBuilder.addInlineTableToPara(para, table);
                }
            } else if (shouldFlattenInlineTextFrame(obj)) {
                flattenInlineTextFrame(para, obj);
            } else {
                paragraphBuilder.textBoxBuilder.addInlineTextFrame(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            // 단락 콘텐츠 또는 인라인 테이블이 있는 그룹은 글상자로, 없으면 이미지로
            boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
            boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
            if (hasInlineTables && !hasParagraphs) {
                for (ASTTable table : obj.inlineTables()) {
                    paragraphBuilder.tableBuilder.addInlineTableToPara(para, table);
                }
            } else if (hasParagraphs && shouldFlattenInlineTextFrame(obj)) {
                flattenInlineTextFrame(para, obj);
            } else if (hasParagraphs || hasInlineTables) {
                paragraphBuilder.textBoxBuilder.addInlineTextFrame(para, obj);
            } else if (obj.imageData() != null && obj.imageData().length > 0) {
                paragraphBuilder.imageBuilder.addInlineImage(para, obj);
            } else if (obj.width() > 0 || obj.height() > 0) {
                // 내용 없는 빈 인라인 사각형 → 스페이서 rect (공간 확보)
                paragraphBuilder.textBoxBuilder.addSpacerRect(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
            if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) {
                paragraphBuilder.imageBuilder.addInlineImageWithOverlays(para, obj, paragraphBuilder.textBoxBuilder, paragraphBuilder);
            } else if (isPagePositionedNonFlowImage(obj)) {
                paragraphBuilder.imageBuilder.addPageLevelInlineImage(para, obj);
            } else {
                paragraphBuilder.imageBuilder.addInlineImage(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.SPACER_RECT) {
            paragraphBuilder.textBoxBuilder.addSpacerRect(para, obj);
        }
    }

    static boolean hasDrawableShell(ASTInlineObject obj) {
        if (obj == null) return false;
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) return true;
        String fill = obj.fillColor();
        if (fill != null && fill.startsWith("#")) return true;
        String stroke = obj.strokeColor();
        return stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0;
    }

    private static boolean isImageOnlyInlineBadgeGroup(ASTInlineObject obj) {
        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        return !hasParagraphs
                && !hasInlineTables
                && obj.imageData() != null
                && obj.imageData().length > 0;
    }

    private static boolean isRasterizedInlineTextFrame(ASTInlineObject obj) {
        return obj != null
                && "simple-button-label-raster".equals(obj.bundlePath())
                && obj.imageData() != null
                && obj.imageData().length > 0;
    }

    private static boolean isTableOnlyInlineTextFrame(ASTInlineObject obj) {
        boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        return hasInlineTables && !hasParagraphs;
    }

    private boolean isNonFlowHorizontalLine(ASTInlineObject obj) {
        return obj.imageData() != null
                && obj.width() > 8000
                && obj.height() > 0
                && obj.height() <= 250
                && obj.resolvedPageX() >= 0
                && obj.resolvedPageY() >= 0;
    }

    private boolean isPagePositionedNonFlowImage(ASTInlineObject obj) {
        if (obj.keepInline()) return false;
        if (isNonFlowHorizontalLine(obj)) return true;
        String wrapMode = obj.textWrapMode();
        boolean hasExplicitWrap = wrapMode != null && !"None".equals(wrapMode);
        if (hasExplicitWrap) return false;
        return obj.imageData() != null
                && !obj.affectsLineSpacing()
                && "Anchored".equals(obj.anchoredPosition())
                && obj.width() > 0
                && obj.height() > 0
                && obj.resolvedPageX() >= 0
                && obj.resolvedPageY() >= 0;
    }

    // ── 인라인 TextFrame 펼침 (단순 구조일 때 hp:rect 없이 부모 문단에 직접 삽입) ──

    /**
     * 시각 shell이 없는 인라인 TextFrame인지 판별한다.
     *
     * <p>의미 텍스트는 부모 흐름에 직접 펼쳐야 한다.  rect+drawText는 문단/run 구조를
     * 별도 drawing object 안에 가두므로, 배경/테두리/이미지처럼 실제 시각 shell이
     * 필요한 경우에만 허용한다.</p>
     */
    boolean shouldFlattenInlineTextFrame(ASTInlineObject obj) {
        if (obj.isOverlay()) return false;
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return false;
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return false;
        if (obj.imageData() != null && obj.imageData().length > 0) return false;
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) return false;
        if (hasVisibleFill(obj)) return false;
        if (hasVisibleStroke(obj)) return false;
        // 공백만 있거나 비어있는 인라인 TextFrame(빈칸박스)은 단어 사이 간격을 확보해야 하므로
        // 납작화하지 않음 — 실제 폭을 가진 인라인 박스로 렌더링해서
        // 배경 PNG 위에 그려진 빈칸 밑줄과 위치를 맞춘다.
        boolean onlyWhitespace = true;  // 비어있어도 빈칸으로 간주
        for (ASTParagraph innerParaW : obj.paragraphs()) {
            if (innerParaW == null) continue;
            for (ASTInlineItem item : innerParaW.items()) {
                if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) { onlyWhitespace = false; break; }
                String t = ((ASTTextRun) item).text();
                if (t == null || !t.trim().isEmpty()) { onlyWhitespace = false; break; }
            }
            if (!onlyWhitespace) break;
        }
        if (onlyWhitespace && obj.width() >= 800) return false; // 8pt 이상 공백 박스
        return true;
    }

    private static boolean hasSemanticText(ASTInlineObject obj) {
        if (obj == null || obj.paragraphs() == null) return false;
        int visibleChars = 0;
        int visibleRuns = 0;
        int visibleParagraphs = 0;
        for (ASTParagraph paragraph : obj.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            boolean paraHasText = false;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.trim().isEmpty()) {
                        visibleRuns++;
                        visibleChars += text.trim().length();
                        paraHasText = true;
                    }
                } else if (item instanceof ASTInlineObject && hasSemanticText((ASTInlineObject) item)) {
                    visibleRuns++;
                    visibleChars += 20;
                    paraHasText = true;
                }
            }
            if (paraHasText) visibleParagraphs++;
        }
        return visibleParagraphs > 1 || visibleRuns > 1 || visibleChars > 20;
    }

    private static boolean hasVisibleFill(ASTInlineObject obj) {
        String fill = obj.fillColor();
        return fill != null && fill.startsWith("#");
    }

    private static boolean hasVisibleStroke(ASTInlineObject obj) {
        String stroke = obj.strokeColor();
        return stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0.5;
    }

    /**
     * 인라인 TextFrame의 콘텐츠를 부모 문단에 직접 삽입.
     * 작은 글꼴의 짧은 텍스트(어휘 번호 등)는 위첨자로 표시.
     */
    void flattenInlineTextFrame(Para para, ASTInlineObject obj) {
        // 인라인 TextFrame이 위첨자 후보인지 판별:
        // - 단일 텍스트 런, 3글자 이하, 글꼴 크기 ≤ 8pt (800 HWPUNIT)
        boolean applySuperscript = false;
        if (obj.paragraphs().size() == 1) {
            ASTParagraph innerPara = obj.paragraphs().get(0);
            if (innerPara.items().size() == 1
                    && innerPara.items().get(0).itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                ASTTextRun tr = (ASTTextRun) innerPara.items().get(0);
                String text = tr.text();
                int fontSize = tr.fontSizeHwpunits() != null ? tr.fontSizeHwpunits() : 0;
                if (text != null && text.trim().length() <= 3 && fontSize > 0 && fontSize <= 800) {
                    applySuperscript = true;
                }
            }
        }

        for (int pIdx = 0; pIdx < obj.paragraphs().size(); pIdx++) {
            ASTParagraph innerPara = obj.paragraphs().get(pIdx);
            if (innerPara == null) continue;
            for (int i = 0; i < innerPara.items().size(); i++) {
                ASTInlineItem item = innerPara.items().get(i);
                switch (item.itemType()) {
                    case TEXT_RUN:
                        ASTTextRun textRun = (ASTTextRun) item;
                        if (applySuperscript) textRun.superscript(true);
                        paragraphBuilder.addTextRun(para, textRun, "0");
                        break;
                    case INLINE_OBJECT:
                        addInlineObject(para, (ASTInlineObject) item,
                                hasMeaningfulFollowingInlineContent(innerPara.items(), i + 1));
                        break;
                    case BREAK:
                        addBreak(para, (ASTBreak) item);
                        break;
                    case EQUATION:
                        addEquationRun(para, (ASTEquation) item);
                        break;
                }
            }
            if (pIdx < obj.paragraphs().size() - 1) {
                addBreak(para, new ASTBreak());
            }
        }
    }

    private static boolean hasMeaningfulFollowingInlineContent(java.util.List<ASTInlineItem> items, int startIndex) {
        if (items == null) return false;
        for (int i = startIndex; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item == null || item.itemType() == ASTInlineItem.ItemType.BREAK) continue;
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return true;
                continue;
            }
            return true;
        }
        return false;
    }


    // ── 줄바꿈 ──

    void addBreak(Para para, ASTBreak breakItem) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addNewLineBreak();
    }

    /**
     * ASTEquation → HWPX Equation (인라인 수식).
     */
    void addEquationRun(Para para, ASTEquation eq) {
        if (addStandaloneRomanNumeralAsTextRun(para, eq)) {
            return;
        }
        // SPEC-055 Phase A: 화학식(CHEM_FORMULA)도 수학 수식과 동일하게
        // hp:equation(한컴 수식)으로 방출한다. 첨자 텍스트런 강등
        // (addChemicalFormulaAsTextRuns)은 정책 전환으로 비활성.
        // 크기·색은 아래 resolveFormulaStyle 이 SPEC-042 본문 힌트를 소비한다.
        // 다행 수식 (예: 분배법칙 전개)에 포함된 U+2028(LINE SEPARATOR) 또는 \n 을
        // HwpScript 의 줄바꿈 토큰 '#' 으로 변환. (탭은 공백으로 정규화)
        String rawScript = eq.hwpScript();
        if (rawScript != null && (rawScript.indexOf(' ') >= 0 || rawScript.indexOf('\n') >= 0)) {
            String normalized = rawScript
                    .replace("\t", " ")
                    .replace(" ", " # ")
                    .replace("\n", " # ")
                    .replaceAll(" +", " ")
                    .trim();
            // # 가 양쪽에 빈 항이 되지 않도록 정리
            normalized = normalized.replaceAll("^#\\s*", "")
                    .replaceAll("\\s*#$", "")
                    .replaceAll("#\\s*#", "#");
            eq.hwpScript(normalized);
        }
        String hwpScript = EquationBuilder.sanitizeHwpScript(eq.hwpScript());
        hwpScript = FormulaStyleResolver.applyChemicalUprightScript(eq, hwpScript);
        hwpScript = FormulaStyleResolver.applyMathItalicScript(eq, hwpScript);
        eq.hwpScript(hwpScript);
        if (emitTextBoundaryWrappedEquation(para, eq, hwpScript)) {
            return;
        }
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        try {
            Equation template = EquationBuilder.fromHwpScript(hwpScript);
            Equation hwpxEq = run.addNewEquation();
            FormulaStyle style = resolveFormulaStyle(para, eq, template);
            int resolvedBaseUnit = style.baseUnit() != null ? style.baseUnit() : 1100;
            String resolvedFont = style.fontFamily();
            String eqColor = style.textColor();
            hwpxEq.versionAnd(template.version())
                    .textColorAnd(eqColor)
                    .baseUnitAnd(resolvedBaseUnit)
                    .lineModeAnd(template.lineMode())
                    .fontAnd(resolvedFont);

            // ShapeObject 기본 속성
            hwpxEq.numberingTypeAnd(NumberingType.EQUATION)
                    .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                    .textFlowAnd(TextFlowSide.BOTH_SIDES)
                    .lockAnd(false);

            // ShapeSize — 한글이 열 때 자동 계산하지만 초기값 필요
            int baseUnit = resolvedBaseUnit;
            String script = hwpScript;
            long estW = (long) (estimateRenderedWidthChars(script) * baseUnit * 0.7);
            // 분수(over) 수식은 분자+분수선+분모로 높이가 크므로 별도 추정
            boolean hasFraction = script.contains(" over ");
            long estH = hasFraction ? (long) (baseUnit * HwpxParagraphBuilder.FRACTION_HEIGHT_MULTIPLIER)
                    : (long) (baseUnit * HwpxParagraphBuilder.NORMAL_EQUATION_HEIGHT_MULTIPLIER);
            hwpxEq.createSZ();
            hwpxEq.sz().widthAnd(estW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(estH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                    .protectAnd(false);

            // ShapePosition — 글자처럼 취급 (인라인)
            hwpxEq.createPos();
            hwpxEq.pos().treatAsCharAnd(true)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.BOTTOM)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);

            // OutMargin — 수식과 주변 텍스트 사이 여백 (좌우 100 HWPUNIT = 1pt)
            // 분수 수식은 상하 여백 추가 (고정 줄간격에서 겹침 방지)
            hwpxEq.createOutMargin();
            long topMargin = hasFraction ? 200L : 0L;
            long bottomMargin = hasFraction ? 200L : 0L;
            hwpxEq.outMargin().leftAnd(100L).rightAnd(100L).topAnd(topMargin).bottomAnd(bottomMargin);

            hwpxEq.createScript();
            hwpxEq.script().addText(hwpScript);
            ctx.equationsConverted++;
        } catch (Exception e) {
            // 수식 파싱 실패 시 텍스트로 표시
            System.err.println("[HwpxParagraphBuilder] 수식 변환 실패: " + e.getMessage()
                    + " (script=" + hwpScript + ")");
            ctx.addWarning("Equation", "수식 변환 실패: " + hwpScript);
            run.addNewT().addText("[수식: " + hwpScript + "]");
        }
    }

    private boolean emitTextBoundaryWrappedEquation(Para para, ASTEquation eq, String hwpScript) {
        if (hwpScript == null || hwpScript.length() < 2 || hwpScript.charAt(0) != ':') {
            return false;
        }
        String core = hwpScript.substring(1).trim();
        if (core.isEmpty() || !isEquationCore(core)) {
            return false;
        }
        ASTTextRun prefix = new ASTTextRun();
        prefix.text(":");
        prefix.textColor(eq.textColor());
        prefix.fontSizeHwpunits(eq.preferredBaseUnit());
        paragraphBuilder.addTextRun(para, prefix, "0");

        ASTEquation coreEq = new ASTEquation(core, eq.sourceType());
        coreEq.textColor(eq.textColor());
        coreEq.preferredBaseUnit(eq.preferredBaseUnit());
        coreEq.preferredFontFamily(eq.preferredFontFamily());
        addEquationRun(para, coreEq);
        return true;
    }

    private static boolean isEquationCore(String script) {
        if (script == null || script.isEmpty()) return false;
        boolean hasFormulaToken = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c) || c == '_' || c == '^' || c == '{' || c == '}') continue;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || Character.isDigit(c)) {
                hasFormulaToken = true;
                continue;
            }
            if (c == '+' || c == '-' || c == '=' || c == '(' || c == ')') {
                hasFormulaToken = true;
                continue;
            }
            return false;
        }
        return hasFormulaToken;
    }

    /**
     * HWP 수식 스크립트의 실제 렌더 가로 폭을 문자 수로 추정한다.
     *
     * <p>{@code script.length()} 를 그대로 쓰면 {@code over}·{@code sqrt}·중괄호 등
     * 폭을 차지하지 않는 마크업까지 세어 폭이 과대해진다(실측: 3단원 y={5} over {x}
     * 가 12자로 계산돼 탭 간격이 사라짐). 마크업을 제거하고, 분수는 세로로 쌓이므로
     * 분자/분모 중 넓은 쪽만 폭에 반영한다.
     */
    private static int estimateRenderedWidthChars(String script) {
        if (script == null || script.isEmpty()) return 0;
        // 분수 {A} over {B} → 넓은 쪽 길이로 축약 (반복 적용)
        java.util.regex.Matcher m;
        java.util.regex.Pattern frac = java.util.regex.Pattern.compile(
                "\\{([^{}]*)\\} over \\{([^{}]*)\\}");
        String s = script;
        while ((m = frac.matcher(s)).find()) {
            String rep = m.group(1).length() >= m.group(2).length() ? m.group(1) : m.group(2);
            s = s.substring(0, m.start()) + rep + s.substring(m.end());
        }
        // 폭을 차지하지 않는 마크업 토큰 제거
        s = s.replace("sqrt", "√").replace(" over ", "")
                .replace("{", "").replace("}", "")
                .replace("^", "").replace("_", "");
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != ' ') count++;
        }
        return Math.max(1, count);
    }

    // SPEC-055 Phase A: 아래 두 메서드는 화학식 텍스트런 강등 경로 — 정책 전환으로
    // 미사용이지만 회귀 시 즉시 되돌릴 수 있도록 유지한다.
    @SuppressWarnings("unused")
    private boolean usesBodyTextEquationStyle(ASTEquation eq) {
        return FormulaStyleResolver.usesBodyTextEquationStyle(eq);
    }

    @SuppressWarnings("unused")
    private boolean addChemicalFormulaAsTextRuns(Para para, ASTEquation eq) {
        FormulaStyle style = resolveFormulaStyle(para, eq, null);
        java.util.List<ASTTextRun> runs = FormulaRenderer.toChemicalTextRuns(
                eq,
                style,
                FormulaClassifier.previousTextEndsWithFormulaElement(lastVisibleParagraphText(para)));
        if (runs == null || runs.isEmpty()) return false;
        String baseCharPrIDRef = lastCharPrIDRef(para);
        for (ASTTextRun run : runs) {
            paragraphBuilder.addTextRun(para, run, baseCharPrIDRef);
            baseCharPrIDRef = lastCharPrIDRef(para);
        }
        return true;
    }

    private static String lastVisibleParagraphText(Para para) {
        if (para == null || para.runs() == null) return null;
        String lastText = null;
        for (Run run : para.runs()) {
            String text = runText(run);
            if (text != null && !text.trim().isEmpty()) {
                lastText = text;
            }
        }
        return lastText;
    }

    private boolean addStandaloneRomanNumeralAsTextRun(Para para, ASTEquation eq) {
        if (eq == null || eq.hwpScript() == null) return false;
        String script = EquationBuilder.sanitizeHwpScript(eq.hwpScript()).trim();
        if (!isStandaloneUnicodeRomanNumeral(script)) return false;
        FormulaStyle style = resolveFormulaStyle(para, eq, null);
        ASTTextRun run = new ASTTextRun();
        run.text(script);
        run.fontFamily(style.fontFamily());
        run.fontSizeHwpunits(style.baseUnit());
        run.textColor(style.textColor());
        paragraphBuilder.addTextRun(para, run, lastCharPrIDRef(para));
        return true;
    }

    private FormulaStyle resolveFormulaStyle(Para para, ASTEquation eq, Equation template) {
        return FormulaStyleResolver.resolve(
                eq,
                inheritedTextHeight(para, null),
                inheritedTextColor(para),
                template != null ? template.baseUnit() : null,
                template != null ? template.font() : null,
                template != null ? template.textColor() : null,
                new FormulaStyleResolver.FontMapper() {
                    @Override
                    public String map(String sourceFont) {
                        return FontMapper.mapToHwpxFont(sourceFont);
                    }
                });
    }

    private static String lastCharPrIDRef(Para para) {
        if (para == null) return "0";
        String last = "0";
        for (Run run : para.runs()) {
            if (run != null && run.charPrIDRef() != null && !run.charPrIDRef().isEmpty()) {
                last = run.charPrIDRef();
            }
        }
        return last;
    }

    private String inheritedTextColor(Para para) {
        CharPr charPr = findCharPr(lastCharPrIDRef(para));
        if (charPr != null && charPr.textColor() != null && !charPr.textColor().isEmpty()) {
            return charPr.textColor();
        }
        return null;
    }

    private Integer inheritedTextHeight(Para para, Integer fallback) {
        CharPr charPr = findCharPr(lastCharPrIDRef(para));
        if (charPr != null && charPr.height() > 0) {
            return charPr.height();
        }
        return fallback;
    }

    private CharPr findCharPr(String id) {
        if (id == null || id.isEmpty() || ctx == null || ctx.hwpxFile == null
                || ctx.hwpxFile.headerXMLFile() == null
                || ctx.hwpxFile.headerXMLFile().refList() == null
                || ctx.hwpxFile.headerXMLFile().refList().charProperties() == null) {
            return null;
        }
        for (CharPr charPr : ctx.hwpxFile.headerXMLFile().refList().charProperties().items()) {
            if (charPr != null && id.equals(charPr.id())) {
                return charPr;
            }
        }
        return null;
    }

    private static boolean isStandaloneUnicodeRomanNumeral(String script) {
        if (script == null || script.isEmpty()) return false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (!isUnicodeRomanNumeral(c)) return false;
        }
        return true;
    }

    private static boolean isUnicodeRomanNumeral(char c) {
        return (c >= '\u2160' && c <= '\u216F') || (c >= '\u2170' && c <= '\u217F');
    }

    private static String runText(Run run) {
        if (run == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < run.countOfRunItem(); i++) {
            RunItem item = run.getRunItem(i);
            if (!(item instanceof T)) continue;
            T t = (T) item;
            if (t.onlyText() != null) {
                out.append(t.onlyText());
                continue;
            }
            for (int j = 0; j < t.countOfItems(); j++) {
                TItem tItem = t.getItem(j);
                if (tItem instanceof NormalText && ((NormalText) tItem).text() != null) {
                    out.append(((NormalText) tItem).text());
                }
            }
        }
        return out.length() > 0 ? out.toString() : null;
    }
}
