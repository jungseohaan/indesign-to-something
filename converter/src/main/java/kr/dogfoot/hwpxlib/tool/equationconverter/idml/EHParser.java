package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import java.util.List;

/**
 * EH 수식 재작성 Phase 2: EHLexeme 리스트 → EHNode 트리 (재귀하강 문법).
 *
 * <p>기존 {@link EHIRBuilder}의 스트림 heuristic(findRadicandEnd / "바 연장 추측")을
 * 명시적 문법으로 대체한다:
 *
 * <pre>
 *   Group    := Item*
 *   Item     := Sqrt | Fraction | Overline | Box | Sep | Text
 *   Sqrt     := HOOK WidthSel? Radicand
 *   Radicand := RadAtom Trailing?
 *   RadAtom  := Number | Var | Fraction | ParenGroup | Box
 *   Number   := ATOM_digits (DIGIT_SEP ATOM_digits)*   // 0x8C 로 조각난 √10 을 이어붙임
 *   ParenGroup := '(' Item* ')'                        // 괄호 균형까지, 지수 흡수
 *   Trailing := SUPERSCRIPT
 * </pre>
 *
 * <p>핵심: HOOK 마다 새 Sqrt (√2·√3 자동 분리). radicand 는 정확히 하나의 RadAtom +
 * 선택 지수. SEP·새 HOOK 을 만나면 radicand 종료. BOX(ANSWER)는 RadAtom 자리에 올 수 있고,
 * BOX(VINCULUM)은 무시된다.
 */
public class EHParser {

    private final List<EHLexeme> lx;
    private int pos;

    private EHParser(List<EHLexeme> lexemes) {
        this.lx = lexemes;
    }

    public static EHNode.Group build(List<EHLexeme> lexemes) {
        if (lexemes == null || lexemes.isEmpty()) return null;
        EHParser p = new EHParser(lexemes);
        EHNode.Group root = new EHNode.Group();
        p.parseItems(root.children(), false);
        return root;
    }

    private EHLexeme peek() { return pos < lx.size() ? lx.get(pos) : null; }
    private EHLexeme next() { return lx.get(pos++); }
    private boolean has() { return pos < lx.size(); }

    /** Item* 를 out 에 파싱. inParen=true 면 ')'(ATOM ")") 에서 멈춘다. */
    private void parseItems(List<EHNode> out, boolean inParen) {
        while (has()) {
            EHLexeme t = peek();
            switch (t.kind()) {
                case HOOK:
                    out.add(parseSqrt());
                    break;
                case FRACTION:
                    next();
                    out.add(fractionNode(t));
                    break;
                case BOX:
                    next();
                    if (t.boxKind() == EHNode.Box.Kind.ANSWER) out.add(new EHNode.Box(EHNode.Box.Kind.ANSWER));
                    // VINCULUM 은 무시
                    break;
                case DOT:
                    next();
                    // 뒤 숫자에 dot 적용
                    applyRecurDot(out);
                    break;
                case SEP:
                    next();
                    if (t.value() != null && !t.value().trim().isEmpty()) {
                        out.add(new EHNode.Text(t.value())); // EM/thin space·원문자 등 텍스트로
                    } else if (t.value() != null) {
                        out.add(new EHNode.Text(t.value()));
                    }
                    break;
                case SUPERSCRIPT:
                    next();
                    out.add(superNode(t.value()));
                    break;
                case SUBSCRIPT:
                    next();
                    out.add(subNode(t.value()));
                    break;
                case OVERLINE:
                    next();
                    wrapLastInOverline(out);
                    break;
                case ATOM_TEXT: {
                    String v = t.value();
                    if (inParen && v != null && v.startsWith(")")) return; // 괄호 종료는 상위에서
                    next();
                    out.add(new EHNode.Text(v));
                    break;
                }
                case WIDTH_SELECTOR:
                case DIGIT_SEP:
                case SKIP:
                    next(); // 근호 밖에서는 무시
                    break;
                default:
                    next();
            }
        }
    }

    /** Sqrt := HOOK WidthSel? Radicand */
    private EHNode parseSqrt() {
        next(); // consume HOOK
        // 연속 HOOK/WIDTH_SELECTOR (같은 근호의 폭 마커 조각) 스킵
        while (has() && (peek().kind() == EHLexeme.Kind.WIDTH_SELECTOR
                || peek().kind() == EHLexeme.Kind.HOOK)) {
            // 연속 HOOK 이 오면? — 별개 근호이므로 여기서 멈춰야 하지만, 실측상 hook 직후
            // width-selector 만 스킵. 새 HOOK 은 radicand 파싱이 끝난 뒤 상위 루프가 잡는다.
            if (peek().kind() == EHLexeme.Kind.HOOK) break;
            next();
        }
        EHNode.Sqrt sqrt = new EHNode.Sqrt();
        parseRadicand(sqrt.radicand());
        return sqrt;
    }

    /** Radicand := RadAtom Trailing? — 하나의 원자 + 선택 지수. */
    private void parseRadicand(List<EHNode> out) {
        if (!has()) return;
        EHLexeme t = peek();
        switch (t.kind()) {
            case ATOM_TEXT: {
                String v = t.value();
                // 여는 괄호로 시작하면 ParenGroup
                if (v != null && v.startsWith("(")) {
                    parseParenGroupInto(out);
                } else {
                    parseNumberOrText(out);
                }
                break;
            }
            case FRACTION:
                next();
                out.add(fractionNode(t));
                break;
            case BOX:
                next();
                if (t.boxKind() == EHNode.Box.Kind.ANSWER) out.add(new EHNode.Box(EHNode.Box.Kind.ANSWER));
                break;
            case WIDTH_SELECTOR:
                next();
                parseRadicand(out); // 폭 선택자 스킵 후 재시도
                return;
            default:
                return; // radicand 없음(빈 근호)
        }
        // radicand 직후 SUPERSCRIPT 는 근호 안에 넣지 않는다 — (√3)² 처럼 근호 밖 지수다.
        // SUPERSCRIPT 를 소비하지 않고 남기면 parseItems 가 Sqrt 의 형제로 emit 하고,
        // emitter 의 "Sqrt 뒤 Superscript → {sqrt{…}}^{n}" 래핑 규칙이 근호를 감싼다.
        // 괄호그룹 뒤 지수(√((-1/2)²))는 이미 parseParenGroupInto 가 Paren 안에 흡수했다.
    }

    /**
     * Number/Text radicand: ATOM 을 소비하되, DIGIT_SEP 로 이어지는 숫자는 이어붙인다.
     *
     * <p>"2_"(=2×) 의 곱셈 연산자 처리가 핵심:
     * <ul>
     *   <li>_ 뒤가 box/fraction/숫자 → radicand 안 곱셈 (√(2×□), √(2×9/2)).</li>
     *   <li>_ 뒤가 새 HOOK → 곱셈은 근호 밖 (√2·√3). radicand 는 "2"까지, TIMES 는
     *       상위 out 에 형제로 emit.</li>
     * </ul>
     * out 에는 radicand 만 넣고, 근호 밖으로 나갈 트레일링 TIMES 는 pendingTrailing 에 담아
     * 호출자(parseSqrt→parseRadicand→parseItems)가 배치하게 한다. 여기서는 radicand 안
     * 곱셈만 처리하고, 근호 밖 곱셈이면 TIMES 를 소비하지 않고 남긴다.
     */
    private void parseNumberOrText(List<EHNode> out) {
        StringBuilder sb = new StringBuilder();
        while (has()) {
            EHLexeme t = peek();
            if (t.kind() == EHLexeme.Kind.ATOM_TEXT) {
                String v = t.value();
                if (v.startsWith(")")) break; // 닫는 괄호는 상위 ParenGroup 처리
                // ATOM 이 곱셈 _ 로 끝나는가?
                int us = v.indexOf('_');
                if (us >= 0) {
                    String before = v.substring(0, us);
                    String after = v.substring(us + 1);
                    // _ 뒤에 같은 ATOM 안에 내용이 더 있으면(2_3) radicand 안 곱셈
                    if (!after.isEmpty()) {
                        next();
                        sb.append(before).append(" TIMES ").append(after.replace("_", " TIMES "));
                        continue;
                    }
                    // _ 로 끝남: 다음 lexeme 으로 안/밖 판정
                    EHLexeme n = (pos + 1 < lx.size()) ? lx.get(pos + 1) : null;
                    boolean insideTimes = n != null
                            && (n.kind() == EHLexeme.Kind.BOX
                                || n.kind() == EHLexeme.Kind.FRACTION
                                || (n.kind() == EHLexeme.Kind.ATOM_TEXT
                                    && n.value() != null && !n.value().isEmpty()
                                    && Character.isLetterOrDigit(n.value().charAt(0))));
                    if (insideTimes) {
                        next();
                        sb.append(before).append(" TIMES ");
                        continue; // radicand 안에서 계속(box/fraction/숫자 흡수)
                    } else {
                        // 근호 밖 곱셈: radicand 는 before 까지. TIMES 는 소비 안 함(남김).
                        // 현재 ATOM 을 before(radicand) + "_" 나머지로 분해: before 만 넣고,
                        // _ 를 별도 TIMES ATOM 으로 남기기 위해 lexeme 을 교체.
                        next();
                        sb.append(before);
                        lx.set(--pos, EHLexeme.atomText("_")); // "_" 를 다시 큐에(밖 곱셈)
                        break;
                    }
                }
                next();
                sb.append(v);
            } else if (t.kind() == EHLexeme.Kind.DIGIT_SEP) {
                next(); // 자리구분 제거하고 숫자 이어붙임
            } else if (t.kind() == EHLexeme.Kind.FRACTION) {
                // radicand 안 곱셈 뒤 분수 (2×9/2)
                if (sb.length() > 0 && sb.toString().trim().endsWith("TIMES")) {
                    next();
                    flushText(sb, out);
                    out.add(fractionNode(t));
                } else break;
            } else if (t.kind() == EHLexeme.Kind.BOX && t.boxKind() == EHNode.Box.Kind.ANSWER) {
                // radicand 안 곱셈 뒤 box (2×□)
                if (sb.length() > 0 && sb.toString().trim().endsWith("TIMES")) {
                    next();
                    flushText(sb, out);
                    out.add(new EHNode.Box(EHNode.Box.Kind.ANSWER));
                } else break;
            } else {
                break;
            }
        }
        flushText(sb, out);
    }

    private void flushText(StringBuilder sb, List<EHNode> out) {
        if (sb.length() > 0) {
            out.add(new EHNode.Text(sb.toString()));
            sb.setLength(0);
        }
    }

    /** '(' Item* ')' 를 out 에 Paren 노드로. */
    private void parseParenGroupInto(List<EHNode> out) {
        EHLexeme t = next(); // ATOM starting with '('
        String v = t.value();
        EHNode.Paren paren = new EHNode.Paren();
        // '(' 뒤 나머지 텍스트
        String rest = v.substring(1);
        int depth = 1;
        depth += count(rest, '(') - count(rest, ')');
        if (!rest.isEmpty()) paren.content().add(new EHNode.Text(stripOuterClose(rest)));
        // 괄호 균형까지 Item 수집
        while (has() && depth > 0) {
            EHLexeme n = peek();
            if (n.kind() == EHLexeme.Kind.ATOM_TEXT) {
                next();
                String nv = n.value();
                depth += count(nv, '(') - count(nv, ')');
                if (depth <= 0) {
                    // 닫는 괄호 위치까지만
                    paren.content().add(new EHNode.Text(stripOuterClose(nv)));
                    break;
                }
                paren.content().add(new EHNode.Text(nv));
            } else if (n.kind() == EHLexeme.Kind.HOOK) {
                paren.content().add(parseSqrt());
            } else if (n.kind() == EHLexeme.Kind.FRACTION) {
                next();
                paren.content().add(fractionNode(n));
            } else if (n.kind() == EHLexeme.Kind.SUPERSCRIPT) {
                next();
                paren.content().add(superNode(n.value()));
            } else if (n.kind() == EHLexeme.Kind.BOX) {
                next();
                if (n.boxKind() == EHNode.Box.Kind.ANSWER) paren.content().add(new EHNode.Box(EHNode.Box.Kind.ANSWER));
            } else {
                break;
            }
        }
        out.add(paren);
        // Paren 직후 지수(제곱)는 radicand 의 Trailing 으로 흡수
        if (has() && peek().kind() == EHLexeme.Kind.SUPERSCRIPT) {
            out.add(superNode(next().value()));
        }
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** 닫는 괄호로 끝나는 문자열의 마지막 ')' 를 제거(Paren 노드가 괄호를 대신 그림). */
    private static String stripOuterClose(String s) {
        int idx = s.lastIndexOf(')');
        if (idx >= 0) return s.substring(0, idx) + s.substring(idx + 1);
        return s;
    }

    private EHNode fractionNode(EHLexeme t) {
        EHNode.Fraction f = new EHNode.Fraction();
        if (t.num() != null && !t.num().isEmpty()) f.numerator().add(new EHNode.Text(t.num()));
        if (t.den() != null && !t.den().isEmpty()) f.denominator().add(new EHNode.Text(t.den()));
        return f;
    }

    private EHNode superNode(String v) {
        EHNode.Superscript s = new EHNode.Superscript();
        if (v != null) s.content().add(new EHNode.Text(v));
        return s;
    }

    private EHNode subNode(String v) {
        EHNode.Subscript s = new EHNode.Subscript();
        if (v != null) s.content().add(new EHNode.Text(v));
        return s;
    }

    private void applyRecurDot(List<EHNode> out) {
        // DOT 다음 ATOM 의 첫 숫자에 dot 적용
        if (has() && peek().kind() == EHLexeme.Kind.ATOM_TEXT) {
            String v = peek().value();
            if (v != null && !v.isEmpty() && Character.isDigit(v.charAt(0))) {
                next();
                out.add(new EHNode.RecurDot(String.valueOf(v.charAt(0))));
                if (v.length() > 1) out.add(new EHNode.Text(v.substring(1)));
                return;
            }
        }
        // 뒤 숫자 없으면 고아 DOT 버림
    }

    private void wrapLastInOverline(List<EHNode> out) {
        if (out.isEmpty()) return;
        EHNode last = out.remove(out.size() - 1);
        EHNode.Overline ov = new EHNode.Overline();
        ov.content().add(last);
        out.add(ov);
    }
}
