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

    /**
     * EH 폰트 해킹 글리프가 EH 수식 그룹에 못 들어간 채 일반 텍스트로 흘러온
     * 경우, 폰트 종류에 맞춰 raw 라틴 글리프를 실제 수학 기호로 디코딩한다.
     *
     * <p><b>공통 진입점</b>: 신 파이프라인의 여러 텍스트 경로(RunBuilder 직접 변환,
     * 테이블 셀의 convertStoryParagraphs 등)가 모두 이 메서드 하나를 호출한다.
     * 경로마다 디코딩을 흩뿌리면 누락이 생겨(실측: 5단원 테이블 셀 180ù) 여기로 모은다.
     *
     * <p>{@code fontFamily} 는 해당 텍스트 run 의 EH 폰트 이름. EH 폰트가 아니면
     * 원문을 그대로 돌려준다.
     *
     * @param text        run 텍스트
     * @param fontFamily  run 의 fontFamily (EH… 계열이어야 디코딩)
     * @return 디코딩된 텍스트 (EH 폰트가 아니면 원문)
     */
    public static String decodeStrayGlyphText(String text, String fontFamily) {
        if (text == null) return text;
        // fontFamily가 null인 경우: EH상부자 등 수식 폰트가 그룹핑 단계에서 리셋(null)됐지만
        // raw EH 글리프(Û/Ö/µ…)는 그대로 남는다(실측: 1단원 (a+b)Û`, 3단원 Ó=). 폰트 정보가
        // 없으므로 본문 폰트 경로로 좁게 디코딩한다.
        if (fontFamily == null) {
            return decodeStrayGlyphInBodyFont(text);
        }
        if (isChemicalFont(fontFamily)) {
            // EH약물: µ→⌒(호), ª→≡(합동)
            text = decodeChemicalGlyphText(text);
        } else if (isSuperscriptFont(fontFamily)) {
            // EH상부자: 선분 표기 marker Ó(0xD3)를 overline 마커로 감싸고
            // (이후 RunPostProcessor.splitOverlineRuns 가 ASTEquation 변환),
            // 도(°) 등 상부자 기호도 디코딩.
            text = applyOverlineMarkers(text);
            text = decodeSuperscriptSymbols(text);
        }
        // 위 분기에 안 걸린 EH 폰트(분수소문자 등)에도 호(µ)·합동(ª)처럼
        // 폰트 무관하게 의미가 고정된 공통 기호는 디코딩한다(실측: 2단원 ';;ª').
        if (isEHFontFamily(fontFamily)) {
            text = decodeCommonSymbols(text);
        } else {
            // 본문 폰트에 섞여 들어온 EH 해킹 글리프(실측: 5단원 테이블 셀의 180ù,
            // 2단원 Õ). EH 폰트가 아니므로 위 분기를 못 타지만, 문맥상 깨진 EH
            // 글리프임이 확실한 경우만 좁게 치환한다(본문의 진짜 기호 오변환 방지).
            text = decodeStrayGlyphInBodyFont(text);
        }
        return text;
    }

    /**
     * 본문 폰트 run 에 섞여 들어온 EH 해킹 글리프를 <b>문맥이 확실할 때만</b> 치환한다.
     *
     * <p>EH 폰트가 아닌 run 은 원칙적으로 건드리지 않는다. 다만 조판자가 EH 폰트 대신
     * 본문 폰트로 기호를 입력했거나 폰트 상속이 꼬여, 한글 수학 본문에 정상적으로는
     * 나올 수 없는 글리프가 깨진 채 남는 경우가 있다. 아래 세 글리프만, 각각 오변환
     * 위험이 없는 좁은 문맥에서 치환한다:
     * <ul>
     *   <li>{@code ù}(0xF9): 바로 앞이 숫자면 도(°). 예 {@code 180ù}→{@code 180°}</li>
     *   <li>{@code ª}(0xAA): 서수 표시자, 한글 수학 본문 정상 등장 없음 → 합동(≡)</li>
     *   <li>{@code Õ}(0xD5): 상부자 수평선 장식 → 제거</li>
     *   <li>{@code µ}(0xB5): 뒤가 소문자 단위(µm·µs·µg…)가 아니면 호(⌒).
     *       예 {@code 2 µ BC}(호 BC)→{@code 2 ⌒ BC}</li>
     * </ul>
     */
    private static String decodeStrayGlyphInBodyFont(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.indexOf('ù') < 0 && text.indexOf('ª') < 0
                && text.indexOf('Õ') < 0 && text.indexOf('µ') < 0
                && text.indexOf('Û') < 0 && text.indexOf('Ü') < 0
                && text.indexOf('Ö') < 0 && text.indexOf('Ó') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'ù') {
                // 바로 앞이 숫자일 때만 도(°) — "180ù" 같은 각도 표기
                char prev = sb.length() > 0 ? sb.charAt(sb.length() - 1) : '\0';
                if (prev >= '0' && prev <= '9') { sb.append('°'); continue; }
                sb.append(c);
            } else if (c == 'ª') {
                sb.append('≡');
            } else if (c == 'Õ') {
                // 수평선 장식 글리프 → 제거
            } else if (c == 'Û' || c == 'Ü') {
                // 위첨자 제곱/세제곱. EH상부자 폰트가 그룹핑 단계에서 리셋돼 raw로 흘러온
                // 경우(실측: 1·2단원 (a+b)Û` = (a+b)²). 앞에 base 문자(영숫자/닫는괄호)가
                // 있을 때만 치환 — 홀로 선 Û는 오변환 위험이 있어 보존.
                char prev = sb.length() > 0 ? sb.charAt(sb.length() - 1) : '\0';
                boolean afterBase = Character.isLetterOrDigit(prev) || prev == ')' || prev == ']';
                if (afterBase) sb.append(c == 'Û' ? '²' : '³');
                else sb.append(c);
            } else if (c == 'Ö') {
                sb.append('÷');
            } else if (c == 'Ó') {
                // overline 마커 — stray 텍스트에선 선분 위 막대 표현 불가, 제거
            } else if (c == 'µ') {
                // 마이크로 단위(µm·µs·µg·µA…)면 그대로, 아니면 호(⌒).
                char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                boolean microUnit = next == 'm' || next == 's' || next == 'g'
                        || next == 'A' || next == 'l' || next == 'L' || next == 'F';
                sb.append(microUnit ? c : '⌒');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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

    /**
     * 어느 EH 폰트에서든 의미가 고정된 공통 기호를 디코딩한다.
     *
     * <p>{@code µ}(0xB5)=호(⌒), {@code ª}(0xAA)=합동(≡) 두 글리프는 폰트 해킹 계열이
     * 무엇이든(약물·분수·상부자 …) 항상 같은 수학 기호를 가리킨다. 폰트별 디코더
     * (약물/상부자)에 못 걸린 케이스가 있어(실측: 수학 2단원 {@code EH분수소문자}의
     * {@code ;;ª}), EH 폰트 전체에 공통으로 이 치환을 한 번 더 적용한다.
     *
     * <p>영문·숫자·한글·기타 글리프는 건드리지 않는다 — 매핑된 두 문자만 치환.
     */
    public static String decodeCommonSymbols(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.indexOf('µ') < 0 && text.indexOf('ª') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'µ') sb.append('⌒');
            else if (c == 'ª') sb.append('≡');
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * EH약물 폰트의 글리프를 실제 수학 기호로 디코딩한다.
     *
     * <p>EH약물은 "약물"(掠物, 문장부호·기호)을 담는 폰트 해킹이다. 특정 라틴 문자
     * 자리에 수학 기호를 그려두어, EH 처리 경로를 못 타면 raw 라틴 문자로 샌다.
     * 실측(수학 5단원):
     * <pre>
     *   µ (0xB5, 39회)  →  ⌒ (호)      문맥: µAB = 호 AB
     *   ª (0xAA,  3회)  →  ≡ (합동)     문맥: △OAM ª △OBM = △OAM ≡ △OBM
     * </pre>
     * 매핑 없는 확장 문자(공백/장식/마커)는 제거한다.
     */
    public static String decodeChemicalGlyphText(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 'µ': sb.append('⌒'); break;   // ⌒ 호(arc)
                case 'ª': sb.append('≡'); break;   // ≡ 합동(equiv)
                case 'p': sb.append('π'); break;   // π 원주율(실측: 1단원 -π, π+1, 2<π)
                case 'y': sb.append('…'); break;   // … 말줄임(실측: 1단원 p21 √2=1.414…)
                case '`': break;                        // 위첨자 마커 → 제거
                default:
                    // 매핑 없는 확장 문자(0x80+ 장식/미지 글리프)는 제거,
                    // 그 외(영문·숫자·한글·공백)는 그대로.
                    if (c < 0x20 || (c >= 0x80 && c <= 0xFF)) break;
                    sb.append(c);
            }
        }
        return sb.toString();
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

            // EH분수소문자 폰트에서 [ \ ] ^ (0x5B-0x5E) 자리는 분수선을 포함한
            // 특수 분모 글리프다(TTF 렌더 확인): [→x, \→g, ]→y, ^→6.
            // 이전엔 "괄호 장식"으로 스킵돼 분모가 통째로 사라졌다(실측: 3단원 y=5/x → 5/□).
            char fracDenomGlyph = decodeFractionDenominatorGlyph(c);
            if (fracDenomGlyph != 0) {
                denom.append(fracDenomGlyph);
                continue;
            }

            // 그 외 구조 문자 ({ } |): 스킵 (분수 시각 장식)
        }

        if (numer.length() == 0 && denom.length() == 0) return null;
        return new String[]{numer.toString(), denom.toString()};
    }

    /**
     * EH분수소문자 폰트의 특수 분모 글리프 디코딩.
     * <p>0x5B-0x5E([ \ ] ^) 자리에 분수선을 포함한 분모 문자가 그려져 있다
     * (TTF 렌더 확인): [→x, \→g, ]→y, ^→6. shift-digit(분자)·대문자(분자 소문자)
     * 뒤 default 분기에서만 도달하므로 shift-digit 우선순위와 충돌하지 않는다.
     *
     * @return 디코딩된 분모 문자, 또는 매핑 없으면 0
     */
    private static char decodeFractionDenominatorGlyph(char c) {
        switch (c) {
            case '[': return 'x';
            case '\\': return 'g';
            case ']': return 'y';
            default: return 0;
        }
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
            case 0xC1: return '1';    // Á → 1 (숫자 1, EH상부자 이탤릭체에서 l과 1이 동일 글리프)
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
            case 0xD3: return '\u0305'; // Ó → overline marker (선분 AB̅ → overline{AB}, EHTokenizer 가 앞 토큰 래핑)
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

    /** 텍스트에 EH overline marker(0xD3, "Ó")가 있는지. */
    public static boolean containsOverlineMarker(String text) {
        return text != null && text.indexOf('Ó') >= 0;
    }

    /**
     * EH overline marker(Ó=0xD3)를 담은 텍스트를 HWP 수식 문법으로 변환한다.
     *
     * <p>EH상부자 폰트에서 Ó 는 앞 문자에 윗줄을 씌우는 선분 기호다(AB̅).
     * 정상 경로(EH 그룹 → EHTokenizer)는 이걸 overline{...} 으로 감싸지만,
     * 이 런들은 IDML 파싱 시점에 폰트가 상속으로만 지정되어(fontFamily=null)
     * EH 그룹에 들어가지 못한 채 일반 텍스트로 흘러 "Ó" 가 raw 로 새어나온다.
     * 실측(수학 5단원)에서 이런 런이 33개.
     *
     * <p>런 안의 "문자열 + Ó" 를 문자열 마커로 감싼다. 이후
     * RunPostProcessor.splitOverlineRuns 가 이 마커를 ASTEquation("overline{문자열}")
     * 으로 변환한다(정상 overline 이 처리되는 기존 경로와 동일). 형태(실측):
     * <pre>
     *   "ABÓ"        → "AB"
     *   "ABÓ=CDÓ"    → "AB=CD"
     *   "=OBÓ"       → "=OB"
     * </pre>
     *
     * <p>Ó 앞에 감쌀 문자가 없으면(런이 "Ó" 로 시작 = 앞 런에서 이어지는 경우)
     * 그 marker 는 텍스트 레벨에서 대상을 알 수 없으므로 제거만 한다.
     *
     * @return overline 마커가 삽입된 텍스트 (Ó 없으면 원본 그대로)
     */
    public static String applyOverlineMarkers(String text) {
        if (text == null || text.indexOf('Ó') < 0) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        int segStart = 0;   // 현재 overline 대상 후보의 시작
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != 'Ó') continue;

            // segStart..i 사이에서 Ó 바로 앞에 붙은 "감쌀 문자열"을 찾는다.
            // 원소기호/변수(영문)만 대상으로 하고, 그 앞의 구분자(=,공백,쉼표,숫자)는
            // overline 밖에 그대로 둔다.
            int wrapStart = i;
            while (wrapStart > segStart) {
                char pc = text.charAt(wrapStart - 1);
                if (isOverlineWrappable(pc)) {
                    wrapStart--;
                } else {
                    break;
                }
            }
            // wrapStart..i = 감쌀 문자열, 그 앞(segStart..wrapStart)은 그대로 출력
            out.append(text, segStart, wrapStart);
            if (wrapStart < i) {
                out.append('\uE000').append(text, wrapStart, i).append('\uE001');
            }
            // 감쌀 문자가 없으면(Ó 단독) marker 를 그냥 버린다.
            segStart = i + 1;
        }
        out.append(text, segStart, text.length());
        return out.toString();
    }

    /**
     * overline 으로 감쌀 수 있는 문자인가.
     *
     * <p>선분/점 이름은 영문자다(AB̅, PA̅). 숫자는 계수이므로(예: 2AM̅ = 2×AM̅)
     * overline 밖에 둔다 — 숫자를 포함하면 "2AM̅" 이 overline{2AM} 으로 잘못 감싸진다.
     */
    private static boolean isOverlineWrappable(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * EH상부자 폰트의 비숫자 기호를 실제 유니코드 기호로 디코딩한다.
     *
     * <p>overline marker(Ó=0xD3)와 위첨자 숫자(0xDA~0xE2)는 각각 applyOverlineMarkers /
     * 수식 그룹 경로가 처리하므로 여기서 건드리지 않는다. 그 외 기호 글리프만 치환한다.
     * 실측(수학 5단원): ù(0xF9) → °(도), 문맥 "90ù" = 90°.
     */
    public static String decodeSuperscriptSymbols(String text) {
        if (text == null || text.isEmpty()) return text;
        // Û/Ü(위첨자 제곱/세제곱), Ö(÷), Ó(overline), ù(°), Õ(장식) 중 하나라도 있어야 처리.
        // 이전엔 ù/Õ만 봤으나, EH 그룹핑 변경(chemical PR)으로 EH상부자 수식 런이 수식
        // 그룹에 못 들어가고 이 stray 경로로 흘러 raw Û/Ö/Ó가 그대로 노출됐다
        // (실측: 2단원 (a+b)Û` = (a+b)², 1단원 0.36Ö, 3단원 Ó=).
        if (text.indexOf('ù') < 0 && text.indexOf('Õ') < 0
                && text.indexOf('Û') < 0 && text.indexOf('Ü') < 0
                && text.indexOf('Ö') < 0 && text.indexOf('Ó') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 'ù': sb.append('°'); break;   // ù → ° (도)
                case 'Õ': break;                        // 0xD5 = 수평선 장식 → 제거
                case 'Û': sb.append('²'); break;  // Û → ² (위첨자 제곱)
                case 'Ü': sb.append('³'); break;  // Ü → ³ (위첨자 세제곱)
                case 'Ö': sb.append('÷'); break;  // Ö → ÷ (나눗셈)
                case 'Ó': break;                        // 0xD3 = overline 마커 — stray 텍스트에선 표현 불가, 제거
                default: sb.append(c);
            }
        }
        return sb.toString();
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
            case 0xD3: return '\u0305'; // Ó → overline marker (선분 기호 AB̅ → overline{AB})
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
     * EH 상부자/하부자 폰트가 GREP으로 적용된 ASCII 텍스트의 글리프 매핑.
     * <p>
     * 일반 본문 폰트(예: 윤고딕)의 ASCII 문자가 ParagraphStyle GREP 규칙으로
     * EH상부자/하부자 폰트로 교체되는 경우, 실제 EH 폰트의 글리프가 ASCII 원형과
     * 다른 케이스(예: '_' → '×')만 시각적으로 일치시킨다.
     * <p>
     * 주의: decodeSubSupText는 0x80+ 범위 미매핑 문자를 스킵하므로 한국어 등이
     * 사라진다. 이 메서드는 GREP 적용으로 분리된 단일/짧은 ASCII 서브런 전용.
     */
    public static String applyEHGrepAsciiGlyphMap(String text) {
        if (text == null || text.isEmpty()) return text;
        boolean changed = false;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_') {
                sb.append('\u00D7'); // EH상부자: '_' → '×' (곱셈 기호)
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : text;
    }

    /**
     * EH 폰트 텍스트를 디코딩 (폰트 패밀리에 따라 적절한 디코딩 적용).
     */
    public static String decodeText(String text, String fontFamily) {
        if (text == null || text.isEmpty()) return text;
        if (isChemicalFont(fontFamily)) {
            // EH약물 전용 글리프: p(0x70) → π. 약물 폰트에서만 π 이며(실측: 1단원
            // -π, π+1, 2<π 등 모두 원주율), 다른 EH 폰트의 p 는 변수이므로 여기서만
            // 치환한다. decodeSubSupText 는 매핑 없는 0x80+ 문자를 버리므로, π(U+03C0)
            // 는 디코딩 이후에 넣는다. 나머지 글리프는 공용 상부자/하부자 매핑을 따른다.
            return decodeSubSupText(text).replace('p', 'π');
        }
        if (isSuperscriptFont(fontFamily) || isSubscriptFont(fontFamily)) {
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
