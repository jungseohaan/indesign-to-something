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

        // CharacterStyle 이름에서 밑줄 추론 (AST에서 설정되지 않은 경우)
        if (!textRun.underline() && textRun.characterStyleRef() != null) {
            String csRef = textRun.characterStyleRef();
            if (csRef.contains("밑줄") || csRef.toLowerCase().contains("underline")) {
                textRun.underline(true);
            }
        }

        if (isFormulaBoundaryText(text)) {
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

        // 인라인 스타일 오버라이드 (SPEC-031: DSL char rule이 있으면 override 강제)
        boolean hasDslChar = kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry
                .hasCharRule(textRun.characterStyleRef());
        if (hasCharacterOverrides(textRun) || hasDslChar) {
            charPrId = createOverrideCharPr(textRun);
        } else if (textRun.characterStyleRef() != null) {
            String charRef = HwpxUtil.resolveStyleRef(textRun.characterStyleRef(), ctx.styleRegistry);
            String mapped = ctx.styleRegistry.getCharPrId(charRef);
            if (mapped != null) charPrId = mapped;
        }

        // 수식 폰트(NP_, BT수식, GREP 해석) → 수식 전용 CharPr.
        // 단, BT수식H 계열이 일반 한국어 문장 run에 묻어 들어오는 경우가 있다.
        // 이때까지 수식 CharPr로 덮어쓰면 8pt 본문이 10pt 수식 스타일로 커진다.
        // 실제 수식 구조가 없는 prose run은 앞단에서 정한 본문 CharPr를 그대로 실행한다.
        if ((isEquationFontCached(textRun.fontFamily()) || textRun.grepMathFont())
                && shouldUseEquationFontCharPr(textRun)) {
            charPrId = createEquationFontCharPr(textRun, inheritedCharPrId);
        }

        // 공백 문자를 별도 런으로 분리하여 장평(ratio) 축소 적용
        // 탭/줄바꿈이 포함된 경우는 기존 로직 유지 (복잡도 방지)
        boolean hasSpecial = text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\u2028') >= 0;
        if (!hasSpecial && spaceRatio() < 100 && text.indexOf(' ') >= 0) {
            addTextRunWithSpaceSplit(para, text, charPrId, textRun);
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

    /** 공백 장평 축소 비율 (100 = 변경 없음, 50 = 50%로 축소) */
    private short spaceRatio() {
        if (ctx.config != null) return (short) ctx.config.spaceCondenseRatio();
        return 50;
    }

    /**
     * 텍스트를 "비공백/공백" 세그먼트로 분할하여 출력.
     * 공백 세그먼트에는 ratio가 축소된 별도 CharPr을 적용.
     */
    private void addTextRunWithSpaceSplit(Para para, String text, String charPrId, ASTTextRun textRun) {
        String spaceCharPrId = getOrCreateSpaceCharPr(charPrId, textRun);
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
    private String getOrCreateSpaceCharPr(String baseCharPrId, ASTTextRun textRun) {
        String cacheKey = "SP|" + baseCharPrId + "|" + (textRun.shadeColor() != null ? textRun.shadeColor() : "");
        String cached = ctx.charPrCache.get(cacheKey);
        if (cached != null) return cached;

        // 기존 CharPr을 기반으로 공백용 CharPr 생성 (ratio만 변경)
        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";
        String fontStyleStr = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";

        LineType3 ulShape = null;
        if (textRun.underline() && textRun.underlineShape() != null) {
            ulShape = LineType3.fromString(textRun.underlineShape());
        }

        // horizontalScale=null로 build 후 ratio를 spaceRatio()로 직접 덮어쓴다.
        // fontRatio(한글 글리프 폭 보정)는 공백 폭과 무관하므로 중복 적용 방지.
        CharPrBuilder.build(charPr, newId, height, textColor,
                textRun.fontFamily(), textRun.fontStyle(), ctx.fontRegistry,
                textRun.letterSpacing(),
                effectiveBoldStyle(fontStyleStr, textRun.fontFamily(), textRun.characterStyleRef()),
                isItalicStyle(fontStyleStr),
                textRun.superscript(), textRun.subscript(),
                textRun.underline() ? UnderlineType.BOTTOM : UnderlineType.NONE,
                textRun.underline()
                        ? (textRun.underlineColor() != null ? textRun.underlineColor() : textColor)
                        : "#000000",
                ulShape,
                null,  // horizontalScale: build에 맡기지 않고 아래에서 직접 덮어씀
                textRun.strikeThrough(),
                textRun.verticalScale(),
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

    String charPrCacheKey(ASTTextRun textRun) {
        return (textRun.characterStyleRef() != null ? textRun.characterStyleRef() : "")
                + "|" + (textRun.fontFamily() != null ? textRun.fontFamily() : "")
                + "|" + (textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : "")
                + "|" + (textRun.textColor() != null ? textRun.textColor() : "")
                + "|" + (textRun.shadeColor() != null ? textRun.shadeColor() : "")
                + "|" + (textRun.fontStyle() != null ? textRun.fontStyle() : "")
                + "|" + (textRun.letterSpacing() != null ? textRun.letterSpacing() : "")
                + "|" + (textRun.horizontalScale() != null ? textRun.horizontalScale() : "")
                + "|" + (textRun.verticalScale() != null ? textRun.verticalScale() : "")
                + "|" + (textRun.baselineShift() != null ? textRun.baselineShift() : "")
                + "|" + textRun.superscript()
                + "|" + textRun.subscript()
                + "|" + textRun.underline()
                + "|" + (textRun.underlineColor() != null ? textRun.underlineColor() : "")
                + "|" + (textRun.underlineShape() != null ? textRun.underlineShape() : "")
                + "|" + textRun.strikeThrough()
                + "|" + textRun.grepMathFont();
    }

    String createOverrideCharPr(ASTTextRun textRun) {
        String cacheKey = charPrCacheKey(textRun);
        String cached = ctx.charPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";
        String fontStyle = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";
        String fontFamilyToUse = textRun.fontFamily();
        Short letterSpacingToUse = textRun.letterSpacing();
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
        if (textRun.underline() && textRun.underlineShape() != null) {
            ulShape = LineType3.fromString(textRun.underlineShape());
        }

        CharPrBuilder.build(charPr, newId, height, textColor,
                fontFamilyToUse, textRun.fontStyle(), ctx.fontRegistry,
                letterSpacingToUse,
                effectiveBoldStyle(fontStyle, textRun.fontFamily(), textRun.characterStyleRef()),
                isItalicStyle(fontStyle),
                textRun.superscript(), textRun.subscript(),
                textRun.underline() ? UnderlineType.BOTTOM : UnderlineType.NONE,
                textRun.underline()
                        ? (textRun.underlineColor() != null ? textRun.underlineColor() : textColor)
                        : "#000000",
                ulShape,
                textRun.horizontalScale(),
                textRun.strikeThrough(),
                textRun.verticalScale(),
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
     * 단어 경계 기반 매칭으로 장식 폰트 이름(예: "SusicBoldItalicB140") 오인식을 방지.
     * Helvetica Neue 넘버링(65+)도 DemiBold 이상으로 판단.
     */
    static boolean isBoldStyle(String fontStyle) {
        if (fontStyle == null || fontStyle.isEmpty()) return false;
        String lower = fontStyle.toLowerCase();
        // "Bold", "Semi Bold", "SemiBold", "DemiBold" 등 단어 경계 매칭
        if (lower.matches(".*\\b(bold|semibold|demibold|heavy|black|extrabold)\\b.*")) return true;
        // Helvetica Neue 넘버링: "65 Medium", "75 Bold" 등 — 65 이상은 DemiBold급
        if (lower.matches("^(\\d{2,3})\\s+.*")) {
            try {
                int num = Integer.parseInt(lower.split("\\s+")[0]);
                if (num >= 65) return true;
            } catch (NumberFormatException ignored) {}
        }
        // 가변폰트 숫자 weight (Yoon 시리즈 등):
        //   10=Thin, 20=ExtraLight, 30=Light, 40=Regular, 50=Medium,
        //   60=Semibold, 70=Bold, 80=ExtraBold, 90=Heavy
        // → 70 이상부터 Bold 로 표시. (기존 ≥30 임계는 Light/Regular 까지 잘못 Bold 처리됨)
        if (lower.matches("^\\d+$")) {
            try {
                int weight = Integer.parseInt(lower);
                if (weight >= 70) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /**
     * fontStyle에서 Italic 여부를 판별한다.
     * 단어 경계 기반 매칭으로 장식 폰트 이름 오인식을 방지.
     */
    static boolean isItalicStyle(String fontStyle) {
        if (fontStyle == null || fontStyle.isEmpty()) return false;
        String lower = fontStyle.toLowerCase();
        return lower.matches(".*\\b(italic|oblique)\\b.*");
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
        String textColor = equationFontTextColor(textRun, baseCharPrId);
        // 키에는 CharPrBuilder.build 가 소비하는 스타일 인자를 전부 넣는다.
        // subscript/superscript 가 빠져 있던 동안, 첨자 런이 만든 CharPr 을 같은
        // 폰트·크기·색의 일반 런이 물려받아 첨자가 이웃 글자로 전이됐다
        // (과학 u1 p47 2H₂+O₂→2H₂O 의 H 가 첨자, 2 가 일반으로 뒤바뀐 사례).
        String cacheKey = baseCharPrId + "|EQ|" + (textRun.fontFamily() != null ? textRun.fontFamily() : "")
                + "|" + (textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : "")
                + "|" + (textColor != null ? textColor : "")
                + "|" + (textRun.shadeColor() != null ? textRun.shadeColor() : "")
                + "|" + (textRun.fontStyle() != null ? textRun.fontStyle() : "")
                + "|" + (textRun.letterSpacing() != null ? textRun.letterSpacing() : "")
                + "|" + (textRun.characterStyleRef() != null ? textRun.characterStyleRef() : "")
                + "|" + textRun.subscript() + "|" + textRun.superscript();
        String cached = ctx.eqFontCharPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String fontStyle = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";

        CharPrBuilder.build(charPr, newId, height, textColor,
                textRun.fontFamily(), textRun.fontStyle(), ctx.fontRegistry,
                textRun.letterSpacing(),
                effectiveBoldStyle(fontStyle, textRun.fontFamily(), textRun.characterStyleRef()),
                isItalicStyle(fontStyle),
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
            String color = charPrTextColor(run.charPrIDRef());
            if (color != null && !color.isEmpty() && !isDefaultBlack(color)) {
                last = run.charPrIDRef();
            }
        }
        return last != null ? last : lastCharPrIDRef(para, fallback);
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
