package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.MatchConfidence;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.RunPropertyResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextControlNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextStyleApplicator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 3 런 빌드 + 매칭 + 스타일 헬퍼 (W3 Step F).
 * StoryConverter에서 분리됨.
 */
class RunBuilder {
    private RunBuilder() {}

    private static final Set<String> CHEMICAL_ELEMENT_SYMBOLS = new HashSet<>(Arrays.asList(
            "H","He","Li","Be","B","C","N","O","F","Ne",
            "Na","Mg","Al","Si","P","S","Cl","Ar","K","Ca",
            "Sc","Ti","V","Cr","Mn","Fe","Co","Ni","Cu","Zn",
            "Ga","Ge","As","Se","Br","Kr","Rb","Sr","Y","Zr",
            "Nb","Mo","Tc","Ru","Rh","Pd","Ag","Cd","In","Sn",
            "Sb","Te","I","Xe","Cs","Ba","La","Ce","Pr","Nd",
            "Pm","Sm","Eu","Gd","Tb","Dy","Ho","Er","Tm","Yb",
            "Lu","Hf","Ta","W","Re","Os","Ir","Pt","Au","Hg",
            "Tl","Pb","Bi","Po","At","Rn","Fr","Ra","Ac","Th",
            "Pa","U","Np","Pu","Am","Cm","Bk","Cf","Es","Fm",
            "Md","No","Lr","Rf","Db","Sg","Bh","Hs","Mt","Ds",
            "Rg","Cn","Nh","Fl","Mc","Lv","Ts","Og"
    ));

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
        // \u0003/\n = Frame Break (ACE 3), \u0007/\u0008 = Indent to Here (ACE 7) — HWPX에 대응 없음
        // \t + \u0008 패턴: 인라인 아이콘 앞 탭+IndentToHere → 둘 다 제거
        // 단독 \t: tabStop이 있으면 유지 (HwpxParagraphBuilder가 <hp:tab>으로 변환), 없으면 공백 치환
        if (text != null) {
            boolean preserveUnderlineBlank = hasUnderlineIntent(cr, rr);
            text = TextControlNormalizer.normalize(
                    text,
                    sc.hasTabStops,
                    preserveUnderlineBlank,
                    true);
        }

        // ── 속성 적용: SPEC-012 RunPropertyResolver 사용 ──
        // 우선순위: resolved → IDML CharacterRun → ParagraphStyle → default
        // resolved는 GREP/중첩 스타일이 모두 적용된 실제 렌더링 값이므로 가장 권위 있음.

        // GREP 적용 캐릭터 스타일이 있으면 색상/글리프 매핑에 활용
        String effectiveIdmlColor = cr.fillColor();
        Double effectiveIdmlTint = cr.fillTint();
        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef grepCharStyle = null;
        if (cr.grepAppliedCharStyle() != null && ctx.styleResolver != null) {
            grepCharStyle = ctx.styleResolver.getResolvedCharacterStyle(cr.grepAppliedCharStyle());
            if (grepCharStyle == null) {
                String shortRef = cr.grepAppliedCharStyle();
                if (shortRef.startsWith("CharacterStyle/")) {
                    shortRef = shortRef.substring("CharacterStyle/".length());
                }
                grepCharStyle = ctx.styleResolver.getResolvedCharacterStyle(shortRef);
            }
            if (grepCharStyle != null && grepCharStyle.fillColor() != null) {
                effectiveIdmlColor = grepCharStyle.fillColor();
                // GREP character styles often override only FillColor. If the concrete
                // CharacterStyleRange carries FillTint, keep it; otherwise tint=0 labels
                // lose their white/washed rendering and become the raw swatch color.
            }
        }
        if (grepCharStyle != null) {
            tr.grepStyleApplied(true);
        }

        // SPEC-065: 실제 문자 스타일(@색자 등)이 적용된 런의 IDML 색은 resolved DOM 보다
        // 권위다. IDML 조판 의미상 로컬 오버라이드 없는 charStyle 색이 확정 렌더 색인데,
        // DOM 은 이런 런의 색을 이웃/본문색으로 오보고할 수 있다 (영어 u1 p15
        // "stay in shape": IDML=@색자 마젠타 #D7157E, DOM=본문 K=60 #757877 누출).
        // GREP 재정의가 있으면 resolved 가 더 정확할 수 있으므로 제외.
        boolean appliedCharStyleColorAuthoritative = false;
        boolean hasRealCharStyle = cr.appliedCharacterStyle() != null
                && !cr.appliedCharacterStyle().contains("[No character style]");
        if (effectiveIdmlColor != null
                && hasRealCharStyle
                && grepCharStyle == null
                && rr != null && rr.fillColor() != null) {
            String idmlHex = resolveColorToHex(ctx, effectiveIdmlColor);
            String rrHex = resolveColorToHex(ctx, rr.fillColor());
            if (idmlHex != null && rrHex != null && !rrHex.equalsIgnoreCase(idmlHex)) {
                appliedCharStyleColorAuthoritative = true;
            }
        }
        // 로컬 FillColor 도 없고 파서가 스타일 색을 주입하지 못한 변형: 적용 문자
        // 스타일 정의에서 직접 색을 가져온다.
        if (effectiveIdmlColor == null
                && hasRealCharStyle
                && ctx.styleResolver != null) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef appliedCharStyle =
                    ctx.styleResolver.getResolvedCharacterStyle(cr.appliedCharacterStyle());
            if (appliedCharStyle == null) {
                String shortRef = cr.appliedCharacterStyle();
                if (shortRef.startsWith("CharacterStyle/")) {
                    shortRef = shortRef.substring("CharacterStyle/".length());
                }
                appliedCharStyle = ctx.styleResolver.getResolvedCharacterStyle(shortRef);
            }
            if (appliedCharStyle != null && appliedCharStyle.fillColor() != null) {
                effectiveIdmlColor = appliedCharStyle.fillColor();
                appliedCharStyleColorAuthoritative = true;
            }
        }

        String characterStyleRef = resolvedCharacterStyleRef(rr, cr, confidence);

        String arrowFont = rr != null ? rr.fontFamily() : null;
        if (arrowFont == null && cr != null) arrowFont = cr.fontFamily();
        if (arrowFont == null && grepCharStyle != null) arrowFont = grepCharStyle.fontFamily();
        text = BTFontGlyphMap.normalizeArrowGlyphText(arrowFont, characterStyleRef, text);

        // EH상부자/하부자 GREP 적용 시 ASCII 글리프 매핑 (예: '_' → '×')
        // GREP으로 분리된 단일/짧은 ASCII 서브런만 영향을 받는다.
        if (text != null && grepCharStyle != null && grepCharStyle.fontFamily() != null
                && EHFontGlyphMap.isEHFontFamily(grepCharStyle.fontFamily())) {
            text = EHFontGlyphMap.applyEHGrepAsciiGlyphMap(text);
        }
        // EH 폰트 해킹 글리프가 EH 수식 그룹에 못 들어간 채 일반 텍스트로 흘러오면
        // raw 라틴 문자로 남는다(실측: 수학 5단원). 공통 진입점에서 디코딩한다.
        String ehFont = rr != null ? rr.fontFamily() : null;
        if (ehFont == null && cr != null) ehFont = cr.fontFamily();
        text = EHFontGlyphMap.decodeStrayGlyphText(text, ehFont);
        // EH thin space 마커 백틱(`)이 일반 텍스트로 새면 콤마·따옴표처럼 노출된다.
        // 숫자·연산자 사이 백틱만 가는 공백으로 치환(실측: 1단원 1`:`√2, 290`K).
        text = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTRunConverter
                .replaceEHThinSpaceBacktick(text);
        tr.text(text);
        if (characterStyleRef != null) {
            tr.characterStyleRef(characterStyleRef);
        }

        // fontFamily / fontSize / textColor: 헬퍼로 단일 우선순위 적용
        // SPEC-016: 매칭 신뢰도(confidence)에 따라 resolved 오버라이드 여부 결정
        String resolvedFontFamily = grepCharStyle != null && grepCharStyle.fontFamily() != null
                ? grepCharStyle.fontFamily()
                : RunPropertyResolver.resolveFontFamilyWithConfidence(
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
        // 삼항 오토언박싱 주의: true 분기가 primitive int 면 Java 가 양쪽을 int 로
        // 통일하려 false 분기의 Integer 를 언박싱한다 → null 이면 여기서 NPE.
        // (int) 대신 (Integer) 로 박싱해 삼항 전체를 Integer 로 유지, 아래 null 체크 유효화.
        Integer resolvedFontSize = grepCharStyle != null && grepCharStyle.fontSize() != null
                ? (Integer) (int) CoordinateConverter.pointsToHwpunits(grepCharStyle.fontSize())
                : RunPropertyResolver.resolveFontSizeHwpunitsWithConfidence(
                        rr, cr, sc.fontSize, confidence);
        if (resolvedFontSize != null) {
            tr.fontSizeHwpunits(resolvedFontSize);
        }
        String resolvedColor;
        if (appliedCharStyleColorAuthoritative) {
            // SPEC-065: 로컬 FillColor 없는 런의 적용 문자 스타일 색은 resolved 보다 권위 —
            // resolved 는 이런 런에서 본문색을 흘려보낸다 (DOM 오보고).
            resolvedColor = effectiveIdmlTint != null
                    ? resolveColorToHex(ctx, effectiveIdmlColor, effectiveIdmlTint)
                    : resolveColorToHex(ctx, effectiveIdmlColor);
            if (resolvedColor == null) {
                resolvedColor = RunPropertyResolver.resolveTextColorHexWithConfidence(
                        rr, effectiveIdmlColor, effectiveIdmlTint, sc.fillColor,
                        (_c) -> resolveColorToHex(ctx, _c),
                        (_c, _t) -> resolveColorToHex(ctx, _c, _t),
                        confidence);
            }
        } else {
            resolvedColor = RunPropertyResolver.resolveTextColorHexWithConfidence(
                    rr,
                    effectiveIdmlColor,
                    effectiveIdmlTint,
                    sc.fillColor,
                    (_c) -> resolveColorToHex(ctx, _c),
                    (_c, _t) -> resolveColorToHex(ctx, _c, _t),
                    confidence);
        }
        if (resolvedColor != null) {
            tr.textColor(resolvedColor);
        }
        String resolvedShadeColor = resolveCharacterShadeColor(ctx, cr, grepCharStyle);
        if (resolvedShadeColor != null) {
            tr.shadeColor(resolvedShadeColor);
        }

        String resolvedFontStyle = grepCharStyle != null && grepCharStyle.fontStyle() != null
                ? grepCharStyle.fontStyle()
                : RunPropertyResolver.resolveFontStyleWithConfidence(
                        rr, cr, sc.fontStyle, confidence);
        if (resolvedFontStyle != null) {
            // 이탤릭 적용 여부는 "원본" 폰트(EH/BT 여부)로 판단해야 한다. resolvedFontFamily
            // 는 위에서 이미 본문 폰트로 교체됐을 수 있어 EH 판정이 사라진다. rr/cr 우선.
            String originFontFamily = (rr != null && rr.fontFamily() != null) ? rr.fontFamily()
                    : (cr.fontFamily() != null ? cr.fontFamily() : resolvedFontFamily);
            if (shouldApplyFontStyle(originFontFamily, resolvedFontStyle, text)) {
                tr.fontStyle(resolvedFontStyle);
            }
        }
        // InDesign Tracking → HWPX 자간
        // 한컴돋움/한컴바탕 fallback 폰트 매핑 시: tracking 값 그대로 (e.g., -15 → -15%)
        // 명시적 매핑 폰트: tracking / 10 (e.g., -30 → -3%)
        // 한국어 폰트(한글 포함 이름)는 대부분 한컴돋움 fallback → 그대로 사용
        {
            Double trackingVal = grepCharStyle != null && grepCharStyle.tracking() != null
                    ? grepCharStyle.tracking()
                    : resolveTracking(cr, rr, sc, confidence);
            if (trackingVal != null && trackingVal != 0) {
                // resolved spacing policy: IDML tracking 1/1000 em → HWPX spacing % (1/100 em). 표준 변환은 /10.
                // (이전 한국어 fontName 케이스는 × 0.5 = ×50 적용으로 자간 5배 과대 → 제거)
                tr.letterSpacing((short) Math.round(trackingVal / 10.0));
            }
        }
        {
            Double horizontalScaleVal = resolveHorizontalScale(ctx, cr, rr, grepCharStyle, sc, confidence);
            if (horizontalScaleVal != null) {
                tr.horizontalScale((short) Math.round(horizontalScaleVal));
            }
        }
        if (isMarkerOnlyText(text) && rr != null) {
            // Marker-only glyphs such as glossary bullets are visual symbols,
            // not editable prose. Preserve resolved marker scale so replacement
            // HWPX fonts do not enlarge the symbol relative to the source.
            applyMarkerScaleFromResolved(tr, rr);
        }
        // IDML Position/Resolved position is the authoritative source for
        // chemical-formula subscripts and mathematical superscripts. Some
        // sources encode this as Position=SUBSCRIPT without a baselineShift,
        // so the baselineShift heuristic below is not sufficient on its own.
        applyPositionStyle(tr, rr, cr);
        // SPEC-055: 첨자 증거가 IDML charStyle 이름(첨자-하부자 등)에만 있는 런.
        // resolved 가 reliable+charStyle 없음이면 resolvedCharacterStyleRef 가 IDML
        // charStyle 을 폐기해 AST 에 아무 증거도 안 남는다 (실측: N₂H₄ 문장).
        // 문자속성은 바꾸지 않고 화학식 세그먼트 앵커용 힌트만 남긴다.
        if (!tr.subscript() && !tr.superscript()
                && tr.droppedResolvedScriptPosition() == null
                && cr != null && isScriptCharacterStyle(cr.appliedCharacterStyle())) {
            String cs = cr.appliedCharacterStyle().toLowerCase(Locale.ROOT)
                    .replace("%3a", ":").replace("%25", "%");
            if (!cs.contains("정체") && !cs.contains("정자")) {
                if (cs.contains("하부자") || cs.contains("아래첨자") || cs.contains("subscript")) {
                    tr.droppedResolvedScriptPosition("subscript");
                } else if (cs.contains("상부자") || cs.contains("위첨자") || cs.contains("superscript")) {
                    tr.droppedResolvedScriptPosition("superscript");
                }
            }
        }
        // baselineShift: InDesign에서 작은 글자 + 양수 baselineShift = 위첨자,
        // 작은 글자 + 음수 baselineShift = 아래첨자 패턴을 감지하여 sup/subscript로 변환
        // resolved 우선, IDML CR fallback
        Double bsVal = (rr != null && rr.baselineShift() != null && rr.baselineShift() != 0)
                ? rr.baselineShift()
                : (cr.baselineShift() != null && cr.baselineShift() != 0 ? cr.baselineShift() : null);
        if (bsVal != null && !tr.subscript() && !tr.superscript()) {
            double bsPt = bsVal;
            // 인접 런의 fontSize 비교: 현재 런이 주변보다 작으면 첨자로 판별
            double curFs = (rr != null && rr.fontSize() != null && rr.fontSize() > 0) ? rr.fontSize()
                    : (cr.fontSize() != null && cr.fontSize() > 0) ? cr.fontSize() : 10.0;
            double baseFs = (sc.fontSize != null && sc.fontSize > 0) ? sc.fontSize : 10.0;
            boolean isSmallerFont = curFs < baseFs * 0.75; // 75% 이하면 첨자
            // 첨자 후보는 숫자·라틴 1~2글자(화학식 H₂O의 2, 지수 x²)이지 한글 단어가
            // 아니다. baselineShift 가 미세한(폰트의 ~12% 미만) 작은 한글 캡션이 첨자로
            // 오판되던 문제 방지(실측: 과학 2단원 "지구의 기온"·"스스로 평가하기"가
            // bs≈-0.2pt/7.7pt 로 아래첨자화). 명시적 position=SUBSCRIPT 화학식은 위쪽
            // applyPositionStyle 에서 이미 처리되므로 이 가드의 영향을 받지 않는다.
            boolean plausibleScriptToken = isPlausibleScriptToken(text)
                    && Math.abs(bsPt) >= curFs * 0.12;
            if (isSmallerFont && plausibleScriptToken && bsPt > 0) {
                tr.superscript(true); // 위첨자
            } else if (isSmallerFont && plausibleScriptToken && bsPt < 0) {
                tr.subscript(true); // 아래첨자
            } else {
                // 일반 기선 이동
                short bsPct = (short) Math.round((bsPt / curFs) * 100);
                tr.baselineShift(bsPct);
            }
        }
        // resolved에서만 가져오는 보조 속성: underline / strikeThrough
        // (fontFamily/fontStyle/fontSize/textColor는 위쪽에서 RunPropertyResolver로 이미 처리됨)
        if (rr != null) {
            // horizontalScale is an IDML text style property. Resolved-only
            // scale can include frame/group render scale and must not be
            // injected into editable text runs.
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
        if ((!isReliableResolvedRun(rr, confidence) || rr.underline() == null)
                && cr.underline() != null && cr.underline()) {
            tr.underline(true);
        }
        if (tr.underline() && tr.underlineColor() == null && sc.underlineColor != null) {
            tr.underlineColor(sc.underlineColor);
        }
        if ((!isReliableResolvedRun(rr, confidence) || rr.strikeThru() == null)
                && cr.strikeThrough() != null && cr.strikeThrough()) {
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

    /**
     * baselineShift 휴리스틱으로 첨자화해도 되는 토큰인지.
     * 첨자(위/아래)는 화학식/이온의 숫자(H₂O의 2), 수학 지수(x²), 라틴 변수처럼
     * 짧은 숫자·라틴이다. 한글이 섞였거나 길면(캡션·라벨) 첨자가 아니다.
     */
    private static boolean isPlausibleScriptToken(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 3) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '+' || c == '-' || c == '(' || c == ')';
            if (!ok) return false;
        }
        return true;
    }

    private static void applyPositionStyle(ASTTextRun target, ResolvedRun rr, IDMLCharacterRun cr) {
        // SPEC-053: IDML/resolved 가 position 을 명시적으로 "첨자 아님"으로 보고했으면
        // 표시해 둔다. NORMAL 또는 OpentypePositionFromBaseline(폰트 자체가 위치를
        // 정의 — 상부자 폰트로 도형 라벨을 조판한 경우) 둘 다 첨자가 아니다. 하류
        // applyPositionFromCharacterStyle 의 이름-폴백이 이 런을 위첨자화하지 못하게 한다
        // (실측: u5 p174 원 O·□ABCD 가 상부자 폰트명 때문에 위첨자로 깨짐).
        String rawResolvedPos = rr != null ? rr.position() : null;
        String rawIdmlPos = cr != null ? cr.position() : null;
        if (isNonScriptPosition(rawResolvedPos) || isNonScriptPosition(rawIdmlPos)) {
            target.explicitNormalPosition(true);
        }
        String idmlPosition = explicitPosition(rawIdmlPos);
        String resolvedPosition = explicitPosition(rawResolvedPos);

        // resolved DOM 이 첨자 위치를 인접 문자로 흘리는 데이터가 있다. 화학 반응식
        // 생성물 "2H₂O" 에서 계수 2·hairspace 까지 SUBSCRIPT 로 보고해, 텍스트만 같은
        // 계수 2 가 진짜 첨자 2(SUBSCRIPT rr)로 오매칭되면 계수가 아래첨자로 깨진다
        // (실측: 과학 u1 pg25/26/28 검정 생성물 박스, IDML 원본은 계수=00_영문 +
        // Position=None). IDML CR 이 "명시적으로 비첨자"(position=normal/null +
        // 첨자 계열 charStyle 아님)인데 resolved 만 script 를 주장하면 무시한다.
        if (resolvedPosition != null && idmlPosition == null
                && cr != null && !isScriptCharacterStyle(cr.appliedCharacterStyle())) {
            // SPEC-055: 폐기하되 힌트로 남긴다. Position 속성만으로 조판된 화학식
            // 첨자(p18 캡션의 N₂/H₂ — IDML charStyle 없음)는 이 가드에 걸리지만,
            // 화학식 세그먼트 수식화(convertSubscriptChemicalSegments)의 "직전
            // 원소기호" 앵커가 SPEC-045 계수 케이스와 구분해 안전하게 살릴 수 있다.
            String rp = resolvedPosition.toLowerCase(Locale.ROOT);
            if (rp.contains("subscript")) target.droppedResolvedScriptPosition("subscript");
            else if (rp.contains("superscript")) target.droppedResolvedScriptPosition("superscript");
            resolvedPosition = null;
        }

        String position = resolvedPosition != null ? resolvedPosition : idmlPosition;
        if (position == null) return;
        String p = position.toLowerCase(Locale.ROOT);
        if (p.contains("superscript")) {
            target.superscript(true);
            target.subscript(false);
        } else if (p.contains("subscript")) {
            target.subscript(true);
            target.superscript(false);
        }
    }

    /** 문자 스타일 이름이 첨자(상부자/하부자/super/subscript)를 표기하는가. */
    static boolean isScriptCharacterStyle(String charStyleRef) {
        if (charStyleRef == null) return false;
        String s = charStyleRef.toLowerCase(Locale.ROOT);
        return s.contains("상부자") || s.contains("하부자")
                || s.contains("위첨자") || s.contains("아래첨자")
                || s.contains("superscript") || s.contains("subscript");
    }

    private static String explicitPosition(String position) {
        if (position == null || position.trim().isEmpty()) return null;
        String p = position.toLowerCase(Locale.ROOT);
        return p.contains("normal") ? null : position;
    }

    /** position 이 명시적으로 "첨자 아님"인가 (NORMAL / OpentypePositionFromBaseline). */
    private static boolean isNonScriptPosition(String position) {
        if (position == null || position.trim().isEmpty()) return false;
        String p = position.toLowerCase(Locale.ROOT);
        return p.contains("normal") || p.contains("frombaseline");
    }

    private static String resolvedCharacterStyleRef(ResolvedRun rr, IDMLCharacterRun cr,
                                                    MatchConfidence confidence) {
        String grepStyle = cr != null ? cr.grepAppliedCharStyle() : null;
        if (isUsableCharacterStyleRef(grepStyle)) return grepStyle;
        String rrStyle = rr != null ? rr.charStyle() : null;
        String crStyle = cr != null ? cr.appliedCharacterStyle() : null;
        // resolved 가 첨자 charStyle(하부자/상부자)을 붙였는데 IDML CR 은 비첨자
        // charStyle 이면, resolved 첨자 위치 흘림에 의한 오매칭이다(applyPositionStyle
        // 가드와 같은 원리 — 화학 반응식 계수 2 가 이웃 첨자 2 의 스타일을 물려받아
        // 하류 applyPositionFromCharacterStyle 에서 다시 첨자화되던 문제). IDML 을 쓴다.
        if (isScriptCharacterStyle(rrStyle) && crStyle != null && !isScriptCharacterStyle(crStyle)) {
            return isUsableCharacterStyleRef(crStyle) ? crStyle : null;
        }
        if (isUsableCharacterStyleRef(rrStyle)) return rrStyle;
        if (isReliableResolvedRun(rr, confidence)) return null;
        if (isUsableCharacterStyleRef(crStyle)) return crStyle;
        return null;
    }

    private static boolean isUsableCharacterStyleRef(String style) {
        if (style == null) return false;
        String trimmed = style.trim();
        return !trimmed.isEmpty()
                && !"[None]".equals(trimmed)
                && !"[없음]".equals(trimmed)
                && !"CharacterStyle/$ID/[No character style]".equals(trimmed);
    }

    private static String normalizeComparableText(String text) {
        if (text == null) return "";
        return text
                .replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .trim();
    }

    static boolean hasUnderlineIntent(IDMLCharacterRun cr, ResolvedRun rr) {
        if (rr != null && Boolean.TRUE.equals(rr.underline())) return true;
        if (cr != null && Boolean.TRUE.equals(cr.underline())) return true;
        String charStyle = cr != null ? cr.appliedCharacterStyle() : null;
        if (charStyle != null) {
            String lower = charStyle.toLowerCase();
            if (charStyle.contains("밑줄") || lower.contains("underline")) return true;
        }
        String resolvedCharStyle = rr != null ? rr.charStyle() : null;
        if (resolvedCharStyle != null) {
            String lower = resolvedCharStyle.toLowerCase();
            return resolvedCharStyle.contains("밑줄") || lower.contains("underline");
        }
        return false;
    }

    static String underlineBlankUnit() {
        return TextControlNormalizer.underlineBlankUnit();
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

        // 불릿 런: 원본 run의 크기와 색상을 그대로 유지한다.
        ASTTextRun bulletRun = new ASTTextRun();
        bulletRun.text(String.valueOf(first));
        bulletRun.textColor(tr.textColor());
        bulletRun.fontFamily(tr.fontFamily());
        bulletRun.fontStyle(tr.fontStyle());
        if (tr.fontSizeHwpunits() != null) {
            bulletRun.fontSizeHwpunits(tr.fontSizeHwpunits());
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
                if (run.grepStyleApplied()) {
                    continue;
                }
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

    static String getStyleFontStyle(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontStyle() : null;
    }

    static Double getStyleFontSize(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.fontSize() : null;
    }

    static Double getStyleHorizontalScale(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        return resolved != null ? resolved.horizontalScale() : null;
    }

    static String getStyleUnderlineColor(ResolvedBuildContext ctx, String styleRef) {
        if (ctx.styleResolver == null) return null;
        IDMLStyleDef resolved = ctx.styleResolver.getResolvedParagraphStyle(styleRef);
        if (resolved != null && resolved.underlineColor() != null) {
            return resolveColorToHex(ctx, resolved.underlineColor());
        }
        return null;
    }

    private static boolean shouldApplyFontStyle(String fontFamily, String fontStyle, String text) {
        if (fontStyle == null || fontStyle.isEmpty()) return false;
        boolean isEHorBT = fontFamily != null
                && (EHFontGlyphMap.isEHFontFamily(fontFamily) || fontFamily.contains("BT수식"));
        if (!isEHorBT) return true;
        String lower = fontStyle.toLowerCase(Locale.ROOT);
        boolean isItalic = lower.contains("italic") || lower.contains("oblique");
        // EH/BT 이탤릭은 "수식 변수(x, y)" 로 보고 이탤릭을 적용하지만, 순수 숫자·부호
        // (예: 제곱근 값 5, -5, 25)는 변수가 아니라 본문 숫자다. 이탤릭을 적용하지 않는다.
        // (실측: 수학 1단원 p14 "제곱근은 5와 -5이다" 의 5/-5 가 이탤릭으로 나옴)
        if (isItalic && isNumericOrSignOnly(text)) return false;
        // 한글은 수식 변수가 아니다. EH/BT 수식 옆 한글 런("그런데")이 인접 수식의
        // 이탤릭 resolved 스타일에 잘못 매칭돼 기울어지던 문제(실측: 1단원 5²=25 앞
        // "그런데")를 막는다.
        if (isItalic && text != null && EHTextClassifier.isKoreanOnly(text)) return false;
        return isItalic || text == null || !EHTextClassifier.isKoreanOnly(text);
    }

    /** 텍스트가 숫자·부호(+ - . , 공백)만으로 이루어졌는지 — 수식 변수가 아닌 본문 숫자 판별용. */
    private static boolean isNumericOrSignOnly(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') { hasDigit = true; continue; }
            if (c == '+' || c == '-' || c == '.' || c == ',' || c == ' '
                    || c == '−' /* − 마이너스 */) continue;
            return false;
        }
        return hasDigit;
    }

    private static String resolveCharacterShadeColor(ResolvedBuildContext ctx, IDMLCharacterRun cr,
                                                     IDMLStyleDef grepCharStyle) {
        String color = cr != null ? cr.shadeColor() : null;
        Double tint = cr != null ? cr.shadeTint() : null;

        if (color == null && grepCharStyle != null && grepCharStyle.shadeColor() != null) {
            color = grepCharStyle.shadeColor();
            tint = grepCharStyle.shadeTint();
        }

        if (color == null && cr != null && cr.appliedCharacterStyle() != null && ctx.styleResolver != null) {
            IDMLStyleDef charStyle = ctx.styleResolver.getResolvedCharacterStyle(cr.appliedCharacterStyle());
            if (charStyle != null && charStyle.shadeColor() != null) {
                color = charStyle.shadeColor();
                tint = charStyle.shadeTint();
            }
        }

        String hex = resolveColorToHex(ctx, color);
        if (hex == null) return null;
        if (tint != null && tint >= 0 && tint < 100) {
            return blendWithWhite(hex, tint / 100.0);
        }
        return hex;
    }

    private static Double resolveIdmlHorizontalScale(ResolvedBuildContext ctx,
                                                     IDMLCharacterRun cr,
                                                     IDMLStyleDef grepCharStyle,
                                                     StoryConverter.StyleContext sc) {
        Double v = firstNonDefaultScale(cr != null ? cr.horizontalScale() : null);
        if (v != null) return v;
        v = firstNonDefaultScale(grepCharStyle != null ? grepCharStyle.horizontalScale() : null);
        if (v != null) return v;
        if (cr != null && cr.appliedCharacterStyle() != null && ctx.styleResolver != null) {
            IDMLStyleDef charStyle = ctx.styleResolver.getResolvedCharacterStyle(cr.appliedCharacterStyle());
            v = firstNonDefaultScale(charStyle != null ? charStyle.horizontalScale() : null);
            if (v != null) return v;
        }
        return firstNonDefaultScale(sc != null ? sc.horizontalScale : null);
    }

    private static Double resolveTracking(IDMLCharacterRun cr,
                                          ResolvedRun rr,
                                          StoryConverter.StyleContext sc,
                                          MatchConfidence confidence) {
        if (isReliableResolvedRun(rr, confidence) && rr.tracking() != null) {
            return rr.tracking();
        }
        return (cr != null && cr.tracking() != null && cr.tracking() != 0)
                ? cr.tracking()
                : (sc != null ? sc.tracking : null);
    }

    private static Double resolveHorizontalScale(ResolvedBuildContext ctx,
                                                 IDMLCharacterRun cr,
                                                 ResolvedRun rr,
                                                 IDMLStyleDef grepCharStyle,
                                                 StoryConverter.StyleContext sc,
                                                 MatchConfidence confidence) {
        Double grepScale = firstNonDefaultScale(grepCharStyle != null ? grepCharStyle.horizontalScale() : null);
        if (grepScale != null) return grepScale;
        if (isReliableResolvedRun(rr, confidence)) {
            Double resolvedScale = firstNonDefaultScale(rr.horizontalScale());
            if (resolvedScale != null) return resolvedScale;
        }
        return resolveIdmlHorizontalScale(ctx, cr, grepCharStyle, sc);
    }

    private static boolean isReliableResolvedRun(ResolvedRun rr, MatchConfidence confidence) {
        return rr != null
                && (confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM);
    }

    private static Double firstNonDefaultScale(Double v) {
        if (v == null || v == 0 || v == 100.0) return null;
        return v;
    }

    private static boolean isMarkerOnlyText(String text) {
        if (text == null) return false;
        String normalized = text
                .replace("\t", "")
                .replace("\u00A0", "")
                .replace("\u2002", "")
                .trim();
        if (normalized.isEmpty()) return false;
        for (int i = 0; i < normalized.length(); i++) {
            if (StoryConverter.BULLET_CHARS.indexOf(normalized.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void applyMarkerScaleFromResolved(ASTTextRun tr, ResolvedRun rr) {
        if (tr == null || rr == null) return;
        if (rr.horizontalScale() != null && rr.horizontalScale() != 0 && rr.horizontalScale() != 100) {
            tr.horizontalScale((short) Math.round(rr.horizontalScale()));
        }
        if (rr.verticalScale() != null && rr.verticalScale() != 0 && rr.verticalScale() != 100) {
            tr.verticalScale((short) Math.round(rr.verticalScale()));
        }
    }

    private static String blendWithWhite(String hex, double fraction) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) return hex;
        double f = Math.max(0, Math.min(1, fraction));
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        int rr = (int) Math.round(255 + (r - 255) * f);
        int gg = (int) Math.round(255 + (g - 255) * f);
        int bb = (int) Math.round(255 + (b - 255) * f);
        return String.format("#%02X%02X%02X", rr, gg, bb);
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

    static void splitChemicalFormulasAndLatinVarsInMixedText(ResolvedBuildContext ctx,
                                                              ASTTextRun originalRun,
                                                              ASTParagraph para) {
        String text = originalRun.text();
        if (text == null || text.isEmpty()) {
            para.addItem(originalRun);
            return;
        }

        // Italic all-capital runs from math typography are geometry/variable labels (AB, ABC),
        // not chemical formula evidence.  Parsing ABC as A + the element pair B/C changes its
        // materialization and typography even though the source owns one consistently styled run.
        // Real chemical notation in these sources is upright or has explicit formula structure.
        if (isItalicUppercaseLabel(originalRun)) {
            splitLatinVarsInMixedText(ctx, originalRun, para);
            return;
        }

        // SPEC-050: overline(선분) 마커 ... 로 이미 감싼 텍스트는 기하
        // 수식(overline{PA}=overline{PB})이지 화학식이 아니다. 화학식 파서가 마커
        // 사이의 "PB"(P+B)를 화학식으로 오인하면 마커쌍이 쪼개져 두 번째 overline 이
        // 파괴된다(u5 p166: overline{PA} 는 A 가 원소 아니라 생존, overline{PB} 는
        // 파괴). 마커가 있으면 화학식 분리를 건너뛰고 latin var 분리만 수행한다.
        if (text.indexOf('\uE000') >= 0 || text.indexOf('\uE001') >= 0) {
            splitLatinVarsInMixedText(ctx, originalRun, para);
            return;
        }

        int cursor = 0;
        boolean emitted = false;
        while (cursor < text.length()) {
            FormulaTokenMatch match = findNextChemicalFormulaToken(text, cursor);
            if (match == null) break;

            if (match.start > cursor) {
                ASTTextRun before = cloneRunWithText(ctx, originalRun, text.substring(cursor, match.start));
                splitLatinVarsInMixedText(ctx, before, para);
            }

            emitChemicalFormulaToken(ctx, originalRun, para, match.token);
            emitted = true;
            cursor = match.endExclusive;
        }

        if (!emitted) {
            splitLatinVarsInMixedText(ctx, originalRun, para);
            return;
        }

        if (cursor < text.length()) {
            ASTTextRun after = cloneRunWithText(ctx, originalRun, text.substring(cursor));
            splitLatinVarsInMixedText(ctx, after, para);
        }
    }

    private static boolean isItalicUppercaseLabel(ASTTextRun run) {
        if (run == null || run.text() == null || run.text().length() < 2) return false;
        String style = run.fontStyle();
        String font = run.fontFamily();
        boolean sourceMathLabel = (style != null
                && style.toLowerCase(java.util.Locale.ROOT).contains("italic"))
                || (font != null && EHFontGlyphMap.isEHFontFamily(font))
                || run.grepMathFont();
        if (!sourceMathLabel) {
            return false;
        }
        for (int i = 0; i < run.text().length(); i++) {
            char c = run.text().charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static void emitChemicalFormulaToken(ResolvedBuildContext ctx,
                                                 ASTTextRun templateRun,
                                                 ASTParagraph para,
                                                 String token) {
        para.addItem(cloneRunWithText(ctx, templateRun, token));
    }

    private static FormulaTokenMatch findNextChemicalFormulaToken(String text, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isUpperCase(ch)) continue;
            FormulaTokenMatch match = tryParseChemicalFormulaToken(text, i);
            if (match != null) return match;
        }
        return null;
    }

    private static FormulaTokenMatch tryParseChemicalFormulaToken(String text, int start) {
        if (start < 0 || start >= text.length()) return null;
        if (!Character.isUpperCase(text.charAt(start))) return null;
        if (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) return null;

        int i = start;
        boolean hasDigit = false;
        int elementCount = 0;
        boolean parsedAnyElement = false;
        while (i < text.length()) {
            int elementEnd = i + 1;
            if (elementEnd < text.length() && Character.isLowerCase(text.charAt(elementEnd))) {
                elementEnd++;
            }
            String symbol = text.substring(i, elementEnd);
            if (!CHEMICAL_ELEMENT_SYMBOLS.contains(symbol)) break;
            parsedAnyElement = true;
            elementCount++;
            i = elementEnd;

            int digitStart = i;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (i > digitStart) hasDigit = true;
        }

        if (!parsedAnyElement || (!hasDigit && elementCount < 2)) return null;
        if (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) return null;
        return new FormulaTokenMatch(start, i, text.substring(start, i));
    }

    private static final class FormulaTokenMatch {
        final int start;
        final int endExclusive;
        final String token;

        FormulaTokenMatch(int start, int endExclusive, String token) {
            this.start = start;
            this.endExclusive = endExclusive;
            this.token = token;
        }
    }

    static ASTTextRun cloneRunWithText(ResolvedBuildContext ctx, ASTTextRun src, String text) {
        ASTTextRun tr = new ASTTextRun();
        tr.text(text);
        tr.fontFamily(src.fontFamily());
        tr.fontStyle(src.fontStyle());
        tr.fontSizeHwpunits(src.fontSizeHwpunits());
        tr.textColor(src.textColor());
        tr.shadeColor(src.shadeColor());
        tr.letterSpacing(src.letterSpacing());
        tr.grepMathFont(src.grepMathFont());
        tr.subscript(src.subscript());
        tr.superscript(src.superscript());
        tr.droppedResolvedScriptPosition(src.droppedResolvedScriptPosition());
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
        if (name.startsWith("Swatch/")) name = name.substring("Swatch/".length());
        if (name.startsWith("Tint/")) name = name.substring("Tint/".length());
        if (name.contains("%25")) name = name.replace("%25", "%");
        if (name.startsWith("#") && name.length() > 1) name = name.substring(1);
        if ("None".equals(name) || "[None]".equals(name) || "n".equals(name)) return null;
        if ("Paper".equals(name)) return "#FFFFFF";
        if ("Black".equals(name)) return "#000000";
        if ("검정".equals(name)) return "#000000";
        if ("Text_Color".equals(name) || "Text Color".equals(name)
                || "$ID/Text_Color".equals(name) || "$ID/Text Color".equals(name)) return "#000000";
        if ("White".equals(name)) return "#FFFFFF";
        // resolvedData에서 조회
        String hex = ctx.resolvedData.resolveColorHex(name);
        if (hex != null) return hex;
        hex = resolveTintedNamedColor(name);
        if (hex != null) return hex;
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
        ConversionTiming.addCounter("phase3.colorResolverFallback.calls", 1);
        if (name.startsWith("u") || name.startsWith("U")) {
            ConversionTiming.addCounter("phase3.colorResolverFallback.idColors", 1);
        } else if (name.startsWith("[") && name.endsWith("]")) {
            ConversionTiming.addCounter("phase3.colorResolverFallback.bracketNames", 1);
        } else {
            ConversionTiming.addCounter("phase3.colorResolverFallback.namedColors", 1);
        }
        // IDML color ID (예: "Color/u1fc", "u1fc") → colorResolver로 해석
        if (ctx.ensureColorInfra != null) {
            ctx.ensureColorInfra.run();
        } else if (ctx.ensureIdmlInfra != null) {
            ctx.ensureIdmlInfra.run();
        }
        if (ctx.colorResolverSupplier.get() != null) {
            String crHex = ctx.colorResolverSupplier.get().resolve(color);
            if (crHex != null) return crHex;
            crHex = ctx.colorResolverSupplier.get().resolve("Color/" + name);
            if (crHex != null) return crHex;
            crHex = ctx.colorResolverSupplier.get().resolve(name);
            if (crHex != null) return crHex;
        }
        return null;
    }

    static String resolveColorToHex(ResolvedBuildContext ctx, String color, Double tint) {
        String hex = resolveColorToHex(ctx, color);
        if (hex == null || tint == null) return hex;
        return kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver.applyTintToHex(hex, tint);
    }

    private static String resolveTintedNamedColor(String name) {
        if (name == null || name.indexOf('%') < 0) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\[?([^\\]]+)\\]?\\s+(\\d+\\.?\\d*)%$")
                .matcher(name.trim());
        if (!m.find()) return null;
        String base = m.group(1).trim();
        double tint;
        try {
            tint = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        String baseHex = null;
        if ("Black".equals(base) || "검정".equals(base)) baseHex = "#000000";
        else if ("Paper".equals(base) || "White".equals(base) || "흰색".equals(base)) baseHex = "#FFFFFF";
        if (baseHex == null) return null;
        return blendWithWhite(baseHex, Math.max(0.0, Math.min(100.0, tint)) / 100.0);
    }

    /**
     * resolved 런 중 가장 긴 텍스트를 가진 런을 기본값으로 선택.
     * 불릿(●, ▪ 등 1~2자)이나 특수문자 런이 아닌 본문 런을 우선 선택.
     */
    /**
     * SPEC-055(text-attribute): 문단 단색 폴백.
     *
     * <p>resolved 가 문단의 모든 텍스트 런을 <b>동일한 유채색</b>으로 보고하면,
     * per-run 매칭 실패(인라인 수식으로 텍스트가 갈라진 줄 등)로 색을 잃고
     * 검정으로 떨어진 런에도 그 색을 채운다. 혼색 문단·색 정보가 없는 런이
     * 하나라도 있으면 아무것도 하지 않는다 (보수적). Paper/흰색/검정은 폴백
     * 대상이 아니다 (Paper 누출 가드 유지).
     */
    static void applyUniformResolvedParagraphColor(ResolvedBuildContext ctx,
                                                   List<ResolvedRun> resolvedRuns,
                                                   ASTParagraph para) {
        if (ctx == null || resolvedRuns == null || para == null || para.items() == null) return;
        String fill = null;
        int textRunCount = 0;
        for (ResolvedRun r : resolvedRuns) {
            if (r == null || r.isInlineAnchor()) continue;
            String t = r.text();
            if (t == null || t.trim().isEmpty()) continue;
            String f = r.fillColor();
            if (f == null || f.trim().isEmpty()) return;
            if (fill == null) fill = f;
            else if (!fill.equals(f)) return;
            textRunCount++;
        }
        if (fill == null || textRunCount < 2) return;
        String hex = resolveColorToHex(ctx, fill);
        if (hex == null) return;
        String up = hex.trim().toUpperCase(java.util.Locale.ROOT);
        if ("#FFFFFF".equals(up) || "#000000".equals(up)) return;
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTTextRun) {
                ASTTextRun tr = (ASTTextRun) item;
                String c = tr.textColor();
                if (c == null || "#000000".equalsIgnoreCase(c.trim())) {
                    tr.textColor(hex);
                }
            } else if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
                if (eq.textColor() == null || eq.textColor().isEmpty()) {
                    eq.textColor(hex);
                }
            }
        }
    }

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
                splitChemicalFormulasAndLatinVarsInMixedText(ctx, tr, para);
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
                .replace('\u2009', ' ').replace('\u200A', ' ').replace('\u00A0', ' ')
                .replace("\t", "");
    }

    /** 정규화된 길이에 대응하는 원본 문자열의 실제 길이. */
    static int findOriginalLength(String original, int normalizedLen) {
        if (original == null || normalizedLen <= 0) return 0;
        int normalizedCount = 0;
        for (int i = 0; i < original.length(); i++) {
            char ch = original.charAt(i);
            if (ch == '\t') {
                continue;
            }
            normalizedCount++;
            if (normalizedCount >= normalizedLen) {
                return i + 1;
            }
        }
        return original.length();
    }

    static ResolvedRun findResolvedRun(ResolvedBuildContext ctx, List<ResolvedRun> runs, int startIdx, String text) {
        if (runs == null || runs.isEmpty() || text == null || text.isEmpty()) return null;
        String normalizedText = normalizeComparableText(normalizeSpaces(text));
        if (normalizedText.isEmpty()) return null;
        String key = normalizedText.length() > 5 ? normalizedText.substring(0, 5) : normalizedText;
        // startIdx부터 순차 검색
        for (int i = startIdx; i < runs.size(); i++) {
            String rt = normalizeComparableText(normalizeSpaces(runs.get(i).text()));
            if (!rt.isEmpty() && rt.contains(key)) {
                ctx.lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        // 앞쪽 소수 런만 역방향 재탐색 (O(n²) 방지: 전체 restart 대신 최대 8칸 window)
        int backWindow = Math.max(0, startIdx - 8);
        for (int i = backWindow; i < startIdx; i++) {
            String rt = normalizeComparableText(normalizeSpaces(runs.get(i).text()));
            if (!rt.isEmpty() && rt.contains(key)) {
                ctx.lastMatchResult[0] = i;
                return runs.get(i);
            }
        }
        return null;
    }

    static MatchConfidence confidenceForResolvedRunMatch(ResolvedRun run, String text) {
        if (run == null || text == null || text.isEmpty()) return MatchConfidence.LOW;
        String idmlText = normalizeComparableText(normalizeSpaces(text));
        String resolvedText = normalizeComparableText(normalizeSpaces(run.text()));
        if (idmlText.isEmpty() || resolvedText.isEmpty()) return MatchConfidence.LOW;
        if (idmlText.equals(resolvedText)) return MatchConfidence.HIGH;
        if (resolvedText.startsWith(idmlText) || idmlText.startsWith(resolvedText)) {
            return MatchConfidence.MEDIUM;
        }
        return MatchConfidence.LOW;
    }
}
