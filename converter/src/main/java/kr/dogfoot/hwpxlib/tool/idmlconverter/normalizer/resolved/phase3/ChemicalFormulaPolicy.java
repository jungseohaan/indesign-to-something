package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.List;

/**
 * 화학 반응식을 HWP 수식이 아닌 일반 텍스트로 처리하는 정책.
 *
 * <p><b>왜 텍스트인가</b><br>
 * 화학식을 ASTEquation(HWP 수식)으로 내보내면, 한글 수식 편집기가 BT 계열
 * 수식 폰트(BT수식H-분수N 등)의 글리프를 렌더링하지 못해 깨진 글자가 표시된다.
 * 과학 교과서 p20의 "2Mg + O₂ → 2MgO" 가 "갤 → 갤" 로 보이는 현상이 그것이다.
 * 수식 스크립트 자체(<code>2 Mg+O_{2} rarrow 2 MgO</code>)는 문법적으로 옳았지만,
 * 한글이 그 안의 폰트를 처리하지 못했다.
 *
 * <p>화학식에는 분수/루트/시그마/적분 같은 수학적 구조가 없다. "원소기호 +
 * 아래첨자 숫자 + 연산자(+)/화살표(→)" 뿐이므로, 일반 텍스트 런과 아래첨자
 * 속성만으로 온전히 표현할 수 있다.
 *
 * <p><b>왜 문단 단위인가</b><br>
 * 개별 런이나 수식 그룹 단위로 텍스트화하면 한 화학식이 여러 조각으로 파편화된다
 * (실제 회귀: '(CH', '(O', '_{3}+2 HCl', 단위 'g'/'mL' 이 개별 수식으로 분리).
 * 런은 이미 아래첨자 경계로 잘게 쪼개져 들어오기 때문에, 반드시 문단 전체를 보고
 * 판정한 뒤 그 문단의 수식 런을 통째로 텍스트로 강등해야 한다.
 *
 * <p><b>보수적 판정</b><br>
 * 수학 수식을 화학식으로 오판하면 수학 교과서가 깨진다. 따라서 아래를 모두
 * 만족할 때만 화학식으로 본다:
 * <ol>
 *   <li>아래첨자로 조판된 런이 하나 이상 (H₂O 의 2) — 화학식의 결정적 특징</li>
 *   <li>알려진 원소기호(H, O, Mg, Ca, Cl …)가 하나 이상 등장</li>
 *   <li>수학 구조 문자(분수/루트/시그마/적분/등호 등)가 전혀 없음</li>
 * </ol>
 */
public final class ChemicalFormulaPolicy {

    private ChemicalFormulaPolicy() {
    }

    /** 화살표 자리에 쓰이는 글리프 코드. 폰트가 BT화살표면 내용은 무엇이든 화살표다. */
    private static final String ARROW = "→";

    /**
     * 이 문단이 화학 반응식인가?
     *
     * <p><b>판정은 resolved 런으로 한다.</b> 이 시점의 IDML 런에는 아직 폰트/위치
     * 정보가 붙지 않아(fontFamily=null, position=null) BT 폰트도 아래첨자도 보이지
     * 않는다. 실제 조판 정보는 InDesign DOM 에서 온 resolved.json 에 있다:
     * <pre>
     *   text='O'  font='BT수식H-분수N'  position='NORMAL'
     *   text='2'  font='BT수식H-분수N'  position='SUBSCRIPT'   ← 화학식의 표지
     *   text='@C' font='BT화살표'                              ← 화살표
     * </pre>
     */
    static boolean isChemicalFormulaParagraph(List<ResolvedRun> resolvedRuns) {
        if (resolvedRuns == null || resolvedRuns.isEmpty()) return false;
        return isChemicalFormulaResolved(resolvedRuns);
    }

    /**
     * resolved 매칭이 없는 문단(표 셀 등)을 위한 폴백 — IDML 런으로 판정한다.
     *
     * <p>표 형태로 조판된 화학반응식(탭 구분)은 resolved 스토리와 매칭되지 않아
     * resolvedRuns 가 null 로 들어온다. 실측:
     * <pre>
     *   resolvedRuns=NULL  text=\tN2\t+\t3 H2\t@C\t2 NH3   ← 화면에서 깨지던 그 화학식
     * </pre>
     * 이 경우 IDML 런의 CharacterStyle(첨자-하부자 / 화살표)과 글리프 코드로 판정한다.
     */
    public static boolean isChemicalFormulaParagraphFromIdml(List<IDMLCharacterRun> runs) {
        if (runs == null || runs.isEmpty()) return false;

        boolean hasSubscript = false;
        boolean hasArrowOrMathFont = false;
        StringBuilder joined = new StringBuilder();

        for (IDMLCharacterRun run : runs) {
            if (run == null) continue;

            if (isArrowGlyphRun(run)) {
                joined.append(ARROW);
                hasArrowOrMathFont = true;
                continue;
            }

            String style = run.appliedCharacterStyle();
            if (style != null && isSubscriptStyleRef(style)) hasSubscript = true;
            if (run.isBTFont() || run.grepMathFont()) hasArrowOrMathFont = true;

            String text = run.content();
            if (text != null) joined.append(text);
        }

        if (!hasArrowOrMathFont) return false;
        if (!hasSubscript) return false;

        String text = joined.toString();
        if (!containsKnownChemicalElement(text)) return false;
        return !containsMathStructure(text);
    }

    private static boolean isChemicalFormulaResolved(List<ResolvedRun> resolvedRuns) {

        boolean hasSubscript = false;
        boolean hasMathFontRun = false;
        StringBuilder joined = new StringBuilder();

        for (ResolvedRun run : resolvedRuns) {
            if (run == null) continue;

            String font = run.fontFamily();
            if (BTFontGlyphMap.isBTArrowFont(font)) {
                joined.append(ARROW);
                hasMathFontRun = true;
                continue;
            }

            if (font != null && BTFontGlyphMap.isBTFontFamily(font)) {
                hasMathFontRun = true;
                String pos = run.position();
                if (pos != null && pos.toUpperCase(java.util.Locale.ROOT).contains("SUBSCRIPT")) {
                    hasSubscript = true;
                }
            }

            String text = run.text();
            if (text != null) joined.append(text);
        }

        // 수식 폰트 런이 하나도 없으면 애초에 수식화 대상이 아니다.
        if (!hasMathFontRun) return false;
        // 아래첨자가 없으면 화학식으로 보지 않는다 (H2O 의 2 가 화학식의 표지).
        if (!hasSubscript) return false;

        String text = joined.toString();
        if (!containsKnownChemicalElement(text)) return false;
        // 수학 구조가 섞여 있으면 수식으로 남긴다.
        return !containsMathStructure(text);
    }

    /**
     * 화학식 문단의 IDML 런에서 수식 표시를 제거해, 수식 그룹에 모이지 않게 한다.
     *
     * <p>화살표 런(@C/?C/C)은 실제 화살표 문자로 바꾼다. 폰트가 BT화살표면 내용은
     * 무엇이든 화살표이므로, 텍스트 패턴이 아니라 폰트/스타일로 판정한다.
     * (텍스트 패턴으로 추측하면 접두문자 없는 "C" 를 놓쳐 화살표 자리에 글자 C 가
     *  그대로 박힌다 — 실제로 'CaO+H₂O C Ca(OH)₂' 로 깨졌다.)
     */
    public static void demoteMathRunsToText(List<IDMLCharacterRun> runs) {
        if (runs == null) return;

        for (IDMLCharacterRun run : runs) {
            if (run == null) continue;

            // 화살표 런: 글리프 코드를 실제 화살표 문자로 바꾼다.
            //
            // 길이를 보존해야 한다. IDML 런 ↔ resolved 런 매칭이 텍스트 길이 기준이라,
            // "@C"(2자)를 "→"(1자)로 줄이면 이후 매칭이 한 칸 밀려 아래첨자가 엉뚱한
            // 런(2MgO 의 계수 2)에 붙는다. (실측 확인)
            // → 원래 글자 수만큼 화살표+공백으로 채워 길이를 유지한다.
            //
            // 글리프 코드는 문서마다 다르다(관측: "@C", "?C", 접두문자 없는 "C").
            // 텍스트가 아니라 폰트/스타일로 판정해야 "C" 단독도 잡힌다.
            if (isArrowGlyphRun(run)) {
                String orig = run.content();
                int len = orig == null ? 1 : orig.length();
                StringBuilder sb = new StringBuilder(ARROW);
                while (sb.length() < len) sb.append(' ');
                run.content(sb.toString());
                run.fontFamily(null);
                run.fontStyle(null);
                run.appliedCharacterStyle(null);
                run.grepMathFont(false);
                continue;
            }

            // 수식 폰트/GREP 표시를 벗겨 본문 폰트 매핑을 타게 한다.
            run.grepMathFont(false);
            String ff = run.fontFamily();
            if (ff != null && BTFontGlyphMap.isBTFontFamily(ff)) {
                run.fontFamily(null);
                run.fontStyle(null);
            }
        }
    }

    /**
     * IDML 런에 남아 있는 첨자 근거를 제거한다.
     *
     * <p>화학식 문단의 첨자는 resolved 런의 position(NORMAL/SUBSCRIPT)만 신뢰한다.
     * InDesign DOM 이 실제 조판 상태를 알려준 값이라 정확하기 때문이다:
     * <pre>
     *   '2'   NORMAL      ← 계수 (2Mg)
     *   'O'   NORMAL
     *   '2'   SUBSCRIPT   ← 아래첨자 (O₂)
     *   '2'   NORMAL      ← 계수 (2MgO)
     * </pre>
     *
     * <p>반면 IDML 런은 "2Mg + O" / "2" / "@C" / "  2MgO" 처럼 뭉쳐 있고,
     * 아래첨자 CharacterStyle("00_수식(첨자-하부자)")이 런 분할·상속 과정에서
     * 이웃 런까지 번진다. 그 상태의 {@link IDMLCharacterRun#isSubscript()} 를 믿으면
     * 계수 2(2MgO)까지 아래첨자로 작아진다.
     *
     * <p>position 과 첨자 CharacterStyle 을 지워, 첨자 판정이 오직 resolved 의
     * position 에서만 오도록 만든다.
     */
    /**
     * 화살표 글리프 런을 실제 화살표 문자로 치환한다 — 화학식 여부와 무관하게.
     *
     * <p>화살표 폰트/스타일("BT화살표", "00_수식(화살표)")이 적용된 런은 어떤 맥락에서든
     * 화살표다. 표 셀에 화살표만 홀로 들어간 경우(반응식을 표로 조판한 레이아웃)에는
     * 그 문단에 원소기호가 없어 화학식 판정을 통과하지 못하고, 결과적으로 "@C" 가
     * 화면에 그대로 노출됐다. → 원소기호 조건 없이 폰트/스타일만으로 치환한다.
     *
     * <p>글리프 코드는 문서마다 다르다(관측: "@C", "?C", 접두문자 없는 "C").
     */
    public static void normalizeArrowGlyphRuns(List<IDMLCharacterRun> runs) {
        if (runs == null) return;
        for (IDMLCharacterRun run : runs) {
            if (run == null) continue;
            if (!isArrowGlyphRun(run)) continue;
            run.content(ARROW);
            run.fontFamily(null);
            run.fontStyle(null);
            run.appliedCharacterStyle(null);
            run.grepMathFont(false);
        }
    }

    /** CharacterStyle 참조가 위/아래첨자 스타일인가 (IDML 은 URL-encoded 일 수 있다). */
    private static boolean isSubscriptStyleRef(String styleRef) {
        String s = styleRef.toLowerCase(java.util.Locale.ROOT)
                .replace("%3a", ":")
                .replace("%25", "%");
        return s.contains("subscript") || s.contains("superscript")
                || s.contains("하부자") || s.contains("상부자")
                || s.contains("아래첨자") || s.contains("위첨자")
                || s.contains("첨자");
    }

    /**
     * IDML 런이 화살표 글리프 런인가.
     * IDML 런은 fontFamily 가 비어 있을 수 있으므로 appliedCharacterStyle 도 함께 본다.
     */
    private static boolean isArrowGlyphRun(IDMLCharacterRun run) {
        if (run == null) return false;
        if (BTFontGlyphMap.isBTArrowFont(run.fontFamily())) return true;
        if (isArrowStyleRef(run.appliedCharacterStyle())) return true;
        // 폰트/스타일 정보가 아직 붙지 않은 경우의 폴백: 알려진 화살표 글리프 코드.
        String c = run.content();
        if (c == null) return false;
        String t = c.trim();
        return "@C".equals(t) || "@c".equals(t) || "?C".equals(t) || "?c".equals(t);
    }

    /**
     * CharacterStyle 참조가 화살표 스타일인가.
     *
     * <p>실제 문서의 스타일명은 "CharacterStyle/00_수식모음%3a00_수식(화살표)" 처럼
     * "BT" 접두사가 없다. BTFontGlyphMap.isBTArrowFontStyle 은 "BT화살표" 만 찾으므로
     * 이 문서를 잡지 못한다. → "화살표" 라는 단어로 직접 판정한다.
     */
    private static boolean isArrowStyleRef(String styleRef) {
        if (styleRef == null) return false;
        String s = styleRef.toLowerCase(java.util.Locale.ROOT)
                .replace("%3a", ":")
                .replace("%ed%99%94%ec%82%b4%ed%91%9c", "화살표");
        return s.contains("화살표");
    }

    /**
     * 수학 구조 문자가 있는가? 있으면 화학식이 아니라 수학 수식이다.
     * (분수선, 루트, 시그마, 적분, 등호, 부등호, 위첨자 캐럿 등)
     */
    private static boolean containsMathStructure(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || c == '<' || c == '>' || c == '^' || c == '_'
                    || c == '√'   // √
                    || c == '∑'   // ∑
                    || c == '∫'   // ∫
                    || c == 'π'   // π
                    || c == '∞'   // ∞
                    || c == '≤' || c == '≥'   // ≤ ≥
                    || c == '×' || c == '÷'   // × ÷
                    || c == '/' || c == '\\'
                    || c == '{' || c == '}' || c == '[' || c == ']') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsKnownChemicalElement(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 'A' || c > 'Z') continue;
            // 2글자 기호 우선 (Mg, Ca, Cl …)
            if (i + 1 < text.length()) {
                char n = text.charAt(i + 1);
                if (n >= 'a' && n <= 'z'
                        && isKnownChemicalElementSymbol(text.substring(i, i + 2))) {
                    return true;
                }
            }
            if (isKnownChemicalElementSymbol(String.valueOf(c))) return true;
        }
        return false;
    }

    private static boolean isKnownChemicalElementSymbol(String symbol) {
        switch (symbol) {
            case "H": case "He": case "Li": case "Be": case "B": case "C":
            case "N": case "O": case "F": case "Ne": case "Na": case "Mg":
            case "Al": case "Si": case "P": case "S": case "Cl": case "Ar":
            case "K": case "Ca": case "Fe": case "Cu": case "Zn": case "Ag":
            case "I": case "Ba": case "Pt": case "Au": case "Hg": case "Pb":
                return true;
            default:
                return false;
        }
    }
}
