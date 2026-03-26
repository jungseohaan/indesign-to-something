package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * EH 분수 GREP 패턴 (;...;) 변환 유틸리티.
 * ;분자분모혼합; 패턴을 감지하여 hwpScript 분수로 변환.
 * EHTokenizer와 ResolvedToASTBuilder에서 공통 사용.
 */
public class EHGrepFractionConverter {

    /** 분수 변환 결과 세그먼트 */
    public static class Segment {
        public enum Type { TEXT, EQUATION }
        private final Type type;
        private final String content;

        public Segment(Type type, String content) {
            this.type = type;
            this.content = content;
        }

        public Type type() { return type; }
        public String content() { return content; }
    }

    /**
     * 텍스트에서 ;...; 분수 GREP 패턴을 찾아 분할.
     * { → (, } → ) 치환 (hwpScript 그룹 구분자 충돌 방지).
     * @return TEXT/EQUATION 세그먼트 리스트
     */
    public static List<Segment> splitAndConvert(String text) {
        List<Segment> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        // { → (, } → )
        text = text.replace('{', '(').replace('}', ')');

        int i = 0;
        while (i < text.length()) {
            int semiStart = text.indexOf(';', i);
            if (semiStart < 0) {
                String rest = text.substring(i);
                if (!rest.isEmpty()) result.add(new Segment(Segment.Type.TEXT, rest));
                break;
            }

            // ; 이전 텍스트
            if (semiStart > i) {
                result.add(new Segment(Segment.Type.TEXT, text.substring(i, semiStart)));
            }

            // 두 번째 ; 찾기
            int semiEnd = text.indexOf(';', semiStart + 1);
            if (semiEnd < 0) {
                result.add(new Segment(Segment.Type.TEXT, text.substring(semiStart)));
                break;
            }

            // ;...; 내용 디코딩
            String inner = text.substring(semiStart + 1, semiEnd);
            String[] fracParts = EHFontGlyphMap.decodeFractionInner(inner);
            if (fracParts != null && (fracParts[0].length() > 0 || fracParts[1].length() > 0)) {
                String hwpScript = buildFractionScript(fracParts[0], fracParts[1]);
                result.add(new Segment(Segment.Type.EQUATION, hwpScript));
            } else {
                // 디코딩 실패 → 원본 패스스루
                result.add(new Segment(Segment.Type.TEXT, ";" + inner + ";"));
            }

            i = semiEnd + 1;
        }

        return result;
    }

    /**
     * 분자/분모 문자열로 hwpScript 분수 생성.
     */
    public static String buildFractionScript(String numerator, String denominator) {
        String num = EHFontEquationConverter.convertToHwpScript(numerator);
        String den = EHFontEquationConverter.convertToHwpScript(denominator);
        if (num.length() > 0 && den.length() > 0) {
            return "{" + num + "} over {" + den + "}";
        } else if (num.length() > 0) {
            return "{" + num + "} over { }";
        } else {
            return "{ } over {" + den + "}";
        }
    }
}
