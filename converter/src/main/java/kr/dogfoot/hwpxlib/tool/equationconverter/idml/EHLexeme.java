package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

/**
 * EH 수식 렉심(lexeme) — 글리프 의미 단위.
 *
 * <p>새 EH 파이프라인 Phase 1(Lexer)의 출력. 기존 {@link EHToken}의
 * "SQRT_MARKER 하나로 뭉개기"를 폐기하고, 글리프의 폰트 역할별 의미를
 * 보존한다. Lexer는 구조(근호 범위 등)를 결정하지 않고 "이 코드포인트가
 * 폰트상 무엇인가"만 태깅한다. 구조 판정은 Parser(재귀하강 문법)가 한다.
 */
public class EHLexeme {

    public enum Kind {
        /** 근호 갈고리 시작. 0x27('), 0xAE(®), 0xBF(¿), 0xBE(¾) — 분수대문자 폰트 */
        HOOK,
        /** 근호 폭 선택 마커(hook 직후 0x80+). 스킵 대상. rawCodepoint 보존 */
        WIDTH_SELECTOR,
        /** 자리구분자 0x8C (숫자-숫자 사이). radicand 숫자 이어붙이기용 */
        DIGIT_SEP,
        /** 디코딩된 일반 수식 텍스트(숫자/변수/연산자/괄호/한글) */
        ATOM_TEXT,
        /** 위첨자 (EH상부자 decoded 또는 백틱 지수) */
        SUPERSCRIPT,
        /** 아래첨자 (EH하부자 decoded) */
        SUBSCRIPT,
        /** 분수 (GREP ;..; 또는 분수대/소문자 쌍). num/den 필드 보유 */
        FRACTION,
        /** 인라인 박스 atom (빈 답란 placeholder). boxKind 보유 */
        BOX,
        /** 선분 위 막대 마커(0xD3 Ó). 앞 ATOM을 감쌈 */
        OVERLINE,
        /** 순환소수 순환마디 점(EH약물 H). 뒤 숫자에 dot{} 적용 */
        DOT,
        /** 구분자: 탭, 원문자(①⑴), thin space, EM space, 줄바꿈 — 근호/항 경계 */
        SEP,
        /** 시각 장식(EH루트/EH선모음/미매핑 확장문자) */
        SKIP
    }

    private final Kind kind;
    private final String value;          // ATOM_TEXT/SUPERSCRIPT/SUBSCRIPT/SEP 등의 디코딩 텍스트
    private final String num;            // FRACTION 분자
    private final String den;            // FRACTION 분모
    private final char hookGlyph;        // HOOK 글리프 (0x27/0xAE/0xBF/0xBE)
    private final int rawCodepoint;      // WIDTH_SELECTOR/DIGIT_SEP 원시 코드포인트 (Parser 재해석용)
    private final EHNode.Box.Kind boxKind; // BOX 종류 (ANSWER/VINCULUM)

    private EHLexeme(Kind kind, String value, String num, String den,
                     char hookGlyph, int rawCodepoint, EHNode.Box.Kind boxKind) {
        this.kind = kind;
        this.value = value;
        this.num = num;
        this.den = den;
        this.hookGlyph = hookGlyph;
        this.rawCodepoint = rawCodepoint;
        this.boxKind = boxKind;
    }

    public static EHLexeme hook(char glyph) {
        return new EHLexeme(Kind.HOOK, null, null, null, glyph, glyph, null);
    }
    public static EHLexeme widthSelector(int rawCodepoint) {
        return new EHLexeme(Kind.WIDTH_SELECTOR, null, null, null, '\0', rawCodepoint, null);
    }
    public static EHLexeme digitSep(int rawCodepoint) {
        return new EHLexeme(Kind.DIGIT_SEP, null, null, null, '\0', rawCodepoint, null);
    }
    public static EHLexeme atomText(String value) {
        return new EHLexeme(Kind.ATOM_TEXT, value, null, null, '\0', 0, null);
    }
    public static EHLexeme superscript(String value) {
        return new EHLexeme(Kind.SUPERSCRIPT, value, null, null, '\0', 0, null);
    }
    public static EHLexeme subscript(String value) {
        return new EHLexeme(Kind.SUBSCRIPT, value, null, null, '\0', 0, null);
    }
    public static EHLexeme fraction(String num, String den) {
        return new EHLexeme(Kind.FRACTION, null, num, den, '\0', 0, null);
    }
    public static EHLexeme box(EHNode.Box.Kind boxKind) {
        return new EHLexeme(Kind.BOX, null, null, null, '\0', 0, boxKind);
    }
    public static EHLexeme overline() {
        return new EHLexeme(Kind.OVERLINE, null, null, null, '\0', 0, null);
    }
    public static EHLexeme dot() {
        return new EHLexeme(Kind.DOT, null, null, null, '\0', 0, null);
    }
    public static EHLexeme sep(String value) {
        return new EHLexeme(Kind.SEP, value, null, null, '\0', 0, null);
    }
    public static EHLexeme skip() {
        return new EHLexeme(Kind.SKIP, null, null, null, '\0', 0, null);
    }

    public Kind kind() { return kind; }
    public String value() { return value; }
    public String num() { return num; }
    public String den() { return den; }
    public char hookGlyph() { return hookGlyph; }
    public int rawCodepoint() { return rawCodepoint; }
    public EHNode.Box.Kind boxKind() { return boxKind; }

    public String toString() {
        switch (kind) {
            case FRACTION: return "FRACTION(" + num + "/" + den + ")";
            case BOX: return "BOX(" + boxKind + ")";
            case HOOK: return "HOOK(" + hookGlyph + ")";
            case WIDTH_SELECTOR: return "WIDTH_SELECTOR(0x" + Integer.toHexString(rawCodepoint) + ")";
            case DIGIT_SEP: return "DIGIT_SEP";
            default: return kind + "(" + (value != null ? value : "") + ")";
        }
    }
}
