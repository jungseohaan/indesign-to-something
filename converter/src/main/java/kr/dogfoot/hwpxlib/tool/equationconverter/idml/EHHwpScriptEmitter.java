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
            String text = ((EHNode.Text) node).text();
            // 탭은 선택지 구분자 → 보존 (convertToHwpScript가 공백으로 변환하므로)
            if ("\t".equals(text)) {
                sb.append('\t');
            } else {
                sb.append(EHFontEquationConverter.convertToHwpScript(text));
            }

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

        } else if (node instanceof EHNode.Box) {
            // 빈 답란 박스 → HWP 수식 box{~} (ANSWER). VINCULUM 은 Parser 가 이미 제거.
            sb.append("box{~}");

        } else if (node instanceof EHNode.Overline) {
            sb.append("overline{");
            emitChildren(((EHNode.Overline) node).content(), sb);
            sb.append("}");

        } else if (node instanceof EHNode.RecurDot) {
            sb.append("dot{").append(((EHNode.RecurDot) node).digit()).append("}");

        } else if (node instanceof EHNode.Paren) {
            sb.append("(");
            emitChildren(((EHNode.Paren) node).content(), sb);
            sb.append(")");

        } else if (node instanceof EHNode.Group) {
            emitChildren(((EHNode.Group) node).children(), sb);
        }
    }

    private static void emitChildren(List<EHNode> children, StringBuilder sb) {
        for (int i = 0; i < children.size(); i++) {
            EHNode child = children.get(i);
            boolean nextIsSuper = i + 1 < children.size()
                    && children.get(i + 1) instanceof EHNode.Superscript;
            // 빈 근호(√ 뒤 radicand 없음) + Superscript: 그 위첨자는 지수가 아니라
            // 작은 크기로 조판된 radicand 다(√² 의 2). {sqrt{ }}^{n} 를 만들지 말고
            // 위첨자 내용을 radicand 로 접어 sqrt{n} 으로 emit.
            if (child instanceof EHNode.Sqrt
                    && ((EHNode.Sqrt) child).radicand().isEmpty()
                    && nextIsSuper) {
                EHNode.Superscript sup = (EHNode.Superscript) children.get(i + 1);
                sb.append("sqrt{");
                emitChildren(sup.content(), sb);
                sb.append("}");
                i++; // Superscript 소비
                continue;
            }
            // Sqrt 뒤에 Superscript가 오면 → {sqrt{...}}^{n} 형태로 감싸야
            // HWP 수식 에디터에서 ^가 sqrt 내부로 흡수되지 않도록 함
            boolean wrapSqrt = (child instanceof EHNode.Sqrt) && nextIsSuper;
            if (wrapSqrt) sb.append("{");
            emitNode(child, sb);
            if (wrapSqrt) sb.append("}");
        }
    }

    /**
     * 후처리: 빈 sqrt 제거, 중괄호 보정, 유효성 검증.
     */
    /** 앞뒤 공백(스페이스·개행)만 제거하고 탭(\t)은 보존한다. 탭은 선택지 구분자다. */
    private static String trimSpacesKeepTabs(String s) {
        if (s == null || s.isEmpty()) return s;
        int start = 0, end = s.length();
        while (start < end) {
            char c = s.charAt(start);
            if (c == '\t' || c > ' ') break;
            start++;
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if (c == '\t' || c > ' ') break;
            end--;
        }
        return s.substring(start, end);
    }

    private static String postProcess(String raw) {
        // 탭은 선택지 구분자(⑶…\t⑷…)이므로 보존한다. trim()은 후행 탭까지
        // 제거해 splitByCircledNumbers 가 항을 못 나눈다(실측: 3단원 y=5/x 뒤 탭 유실).
        String result = trimSpacesKeepTabs(raw);
        if (result.isEmpty()) return null;

        // 닫는 괄호 앞 공백 제거: InDesign 원문은 근호 가로줄이 ')' 와 겹치지 않게
        // 얇은 공백(U+2009 등)을 넣지만, HWP 수식은 자체 여백을 그리므로 그대로 두면
        // 쓸데없는 틈이 된다(실측: p17 (√5 )²). left/right 확대 전에 지워야
        // " right)" 앞 이중 공백도 안 생긴다.
        result = result.replaceAll("[ \\u2000-\\u200B\\u202F]+\\)", ")");
        // 닫는 중괄호 앞 유니코드 공백(EM/thin)도 제거: 근호 종료 센티넬(U+241C→EM)이
        // radicand 끝에 남으면 sqrt{3 } 처럼 가로줄이 늘어진다. ASCII 공백은 보존
        // (빈 근호 sqrt{ } 의 자리 공백).
        result = result.replaceAll("[\\u2000-\\u200B\\u202F]+}", "}");

        // HWP 수식 키워드 앞뒤 공백 보장 (인접 토큰에 붙는 것 방지)
        for (String kw : new String[]{"TIMES", " div "}) {
            if (!result.contains(kw)) continue;
            // 이미 공백이 있으면 skip
        }
        // TIMES: 앞뒤 공백 보장 (인접 토큰과 붙지 않게)
        result = result.replaceAll("(?<=[^ ])TIMES", " TIMES");
        result = result.replaceAll("TIMES(?=[^ ])", "TIMES ");
        // div: 단어 경계 기준 (숫자/문자에 붙은 경우만)
        result = result.replaceAll("(?<=[\\w}])div(?=[\\w{(])", " div ");
        result = result.replaceAll("(?<=[\\w}])div$", " div");
        result = result.replaceAll("^div(?=[\\w{(])", "div ");
        // lbrace/rbrace: 앞뒤 공백 보장
        result = result.replaceAll("lbrace(?=[^ ])", "lbrace ");
        result = result.replaceAll("(?<=[^ ])rbrace", " rbrace");

        // 괄호 안에 over(분수)나 sqrt(루트)가 포함되면 left/right로 확대
        // ( ... over ... ) → left( ... over ... right)
        // lbrace ... over ... rbrace → left lbrace ... over ... right rbrace
        result = enlargeBracketsContainingFraction(result);

        // 빈 sqrt{} 제거 (단독 sqrt{ }는 유지)
        while (result.contains("sqrt{}")) {
            result = result.replace("sqrt{}", "");
        }
        // 끝에 매달린 곱셈·나눗셈 키워드(TIMES/div)는 피연산자가 없으므로 제거한다.
        // 원문이 깨진 근호 나열(sqrt{p2} TIMES 처럼 곱셈 뒤 항 없음)에서 발생.
        result = result.replaceAll("(?:\\s*(?:TIMES|div))+\\s*$", "");
        result = trimSpacesKeepTabs(result);
        if (result.isEmpty()) return null;

        // 짝 안 맞는 중괄호 보정
        int open = 0;
        for (int i = 0; i < result.length(); i++) {
            if (result.charAt(i) == '{') open++;
            else if (result.charAt(i) == '}') open--;
        }
        while (open > 0) { result = result + "}"; open--; }

        // 순수 한국어만이면 수식 아님
        if (EHTextClassifier.isKoreanOnly(result)) return null;

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

    /**
     * 괄호 안에 over(분수)나 sqrt(루트)가 포함되면 left/right로 확대.
     * HWP 수식에서 left( right), left[ right], left lbrace right rbrace는
     * 내용 높이에 맞게 괄호 크기를 자동 조절한다.
     */
    private static String enlargeBracketsContainingFraction(String s) {
        // 소괄호: ( ... ) → left( ... right) (over 또는 sqrt 포함 시)
        s = enlargePair(s, "(", ")", "left(", " right)");
        // 중괄호: lbrace ... rbrace → left lbrace ... right rbrace
        s = enlargePair(s, "lbrace ", " rbrace", "left lbrace ", " right rbrace");
        return s;
    }

    private static String enlargePair(String s, String open, String close, String leftOpen, String rightClose) {
        int searchFrom = 0;
        StringBuilder sb = new StringBuilder();
        while (searchFrom < s.length()) {
            int openPos = s.indexOf(open, searchFrom);
            if (openPos < 0) {
                sb.append(s.substring(searchFrom));
                break;
            }
            // 이미 left( 인지 확인 (중복 적용 방지)
            if (openPos >= 4 && s.substring(openPos - 4, openPos).endsWith("left")) {
                sb.append(s.substring(searchFrom, openPos + open.length()));
                searchFrom = openPos + open.length();
                continue;
            }
            // 매칭 닫기 괄호 찾기 (중첩 고려)
            int depth = 1;
            int closePos = -1;
            int i = openPos + open.length();
            while (i < s.length() && depth > 0) {
                if (s.startsWith(open, i)) {
                    depth++;
                    i += open.length();
                } else if (s.startsWith(close, i)) {
                    depth--;
                    if (depth == 0) { closePos = i; break; }
                    i += close.length();
                } else {
                    i++;
                }
            }
            if (closePos < 0) {
                sb.append(s.substring(searchFrom));
                break;
            }
            String inner = s.substring(openPos + open.length(), closePos);
            // over 또는 sqrt가 포함되면 left/right로 확대
            if (inner.contains(" over ") || inner.contains("sqrt{")) {
                sb.append(s.substring(searchFrom, openPos));
                sb.append(leftOpen).append(inner).append(rightClose);
            } else {
                sb.append(s.substring(searchFrom, closePos + close.length()));
            }
            searchFrom = closePos + close.length();
        }
        return sb.toString();
    }
}
