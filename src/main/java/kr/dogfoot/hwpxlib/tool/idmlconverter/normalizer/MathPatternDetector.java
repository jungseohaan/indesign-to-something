package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 폰트에 의존하지 않는 콘텐츠 기반 수식 패턴 감지.
 * BT/NP/EH에 해당하지 않는 런에서 수식 패턴을 감지하고,
 * 감지된 런의 폰트를 동적 수식 폰트 레지스트리에 등록한다.
 */
class MathPatternDetector {

    /** 수식으로 감지된 폰트를 문서 단위로 저장하는 레지스트리 */
    private static final Set<String> detectedMathFonts = new HashSet<String>();

    /** 문서 변환 시작 시 레지스트리 초기화 */
    static void reset() {
        detectedMathFonts.clear();
    }

    /** 수식 패턴으로 감지된 폰트 등록 */
    static void registerDetectedMathFont(String fontFamily) {
        if (fontFamily != null && !fontFamily.isEmpty()) {
            detectedMathFonts.add(fontFamily);
        }
    }

    /** 등록된 수식 폰트인지 확인 */
    static boolean isRegisteredMathFont(String fontFamily) {
        return fontFamily != null && detectedMathFonts.contains(fontFamily);
    }

    // --- 수학 함수 키워드 ---
    private static final Set<String> MATH_FUNCTIONS = new HashSet<String>(Arrays.asList(
            "sin", "cos", "tan", "log", "ln", "lim", "max", "min",
            "gcd", "lcm", "det", "dim", "exp", "inf", "sup",
            "sec", "csc", "cot", "arcsin", "arccos", "arctan"
    ));

    // --- 일반 영단어 필터 (오인식 방지) ---
    private static final Set<String> COMMON_WORDS = new HashSet<String>(Arrays.asList(
            "the", "and", "for", "are", "but", "not", "you", "all",
            "can", "had", "her", "was", "one", "our", "out", "day",
            "get", "has", "him", "his", "how", "its", "may", "new",
            "now", "old", "see", "way", "who", "did", "let", "say",
            "she", "too", "use", "with", "that", "this", "from",
            "have", "been", "will", "more", "when", "what", "some",
            "them", "than", "each", "make", "like", "just", "over",
            "such", "take", "year", "also", "back", "into", "only",
            "come", "find", "give", "most", "they", "very", "after",
            "point", "about", "which", "their", "there", "other",
            "right", "think", "where", "would", "first", "could",
            "these", "being", "still", "every", "never", "start"
    ));

    // 수식 변수+연산자 조합 패턴 (x+1, 2a-3, a=b 등)
    private static final Pattern MATH_EXPR_PATTERN = Pattern.compile(
            "[a-zA-Z]\\s*[+\\-=<>≤≥≠×÷±]|[+\\-=<>≤≥≠×÷±]\\s*[a-zA-Z0-9]"
    );

    /**
     * 런의 텍스트가 수식 패턴에 해당하는지 판정.
     * 폰트 이름에 의존하지 않고 텍스트 콘텐츠만으로 판단.
     */
    static boolean isMathPattern(String text) {
        if (text == null || text.isEmpty()) return false;

        // 탭/공백/선지번호(⑴①ㄱ. 등) 제거 후 수식 부분만 추출하여 판정
        String cleaned = stripNonMathPrefix(text);
        if (cleaned.isEmpty()) return false;

        // 한국어 포함 → 수식 아님
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
            if (c >= 0x3131 && c <= 0x318E) return false;
        }

        // 일반 영단어이면 수식 아님
        String stripped = cleaned.trim();
        if (isCommonWord(stripped)) return false;

        // 유니코드 수학 기호 포함 → 수식
        if (hasUnicodeMathSymbol(cleaned)) return true;

        // 유니코드 그리스 문자 포함 → 수식
        if (hasGreekLetter(cleaned)) return true;

        // 유니코드 첨자 포함 → 수식
        if (hasUnicodeScript(cleaned)) return true;

        // 수학 함수 키워드 + 괄호/첨자 → 수식
        if (hasMathFunction(cleaned)) return true;

        // 변수+연산자 조합 패턴 → 수식
        if (MATH_EXPR_PATTERN.matcher(cleaned).find()) return true;

        // EH 분수 패턴 (;...;) 포함 → 수식
        if (hasEHFractionPattern(cleaned)) return true;

        return false;
    }

    /**
     * 단락 컨텍스트에서 수식 가능성 판정.
     * 이미 감지된 수식 폰트 또는 인접 수식 런이 있으면 격상.
     */
    static boolean isMathInContext(IDMLCharacterRun run,
                                    List<IDMLCharacterRun> runs, int idx) {
        // 이미 BT/NP/EH 수식 폰트인 경우 → 이미 처리됨
        if (run.isMathFont() || run.grepMathFont()) return false;

        String text = run.content();
        if (text == null || text.isEmpty()) return false;

        // 1차: 텍스트 자체가 수식 패턴
        if (isMathPattern(text)) return true;

        // 2차: 동적 레지스트리에 등록된 수식 폰트
        if (isRegisteredMathFont(run.fontFamily())) {
            // 등록된 수식 폰트지만 순수 숫자/단어이면 수식 아님
            if (isPlainNumberOrWord(text)) return false;
            return true;
        }

        return false;
    }

    /** 유니코드 수학 연산자 (U+2200-22FF, U+2A00-2AFF) 포함 여부 */
    private static boolean hasUnicodeMathSymbol(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 수학 연산자 블록
            if (c >= 0x2200 && c <= 0x22FF) return true;
            // 보충 수학 연산자
            if (c >= 0x2A00 && c <= 0x2AFF) return true;
            // 개별 수학 기호
            if (c == 0x00B1 || c == 0x00D7 || c == 0x00F7) return true; // ±×÷
        }
        return false;
    }

    /** 유니코드 그리스 문자 (U+0391-03C9) 포함 여부 */
    private static boolean hasGreekLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0391 && c <= 0x03C9) return true;
        }
        return false;
    }

    /** 유니코드 첨자 문자 (U+2070-209F) 포함 여부 */
    private static boolean hasUnicodeScript(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2070 && c <= 0x209F) return true;
            if (c == 0x00B2 || c == 0x00B3 || c == 0x00B9) return true; // ²³¹
        }
        return false;
    }

    /** 수학 함수 키워드 + 괄호/첨자가 뒤따르는지 확인 */
    private static boolean hasMathFunction(String text) {
        String lower = text.toLowerCase();
        for (String func : MATH_FUNCTIONS) {
            int idx = lower.indexOf(func);
            if (idx >= 0) {
                int after = idx + func.length();
                if (after >= lower.length()) {
                    // 함수명으로만 구성된 경우 → 함수 키워드 전후에 일반 영문자가 없어야 함
                    boolean prefixOk = idx == 0 || !Character.isLetter(lower.charAt(idx - 1));
                    return prefixOk;
                }
                char next = lower.charAt(after);
                boolean prefixOk = idx == 0 || !Character.isLetter(lower.charAt(idx - 1));
                boolean suffixOk = next == '(' || next == '_' || next == '^'
                        || next == ' ' || Character.isDigit(next)
                        || next >= 0x2080; // 유니코드 첨자
                if (prefixOk && suffixOk) return true;
            }
        }
        return false;
    }

    /** 일반 영단어인지 확인 (3글자 이상 연속 알파벳) */
    private static boolean isCommonWord(String text) {
        // 공백/구두점으로 분리
        String[] words = text.split("[^a-zA-Z]+");
        for (String word : words) {
            if (word.length() >= 3 && COMMON_WORDS.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 선지번호(⑴①ㄱ. 등), 탭, 앞뒤 공백 제거 후 수식 부분만 추출.
     * InDesign 탭(열 정렬용)과 선지번호가 수식 감지를 방해하지 않도록 한다.
     */
    private static String stripNonMathPrefix(String text) {
        // 앞뒤 탭/공백 제거
        int start = 0;
        int end = text.length();
        while (start < end) {
            char c = text.charAt(start);
            if (c == ' ' || c == '\t' || c == '\u2009' || c == '\u200A') {
                start++;
            } else if (c >= 0x2460 && c <= 0x2473) {
                start++; // ①-⑳
            } else if (c >= 0x2474 && c <= 0x2487) {
                start++; // ⑴-⒇
            } else if (c >= 0x3131 && c <= 0x314E && start + 1 < end && text.charAt(start + 1) == '.') {
                start += 2; // ㄱ. ㄴ. 등
            } else {
                break;
            }
        }
        while (end > start) {
            char c = text.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\u2009' || c == '\u200A') {
                end--;
            } else {
                break;
            }
        }
        if (start >= end) return "";
        return text.substring(start, end);
    }

    /** EH 분수 패턴 (;...;) 포함 여부 확인 */
    private static boolean hasEHFractionPattern(String text) {
        int firstSemi = text.indexOf(';');
        if (firstSemi < 0) return false;
        int secondSemi = text.indexOf(';', firstSemi + 1);
        if (secondSemi < 0) return false;
        // ; 사이에 1~10자 내용이 있어야 함
        int innerLen = secondSemi - firstSemi - 1;
        return innerLen >= 1 && innerLen <= 10;
    }

    /** 순수 숫자 또는 영단어인지 확인 (수식 폰트라도 수식 아님) */
    private static boolean isPlainNumberOrWord(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;

        // 순수 숫자
        boolean allDigits = true;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i)) && trimmed.charAt(i) != '.'
                    && trimmed.charAt(i) != ',' && trimmed.charAt(i) != ' ') {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return true;

        // 일반 영단어
        return isCommonWord(trimmed);
    }
}
