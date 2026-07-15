package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 런 후처리 — overline 마킹, italic→ASTEquation 변환.
 * StoryConverter에서 분리됨 (W3 Step A).
 *
 * 모든 메서드는 ctx 없이 동작 (단락/런 단위 변환만).
 */
class RunPostProcessor {

    private RunPostProcessor() {}

    /**
     * Ó(U+00D3) → 대문자 시퀀스를 PUA 마커로 감싸 표시.
     * 예: "ABÓ=APÓ" → "\uE000AB\uE001=\uE000AP\uE001"
     */
    static String markOverlineSegments(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00D3') {
                // Ó: 앞으로 거슬러 올라가며 연속 대문자를 찾아 마커로 감싸기
                int endPos = sb.length();
                int startPos = endPos;
                while (startPos > 0 && sb.charAt(startPos - 1) >= 'A' && sb.charAt(startPos - 1) <= 'Z') {
                    startPos--;
                }
                if (startPos < endPos) {
                    String letters = sb.substring(startPos, endPos);
                    sb.replace(startPos, endPos, "\uE000" + letters + "\uE001");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * \uE000...\uE001 overline 마커가 포함된 TextRun을 분리하여 ASTEquation(overline{AB})으로 변환.
     */
    static void splitOverlineRuns(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;
        boolean hasMarker = false;
        for (ASTInlineItem it : items) {
            if (it instanceof ASTTextRun) {
                String t = ((ASTTextRun) it).text();
                if (t != null && t.indexOf('\uE000') >= 0) { hasMarker = true; break; }
            }
        }
        if (!hasMarker) return;

        List<ASTInlineItem> newItems = new ArrayList<>();
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTTextRun)) {
                newItems.add(item);
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.indexOf('\uE000') < 0) {
                newItems.add(item);
                continue;
            }
            // \uE000...\uE001 패턴으로 분할
            int pos = 0;
            while (pos < text.length()) {
                int markerStart = text.indexOf('\uE000', pos);
                if (markerStart < 0) {
                    // 나머지 텍스트
                    String rest = text.substring(pos);
                    if (!rest.isEmpty()) {
                        ASTTextRun tr = new ASTTextRun();
                        tr.text(rest);
                        tr.fontFamily(run.fontFamily());
                        tr.fontStyle(run.fontStyle());
                        tr.fontSizeHwpunits(run.fontSizeHwpunits());
                        tr.textColor(run.textColor());
                        tr.shadeColor(run.shadeColor());
                        newItems.add(tr);
                    }
                    break;
                }
                // 마커 앞 텍스트
                if (markerStart > pos) {
                    ASTTextRun tr = new ASTTextRun();
                    tr.text(text.substring(pos, markerStart));
                    tr.fontFamily(run.fontFamily());
                    tr.fontStyle(run.fontStyle());
                    tr.fontSizeHwpunits(run.fontSizeHwpunits());
                    tr.textColor(run.textColor());
                    tr.shadeColor(run.shadeColor());
                    newItems.add(tr);
                }
                int markerEnd = text.indexOf('\uE001', markerStart + 1);
                if (markerEnd < 0) markerEnd = text.length();
                String letters = text.substring(markerStart + 1, markerEnd);
                // ASTEquation: overline{AB}
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(
                                "overline{" + letters + "}", "EH_FONT");
                newItems.add(eq);
                pos = markerEnd + 1;
            }
        }
        items.clear();
        items.addAll(newItems);
    }

    /**
     * 이탤릭 TextRun을 인라인 수식(ASTEquation)으로 변환.
     * 한컴 수식 에디터가 변수=이탤릭, 괄호/연산자=정체를 자동 처리.
     * 연속된 이탤릭 수학 런은 하나의 수식으로 합쳐서 변환.
     */
    static void convertItalicRunsToEquations(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        boolean hasTarget = false;
        for (ASTInlineItem it : items) {
            if (it instanceof ASTTextRun
                    && !isSimplePositionedTextRun((ASTTextRun) it)
                    && isItalicMathRun((ASTTextRun) it)) {
                hasTarget = true;
                break;
            }
        }
        if (!hasTarget) return;

        List<ASTInlineItem> newItems = new ArrayList<>();
        StringBuilder mathBuf = new StringBuilder();
        List<IDMLCharacterRun> mathRuns = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun && isSimplePositionedTextRun((ASTTextRun) item)) {
                flushItalicMathBuf(mathBuf, mathRuns, newItems);
                newItems.add(item);
            } else if (item instanceof ASTTextRun && isItalicMathRun((ASTTextRun) item)) {
                ASTTextRun tr = (ASTTextRun) item;
                mathBuf.append(tr.text());
                IDMLCharacterRun cr = new IDMLCharacterRun();
                cr.content(tr.text());
                cr.fontFamily(tr.fontFamily());
                mathRuns.add(cr);
            } else if (item instanceof ASTTextRun && mathBuf.length() > 0) {
                // 수식 버퍼가 열려있을 때 backtick/thin space 등 짧은 비수식 런은 흡수
                String t = ((ASTTextRun) item).text();
                if (t != null && t.length() <= 2 && !t.isEmpty()) {
                    boolean allSkippable = true;
                    for (int ci = 0; ci < t.length(); ci++) {
                        char c = t.charAt(ci);
                        if (c != '`' && c != ' ' && c != '\u2009' && c != '\u2005') {
                            allSkippable = false; break;
                        }
                    }
                    if (allSkippable) continue; // backtick/space 스킵
                }
                // 수학 버퍼 flush + 일반 텍스트 추가
                flushItalicMathBuf(mathBuf, mathRuns, newItems);
                newItems.add(item);
            } else if (item instanceof ASTEquation) {
                // 이미 완성된 ASTEquation. mathBuf(텍스트 이탤릭 버퍼)만 EH 변환하는
                // flushItalicMathBuf 는 이 스크립트를 반영하지 못한다(실측: 3단원
                // y=-x²/5 에서 x²/5 앵커 수식이 y=- 이탤릭 버퍼에 흡수돼 유실). 앞
                // 버퍼를 먼저 flush 하고, 이 수식은 스크립트를 이어붙여 그대로 유지한다.
                ASTEquation existingEq = (ASTEquation) item;
                if (mathBuf.length() > 0) {
                    // 앞 이탤릭 버퍼 + 이 수식 스크립트를 하나로 이어붙인다.
                    flushItalicMathBufWithSuffix(mathBuf, mathRuns, newItems, existingEq.hwpScript());
                } else {
                    newItems.add(existingEq);
                }
            } else {
                // 수학 버퍼 flush
                flushItalicMathBuf(mathBuf, mathRuns, newItems);
                newItems.add(item);
            }
        }
        // 마지막 버퍼 flush
        flushItalicMathBuf(mathBuf, mathRuns, newItems);

        items.clear();
        items.addAll(newItems);
    }

    private static boolean isSimplePositionedTextRun(ASTTextRun tr) {
        if (tr == null || (!tr.subscript() && !tr.superscript())) return false;
        String text = tr.text();
        if (text == null) return false;
        String cleaned = text.trim();
        if (cleaned.isEmpty() || cleaned.length() > 4) return false;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '-') continue;
            return false;
        }
        return true;
    }

    /**
     * 이탤릭 수학 버퍼를 ASTEquation으로 변환.
     * EH 폰트 런이 있으면 EHFontEquationConverter로 파이프라인 처리,
     * 없으면 raw 텍스트에서 backtick 제거 후 수식으로 사용.
     */
    /**
     * 이탤릭 버퍼를 수식으로 변환한 스크립트에 이미 완성된 수식 스크립트(suffix)를
     * 이어붙여 하나의 ASTEquation 으로 만든다. 앵커로 배치된 분수 수식(x²/5)이 앞
     * 이탤릭(y=-)에 이어져야 y=-x²/5 로 온전히 렌더된다.
     */
    private static void flushItalicMathBufWithSuffix(StringBuilder mathBuf, List<IDMLCharacterRun> mathRuns,
                                                     List<ASTInlineItem> out, String suffixScript) {
        List<ASTInlineItem> tmp = new ArrayList<>();
        flushItalicMathBuf(mathBuf, mathRuns, tmp);
        String prefix = "";
        for (ASTInlineItem it : tmp) {
            if (it instanceof ASTEquation) {
                prefix += ((ASTEquation) it).hwpScript();
            } else {
                out.add(it); // 수식이 아닌 잔여물은 그대로
            }
        }
        String merged = prefix + (suffixScript != null ? suffixScript : "");
        if (!merged.isEmpty()) {
            out.add(new ASTEquation(merged, "EH_FONT"));
        }
    }

    private static void flushItalicMathBuf(StringBuilder mathBuf, List<IDMLCharacterRun> mathRuns,
                                            List<ASTInlineItem> out) {
        if (mathBuf.length() == 0) return;

        // EH 폰트 런이 있으면 EH 변환 파이프라인 사용
        boolean hasEH = false;
        for (IDMLCharacterRun cr : mathRuns) {
            if (cr.fontFamily() != null && EHFontGlyphMap.isEHFontFamily(cr.fontFamily())) {
                hasEH = true;
                break;
            }
        }
        String script;
        if (hasEH && mathRuns.size() > 0) {
            script = EHFontEquationConverter.convert(mathRuns);
        } else {
            script = null;
        }
        if (script == null || script.isEmpty()) {
            // EH 변환 실패 → raw 텍스트에서 backtick 제거
            script = mathBuf.toString().replace("`", "");
        }
        if (!script.isEmpty()) {
            out.add(new ASTEquation(script, "EH_FONT"));
        }
        mathBuf.setLength(0);
        mathRuns.clear();
    }

    /** 이탤릭 수학 런 판별: 수식 전용 폰트/기호/짧은 변수 런만 수식으로 승격한다. */
    private static boolean isItalicMathRun(ASTTextRun tr) {
        String text = tr.text();
        if (text == null || text.isEmpty()) return false;
        // 공백만인 run은 EH 폰트여도 수식 내용이 아니다(구분자). 수식 버퍼에 넣으면
        // 앞 항과 합쳐질 때 선행 공백이 trim 돼 사라진다(실측: 3단원 ⑵ 뒤 EH상부자
        // 공백이 y=- 수식에 흡수돼 ⑵와 y= 가 붙음).
        if (text.trim().isEmpty()) return false;
        // 한국어가 포함되면 수식이 아님
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
        }
        // Italic fontStyle
        String fs = tr.fontStyle();
        boolean isItalic = fs != null && fs.toLowerCase().contains("italic");
        // EH 수식 폰트 (이탤릭 여부와 무관하게 수식으로 변환)
        String ff = tr.fontFamily();
        boolean isEHFont = ff != null && EHFontGlyphMap.isEHFontFamily(ff);
        if (!isItalic && !isEHFont) return false;

        if (hasLongLatinWord(text, 3) && !hasMathSyntax(text)
                && !EHFontGlyphMap.containsEHEncodedChars(text)
                && !EHFontGlyphMap.containsEHFractionPattern(text)) {
            return false;
        }

        if (isEHFont) {
            return true;
        }
        if (hasMathSyntax(text)) {
            return true;
        }

        // 단일 문자: 알파벳이면 수식 변수
        char c0 = text.charAt(0);
        if (text.length() == 1) {
            return (c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z');
        }
        return isShortLatinVariableCluster(text);
    }

    private static boolean hasMathSyntax(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("+=<>≤≥±×÷√²³^_π∑∫∞{}[]()/".indexOf(c) >= 0) {
                return true;
            }
            if (c == '\uE000' || c == '\uE001') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLongLatinWord(String text, int minLen) {
        int streak = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c < 0x100) {
                streak++;
                if (streak >= minLen) return true;
            } else {
                streak = 0;
            }
        }
        return false;
    }

    private static boolean isShortLatinVariableCluster(String text) {
        String compact = text.replace("`", "").replace(" ", "")
                .replace("\u2009", "").replace("\u2005", "");
        if (compact.isEmpty() || compact.length() > 4) return false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || Character.isDigit(c))) {
                return false;
            }
        }
        return true;
    }
}
