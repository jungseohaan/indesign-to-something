package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.ArrayList;
import java.util.List;

/**
 * EH 수식 재작성 Phase 1: 런 → EHLexeme 리스트.
 *
 * <p>글리프의 폰트 역할별 의미를 보존한다. 구조(근호 범위 등)는 결정하지 않는다 —
 * 그건 {@link EHParser}(재귀하강 문법)가 한다. 기존 {@link EHTokenizer}의
 * "isFractionBarDecoration 으로 SQRT_MARKER 뭉개기"를 폐기하고, 근호 갈고리(HOOK)·
 * 폭선택자(WIDTH_SELECTOR)·자리구분자(DIGIT_SEP)·radicand 를 각각 구분해 태깅한다.
 *
 * <p>1차 식별 신호는 fontFamily 가 아니라 AppliedCharacterStyle(
 * {@link EHFontGlyphMap#extractFontFromStyle}) — 실측상 AppliedFont 로 직접
 * 지정된 EH 런은 극소수이고 절대다수가 CharacterStyle 이름으로 식별된다.
 */
public class EHLexer {

    /**
     * 근호 갈고리 글리프(분수대문자 폰트에서 radicand 를 여는 시각 마커).
     * '"'(0x22) 는 키 큰 hook — radicand 에 위첨자가 포함될 때(√3², √(-3)²) 쓰인다
     * (실측: 1단원 p17 탐구2). 놓치면 HWP 스크립트에 '"' 리터럴이 들어가 수식이
     * 통째로 사라진다.
     */
    private static boolean isHookGlyph(char c) {
        return c == '\'' || c == '"' || c == '®' /* ® */
                || c == '¿' /* ¿ */ || c == '¾' /* ¾ */;
    }

    /** 자리구분자: 분수대문자 폰트의 0x8C (여러자리 수의 숫자 사이 장식). */
    private static boolean isDigitSep(char c) {
        return c == '';
    }

    public static List<EHLexeme> lex(List<IDMLCharacterRun> runs) {
        List<EHLexeme> out = new ArrayList<>();
        if (runs == null) return out;
        for (IDMLCharacterRun run : runs) {
            lexRun(run, out);
        }
        return out;
    }

    private static void lexRun(IDMLCharacterRun run, List<EHLexeme> out) {
        if (run == null) return;

        // 인라인 그래픽(빈 답란 박스 / vinculum)을 BOX/SKIP lexeme 로.
        if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) {
            emitInlineGraphics(run, out);
            // content 에 FFFC 만 있으면 그래픽만 처리하고 종료.
            String c = run.content();
            if (c == null || c.replace("￼", "").isEmpty()) return;
        }

        String content = run.content();
        if (content == null || content.isEmpty()) return;
        content = content.replace("￼", ""); // 이미 그래픽으로 처리됨
        if (content.isEmpty()) return;

        String fontRole = fontRole(run);

        if ("EH분수대문자".equals(fontRole)) {
            lexFractionUpper(content, run, out);
        } else if ("EH분수소문자".equals(fontRole)) {
            // 분모 전용 런 (실측 극소). 디코딩해 ATOM 으로 — GREP 경로가 주력이라 드묾.
            emitAtom(EHFontGlyphMap.decodeText(content, "EH분수소문자"), out);
        } else if ("EH상부자".equals(fontRole) || "EH고딕상부자".equals(fontRole)) {
            lexSubSup(content, true, out);
        } else if ("EH하부자".equals(fontRole) || "EH고딕하부자".equals(fontRole)) {
            lexSubSup(content, false, out);
        } else if ("EH약물".equals(fontRole)) {
            lexChemical(content, out);
        } else if ("EH루트".equals(fontRole) || "EH선모음".equals(fontRole)) {
            out.add(EHLexeme.skip()); // 시각 장식
        } else if ("EH수식".equals(fontRole) && content.indexOf('`') >= 0) {
            // EH수식 폰트에서 백틱(`)은 "앞 문자를 위첨자로" 만드는 마커다(실측:
            // 1단원 p14 (1/2)² 의 "2`" → ^{2}). lexBody 는 백틱을 그냥 제거해 지수를
            // 잃으므로, EH수식 + 백틱 조합만 별도로 위첨자 렉싱한다.
            lexMathExponent(content, out);
        } else {
            // 비EH 본문 런 또는 백틱 없는 EH수식: GREP 분수·자리구분·일반 텍스트.
            lexBody(content, out);
        }
    }

    /** run 의 EH 폰트 역할 판정 (CharacterStyle 이름 우선, fontFamily 폴백). */
    private static String fontRole(IDMLCharacterRun run) {
        String cs = run.appliedCharacterStyle();
        if (cs != null && EHFontGlyphMap.isEHFontStyle(cs)) {
            return EHFontGlyphMap.extractFontFromStyle(cs);
        }
        String ff = run.fontFamily();
        if (ff != null && EHFontGlyphMap.isEHFontFamily(ff)) {
            // "EH분수대문자" 등 접두 그대로
            if (ff.startsWith("EH분수대문자")) return "EH분수대문자";
            if (ff.startsWith("EH분수소문자")) return "EH분수소문자";
            if (ff.startsWith("EH고딕상부자")) return "EH고딕상부자";
            if (ff.startsWith("EH상부자")) return "EH상부자";
            if (ff.startsWith("EH고딕하부자")) return "EH고딕하부자";
            if (ff.startsWith("EH하부자")) return "EH하부자";
            if (ff.startsWith("EH약물")) return "EH약물";
            if (ff.startsWith("EH루트")) return "EH루트";
            if (ff.startsWith("EH선모음")) return "EH선모음";
            if (ff.startsWith("EH수식")) return "EH수식";
        }
        return null; // 비EH 본문
    }

    /** 분수대문자 폰트 런: hook / width-selector / digit-sep / GREP 분수 / atom. */
    private static void lexFractionUpper(String content, IDMLCharacterRun run, List<EHLexeme> out) {
        // GREP 분수가 이 런에 섞여 있으면(드뭄) body 규칙과 공유.
        int i = 0;
        // 폭 선택자가 hook 과 별개 런으로 오는 경우(실측: 1단원 p16 √121 의 hook 런
        // 다음 0xB6 단독 런, p17 "Å·"Ã): 직전 lexeme 이 HOOK 이고 이 런 첫 글자가
        // 0x80+ 글리프면 WIDTH_SELECTOR lexeme 으로(원본 코드포인트 보존) — in-run
        // 규칙(hook 직후 0x80+ 1개)과 동일 판정. 단 같은 코드포인트가 √u·√l 처럼 진짜
        // 변수 radicand 이기도 하므로, 폭선택자인지 변수인지는 "뒤에 radicand 가 더
        // 오는가"로 Parser 가 판정한다(뒤 있으면 폭선택자 스킵, 없으면 디코딩해 변수로).
        if (i < content.length() && !out.isEmpty()
                && out.get(out.size() - 1).kind() == EHLexeme.Kind.HOOK) {
            char c0 = content.charAt(i);
            if (c0 >= 0x80 && !isDigitSep(c0) && !isHookGlyph(c0) && !isSepChar(c0)) {
                out.add(EHLexeme.widthSelector(c0));
                i++;
            }
        }
        while (i < content.length()) {
            char c = content.charAt(i);
            if (isHookGlyph(c)) {
                out.add(EHLexeme.hook(c));
                i++;
                // hook 직후 0x80+ 1개는 폭선택자 후보 — rawCodepoint 보존해 Parser 가 판정.
                if (i < content.length()) {
                    char n = content.charAt(i);
                    if (n >= 0x80 && !isDigitSep(n) && !isHookGlyph(n)) {
                        out.add(EHLexeme.widthSelector(n));
                        i++;
                    }
                }
                continue;
            }
            if (isDigitSep(c)) {
                out.add(EHLexeme.digitSep(c));
                i++;
                continue;
            }
            // GREP 분수 시작?
            if (c == ';' && EHFontGlyphMap.containsEHFractionPattern(content.substring(i))) {
                i = emitGrepFraction(content, i, out);
                continue;
            }
            if (c == ' ' || c == ' ') { // 분수대문자 폰트 공백
                out.add(EHLexeme.sep(" "));
                i++;
                continue;
            }
            // 그 외: 디코딩된 텍스트 (분수대문자는 대부분 라틴 그대로 radicand).
            String dec = EHFontGlyphMap.decodeText(String.valueOf(c), "EH분수대문자");
            emitAtom(dec, out);
            i++;
        }
    }

    /** 비EH 본문 런: GREP 분수, 자리구분 없음(본문엔 0x8C 안 옴), 일반 텍스트. */
    private static void lexBody(String content, List<EHLexeme> out) {
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == ';' && EHFontGlyphMap.containsEHFractionPattern(content.substring(i))) {
                i = emitGrepFraction(content, i, out);
                continue;
            }
            if (isDigitSep(c)) { // 본문에 섞인 자리구분(폰트 상속 꼬임)
                out.add(EHLexeme.digitSep(c));
                i++;
                continue;
            }
            if (isSepChar(c)) {
                out.add(EHLexeme.sep(String.valueOf(c)));
                i++;
                continue;
            }
            if (c == '`') { // thin space / 위첨자 닫기 마커 — 제거
                i++;
                continue;
            }
            // 나머지는 연속 텍스트로 모아 한 ATOM 으로 (연산자/숫자/변수/괄호/한글).
            int j = i;
            StringBuilder sb = new StringBuilder();
            while (j < content.length()) {
                char d = content.charAt(j);
                if (d == ';' && EHFontGlyphMap.containsEHFractionPattern(content.substring(j))) break;
                if (isDigitSep(d) || isSepChar(d) || d == '`') break;
                sb.append(d);
                j++;
            }
            if (sb.length() > 0) emitAtom(sb.toString(), out);
            i = j;
        }
    }

    /** ;분자분모; GREP 분수 → FRACTION lexeme. 반환: 소비 후 인덱스. */
    private static int emitGrepFraction(String content, int start, List<EHLexeme> out) {
        int first = content.indexOf(';', start);
        int second = content.indexOf(';', first + 1);
        if (first < 0 || second < 0) { emitAtom(content.substring(start), out); return content.length(); }
        String inner = content.substring(first + 1, second);
        String[] frac = EHFontGlyphMap.decodeFractionInner(inner);
        if (frac != null && (!frac[0].isEmpty() || !frac[1].isEmpty())) {
            out.add(EHLexeme.fraction(frac[0], frac[1]));
        } else {
            emitAtom(";" + inner + ";", out);
        }
        return second + 1;
    }

    /**
     * 상부자/하부자 런 → SUPERSCRIPT/SUBSCRIPT/ATOM.
     *
     * <p>중요: 상부자 폰트라도 <b>기본범위(0x20-0x7F, 일반 숫자·문자)</b> 글리프는
     * 위첨자가 아니라 "작은 크기로 조판된 radicand/본문"이다(실측: √80 의 8·0 이
     * EH상부자 폰트). 위첨자 위치가 아니므로 ATOM_TEXT 로 내보내 Parser 가 radicand
     * 로 흡수하게 한다. 진짜 지수는 <b>확장범위(0x80+ → ²³ 등)</b> 또는 백틱(`)으로
     * 닫히는 글리프다. 백틱 종료가 있으면 위첨자 위치가 확실.
     */
    private static void lexSubSup(String content, boolean isSuper, List<EHLexeme> out) {
        boolean hasBacktick = content.indexOf('`') >= 0;   // 위첨자 닫기 마커 존재
        boolean hasExtended = false;                        // 0x80+ 위첨자 글리프 존재
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '`') continue;
            char decoded = EHFontGlyphMap.decodeSubSupGlyph(c);
            if (c >= 0x80) {
                if (decoded == c) continue; // 미매핑 확장문자 스킵
                hasExtended = true;
            }
            buf.append(decoded);
        }
        String s = buf.toString();
        if (s.isEmpty()) return;
        // 연산자(÷×±°)는 항상 ATOM.
        if (s.length() == 1 && "÷×±°".indexOf(s.charAt(0)) >= 0) {
            emitAtom(s, out);
            return;
        }
        // 위첨자 위치 확실(확장범위 글리프 또는 백틱 종료) → 진짜 첨자.
        // 그 외(기본범위 숫자·문자, 백틱 없음) → 작은 크기 radicand/본문 = ATOM.
        boolean isTrueScript = hasExtended || hasBacktick;
        if (!isTrueScript) {
            emitAtom(s, out);
        } else if (isSuper) {
            out.add(EHLexeme.superscript(s));
        } else {
            out.add(EHLexeme.subscript(s));
        }
    }

    /**
     * EH수식 폰트 + 백틱 런: 백틱 앞 문자를 위첨자로.
     *
     * <p>백틱(`)은 EH수식에서 "직전 글자를 위첨자로 올리는" 마커다. "2`" → ^{2},
     * "x2`+1" → x^{2}+1 처럼, 백틱 바로 앞 1글자(연속 숫자면 그 숫자 묶음)를
     * SUPERSCRIPT 로, 나머지는 ATOM 으로 낸다. GREP 분수·자리구분은 lexBody 와 동일.
     */
    private static void lexMathExponent(String content, List<EHLexeme> out) {
        StringBuilder base = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '`') {
                // base 끝의 지수 대상(연속 숫자/문자 1묶음)을 위첨자로 분리.
                int k = base.length();
                while (k > 0 && (Character.isLetterOrDigit(base.charAt(k - 1)))) k--;
                // 지수는 백틱 직전 "마지막 토큰"이 아니라 통상 마지막 1~2 글자다.
                // 실측상 지수는 숫자 묶음(2, 10)·단일 문자. base 전체가 지수가 되면
                // 곤란하므로, 앞에 다른 내용이 있으면 그 경계까지만 남기고 뒤를 지수로.
                String exp;
                if (k < base.length()) {
                    exp = base.substring(k);
                    base.setLength(k);
                } else {
                    // 백틱 앞이 비영숫자거나 base 가 비면 지수 없음 — 백틱만 제거.
                    continue;
                }
                if (base.length() > 0) { emitAtom(base.toString(), out); base.setLength(0); }
                out.add(EHLexeme.superscript(exp));
                continue;
            }
            base.append(c);
        }
        if (base.length() > 0) emitAtom(base.toString(), out);
    }

    /** 약물 런: p→π, H→순환마디점(DOT), 그 외 공통기호 디코딩. */
    private static void lexChemical(String content, List<EHLexeme> out) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == 'p') { emitAtom("π", out); continue; }
            if (c == 'H') { out.add(EHLexeme.dot()); continue; }
            if (c == '`') continue;
            String dec = EHFontGlyphMap.decodeChemicalGlyphText(String.valueOf(c));
            if (dec != null && !dec.isEmpty()) emitAtom(dec, out);
        }
    }

    /** 인라인 그래픽 → BOX(ANSWER: 정사각) / BOX(VINCULUM: 가로막대). */
    private static void emitInlineGraphics(IDMLCharacterRun run, List<EHLexeme> out) {
        for (IDMLCharacterRun.InlineGraphic g : run.inlineGraphics()) {
            if (g == null) continue;
            if (!"rectangle".equalsIgnoreCase(g.type())) { out.add(EHLexeme.skip()); continue; }
            double w = g.widthPoints(), h = g.heightPoints();
            if (w <= 0 || h <= 0) { out.add(EHLexeme.skip()); continue; }
            if (w >= h * 2.0) {
                out.add(EHLexeme.box(EHNode.Box.Kind.VINCULUM)); // 가로막대 스페이서
            } else {
                out.add(EHLexeme.box(EHNode.Box.Kind.ANSWER)); // 정사각 답란
            }
        }
    }

    private static boolean isSepChar(char c) {
        if (c == '\t' || c == '\n' || c == '\r') return true;
        if (c == ' ' || c == ' ' || c == ' ' || c == ' ') return true; // thin/em space
        if (c >= 0x2460 && c <= 0x2473) return true; // ①-⑳
        if (c >= 0x2474 && c <= 0x2487) return true; // ⑴-⒇
        return false;
    }

    /** ATOM_TEXT emit — 빈 문자열/FFFC 는 무시, 백틱 제거. */
    private static void emitAtom(String s, List<EHLexeme> out) {
        if (s == null) return;
        s = s.replace("￼", "").replace("`", "");
        // EH분수대문자의 { } 는 큰 소괄호 글리프다(실측: 1단원 p14 (1/2)² 의 여는·닫는
        // 소괄호). HWP 수식 스크립트의 그룹핑 중괄호와 충돌해 화면에서 유실되므로 소괄호
        // 문자로 치환한다. 단, StoryLoader 가 주입하는 box{~} 명령 문자열은 예외 —
        // 이건 괄호 글리프가 아니라 HWP 수식 명령이라 중괄호를 보존해야 한다.
        if (!s.contains("box{~}")) {
            s = s.replace('{', '(').replace('}', ')');
        }
        if (s.isEmpty()) return;
        out.add(EHLexeme.atomText(s));
    }
}
