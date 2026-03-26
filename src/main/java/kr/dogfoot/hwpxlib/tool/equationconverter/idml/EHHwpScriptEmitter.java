package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import java.util.List;

/**
 * Phase 3: EHNode 트리 → hwpScript 문자열 생성.
 * 트리를 재귀 순회하여 hwpScript 포맷으로 변환.
 */
public class EHHwpScriptEmitter {

    /**
     * EHNode 트리를 hwpScript 문자열로 변환.
     * @return hwpScript 문자열, 또는 null (빈/무효 수식)
     */
    public static String emit(EHNode root) {
        if (root == null) return null;
        StringBuilder sb = new StringBuilder();
        emitNode(root, sb);
        return postProcess(sb.toString());
    }

    private static void emitNode(EHNode node, StringBuilder sb) {
        if (node instanceof EHNode.Text) {
            sb.append(EHFontEquationConverter.convertToHwpScript(((EHNode.Text) node).text()));

        } else if (node instanceof EHNode.Superscript) {
            sb.append("^{");
            emitChildren(((EHNode.Superscript) node).content(), sb);
            sb.append("}");

        } else if (node instanceof EHNode.Subscript) {
            sb.append("_{");
            emitChildren(((EHNode.Subscript) node).content(), sb);
            sb.append("}");

        } else if (node instanceof EHNode.Fraction) {
            EHNode.Fraction frac = (EHNode.Fraction) node;
            sb.append("{");
            emitChildren(frac.numerator(), sb);
            sb.append("} over {");
            emitChildren(frac.denominator(), sb);
            sb.append("}");

        } else if (node instanceof EHNode.Sqrt) {
            EHNode.Sqrt sqrt = (EHNode.Sqrt) node;
            sb.append("sqrt{");
            if (sqrt.radicand().isEmpty()) {
                sb.append(" "); // 단독 √ 기호
            } else {
                emitChildren(sqrt.radicand(), sb);
            }
            sb.append("}");

        } else if (node instanceof EHNode.Group) {
            emitChildren(((EHNode.Group) node).children(), sb);
        }
    }

    private static void emitChildren(List<EHNode> children, StringBuilder sb) {
        for (EHNode child : children) {
            emitNode(child, sb);
        }
    }

    /**
     * 후처리: 빈 sqrt 제거, 중괄호 보정, 유효성 검증.
     */
    private static String postProcess(String raw) {
        String result = raw.trim();
        if (result.isEmpty()) return null;

        // 빈 sqrt{} 제거 (단독 sqrt{ }는 유지)
        while (result.contains("sqrt{}")) {
            result = result.replace("sqrt{}", "");
        }
        result = result.trim();
        if (result.isEmpty()) return null;

        // 짝 안 맞는 중괄호 보정
        int open = 0;
        for (int i = 0; i < result.length(); i++) {
            if (result.charAt(i) == '{') open++;
            else if (result.charAt(i) == '}') open--;
        }
        while (open > 0) { result = result + "}"; open--; }

        // 순수 한국어만이면 수식 아님
        if (EHFontEquationConverter.isOnlyKorean(result)) return null;

        // 글자나 숫자가 없으면 수식 아님
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < result.length(); i++) {
            char ch = result.charAt(i);
            if (Character.isLetterOrDigit(ch)) { hasLetterOrDigit = true; break; }
            if (ch >= 0x2460 && ch <= 0x2473) { hasLetterOrDigit = true; break; }
        }
        if (!hasLetterOrDigit) return null;

        return result;
    }
}
