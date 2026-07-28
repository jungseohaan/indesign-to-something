package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.CharPrBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.font.FontStyleClassifier;

/**
 * TextRun + CharPr 생성 + 폰트 스타일 판별 (W4-2 Step C).
 * - addTextRun / addTextRunWithSpaceSplit / addTextWithSpecialChars: 텍스트 런 생성
 * - getOrCreateSpaceCharPr: 공백용 CharPr 캐싱
 * - hasCharacterOverrides / charPrCacheKey / createOverrideCharPr: CharPr override
 * - effectiveBoldStyle / isBoldStyle / isItalicStyle / isEquationFont: 폰트 스타일 판별
 * - createEquationFontCharPr: 수식 폰트 전용 CharPr
 *
 * HwpxParagraphBuilder에서 분리됨.
 */
final class CharPrFactory {

    private final HwpxConverterContext ctx;

    /** HwpxParagraphBuilder가 단락 처리 시작 시 세팅 — DSL char rule에서 para style 참조용 (SPEC-031) */
    String currentParaStyleRef = null;

    /** isEquationFont() 결과 캐시 — 수식 교과서는 동일 폰트명이 수천 번 반복되므로 런당 중복 판별 방지 */
    private final java.util.HashMap<String, Boolean> eqFontCache = new java.util.HashMap<>();

    CharPrFactory(HwpxConverterContext ctx) {
        this.ctx = ctx;
    }

    void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId) {
        addTextRun(para, textRun, defaultCharPrId, 0);
    }

    void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId, long indentToHerePosition) {
        String text = HwpxUtil.sanitizeText(textRun.text());
        if (text == null || text.isEmpty()) return;

        String charPrId = defaultCharPrId;
        String inheritedCharPrId = lastNonDefaultTextColorCharPrIDRef(para, defaultCharPrId);

        // Generic EH "상부자(이탤릭)" is math-variable typography, not a semantic
        // superscript style. Some legacy/resolved bridges have already inferred
        // superscript=true from that name before reaching this single materialization
        // gateway. Do not execute that name-only inference as <hh:supscript/>.
        if (textRun.superscript() && isEhSangbujaItalicTypography(textRun.characterStyleRef())) {
            textRun.superscript(false);
        }

        // CharacterStyle 이름에서 밑줄 추론 (AST에서 설정되지 않은 경우)
        if (!textRun.underline() && textRun.characterStyleRef() != null) {
            String csRef = textRun.characterStyleRef();
            if (csRef.contains("밑줄") || csRef.toLowerCase().contains("underline")) {
                textRun.underline(true);
            }
        }

        if (isFormulaBoundaryText(text) && !lastVisibleRunIsAnswerBox(para)) {
            String boundaryCharPrId = lastBodyTextCharPrIDRef(para, inheritedCharPrId);
            String inheritedColor = charPrTextColor(boundaryCharPrId);
            if (inheritedColor != null && !inheritedColor.isEmpty() && !isDefaultBlack(inheritedColor)
                    && (textRun.textColor() == null
                        || textRun.textColor().isEmpty()
                        || isDefaultBlack(textRun.textColor()))) {
                Run run = para.addNewRun();
                run.charPrIDRef(boundaryCharPrId);
                run.addNewT().addText(text);
                return;
            }
        }

        EffectiveTextStyle effectiveStyle = effectiveTextStyle(textRun);

        // 인라인 스타일 오버라이드 (SPEC-031: DSL char rule이 있으면 override 강제)
        boolean hasDslChar = kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry
                .hasCharRule(textRun.characterStyleRef());
        if (hasCharacterOverrides(textRun) || hasDslChar || isRealCharacterStyleRef(textRun.characterStyleRef())) {
            charPrId = createOverrideCharPr(textRun, effectiveStyle);
        } else if (textRun.characterStyleRef() != null) {
            String charRef = HwpxUtil.resolveStyleRef(textRun.characterStyleRef(), ctx.styleRegistry);
            String mapped = ctx.styleRegistry.getCharPrId(charRef);
            if (mapped != null) charPrId = mapped;
        }

        // 수식 폰트(NP_, BT수식, GREP 해석) → 수식 전용 CharPr.
        // 단, BT수식H 계열이 일반 한국어 문장 run에 묻어 들어오는 경우가 있다.
        // 이때까지 수식 CharPr로 덮어쓰면 8pt 본문이 10pt 수식 스타일로 커진다.
        // 실제 수식 구조가 없는 prose run은 앞단에서 정한 본문 CharPr를 그대로 실행한다.
        if ((isEquationFontCached(effectiveStyle.fontFamily) || textRun.grepMathFont())
                && shouldUseEquationFontCharPr(textRun)) {
            charPrId = createEquationFontCharPr(textRun, inheritedCharPrId, effectiveStyle);
        }

        // 공백 문자를 별도 런으로 분리하여 장평(ratio) 축소 적용
        // 탭/줄바꿈이 포함된 경우는 기존 로직 유지 (복잡도 방지)
        boolean hasSpecial = text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\u2028') >= 0;
        if (!hasSpecial && spaceRatio() < 100 && text.indexOf(' ') >= 0) {
            addTextRunWithSpaceSplit(para, text, charPrId, textRun, effectiveStyle);
        } else {
            Run run = para.addNewRun();
            run.charPrIDRef(charPrId);
            if (hasSpecial) {
                addTextWithSpecialChars(run, text, indentToHerePosition);
            } else {
                run.addNewT().addText(text);
            }
        }
    }

    private static boolean isEhSangbujaItalicTypography(String styleRef) {
        if (styleRef == null) return false;
        String normalized = styleRef.toLowerCase(java.util.Locale.ROOT)
                .replace("%3a", ":")
                .replace("%25", "%");
        return normalized.contains("상부자") && normalized.contains("이탤릭");
    }

    /** 공백 장평 축소 비율 (100 = 변경 없음, 50 = 50%로 축소) */
    private short spaceRatio() {
        if (ctx.config != null) return (short) ctx.config.spaceCondenseRatio();
        return 50;
    }

    /**
     * 텍스트를 "비공백/공백" 세그먼트로 분할하여 출력.
     * 공백 세그먼트에는 ratio가 축소된 별도 CharPr을 적용.
     */
    private void addTextRunWithSpaceSplit(Para para, String text, String charPrId,
                                          ASTTextRun textRun, EffectiveTextStyle effectiveStyle) {
        String spaceCharPrId = getOrCreateSpaceCharPr(charPrId, textRun, effectiveStyle);
        int i = 0;
        while (i < text.length()) {
            boolean isSpace = text.charAt(i) == ' ';
            int start = i;
            while (i < text.length() && (text.charAt(i) == ' ') == isSpace) {
                i++;
            }
            String segment = text.substring(start, i);
            Run run = para.addNewRun();
            // Leading spaces can be source-authored layout, for example a
            // left-aligned citation pushed right inside an InDesign text frame.
            // Keep their original advance; only condense inter-word spaces.
            boolean preserveSourceLeadingSpaces = isSpace && start == 0;
            run.charPrIDRef(isSpace && !preserveSourceLeadingSpaces ? spaceCharPrId : charPrId);
            run.addNewT().addText(segment);
        }
    }

    /**
     * 공백용 CharPr을 생성 또는 캐시에서 가져온다.
     * 기존 CharPr과 동일하되 ratio만 spaceRatio()로 축소.
     */
    private String getOrCreateSpaceCharPr(String baseCharPrId, ASTTextRun textRun,
                                          EffectiveTextStyle effectiveStyle) {
        String cacheKey = "SP|" + baseCharPrId + "|" + effectiveStyle.cacheKey()
                + "|" + (textRun.shadeColor() != null ? textRun.shadeColor() : "");
        String cached = ctx.charPrCache.get(cacheKey);
        if (cached != null) return cached;

        // 기존 CharPr을 기반으로 공백용 CharPr 생성 (ratio만 변경)
        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        LineType3 ulShape = null;
        if (effectiveStyle.underline && effectiveStyle.underlineShape != null) {
            ulShape = LineType3.fromString(effectiveStyle.underlineShape);
        }

        // horizontalScale=null로 build 후 ratio를 spaceRatio()로 직접 덮어쓴다.
        // fontRatio(한글 글리프 폭 보정)는 공백 폭과 무관하므로 중복 적용 방지.
        CharPrBuilder.build(charPr, newId, effectiveStyle.height, effectiveStyle.textColor,
                effectiveStyle.fontFamily, effectiveStyle.fontStyle, ctx.fontRegistry,
                effectiveStyle.letterSpacing,
                effectiveStyle.bold,
                effectiveStyle.italic,
                textRun.superscript(), textRun.subscript(),
                effectiveStyle.underline ? UnderlineType.BOTTOM : UnderlineType.NONE,
                effectiveStyle.underline
                        ? (effectiveStyle.underlineColor != null ? effectiveStyle.underlineColor : effectiveStyle.textColor)
                        : "#000000",
                ulShape,
                null,  // horizontalScale: build에 맡기지 않고 아래에서 직접 덮어씀
                effectiveStyle.strikeThrough,
                effectiveStyle.verticalScale,
                textRun.baselineShift());
        applyShadeColor(charPr, textRun);
        // spaceCondenseRatio를 절대 목표값으로 직접 적용 (fontRatio와 독립)
        short sr = spaceRatio();
        charPr.ratio().set(sr, sr, sr, sr, sr, sr, sr);

        ctx.charPrCache.put(cacheKey, newId);
        return newId;
    }

    /**
     * 텍스트 내의 탭과 source text-run separator를 HWPX 요소로 변환한다.
     *
     * <p>명시적 줄바꿈은 Stage 2에서 ASTBreak로 승격된 것만 보존한다. 텍스트런 내부의
     * \n/U+2028은 IDML Content에 섞인 soft separator이므로 HWPX 강제 lineBreak로
     * 내보내지 않고 공백으로 접는다.
     */
    void addTextWithSpecialChars(Run run, String text, long indentToHerePosition) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                if (buf.length() > 0) {
                    run.addNewT().addText(buf.toString());
                    buf.setLength(0);
                }
                run.addNewT().addNewTab();
            } else if (c == '\n' || c == '\u2028') {
                if (buf.length() == 0 || buf.charAt(buf.length() - 1) != ' ') {
                    buf.append(' ');
                }
            } else if (c == '\r') {
                // \r 무시 (\r\n의 경우 \n이 처리)
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            run.addNewT().addText(buf.toString());
        }
    }

    boolean hasCharacterOverrides(ASTTextRun run) {
        return run.fontFamily() != null
                || run.fontStyle() != null
                || run.fontSizeHwpunits() != null
                || run.textColor() != null
                || run.shadeColor() != null
                || run.letterSpacing() != null
                || run.horizontalScale() != null
                || run.verticalScale() != null
                || run.baselineShift() != null
                || run.subscript()
                || run.superscript()
                || run.underline()
                || run.strikeThrough();
    }

    private EffectiveTextStyle effectiveTextStyle(ASTTextRun run) {
        ASTStyleDef paraStyle = findStyle(ctx.paragraphStyles, currentParaStyleRef);
        ASTStyleDef charStyle = findStyle(ctx.characterStyles, run.characterStyleRef());

        boolean hasRealCharacterStyle = charStyle != null;
        String fontFamily = hasRealCharacterStyle
                ? firstNonBlank(charStyle.fontFamily(), run.fontFamily(), paraStyle != null ? paraStyle.fontFamily() : null)
                : firstNonBlank(run.fontFamily(), paraStyle != null ? paraStyle.fontFamily() : null);
        String fontStyle = hasRealCharacterStyle
                ? firstNonBlank(charStyle.fontStyle(), run.fontStyle(), paraStyle != null ? paraStyle.fontStyle() : null)
                : firstNonBlank(run.fontStyle(), paraStyle != null ? paraStyle.fontStyle() : null);
        int height = hasRealCharacterStyle
                ? firstPositive(charStyle.fontSizeHwpunits(), run.fontSizeHwpunits(),
                        paraStyle != null ? paraStyle.fontSizeHwpunits() : null, 1000)
                : firstPositive(run.fontSizeHwpunits(),
                        paraStyle != null ? paraStyle.fontSizeHwpunits() : null, null, 1000);
        String textColor = hasRealCharacterStyle
                ? firstNonBlank(charStyle.textColor(), run.textColor(), paraStyle != null ? paraStyle.textColor() : null, "#000000")
                : firstNonBlank(run.textColor(), paraStyle != null ? paraStyle.textColor() : null, "#000000");
        Short letterSpacing = hasRealCharacterStyle
                ? firstNonNull(charStyle.letterSpacing(), run.letterSpacing(), paraStyle != null ? paraStyle.letterSpacing() : null)
                : firstNonNull(run.letterSpacing(), paraStyle != null ? paraStyle.letterSpacing() : null);
        Short horizontalScale = hasRealCharacterStyle
                ? firstNonNull(charStyle.horizontalScale(), run.horizontalScale(), paraStyle != null ? paraStyle.horizontalScale() : null)
                : firstNonNull(run.horizontalScale(), paraStyle != null ? paraStyle.horizontalScale() : null);

        boolean underline = run.underline()
                || (charStyle != null && Boolean.TRUE.equals(charStyle.underline()))
                || (paraStyle != null && Boolean.TRUE.equals(paraStyle.underline()));
        String underlineColor = firstNonBlank(run.underlineColor(),
                charStyle != null ? charStyle.underlineColor() : null,
                paraStyle != null ? paraStyle.underlineColor() : null);
        String underlineShape = firstNonBlank(run.underlineShape(),
                charStyle != null ? charStyle.underlineType() : null,
                paraStyle != null ? paraStyle.underlineType() : null);
        boolean strikeThrough = run.strikeThrough()
                || (charStyle != null && Boolean.TRUE.equals(charStyle.strikeThrough()))
                || (paraStyle != null && Boolean.TRUE.equals(paraStyle.strikeThrough()));

        String fontStyleLower = fontStyle != null ? fontStyle.toLowerCase() : "";
        boolean bold = (charStyle != null && Boolean.TRUE.equals(charStyle.bold()))
                || (paraStyle != null && Boolean.TRUE.equals(paraStyle.bold()))
                || effectiveBoldStyle(fontStyleLower, fontFamily, run.characterStyleRef());
        boolean italic = (charStyle != null && Boolean.TRUE.equals(charStyle.italic()))
                || (paraStyle != null && Boolean.TRUE.equals(paraStyle.italic()))
                || isItalicStyle(fontStyleLower);

        return new EffectiveTextStyle(fontFamily, fontStyle, height, textColor,
                letterSpacing, horizontalScale, run.verticalScale(),
                underline, underlineColor, underlineShape, strikeThrough, bold, italic);
    }

    private ASTStyleDef findStyle(java.util.List<ASTStyleDef> styles, String styleRef) {
        if (styles == null || !isRealStyleRef(styleRef)) return null;
        String resolved = HwpxUtil.resolveStyleRef(styleRef, ctx.styleRegistry);
        for (ASTStyleDef sd : styles) {
            if (sd != null && sameStyleId(sd.styleId(), resolved, styleRef)) {
                return sd;
            }
        }
        return null;
    }

    private static boolean sameStyleId(String styleId, String resolvedRef, String rawRef) {
        if (styleId == null) return false;
        if (styleId.equals(resolvedRef) || styleId.equals(rawRef)) return true;
        return (resolvedRef != null && styleId.endsWith("/" + resolvedRef))
                || (rawRef != null && styleId.endsWith("/" + rawRef));
    }

    private static boolean isRealCharacterStyleRef(String styleRef) {
        if (!isRealStyleRef(styleRef)) return false;
        return styleRef.startsWith("CharacterStyle/")
                || !styleRef.startsWith("ParagraphStyle/");
    }

    private static boolean isRealStyleRef(String styleRef) {
        if (styleRef == null || styleRef.isEmpty()) return false;
        String lower = styleRef.toLowerCase(java.util.Locale.ROOT);
        return !lower.contains("[no character style]")
                && !lower.contains("no character style");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static int firstPositive(Integer first, Integer second, Integer third, int defaultValue) {
        if (first != null && first > 0) return first;
        if (second != null && second > 0) return second;
        if (third != null && third > 0) return third;
        return defaultValue;
    }

    static final class EffectiveTextStyle {
        final String fontFamily;
        final String fontStyle;
        final int height;
        final String textColor;
        final Short letterSpacing;
        final Short horizontalScale;
        final Short verticalScale;
        final boolean underline;
        final String underlineColor;
        final String underlineShape;
        final boolean strikeThrough;
        final boolean bold;
        final boolean italic;

        EffectiveTextStyle(String fontFamily, String fontStyle, int height, String textColor,
                           Short letterSpacing, Short horizontalScale, Short verticalScale,
                           boolean underline, String underlineColor, String underlineShape,
                           boolean strikeThrough, boolean bold, boolean italic) {
            this.fontFamily = fontFamily;
            this.fontStyle = fontStyle;
            this.height = height;
            this.textColor = textColor;
            this.letterSpacing = letterSpacing;
            this.horizontalScale = horizontalScale;
            this.verticalScale = verticalScale;
            this.underline = underline;
            this.underlineColor = underlineColor;
            this.underlineShape = underlineShape;
            this.strikeThrough = strikeThrough;
            this.bold = bold;
            this.italic = italic;
        }

        String cacheKey() {
            return (fontFamily != null ? fontFamily : "")
                    + "|" + (fontStyle != null ? fontStyle : "")
                    + "|" + height
                    + "|" + (textColor != null ? textColor : "")
                    + "|" + (letterSpacing != null ? letterSpacing : "")
                    + "|" + (horizontalScale != null ? horizontalScale : "")
                    + "|" + (verticalScale != null ? verticalScale : "")
                    + "|" + underline
                    + "|" + (underlineColor != null ? underlineColor : "")
                    + "|" + (underlineShape != null ? underlineShape : "")
                    + "|" + strikeThrough
                    + "|" + bold
                    + "|" + italic;
        }
    }

    String charPrCacheKey(ASTTextRun textRun) {
        return charPrCacheKey(textRun, effectiveTextStyle(textRun));
    }

    String charPrCacheKey(ASTTextRun textRun, EffectiveTextStyle effectiveStyle) {
        return (textRun.characterStyleRef() != null ? textRun.characterStyleRef() : "")
                + "|" + effectiveStyle.cacheKey()
                + "|" + (textRun.shadeColor() != null ? textRun.shadeColor() : "")
                + "|" + (textRun.baselineShift() != null ? textRun.baselineShift() : "")
                + "|" + textRun.superscript()
                + "|" + textRun.subscript()
                + "|" + textRun.grepMathFont();
    }

    String createOverrideCharPr(ASTTextRun textRun) {
        return createOverrideCharPr(textRun, effectiveTextStyle(textRun));
    }

    String createOverrideCharPr(ASTTextRun textRun, EffectiveTextStyle effectiveStyle) {
        String cacheKey = charPrCacheKey(textRun, effectiveStyle);
        String cached = ctx.charPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = effectiveStyle.height;
        String textColor = effectiveStyle.textColor;
        String fontFamilyToUse = effectiveStyle.fontFamily;
        String fontStyleToUse = effectiveStyle.fontStyle;
        Short letterSpacingToUse = effectiveStyle.letterSpacing;
        if (fontFamilyToUse != null && fontFamilyToUse.contains("BT수식H")
                && isPlainKoreanProse(textRun.text())) {
            fontFamilyToUse = null;
        }

        // SPEC-031: DSL char rule 적용
        kr.dogfoot.hwpxlib.tool.idmlconverter.rule.RuleContext ruleCtx =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.rule.RuleContext(
                        currentParaStyleRef, textRun.characterStyleRef(),
                        textRun.text() != null ? textRun.text() : "");
        kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry.applyCharRule(ruleCtx);
        if (ruleCtx.targetKoFont != null) fontFamilyToUse = ruleCtx.targetKoFont;
        if (ruleCtx.targetFontSizePt != null && textRun.fontSizeHwpunits() == null)
            height = (int) (ruleCtx.targetFontSizePt * kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ConverterConstants.HWPUNIT_PER_POINT);
        if (ruleCtx.targetLetterSpacing != null) letterSpacingToUse = ruleCtx.targetLetterSpacing;

        // underline shape: ASTTextRun.underlineShape → LineType3
        LineType3 ulShape = null;
        if (effectiveStyle.underline && effectiveStyle.underlineShape != null) {
            ulShape = LineType3.fromString(effectiveStyle.underlineShape);
        }

        CharPrBuilder.build(charPr, newId, height, textColor,
                fontFamilyToUse, fontStyleToUse, ctx.fontRegistry,
                letterSpacingToUse,
                effectiveStyle.bold,
                effectiveStyle.italic,
                textRun.superscript(), textRun.subscript(),
                effectiveStyle.underline ? UnderlineType.BOTTOM : UnderlineType.NONE,
                effectiveStyle.underline
                        ? (effectiveStyle.underlineColor != null ? effectiveStyle.underlineColor : textColor)
                        : "#000000",
                ulShape,
                effectiveStyle.horizontalScale,
                effectiveStyle.strikeThrough,
                effectiveStyle.verticalScale,
                textRun.baselineShift());
        applyShadeColor(charPr, textRun);

        ctx.charPrCache.put(cacheKey, newId);
        return newId;
    }

    private void applyShadeColor(CharPr charPr, ASTTextRun textRun) {
        if (textRun.shadeColor() != null && !textRun.shadeColor().isEmpty()) {
            charPr.shadeColor(textRun.shadeColor());
        }
    }

    /**
     * IDML CharStyle 에서 fontStyle="Bold" 라고 지정해도, AppliedFont 가 실제로 Bold 변종이 없는 경우
     * InDesign 은 Medium/Regular 로 대체 렌더한다 (예: "DIN Next LT Pro (TT)" + Bold → Medium).
     * resolved.json fontMetrics 의 실제 weight 가 600 미만이면 Bold 로 강조 표시하지 않는다.
     *
     * 단, CharacterStyle 자체가 "강조"/emphasis/strong 의미를 갖는 경우는 원본의 semantic emphasis가
     * 이미 확정된 상태이므로 실제 weight guard보다 우선한다.
     */
    boolean effectiveBoldStyle(String fontStyle, String fontFamily, String characterStyleRef) {
        if (isEmphasisStyle(characterStyleRef)) return true;
        if (!isBoldStyle(fontStyle)) return false;
        try {
            kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper fm = ctx.fontRegistry.fontMapper();
            if (fm != null && fontFamily != null) {
                int actual = fm.resolvedWeightFor(fontFamily);
                if (actual > 0 && actual < 600) return false;  // Bold 변종 미존재 → 시각적 Bold 아님
            }
        } catch (Exception e) {}
        return true;
    }

    static boolean isEmphasisStyle(String styleRef) {
        if (styleRef == null || styleRef.isEmpty()) return false;
        String lower = styleRef.toLowerCase();
        return styleRef.contains("강조")
                || lower.contains("emphasis")
                || lower.contains("strong");
    }

    /**
     * fontStyle에서 Bold 여부를 판별한다.
     */
    static boolean isBoldStyle(String fontStyle) {
        return FontStyleClassifier.isBoldStyle(fontStyle);
    }

    /**
     * fontStyle에서 Italic 여부를 판별한다.
     * 단어 경계 기반 매칭으로 장식 폰트 이름 오인식을 방지.
     */
    static boolean isItalicStyle(String fontStyle) {
        return FontStyleClassifier.isItalicStyle(fontStyle);
    }

    private boolean isEquationFontCached(String fontFamily) {
        if (fontFamily == null) return false;
        Boolean cached = eqFontCache.get(fontFamily);
        if (cached != null) return cached;
        boolean result = isEquationFont(fontFamily);
        eqFontCache.put(fontFamily, result);
        return result;
    }

    static boolean isEquationFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("NP_")
                || BTFontGlyphMap.isBTFontFamily(fontFamily)
                || EHFontGlyphMap.isEHFontFamily(fontFamily);
    }

    private static boolean shouldUseEquationFontCharPr(ASTTextRun textRun) {
        if (textRun == null) return false;
        String fontFamily = textRun.fontFamily();
        String text = textRun.text();

        if (fontFamily != null && fontFamily.contains("BT수식H") && isPlainKoreanProse(text)) {
            return false;
        }
        if (fontFamily != null && fontFamily.contains("BT수식H") && text != null && containsHangul(text)
                && !containsEquationSyntax(text)) {
            return false;
        }
        return true;
    }

    private static boolean isPlainKoreanProse(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (!containsHangul(text)) return false;
        return !containsEquationSyntax(text);
    }

    private static boolean containsHangul(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\uAC00' && c <= '\uD7A3')
                    || (c >= '\u1100' && c <= '\u11FF')
                    || (c >= '\u3130' && c <= '\u318F')) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsEquationSyntax(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(" over ") || lower.contains("sqrt") || lower.contains("root")
                || lower.contains("rarrow") || lower.contains("overline")) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("=+*/<>≤≥±×÷√²³^_π∑∫∞{}[]".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    String createEquationFontCharPr(ASTTextRun textRun, String baseCharPrId) {
        return createEquationFontCharPr(textRun, baseCharPrId, effectiveTextStyle(textRun));
    }

    String createEquationFontCharPr(ASTTextRun textRun, String baseCharPrId,
                                    EffectiveTextStyle effectiveStyle) {
        String textColor = equationFontTextColor(textRun, baseCharPrId);
        if ((textRun.textColor() == null || textRun.textColor().isEmpty())
                && effectiveStyle.textColor != null
                && !effectiveStyle.textColor.isEmpty()) {
            textColor = effectiveStyle.textColor;
        }
        // 키에는 CharPrBuilder.build 가 소비하는 스타일 인자를 전부 넣는다.
        // subscript/superscript 가 빠져 있던 동안, 첨자 런이 만든 CharPr 을 같은
        // 폰트·크기·색의 일반 런이 물려받아 첨자가 이웃 글자로 전이됐다
        // (과학 u1 p47 2H₂+O₂→2H₂O 의 H 가 첨자, 2 가 일반으로 뒤바뀐 사례).
        String cacheKey = baseCharPrId + "|EQ|" + (effectiveStyle.fontFamily != null ? effectiveStyle.fontFamily : "")
                + "|" + effectiveStyle.height
                + "|" + (textColor != null ? textColor : "")
                + "|" + (textRun.shadeColor() != null ? textRun.shadeColor() : "")
                + "|" + (effectiveStyle.fontStyle != null ? effectiveStyle.fontStyle : "")
                + "|" + (effectiveStyle.letterSpacing != null ? effectiveStyle.letterSpacing : "")
                + "|" + (textRun.characterStyleRef() != null ? textRun.characterStyleRef() : "")
                + "|" + textRun.subscript() + "|" + textRun.superscript();
        String cached = ctx.eqFontCharPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        CharPrBuilder.build(charPr, newId, effectiveStyle.height, textColor,
                effectiveStyle.fontFamily, effectiveStyle.fontStyle, ctx.fontRegistry,
                effectiveStyle.letterSpacing,
                effectiveStyle.bold,
                effectiveStyle.italic,
                textRun.superscript(), textRun.subscript(),
                UnderlineType.NONE, textColor,
                null, // underlineShape
                null,
                false,
                null, null);
        applyShadeColor(charPr, textRun);

        ctx.eqFontCharPrCache.put(cacheKey, newId);
        return newId;
    }

    private String equationFontTextColor(ASTTextRun textRun, String baseCharPrId) {
        String sourceColor = textRun.textColor();
        if (sourceColor != null && !sourceColor.isEmpty() && !isDefaultBlack(sourceColor)) {
            return sourceColor;
        }
        String inherited = charPrTextColor(baseCharPrId);
        if (inherited != null && !inherited.isEmpty()) {
            return inherited;
        }
        return sourceColor != null && !sourceColor.isEmpty() ? sourceColor : "#000000";
    }

    private static String lastCharPrIDRef(Para para, String fallback) {
        if (para == null || para.runs() == null) return fallback;
        String last = fallback;
        for (Run run : para.runs()) {
            if (run != null && run.charPrIDRef() != null && !run.charPrIDRef().isEmpty()) {
                last = run.charPrIDRef();
            }
        }
        return last;
    }

    private String lastNonDefaultTextColorCharPrIDRef(Para para, String fallback) {
        if (para == null || para.runs() == null) return fallback;
        String last = null;
        for (Run run : para.runs()) {
            if (run == null || run.charPrIDRef() == null || run.charPrIDRef().isEmpty()) {
                continue;
            }
            // SPEC-085: 빈 답란 □(박스 크기·색 charPr)는 색 상속원이 아니다 —
            // □ 사이 쉼표가 20pt 하늘색으로 커지는 원인 (수학 u1 p14).
            String text = runText(run);
            if (text != null && !text.trim().isEmpty()
                    && text.trim().chars().allMatch(c -> c == 0x25A1)) {
                continue;
            }
            String color = charPrTextColor(run.charPrIDRef());
            if (color != null && !color.isEmpty() && !isDefaultBlack(color)) {
                last = run.charPrIDRef();
            }
        }
        return last != null ? last : lastCharPrIDRef(para, fallback);
    }

    /**
     * SPEC-085: 직전 가시 런이 빈 답란 □ 이면 경계 텍스트(쉼표 등)가 그 charPr
     * (박스 크기·색, SPEC-083)를 상속하지 않는다 — □ 사이 쉼표가 20pt 하늘색으로
     * 커지던 원인 (수학 u1 p14 "제곱근은 □, □이다").
     */
    private static boolean lastVisibleRunIsAnswerBox(Para para) {
        if (para == null || para.runs() == null) return false;
        String lastVisible = null;
        for (Run run : para.runs()) {
            String text = runText(run);
            if (text == null || text.trim().isEmpty()) continue;
            lastVisible = text.trim();
        }
        return lastVisible != null && lastVisible.chars().allMatch(c -> c == 0x25A1);
    }

    private String lastBodyTextCharPrIDRef(Para para, String fallback) {
        if (para == null || para.runs() == null) return fallback;
        String last = null;
        for (Run run : para.runs()) {
            if (run == null || run.charPrIDRef() == null || run.charPrIDRef().isEmpty()) {
                continue;
            }
            String text = runText(run);
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            String trimmed = text.trim();
            if (isStandaloneUnicodeRomanNumeral(trimmed) || containsAsciiLetterOrDigit(trimmed)) {
                continue;
            }
            // SPEC-085: 빈 답란 □(SPEC-083 박스 크기·색 반영 charPr)는 상속원이 아니다.
            // 상속하면 □ 사이 쉼표가 20pt 하늘색 박스 스타일로 커진다 (수학 u1 p14).
            if (trimmed.chars().allMatch(c -> c == 0x25A1)) {
                continue;
            }
            String color = charPrTextColor(run.charPrIDRef());
            if (color != null && !color.isEmpty() && !isDefaultBlack(color)) {
                last = run.charPrIDRef();
            }
        }
        return last != null ? last : fallback;
    }

    private static String runText(Run run) {
        if (run == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < run.countOfRunItem(); i++) {
            RunItem item = run.getRunItem(i);
            if (!(item instanceof T)) {
                continue;
            }
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

    private static boolean isStandaloneUnicodeRomanNumeral(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '\u2160' && c <= '\u216F') || (c >= '\u2170' && c <= '\u217F'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAsciiLetterOrDigit(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                return true;
            }
        }
        return false;
    }

    private String charPrTextColor(String id) {
        if (id == null || id.isEmpty() || ctx == null || ctx.hwpxFile == null
                || ctx.hwpxFile.headerXMLFile() == null
                || ctx.hwpxFile.headerXMLFile().refList() == null
                || ctx.hwpxFile.headerXMLFile().refList().charProperties() == null) {
            return null;
        }
        for (CharPr charPr : ctx.hwpxFile.headerXMLFile().refList().charProperties().items()) {
            if (charPr != null && id.equals(charPr.id())) {
                return charPr.textColor();
            }
        }
        return null;
    }

    private static boolean isFormulaBoundaryText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasBoundary = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '\u2005'
                    || c == '\u2007'
                    || c == '\u2009'
                    || c == '\u200A') {
                continue;
            }
            if (c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == ',' || c == '.') {
                hasBoundary = true;
                continue;
            }
            return false;
        }
        return hasBoundary;
    }

    private static boolean isDefaultBlack(String color) {
        if (color == null) return false;
        String normalized = color.trim();
        return "#000000".equalsIgnoreCase(normalized)
                || "000000".equalsIgnoreCase(normalized);
    }
}
