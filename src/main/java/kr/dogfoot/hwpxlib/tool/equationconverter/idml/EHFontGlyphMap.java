package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

/**
 * EH 수식 폰트 체계의 감지 및 글리프 디코딩.
 * <p>
 * EH 폰트는 수학교과서(비상교육)에서 사용되는 수식 전용 폰트.
 * 표준 유니코드 cmap 테이블(U+0000-00FF)을 사용하지만,
 * 실제 글리프 모양이 수학 기호/문자로 교체된 "폰트 해킹" 방식.
 * <p>
 * 폰트 변형별 역할:
 * - EH수식: 기본 수식 조판 (합성 글리프, 큰 괄호, ∑, ∫ 등)
 * - EH상부자/EH고딕상부자: 위첨자 문자
 * - EH하부자/EH고딕하부자: 아래첨자 문자
 * - EH분수대문자: 분수 분자 (윗줄 포함)
 * - EH분수소문자: 분수 분모 (아랫줄 포함)
 * - EH루트: 제곱근 기호 빌딩 블록
 * - EH약물: 화학식 문자
 * - EH선모음: 선/괄선 요소
 * - EH초등: 초등수학용 큰 문자
 */
public class EHFontGlyphMap {

    /**
     * fontFamily 문자열이 EH 수식 폰트인지 확인.
     */
    public static boolean isEHFontFamily(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH");
    }

    /**
     * AppliedCharacterStyle 문자열이 EH 수식 폰트 스타일인지 확인.
     * EH 교과서에서 사용하는 문자 스타일명 패턴도 포함.
     */
    public static boolean isEHFontStyle(String styleRef) {
        if (styleRef == null) return false;
        if (styleRef.contains("/EH")) return true;
        // 비상교육 교과서 EH 문자 스타일명 패턴
        return styleRef.contains("상부자") || styleRef.contains("하부자")
                || styleRef.contains("분수대문자") || styleRef.contains("분수소문자")
                || styleRef.contains("선모음") || styleRef.contains("약물")
                || styleRef.contains("수식") || styleRef.contains("루트");
    }

    /**
     * CharacterStyle 이름에서 EH 폰트 변형명 추출.
     * 예: "태광10%3a분수대문자 10" → "EH분수대문자"
     *     "CharacterStyle/분수대문자" → "EH분수대문자"
     */
    public static String extractFontFromStyle(String styleRef) {
        if (styleRef == null) return null;
        if (styleRef.contains("상부자")) return "EH상부자";
        if (styleRef.contains("하부자")) return "EH하부자";
        if (styleRef.contains("분수대문자")) return "EH분수대문자";
        if (styleRef.contains("분수소문자")) return "EH분수소문자";
        if (styleRef.contains("선모음")) return "EH선모음";
        if (styleRef.contains("약물")) return "EH약물";
        if (styleRef.contains("수식")) return "EH수식";
        if (styleRef.contains("루트")) return "EH루트";
        if (styleRef.contains("/EH")) {
            int idx = styleRef.indexOf("/EH");
            String rest = styleRef.substring(idx + 1);
            int sp = rest.indexOf(' ');
            return sp > 0 ? rest.substring(0, sp) : rest;
        }
        return "EH수식"; // 기본 폴백
    }

    /**
     * EH분수대문자 텍스트가 분수선 장식 글리프만 포함하는지 판별.
     * 확장 범위(0x80+) 글리프만 있고 기본 범위 숫자/문자가 없으면 장식.
     */
    public static boolean isFractionBarDecoration(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') return false;
            if (c >= 'A' && c <= 'Z') return false;
            if (c >= 'a' && c <= 'z') return false;
        }
        return true;
    }

    /**
     * 텍스트가 EH 인코딩 패턴을 포함하는지 확인.
     * EH 상부자/하부자 확장 범위 숫자 문자(0xDA-0xE2)를 감지.
     * 대부분 뒤에 백틱(0x60)이 따라오지만 런 경계에서 생략될 수 있다.
     * 폰트가 명시되지 않은 런에서도 EH 인코딩을 식별할 수 있게 한다.
     */
    public static boolean containsEHEncodedChars(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 핵심 숫자 매핑 범위 (0xDA-0xE2)
            if (c >= 0xDA && c <= 0xE2) {
                return true;
            }
        }
        return false;
    }

    // ── 폰트 역할 판별 ──

    /** 위첨자 폰트인지 확인 (EH상부자, EH고딕상부자) */
    public static boolean isSuperscriptFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH상부자")
                || fontFamily.startsWith("EH고딕상부자");
    }

    /** 아래첨자 폰트인지 확인 (EH하부자, EH고딕하부자) */
    public static boolean isSubscriptFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH하부자")
                || fontFamily.startsWith("EH고딕하부자");
    }

    /** 분수 분자 폰트인지 확인 (EH분수대문자) */
    public static boolean isFractionNumeratorFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH분수대문자");
    }

    /** 분수 분모 폰트인지 확인 (EH분수소문자) */
    public static boolean isFractionDenominatorFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH분수소문자");
    }

    /** 분수 폰트 (분자 또는 분모) */
    public static boolean isFractionFont(String fontFamily) {
        return isFractionNumeratorFont(fontFamily) || isFractionDenominatorFont(fontFamily);
    }

    /** 루트(제곱근) 폰트인지 확인 */
    public static boolean isRootFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH루트");
    }

    /** 수식 기본 폰트인지 확인 (EH수식) */
    public static boolean isBaseEquationFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH수식");
    }

    /** 약물(화학식) 폰트인지 확인 */
    public static boolean isChemicalFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH약물");
    }

    /** 선모음 폰트인지 확인 */
    public static boolean isLineFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("EH선모음");
    }

    /**
     * 텍스트에 EH 분수 GREP 인코딩 패턴 (;...;) 이 포함되어 있는지 확인.
     * ParagraphStyle GREP 규칙으로 분수소문자 폰트가 적용되는 패턴:
     * (;{1,2}).*?(;{1,2})
     */
    public static boolean containsEHFractionPattern(String text) {
        if (text == null || text.isEmpty()) return false;
        int first = text.indexOf(';');
        if (first < 0) return false;
        int second = text.indexOf(';', first + 1);
        if (second < 0) return false;
        int innerLen = second - first - 1;
        return innerLen >= 1 && innerLen <= 10;
    }

    // ── 분수 기본범위 글리프 디코딩 ──

    /**
     * EH 분수소문자 폰트의 기본 범위(0x20-0x7F) 글리프 디코딩.
     * <p>
     * EH 분수소문자 폰트는 "폰트 해킹"으로 ASCII 글리프를 교체:
     * - 분자(분수선 위): shift-digit → 숫자, 대문자 → 소문자
     * - 분모(분수선 아래): 숫자/소문자 → 그대로
     * - 구조: ; = 분수선, [] {} | = 괄호
     * <p>
     * GREP 스타일 (;{1,2}).*?(;{1,2})로 적용되므로,
     * 폰트 지정 없는 런에서도 ;...; 패턴으로 분수를 감지할 수 있다.
     *
     * @return 2-element String[]{numerator, denominator}, 또는 분수가 아니면 null
     */
    public static String[] decodeFractionInner(String inner) {
        if (inner == null || inner.isEmpty()) return null;

        StringBuilder numer = new StringBuilder();
        StringBuilder denom = new StringBuilder();

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);

            // 확장 범위 (0x80+): 상부자 매핑 사용 → 분자 숫자 인코딩
            if (c >= 0x80) {
                char decoded = decodeSubSupGlyph(c);
                if (decoded != c) {
                    numer.append(decoded);
                }
                continue;
            }

            // Shift-digit → 분자 숫자 (! @ # $ % ^ & * ( ) → 1 2 3 4 5 6 7 8 9 0)
            char fracNum = decodeFractionNumeratorDigit(c);
            if (fracNum != 0) {
                numer.append(fracNum);
                continue;
            }

            // 대문자 A-Z → 분자 소문자 a-z
            if (c >= 'A' && c <= 'Z') {
                numer.append((char) (c - 'A' + 'a'));
                continue;
            }

            // 숫자 0-9 → 분모
            if (c >= '0' && c <= '9') {
                denom.append(c);
                continue;
            }

            // 소문자 a-z → 분모
            if (c >= 'a' && c <= 'z') {
                denom.append(c);
                continue;
            }

            // 연산자: +, -, = 등 → 분수 내용에 포함 (위치에 따라 판단)
            if (c == '+' || c == '-' || c == '=') {
                // 앞에 분자가 있으면 분자에, 없으면 분모에 추가
                if (numer.length() > 0 && denom.length() == 0) {
                    numer.append(c);
                } else {
                    denom.append(c);
                }
                continue;
            }

            // 구조 문자 (괄호): 스킵 (분수 시각 장식)
            // [, ], {, }, | → 분수 괄호 요소
        }

        if (numer.length() == 0 && denom.length() == 0) return null;
        return new String[]{numer.toString(), denom.toString()};
    }

    /**
     * Shift-digit 패턴으로 분자 숫자 디코딩.
     * 키보드 Shift+숫자키: ! @ # $ % ^ & * ( ) → 1 2 3 4 5 6 7 8 9 0
     *
     * @return 디코딩된 숫자 문자, 또는 매핑 없으면 0
     */
    public static char decodeFractionNumeratorDigit(char c) {
        switch (c) {
            case '!': return '1';
            case '@': return '2';
            case '#': return '3';
            case '$': return '4';
            case '%': return '5';
            case '^': return '6';
            case '&': return '7';
            case '*': return '8';
            case '(': return '9';
            case ')': return '0';
            default: return 0;
        }
    }

    /**
     * 분수 인코딩 문자인지 확인 (분자 shift-digit 또는 분모 숫자/문자).
     * ; 사이에 올 수 있는 분수 내용 문자.
     */
    public static boolean isFractionContentChar(char c) {
        if (decodeFractionNumeratorDigit(c) != 0) return true;  // shift-digit
        if (c >= 'A' && c <= 'Z') return true;  // 분자 대문자
        if (c >= '0' && c <= '9') return true;   // 분모 숫자
        if (c >= 'a' && c <= 'z') return true;   // 분모 소문자
        if (c >= 0x80) return true;               // 확장 범위
        if (c == '+' || c == '-') return true;    // 부호
        return false;
    }

    // ── 글리프 디코딩 ──

    /**
     * EH 상부자/하부자/고딕상부자/고딕하부자 확장 범위 (0x80-0xFF) 글리프 디코딩.
     * 이 폰트들은 Latin-1 Supplement 영역의 글리프를 표준 ASCII 문자로 교체.
     * <p>
     * 매핑은 TTF 글리프 렌더링 분석으로 구축 (EH상부자-Plain.ttf 기준).
     *
     * @param c 원본 유니코드 문자
     * @return 디코딩된 문자, 또는 매핑 없으면 원본
     */
    public static char decodeSubSupGlyph(char c) {
        if (c < 0x80) return c;  // ASCII 범위는 패스스루
        switch (c) {
            // 0x80 영역: 대문자/소문자 중복
            case 0x81: return 'A';
            case 0x82: return 'C';
            case 0x8C: return 'a';
            case 0x8D: return 'c';

            // 0xA0 영역: 숫자, 소문자, 대문자
            case 0xA1: return '8';    // ¡ → 8
            case 0xA2: return '4';    // ¢ → 4
            case 0xA3: return '3';    // £ → 3
            case 0xA4: return '6';    // ¤ → 6
            case 0xA5: return '8';    // ¥ → 8 (중복)
            case 0xA6: return '7';    // ¦ → 7
            case 0xA7: return 's';    // § → s
            case 0xA8: return 'r';    // ¨ → r
            case 0xA9: return 'g';    // © → g
            case 0xAA: return '2';    // ª → 2
            case 0xAB: return 'E';    // « → E
            case 0xAC: return 'U';    // ¬ → U
            case 0xAF: return 'O';    // ¯ → O (with overline → O)

            // 0xB0 영역: 숫자, 기호, 소문자, 대문자
            case 0xB0: return '5';    // ° → 5
            case 0xB1: return '+';    // ± → +
            case 0xB2: return '\u2192'; // ² → → (right arrow)
            case 0xB3: return '\u2192'; // ³ → → (right arrow)
            case 0xB4: return 'y';    // ´ → y
            case 0xB5: return 'm';    // µ → m
            case 0xB6: return 'd';    // ¶ → d
            case 0xB7: return 'w';    // · → w
            case 0xB8: return 'P';    // ¸ → P
            case 0xB9: return 'p';    // ¹ → p
            case 0xBA: return 'b';    // º → b
            case 0xBB: return '9';    // » → 9
            case 0xBC: return '0';    // ¼ → 0
            case 0xBD: return 'z';    // ½ → z
            case 0xBE: return '\u2103'; // ¾ → ℃ (섭씨)
            case 0xBF: return '\u00B0'; // ¿ → ° (도)

            // 0xC0 영역: 소문자, 기호
            case 0xC1: return 'l';    // Á → l (소문자 L)
            case 0xC2: return 'l';    // Â → l (소문자 L, 변형)
            case 0xC3: return 'v';    // Ã → v
            case 0xC4: return 'f';    // Ä → f
            case 0xC5: return 'x';    // Å → x
            case 0xC6: return 'j';    // Æ → j
            case 0xC7: return 'n';    // Ç → n
            case 0xC8: return '|';    // È → | (세로선)
            case 0xC9: return 'u';    // É → u
            case 0xCE: return 'Q';    // Î → Q
            case 0xCF: return 'q';    // Ï → q

            // 0xD0 영역: 기호, 소문자, 숫자
            case 0xD0: return '-';    // Ð → - (마이너스)
            case 0xD1: return 'e';    // Ñ → e
            // 0xD2: 긴 수평선 (분수선/장식선) → 무시
            case 0xD4: return 'i';    // Ô → i
            // 0xD5: 중간 수평선 → 무시
            case 0xD6: return '\u00F7'; // Ö → ÷ (나눗셈)
            case 0xD7: return 'V';    // × → V (대문자)

            // 0xDA-0xE2: 숫자 (핵심 매핑)
            case 0xDA: return '1';    // Ú → 1
            case 0xDB: return '2';    // Û → 2 ★ 핵심: y=ax²의 ² 인코딩
            case 0xDC: return '3';    // Ü → 3
            case 0xDD: return '4';    // Ý → 4
            case 0xDE: return '5';    // Þ → 5
            case 0xDF: return '6';    // ß → 6
            case 0xE0: return '7';    // à → 7
            case 0xE1: return '9';    // á → 9
            case 0xE2: return '0';    // â → 0

            // 0xE3-0xEF: 대문자
            case 0xE3: return 'W';    // ã → W
            case 0xE4: return 'R';    // ä → R
            case 0xE5: return 'M';    // å → M
            case 0xE7: return 'Y';    // ç → Y
            case 0xEA: return 'S';    // ê → S
            case 0xEB: return 'D';    // ë → D
            case 0xEC: return 'F';    // ì → F
            case 0xEE: return 'H';    // î → H
            case 0xEF: return 'J';    // ï → J

            // 0xF0-0xFF: 대문자/소문자, 기호
            case 0xF0: return 'K';    // ð → K
            case 0xF1: return 'L';    // ñ → L
            // 0xF2: 긴 수평선 → 무시
            case 0xF5: return 'B';    // õ → B
            case 0xF6: return 'I';    // ö → I
            case 0xF7: return 'N';    // ÷ → N
            case 0xF8: return '\u2192'; // ø → → (화살표)
            case 0xF9: return '\u00B0'; // ù → ° (도)
            case 0xFA: return 'h';    // ú → h
            case 0xFB: return 'k';    // û → k
            case 0xFC: return 'Z';    // ü → Z
            case 0xFD: return 'G';    // ý → G
            case 0xFE: return 'X';    // þ → X
            case 0xFF: return 'T';    // ÿ → T

            default: return c;
        }
    }

    /**
     * EH 상부자/하부자 텍스트를 디코딩.
     *
     * @param text 원본 텍스트 (EH 폰트 인코딩)
     * @return 디코딩된 텍스트
     */
    public static String decodeSubSupText(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char decoded = decodeSubSupGlyph(c);
            // 수평선 글리프는 스킵
            if (c >= 0x80 && decoded == c) {
                // 매핑 없는 확장 문자 → 스킵 (빈 글리프이거나 장식선)
                continue;
            }
            sb.append(decoded);
        }
        return sb.toString();
    }

    /**
     * EH수식 폰트 확장 범위 (0x80-0xFF) 글리프 디코딩.
     * EH수식은 합성(composition) 폰트로, 큰 괄호/∑/∫ 등 특수 기호를 포함.
     * 기본 범위(0x20-0x7F)도 이탤릭 수식 문자로 교체되어 있음.
     */
    public static char decodeBaseEquationGlyph(char c) {
        if (c < 0x80) return c;  // 기본 범위는 별도 처리 필요하지만 우선 패스스루
        switch (c) {
            // 대문자 (큰 글리프, 합성용 — 단독 사용 시 대문자로 디코딩)
            case 0x81: return 'A';
            case 0x82: return 'C';
            case 0x8C: return 'A';  // 위치 변형
            case 0x8D: return 'C';  // 위치 변형
            case 0x9C: return 'A';
            case 0x9D: return 'C';

            // 괄호/구분자 (큰 버전)
            case 0xA0: return '}';
            case 0xA2: return 'y';
            case 0xA3: return 'g';
            case 0xA4: return ']';
            case 0xA5: return ')';
            case 0xA6: return ')';
            case 0xA7: return '(';
            case 0xAB: return 'E';
            case 0xAC: return 'U';
            case 0xAF: return 'O';

            case 0xB0: return '[';
            case 0xB1: return '{';
            case 0xB2: return 'N';
            case 0xB3: return 'K';
            case 0xB7: return 'S';
            case 0xB8: return 'R';
            case 0xB9: return 'G';

            // 대문자 (일반 크기)
            case 0xC3: return 'Y';
            case 0xC4: return 'M';
            case 0xC5: return 'D';
            case 0xC6: return 'W';
            case 0xC8: return '\u03C0'; // π
            case 0xC9: return 'P';
            case 0xCA: return 'B';
            case 0xCC: return 'Z';
            case 0xCE: return 'Q';
            case 0xCF: return 'O';

            // 0xD0 영역
            case 0xD2: return 'L';
            case 0xD3: return 'v';
            case 0xD4: return 'F';
            case 0xD7: return 'N';
            case 0xD8: return 'U';
            case 0xD9: return 'f';
            case 0xDB: return 'g';
            case 0xDC: return 'j';
            case 0xDD: return 'p';
            case 0xDE: return 'q';

            // 대문자
            case 0xE2: return 'E';
            case 0xE3: return 'W';
            case 0xE4: return 'R';
            case 0xE5: return 'M';
            case 0xE7: return 'Y';
            case 0xEA: return 'S';
            case 0xEB: return 'D';
            case 0xEC: return 'F';
            case 0xEE: return 'H';
            case 0xEF: return 'J';

            case 0xF0: return 'K';
            case 0xF1: return 'L';
            case 0xF5: return 'B';
            case 0xF6: return 'I';
            case 0xF7: return 'N';
            case 0xFC: return 'Z';
            case 0xFD: return 'G';
            case 0xFE: return 'X';
            case 0xFF: return 'T';

            default: return c;
        }
    }

    /**
     * EH 폰트 텍스트를 디코딩 (폰트 패밀리에 따라 적절한 디코딩 적용).
     */
    public static String decodeText(String text, String fontFamily) {
        if (text == null || text.isEmpty()) return text;
        if (isSuperscriptFont(fontFamily) || isSubscriptFont(fontFamily)
                || isChemicalFont(fontFamily)) {
            return decodeSubSupText(text);
        }
        if (isFractionFont(fontFamily)) {
            return decodeSubSupText(text); // 분수도 같은 매핑 사용
        }
        if (isBaseEquationFont(fontFamily)) {
            // EH수식은 기본 범위도 커스텀이므로 별도 처리
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                char decoded = decodeBaseEquationGlyph(c);
                if (c >= 0x80 && decoded == c) continue; // 매핑 없는 확장 문자 스킵
                sb.append(decoded);
            }
            return sb.toString();
        }
        // 기타 EH 폰트 → 상부자/하부자 매핑 적용 (가장 범용)
        return decodeSubSupText(text);
    }
}
