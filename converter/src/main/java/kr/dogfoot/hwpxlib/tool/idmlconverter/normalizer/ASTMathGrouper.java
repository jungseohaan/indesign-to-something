package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.PatternEquationConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * BT 수식 폰트 런 그룹핑 및 수식 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
public class ASTMathGrouper {

    /**
     * 한국어+수식마커 혼합 런을 한국어/비한국어 경계에서 분리한다.
     * 예: "&P_r를 구해 보자" → "&P_r" + "를 구해 보자"
     * BT 폰트/grepMath 런이나 한국어/수식마커가 혼합되지 않은 런은 그대로 통과.
     */
    public static List<IDMLCharacterRun> splitMathKoreanMixedRuns(List<IDMLCharacterRun> runs) {
        List<IDMLCharacterRun> result = new ArrayList<>();
        for (IDMLCharacterRun run : runs) {
            // BT/grepMath 런도 한국어+수식 혼합이면 분리 대상 (한국어 부분을 수식에서 제외하기 위해)
            String text = run.content();
            if (text == null || text.isEmpty()) {
                result.add(run);
                continue;
            }
            // 한국어와 라틴/수학 문자가 모두 포함된 경우 분리 대상
            boolean hasKorean = false;
            boolean hasLatinMath = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) hasKorean = true;
                if (Character.isLetterOrDigit(c) && !(c >= 0xAC00 && c <= 0xD7AF) && !(c >= 0x3131 && c <= 0x318E))
                    hasLatinMath = true;
                if (c == '_' || c == '^' || c == '&' || c == '\\' || c == '`'
                        || "+-*/=<>()[]{}|!.;".indexOf(c) >= 0) hasLatinMath = true;
            }
            if (!hasKorean || !hasLatinMath) {
                result.add(run);
                continue;
            }
            // 한국어/비한국어 경계에서 분리
            // 각 문자를 KOREAN(1) / OTHER(2) / NEUTRAL(0)로 분류
            int len = text.length();
            int[] types = new int[len];
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);
                if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E
                        || (c >= 0x2460 && c <= 0x2473) || (c >= 0x2474 && c <= 0x2487)) {
                    types[i] = 1; // KOREAN + 원문자 (선택지 번호 분리)
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '^' || c == '&'
                        || c == '\\' || c == '`' || "+-*/=<>()[]{}|!.;".indexOf(c) >= 0) {
                    types[i] = 2; // LATIN/MATH
                } else {
                    types[i] = 0; // NEUTRAL (공백, 기타)
                }
            }
            // 중립 문자 → 이전 타입 상속
            int lastType = 0;
            for (int i = 0; i < len; i++) {
                if (types[i] != 0) {
                    lastType = types[i];
                } else {
                    types[i] = (lastType != 0) ? lastType : 1;
                }
            }
            // 연속 동일 타입 구간을 분리
            int segStart = 0;
            boolean first = true;
            List<IDMLCharacterRun.InlineAnchor> srcAnchors = run.inlineAnchors();
            List<IDMLCharacterRun.InlineGraphic> srcGraphics = run.inlineGraphics();
            List<IDMLTextFrame> srcFrames = run.inlineFrames();
            int anchorIdx = 0;
            for (int i = 1; i <= len; i++) {
                if (i == len || types[i] != types[segStart]) {
                    String segText = text.substring(segStart, i);
                    IDMLCharacterRun subRun = cloneRunForSplit(run, segText);
                    // FFFC 기반 인라인 항목 배분 (앵커가 있는 경우)
                    if (!srcAnchors.isEmpty()) {
                        for (int ci = 0; ci < segText.length(); ci++) {
                            if (segText.charAt(ci) == '\uFFFC' && anchorIdx < srcAnchors.size()) {
                                IDMLCharacterRun.InlineAnchor anchor = srcAnchors.get(anchorIdx++);
                                if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                                        && anchor.index() < srcFrames.size()) {
                                    int newIdx = subRun.inlineFrames().size();
                                    subRun.addInlineFrame(srcFrames.get(anchor.index()));
                                    subRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.FRAME, newIdx);
                                } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                                        && anchor.index() < srcGraphics.size()) {
                                    int newIdx = subRun.inlineGraphics().size();
                                    subRun.addInlineGraphic(srcGraphics.get(anchor.index()));
                                    subRun.addInlineAnchor(IDMLCharacterRun.InlineAnchorType.GRAPHIC, newIdx);
                                }
                            }
                        }
                    } else if (first) {
                        // 레거시: 첫 번째 분할 런에 인라인 그래픽을 보존
                        for (IDMLCharacterRun.InlineGraphic ig : srcGraphics) {
                            subRun.addInlineGraphic(ig);
                        }
                    }
                    first = false;
                    result.add(subRun);
                    segStart = i;
                }
            }
            // 앵커 기반: 나머지 미처리 앵커를 마지막 서브런에 전달
            if (!srcAnchors.isEmpty() && !result.isEmpty()) {
                IDMLCharacterRun lastSub = result.get(result.size() - 1);
                for (int ai = anchorIdx; ai < srcAnchors.size(); ai++) {
                    IDMLCharacterRun.InlineAnchor anchor = srcAnchors.get(ai);
                    if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                            && anchor.index() < srcFrames.size()) {
                        lastSub.addInlineFrame(srcFrames.get(anchor.index()));
                    } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                            && anchor.index() < srcGraphics.size()) {
                        lastSub.addInlineGraphic(srcGraphics.get(anchor.index()));
                    }
                }
            }
        }
        return result;
    }

    /**
     * IDML direct 경로에서는 GREP/font split 전의 run이 ")(Cu", "O)과", ")(CuO)"처럼
     * 괄호/한글과 화학식 조각을 함께 담는 경우가 있다. 화학식 ownership은 문자 단위
     * 스타일 증거를 유지해야 하므로, 화학식처럼 닫히는 ASCII 구간을 먼저 분리한다.
     */
    public static List<IDMLCharacterRun> splitChemicalFormulaMixedRuns(List<IDMLCharacterRun> runs) {
        if (runs == null || runs.isEmpty()) return runs;
        List<IDMLCharacterRun> result = new ArrayList<>();
        for (IDMLCharacterRun run : runs) {
            String text = run == null ? null : run.content();
            if (text == null || text.isEmpty()) {
                result.add(run);
                continue;
            }
            // "정체"(正體=정상 위치) 문자스타일은 본문 라틴(인명·연도)이지 화학식이 아니다.
            // 원소기호로 분리하면 Pythagoras 의 P(인)·B(붕소)가 별도 수식 런으로 쪼개져
            // 색상·이탤릭이 유실된다(실측: 1단원 "Pythagoras, B.C. 569?~475?").
            String csRef = run.appliedCharacterStyle();
            if (csRef != null) {
                String cs = csRef.toLowerCase(java.util.Locale.ROOT);
                if (cs.contains("정체") || cs.contains("정자")) {
                    result.add(run);
                    continue;
                }
            }
            List<String> parts = splitChemicalFormulaTextParts(text);
            if (parts.size() <= 1) {
                result.add(run);
                continue;
            }
            for (String part : parts) {
                if (part == null || part.isEmpty()) continue;
                IDMLCharacterRun split = cloneRunForSplit(run, part);
                if (isChemicalFormulaElementSequence(part)) {
                    split.grepMathFont(true);
                }
                result.add(split);
            }
        }
        return result;
    }

    private static List<String> splitChemicalFormulaTextParts(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int len = chemicalFormulaSpanLength(text, i);
            if (len > 0 && isFormulaDelimited(text, i, i + len)) {
                if (plain.length() > 0) {
                    parts.add(plain.toString());
                    plain.setLength(0);
                }
                parts.add(text.substring(i, i + len));
                i += len;
                continue;
            }
            plain.append(text.charAt(i));
            i++;
        }
        if (plain.length() > 0) parts.add(plain.toString());
        return parts;
    }

    private static int chemicalFormulaSpanLength(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) return 0;
        int i = start;
        int elements = 0;
        boolean hasDigit = false;
        while (i < text.length() && Character.isDigit(text.charAt(i))) {
            hasDigit = true;
            i++;
        }
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c < 'A' || c > 'Z') break;
            String one = String.valueOf(c);
            String symbol = one;
            int nextIndex = i + 1;
            if (nextIndex < text.length()) {
                char next = text.charAt(nextIndex);
                if (next >= 'a' && next <= 'z') {
                    String two = "" + c + next;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        nextIndex++;
                    }
                }
            }
            if (!isChemicalElement(symbol)) break;
            elements++;
            i = nextIndex;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                hasDigit = true;
                i++;
            }
        }
        if (elements == 0) return 0;
        return i - start;
    }

    private static boolean isFormulaDelimited(String text, int start, int end) {
        char before = start > 0 ? text.charAt(start - 1) : 0;
        char after = end < text.length() ? text.charAt(end) : 0;
        return isChemicalFormulaDelimiter(before) || isChemicalFormulaDelimiter(after);
    }

    private static boolean isChemicalFormulaDelimiter(char c) {
        return c == 0
                || Character.isWhitespace(c)
                || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}'
                || c == '+' || c == '-' || c == '=' || c == '\u2192'
                || c == '\u2005' || c == '\u2007' || c == '\u2009' || c == '\u200A';
    }

    /**
     * 런의 스타일을 복사하고 텍스트만 변경한 새 런을 생성한다 (수식/한국어 분리용).
     */
    static IDMLCharacterRun cloneRunForSplit(IDMLCharacterRun source, String newText) {
        IDMLCharacterRun clone = new IDMLCharacterRun();
        clone.appliedCharacterStyle(source.appliedCharacterStyle());
        clone.fontFamily(source.fontFamily());
        clone.fontSize(source.fontSize());
        clone.fillColor(source.fillColor());
        clone.fontStyle(source.fontStyle());
        clone.position(source.position());
        clone.baselineShift(source.baselineShift());
        clone.tracking(source.tracking());
        clone.grepMathFont(source.grepMathFont());
        clone.content(newText);
        return clone;
    }

    /**
     * 텍스트가 수식처럼 보이는지 확인.
     * BT 마커(_^&\), BT 키워드(.c3), 또는 연산자+변수 조합을 감지한다.
     */
    public static boolean looksLikeMathRun(String text) {
        if (text == null || text.isEmpty()) return false;
        // BT 마커
        boolean hasOperator = false;
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '^' || c == '&' || c == '\\') return true;
            if ("+-*/=<>|".indexOf(c) >= 0) hasOperator = true;
            if (Character.isLetterOrDigit(c)) hasLetterOrDigit = true;
        }
        // BT 키워드
        if (text.contains(".c3")) return true;
        // 연산자 + 문자/숫자 조합 → 수식 (예: (n-1), {n-(r-1)})
        return hasOperator && hasLetterOrDigit;
    }

    /**
     * 수식 폰트가 적용되지 않은 순수 알파벳/숫자 런인지 확인.
     * GREP 매칭으로 grepMathFont=true가 되었더라도, 실제 폰트가 BT수식M이 아니고
     * 내용이 알파벳/숫자/공백만으로 구성되면 수식이 아닌 일반 텍스트로 처리.
     * 예: "1", "2" (문항 번호) 등이 수식으로 잘못 변환되는 것을 방지.
     */
    public static boolean isPlainAlphanumericRun(IDMLCharacterRun run) {
        // BT 폰트가 직접 적용된 런은 수식으로 간주.
        //
        // 단, "BT수식H" 계열은 예외다. 이름만 수식이고 실제로는 본문 속 영문/숫자/기호를
        // 조판하는 폰트다(GREP 스타일 "00_영문", "00_숫자"가 적용). 실측:
        //   BT수식H-분수N   (356회)  "1-1", "1.", "2.", "H", "O", "2"
        //   BT수식H-편한글씨 (48회)   ":"  (비율 표기)
        // 이걸 무조건 수식으로 보면 한글 문장 속 원소기호까지 HWP 수식(이탤릭)이 된다
        // — "수소 원자(H)" 의 H, "산소 원자(O)" 의 O (표 셀에서 관측).
        // → 폰트가 아니라 내용으로 판정하도록 아래 검사를 계속 진행한다.
        if (run.isBTFont() && !BTFontGlyphMap.isBTBodyTextFont(run.fontFamily())) return false;
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        // 수식 기호가 하나라도 있으면 수식 가능 → plain이 아님
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("+=<>≤≥±×÷√²³^_π∑∫∞".indexOf(c) >= 0) return false;
        }
        // 그리스 문자 키워드(alpha, beta 등)가 포함되면 수식으로 간주
        if (BTFontEquationConverter.containsGreekKeyword(text)) return false;
        // 순수 숫자+구두점(예: "2", "1.4", "569?")은 수식 변수가 아님 → plain
        String trimmed = text.trim();
        boolean hasLetter = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetter(trimmed.charAt(i))) { hasLetter = true; break; }
        }
        if (!hasLetter) return true; // 숫자+구두점만 → plain
        // 단일 문자(1~2자)는 수식 변수일 수 있음 (a, x, n) → plain 아님
        if (trimmed.length() <= 2) return false;
        // 3자 이상 단어가 있으면 plain (수식 아님)
        return true;
    }

    /**
     * BT 폰트 런의 내용이 한국어만 포함하는지 확인.
     * 한국어 + 공백/구두점만 있고 라틴 문자, 숫자, 수식 마커/연산자가 없으면 true.
     * BT 폰트 런이라도 한국어만 있으면 수식이 아닌 일반 텍스트로 처리하기 위해 사용.
     */
    public static boolean isBTRunWithOnlyKorean(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) {
                hasKorean = true;
            } else if (Character.isLetterOrDigit(c)) {
                return false; // 라틴 문자 또는 숫자
            } else if (c == '_' || c == '^' || c == '&' || c == '\\' || c == '`') {
                return false; // BT 마커
            } else if ("+-*/=<>()[]{}|".indexOf(c) >= 0) {
                return false; // 수학 연산자
            }
        }
        return hasKorean;
    }

    /**
     * Chemical formulas are often adjacent to ordinary text delimiters such as
     * ")(Cu2O)" or "(CuO)". When a delimiter-only run inherits a math font,
     * it must remain text; otherwise it starts a math group and produces
     * malformed equations such as ")(Cu2".
     */
    public static boolean isChemicalFormulaBoundaryRun(IDMLCharacterRun run) {
        if (run == null) return false;
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        boolean hasBoundary = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '\u2005' || c == '\u2007'
                    || c == '\u2009' || c == '\u200A') {
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

    /**
     * BT 수식 런 사이 또는 뒤의 비수식 런이 수식 그룹에 포함될 수 있는지 확인.
     * 비한국어 텍스트이고, (1) 뒤에 BT 수식 런이 이어지거나 (2) BT 마커를 포함하면 수식 그룹에 포함.
     */
    public static boolean isMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        if (isChemicalFormulaBoundaryRun(run)) return false;
        // 수식 토큰 사이를 잇는 순수 공백 런은 브리지로 인정한다.
        // InDesign은 BT수식/BT화살표 글리프 앞뒤 공백을 일반 본문 폰트 런으로
        // 분리하는 경우가 있어, 이 공백을 끊어 버리면 화학식/수식이 둘로 분할된다.
        if (text.trim().isEmpty()) {
            return hasFormulaNeighbor(runs, idx);
        }
        // 한국어 포함 또는 탭 포함 → 브릿지 아님 (탭은 열 구분자)
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false; // 한글 음절
            if (c >= 0x3131 && c <= 0x318E) return false; // 한글 자모
            if (c == '\t') return false; // 탭은 수식 그룹 구분자
        }
        // BT수식M 마커 포함 → 수식 그룹의 연속으로 직접 포함 (look-ahead 불필요)
        boolean onlyAlphanumeric = true; // 알파벳/숫자/공백만으로 구성된지 확인
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '^' || c == '&' || c == '\\') return true;
            if ("+-*/=<>()[]{}|".indexOf(c) >= 0) {
                onlyAlphanumeric = false;
            } else if (c != ' ' && !Character.isLetterOrDigit(c)) {
                onlyAlphanumeric = false;
            }
        }
        // 수식 폰트가 적용되지 않은 순수 알파벳/숫자는 브릿지하지 않음
        // 예: "1" (번호), "n" (일반 변수명) 등이 수식으로 잘못 검출되는 것을 방지
        if (onlyAlphanumeric && !run.isBTFont()) return false;
        // GREP 수식 폰트여도 순수 알파벳/숫자/구두점이면 수식 아님 (예: "Pythagoras, B.C. 569?")
        if (run.grepMathFont() && isPlainAlphanumericRun(run)) return false;
        // 뒤에 BT 수식 런이 있는지 확인 (비한국어 런은 건너뜀)
        for (int j = idx + 1; j < runs.size(); j++) {
            IDMLCharacterRun next = runs.get(j);
            if (next.isBTFont() || (next.grepMathFont() && !isPlainAlphanumericRun(next))) return true;
            String nextText = next.content();
            if (nextText == null || nextText.isEmpty()) continue;
            boolean hasKorean = false;
            for (int i = 0; i < nextText.length(); i++) {
                char c = nextText.charAt(i);
                if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) {
                    hasKorean = true; break;
                }
            }
            if (hasKorean) return false;
        }
        return false;
    }

    /**
     * NP 폰트 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 유니코드 변환 후 일반 텍스트 런으로 폴백.
     */
    public static void flushNPMathGroup(List<IDMLCharacterRun> npRuns, ASTParagraph para) {
        if (emitPositionedFormulaEquation(npRuns, para, "CHEM_FORMULA")) {
            return;
        }
        String hwpScript = NPFontEquationConverter.convert(npRuns);
        if (hwpScript != null && shouldEmitConvertedEquation(hwpScript, npRuns)) {
            para.addItem(new ASTEquation(hwpScript, "NP_FONT"));
        } else {
            // 수식이 아닌 NP 텍스트 → 유니코드 변환 후 텍스트 런으로 폴백
            for (IDMLCharacterRun run : npRuns) {
                String npFont = run.npFontName();
                String text = run.content();
                if (npFont != null && text != null && !text.isEmpty()) {
                    text = NPFontGlyphMap.convertRunToUnicode(npFont, text);
                }
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
                    textRun.text(ASTPageProcessor.stripACEPlaceholders(text));
                    if (run.fontStyle() != null) textRun.fontStyle(run.fontStyle());
                    if (run.fontSize() != null) textRun.fontSizeHwpunits((int)(run.fontSize() * 100));
                    para.addItem(textRun);
                }
            }
        }
    }

    /**
     * 독립적인 수학 표현식인지 확인 (NP 구조 런이 있는 단락 내에서).
     * 한국어 없음, 수학 연산자(=, <, >, ±) 포함, 문자/숫자 존재 시 수식으로 인정.
     * 예: "x=k", "0<k<8", "A+B"
     */
    public static boolean isStandaloneMathRun(IDMLCharacterRun run) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        // 한국어/탭 포함 → 불가
        boolean hasOperator = false;
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
            if (c >= 0x3131 && c <= 0x318E) return false;
            if (c == '\t') return false;
            if (c == '=' || c == '<' || c == '>') hasOperator = true;
            if (Character.isLetterOrDigit(c)) hasLetterOrDigit = true;
        }
        // 수학 연산자 + 문자/숫자 → 수식
        return hasOperator && hasLetterOrDigit;
    }

    /**
     * NP 그룹이 아직 시작되지 않았을 때, 비NP 런이 곧 올 NP 런과 합쳐져야 하는지 확인.
     * "y=log" [NoStyle] + "2" [NP_ISHS] → "y=log_{2}" 로 합치기 위함.
     * 조건: 한국어 없음, 수학 연산자/변수 포함, 바로 뒤에 NP 런이 있음.
     */
    public static boolean isPreNPMathRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        if (run.isNPFont() || run.isBTFont() || run.grepMathFont()) return false;
        String text = run.content();
        if (text == null || text.isEmpty()) return false;

        // 한국어/탭 포함 → 불가
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
            if (c >= 0x3131 && c <= 0x318E) return false;
            if (c == '\t') return false;
        }

        // 수학 관련 내용이어야 함 (연산자, 함수명, 변수 등)
        boolean hasMathContent = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("=<>+-".indexOf(c) >= 0) { hasMathContent = true; break; }
            if (Character.isLetterOrDigit(c)) hasMathContent = true;
        }
        if (!hasMathContent) return false;

        // 바로 다음(비한국어 런 건너뛰며) NP 런이 오는지 확인
        for (int j = idx + 1; j < runs.size(); j++) {
            IDMLCharacterRun next = runs.get(j);
            if (next.isNPFont()) return true;
            // 한국어 만나면 중단
            String nextText = next.content();
            if (nextText != null) {
                for (int i = 0; i < nextText.length(); i++) {
                    char c = nextText.charAt(i);
                    if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /**
     * NP 수식 그룹 사이의 비NP 런이 브릿지될 수 있는지 확인.
     * 한국어/탭 포함 → 브릿지 아님. 뒤에 NP 런이 이어지면 → 브릿지.
     */
    public static boolean isNPMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        // 한국어 포함 또는 탭 포함 → 브릿지 아님
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
            if (c >= 0x3131 && c <= 0x318E) return false;
            if (c == '\t') return false;
        }
        // 뒤에 NP 수식 런이 있는지 확인
        for (int j = idx + 1; j < runs.size(); j++) {
            IDMLCharacterRun next = runs.get(j);
            if (next.isNPFont()) return true;
            String nextText = next.content();
            if (nextText == null || nextText.isEmpty()) continue;
            // 한국어 만나면 중단
            for (int i = 0; i < nextText.length(); i++) {
                char c = nextText.charAt(i);
                if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * EH 수식 런 사이의 비EH 런이 수식 그룹에 포함될 수 있는지 확인.
     * 한국어/탭/원문자 포함 → 브릿지 아님. 뒤에 EH 런이 이어지면 → 브릿지.
     */
    public static boolean isEHMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false; // 한국어
            if (c >= 0x3131 && c <= 0x318E) return false; // 한국어 자모
            if (c == '\t') return false; // 탭 (선택지 구분)
            // 원문자 ①-⑳, ⑴-⒇ → 선택지 번호, 브릿지 아님
            if ((c >= 0x2460 && c <= 0x2473) || (c >= 0x2474 && c <= 0x2487)) return false;
        }
        // 뒤에 EH 수식 런이 있는지 확인
        for (int j = idx + 1; j < runs.size(); j++) {
            IDMLCharacterRun next = runs.get(j);
            if (next.isEHFont()) return true;
            String nextText = next.content();
            if (nextText == null || nextText.isEmpty()) continue;
            for (int i = 0; i < nextText.length(); i++) {
                char c = nextText.charAt(i);
                if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * EH 수식 폰트 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 일반 텍스트 런으로 폴백.
     */
    public static void flushEHMathGroup(List<IDMLCharacterRun> ehRuns, ASTParagraph para) {
        // 근호(√)·GREP 분수 구조가 포함된 EH 그룹은 화학식 경계 분할을 건너뛴다.
        // - 근호: radicand 안의 괄호(예: √(1/2)²)의 ")" 가 isChemicalFormulaBoundaryRun
        //   으로 감지돼 그룹이 통째로 쪼개지면 sqrt 구조가 파괴된다(실측: 1단원 p18
        //   (√10)², (-√(3/4))²).
        // - GREP 분수: ";2!;, -0.1, -;3%;" 처럼 분수(;...;)와 일반 텍스트가 섞인 런은
        //   ","·"-"·약물 폰트 때문에 화학식으로 오인돼, GREP 분수를 디코딩 못 한 채
        //   ";2!;" 원문이 그대로 노출된다(실측: 1단원 p23 "정수가 아닌 유리수").
        // 두 경우 모두 EHFontEquationConverter 가 온전히 처리하므로 그대로 넘긴다.
        boolean hasEHEquationStructure = containsEHSqrtMarker(ehRuns)
                || containsEHGrepFraction(ehRuns);
        if (!hasEHEquationStructure) {
            if (emitPositionedFormulaEquation(ehRuns, para, "CHEM_FORMULA")) {
                return;
            }
            if (emitSimplePositionedTextRun(ehRuns, para)) {
                return;
            }
            if (emitBoundaryAwareChemicalFormulaGroup(ehRuns, para)) {
                return;
            }
        }
        String hwpScript = EHFontEquationConverter.convert(ehRuns);
        if (hwpScript != null && shouldEmitConvertedEquation(hwpScript, ehRuns)) {
            int beforeCount = para.items().size();
            // 선행 번호 "(숫자) " 분리
            hwpScript = splitLeadingNumber(hwpScript, para);
            // 원문자 번호(⑴⑵⑶... ①②③...)로 수식 분할
            splitByCircledNumbers(hwpScript, para);
            // 이 flush 로 새로 추가된 EH 수식들에 원본 폰트 크기(preferredBaseUnit)를
            // 붙인다. 없으면 baseUnit 이 기본값(11pt)으로 떨어져 큰 제목 속 수식이
            // 본문보다 작게 나온다(실측: 3단원 "이차함수 y=ax² 의 그래프" 24pt).
            for (int i = beforeCount; i < para.items().size(); i++) {
                ASTInlineItem it = para.items().get(i);
                if (it instanceof ASTEquation) {
                    applyTextDerivedEquationHints((ASTEquation) it, ehRuns);
                }
            }
        } else {
            // 수식이 아닌 EH 폰트 텍스트 → 일반 텍스트 런으로 폴백
            for (IDMLCharacterRun run : ehRuns) {
                String text = run.content();
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
                    // EH 그룹이 수식으로 변환되지 못하고 텍스트로 폴백될 때, raw EH 해킹
                    // 글리프(Û→²/Ö→÷/µ→⌒…)가 그대로 노출되지 않게 디코딩한다(실측: 1·2단원
                    // (a+b)Û` = (a+b)², EH상부자 런이 수식 그룹에서 탈락한 케이스).
                    text = EHFontGlyphMap.decodeStrayGlyphText(text, run.fontFamily());
                    textRun.text(ASTPageProcessor.stripACEPlaceholders(text));
                    // 한국어만 텍스트에 EH 폰트/스타일 적용 방지
                    String ff = run.fontFamily();
                    if (ff != null && EHFontGlyphMap.isEHFontFamily(ff) && EHTextClassifier.isKoreanOnly(text)) {
                        // EH 폰트/스타일 제거 → 기본 폰트 사용
                    } else {
                        textRun.fontFamily(ff);
                        if (run.fontStyle() != null) textRun.fontStyle(run.fontStyle());
                    }
                    if (run.fontSize() != null) textRun.fontSizeHwpunits((int)(run.fontSize() * 100));
                    para.addItem(textRun);
                }
            }
        }
    }

    /**
     * EH 그룹에 근호(√) 구조 마커가 있는지 확인.
     *
     * <p>근호는 EH분수대문자 폰트의 분수선 장식 글리프(®, Â, ', { 등, 실제 분자
     * 값이 없는 비영숫자)로 인코딩된다. 이런 마커가 있으면 그룹은 화학식이 아니라
     * 근호 수식이므로, 화학식 경계 분할({@link #emitBoundaryAwareChemicalFormulaGroup})
     * 로 쪼개지 말고 EHFontEquationConverter 스택 파서에 통째로 넘겨야 한다.
     */
    private static boolean containsEHSqrtMarker(List<IDMLCharacterRun> ehRuns) {
        if (ehRuns == null) return false;
        for (IDMLCharacterRun run : ehRuns) {
            if (run == null) continue;
            String ff = run.fontFamily();
            if (EHFontGlyphMap.isRootFont(ff)) return true;
            if (EHFontGlyphMap.isFractionNumeratorFont(ff)
                    && EHFontGlyphMap.isFractionBarDecoration(run.content())) {
                return true;
            }
        }
        return false;
    }

    /**
     * EH 그룹에 GREP 분수 패턴(;...;)이 있는지 확인.
     *
     * <p>";2!;"(=1/2), "-;3%;"(=-3/5) 같은 GREP 분수는 EHFontEquationConverter 만
     * 디코딩할 수 있다. 이런 패턴이 일반 텍스트("-0.1", ",")·약물 폰트와 섞인 런은
     * ","·"-" 때문에 화학식으로 오인돼 GREP 분수가 원문 그대로 노출되므로, 화학식
     * 경계 분할({@link #emitBoundaryAwareChemicalFormulaGroup})을 건너뛰어야 한다.
     */
    private static boolean containsEHGrepFraction(List<IDMLCharacterRun> ehRuns) {
        if (ehRuns == null) return false;
        for (IDMLCharacterRun run : ehRuns) {
            if (run == null) continue;
            if (EHFontGlyphMap.containsEHFractionPattern(run.content())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 연속된 수식 폰트 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 (순수 텍스트 등) 일반 텍스트 런으로 폴백.
     */
    public static void flushMathGroup(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        if (emitBoundaryAwareChemicalFormulaGroup(mathRuns, para)) {
            return;
        }
        if (emitChemicalFormulaTextEquation(mathRuns, para)) {
            return;
        }
        if (emitPositionedFormulaEquation(mathRuns, para, "CHEM_FORMULA")) {
            return;
        }
        if (emitSimplePositionedTextRun(mathRuns, para)) {
            return;
        }
        String hwpScript = BTFontEquationConverter.convert(mathRuns);
        if (hwpScript != null && shouldEmitConvertedEquation(hwpScript, mathRuns)) {
            String sourceType = mathRuns.get(0).isBTFont() ? "BT_FONT" : "GREP_FONT";
            para.addItem(new ASTEquation(hwpScript, sourceType));
        } else {
            // 수식이 아닌 BT 폰트 텍스트 → 일반 텍스트 런으로 폴백
            for (IDMLCharacterRun run : mathRuns) {
                String text = run.content();
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
                    // 선행 thin space 마커(백틱, 틸드)를 제거하여 리터럴 문자로 나타나지 않도록
                    String cleaned = ASTPageProcessor.stripACEPlaceholders(text);
                    int mIdx = 0;
                    while (mIdx < cleaned.length() && (cleaned.charAt(mIdx) == '`' || cleaned.charAt(mIdx) == '~')) mIdx++;
                    if (mIdx > 0) cleaned = cleaned.substring(mIdx);
                    if (cleaned.isEmpty()) continue;
                    textRun.text(cleaned);
                    String ff = run.fontFamily();
                    if (run.isBTFont() || (run.grepMathFont() && !isPlainAlphanumericRun(run))) {
                        if (ff == null || !ff.contains("BT수식")) ff = "BT수식M";
                    }
                    textRun.fontFamily(ff);
                    textRun.grepMathFont(run.grepMathFont());
                    if (run.fontStyle() != null) textRun.fontStyle(run.fontStyle());
                    if (run.fontSize() != null) textRun.fontSizeHwpunits((int)(run.fontSize() * 100));
                    para.addItem(textRun);
                }
            }
        }
    }

    private static boolean emitBoundaryAwareChemicalFormulaGroup(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        if (mathRuns == null || mathRuns.isEmpty()) return false;
        List<IDMLCharacterRun> splitRuns = splitChemicalFormulaMixedRuns(mathRuns);
        boolean needsSegmentedFlush = splitRuns.size() != mathRuns.size();
        if (!needsSegmentedFlush) {
            for (IDMLCharacterRun run : splitRuns) {
                if (isChemicalFormulaBoundaryRun(run)) {
                    needsSegmentedFlush = true;
                    break;
                }
            }
        }
        if (!needsSegmentedFlush) return false;

        List<IDMLCharacterRun> formulaRuns = new ArrayList<>();
        boolean emittedFormula = false;
        boolean changed = false;
        for (IDMLCharacterRun run : splitRuns) {
            if (run == null) continue;
            if (isChemicalFormulaBoundaryRun(run)) {
                emittedFormula |= flushFormulaSegment(formulaRuns, para);
                formulaRuns.clear();
                emitTextRun(run, para);
                changed = true;
                continue;
            }

            String normalized = normalizeFormulaRunText(run);
            boolean formulaRun = isChemicalFormulaTextRun(run)
                    || run.isSubscript()
                    || run.isSuperscript()
                    || (!formulaRuns.isEmpty()
                    && (normalized.trim().isEmpty() || isFormulaEquationText(normalized)));
            if (formulaRun) {
                formulaRuns.add(run);
                continue;
            }

            emittedFormula |= flushFormulaSegment(formulaRuns, para);
            formulaRuns.clear();
            emitTextRun(run, para);
            changed = true;
        }
        emittedFormula |= flushFormulaSegment(formulaRuns, para);
        formulaRuns.clear();
        return changed;
    }

    private static boolean flushFormulaSegment(List<IDMLCharacterRun> formulaRuns, ASTParagraph para) {
        if (formulaRuns == null || formulaRuns.isEmpty()) return false;
        if (emitChemicalFormulaTextEquation(formulaRuns, para)) return true;
        if (emitPositionedFormulaEquation(formulaRuns, para, "CHEM_FORMULA")) return true;
        for (IDMLCharacterRun run : formulaRuns) {
            emitTextRun(run, para);
        }
        return false;
    }

    private static void emitTextRun(IDMLCharacterRun run, ASTParagraph para) {
        String text = cleanFormulaText(run != null ? run.content() : null);
        if (text.isEmpty()) return;
        para.addItem(textRunFromMathRun(run, text));
    }

    private static boolean emitChemicalFormulaTextEquation(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        // A plain chemical formula authored with character attributes is still text:
        // H2O, Cu2O, CaCO3 should keep editable runs with subscript/superscript
        // instead of becoming an HWP equation. Equation materialization is reserved
        // for clusters that need equation semantics: arrows, answer boxes, or
        // reaction-like operators.
        if (!canEmitChemicalFormulaTextEquation(mathRuns)) return false;
        String script = buildPositionedFormulaScript(mathRuns);
        if (script == null || script.trim().isEmpty()) return false;
        ASTEquation eq = new ASTEquation(stripChemicalFormulaInnerSpaces(script), "CHEM_FORMULA");
        applyTextDerivedEquationHints(eq, mathRuns);
        para.addItem(eq);
        return true;
    }

    private static boolean canEmitChemicalFormulaTextEquation(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null || mathRuns.isEmpty()) return false;
        boolean hasFormulaEvidence = false;
        StringBuilder script = new StringBuilder();
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) return false;
            if (run.isBTFont() || run.grepMathFont() || run.isEHFont() || run.isNPFont()) {
                hasFormulaEvidence = true;
            }
            String text = normalizeFormulaRunText(run);
            if (text.isEmpty()) continue;
            if (!isFormulaEquationText(text)) return false;
            if (run.isSubscript()) {
                String sub = stripFormulaScriptSpaces(text);
                if (!sub.isEmpty()) script.append("_{").append(sub).append("}");
            } else if (run.isSuperscript()) {
                String sup = stripFormulaScriptSpaces(text);
                if (!sup.isEmpty()) script.append("^{").append(sup).append("}");
            } else {
                script.append(text);
            }
        }
        if (!hasFormulaEvidence || !isChemicalFormulaElementSequence(script.toString())) return false;
        return hasReactionEquationEvidence(mathRuns);
    }

    /**
     * Chemical-style formula text such as H2O may be authored as separate text runs
     * with Position=Subscript/Superscript and inline answer boxes. Materialize the
     * whole local formula cluster as one HWP equation so runs, boxes, and arrows
     * do not split into unrelated inline objects.
     */
    private static boolean emitPositionedFormulaEquation(
            List<IDMLCharacterRun> mathRuns,
            ASTParagraph para,
            String sourceType) {
        if (!canEmitPositionedFormulaEquation(mathRuns)) return false;
        String script = buildPositionedFormulaScript(mathRuns);
        if (script == null || script.trim().isEmpty()) return false;
        ASTEquation eq = new ASTEquation(script.trim(), sourceType);
        applyTextDerivedEquationHints(eq, mathRuns);
        para.addItem(eq);
        return true;
    }

    private static void applyTextDerivedEquationHints(ASTEquation equation, List<IDMLCharacterRun> mathRuns) {
        if (equation == null || mathRuns == null || mathRuns.isEmpty()) return;
        Integer preferredBaseUnit = null;
        String preferredFontFamily = null;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) continue;
            if (preferredBaseUnit == null && run.fontSize() != null && run.fontSize() > 0.0) {
                preferredBaseUnit = (int) Math.round(run.fontSize() * 100.0);
            }
            if (preferredFontFamily == null
                    && run.fontFamily() != null
                    && !run.fontFamily().isEmpty()
                    && !EHFontGlyphMap.isEHFontFamily(run.fontFamily())
                    && !BTFontGlyphMap.isBTFontFamily(run.fontFamily())
                    && !NPFontGlyphMap.isNPFont(run.fontFamily())) {
                preferredFontFamily = run.fontFamily();
            }
            if (preferredBaseUnit != null && preferredFontFamily != null) break;
        }
        if (preferredBaseUnit != null && preferredBaseUnit > 0) {
            equation.preferredBaseUnit(preferredBaseUnit);
        }
        if (preferredFontFamily != null && !preferredFontFamily.isEmpty()) {
            equation.preferredFontFamily(preferredFontFamily);
        }
    }

    private static boolean canEmitPositionedFormulaEquation(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null || mathRuns.size() < 2) return false;

        // 실제 인라인 프레임/그래픽 앵커(U+FFFC)를 가진 run이 있으면 이 경로를 포기한다.
        // 여기서 앵커를 □(답안 상자)로 뭉개면 프레임이 유실된다(실측: 3단원 y=-x²/5 에서
        // x²/5 분수가 앵커 프레임인데 y=-□ 로 깨짐). 정상 인라인 앵커 배치 경로로 보낸다.
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) continue;
            if ((run.inlineFrames() != null && !run.inlineFrames().isEmpty())
                    || (run.inlineAnchors() != null && !run.inlineAnchors().isEmpty())) {
                return false;
            }
        }

        boolean hasFormulaLetter = false;
        boolean hasPositionedRun = false;
        boolean hasFormulaOperator = false;
        boolean hasAnswerBox = false;
        boolean hasArrow = false;
        int visibleChars = 0;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) return false;
            String normalized = normalizeFormulaRunText(run);
            if (normalized.isEmpty()) continue;
            if (!isFormulaEquationText(normalized)) return false;
            visibleChars += normalized.length();
            if (containsAsciiLetter(normalized)) hasFormulaLetter = true;
            if (run.isSubscript() || run.isSuperscript()) hasPositionedRun = true;
            if (containsFormulaOperator(normalized)) hasFormulaOperator = true;
            if (normalized.indexOf('\u2192') >= 0 || "rarrow".equalsIgnoreCase(normalized.trim())) hasArrow = true;
            if (normalized.indexOf('\u25A1') >= 0) hasAnswerBox = true;
        }
        if (!hasFormulaLetter || visibleChars == 0 || visibleChars > 96) return false;
        if (hasAnswerBox || hasArrow) return true;
        if (!hasFormulaOperator) return false;
        // A positioned run plus a reaction/operator context is equation-worthy;
        // positioned runs alone are text character attributes.
        return hasPositionedRun || hasFormulaOperator;
    }

    private static boolean hasReactionEquationEvidence(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null || mathRuns.isEmpty()) return false;
        boolean hasOperator = false;
        boolean hasArrow = false;
        boolean hasBox = false;
        boolean hasPositioned = false;
        boolean hasFormulaLetter = false;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) continue;
            String normalized = normalizeFormulaRunText(run);
            if (normalized == null || normalized.isEmpty()) continue;
            if (containsAsciiLetter(normalized)) hasFormulaLetter = true;
            if (run.isSubscript() || run.isSuperscript()) hasPositioned = true;
            if (containsFormulaOperator(normalized)) hasOperator = true;
            if (normalized.indexOf('\u2192') >= 0 || normalized.toLowerCase(java.util.Locale.ROOT).contains("rarrow")) {
                hasArrow = true;
            }
            if (normalized.indexOf('\u25A1') >= 0) hasBox = true;
        }
        if (!hasFormulaLetter) return false;
        if (hasArrow || hasBox) return true;
        return hasOperator && hasPositioned;
    }

    private static String buildPositionedFormulaScript(List<IDMLCharacterRun> mathRuns) {
        StringBuilder sb = new StringBuilder();
        for (IDMLCharacterRun run : mathRuns) {
            String text = normalizeFormulaRunText(run);
            if (text.isEmpty()) continue;
            if (run.isSubscript()) {
                String sub = stripFormulaScriptSpaces(text);
                if (!sub.isEmpty()) sb.append("_{").append(sub).append("}");
            } else if (run.isSuperscript()) {
                String sup = stripFormulaScriptSpaces(text);
                if (!sup.isEmpty()) sb.append("^{").append(sup).append("}");
            } else {
                sb.append(text);
            }
        }
        String script = sb.toString();
        script = script.replace("\u2192", " rarrow ");
        script = script.replaceAll("\\s+", " ").trim();
        script = script.replaceAll("\\s*\\+\\s*", "+");
        script = script.replaceAll("(?i)\\s*rarrow\\s*", " rarrow ");
        script = script.replaceAll("\\s*->\\s*", " rarrow ");
        if (isChemicalFormulaElementSequence(script)) {
            script = stripChemicalFormulaInnerSpaces(script);
        }
        return script;
    }

    public static boolean isChemicalFormulaTextRun(IDMLCharacterRun run) {
        if (run == null) return false;
        if (!(run.isBTFont() || run.grepMathFont() || run.isEHFont() || run.isNPFont())) return false;
        return isChemicalFormulaElementSequence(normalizeFormulaRunText(run));
    }

    private static String normalizeFormulaRunText(IDMLCharacterRun run) {
        if (run == null || run.content() == null) return "";
        String text = run.content();
        if (isBtArrowGlyphRun(run)) {
            return "\u2192";
        }
        String cleaned = text.trim();
        if ("@C".equals(cleaned) || "@c".equals(cleaned)
                || "?C".equals(cleaned) || "?c".equals(cleaned)) {
            return "\u2192";
        }
        text = text.replace("\uFFFC", "\u25A1");
        text = ASTPageProcessor.stripACEPlaceholders(text)
                .replace("`", "")
                .replace("~", "")
                .replace("&", "")
                .replace("\u2005", " ")
                .replace("\u2007", " ")
                .replace("\u2009", " ")
                .replace("\u200A", " ");
        return text;
    }

    private static String stripFormulaScriptSpaces(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "");
    }

    private static boolean isFormulaEquationText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (isAsciiLetter(c) || Character.isDigit(c)) continue;
            if (c == '\u25A1' || c == '\u2192') continue;
            if ("+-=()[]{}.,:".indexOf(c) >= 0) continue;
            return false;
        }
        return true;
    }

    private static boolean containsAsciiLetter(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (isAsciiLetter(text.charAt(i))) return true;
        }
        return false;
    }

    private static boolean containsFormulaOperator(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '+' || c == '-' || c == '=' || c == '\u2192') return true;
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isChemicalFormulaElementSequence(String script) {
        String token = chemicalFormulaToken(script);
        if (token.isEmpty()) return false;
        int i = 0;
        int elements = 0;
        boolean hasDigit = false;
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            hasDigit = true;
            i++;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c < 'A' || c > 'Z') return false;
            String one = String.valueOf(c);
            String symbol = one;
            int nextIndex = i + 1;
            if (nextIndex < token.length()) {
                char next = token.charAt(nextIndex);
                if (next >= 'a' && next <= 'z') {
                    String two = "" + c + next;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        nextIndex++;
                    }
                }
            }
            if (!isChemicalElement(symbol)) return false;
            elements++;
            i = nextIndex;
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                hasDigit = true;
                i++;
            }
        }
        return elements > 0 && (hasDigit || elements >= 2);
    }

    private static String chemicalFormulaToken(String script) {
        if (script == null || script.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '\u2005' || c == '\u2007' || c == '\u2009' || c == '\u200A'
                    || c == '_' || c == '^' || c == '{' || c == '}') {
                continue;
            }
            if (Character.isDigit(c) || isAsciiLetter(c)) {
                out.append(c);
                continue;
            }
            return "";
        }
        return out.toString();
    }

    private static String stripChemicalFormulaInnerSpaces(String script) {
        return script == null ? "" : script.replaceAll("\\s+", "");
    }

    private static boolean isChemicalElement(String symbol) {
        if (symbol == null) return false;
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

    private static boolean isBtArrowGlyphRun(IDMLCharacterRun run) {
        if (run == null) return false;
        String style = run.appliedCharacterStyle();
        String text = run.content();
        if (style == null || text == null) return false;
        String normalizedStyle = style.toLowerCase(java.util.Locale.ROOT)
                .replace("%3a", ":")
                .replace("%ed%99%94%ec%82%b4%ed%91%9c", "화살표");
        String cleaned = text.trim();
        return normalizedStyle.contains("화살표")
                && ("@C".equals(cleaned) || "@c".equals(cleaned)
                    || "?C".equals(cleaned) || "?c".equals(cleaned));
    }

    public static boolean isFormulaEquationClusterRun(
            IDMLCharacterRun run,
            List<IDMLCharacterRun> runs,
            int index) {
        if (run == null) return false;
        String text = run.content();
        boolean orcOnly = text != null && !text.isEmpty()
                && text.replace("\uFFFC", "").isEmpty();
        if (orcOnly) {
            return hasFormulaNeighbor(runs, index);
        }
        String normalized = normalizeFormulaRunText(run);
        if (normalized.isEmpty() || !isFormulaEquationText(normalized)) return false;
        if (run.isSubscript() || run.isSuperscript()) {
            return hasFormulaNeighbor(runs, index) || containsAsciiLetter(normalized);
        }
        if (isBtArrowGlyphRun(run) || containsFormulaOperator(normalized)) {
            return hasFormulaNeighbor(runs, index);
        }
        if (run.isBTFont() || run.grepMathFont() || run.isEHFont() || run.isNPFont()) {
            return hasFormulaNeighbor(runs, index);
        }
        return containsAsciiLetter(normalized) && hasFormulaNeighbor(runs, index);
    }

    public static IDMLCharacterRun formulaAnswerBoxRun(IDMLCharacterRun source) {
        IDMLCharacterRun run = new IDMLCharacterRun();
        if (source != null) {
            run.appliedCharacterStyle(source.appliedCharacterStyle());
            run.fontFamily(source.fontFamily());
            run.fontSize(source.fontSize());
            run.fillColor(source.fillColor());
            run.fontStyle(source.fontStyle());
            run.position(source.position());
            run.baselineShift(source.baselineShift());
            run.tracking(source.tracking());
            run.grepMathFont(source.grepMathFont());
        }
        run.content("\u25A1");
        if (run.fontFamily() == null) {
            run.fontFamily("BT수식M");
        }
        return run;
    }

    private static boolean hasFormulaNeighbor(List<IDMLCharacterRun> runs, int index) {
        return isFormulaNeighbor(runs, index - 1) || isFormulaNeighbor(runs, index + 1);
    }

    private static boolean isFormulaNeighbor(List<IDMLCharacterRun> runs, int index) {
        if (runs == null || index < 0 || index >= runs.size()) return false;
        IDMLCharacterRun run = runs.get(index);
        if (run == null) return false;
        String text = run.content();
        if (text != null && !text.isEmpty() && text.replace("\uFFFC", "").isEmpty()) {
            return true;
        }
        String normalized = normalizeFormulaRunText(run);
        if (normalized.isEmpty() || !isFormulaEquationText(normalized)) return false;
        if (run.isSubscript() || run.isSuperscript()
                || run.isBTFont() || run.grepMathFont()
                || run.isEHFont() || run.isNPFont()
                || isBtArrowGlyphRun(run)
                || containsFormulaOperator(normalized)) {
            return true;
        }
        return false;
    }

    private static boolean emitSimplePositionedFormulaTextRuns(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        if (!canEmitSimplePositionedFormulaTextRuns(mathRuns)) return false;

        for (IDMLCharacterRun run : mathRuns) {
            String cleaned = cleanFormulaText(run.content());
            if (cleaned.isEmpty()) continue;
            ASTTextRun textRun = textRunFromMathRun(run, cleaned);
            para.addItem(textRun);
        }
        return true;
    }

    private static boolean canEmitSimplePositionedFormulaTextRuns(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null || mathRuns.size() < 2) return false;

        boolean hasPositionedRun = false;
        int visibleChars = 0;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) return false;
            String cleaned = cleanFormulaText(run.content());
            if (cleaned.isEmpty()) continue;
            if (!isSimpleFormulaText(cleaned)) return false;
            visibleChars += cleaned.length();
            if (run.isSubscript() || run.isSuperscript()) {
                hasPositionedRun = true;
            }
        }
        if (!hasPositionedRun || visibleChars == 0 || visibleChars > 16) return false;
        return true;
    }

    /**
     * InDesign Position=Subscript/Superscript is a text character property.
     * A single short token such as the "2" in H2O should remain editable text
     * with HWPX subscript/superscript instead of becoming an equation object.
     */
    private static boolean emitSimplePositionedTextRun(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        if (mathRuns == null || mathRuns.size() != 1) return false;
        IDMLCharacterRun run = mathRuns.get(0);
        if (!run.isSubscript() && !run.isSuperscript()) return false;

        String cleaned = cleanSimplePositionedMathText(run.content());
        if (cleaned.isEmpty() || cleaned.length() > 4 || !isSimplePositionedToken(cleaned)) {
            return false;
        }

        ASTTextRun textRun = new ASTTextRun();
        copyMathRunTextStyle(run, textRun, cleaned);
        para.addItem(textRun);
        return true;
    }

    private static ASTTextRun textRunFromMathRun(IDMLCharacterRun run, String text) {
        ASTTextRun textRun = new ASTTextRun();
        copyMathRunTextStyle(run, textRun, text);
        if (isChemicalFormulaElementSequence(text)) {
            textRun.grepMathFont(true);
        }
        return textRun;
    }

    private static void copyMathRunTextStyle(IDMLCharacterRun run, ASTTextRun textRun, String text) {
        // EH 그룹이 수식으로 변환되지 못하고 텍스트 런으로 폴백되는 모든 경로
        // (emitBoundaryAwareChemicalFormulaGroup 등)의 공통 sink. raw EH 해킹 글리프
        // (Û→²/Ö→÷/µ→⌒…)를 여기서 디코딩한다(실측: 1단원 글상자의 (-5)Û`		⑷, 0.36Ö).
        text = EHFontGlyphMap.decodeStrayGlyphText(text, run.fontFamily());
        textRun.text(text);
        textRun.fontFamily(run.fontFamily());
        textRun.grepMathFont(run.grepMathFont());
        textRun.characterStyleRef(run.appliedCharacterStyle());
        textRun.subscript(run.isSubscript());
        textRun.superscript(run.isSuperscript());
        if (run.fontStyle() != null) textRun.fontStyle(run.fontStyle());
        if (run.fontSize() != null) textRun.fontSizeHwpunits((int) (run.fontSize() * 100));
        if (run.tracking() != null) textRun.letterSpacing((short) Math.round(run.tracking() / 10.0));
        if (run.horizontalScale() != null
                && run.horizontalScale() != 0
                && run.horizontalScale() != 100) {
            textRun.horizontalScale((short) Math.round(run.horizontalScale()));
        }
        if (run.baselineShift() != null && run.baselineShift() != 0) {
            textRun.baselineShift((short) Math.round(run.baselineShift()));
        }
    }

    private static String cleanSimplePositionedMathText(String text) {
        if (text == null) return "";
        String cleaned = ASTPageProcessor.stripACEPlaceholders(text)
                .replace("`", "")
                .replace("~", "")
                .replace("&", "")
                .replace("\u2005", "")
                .replace("\u2007", "")
                .replace("\u2009", "")
                .replace("\u200A", "")
                .trim();
        while (!cleaned.isEmpty() && (cleaned.charAt(0) == '_' || cleaned.charAt(0) == '^')) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    private static String cleanFormulaText(String text) {
        if (text == null) return "";
        return ASTPageProcessor.stripACEPlaceholders(text)
                .replace("`", "")
                .replace("~", "")
                .replace("&", "");
    }

    private static boolean isSimpleFormulaText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) continue;
            if (Character.isWhitespace(c) || c == '\u2005' || c == '\u2007' || c == '\u2009' || c == '\u200A') {
                continue;
            }
            if (c == ':' || c == '+' || c == '-' || c == '\u2192') continue;
            return false;
        }
        return true;
    }

    private static boolean isSimplePositionedToken(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '-') continue;
            return false;
        }
        return true;
    }

    /**
     * 수식 패턴으로 감지된 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 일반 텍스트 런으로 폴백.
     */
    static void flushPatternMathGroup(List<IDMLCharacterRun> patternRuns, ASTParagraph para) {
        flushPatternMathGroup(patternRuns, para, null);
    }

    static void flushPatternMathGroup(List<IDMLCharacterRun> patternRuns, ASTParagraph para,
                                        ColorResolver colorResolver) {
        if (emitPositionedFormulaEquation(patternRuns, para, "CHEM_FORMULA")) {
            return;
        }
        if (emitSimplePositionedTextRun(patternRuns, para)) {
            return;
        }
        String hwpScript = PatternEquationConverter.convert(patternRuns);
        if (hwpScript != null && shouldEmitConvertedEquation(hwpScript, patternRuns)) {
            // 수식 폰트 등록 (동적 학습)
            for (IDMLCharacterRun run : patternRuns) {
                if (run.fontFamily() != null) {
                    MathPatternDetector.registerDetectedMathFont(run.fontFamily());
                }
            }
            ASTEquation eq = new ASTEquation(hwpScript, "PATTERN_DETECT");
            if (colorResolver != null) {
                for (IDMLCharacterRun run : patternRuns) {
                    if (run.fillColor() != null) {
                        String hex = colorResolver.resolve(run.fillColor());
                        if (hex != null && !hex.isEmpty()) {
                            eq.textColor(hex);
                            break;
                        }
                    }
                }
            }
            para.addItem(eq);
        } else {
            // 변환 실패 → 일반 텍스트 런으로 폴백
            for (IDMLCharacterRun run : patternRuns) {
                String text = run.content();
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
                    textRun.text(ASTPageProcessor.stripACEPlaceholders(text));
                    textRun.fontFamily(run.fontFamily());
                    if (run.fontStyle() != null) textRun.fontStyle(run.fontStyle());
                    if (run.fontSize() != null) textRun.fontSizeHwpunits((int)(run.fontSize() * 100));
                    para.addItem(textRun);
                }
            }
        }
    }

    /**
     * 수식 시작의 "(숫자) " 번호 패턴을 분리하여 일반 텍스트로 출력.
     * 예: "(1) 3^{2}=9" → para에 "(1) " 텍스트 추가, "3^{2}=9" 반환
     */
    private static String splitLeadingNumber(String hwpScript, ASTParagraph para) {
        if (hwpScript.length() > 3 && hwpScript.charAt(0) == '(') {
            int closeIdx = hwpScript.indexOf(')');
            if (closeIdx > 0 && closeIdx <= 3) {
                String inner = hwpScript.substring(1, closeIdx);
                boolean isNumber = true;
                for (int ci = 0; ci < inner.length(); ci++) {
                    if (!Character.isDigit(inner.charAt(ci))) { isNumber = false; break; }
                }
                if (isNumber) {
                    int afterClose = closeIdx + 1;
                    while (afterClose < hwpScript.length()
                            && (hwpScript.charAt(afterClose) == ' '
                                || hwpScript.charAt(afterClose) == '\t')) {
                        afterClose++;
                    }
                    if (afterClose < hwpScript.length()) {
                        ASTTextRun numRun = new ASTTextRun();
                        numRun.text(hwpScript.substring(0, afterClose));
                        para.addItem(numRun);
                        return hwpScript.substring(afterClose);
                    }
                }
            }
        }
        return hwpScript;
    }

    /**
     * 원문자 번호(⑴⑵...⑼, ①②...⑨)로 수식을 분할.
     * 예: "sqrt{9 ⑵ -}sqrt{121}" → "sqrt{9}" + "⑵" + "-sqrt{121}"
     */
    private static void splitByCircledNumbers(String hwpScript, ASTParagraph para) {
        // 원문자 범위: ⑴-⑼ (U+2474-U+247C), ①-⑨ (U+2460-U+2468)
        int i = 0;
        int lastSplit = 0;
        while (i < hwpScript.length()) {
            char c = hwpScript.charAt(i);
            boolean isCircled = (c >= 0x2460 && c <= 0x2473)  // ①-⑳
                    || (c >= 0x2474 && c <= 0x2487); // ⑴-⒇
            boolean isTab = (c == '\t');
            if (isCircled || isTab) {
                // 원문자 앞 수식
                if (i > lastSplit) {
                    String before = hwpScript.substring(lastSplit, i).trim();
                    if (!before.isEmpty()) {
                        before = splitLeadingNumber(before, para);
                        if (!before.isEmpty()) {
                            addEquationOrTextFallback(before, "EH_FONT", para);
                        }
                    }
                }
                if (isTab) {
                    // 연속 탭 → 하나의 탭으로 병합
                    while (lastSplit < hwpScript.length() && hwpScript.charAt(lastSplit) == '\t') {
                        lastSplit++;
                    }
                    ASTTextRun tabRun = new ASTTextRun();
                    tabRun.text("\t");
                    para.addItem(tabRun);
                } else {
                    // 원문자 앞 여백 + 원문자 자체를 텍스트로
                    ASTTextRun numRun = new ASTTextRun();
                    numRun.text("  " + String.valueOf(c) + " ");
                    para.addItem(numRun);
                }
                lastSplit = i + 1;
                // 원문자 뒤 공백 스킵
                while (lastSplit < hwpScript.length() && hwpScript.charAt(lastSplit) == ' ') {
                    lastSplit++;
                }
                i = lastSplit;
            } else {
                i++;
            }
        }
        // 남은 수식
        if (lastSplit < hwpScript.length()) {
            String rest = hwpScript.substring(lastSplit).trim();
            if (!rest.isEmpty()) {
                rest = splitLeadingNumber(rest, para);
                if (!rest.isEmpty()) {
                    addEquationOrTextFallback(rest, "EH_FONT", para);
                }
            }
        } else if (lastSplit == 0) {
            // 원문자 없음 → 전체 수식
            addEquationOrTextFallback(hwpScript, "EH_FONT", para);
        }
    }

    private static void addEquationOrTextFallback(String script, String sourceType, ASTParagraph para) {
        if (shouldEmitConvertedEquation(script, null)) {
            para.addItem(new ASTEquation(script, sourceType));
            return;
        }
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(FormulaClassifier.hwpScriptFallbackText(script));
        para.addItem(textRun);
    }

    private static boolean shouldEmitConvertedEquation(String script, List<IDMLCharacterRun> mathRuns) {
        return FormulaClassifier.shouldEmitConvertedEquation(
                script,
                containsEncodedMathEvidence(mathRuns));
    }

    private static boolean containsEncodedMathEvidence(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null) return false;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null || run.content() == null) continue;
            String text = run.content();
            if (EHFontGlyphMap.containsEHEncodedChars(text)
                    || EHFontGlyphMap.containsEHFractionPattern(text)) {
                return true;
            }
        }
        return false;
    }

}
