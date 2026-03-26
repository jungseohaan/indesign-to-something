package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2: EHToken 리스트 → EHNode 트리 빌드.
 * 토큰의 구조적 패턴(sqrt, fraction, superscript 등)을 인식하여 트리로 조립.
 */
public class EHIRBuilder {

    /**
     * 토큰 리스트를 EHNode 트리로 빌드.
     * @return 루트 EHNode.Group, 또는 null (빈 입력)
     */
    public static EHNode.Group build(List<EHToken> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;

        EHNode.Group root = new EHNode.Group();
        int i = 0;
        while (i < tokens.size()) {
            i = processToken(tokens, i, root.children());
        }
        return root;
    }

    /**
     * 현재 위치의 토큰을 처리하고 다음 위치를 반환.
     */
    private static int processToken(List<EHToken> tokens, int pos, List<EHNode> out) {
        EHToken token = tokens.get(pos);

        switch (token.type()) {
            case SKIP:
                return pos + 1;

            case SQRT_MARKER:
                return processSqrt(tokens, pos, out);

            case FRACTION_NUMERATOR:
                return processFraction(tokens, pos, out);

            case FRACTION_DENOMINATOR:
                // 분자 없이 분모만 → 텍스트로 처리
                out.add(new EHNode.Text(token.text()));
                return pos + 1;

            case SUPERSCRIPT_GLYPH:
                return processSuperscriptGlyphs(tokens, pos, out);

            case SUBSCRIPT_GLYPH:
                return processSubscriptGlyphs(tokens, pos, out);

            case BACKTICK_SUPER: {
                EHNode.Superscript sup = new EHNode.Superscript();
                sup.content().add(new EHNode.Text(token.text()));
                out.add(sup);
                return pos + 1;
            }

            case GREP_FRACTION: {
                EHNode.Fraction frac = new EHNode.Fraction();
                if (token.fracNumerator() != null && !token.fracNumerator().isEmpty()) {
                    frac.numerator().add(new EHNode.Text(token.fracNumerator()));
                }
                if (token.fracDenominator() != null && !token.fracDenominator().isEmpty()) {
                    frac.denominator().add(new EHNode.Text(token.fracDenominator()));
                }
                out.add(frac);
                return pos + 1;
            }

            case SUP_BASE_TEXT:
            case SUB_BASE_TEXT:
            case BASE_TEXT:
            default:
                out.add(new EHNode.Text(token.text()));
                return pos + 1;
        }
    }

    /**
     * SQRT_MARKER부터 시작하여 루트 내용(radicand) 수집.
     * radicand: 다음 토큰들 중 한국어/thin space/줄바꿈 이전까지.
     * 연속 SQRT_MARKER는 같은 √에 병합.
     */
    private static int processSqrt(List<EHToken> tokens, int pos, List<EHNode> out) {
        EHNode.Sqrt sqrt = new EHNode.Sqrt();

        // 연속 SQRT_MARKER 스킵 (같은 √ 기호의 장식 부분)
        int i = pos;
        while (i < tokens.size() && tokens.get(i).type() == EHToken.Type.SQRT_MARKER) {
            i++;
        }

        // radicand 수집: 다음 토큰들에서 한국어/줄바꿈 이전까지
        while (i < tokens.size()) {
            EHToken t = tokens.get(i);

            // SQRT_MARKER가 다시 오면 → 새로운 √ (현재 √ 종료)
            if (t.type() == EHToken.Type.SQRT_MARKER) break;

            // SKIP 토큰은 건너뜀
            if (t.type() == EHToken.Type.SKIP) { i++; continue; }

            // FRACTION_NUMERATOR/DENOMINATOR → radicand 안에 분수 포함 가능
            if (t.type() == EHToken.Type.FRACTION_NUMERATOR) {
                i = processFraction(tokens, i, sqrt.radicand());
                continue;
            }

            // SUPERSCRIPT/SUBSCRIPT 글리프 → radicand 안에 첨자 포함
            if (t.type() == EHToken.Type.SUPERSCRIPT_GLYPH) {
                i = processSuperscriptGlyphs(tokens, i, sqrt.radicand());
                continue;
            }
            if (t.type() == EHToken.Type.SUBSCRIPT_GLYPH) {
                i = processSubscriptGlyphs(tokens, i, sqrt.radicand());
                continue;
            }

            // BACKTICK_SUPER → radicand 안에 위첨자
            if (t.type() == EHToken.Type.BACKTICK_SUPER) {
                EHNode.Superscript sup = new EHNode.Superscript();
                sup.content().add(new EHNode.Text(t.text()));
                sqrt.radicand().add(sup);
                i++;
                continue;
            }

            // GREP_FRACTION → radicand 안에 분수
            if (t.type() == EHToken.Type.GREP_FRACTION) {
                EHNode.Fraction frac = new EHNode.Fraction();
                if (t.fracNumerator() != null) frac.numerator().add(new EHNode.Text(t.fracNumerator()));
                if (t.fracDenominator() != null) frac.denominator().add(new EHNode.Text(t.fracDenominator()));
                sqrt.radicand().add(frac);
                i++;
                continue;
            }

            // BASE_TEXT, SUP_BASE_TEXT, SUB_BASE_TEXT → 한국어/thin space에서 분리
            if (t.type() == EHToken.Type.BASE_TEXT
                    || t.type() == EHToken.Type.SUP_BASE_TEXT
                    || t.type() == EHToken.Type.SUB_BASE_TEXT) {
                String text = t.text();
                int splitPos = findRadicandEnd(text);

                if (splitPos == 0) {
                    // 전체가 radicand 밖 (한국어로 시작) → sqrt 종료
                    break;
                } else if (splitPos > 0 && splitPos < text.length()) {
                    // 부분 분리: 앞부분 radicand, 뒷부분 밖
                    sqrt.radicand().add(new EHNode.Text(text.substring(0, splitPos)));
                    // 나머지는 sqrt 밖 — 토큰을 분할하여 다음에 처리
                    // 현재 토큰을 수정할 수 없으므로, 나머지를 out에 추가
                    out.add(sqrt);
                    out.add(new EHNode.Text(text.substring(splitPos)));
                    return i + 1;
                } else {
                    // 전체가 radicand 안 → 계속 수집
                    sqrt.radicand().add(new EHNode.Text(text));
                    i++;
                    continue;
                }
            }

            // FRACTION_DENOMINATOR (단독) → radicand 밖
            if (t.type() == EHToken.Type.FRACTION_DENOMINATOR) break;

            // 기타 → radicand 밖
            break;
        }

        // sqrt에 내용이 있으면 추가, 없으면 빈 sqrt 기호
        out.add(sqrt);
        return i;
    }

    /**
     * FRACTION_NUMERATOR부터 시작하여 분수 구성.
     * FRACTION_NUMERATOR + FRACTION_DENOMINATOR 쌍.
     */
    private static int processFraction(List<EHToken> tokens, int pos, List<EHNode> out) {
        EHNode.Fraction frac = new EHNode.Fraction();
        EHToken numToken = tokens.get(pos);
        if (numToken.text() != null && !numToken.text().isEmpty()) {
            frac.numerator().add(new EHNode.Text(numToken.text()));
        }

        int i = pos + 1;
        // 연속 FRACTION_NUMERATOR 병합 (드문 케이스)
        while (i < tokens.size() && tokens.get(i).type() == EHToken.Type.FRACTION_NUMERATOR) {
            if (tokens.get(i).text() != null && !tokens.get(i).text().isEmpty()) {
                frac.numerator().add(new EHNode.Text(tokens.get(i).text()));
            }
            i++;
        }

        // FRACTION_DENOMINATOR 찾기
        if (i < tokens.size() && tokens.get(i).type() == EHToken.Type.FRACTION_DENOMINATOR) {
            if (tokens.get(i).text() != null && !tokens.get(i).text().isEmpty()) {
                frac.denominator().add(new EHNode.Text(tokens.get(i).text()));
            }
            i++;
            // 연속 FRACTION_DENOMINATOR 병합
            while (i < tokens.size() && tokens.get(i).type() == EHToken.Type.FRACTION_DENOMINATOR) {
                if (tokens.get(i).text() != null && !tokens.get(i).text().isEmpty()) {
                    frac.denominator().add(new EHNode.Text(tokens.get(i).text()));
                }
                i++;
            }
        }

        out.add(frac);
        return i;
    }

    /**
     * 연속 SUPERSCRIPT_GLYPH를 하나의 Superscript 노드로 병합.
     */
    private static int processSuperscriptGlyphs(List<EHToken> tokens, int pos, List<EHNode> out) {
        EHNode.Superscript sup = new EHNode.Superscript();
        StringBuilder merged = new StringBuilder();
        int i = pos;
        while (i < tokens.size() && tokens.get(i).type() == EHToken.Type.SUPERSCRIPT_GLYPH) {
            merged.append(tokens.get(i).text());
            i++;
        }
        sup.content().add(new EHNode.Text(merged.toString()));
        out.add(sup);
        return i;
    }

    /**
     * 연속 SUBSCRIPT_GLYPH를 하나의 Subscript 노드로 병합.
     */
    private static int processSubscriptGlyphs(List<EHToken> tokens, int pos, List<EHNode> out) {
        EHNode.Subscript sub = new EHNode.Subscript();
        StringBuilder merged = new StringBuilder();
        int i = pos;
        while (i < tokens.size() && tokens.get(i).type() == EHToken.Type.SUBSCRIPT_GLYPH) {
            merged.append(tokens.get(i).text());
            i++;
        }
        sub.content().add(new EHNode.Text(merged.toString()));
        out.add(sub);
        return i;
    }

    /**
     * radicand 종료 위치 탐색.
     * radicand = 하나의 수학 "항(factor)": 숫자/문자 시퀀스 또는 괄호 그룹.
     *
     * 종료 조건:
     * - 한국어, thin space, 줄바꿈 → 무조건 종료
     * - =, <, >, , → 무조건 종료
     * - +, - → i > 0일 때만 종료 (단항 연산자는 radicand에 포함)
     * - 매칭 안 되는 닫는 괄호 → 종료
     *
     * @return 종료 인덱스 (해당 위치 이전까지가 radicand), -1이면 전체가 radicand
     */
    private static int findRadicandEnd(String text) {
        int parenDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 한국어
            if (ch >= 0xAC00 && ch <= 0xD7A3) return i;
            // thin space / 줄바꿈 / 탭 (선택지 구분자)
            if (ch == '\u2009' || ch == '\r' || ch == '\n' || ch == '\t') return i;
            // 무조건 종료 연산자
            if (ch == '=' || ch == ',' || ch == '<' || ch == '>') return i;
            // 이항 연산자: 첫 문자가 아닐 때만 종료 (단항 -, + 허용)
            if ((ch == '+' || ch == '-') && i > 0 && parenDepth == 0) return i;
            // 괄호 균형
            if (ch == '(') parenDepth++;
            else if (ch == ')') {
                if (parenDepth <= 0) return i;
                parenDepth--;
            }
        }
        return -1;
    }
}
