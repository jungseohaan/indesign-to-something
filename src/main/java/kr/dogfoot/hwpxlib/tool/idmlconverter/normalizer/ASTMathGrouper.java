package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.ArrayList;
import java.util.List;

/**
 * BT 수식 폰트 런 그룹핑 및 수식 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
class ASTMathGrouper {

    /**
     * 한국어+수식마커 혼합 런을 한국어/비한국어 경계에서 분리한다.
     * 예: "&P_r를 구해 보자" → "&P_r" + "를 구해 보자"
     * BT 폰트/grepMath 런이나 한국어/수식마커가 혼합되지 않은 런은 그대로 통과.
     */
    static List<IDMLCharacterRun> splitMathKoreanMixedRuns(List<IDMLCharacterRun> runs) {
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
                        || "+-*/=<>()[]{}|!.".indexOf(c) >= 0) hasLatinMath = true;
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
                if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) {
                    types[i] = 1; // KOREAN
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '^' || c == '&'
                        || c == '\\' || c == '`' || "+-*/=<>()[]{}|!.".indexOf(c) >= 0) {
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
            for (int i = 1; i <= len; i++) {
                if (i == len || types[i] != types[segStart]) {
                    String segText = text.substring(segStart, i);
                    IDMLCharacterRun subRun = cloneRunForSplit(run, segText);
                    // 첫 번째 분할 런에 인라인 프레임/그래픽을 보존
                    if (first) {
                        for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                            subRun.addInlineGraphic(ig);
                        }
                        first = false;
                    }
                    result.add(subRun);
                    segStart = i;
                }
            }
        }
        return result;
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
        clone.tracking(source.tracking());
        clone.grepMathFont(source.grepMathFont());
        clone.content(newText);
        return clone;
    }

    /**
     * 텍스트가 수식처럼 보이는지 확인.
     * BT 마커(_^&\), BT 키워드(.c3), 또는 연산자+변수 조합을 감지한다.
     */
    static boolean looksLikeMathRun(String text) {
        if (text == null || text.isEmpty()) return false;
        // BT 마커
        boolean hasOperator = false;
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '^' || c == '&' || c == '\\') return true;
            if ("+-*/=<>()[]{}|".indexOf(c) >= 0) hasOperator = true;
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
    static boolean isPlainAlphanumericRun(IDMLCharacterRun run) {
        // BT 폰트가 직접 적용된 런은 수식으로 간주
        if (run.isBTFont()) return false;
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == ',') continue;
            // 수식 마커나 연산자가 있으면 순수 알파벳/숫자가 아님
            return false;
        }
        return true;
    }

    /**
     * BT 폰트 런의 내용이 한국어만 포함하는지 확인.
     * 한국어 + 공백/구두점만 있고 라틴 문자, 숫자, 수식 마커/연산자가 없으면 true.
     * BT 폰트 런이라도 한국어만 있으면 수식이 아닌 일반 텍스트로 처리하기 위해 사용.
     */
    static boolean isBTRunWithOnlyKorean(String text) {
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
     * BT 수식 런 사이 또는 뒤의 비수식 런이 수식 그룹에 포함될 수 있는지 확인.
     * 비한국어 텍스트이고, (1) 뒤에 BT 수식 런이 이어지거나 (2) BT 마커를 포함하면 수식 그룹에 포함.
     */
    static boolean isMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
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
        if (onlyAlphanumeric && !run.isBTFont() && !run.grepMathFont()) return false;
        // 뒤에 BT 수식 런이 있는지 확인 (비한국어 런은 건너뜀)
        for (int j = idx + 1; j < runs.size(); j++) {
            IDMLCharacterRun next = runs.get(j);
            if (next.isBTFont() || next.grepMathFont()) return true;
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
    static void flushNPMathGroup(List<IDMLCharacterRun> npRuns, ASTParagraph para) {
        String hwpScript = NPFontEquationConverter.convert(npRuns);
        if (hwpScript != null) {
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
                    textRun.text(Stage4_BuildAST.stripACEPlaceholders(text));
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
    static boolean isStandaloneMathRun(IDMLCharacterRun run) {
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
    static boolean isPreNPMathRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
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
    static boolean isNPMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
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
     * 연속된 수식 폰트 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 (순수 텍스트 등) 일반 텍스트 런으로 폴백.
     */
    static void flushMathGroup(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        String hwpScript = BTFontEquationConverter.convert(mathRuns);
        if (hwpScript != null) {
            String sourceType = mathRuns.get(0).isBTFont() ? "BT_FONT" : "GREP_FONT";
            para.addItem(new ASTEquation(hwpScript, sourceType));
        } else {
            // 수식이 아닌 BT 폰트 텍스트 → 일반 텍스트 런으로 폴백
            for (IDMLCharacterRun run : mathRuns) {
                String text = run.content();
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
                    textRun.text(Stage4_BuildAST.stripACEPlaceholders(text));
                    String ff = run.fontFamily();
                    if (run.isBTFont() || run.grepMathFont()) {
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
}
