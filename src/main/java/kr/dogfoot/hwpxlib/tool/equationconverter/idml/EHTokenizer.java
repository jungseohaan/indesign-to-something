package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1: EH 폰트 런 → EHToken 리스트.
 * 글리프 디코딩 + 역할 분류만 수행, 구조 결정은 하지 않음.
 */
public class EHTokenizer {

    /**
     * IDMLCharacterRun 리스트를 EHToken 리스트로 변환.
     */
    public static List<EHToken> tokenize(List<IDMLCharacterRun> runs) {
        List<EHToken> tokens = new ArrayList<>();
        if (runs == null || runs.isEmpty()) return tokens;

        // 전방 탐색: EH분수소문자 존재 여부 (전역이 아닌 개별 판단은 IR 빌더에서 처리)
        for (int ri = 0; ri < runs.size(); ri++) {
            IDMLCharacterRun run = runs.get(ri);
            String text = run.content();
            if (text == null || text.isEmpty()) continue;

            String fontFamily = run.fontFamily();

            // 루트/선모음 → SKIP
            if (EHFontGlyphMap.isRootFont(fontFamily)
                    || EHFontGlyphMap.isLineFont(fontFamily)) {
                tokens.add(new EHToken(EHToken.Type.SKIP, text));
                continue;
            }

            // EH상부자/하부자
            if (EHFontGlyphMap.isSuperscriptFont(fontFamily)
                    || EHFontGlyphMap.isSubscriptFont(fontFamily)) {
                boolean isSuper = EHFontGlyphMap.isSuperscriptFont(fontFamily);
                tokenizeSubSupRun(text, isSuper, tokens);
                continue;
            }

            // EH분수대문자
            if (EHFontGlyphMap.isFractionNumeratorFont(fontFamily)) {
                // 개별 판단: 이 분수대문자 뒤에 분수소문자가 오는지
                boolean followedByDenom = false;
                for (int fj = ri + 1; fj < runs.size(); fj++) {
                    String fjFont = runs.get(fj).fontFamily();
                    if (EHFontGlyphMap.isFractionDenominatorFont(fjFont)) {
                        followedByDenom = true;
                        break;
                    }
                    if (!EHFontGlyphMap.isFractionNumeratorFont(fjFont)) break;
                }
                if (followedByDenom) {
                    String decoded = EHFontGlyphMap.decodeText(text, fontFamily);
                    tokens.add(new EHToken(EHToken.Type.FRACTION_NUMERATOR, decoded));
                } else {
                    tokens.add(new EHToken(EHToken.Type.SQRT_MARKER, text));
                }
                continue;
            }

            // EH분수소문자
            if (EHFontGlyphMap.isFractionDenominatorFont(fontFamily)) {
                String decoded = EHFontGlyphMap.decodeText(text, fontFamily);
                tokens.add(new EHToken(EHToken.Type.FRACTION_DENOMINATOR, decoded));
                continue;
            }

            // EH수식/EH약물
            if (run.isEHFont()) {
                tokenizeBaseEH(text, fontFamily, tokens);
                continue;
            }

            // 비EH 런: EH 인코딩 패턴 확인
            if (EHFontGlyphMap.containsEHEncodedChars(text)) {
                // EH 인코딩 패턴 포함 → 상부자로 처리
                tokenizeSubSupRun(text, true, tokens);
                continue;
            }

            if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                // ;...; 분수 GREP 패턴 포함
                tokenizeGrepFractions(text, tokens);
                continue;
            }

            // 순수 비EH 브릿지 런
            tokens.add(new EHToken(EHToken.Type.BASE_TEXT, text));
        }

        return tokens;
    }

    /**
     * EH상부자/하부자 텍스트를 토큰화.
     * 확장 범위(0x80+) → SUPERSCRIPT_GLYPH/SUBSCRIPT_GLYPH
     * 기본 범위(0x20-0x7F) → SUP_BASE_TEXT/SUB_BASE_TEXT
     * { → (, } → ) 치환 (hwpScript 충돌 방지)
     */
    private static void tokenizeSubSupRun(String text, boolean isSuper, List<EHToken> tokens) {
        EHToken.Type glyphType = isSuper ? EHToken.Type.SUPERSCRIPT_GLYPH : EHToken.Type.SUBSCRIPT_GLYPH;
        EHToken.Type baseType = isSuper ? EHToken.Type.SUP_BASE_TEXT : EHToken.Type.SUB_BASE_TEXT;

        StringBuilder extBuf = new StringBuilder();
        StringBuilder baseBuf = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 백틱(0x60) = 불가시 여백 → 스킵
            if (c == 0x60) continue;

            // { → (, } → )
            if (c == '{') c = '(';
            else if (c == '}') c = ')';

            if (c >= 0x80) {
                // 확장 범위 → 기본 범위 버퍼 플러시
                flushBaseBuf(baseBuf, baseType, tokens);
                char decoded = EHFontGlyphMap.decodeSubSupGlyph(c);
                if (decoded != c) {
                    extBuf.append(decoded);
                }
            } else {
                // 기본 범위 → 확장 범위 버퍼 플러시
                flushExtBuf(extBuf, glyphType, tokens);
                baseBuf.append(c);
            }
        }

        // 남은 버퍼 플러시
        flushExtBuf(extBuf, glyphType, tokens);
        flushBaseBuf(baseBuf, baseType, tokens);
    }

    /**
     * EH수식/EH약물 텍스트를 토큰화.
     * 백틱(`) = 앞 문자를 위첨자로 만드는 마커.
     * EH약물의 특수 글리프(Ñ→±)는 직접 변환.
     */
    private static void tokenizeBaseEH(String text, String fontFamily, List<EHToken> tokens) {
        // EH약물 전용 글리프 전처리: decodeText가 잘못 매핑하는 글리프를 직접 처리
        if (EHFontGlyphMap.isChemicalFont(fontFamily) && text.indexOf('\u00D1') >= 0) {
            // Ñ(0xD1) = ± 기호. decodeText는 이를 'e'/'+'로 잘못 매핑하므로 직접 분리
            StringBuilder buf = new StringBuilder();
            for (int ci = 0; ci < text.length(); ci++) {
                char ch = text.charAt(ci);
                if (ch == '\u00D1') {
                    if (buf.length() > 0) {
                        String decoded = EHFontGlyphMap.decodeText(buf.toString(), fontFamily);
                        if (decoded != null && !decoded.isEmpty()) {
                            tokens.add(new EHToken(EHToken.Type.BASE_TEXT, decoded));
                        }
                        buf.setLength(0);
                    }
                    tokens.add(new EHToken(EHToken.Type.BASE_TEXT, "\u00B1")); // ±
                } else {
                    buf.append(ch);
                }
            }
            if (buf.length() > 0) {
                String decoded = EHFontGlyphMap.decodeText(buf.toString(), fontFamily);
                if (decoded != null && !decoded.isEmpty()) {
                    tokens.add(new EHToken(EHToken.Type.BASE_TEXT, decoded));
                }
            }
            return;
        }
        if (text.contains("`")) {
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '`' && buf.length() > 0) {
                    // 백틱 앞 문자를 위첨자로
                    char prev = buf.charAt(buf.length() - 1);
                    buf.deleteCharAt(buf.length() - 1);
                    if (buf.length() > 0) {
                        String decoded = EHFontGlyphMap.decodeText(buf.toString(), fontFamily);
                        if (decoded != null && !decoded.isEmpty()) {
                            tokens.add(new EHToken(EHToken.Type.BASE_TEXT, decoded));
                        }
                        buf.setLength(0);
                    }
                    // 위첨자 대상 문자를 디코딩
                    String decodedChar = EHFontGlyphMap.decodeText(String.valueOf(prev), fontFamily);
                    tokens.add(new EHToken(EHToken.Type.BACKTICK_SUPER,
                            decodedChar != null ? decodedChar : String.valueOf(prev)));
                } else {
                    buf.append(c);
                }
            }
            if (buf.length() > 0) {
                String decoded = EHFontGlyphMap.decodeText(buf.toString(), fontFamily);
                if (decoded != null && !decoded.isEmpty()) {
                    tokens.add(new EHToken(EHToken.Type.BASE_TEXT, decoded));
                }
            }
        } else {
            String decoded = EHFontGlyphMap.decodeText(text, fontFamily);
            if (decoded != null && !decoded.isEmpty()) {
                tokens.add(new EHToken(EHToken.Type.BASE_TEXT, decoded));
            }
        }
    }

    /**
     * ;...; GREP 분수 패턴을 토큰화.
     * 패턴 외부 텍스트는 BASE_TEXT로, 패턴은 GREP_FRACTION으로.
     */
    private static void tokenizeGrepFractions(String text, List<EHToken> tokens) {
        // { → (, } → )
        text = text.replace('{', '(').replace('}', ')');
        int i = 0;
        while (i < text.length()) {
            int semiStart = text.indexOf(';', i);
            if (semiStart < 0) {
                String rest = text.substring(i);
                if (!rest.isEmpty()) tokens.add(new EHToken(EHToken.Type.BASE_TEXT, rest));
                break;
            }
            if (semiStart > i) {
                tokens.add(new EHToken(EHToken.Type.BASE_TEXT, text.substring(i, semiStart)));
            }
            int semiEnd = text.indexOf(';', semiStart + 1);
            if (semiEnd < 0) {
                tokens.add(new EHToken(EHToken.Type.BASE_TEXT, text.substring(semiStart)));
                break;
            }
            String inner = text.substring(semiStart + 1, semiEnd);
            String[] fracParts = EHFontGlyphMap.decodeFractionInner(inner);
            if (fracParts != null && (fracParts[0].length() > 0 || fracParts[1].length() > 0)) {
                tokens.add(new EHToken(EHToken.Type.GREP_FRACTION, inner,
                        fracParts[0], fracParts[1]));
            } else {
                tokens.add(new EHToken(EHToken.Type.BASE_TEXT, ";" + inner + ";"));
            }
            i = semiEnd + 1;
        }
    }

    private static void flushExtBuf(StringBuilder buf, EHToken.Type type, List<EHToken> tokens) {
        if (buf.length() > 0) {
            tokens.add(new EHToken(type, buf.toString()));
            buf.setLength(0);
        }
    }

    private static void flushBaseBuf(StringBuilder buf, EHToken.Type type, List<EHToken> tokens) {
        if (buf.length() > 0) {
            String text = buf.toString();
            buf.setLength(0);
            // 기본 범위 텍스트에 ;...; 분수 패턴이 있으면 분수로 변환
            if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                tokenizeGrepFractions(text, tokens);
            } else {
                tokens.add(new EHToken(type, text));
            }
        }
    }
}
