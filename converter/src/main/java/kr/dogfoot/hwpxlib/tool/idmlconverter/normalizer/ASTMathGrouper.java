package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHTextClassifier;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.PatternEquationConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
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
    static boolean isPlainAlphanumericRun(IDMLCharacterRun run) {
        // BT 폰트가 직접 적용된 런은 수식으로 간주
        if (run.isBTFont()) return false;
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
     * BT 수식 런 사이 또는 뒤의 비수식 런이 수식 그룹에 포함될 수 있는지 확인.
     * 비한국어 텍스트이고, (1) 뒤에 BT 수식 런이 이어지거나 (2) BT 마커를 포함하면 수식 그룹에 포함.
     */
    public static boolean isMathBridgeRun(IDMLCharacterRun run, List<IDMLCharacterRun> runs, int idx) {
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
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
        if (emitPositionedFormulaEquation(ehRuns, para, "CHEM_FORMULA")) {
            return;
        }
        if (emitSimplePositionedTextRun(ehRuns, para)) {
            return;
        }
        String hwpScript = EHFontEquationConverter.convert(ehRuns);
        if (hwpScript != null) {
            // 선행 번호 "(숫자) " 분리
            hwpScript = splitLeadingNumber(hwpScript, para);
            // 원문자 번호(⑴⑵⑶... ①②③...)로 수식 분할
            splitByCircledNumbers(hwpScript, para);
        } else {
            // 수식이 아닌 EH 폰트 텍스트 → 일반 텍스트 런으로 폴백
            for (IDMLCharacterRun run : ehRuns) {
                String text = run.content();
                if (text != null && !text.isEmpty()) {
                    ASTTextRun textRun = new ASTTextRun();
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
     * 연속된 수식 폰트 런 그룹을 ASTEquation으로 변환하여 단락에 추가.
     * 수식으로 변환할 수 없는 경우 (순수 텍스트 등) 일반 텍스트 런으로 폴백.
     */
    public static void flushMathGroup(List<IDMLCharacterRun> mathRuns, ASTParagraph para) {
        if (emitPositionedFormulaEquation(mathRuns, para, "CHEM_FORMULA")) {
            return;
        }
        if (emitSimplePositionedTextRun(mathRuns, para)) {
            return;
        }
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
        para.addItem(new ASTEquation(script.trim(), sourceType));
        return true;
    }

    private static boolean canEmitPositionedFormulaEquation(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null || mathRuns.size() < 2) return false;

        boolean hasFormulaLetter = false;
        boolean hasPositionedRun = false;
        boolean hasFormulaOperator = false;
        boolean hasAnswerBox = false;
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
            if (normalized.indexOf('\u25A1') >= 0) hasAnswerBox = true;
        }
        if (!hasFormulaLetter || visibleChars == 0 || visibleChars > 96) return false;
        return hasPositionedRun || hasFormulaOperator || hasAnswerBox;
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
        return script;
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
        return containsAsciiLetter(normalized);
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
        return textRun;
    }

    private static void copyMathRunTextStyle(IDMLCharacterRun run, ASTTextRun textRun, String text) {
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
        if (hwpScript != null) {
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
                            para.addItem(new ASTEquation(before, "EH_FONT"));
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
                    para.addItem(new ASTEquation(rest, "EH_FONT"));
                }
            }
        } else if (lastSplit == 0) {
            // 원문자 없음 → 전체 수식
            para.addItem(new ASTEquation(hwpScript, "EH_FONT"));
        }
    }

}
