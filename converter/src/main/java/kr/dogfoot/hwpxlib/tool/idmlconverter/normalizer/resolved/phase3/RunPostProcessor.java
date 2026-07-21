package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier;
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
                if (t != null && (t.indexOf('\uE000') >= 0 || t.indexOf('\uE002') >= 0)) {
                    hasMarker = true; break;
                }
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
            // SPEC-052: 경계 마커(U+E002) = "앞 런 끝 대문자에 overline". InDesign DOM 이
            // AM|Ó|BM|Ó 로 overline 마커를 별도 런으로 분리한 경우(u5 p168). 직전에 추가된
            // TextRun 의 끝 연속 대문자를 떼어 overline{...} 수식으로 바꾸고, 마커는 버린다.
            if (text != null && text.indexOf('\uE002') >= 0) {
                wrapPreviousTrailingCapitalsAsOverline(newItems);
                String residual = text.replace(String.valueOf('\uE002'), "");
                if (!residual.isEmpty()) {
                    ASTTextRun tr = new ASTTextRun();
                    tr.text(residual);
                    tr.fontFamily(run.fontFamily());
                    tr.fontStyle(run.fontStyle());
                    tr.fontSizeHwpunits(run.fontSizeHwpunits());
                    tr.textColor(run.textColor());
                    tr.shadeColor(run.shadeColor());
                    newItems.add(tr);
                }
                continue;
            }
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
        mergeTriangleGeometryLabels(items);
    }

    /**
     * A triangle marker is explicit source math structure.  Normalize the immediately following
     * uppercase label as one equation even if an earlier generic parser split it into text A and
     * equation BC.  This is structure-based and applies to every source-authored △ABC label.
     */
    private static void mergeTriangleGeometryLabels(List<ASTInlineItem> items) {
        if (items == null || items.size() < 2) return;
        List<ASTInlineItem> merged = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            merged.add(item);
            if (!(item instanceof ASTTextRun)
                    || !((ASTTextRun) item).text().endsWith("△")) continue;

            int cursor = i + 1;
            StringBuilder label = new StringBuilder();
            ASTEquation styleSource = null;
            while (cursor < items.size()) {
                ASTInlineItem candidate = items.get(cursor);
                String part = uppercaseLabelPart(candidate);
                if (part == null) break;
                label.append(part);
                if (styleSource == null && candidate instanceof ASTEquation) {
                    styleSource = (ASTEquation) candidate;
                }
                cursor++;
            }
            if (label.length() < 2 || cursor == i + 1) continue;

            ASTEquation equation = new ASTEquation(label.toString(), "EH_FONT");
            if (styleSource != null) {
                equation.preferredBaseUnit(styleSource.preferredBaseUnit());
                equation.preferredFontFamily(styleSource.preferredFontFamily());
                equation.textColor(styleSource.textColor());
            }
            merged.add(equation);
            i = cursor - 1;
        }
        items.clear();
        items.addAll(merged);
    }

    private static String uppercaseLabelPart(ASTInlineItem item) {
        String value;
        if (item instanceof ASTTextRun) value = ((ASTTextRun) item).text();
        else if (item instanceof ASTEquation) value = ((ASTEquation) item).hwpScript();
        else return null;
        return isAllAsciiUppercase(value) ? value : null;
    }

    /**
     * SPEC-052: 경계 마커()를 만났을 때, newItems 의 마지막 ASTTextRun 끝
     * 연속 대문자를 떼어 overline{…} ASTEquation 으로 교체한다. 대문자 앞의
     * 숫자 계수(예: 2AM̅)는 overline 밖에 남긴다(2 overline{AM}).
     * 직전이 TextRun 이 아니거나 끝이 대문자가 아니면 아무것도 하지 않는다.
     */
    private static void wrapPreviousTrailingCapitalsAsOverline(List<ASTInlineItem> newItems) {
        if (newItems == null || newItems.isEmpty()) return;
        // 직전 런들이 낱글자(A|M 등)로 쪼개져 있을 수 있으므로, newItems 를 뒤에서부터
        // 훑어 연속된 영문자 런을 모두 모은다(실측: u5 p168 AM 이 A|M 두 런). 한 런
        // 안에서는 끝 연속 영문자만, 그 앞이 통째로 영문 런이면 계속 이어붙인다.
        StringBuilder lettersRev = new StringBuilder();
        int removeFrom = newItems.size(); // 이 인덱스부터 끝까지 제거
        String head = null;               // 영문 시작 런의 비영문 앞부분
        ASTTextRun anchorRun = null;      // 스타일 상속용(가장 뒤 런)
        for (int idx = newItems.size() - 1; idx >= 0; idx--) {
            ASTInlineItem it = newItems.get(idx);
            if (!(it instanceof ASTTextRun)) break;
            ASTTextRun tr = (ASTTextRun) it;
            String t = tr.text();
            if (t == null || t.isEmpty()) break;
            if (anchorRun == null) anchorRun = tr;
            // 이 런의 끝에서부터 연속 영문자 길이
            int end = t.length();
            int s = end;
            while (s > 0) {
                char c = t.charAt(s - 1);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) s--;
                else break;
            }
            if (s >= end) break; // 이 런 끝이 영문이 아니면 여기서 멈춤
            // 이 런의 영문 꼬리를 앞쪽에 이어붙인다(역순 수집이므로 prepend)
            lettersRev.insert(0, t, s, end);
            if (s > 0) {
                // 런 중간부터 영문 시작 → 여기가 경계. 앞부분은 head 로 보존하고 종료.
                head = t.substring(0, s);
                removeFrom = idx;
                break;
            }
            // 런 전체가 영문(A|M 처럼 낱글자 통째) → 이 런도 제거 대상, 더 앞으로.
            removeFrom = idx;
            // 계속 앞 런을 검사
        }
        String letters = lettersRev.toString();
        if (letters.isEmpty()) return;
        // removeFrom..끝 런 제거
        while (newItems.size() > removeFrom) {
            newItems.remove(newItems.size() - 1);
        }
        if (head != null && !head.isEmpty() && anchorRun != null) {
            ASTTextRun headRun = new ASTTextRun();
            headRun.text(head);
            headRun.fontFamily(anchorRun.fontFamily());
            headRun.fontStyle(anchorRun.fontStyle());
            headRun.fontSizeHwpunits(anchorRun.fontSizeHwpunits());
            headRun.textColor(anchorRun.textColor());
            headRun.shadeColor(anchorRun.shadeColor());
            newItems.add(headRun);
        }
        newItems.add(new ASTEquation("overline{" + letters + "}", "EH_FONT"));
    }

    /**
     * 이탤릭 TextRun을 인라인 수식(ASTEquation)으로 변환.
     * 한컴 수식 에디터가 변수=이탤릭, 괄호/연산자=정체를 자동 처리.
     * 연속된 이탤릭 수학 런은 하나의 수식으로 합쳐서 변환.
     */
    static void convertItalicRunsToEquations(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        splitItalicUppercaseLabelsFromPunctuation(items);

        boolean hasTarget = false;
        for (ASTInlineItem it : items) {
            if (it instanceof ASTTextRun
                    && !isSimplePositionedTextRun((ASTTextRun) it)
                    && (isItalicMathRun((ASTTextRun) it)
                        || isVariableBacktickLatinRun(((ASTTextRun) it).text()))) {
                hasTarget = true;
                break;
            }
        }
        if (!hasTarget) {
            mergeTriangleGeometryLabels(items);
            return;
        }

        List<ASTInlineItem> newItems = new ArrayList<>();
        StringBuilder mathBuf = new StringBuilder();
        List<IDMLCharacterRun> mathRuns = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun && isSimplePositionedTextRun((ASTTextRun) item)) {
                flushItalicMathBuf(mathBuf, mathRuns, newItems);
                newItems.add(item);
            } else if (item instanceof ASTTextRun
                    && (isItalicMathRun((ASTTextRun) item)
                        || isVariableBacktickLatinRun(((ASTTextRun) item).text()))) {
                ASTTextRun tr = (ASTTextRun) item;
                // "라틴1글자`라틴…"(예: x`km) 구조는 변수(수식)+백틱(thin space)+
                // 단위 텍스트다. 백틱은 원본이 명시한 경계 마커. 변수만 수식화하고
                // 백틱 뒤는 공백+정체 텍스트로 분리한다(실측: 3단원 x km, y cm²).
                String[] varUnit = splitVariableBacktickLatin(tr.text());
                if (varUnit != null) {
                    mathBuf.append(varUnit[0]);
                    IDMLCharacterRun crv = sourceRunForItalicMath(tr, varUnit[0]);
                    mathRuns.add(crv);
                    flushItalicMathBuf(mathBuf, mathRuns, newItems);
                    ASTTextRun unitRun = new ASTTextRun();
                    unitRun.text(" " + varUnit[1]);
                    newItems.add(unitRun);
                } else {
                    mathBuf.append(tr.text());
                    IDMLCharacterRun cr = sourceRunForItalicMath(tr, tr.text());
                    mathRuns.add(cr);
                }
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

    /**
     * Source character ranges commonly keep sentence punctuation in the same EH italic run as
     * a geometry label (for example {@code A,}).  Punctuation is prose, while the contiguous
     * uppercase label is one math variable.  Split that source range before equation grouping so
     * punctuation cannot change the label's materialization.
     */
    private static void splitItalicUppercaseLabelsFromPunctuation(List<ASTInlineItem> items) {
        List<ASTInlineItem> expanded = new ArrayList<>();
        boolean changed = false;
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTTextRun)) {
                expanded.add(item);
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            String style = run.fontStyle();
            if (text == null || style == null
                    || !style.toLowerCase(java.util.Locale.ROOT).contains("italic")) {
                expanded.add(item);
                continue;
            }

            int start = 0;
            while (start < text.length() && !isAsciiUpper(text.charAt(start))) start++;
            int end = start;
            while (end < text.length() && isAsciiUpper(text.charAt(end))) end++;
            if (start == end || !onlyLabelPunctuation(text, 0, start)
                    || !onlyLabelPunctuation(text, end, text.length())) {
                expanded.add(item);
                continue;
            }
            if (start > 0) expanded.add(run.copyWithText(text.substring(0, start)));
            expanded.add(run.copyWithText(text.substring(start, end)));
            if (end < text.length()) expanded.add(run.copyWithText(text.substring(end)));
            changed = true;
        }
        if (changed) {
            items.clear();
            items.addAll(expanded);
        }
    }

    private static boolean isAsciiUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean onlyLabelPunctuation(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) return false;
        }
        return true;
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
            // EH 변환 실패(예: 본문 폰트에 섞인 EH 인코딩 글리프) → 그래도 EH 글리프를
            // 디코딩한다. 그냥 raw 로 두면 Û(²)·Ü(³) 같은 위첨자 글리프가 깨진다
            // (실측: 3단원 p104 y=xÛ 이 y=x² 로 안 바뀜). convertToHwpScript 가 Û→^{2}
            // 등을 처리한다.
            script = EHFontEquationConverter.convertToHwpScript(mathBuf.toString());
        }
        if (!script.isEmpty()) {
            // 수식 스크립트의 선행 공백은 sanitizeHwpScript 의 trim 에 제거돼 앞 항과
            // 붙는다. 원문자와 수식 사이 구분 공백이므로(⑴ f(-1)) 공백을 앞 텍스트로
            // 분리해 보존한다(실측: 3단원 ⑴f(-1) 처럼 공백 흡수).
            int lead = 0;
            while (lead < script.length() && script.charAt(lead) == ' ') lead++;
            if (lead > 0) {
                ASTTextRun sp = new ASTTextRun();
                sp.text(script.substring(0, lead));
                out.add(sp);
                script = script.substring(lead);
            }
            if (!script.isEmpty()) {
                if (shouldEmitItalicEquation(script, mathRuns)) {
                    out.add(italicEquationWithSourceStyle(script, mathRuns));
                } else {
                    emitItalicMathRunsAsText(mathRuns, out);
                }
            }
        }
        mathBuf.setLength(0);
        mathRuns.clear();
    }

    /**
     * Preserve the concrete source typography while an ASTTextRun temporarily travels through
     * the italic-math classifier.  Keeping only content/fontFamily made the text fallback lose
     * its 8.5pt size and made punctuation decide whether an otherwise identical label became
     * 8.5pt text or a 10pt equation.
     */
    private static IDMLCharacterRun sourceRunForItalicMath(ASTTextRun source, String text) {
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content(text);
        run.fontFamily(source.fontFamily());
        run.fontStyle(source.fontStyle());
        if (source.fontSizeHwpunits() != null) {
            run.fontSize(source.fontSizeHwpunits() / 100.0);
        }
        run.fillColor(source.textColor());
        run.appliedCharacterStyle(source.characterStyleRef());
        if (source.subscript()) run.position("Subscript");
        else if (source.superscript()) run.position("Superscript");
        return run;
    }

    private static ASTEquation italicEquationWithSourceStyle(
            String script, List<IDMLCharacterRun> sourceRuns) {
        ASTEquation equation = new ASTEquation(script, "EH_FONT");
        if (sourceRuns == null) return equation;
        for (IDMLCharacterRun source : sourceRuns) {
            if (source == null) continue;
            if (equation.preferredBaseUnit() == null && source.fontSize() != null) {
                equation.preferredBaseUnit((int) Math.round(source.fontSize() * 100.0));
            }
            if (equation.preferredFontFamily() == null && source.fontFamily() != null) {
                equation.preferredFontFamily(source.fontFamily());
            }
            if (equation.textColor() == null && source.fillColor() != null) {
                equation.textColor(source.fillColor());
            }
        }
        return equation;
    }

    private static boolean shouldEmitItalicEquation(String script, List<IDMLCharacterRun> mathRuns) {
        if (script == null) return false;
        String trimmed = script.trim();
        if (trimmed.isEmpty()) return false;

        // Korean prose and bibliographic fragments can inherit italic/EH styling.
        // They are text, never equation objects.
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
        }

        // 각도값·다글자 HWP 키워드만 있는 약한 조각은 수식 오브젝트가 아니라 텍스트다.
        // 원본에 실질 수식 구조(분수 over·근호 sqrt·첨자 ^/_·위첨자 ²³) 없이
        // 등호+숫자+각(angle)/도(DEG) 만 있으면 텍스트로 둔다(실측: u5 p166 =90ù
        // → =90°. HWP 수식 키워드 DEG/angle 은 텍스트로 새면 낱글자로 깨지므로,
        // 애초에 수식으로 만들지 않고 텍스트+글리프 디코딩 경로로 보낸다).
        if (isWeakAngleDegreeScript(trimmed)) return false;

        if (FormulaClassifier.containsEquationSyntax(trimmed)) return true;
        if (containsEHEncodedEvidence(mathRuns)) return true;

        // A bare one-letter italic token is a source math variable.  Check this before the
        // broad unit classifier (which also accepts "x"); tokens such as *C still continue to
        // the unit path because they are not a bare letter.
        if (trimmed.length() == 1) {
            char only = trimmed.charAt(0);
            if ((only >= 'A' && only <= 'Z') || (only >= 'a' && only <= 'z')) return true;
        }

        // A source-authored italic uppercase cluster (AB, ABC) is one geometry label.  It is not
        // a prose word or unit, and punctuation has already been separated above.
        if (isItalicUppercaseSourceLabel(trimmed, mathRuns)) return true;

        // Plain units/numbers/short latin tokens such as mL, 28, *C must remain text.
        if (FormulaClassifier.isPlainUnitOrNumber(trimmed)) return false;
        if (hasLongLatinWord(trimmed, 3)) return false;

        // A single latin variable is a valid math equation; two or more latin letters
        // without syntax are usually unit/body text in these books.
        int letters = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) letters++;
        }
        return letters == 1;
    }

    private static boolean isItalicUppercaseSourceLabel(
            String text, List<IDMLCharacterRun> sourceRuns) {
        if (text == null || text.length() < 2 || sourceRuns == null || sourceRuns.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isAsciiUpper(text.charAt(i))) return false;
        }
        for (IDMLCharacterRun source : sourceRuns) {
            if (source == null || source.fontStyle() == null
                    || !source.fontStyle().toLowerCase(java.util.Locale.ROOT).contains("italic")) {
                return false;
            }
        }
        return true;
    }

    /**
     * "약한 각도/도 조각" 판별: 실질 수식 구조가 없고 등호/숫자/공백과
     * angle·DEG 키워드로만 이루어진 스크립트. 이런 조각은 수식 오브젝트가 아니라
     * 각 기호·도(°)가 섞인 텍스트이므로 텍스트로 폴백해야 한다(u5 p166 =90°).
     * 분수(over)·근호(sqrt/root)·첨자(^/_)·위첨자(²³)·기타 수식 기호가 있으면
     * false(진짜 수식이므로 건드리지 않음).
     */
    private static boolean isWeakAngleDegreeScript(String script) {
        if (script == null || script.isEmpty()) return false;
        String lower = script.toLowerCase(java.util.Locale.ROOT);
        // 강한 수식 구조가 하나라도 있으면 약한 조각이 아니다.
        if (lower.contains("over") || lower.contains("sqrt") || lower.contains("root")
                || lower.contains("overline") || lower.contains("rarrow")) {
            return false;
        }
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if ("^_²³√±×÷π∑∫∞{}[]<>≤≥".indexOf(c) >= 0) return false;
        }
        // angle/DEG 키워드가 반드시 있어야 이 판정 대상(그 외 일반 =수식은 기존 로직).
        if (!lower.contains("angle") && !lower.contains("deg")) return false;
        // 남은 문자가 등호/숫자/공백/마침표/쉼표 + angle·DEG 키워드 뿐인지 확인.
        String stripped = lower.replace("angle", "").replace("deg", "");
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isDigit(c) || c == '=' || c == ' ' || c == '.' || c == ',' || c == '-') continue;
            return false; // 그 외 문자(다른 라틴 변수 등)가 있으면 약한 조각 아님
        }
        return true;
    }

    private static boolean containsEHEncodedEvidence(List<IDMLCharacterRun> mathRuns) {
        if (mathRuns == null) return false;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null || run.content() == null) continue;
            String text = run.content();
            if (EHFontGlyphMap.containsEHEncodedChars(text)
                    || EHFontGlyphMap.containsEHFractionPattern(text)) {
                return true;
            }
        }
        return false;
    }

    private static void emitItalicMathRunsAsText(List<IDMLCharacterRun> mathRuns, List<ASTInlineItem> out) {
        if (mathRuns == null) return;
        for (IDMLCharacterRun run : mathRuns) {
            if (run == null) continue;
            String text = run.content();
            if (text == null || text.isEmpty()) continue;
            ASTTextRun tr = new ASTTextRun();
            // EH 글리프(ù→°, Û→² 등)를 디코딩한다. 이탤릭 수식 버퍼가 텍스트로
            // 폴백될 때 raw EH 글리프가 그대로 노출되지 않게 한다(실측: u5 p166
            // =90ù 가 =90° 로. flushEHMathGroup 폴백과 동일 처리).
            String decoded = EHFontGlyphMap.decodeStrayGlyphText(text.replace("`", ""), run.fontFamily());
            tr.text(decoded);
            tr.fontFamily(run.fontFamily());
            tr.characterStyleRef(run.appliedCharacterStyle());
            if (run.fontStyle() != null) tr.fontStyle(run.fontStyle());
            if (run.fontSize() != null) tr.fontSizeHwpunits((int) Math.round(run.fontSize() * 100.0));
            if (run.fillColor() != null) tr.textColor(run.fillColor());
            tr.subscript(run.isSubscript());
            tr.superscript(run.isSuperscript());
            out.add(tr);
        }
    }

    /** 이탤릭 수학 런 판별: 수식 전용 폰트/기호/짧은 변수 런만 수식으로 승격한다. */
    /**
     * run 텍스트가 "라틴1글자`라틴…" (변수+백틱+단위) 구조인지 판정한다.
     * {@link #splitVariableBacktickLatin} 과 동일 규칙 — EH 그룹 진입 제외 판정용.
     */
    static boolean isVariableBacktickLatinRun(String text) {
        return splitVariableBacktickLatin(text) != null;
    }

    /**
     * "라틴1글자`라틴…" 구조를 변수와 단위 텍스트로 분리한다.
     *
     * <p>순수 구조 규칙(단위 목록 없음): 백틱(thin space) 앞이 라틴 <b>1글자</b>이고,
     * 백틱 <b>뒤 첫 글자가 라틴</b>일 때만 분리한다. 백틱은 원본이 명시한 경계 마커다.
     * 제곱(x²의 xÛ`)처럼 백틱 뒤가 부호·공백인 경우는 첫 글자가 라틴이 아니므로 걸리지
     * 않는다. 단위의 EH 지수 마커(Û→², Ü→³)는 유니코드 위첨자로 디코딩한다.
     *
     * @return {@code [변수, 단위텍스트]} 또는 대상이 아니면 null
     */
    private static String[] splitVariableBacktickLatin(String text) {
        if (text == null) return null;
        int bt = text.indexOf('`');
        if (bt != 1) return null; // 백틱 앞이 정확히 1글자
        char v = text.charAt(0);
        if (!((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z'))) return null;
        String rest = text.substring(bt + 1);
        if (rest.isEmpty()) return null;
        char u0 = rest.charAt(0);
        if (!((u0 >= 'a' && u0 <= 'z') || (u0 >= 'A' && u0 <= 'Z'))) return null;
        // 단위의 EH 지수/마커 디코딩: Û→², Ü→³, 백틱 제거
        StringBuilder unit = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == 'Û') unit.append('²');      // Û → ²
            else if (c == 'Ü') unit.append('³'); // Ü → ³
            else if (c == '`') continue;                    // 백틱(thin space) 제거
            else unit.append(c);
        }
        return new String[]{String.valueOf(v), unit.toString()};
    }

    private static boolean isItalicMathRun(ASTTextRun tr) {
        String text = tr.text();
        if (text == null || text.isEmpty()) return false;
        // "정체"(正體=정상 위치) 문자스타일은 EH상부자 폰트를 쓰되 수식 변수가 아니라
        // 본문 라틴(인명·연도)이다. 수식으로 처리하면 원소기호 분리·이탤릭·색상 유실이
        // 연쇄로 일어난다(실측: 1단원 "Pythagoras, B.C. 569?~475?"의 P/B가 별도 수식
        // 런으로 쪼개져 검정색으로). 정체 스타일은 수식 변수 판정에서 제외한다.
        String csRef = tr.characterStyleRef();
        if (csRef != null) {
            String cs = csRef.toLowerCase(java.util.Locale.ROOT);
            if (cs.contains("정체") || cs.contains("정자")) return false;
        }
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

        // A punctuation fragment split from an EH range is prose punctuation, not a math run.
        // Without this guard the EH font alone reabsorbs the comma into the equation buffer.
        if (!containsLetterDigitOrMathEvidence(text)) return false;

        // Multi-letter geometry labels are authored as one italic EH/GREP math range.  Admit the
        // source math range before the generic long-word guard; ordinary italic acronyms in body
        // fonts still remain prose.
        if ((isEHFont || tr.grepMathFont()) && isAllAsciiUppercase(text)) return true;

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

    private static boolean isAllAsciiUppercase(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!isAsciiUpper(text.charAt(i))) return false;
        }
        return true;
    }

    private static boolean containsLetterDigitOrMathEvidence(String text) {
        if (text == null) return false;
        if (EHFontGlyphMap.containsEHEncodedChars(text)
                || EHFontGlyphMap.containsEHFractionPattern(text)) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || hasMathSyntax(String.valueOf(c))) return true;
        }
        return false;
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
