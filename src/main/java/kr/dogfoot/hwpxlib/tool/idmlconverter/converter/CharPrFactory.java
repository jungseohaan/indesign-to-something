package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
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

        // CharacterStyle 이름에서 밑줄 추론 (AST에서 설정되지 않은 경우)
        if (!textRun.underline() && textRun.characterStyleRef() != null) {
            String csRef = textRun.characterStyleRef();
            if (csRef.contains("밑줄") || csRef.toLowerCase().contains("underline")) {
                textRun.underline(true);
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

        // 수식 폰트(NP_, BT수식, GREP 해석) → 밑줄 + 초록색 스타일
        if (isEquationFontCached(textRun.fontFamily()) || textRun.grepMathFont()) {
            charPrId = createEquationFontCharPr(textRun, charPrId);
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
            run.charPrIDRef(isSpace ? spaceCharPrId : charPrId);
            run.addNewT().addText(segment);
        }
    }

    /**
     * 공백용 CharPr을 생성 또는 캐시에서 가져온다.
     * 기존 CharPr과 동일하되 ratio만 spaceRatio()로 축소.
     */
    private String getOrCreateSpaceCharPr(String baseCharPrId, ASTTextRun textRun) {
        String cacheKey = "SP|" + baseCharPrId;
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
                effectiveBoldStyle(fontStyleStr, textRun.fontFamily()),
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
        // spaceCondenseRatio를 절대 목표값으로 직접 적용 (fontRatio와 독립)
        short sr = spaceRatio();
        charPr.ratio().set(sr, sr, sr, sr, sr, sr, sr);

        ctx.charPrCache.put(cacheKey, newId);
        return newId;
    }

    /**
     * 텍스트 내의 탭(\t)과 줄바꿈(\n, U+2028) 문자를 HWPX 요소로 변환.
     * 각 탭/줄바꿈은 별도의 T 요소로 분리 (한글 렌더링 호환).
     * indentToHerePosition > 0이면 lineBreak 직후 탭을 삽입하여 들여쓰기 재현.
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
                if (buf.length() > 0) {
                    run.addNewT().addText(buf.toString());
                    buf.setLength(0);
                }
                run.addNewT().addNewLineBreak();
                if (indentToHerePosition > 0) {
                    run.addNewT().addNewTab();
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

        // SPEC-031: DSL char rule 적용
        kr.dogfoot.hwpxlib.tool.idmlconverter.rule.RuleContext ruleCtx =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.rule.RuleContext(
                        currentParaStyleRef, textRun.characterStyleRef(),
                        textRun.text() != null ? textRun.text() : "");
        kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry.applyCharRule(ruleCtx);
        if (ruleCtx.targetKoFont != null) fontFamilyToUse = ruleCtx.targetKoFont;
        if (ruleCtx.targetFontSizePt != null)
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
                effectiveBoldStyle(fontStyle, textRun.fontFamily()),
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

        ctx.charPrCache.put(cacheKey, newId);
        return newId;
    }

    /**
     * IDML CharStyle 에서 fontStyle="Bold" 라고 지정해도, AppliedFont 가 실제로 Bold 변종이 없는 경우
     * InDesign 은 Medium/Regular 로 대체 렌더한다 (예: "DIN Next LT Pro (TT)" + Bold → Medium).
     * resolved.json fontMetrics 의 실제 weight 가 600 미만이면 Bold 로 강조 표시하지 않는다.
     */
    boolean effectiveBoldStyle(String fontStyle, String fontFamily) {
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

    String createEquationFontCharPr(ASTTextRun textRun, String baseCharPrId) {
        String cacheKey = baseCharPrId + "|EQ|" + (textRun.fontFamily() != null ? textRun.fontFamily() : "")
                + "|" + (textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : "")
                + "|" + (textRun.textColor() != null ? textRun.textColor() : "");
        String cached = ctx.eqFontCharPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";
        String fontStyle = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";

        CharPrBuilder.build(charPr, newId, height, textColor,
                textRun.fontFamily(), textRun.fontStyle(), ctx.fontRegistry,
                textRun.letterSpacing(),
                effectiveBoldStyle(fontStyle, textRun.fontFamily()),
                isItalicStyle(fontStyle),
                textRun.superscript(), textRun.subscript(),
                UnderlineType.NONE, textColor,
                null, // underlineShape
                null,
                false,
                null, null);

        ctx.eqFontCharPrCache.put(cacheKey, newId);
        return newId;
    }
}
