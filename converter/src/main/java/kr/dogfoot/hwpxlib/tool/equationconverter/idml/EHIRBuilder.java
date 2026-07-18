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

            case FRACTION_DENOMINATOR: {
                // standalone 분모: 연속 토큰 수집 → decodeFractionInner로 분수 조립
                StringBuilder fracRaw = new StringBuilder();
                fracRaw.append(token.text());
                int fi = pos + 1;
                while (fi < tokens.size()) {
                    EHToken ft = tokens.get(fi);
                    if (ft.type() == EHToken.Type.FRACTION_DENOMINATOR) {
                        fracRaw.append(ft.text());
                        fi++;
                    } else if (ft.type() == EHToken.Type.SUP_BASE_TEXT
                            || ft.type() == EHToken.Type.BASE_TEXT) {
                        // 탭은 선택지 구분자이지 분수 내용이 아니다. 여기서 수집을
                        // 멈춰야 뒤 항(⑷ …)이 분수에 흡수되지 않는다(실측: 3단원
                        // y=;[%;\t⑷ 에서 탭이 분수에 먹혀 (3)(4) 항이 붙던 문제).
                        if (ft.text() != null && ft.text().indexOf('\t') >= 0) break;
                        // 닫는 괄호 )는 바깥 괄호의 닫기이지 분모 내용이 아니다. 여기서
                        // 멈춰야 (-√(1/6))² 의 바깥 ) 가 분수에 흡수돼 유실되지 않는다
                        // (실측: 1단원 √((-1/2)²)의 여는 ( 짝이 사라짐).
                        if (ft.text() != null && ft.text().indexOf(')') >= 0) break;
                        fracRaw.append(ft.text());
                        fi++;
                    } else if (ft.type() == EHToken.Type.SKIP) {
                        fi++;
                    } else {
                        break;
                    }
                }
                String[] parts = EHFontGlyphMap.decodeFractionInner(fracRaw.toString());
                if (parts != null && (parts[0].length() > 0 || parts[1].length() > 0)) {
                    EHNode.Fraction frac = new EHNode.Fraction();
                    if (parts[0].length() > 0) frac.numerator().add(new EHNode.Text(parts[0]));
                    if (parts[1].length() > 0) frac.denominator().add(new EHNode.Text(parts[1]));
                    out.add(frac);
                    return fi;
                }
                // decodeFractionInner 실패 → 텍스트 폴백
                out.add(new EHNode.Text(token.text()));
                return pos + 1;
            }

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

        // 괄호 스택 깊이. radicand 안에서 (…) 가 열려 있으면(depth>0) 종료 조건
        // (제곱 trailing·닫는 괄호·구분자)을 억제해 (…)² 처럼 괄호 그룹 전체를 근호에
        // 포함한다. ( 는 push(+1), ) 는 pop(-1). 토큰 경계를 넘어 깊이를 유지한다.
        int[] parenDepth = {0};

        // radicand 수집: 다음 토큰들에서 한국어/줄바꿈 이전까지
        while (i < tokens.size()) {
            EHToken t = tokens.get(i);

            // SQRT_MARKER가 다시 오면:
            // - radicand가 비어있으면 → √ 바 연장 (스킵)
            // - radicand가 있고 다음이 비SQRT 토큰이면 → √ 바 연장 (스킵, radicand 계속)
            // - radicand가 있고 다음이 SQRT_MARKER이면 → 연속 √ (스킵)
            // - radicand가 있고 다음이 없거나 한국어이면 → 새로운 √ (종료)
            if (t.type() == EHToken.Type.SQRT_MARKER) {
                // 연속 SQRT_MARKER 스킵
                while (i < tokens.size() && tokens.get(i).type() == EHToken.Type.SQRT_MARKER) {
                    i++;
                }
                // 다음 토큰 확인: radicand 계속 수집 가능하면 √ 바 연장
                if (i < tokens.size() && !sqrt.radicand().isEmpty()) {
                    EHToken next = tokens.get(i);
                    // radicand 가 이미 완결된 분수로 끝났으면 바 연장하지 않는다 —
                    // √(5/2)√10 처럼 분수 근호 뒤 SQRT_MARKER 는 새 근호다. 분수는 그
                    // 자체로 완결된 radicand 라 뒤 숫자가 붙을 이유가 없다(실측: 1단원
                    // p33 √(5/2)√10 이 √(5/2·10) 로 뭉치던 문제). 분수와 SQRT_MARKER
                    // 사이의 백틱(thin space)·빈 Text 노드는 건너뛰고 실질 마지막을 본다.
                    boolean radicandEndsWithFraction = false;
                    for (int rj = sqrt.radicand().size() - 1; rj >= 0; rj--) {
                        EHNode last = sqrt.radicand().get(rj);
                        if (last instanceof EHNode.Text) {
                            String lt = ((EHNode.Text) last).text();
                            if (lt == null || lt.replace("`", "").trim().isEmpty()) continue;
                        }
                        radicandEndsWithFraction = last instanceof EHNode.Fraction;
                        break;
                    }
                    // 다음이 SKIP/BASE_TEXT/SUP_BASE_TEXT 등 radicand 후보이면 계속
                    if (!radicandEndsWithFraction
                            && next.type() != EHToken.Type.SQRT_MARKER
                            && next.type() != EHToken.Type.FRACTION_DENOMINATOR) {
                        continue; // √ 바 연장 → radicand 계속 수집
                    }
                }
                break; // 새로운 √ 또는 종료
            }

            // SKIP 토큰은 건너뜀
            if (t.type() == EHToken.Type.SKIP) { i++; continue; }

            // FRACTION_NUMERATOR/DENOMINATOR → radicand 안에 분수 포함 가능
            if (t.type() == EHToken.Type.FRACTION_NUMERATOR) {
                i = processFraction(tokens, i, sqrt.radicand());
                continue;
            }

            // SUPERSCRIPT/SUBSCRIPT 글리프
            if (t.type() == EHToken.Type.SUPERSCRIPT_GLYPH) {
                // radicand 뒤에 오는 지수(예: √23² → sqrt{23}^{2})인지 확인.
                // 단, 괄호가 열려 있거나(parenDepth>0) radicand가 닫는 괄호 )로 끝났으면
                // 이 제곱은 괄호 그룹에 붙은 것이므로 radicand 안에 포함한다(실측:
                // √((1/2)²) → 근호가 (1/2)² 전체를 덮어야 하고, 제곱이 밖으로 새면 sqrt
                // 범위가 = 까지 잘못 확장됨).
                boolean insideOrAfterParen = parenDepth[0] > 0
                        || radicandEndsWithCloseParen(sqrt.radicand());
                if (!sqrt.radicand().isEmpty() && !insideOrAfterParen
                        && isTrailingExponent(tokens, i)) {
                    break; // sqrt 종료, 상위에서 superscript 처리
                }
                i = processSuperscriptGlyphs(tokens, i, sqrt.radicand());
                continue;
            }
            if (t.type() == EHToken.Type.SUBSCRIPT_GLYPH) {
                i = processSubscriptGlyphs(tokens, i, sqrt.radicand());
                continue;
            }

            // BACKTICK_SUPER → 지수 여부 확인
            if (t.type() == EHToken.Type.BACKTICK_SUPER) {
                if (!sqrt.radicand().isEmpty() && !radicandEndsWithCloseParen(sqrt.radicand())
                        && isTrailingExponent(tokens, i)) {
                    break; // sqrt 종료, 상위에서 superscript 처리
                }
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

                // 괄호가 열려 있으면(depth>0) 종료 조건을 억제하고 이 토큰 전체를
                // radicand 에 넣되, 괄호 깊이를 갱신한다. 여는 ( 를 만나도 마찬가지로
                // 이 토큰부터 괄호 구간에 진입한다. 스택 방식이라 (…) 짝을 절대 놓치지 않는다.
                int splitPos = parenDepth[0] > 0 ? -1 : findRadicandEnd(text);

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
                    // 전체가 radicand 안 → 계속 수집. 괄호 깊이 갱신.
                    parenDepth[0] += countParenDelta(text);
                    if (parenDepth[0] < 0) parenDepth[0] = 0;
                    sqrt.radicand().add(new EHNode.Text(text));
                    i++;
                    continue;
                }
            }

            // FRACTION_DENOMINATOR → √(분수) 패턴이면 radicand에 분수 포함
            if (t.type() == EHToken.Type.FRACTION_DENOMINATOR) {
                // EH 분수소문자 연속 토큰을 모아서 decodeFractionInner로 분자/분모 분리
                // 토크나이저의 decodeText가 분수소문자 ASCII(;@#$)를 그대로 통과시키므로
                // text()가 raw와 동일 → decodeFractionInner에 직접 전달 가능
                StringBuilder fracRaw = new StringBuilder();
                fracRaw.append(t.text());
                int fi = i + 1;
                while (fi < tokens.size()) {
                    EHToken ft = tokens.get(fi);
                    if (ft.type() == EHToken.Type.FRACTION_DENOMINATOR) {
                        fracRaw.append(ft.text());
                        fi++;
                    } else if (ft.type() == EHToken.Type.SUP_BASE_TEXT
                            || ft.type() == EHToken.Type.BASE_TEXT) {
                        // 탭은 선택지 구분자이지 분수 내용이 아니다. 여기서 수집을
                        // 멈춰야 뒤 항(⑷ …)이 분수에 흡수되지 않는다(실측: 3단원
                        // y=;[%;\t⑷ 에서 탭이 분수에 먹혀 (3)(4) 항이 붙던 문제).
                        if (ft.text() != null && ft.text().indexOf('\t') >= 0) break;
                        // 닫는 괄호 )는 바깥 괄호의 닫기이지 분모 내용이 아니다. 여기서
                        // 멈춰야 (-√(1/6))² 의 바깥 ) 가 분수에 흡수돼 유실되지 않는다
                        // (실측: 1단원 √((-1/2)²)의 여는 ( 짝이 사라짐).
                        if (ft.text() != null && ft.text().indexOf(')') >= 0) break;
                        fracRaw.append(ft.text());
                        fi++;
                    } else if (ft.type() == EHToken.Type.SKIP) {
                        fi++;
                    } else {
                        break;
                    }
                }
                String[] parts = EHFontGlyphMap.decodeFractionInner(fracRaw.toString());
                if (parts != null && (parts[0].length() > 0 || parts[1].length() > 0)) {
                    EHNode.Fraction frac = new EHNode.Fraction();
                    if (parts[0].length() > 0) frac.numerator().add(new EHNode.Text(parts[0]));
                    if (parts[1].length() > 0) frac.denominator().add(new EHNode.Text(parts[1]));
                    sqrt.radicand().add(frac);
                    i = fi;
                    continue;
                }
                break; // 분수 구성 실패 → radicand 밖
            }

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
    /**
     * 위첨자 토큰이 sqrt 밖의 지수(trailing exponent)인지 판별.
     * superPos 이후에 radicand에 기여할 토큰이 없으면 trailing exponent.
     * 예: √23² → SQRT_MARKER, BASE_TEXT "23", SUPERSCRIPT_GLYPH "2"
     *     → "2"는 trailing → sqrt{23}^{2}
     */
    /** 텍스트의 소괄호 균형 증감: ( 는 +1, ) 는 -1. 괄호 스택 깊이 갱신용. */
    private static int countParenDelta(String text) {
        if (text == null) return 0;
        int delta = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') delta++;
            else if (c == ')') delta--;
        }
        return delta;
    }

    /**
     * radicand의 마지막 유효 문자가 닫는 괄호 ) 인지 확인.
     * (…)² 처럼 괄호 그룹에 붙은 제곱은 근호 안에 포함해야 한다.
     */
    private static boolean radicandEndsWithCloseParen(List<EHNode> radicand) {
        for (int k = radicand.size() - 1; k >= 0; k--) {
            EHNode node = radicand.get(k);
            if (node instanceof EHNode.Text) {
                String t = ((EHNode.Text) node).text();
                if (t == null) continue;
                for (int c = t.length() - 1; c >= 0; c--) {
                    char ch = t.charAt(c);
                    if (ch == ' ' || ch == '`') continue;
                    return ch == ')';
                }
                continue;
            }
            // 분수/루트 등 비Text 노드로 끝나면 괄호 종료 아님
            return false;
        }
        return false;
    }

    private static boolean isTrailingExponent(List<EHToken> tokens, int superPos) {
        boolean passedSeparator = false;
        for (int j = superPos + 1; j < tokens.size(); j++) {
            EHToken next = tokens.get(j);
            EHToken.Type nt = next.type();
            // 연속 위첨자/스킵/백틱 → 같이 trailing
            if (nt == EHToken.Type.SKIP || nt == EHToken.Type.BACKTICK_SUPER
                    || nt == EHToken.Type.SUPERSCRIPT_GLYPH) continue;
            // BASE_TEXT/SUP_BASE_TEXT: 구분자(탭, 공백, 원문자 등)인지 확인
            if (nt == EHToken.Type.BASE_TEXT || nt == EHToken.Type.SUP_BASE_TEXT) {
                String text = next.text();
                if (text == null || text.isEmpty()) continue;
                char c = text.charAt(0);
                if (c == '`' || c == '\t' || c == '\r' || c == '\n' || c == ' ') {
                    passedSeparator = true;
                    continue;
                }
                if (c >= 0xAC00 && c <= 0xD7AF) { passedSeparator = true; continue; } // 한국어
                if (c >= 0x2460 && c <= 0x2487) { passedSeparator = true; continue; } // 원문자 ①⑴
                // 구분자 이후 새 내용 → 다른 수식이므로 trailing
                if (passedSeparator) continue;
                return false; // 구분자 없이 직접 이어지는 내용 → radicand
            }
            // SQRT_MARKER/FRACTION: 구분자 이후이면 새 수식 시작 → trailing
            if (nt == EHToken.Type.SQRT_MARKER || nt == EHToken.Type.FRACTION_NUMERATOR
                    || nt == EHToken.Type.GREP_FRACTION) {
                if (passedSeparator) continue;
                return false;
            }
        }
        return true;
    }

    private static int findRadicandEnd(String text) {
        int parenDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 근호 종료 센티넬(U+241C) — 이 근호의 radicand 끝(실측: 1단원 p22
            // √15·√0.81·√(9/144), 항 간격 스페이서 Rectangle 앵커 위치가 경계).
            if (ch == '\u241C') return i;
            // 한국어
            if (ch >= 0xAC00 && ch <= 0xD7A3) return i;
            // thin space / four-per-em space / 줄바꿈 / 탭 (선택지 구분자)
            // \u2005 (FOUR-PER-EM SPACE)는 InDesign에서 × (곱셈) 표현에 사용됨
            if (ch == '\u2009' || ch == '\u2005' || ch == '\r' || ch == '\n' || ch == '\t') return i;
            // 무조건 종료 연산자
            if (ch == '=' || ch == ',' || ch == '<' || ch == '>'
                    || ch == '\u00F7' || ch == '\u00D6') return i; // ÷(decoded) 및 Ö(raw EH상부자)
            // _ = × (EH상부자 곱셈 기호) → radicand 분리
            if (ch == '_' && i > 0) return i;
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
