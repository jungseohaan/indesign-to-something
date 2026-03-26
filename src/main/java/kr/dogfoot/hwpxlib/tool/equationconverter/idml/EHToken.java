package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

/**
 * EH 폰트 수식 토큰.
 * EHTokenizer가 IDMLCharacterRun에서 글리프 디코딩 + 역할 분류하여 생성.
 */
public class EHToken {

    public enum Type {
        /** 일반 수식 텍스트 (EH수식/EH약물/비EH 브릿지) */
        BASE_TEXT,
        /** 위첨자 글리프 (EH상부자 확장 범위 0x80+, 디코딩됨) */
        SUPERSCRIPT_GLYPH,
        /** 아래첨자 글리프 (EH하부자 확장 범위 0x80+, 디코딩됨) */
        SUBSCRIPT_GLYPH,
        /** 상부자 기본 범위 텍스트 (EH상부자 0x20-0x7F, 패스스루) */
        SUP_BASE_TEXT,
        /** 하부자 기본 범위 텍스트 (EH하부자 0x20-0x7F, 패스스루) */
        SUB_BASE_TEXT,
        /** 제곱근 마커 (EH분수대문자, 분모 없음) */
        SQRT_MARKER,
        /** 분수 분자 (EH분수대문자, 분모 있음) */
        FRACTION_NUMERATOR,
        /** 분수 분모 (EH분수소문자) */
        FRACTION_DENOMINATOR,
        /** 백틱 위첨자 (EH수식의 문자+` 패턴) */
        BACKTICK_SUPER,
        /** GREP 인라인 분수 (;...; 패턴, 디코딩된 분자/분모) */
        GREP_FRACTION,
        /** 시각 장식 (EH루트/EH선모음, 스킵) */
        SKIP
    }

    private final Type type;
    private final String text;
    // GREP_FRACTION 전용: 분자/분모
    private final String fracNumerator;
    private final String fracDenominator;

    public EHToken(Type type, String text) {
        this.type = type;
        this.text = text;
        this.fracNumerator = null;
        this.fracDenominator = null;
    }

    public EHToken(Type type, String text, String fracNumerator, String fracDenominator) {
        this.type = type;
        this.text = text;
        this.fracNumerator = fracNumerator;
        this.fracDenominator = fracDenominator;
    }

    public Type type() { return type; }
    public String text() { return text; }
    public String fracNumerator() { return fracNumerator; }
    public String fracDenominator() { return fracDenominator; }

    public String toString() {
        if (type == Type.GREP_FRACTION) {
            return type + "(" + fracNumerator + "/" + fracDenominator + ")";
        }
        return type + "(" + (text != null ? text : "") + ")";
    }
}
