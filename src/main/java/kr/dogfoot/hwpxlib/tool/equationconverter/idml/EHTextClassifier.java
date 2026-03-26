package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

/**
 * EH 수식 텍스트 분류 유틸리티.
 * 한국어/수학/혼합 텍스트 판별을 통합.
 */
public class EHTextClassifier {

    /**
     * 텍스트가 한국어/구두점/공백만 포함하는지 판별.
     * 라틴 알파벳, 숫자, 수학 연산자가 없으면 true.
     * EH 수식 폰트가 한국어 텍스트에 잘못 적용되는 것을 방지할 때 사용.
     */
    public static boolean isKoreanOnly(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 라틴 알파벳/숫자 포함 → false
            if (Character.isLetterOrDigit(c)
                    && !(c >= 0xAC00 && c <= 0xD7A3)
                    && !(c >= 0x3131 && c <= 0x318E)) {
                return false;
            }
            // 수학 연산자/기호 포함 → false
            if ("+-*/=<>()[]{}|!.;^_&\\`".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 텍스트에 한국어 문자가 포함되어 있는지 판별.
     */
    public static boolean containsKorean(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E)) {
                return true;
            }
        }
        return false;
    }
}
