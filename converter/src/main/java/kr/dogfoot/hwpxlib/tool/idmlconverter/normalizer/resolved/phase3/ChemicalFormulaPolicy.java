package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.List;

/**
 * 화학 반응식 판정과 화살표 글리프 정규화 정책.
 *
 * <p>화학식은 일반 텍스트 첨자 보정으로 강등하지 않고
 * {@code ASTEquation("CHEM_FORMULA")}으로 닫는다. 이 클래스는 resolved/IDML 런에
 * 남아 있는 화학식 증거를 판정하고, BT 화살표 글리프처럼 HWP 수식 스크립트로
 * 직접 표현해야 하는 기호를 정규화한다.
 *
 * <p><b>보수적 판정</b><br>
 * 수학 수식을 화학식으로 오판하면 다른 교과서가 깨진다. 따라서 아래를 모두
 * 만족할 때만 화학식 후보로 본다:
 * <ol>
 *   <li>수식 폰트/화살표 또는 아래첨자처럼 화학식 조판 증거가 있다.</li>
 *   <li>알려진 원소기호(H, O, Mg, Ca, Cl …)가 하나 이상 등장한다.</li>
 *   <li>분수/루트/시그마/적분 같은 수학 구조 문자가 없다.</li>
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
            // 텍스트는 IDMLStoryParser 가 파싱 직후 이미 "→" 로 정규화했다.
            // 여기서는 폰트/스타일만 벗긴다 — BT화살표 폰트를 그대로 두면
            // 한글이 글리프를 렌더링하지 못한다.
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
