package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.MatchConfidence;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.RunPropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 런 빌드 + 매칭 + 스타일 헬퍼 (W3 Step F).
 * StoryConverter에서 분리됨.
 */
class RunBuilder {
    private RunBuilder() {}

    /** 기본 매칭 신뢰도(LOW)로 createRunFromIDML 호출 — 호환용 래퍼. */
    static ASTTextRun createRunFromIDML(ResolvedBuildContext ctx, IDMLCharacterRun cr, String text, ResolvedRun rr, StoryConverter.StyleContext sc) {
        return createRunFromIDML(ctx, cr, text, rr, sc, MatchConfidence.LOW);
    }

    static ASTTextRun createRunFromIDML(ResolvedBuildContext ctx, IDMLCharacterRun cr, String text, ResolvedRun rr, StoryConverter.StyleContext sc, MatchConfidence confidence) {
        // SPEC-016 Phase 2: 신뢰도 카운터 (모든 IDML→AST 런 생성 경로 단일 집계 지점)
        switch (confidence) {
            case HIGH: ctx.spec016Counts[0]++; break;
            case MEDIUM: ctx.spec016Counts[1]++; break;
            case LOW: ctx.spec016Counts[2]++; break;
        }
        ASTTextRun tr = new ASTTextRun();
        // 특수 제어 문자 제거
        // \u0008 = Indent to Here (ACE 7) — HWPX에 대응 없음
        // \n = Frame Break (ACE 3) — 같은 글상자 안에서 의미 없음
        // \t + \u0008 패턴: 인라인 아이콘 앞 탭+IndentToHere → 둘 다 제거
        // 단독 \t: tabStop이 있으면 유지 (HwpxParagraphBuilder가 <hp:tab>으로 변환), 없으면 공백 치환
        if (text != null) {
            text = text.replace("\t\u0008", ""); // \t + IndentToHere 조합 제거
            text = text.replace("\u0008", "");   // 단독 IndentToHere 제거
            text = text.replace("\n", "");       // Frame Break 제거
            if (!sc.hasTabStops) {
                text = text.replace("\t", " ");  // 탭 → 공백 (탭스톱 없는 경우 간격 방지)
            }
            text = text.replace("\u2009", " ");   // Thin Space → 공백
            text = text.replace("\u2002", " ");  // En Space → 공백 (단어 구분자 보존)
            text = text.replace("\u2003", " ");  // Em Space → 공백 (단어 구분자 보존)
            text = text.replace("\u200A", "");   // Hair Space 제거 (타이포 조정용, 시각상 무의미)
            text = text.replace("\uFFE3", "~");  // Fullwidth Macron → 물결 (한글 호환)
            // Yoon 폰트 (윤명조/윤고딕) 의 PUA 글리프 → 안전한 유니코드 치환
            // U+E287 = 빈 정답 칸 (□). 매핑 폰트에서 잘못된 글자 (예: 늣) 로 렌더링되는 회귀 방지.
            text = text.replace('\uE285', '\u25A1').replace('\uE287', '\u25A1').replace('\uE288', '\u25A1'); // □ White Square
            // EH상부자 overline marker: Ó(0xD3) → \uE000{letters}\uE001 마커로 치환
            // 단락 후처리(splitOverlineRuns)에서 ASTEquation overline{AB}로 변환
            if (text.indexOf('\u00D3') >= 0) {
                text = RunPostProcessor.markOverlineSegments(text);
            }
        }

        // ── 속성 적용: SPEC-012 RunPropertyResolver 사용 ──
        // 우선순위: resolved → IDML CharacterRun → ParagraphStyle → default
        // resolved는 GREP/중첩 스타일이 모두 적용된 실제 렌더링 값이므로 가장 권위 있음.

        // GREP 적용 캐릭터 스타일이 있으면 색상/글리프 매핑에 활용
        String effectiveIdmlColor = cr.fillColor();
        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef grepCharStyle = null;
        if (cr.grepAppliedCharStyle() != null && ctx.idmlDocumentSupplier.get() != null) {
            ctx.ensureIdmlInfra.run();
            if (ctx.idmlDocumentSupplier.get() != null) {
                grepCharStyle = ctx.idmlDocumentSupplier.get().charStyles().get(cr.grepAppliedCharStyle());
                if (grepCharStyle == null) {
                    String shortRef = cr.grepAppliedCharStyle();
                    if (shortRef.startsWith("CharacterStyle/")) shortRef = shortRef.substring("CharacterStyle/".length());
                    grepCharStyle = ctx.idmlDocumentSupplier.get().charStyles().get(shortRef);
                }
                if (grepCharStyle != null && grepCharStyle.fillColor() != null) {
                    effectiveIdmlColor = grepCharStyle.fillColor();
                }
            }
        }

        // EH상부자/하부자 GREP 적용 시 ASCII 글리프 매핑 (예: '_' → '×')
        // GREP으로 분리된 단일/짧은 ASCII 서브런만 영향을 받는다.
        if (text != null && grepCharStyle != null && grepCharStyle.fontFamily() != null
                && EHFontGlyphMap.isEHFontFamily(grepCharStyle.fontFamily())) {
            text = EHFontGlyphMap.applyEHGrepAsciiGlyphMap(text);
        }
        tr.text(text);

        // fontFamily / fontSize / textColor: 헬퍼로 단일 우선순위 적용
        // SPEC-016: 매칭 신뢰도(confidence)에 따라 resolved 오버라이드 여부 결정
        String resolvedFontFamily = RunPropertyResolver.resolveFontFamilyWithConfidence(
                rr, cr, sc.fontFamily, text, confidence);
        if (resolvedFontFamily != null) {
            // EH/BT/NP 수식 전용 폰트가 일반 텍스트(수식 그룹 밖)에 적용된 경우
            // → 이탤릭이면 Times New Roman (수식 변수 스타일), 아니면 ParagraphStyle 기본 폰트
            if (EHFontGlyphMap.isEHFontFamily(resolvedFontFamily)
                    || resolvedFontFamily.contains("BT수식")
                    || resolvedFontFamily.startsWith("NP_")) {
                String rrStyle = (rr != null && rr.fontStyle() != null) ? rr.fontStyle().toLowerCase() : "";
                if (rrStyle.contains("italic")) {
                    // 수식 변수 이탤릭: ParagraphStyle 기본 폰트를 유지하되
                    // fontStyle=Italic으로 이탤릭 적용 (charPr에서 처리)
                    resolvedFontFamily = sc.fontFamily;
                } else {
                    resolvedFontFamily = sc.fontFamily;
                }
            }
            tr.fontFamily(resolvedFontFamily);
        }
        Integer resolvedFontSize = RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                rr, cr, sc.fontSize, confidence);
        if (resolvedFontSize != null) {
            tr.fontSizeHwpunits(resolvedFontSize);
        }
        String resolvedColor = RunPropertyResolver.resolveTextColorHexWithConfidence(
                rr, effectiveIdmlColor, sc.fillColor, (_c) -> resolveColorToHex(ctx, _c), confidence);
        if (resolvedColor != null) {
            tr.textColor(resolvedColor);
        }

        // fontStyle: IDML CR이 EH/BT 수식 폰트를 한국어에 잘못 적용한 경우 리셋
        if (cr.fontStyle() != null) {
            String crFf = cr.fontFamily();
            boolean crIsEHOrBT = crFf != null && (EHFontGlyphMap.isEHFontFamily(crFf) || crFf.contains("BT수식"));
            if (crIsEHOrBT && text != null && EHTextClassifier.isKoreanOnly(text)) {
                // 한국어 텍스트에 EH/BT 폰트의 fontStyle 적용 안 함
            } else {
                tr.fontStyle(cr.fontStyle());
            }
        }
        // InDesign Tracking → HWPX 자간
        // 한컴돋움/한컴바탕 fallback 폰트 매핑 시: tracking 값 그대로 (e.g., -15 → -15%)
        // 명시적 매핑 폰트: tracking / 10 (e.g., -30 → -3%)
        // 한국어 폰트(한글 포함 이름)는 대부분 한컴돋움 fallback → 그대로 사용
        {
            Double trackingVal = (cr.tracking() != null && cr.tracking() != 0)
                    ? cr.tracking() : sc.tracking;
            if (trackingVal != null && trackingVal != 0) {
                // SPEC-029: IDML tracking 1/1000 em → HWPX spacing % (1/100 em). 표준 변환은 /10.
                // (이전 한국어 fontName 케이스는 × 0.5 = ×50 적용으로 자간 5배 과대 → 제거)
                tr.letterSpacing((short) Math.round(trackingVal / 10.0));
            }
        }
        // baselineShift: InDesign에서 작은 글자 + 양수 baselineShift = 위첨자,
        // 작은 글자 + 음수 baselineShift = 아래첨자 패턴을 감지하여 sup/subscript로 변환
        // resolved 우선, IDML CR fallback
        Double bsVal = (rr != null && rr.baselineShift() != null && rr.baselineShift() != 0)
                ? rr.baselineShift()
                : (cr.baselineShift() != null && cr.baselineShift() != 0 ? cr.baselineShift() : null);
        if (bsVal != null) {
            double bsPt = bsVal;
            // 인접 런의 fontSize 비교: 현재 런이 주변보다 작으면 첨자로 판별
            double curFs = (rr != null && rr.fontSize() != null && rr.fontSize() > 0) ? rr.fontSize()
                    : (cr.fontSize() != null && cr.fontSize() > 0) ? cr.fontSize() : 10.0;
            double baseFs = (sc.fontSize != null && sc.fontSize > 0) ? sc.fontSize : 10.0;
            boolean isSmallerFont = curFs < baseFs * 0.75; // 75% 이하면 첨자
            if (isSmallerFont && bsPt > 0) {
                tr.superscript(true); // 위첨자
            } else if (isSmallerFont && bsPt < 0) {
                tr.subscript(true); // 아래첨자
            } else {
                // 일반 기선 이동
                short bsPct = (short) Math.round((bsPt / curFs) * 100);
                tr.baselineShift(bsPct);
            }
        }
        // resolved에서만 가져오는 보조 속성: fontStyle / horizontalScale / underline / strikeThrough
        // (fontFamily/fontSize/textColor는 위쪽에서 RunPropertyResolver로 이미 처리됨)
        if (rr != null) {
            if (rr.fontStyle() != null) {
                String rrStyle = rr.fontStyle().toLowerCase();
                boolean isItalic = rrStyle.contains("italic") || rrStyle.contains("oblique");
                boolean isEHorBT = rr.fontFamily() != null
                        && (EHFontGlyphMap.isEHFontFamily(rr.fontFamily()) || rr.fontFamily().contains("BT수식"));
                // EH/BT 수식 폰트라도 Italic이면 적용 (GREP 이탤릭 스타일)
                // 숫자 전용 fontStyle(예: "30")은 무시
                if (!isEHorBT || isItalic) {
                    tr.fontStyle(rr.fontStyle());
                }
            }
            // horizontalScale: IDML에 없으면 resolved에서 보강
            // hs == vs인 비례 확대라도 fontSize를 키우면 baseline이 어긋나 보이므로
            // ratio에만 반영한다. (예: `+` 글자만 115% 확대인 경우 위첨자처럼 보이는 현상 방지)
            if (tr.horizontalScale() == null && rr.horizontalScale() != null
                    && rr.horizontalScale() != 0 && rr.horizontalScale() != 100) {
                tr.horizontalScale((short) rr.horizontalScale().doubleValue());
            }
            // underline / strikeThrough
            if (rr.underline() != null && rr.underline()) {
                tr.underline(true);
            }
            if (rr.strikeThru() != null && rr.strikeThru()) {
                tr.strikeThrough(true);
            }
        }
        // 색상 default 폴백 (헬퍼에서 처리되지 않은 경우)
        if (tr.textColor() == null) {
            tr.textColor("#000000");
        }
        // IDML CharacterRun의 underline/strikeThrough (resolved보다 우선)
        if (cr.underline() != null && cr.underline()) {
            tr.underline(true);
        }
        if (cr.strikeThrough() != null && cr.strikeThrough()) {
            tr.strikeThrough(true);
        }
        // IDML UnderlineType → underlineShape 매핑 (Wavy → WAVE 등)
        if (cr.underlineType() != null) {
            String ulType = cr.underlineType().toLowerCase();
            if (ulType.contains("wavy") || ulType.contains("wave")) {
                tr.underline(true);
                tr.underlineShape("WAVE");
            } else if (ulType.contains("dashed") || ulType.contains("dash")) {
                tr.underline(true);
                tr.underlineShape("DASH");
            } else if (ulType.contains("dotted") || ulType.contains("dot")) {
                tr.underline(true);
                tr.underlineShape("DOT");
            }
        }
        // CharacterStyle 이름에서 밑줄/취소선 추론
        // resolved.json이 명시적으로 false면 run 레벨 오버라이드 → 스타일 이름 휴리스틱 무시
        boolean resolvedUnderlineFalse = rr != null && rr.underline() != null && !rr.underline();
        boolean resolvedStrikeFalse = rr != null && rr.strikeThru() != null && !rr.strikeThru();
        String charStyle = cr.appliedCharacterStyle();
        if (charStyle != null) {
            if (!resolvedUnderlineFalse && (charStyle.contains("밑줄") || charStyle.toLowerCase().contains("underline"))) {
                tr.underline(true);
            }
            if (!resolvedStrikeFalse && (charStyle.contains("취소선") || charStyle.toLowerCase().contains("strikethrough"))) {
                tr.strikeThrough(true);
            }
            // CharacterStyle에서 물결 밑줄 추론
            if (charStyle.contains("물결") || charStyle.toLowerCase().contains("wavy")) {
                tr.underlineShape("WAVE");
            }
        }
        // 수식 폰트 감지는 convertMathRunsInParagraph에서 후처리
        return tr;
    }

    /** 한국어 폰트 이름 판별: 한글 문자가 포함되어 있으면 한국어 폰트 */
    static boolean isKoreanFontName(String fontName) {
        if (fontName == null) return false;
        for (int i = 0; i < fontName.length(); i++) {
            char c = fontName.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) return true;
        }
        return false;
    }

    /**
     * 불릿 문자(●, •)로 시작하는 런을 불릿 런 + 본문 런으로 분리하여 단락에 추가.
     * InDesign에서 불릿과 본문이 같은 런에 포함되면 불릿 색상이 본문에도 적용되는 문제 해결.
     * 분리 시 불릿 런은 원래 색상 유지, 본문 런은 검정(#000000)으로 리셋.
     * @return true: 분리되어 단락에 추가됨, false: 분리 불필요 (호출자가 직접 추가)
     */
    static boolean splitBulletRun(ResolvedBuildContext ctx, ASTTextRun tr, ASTParagraph para) {
        String text = tr.text();
        if (text == null || text.length() < 2) return false;

        char first = text.charAt(0);
        if (StoryConverter.BULLET_CHARS.indexOf(first) < 0) return false;

        // 불릿 뒤에 공백/탭이 있어야 분리 (단독 불릿 또는 연속 불릿 문자열은 무시)
        // 예: "□□" 빈 정답 칸 두 개는 불릿 패턴 아님 → 분리 금지.
        if (text.length() < 2) return false;
        char second = text.charAt(1);
        if (second != ' ' && second != '\t') return false;
        int splitIdx = 2; // bullet + 공백/탭

        // 단락에 불릿 플래그 설정 (이후 런의 색상 리셋용)
        para.bulletParagraph(true);

        // 불릿 런: 원래 색상, 약간 작은 크기
        ASTTextRun bulletRun = new ASTTextRun();
        bulletRun.text(String.valueOf(first));
        bulletRun.textColor(tr.textColor());
        bulletRun.fontFamily(tr.fontFamily());
        bulletRun.fontStyle(tr.fontStyle());
        if (tr.fontSizeHwpunits() != null) {
            // 불릿 크기: 본문의 50% (함초롬돋움의 ● 글리프가 크므로)
            bulletRun.fontSizeHwpunits((int) (tr.fontSizeHwpunits() * 0.5));
        }
        bulletRun.letterSpacing(tr.letterSpacing());
        para.addItem(bulletRun);

        // 구분자(탭/공백) 런
        if (splitIdx > 1) {
            ASTTextRun sepRun = new ASTTextRun();
            sepRun.text(text.substring(1, splitIdx));
            sepRun.fontFamily(tr.fontFamily());
            sepRun.fontStyle(tr.fontStyle());
            sepRun.fontSizeHwpunits(tr.fontSizeHwpunits());
            sepRun.textColor("#000000");
            para.addItem(sepRun);
        }

        // 본문 런: 검정색
        ASTTextRun bodyRun = new ASTTextRun();
        bodyRun.text(text.substring(splitIdx));
        bodyRun.fontFamily(tr.fontFamily());
        bodyRun.fontStyle(tr.fontStyle());
        bodyRun.fontSizeHwpunits(tr.fontSizeHwpunits());
        bodyRun.letterSpacing(tr.letterSpacing());
        bodyRun.textColor("#000000");
        bodyRun.baselineShift(tr.baselineShift());
        para.addItem(bodyRun);

        return true;
    }

    /**
     * 불릿 단락의 런 색상을 검정으로 리셋 (불릿 런 자체는 제외).
     * splitBulletRun이 단락 첫 런만 처리하므로, 이후 런의 색상도 리셋 필요.
     */
    static void resetBulletParagraphColors(ResolvedBuildContext ctx, ASTParagraph para) {
        if (!para.bulletParagraph()) return;
        boolean firstItem = true;
        for (Object item : para.items()) {
            if (item instanceof ASTTextRun) {
                if (firstItem) {
                    firstItem = false;
                    continue; // 불릿 런 자체는 건너뜀
                }
                ASTTextRun run = (ASTTextRun) item;
                // 불릿 색상(비검정)이면 검정으로 리셋
                if (run.textColor() != null && !run.textColor().equals("#000000")) {
                    run.textColor("#000000");
                }
            } else {
                firstItem = false;
            }
        }
    }

    /**
     * ParagraphStyle의 Leading 값을 가져옴 (pt). StylePropertyResolver에 위임.
     */
    static Double getStyleLeading(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.leading() : null;
    }

    /**
     * ParagraphStyle의 Tracking(자간) 값을 가져옴. StylePropertyResolver에 위임.
     */
    static Double getStyleTracking(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.tracking() : null;
    }

    /**
     * ParagraphStyle의 FillColor를 hex로 변환하여 반환. StylePropertyResolver에 위임.
     */
    static String getStyleFillColor(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        if (resolved != null && resolved.fillColor() != null) {
            return resolveColorToHex(ctx, resolved.fillColor());
        }
        return null;
    }

    static String getStyleFontFamily(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontFamily() : null;
    }

    static Double getStyleFontSize(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontSize() : null;
    }

    /**
     * 한국어 사이 단일 라틴 문자를 수식 변수(이탤릭)로 분리.
     * "길이를 x라고 할 때" → "길이를 " + [수식 x] + "라고 할 때"
     */
    static void splitLatinVarsInMixedText(ResolvedBuildContext ctx, ASTTextRun originalRun, ASTParagraph para) {
        String text = originalRun.text();
        if (text == null || text.isEmpty()) { para.addItem(originalRun); return; }

        // 한국어가 포함된 텍스트에서만 분리 (순수 라틴 텍스트는 그대로)
        boolean hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) { hasKorean = true; break; }
        }
        if (!hasKorean) { para.addItem(originalRun); return; }

        // 단일 라틴 문자(공백으로 둘러싸인)를 찾아 분리
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isLatinLetter = Character.isLetter(c) && c < 0x100 && !Character.isDigit(c);
            // 단일 라틴 문자: 앞뒤가 공백/한국어/숫자이고, 다음 문자가 라틴이 아님
            boolean isSingleVar = isLatinLetter
                    && (i == 0 || !Character.isLetter(text.charAt(i - 1)) || text.charAt(i - 1) >= 0x100)
                    && (i == text.length() - 1 || !Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) >= 0x100);
            if (isSingleVar) {
                // 앞 텍스트 flush
                if (buf.length() > 0) {
                    ASTTextRun before = cloneRunWithText(ctx, originalRun, buf.toString());
                    para.addItem(before);
                    buf.setLength(0);
                }
                // 수식 변수
                ASTEquation eq = new ASTEquation();
                eq.hwpScript(String.valueOf(c));
                eq.sourceType("LATIN_VAR");
                if (originalRun.textColor() != null) eq.textColor(originalRun.textColor());
                para.addItem(eq);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            ASTTextRun after = cloneRunWithText(ctx, originalRun, buf.toString());
            para.addItem(after);
        }
    }

    static ASTTextRun cloneRunWithText(ResolvedBuildContext ctx, ASTTextRun src, String text) {
        ASTTextRun tr = new ASTTextRun();
        tr.text(text);
        tr.fontFamily(src.fontFamily());
        tr.fontStyle(src.fontStyle());
        tr.fontSizeHwpunits(src.fontSizeHwpunits());
        tr.textColor(src.textColor());
        tr.letterSpacing(src.letterSpacing());
        tr.grepMathFont(src.grepMathFont());
        tr.underline(src.underline());
        tr.underlineShape(src.underlineShape());
        tr.underlineColor(src.underlineColor());
        tr.strikeThrough(src.strikeThrough());
        tr.characterStyleRef(src.characterStyleRef());
        tr.horizontalScale(src.horizontalScale());
        tr.verticalScale(src.verticalScale());
        tr.baselineShift(src.baselineShift());
        return tr;
    }

    /** 텍스트에 minLen자 이상 연속 라틴 문자(영단어)가 포함되어 있는지 확인. */
    static boolean containsLongLatinWord(String text, int minLen) {
        if (text == null) return false;
        int streak = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c < 0x100) {
                streak++;
                if (streak >= minLen) return true;
            } else {
                streak = 0;
            }
        }
        return false;
    }

    /**
     * 색상 이름/CMYK 문자열을 hex RGB로 변환.
     * IDML 스와치 이름("Color/홀수_1단원_MD") 또는 CMYK 문자열("C=0 M=0 Y=0 K=70") 지원.
     */
    static String resolveColorToHex(ResolvedBuildContext ctx, String color) {
        if (color == null) return null;
        // 이미 hex (#RRGGBB 또는 #RGB 형식: # 뒤가 모두 hex 문자)
        if (color.startsWith("#") && color.length() >= 4 && color.substring(1).matches("[0-9a-fA-F]+")) {
            return color;
        }
        // # 접두사가 있지만 hex가 아닌 스와치 이름 (예: "#활동 번호 색") → 이름으로 조회
        if (color.startsWith("#")) {
            String hex = ctx.resolvedData.resolveColorHex(color);
            if (hex != null) return hex;
            // # 제거 후 재조회
            hex = ctx.resolvedData.resolveColorHex(color.substring(1));
            if (hex != null) return hex;
        }
        // IDML 스와치: "Color/Paper" → "Paper"
        String name = color.startsWith("Color/") ? color.substring(6) : color;
        // resolvedData에서 조회
        String hex = ctx.resolvedData.resolveColorHex(name);
        if (hex != null) return hex;
        // IDML color ID (예: "Color/u1fc", "u1fc") → colorResolver로 해석
        ctx.ensureIdmlInfra.run();
        if (ctx.colorResolverSupplier.get() != null) {
            String crHex = ctx.colorResolverSupplier.get().resolve(color);
            if (crHex != null) return crHex;
            crHex = ctx.colorResolverSupplier.get().resolve("Color/" + name);
            if (crHex != null) return crHex;
            crHex = ctx.colorResolverSupplier.get().resolve(name);
            if (crHex != null) return crHex;
        }
        // CMYK 문자열 파싱: "C=0 M=15 Y=80 K=0"
        if (name.contains("C=") && name.contains("M=")) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("C=(\\d+\\.?\\d*)\\s+M=(\\d+\\.?\\d*)\\s+Y=(\\d+\\.?\\d*)\\s+K=(\\d+\\.?\\d*)")
                        .matcher(name);
                if (m.find()) {
                    double c = Double.parseDouble(m.group(1)) / 100.0;
                    double mm = Double.parseDouble(m.group(2)) / 100.0;
                    double y = Double.parseDouble(m.group(3)) / 100.0;
                    double k = Double.parseDouble(m.group(4)) / 100.0;
                    int r = (int) (255 * (1 - c) * (1 - k));
                    int g = (int) (255 * (1 - mm) * (1 - k));
                    int b = (int) (255 * (1 - y) * (1 - k));
                    return String.format("#%02X%02X%02X", r, g, b);
                }
            } catch (Exception e) {}
        }
        return null;
    }

    /**
     * resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 선택.
     * 불릿(●, ▪ 등 1~2자)이나 특수문자 런이 아닌 본문 런을 우선 선택.
     */
    static ResolvedRun findDefaultResolvedRun(ResolvedBuildContext ctx, List<ResolvedRun> runs) {
        if (runs == null || runs.isEmpty()) return null;
        ResolvedRun longest = null;
        int maxLen = 0;
        for (ResolvedRun r : runs) {
            String text = r.text();
            if (text == null) continue;
            String trimmed = text.replace("\uFFFC", "").trim();
            if (trimmed.length() > maxLen) {
                maxLen = trimmed.length();
                longest = r;
            }
        }
        return longest != null ? longest : runs.get(0);
    }

    /** SPEC-016: 분할 세그먼트 정보 (텍스트 + 매칭된 resolved 런 + 매칭 신뢰도). */
    static class Segment {
        final String text;
        final int rrIdx;
        final MatchConfidence confidence;

        Segment(String text, int rrIdx, MatchConfidence confidence) {
            this.text = text;
            this.rrIdx = rrIdx;
            this.confidence = confidence;
        }
    }

    static boolean splitIdmlRunByResolvedRuns(ResolvedBuildContext ctx, IDMLCharacterRun cr, String text,
            List<ResolvedRun> resolvedRuns, int startIdx,
            ASTParagraph para, StoryConverter.StyleContext sc) {
        if (text == null || text.isEmpty() || resolvedRuns == null) return false;

        // resolved 런에서 이 텍스트와 겹치는 연속 런들을 찾기
        // 텍스트 시작부터 순차적으로 resolved 런 텍스트를 매칭
        String remaining = text;
        List<Segment> segments = new ArrayList<>();
        int rIdx = startIdx;
        boolean foundSplit = false;

        while (!remaining.isEmpty() && rIdx < resolvedRuns.size()) {
            ResolvedRun rr = resolvedRuns.get(rIdx);
            String rrText = rr.text();
            if (rrText == null || rrText.isEmpty()) { rIdx++; continue; }

            // resolved 런 텍스트가 remaining의 접두사인지 확인
            // 특수 공백(Figure Space \u2007 등)을 일반 공백으로 정규화하여 비교
            String normRemaining = normalizeSpaces(remaining);
            String normRRText = normalizeSpaces(rrText);
            if (normRemaining.startsWith(normRRText)) {
                // 정규화 후 정확 접두사 매칭 → HIGH (특수 공백 포함 원문 raw 매칭도 이 경로로 처리됨)
                int cutLen = findOriginalLength(remaining, normRRText.length());
                segments.add(new Segment(remaining.substring(0, cutLen), rIdx, MatchConfidence.HIGH));
                remaining = remaining.substring(cutLen);
                rIdx++;
            } else if (rrText.length() > 0 && remaining.startsWith(rrText.substring(0, Math.min(3, rrText.length())))) {
                // 부분 매칭: 앞 3자만 일치 → 다음 런 키워드로 분할
                // 성공하면 MEDIUM, 분할 실패 시 LOW
                if (rIdx + 1 < resolvedRuns.size()) {
                    ResolvedRun nextRR = resolvedRuns.get(rIdx + 1);
                    String nextText = nextRR.text();
                    if (nextText != null && nextText.length() >= 3) {
                        String nextKey = nextText.substring(0, Math.min(5, nextText.length()));
                        int splitPos = remaining.indexOf(nextKey);
                        if (splitPos > 0) {
                            segments.add(new Segment(remaining.substring(0, splitPos), rIdx, MatchConfidence.MEDIUM));
                            remaining = remaining.substring(splitPos);
                            rIdx++;
                            foundSplit = true;
                            continue;
                        }
                    }
                }
                // 분할 실패: 남은 텍스트 전체를 LOW로 처리
                segments.add(new Segment(remaining, rIdx, MatchConfidence.LOW));
                remaining = "";
                rIdx++;
            } else {
                rIdx++; // 매칭 실패 → 다음 런 시도
            }
        }
        if (!remaining.isEmpty()) {
            // 루프 탈출 후 남은 텍스트: 매칭 없음 → LOW
            segments.add(new Segment(remaining, Math.max(0, rIdx - 1), MatchConfidence.LOW));
        }

        // 분할이 없으면(세그먼트 1개) 기존 로직 사용
        if (segments.size() <= 1 && !foundSplit) return false;

        // 각 세그먼트별로 ASTTextRun 생성 (confidence 전달)
        for (Segment seg : segments) {
            ResolvedRun rr = (seg.rrIdx >= 0 && seg.rrIdx < resolvedRuns.size())
                    ? resolvedRuns.get(seg.rrIdx) : null;
            ResolvedRun effectiveRr = rr != null ? rr : findDefaultResolvedRun(ctx, resolvedRuns);
            // findDefaultResolvedRun 폴백은 신뢰도 강등
            MatchConfidence effConf = (rr != null) ? seg.confidence : MatchConfidence.LOW;

            // SPEC-016 Phase 2: LOW 진단 — 마커 텍스트가 포함되면 컨텍스트 덤프
            if (effConf == MatchConfidence.LOW && StoryConverter.SPEC016_DEBUG_TEXT != null
                    && seg.text != null && seg.text.contains(StoryConverter.SPEC016_DEBUG_TEXT)) {
                System.err.println("[SPEC-016 LOW] segment=\"" + seg.text + "\""
                        + " idmlCRtext=\"" + text + "\""
                        + " rrIdx=" + seg.rrIdx
                        + " resolvedRuns(" + resolvedRuns.size() + ")=");
                for (int i = 0; i < resolvedRuns.size(); i++) {
                    ResolvedRun dbg = resolvedRuns.get(i);
                    System.err.println("  rr[" + i + "] text=\""
                            + (dbg.text() == null ? "<null>" : dbg.text())
                            + "\" font=" + dbg.fontFamily()
                            + " size=" + dbg.fontSize()
                            + " color=" + dbg.fillColor());
                }
            }

            ASTTextRun tr = createRunFromIDML(ctx, cr, seg.text, effectiveRr, sc, effConf);
            if (!splitBulletRun(ctx, tr, para)) {
                splitLatinVarsInMixedText(ctx, tr, para);
            }
        }
        return true;
    }

    /** resolved 런 간 스타일(색상, 폰트, fontStyle) 차이가 있는지 확인 */
    static boolean hasStyleVariation(ResolvedBuildContext ctx, List<ResolvedRun> runs) {
        if (runs == null || runs.size() <= 1) return false;
        String firstColor = null, firstFont = null, firstStyle = null;
        boolean initialized = false;
        for (ResolvedRun rr : runs) {
            if (rr.text() == null || rr.text().isEmpty()) continue;
            String color = rr.fillColor();
            String font = rr.fontFamily();
            String style = rr.fontStyle();
            if (!initialized) {
                firstColor = color; firstFont = font; firstStyle = style;
                initialized = true;
            } else {
                if (!eq(color, firstColor) || !eq(font, firstFont) || !eq(style, firstStyle)) return true;
            }
        }
        return false;
    }

    static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /** 특수 공백(Figure Space, En/Em Space 등)을 일반 공백으로 정규화 */
    static String normalizeSpaces(String s) {
        if (s == null) return "";
        return s.replace('\u2007', ' ').replace('\u2002', ' ').replace('\u2003', ' ')
                .replace('\u2009', ' ').replace('\u200A', ' ').replace('\u00A0', ' ');
    }

    /** 정규화된 길이에 대응하는 원본 문자열의 실제 길이 (1:1 매핑이므로 동일) */
    static int findOriginalLength(String original, int normalizedLen) {
        return Math.min(normalizedLen, original.length());
    }

    static ResolvedRun findResolvedRun(ResolvedBuildContext ctx, List<ResolvedRun> runs, int startIdx, String text) {
        if (runs == null || runs.isEmpty() || text == null || text.isEmpty()) return null;
        String key = text.length() > 5 ? text.substring(0, 5) : text;
        // startIdx부터 순차 검색
        for (int i = startIdx; i < runs.size(); i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) {
                ctx.lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        // 앞쪽 소수 런만 역방향 재탐색 (O(n²) 방지: 전체 restart 대신 최대 8칸 window)
        int backWindow = Math.max(0, startIdx - 8);
        for (int i = backWindow; i < startIdx; i++) {
            String rt = runs.get(i).text();
            if (rt != null && rt.contains(key)) {
                ctx.lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        return null;
    }
}
