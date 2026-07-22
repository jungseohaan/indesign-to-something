package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHGrepFractionConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTMathGrouper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3 수식 처리 — BT/EH/NP 폰트 런을 ASTEquation으로 변환 (W3 Step B).
 * StoryConverter에서 분리됨.
 *
 * 책임:
 * - resolved-only 단락의 수식 폰트 런을 ASTMathGrouper로 위임 변환
 * - 분수 패턴 (EHGrepFractionConverter) 텍스트 분할
 * - EH 제곱근 (sqrt) 컨텐츠 감지
 */
class MathProcessor {

    private MathProcessor() {}

    /**
     * resolved-only 단락 내 수식 폰트 런(EH/BT/NP)을 ASTEquation으로 변환.
     * ASTTextRun의 fontFamily를 기반으로 IDMLCharacterRun 어댑터를 생성하여
     * ASTMathGrouper.flush* 메서드로 위임.
     */
    static void convertMathRunsInParagraph(ResolvedBuildContext ctx, ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        // 화학식은 문자속성 첨자 텍스트가 아니라 ASTEquation("CHEM_FORMULA")으로
        // 변환한다. 원본 run의 font size/color는 collectFormulaEquationCluster 에서
        // equation metadata(preferredBaseUnit/textColor)로 전달된다.

        // IDML 경로에서 이미 ASTEquation으로 변환된 단락은 건너뜀 (중복 변환 방지)
        boolean hasEquation = false;
        boolean hasEHRun = false;
        for (ASTInlineItem it : items) {
            if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) hasEquation = true;
            if (it instanceof ASTTextRun) {
                String ff = ((ASTTextRun) it).fontFamily();
                if (ff != null && EHFontGlyphMap.isEHFontFamily(ff)) hasEHRun = true;
            }
        }
        if (hasEquation) {
            collapseMixedFormulaEquationClusters(ctx, para);
            if (hasEHRun) {
                // 수식과 EH TextRun이 공존: EH TextRun은 수식 변환 잔여물 → 제거
                items.removeIf(it -> it instanceof ASTTextRun
                        && ((ASTTextRun) it).fontFamily() != null
                        && EHFontGlyphMap.isEHFontFamily(((ASTTextRun) it).fontFamily()));
            }
            convertSubscriptChemicalSegments(para);
            stitchChemicalFormulaFragments(para);
            return;
        }

        splitEHKoreanMixedTextRuns(items);

        List<ASTInlineItem> newItems = new ArrayList<>();
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        // SPEC-042: 그룹에 들어간 원본 ASTTextRun — flush 결과 텍스트런에 크기·굵기·
        // 첨자를 백필하기 위해 어댑터와 병렬로 유지 (어댑터 자체에 실으면 그룹핑
        // 하류 경로가 바뀌어 첨자 회귀가 났다. SPEC-042 실패 기록 참조)
        List<ASTTextRun> mathGroupSrc = new ArrayList<>();
        String mathType = null; // "EH", "BT", "NP"

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            ASTTextRun tr = (ASTTextRun) item;
            applyPositionFromCharacterStyle(tr);
            String ff = tr.fontFamily();
            String currentType = null;
            if (ff != null) {
                if (EHFontGlyphMap.isEHFontFamily(ff)) currentType = "EH";
                else if (BTFontGlyphMap.isBTFontFamily(ff)) currentType = "BT";
                else if (NPFontGlyphMap.isNPFont(ff)) currentType = "NP";
            }

            FormulaCluster formulaCluster = collectFormulaEquationCluster(items, i);
            if (formulaCluster != null) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                newItems.add(formulaCluster.equation);
                i = formulaCluster.endExclusive - 1;
                continue;
            }

            if (currentType != null && isSimplePositionedTextRun(tr)) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            boolean formulaBoundaryOnly = isFormulaBoundaryText(tr.text());
            if (formulaBoundaryOnly) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                inheritFormulaBoundaryTextColor(items, i, tr);
                newItems.add(item);
                continue;
            }

            // "BT수식H" 계열은 이름만 수식이고, 실제로는 본문 속 영문/숫자/기호를
            // 조판하는 폰트다(GREP 스타일 "00_영문", "00_숫자"가 적용). 실측:
            //   BT수식H-분수N   (356회)  "1-1", "1.", "2.", "H", "O", "2"
            //   BT수식H-편한글씨 (48회)   ":"  (비율 표기)
            // 수식 구조(분수/루트/시그마)가 전혀 없다.
            //
            // 이런 런을 무조건 수식 그룹에 넣으면 한글 문장 속 원소기호나 번호까지
            // HWP 수식(이탤릭)이 된다 — "수소 원자(H)" 의 H, "1." 같은 항목 번호.
            // → 수식 구조 문자가 없는 평범한 텍스트면 수식 그룹에 넣지 않는다.
            if (currentType != null && mathGroup.isEmpty()
                    && BTFontGlyphMap.isBTBodyTextFont(ff)
                    && !looksLikeMathContent(tr.text())) {
                newItems.add(item);
                continue;
            }

            if (currentType != null) {
                if (mathType == null || mathType.equals(currentType)) {
                    mathType = currentType;
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, ff);
                    mathGroup.add(cr);
                    mathGroupSrc.add(tr);
                } else {
                    flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                    mathGroup.clear();
                    mathGroupSrc.clear();
                    mathType = currentType;
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, ff);
                    mathGroup.add(cr);
                    mathGroupSrc.add(tr);
                }
            } else {
                // EH 그룹이 열려있으면 비EH 런의 bridge 가능성 확인
                // 짧은 특수 공백(thin/four-per-em space) 또는 연산자 1문자만 bridge 허용
                boolean bridge = false;
                if ("EH".equals(mathType) && !mathGroup.isEmpty()) {
                    String text = tr.text();
                    if (text != null && text.length() <= 2) {
                        boolean allBridgeable = true;
                        for (int ci = 0; ci < text.length(); ci++) {
                            char c = text.charAt(ci);
                            // 특수 공백, 연산 기호만 bridge
                            if (c != '\u2005' && c != '\u2009' && c != '\u2003' && c != ' '
                                    && c != '+' && c != '-' && c != '=' && c != '\u00D7'
                                    && c != '\u00F7') {
                                allBridgeable = false;
                                break;
                            }
                        }
                        if (allBridgeable) {
                            // 뒤에 EH 폰트 런이 있는지 확인
                            for (int ni = i + 1; ni < items.size(); ni++) {
                                ASTInlineItem next = items.get(ni);
                                if (next instanceof ASTTextRun) {
                                    String nff = ((ASTTextRun) next).fontFamily();
                                    if (nff != null && EHFontGlyphMap.isEHFontFamily(nff)) {
                                        bridge = true;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                if (bridge) {
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, tr.fontFamily() != null ? tr.fontFamily() : "");
                    mathGroup.add(cr);
                    mathGroupSrc.add(tr);
                } else {
                    flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                    mathGroup.clear();
                    mathGroupSrc.clear();
                    mathType = null;
                    newItems.add(item);
                }
            }
        }
        flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);

        if (newItems.size() != items.size() || !newItems.equals(items)) {
            items.clear();
            items.addAll(newItems);
        }
        collapseMixedFormulaEquationClusters(ctx, para);
        convertSubscriptChemicalSegments(para);
        stitchChemicalFormulaFragments(para);
    }

    /**
     * EH 폰트가 붙었지만 한국어가 섞인 TextRun 을 첫 한국어 문자에서 분리한다.
     *
     * <p>InDesign DOM(resolved)은 √ 글리프 뒤 한국어 문장까지 EH상부자 폰트로
     * 보고하는 경우가 있다(실측: p20 표 셀 "a가 √a 보다 항상 더 큰지 말해 보자."의
     * "a 보다…보자." 전체가 EH상부자). 그대로 수식 그룹에 넣으면 lexSubSup 이
     * 미매핑 0x80+ 문자로 한국어를 통째로 버려 문장이 sqrt{a}. 로 잘린다.
     * 라틴 머리(radicand)는 EH 수식으로 남기고, 첫 한국어부터는 EH 폰트를 지운
     * 일반 텍스트 런으로 분리한다.
     */
    private static void splitEHKoreanMixedTextRuns(List<ASTInlineItem> items) {
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun tr = (ASTTextRun) item;
            String ff = tr.fontFamily();
            if (ff == null || !EHFontGlyphMap.isEHFontFamily(ff)) continue;
            String text = tr.text();
            if (text == null) continue;
            int k = firstKoreanIndex(text);
            if (k < 0) continue;
            if (k == 0) {
                tr.fontFamily(null);
                tr.fontStyle(null);
                continue;
            }
            ASTTextRun tail = new ASTTextRun();
            tail.text(text.substring(k));
            tail.fontSizeHwpunits(tr.fontSizeHwpunits());
            tail.textColor(tr.textColor());
            tail.shadeColor(tr.shadeColor());
            tail.letterSpacing(tr.letterSpacing());
            tr.text(text.substring(0, k));
            items.add(i + 1, tail);
            i++; // tail 은 EH 폰트가 없으므로 재검사 불필요
        }
    }

    private static int firstKoreanIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) return i;
        }
        return -1;
    }

    private static void collapseMixedFormulaEquationClusters(ResolvedBuildContext ctx, ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        splitBoundaryWrappedFormulaEquations(items);

        List<ASTInlineItem> out = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            int duplicatePrefixEnd = duplicatePlaceholderPrefixBeforeBoxEquation(ctx, items, i);
            if (duplicatePrefixEnd > i) {
                out.add(items.get(duplicatePrefixEnd));
                i = duplicatePrefixEnd;
                changed = true;
                continue;
            }
            FormulaCluster cluster = collectMixedFormulaEquationCluster(ctx, items, i);
            if (cluster != null) {
                removeTrailingFormulaPlaceholdersAlreadyInEquation(ctx, out, cluster.equation);
                out.add(cluster.equation);
                i = cluster.endExclusive - 1;
                changed = true;
            } else {
                out.add(items.get(i));
            }
        }
        if (changed) {
            items.clear();
            items.addAll(out);
        }
    }

    private static void splitBoundaryWrappedFormulaEquations(List<ASTInlineItem> items) {
        if (items == null || items.isEmpty()) return;
        List<ASTInlineItem> out = new ArrayList<>();
        boolean changed = false;
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTEquation)) {
                out.add(item);
                continue;
            }
            ASTEquation eq = (ASTEquation) item;
            String script = normalizeExistingFormulaEquationScript(eq.hwpScript());
            if (script == null || script.isEmpty()) {
                out.add(item);
                continue;
            }
            int start = 0;
            while (start < script.length() && isEquationBoundaryChar(script.charAt(start))) start++;
            int end = script.length();
            while (end > start && isEquationBoundaryChar(script.charAt(end - 1))) end--;
            if (start == 0 && end == script.length()) {
                out.add(item);
                continue;
            }
            String core = script.substring(start, end);
            if (core.isEmpty() || !isFormulaEquationScript(core)) {
                out.add(item);
                continue;
            }
            // sqrt{...}·분수 {A} over {B} 의 구문 중괄호를 경계로 오인해 벗기면 짝이 깨져
            // 닫는 }가 리터럴 텍스트로 새고 수식이 열린 채 렌더된다(실측: 수학 1단원 근호
            // 선택지 -sqrt{121} → -sqrt{121 + "}"). core 의 { } 균형이 맞을 때만 벗긴다.
            if (!isBraceBalanced(core)) {
                out.add(item);
                continue;
            }
            // 소괄호도 마찬가지 — (-16)×…÷(-6/7) 의 바깥 ( ) 나 left( … right) 의 소괄호를
            // 경계로 벗기면 짝이 깨져 닫는 )·right 가 리터럴로 새거나 left( 가 미완결된다
            // (실측: 1단원 p30 "(-16)×3/4÷(-6/7)" → "-16)…right"). ( ) 균형이 맞을 때만 벗긴다.
            if (!isParenBalanced(core)) {
                out.add(item);
                continue;
            }
            if (start > 0) {
                out.add(textRunFromEquationBoundary(eq, script.substring(0, start)));
            }
            ASTEquation coreEq = new ASTEquation(core, eq.sourceType());
            copyEquationHints(eq, coreEq);
            out.add(coreEq);
            if (end < script.length()) {
                out.add(textRunFromEquationBoundary(eq, script.substring(end)));
            }
            changed = true;
        }
        if (changed) {
            items.clear();
            items.addAll(out);
        }
    }

    private static boolean isEquationBoundaryChar(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == ',' || c == '.';
    }

    /** HWP 수식 구문 중괄호 { } 짝이 맞는지. sqrt/분수 구문 보호용. */
    private static boolean isBraceBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth < 0) return false; }
        }
        return depth == 0;
    }

    /**
     * HWP 수식 소괄호 ( ) 짝이 맞는지. left( … right) 및 (-16)·(-6/7) 보호용.
     * left(/right 키워드의 소괄호도 리터럴 ( )와 함께 depth 로 센다.
     */
    private static boolean isParenBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth < 0) return false; }
        }
        return depth == 0;
    }

    private static ASTTextRun textRunFromEquationBoundary(ASTEquation source, String text) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        if (source != null) {
            run.textColor(source.textColor());
            run.fontSizeHwpunits(source.preferredBaseUnit());
            run.fontFamily(source.preferredFontFamily());
        }
        return run;
    }

    private static void copyEquationHints(ASTEquation source, ASTEquation target) {
        if (source == null || target == null) return;
        target.textColor(source.textColor());
        target.preferredBaseUnit(source.preferredBaseUnit());
        target.preferredFontFamily(source.preferredFontFamily());
    }

    private static int duplicatePlaceholderPrefixBeforeBoxEquation(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> items,
            int start) {
        if (ctx == null || items == null || start < 0 || start >= items.size()) return -1;
        int i = start;
        int placeholders = 0;
        while (i < items.size()
                && items.get(i) instanceof ASTInlineObject
                && isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) items.get(i))) {
            placeholders++;
            i++;
        }
        if (placeholders == 0 || i >= items.size() || !(items.get(i) instanceof ASTEquation)) {
            return -1;
        }
        ASTEquation equation = (ASTEquation) items.get(i);
        return countFormulaBoxes(equation.hwpScript()) >= placeholders ? i : -1;
    }

    private static void removeTrailingFormulaPlaceholdersAlreadyInEquation(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> out,
            ASTEquation equation) {
        if (ctx == null || out == null || out.isEmpty() || equation == null) return;
        int remainingBoxes = countFormulaBoxes(equation.hwpScript());
        while (remainingBoxes > 0 && !out.isEmpty()) {
            ASTInlineItem last = out.get(out.size() - 1);
            if (!(last instanceof ASTInlineObject)
                    || !isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) last)) {
                return;
            }
            out.remove(out.size() - 1);
            remainingBoxes--;
        }
    }

    private static int countFormulaBoxes(String script) {
        if (script == null || script.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\u25A1' || c == '\uFFFC') count++;
        }
        return count;
    }

    private static FormulaCluster collectMixedFormulaEquationCluster(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> items,
            int start) {
        if (items == null || start < 0 || start >= items.size()) return null;

        StringBuilder script = new StringBuilder();
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasOperator = false;
        boolean hasBox = false;
        boolean hasArrow = false;
        boolean hasEquation = false;
        boolean hasPositioned = false;
        boolean hasChemicalSymbol = false;
        boolean hasFormulaFontEvidence = false;
        String color = null;
        Integer preferredBaseUnit = null;
        String preferredFontFamily = null;
        char previousVisible = 0;
        int end = start;

        for (int i = start; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun) {
                ASTTextRun tr = (ASTTextRun) item;
                applyPositionFromCharacterStyle(tr);
                String text = tr.text();
                if (text == null || text.isEmpty() || !isFormulaClusterText(text)) break;
                if (isFormulaBoundaryText(text)) {
                    if (i == start) return null;
                    break;
                }
                if (color == null) color = tr.textColor();
                if (preferredBaseUnit == null && tr.fontSizeHwpunits() != null && tr.fontSizeHwpunits() > 0) {
                    preferredBaseUnit = tr.fontSizeHwpunits();
                }
                if (preferredFontFamily == null && tr.fontFamily() != null && !tr.fontFamily().isEmpty()) {
                    preferredFontFamily = tr.fontFamily();
                }
                ScriptAppendResult result = appendFormulaScript(script, text, tr, previousVisible);
                if (!result.accepted) break;
                previousVisible = result.previousVisible;
                hasLetter |= result.hasLetter;
                hasDigit |= result.hasDigit;
                hasOperator |= result.hasOperator;
                hasBox |= result.hasBox;
                hasArrow |= result.hasArrow;
                hasPositioned |= tr.subscript() || tr.superscript();
                hasChemicalSymbol |= result.hasChemicalSymbol;
                hasFormulaFontEvidence |= isFormulaFontEvidence(tr);
                end = i + 1;
                continue;
            }

            if (item instanceof ASTEquation) {
                ASTEquation eq = (ASTEquation) item;
                if (isProtectedInlineFormulaEquation(eq)) break;
                String eqScript = normalizeExistingFormulaEquationScript(eq.hwpScript());
                if (eqScript.isEmpty() || !isFormulaEquationScript(eqScript)) break;
                ScriptAppendResult result =
                        appendExistingFormulaEquationScript(script, eqScript, previousVisible);
                if (!result.accepted) break;
                hasLetter |= result.hasLetter;
                hasDigit |= result.hasDigit;
                hasOperator |= result.hasOperator;
                hasBox |= result.hasBox;
                hasArrow |= result.hasArrow;
                hasPositioned |= result.hasImplicitSubscript || eqScript.indexOf('_') >= 0 || eqScript.indexOf('^') >= 0;
                hasChemicalSymbol |= result.hasChemicalSymbol;
                hasEquation = true;
                hasFormulaFontEvidence = true;
                previousVisible = result.previousVisible;
                if (color == null) color = eq.textColor();
                if (preferredBaseUnit == null && eq.preferredBaseUnit() != null && eq.preferredBaseUnit() > 0) {
                    preferredBaseUnit = eq.preferredBaseUnit();
                }
                if (preferredFontFamily == null && eq.preferredFontFamily() != null && !eq.preferredFontFamily().isEmpty()) {
                    preferredFontFamily = eq.preferredFontFamily();
                }
                end = i + 1;
                continue;
            }

            if (item instanceof ASTInlineObject && isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) item)) {
                script.append('\u25A1');
                hasBox = true;
                previousVisible = '\u25A1';
                end = i + 1;
                continue;
            }

            break;
        }

        String hwpScript = normalizeFormulaScript(script.toString());
        if (hwpScript.isEmpty() || hwpScript.length() > 160) return null;
        boolean chemicalElementSequence = isChemicalFormulaElementSequence(hwpScript)
                && (hasFormulaFontEvidence || hasChemicalFormulaDelimiterContext(items, start, end));
        if (end - start < 2 && !chemicalElementSequence) return null;
        if (!hasLetter) return null;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return null;
        if (!hasEquation && !hasBox && !chemicalElementSequence) return null;
        if (hasBox && !hasEquation && !hasDigit && !hasOperator && !hasArrow && !hasPositioned) return null;
        if (!hasEquation
                && !hasBox
                && !hasOperator
                && !hasArrow
                && hasPositioned
                && chemicalElementSequence) {
            return null;
        }
        if (!chemicalElementSequence
                && !hasDigit
                && !hasOperator
                && !hasBox
                && !hasArrow
                && !hasPositioned) {
            return null;
        }
        if (!isFormulaEquationMaterializationCandidate(
                hwpScript,
                chemicalElementSequence,
                hasFormulaFontEvidence,
                hasEquation,
                hasOperator,
                hasBox,
                hasArrow,
                hasPositioned,
                hasChemicalSymbol)) {
            return null;
        }

        // 화학식(CHEM_FORMULA)은 toChemicalTextRuns 로 낱글자 텍스트화된다. 그런데
        // hwpScript 에 화학식과 무관한 다글자 HWP 수식 키워드(angle/DEG/TIMES/div/
        // sqrt/over…)가 들어 있으면 낱글자화 시 그 키워드가 대문자 원소기호로 오인돼
        // raw 로 노출된다(실측: u5 p166 ∠PAO=∠PBO=90° → EH상부자 ù→"DEG" 가 포함된
        // "PBO=90 DEG" 가 D·E·G 낱글자로 깨짐). 이런 조각은 진짜 화학식이 아니라 각
        // 기호/도(°)가 섞인 텍스트이므로 CHEM_FORMULA 로 방출하지 않고 null 을 반환해
        // 원본 텍스트 런을 보존한다(decodeStrayGlyphInBodyFont 가 ù→° 로 처리).
        if (containsNonChemicalFormulaKeyword(hwpScript)) {
            return null;
        }

        ASTEquation eq = new ASTEquation(hwpScript, "CHEM_FORMULA");
        color = resolvedFormulaTextColor(items, start, end, color);
        if (color != null) eq.textColor(color);
        applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
        return new FormulaCluster(eq, end);
    }

    private static String resolvedFormulaTextColor(
            List<ASTInlineItem> items,
            int start,
            int endExclusive,
            String clusterColor) {
        if (clusterColor != null && !clusterColor.isEmpty() && !isDefaultBlack(clusterColor)) {
            return clusterColor;
        }
        String nearby = nearbyBodyTextColor(items, start, endExclusive);
        if (nearby != null && !nearby.isEmpty()) {
            return nearby;
        }
        return clusterColor;
    }

    private static void inheritFormulaBoundaryTextColor(List<ASTInlineItem> items, int index, ASTTextRun boundaryRun) {
        if (boundaryRun == null) return;
        String color = boundaryRun.textColor();
        if (color != null && !color.isEmpty() && !isDefaultBlack(color)) return;
        String inherited = nearbyBodyTextColor(items, index, index + 1);
        if (inherited != null && !inherited.isEmpty()) {
            boundaryRun.textColor(inherited);
        }
    }

    private static String nearbyBodyTextColor(List<ASTInlineItem> items, int start, int endExclusive) {
        String before = nearbyBodyTextColor(items, start - 1, -1, -1);
        if (before != null) return before;
        return nearbyBodyTextColor(items, endExclusive, items != null ? items.size() : 0, 1);
    }

    private static String nearbyBodyTextColor(List<ASTInlineItem> items, int index, int stopExclusive, int step) {
        if (items == null || step == 0) return null;
        int seen = 0;
        for (int i = index; i != stopExclusive && i >= 0 && i < items.size() && seen < 12; i += step, seen++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.trim().isEmpty()) continue;
            String color = run.textColor();
            if (color == null || color.isEmpty() || isDefaultBlack(color)) continue;
            if (isFormulaFontEvidence(run)) continue;
            return color;
        }
        return null;
    }

    private static boolean isDefaultBlack(String color) {
        if (color == null) return false;
        String normalized = color.trim();
        return "#000000".equalsIgnoreCase(normalized)
                || "000000".equalsIgnoreCase(normalized);
    }

    private static boolean isProtectedInlineFormulaEquation(ASTEquation eq) {
        if (eq == null) return false;
        String sourceType = eq.sourceType();
        if ("INLINE_FRACTION".equals(sourceType)) return true;

        String script = eq.hwpScript();
        if (script == null) return false;
        String normalized = script.toLowerCase(Locale.ROOT);
        return normalized.contains(" over ")
                || normalized.contains("sqrt")
                || normalized.contains("root");
    }

    private static String normalizeExistingFormulaEquationScript(String script) {
        if (script == null) return "";
        return script
                .replace('\uFFFC', '\u25A1')
                .replace("@C", " rarrow ")
                .replace("@c", " rarrow ")
                .replace("?C", " rarrow ")
                .replace("?c", " rarrow ")
                .replace("RIGHT", " rarrow ")
                .replace("RARROW", " rarrow ")
                .replace("->", " rarrow ")
                .trim();
    }

    private static boolean needsFormulaSpaceBefore(StringBuilder script, String next) {
        if (script == null || script.length() == 0 || next == null || next.isEmpty()) return false;
        char prev = script.charAt(script.length() - 1);
        char first = next.charAt(0);
        return Character.isLetterOrDigit(prev) && Character.isLetterOrDigit(first);
    }

    private static ScriptAppendResult appendExistingFormulaEquationScript(
            StringBuilder out,
            String script,
            char previousVisible) {
        if (script == null || script.isEmpty()) {
            ScriptAppendResult empty = new ScriptAppendResult();
            empty.previousVisible = previousVisible;
            return empty;
        }
        if (script.indexOf('_') < 0 && script.indexOf('^') < 0 && !containsFormulaArrow(script)) {
            if (isChemicalFormulaElementSequence(script)) {
                String implicitScript = withImplicitChemicalSubscripts(script);
                if (!implicitScript.equals(script)) {
                    if (out.length() > 0 && needsFormulaSpaceBefore(out, implicitScript)) out.append(' ');
                    out.append(implicitScript);
                    FormulaScriptStats stats = statsForFormulaScript(implicitScript);
                    ScriptAppendResult result = new ScriptAppendResult();
                    result.hasLetter = stats.hasLetter;
                    result.hasDigit = stats.hasDigit;
                    result.hasOperator = stats.hasOperator;
                    result.hasBox = stats.hasBox;
                    result.hasArrow = stats.hasArrow;
                    result.hasImplicitSubscript = stats.hasPositioned;
                    result.hasChemicalSymbol = stats.hasChemicalSymbol;
                    result.previousVisible = stats.previousVisible != 0 ? stats.previousVisible : previousVisible;
                    return result;
                }
            }
            ASTTextRun run = new ASTTextRun();
            run.text(script);
            return appendFormulaScript(out, script, run, previousVisible);
        }

        ScriptAppendResult result = new ScriptAppendResult();
        result.previousVisible = previousVisible;
        if (out.length() > 0 && needsFormulaSpaceBefore(out, script)) out.append(' ');
        out.append(script);
        FormulaScriptStats stats = statsForFormulaScript(script);
        result.hasLetter = stats.hasLetter;
        result.hasDigit = stats.hasDigit;
        result.hasOperator = stats.hasOperator;
        result.hasBox = stats.hasBox;
        result.hasArrow = stats.hasArrow;
        result.hasImplicitSubscript = stats.hasPositioned;
        result.hasChemicalSymbol = stats.hasChemicalSymbol;
        result.previousVisible = stats.previousVisible != 0 ? stats.previousVisible : previousVisible;
        return result;
    }

    private static boolean isFormulaEquationScript(String script) {
        if (script == null || script.isEmpty()) return false;
        boolean hasFormulaToken = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '_' || c == '^' || c == '{' || c == '}') continue;
            if (isAsciiLetter(c) || Character.isDigit(c)) {
                hasFormulaToken = true;
                continue;
            }
            if (script.regionMatches(true, i, "rarrow", 0, "rarrow".length())) {
                hasFormulaToken = true;
                i += "rarrow".length() - 1;
                continue;
            }
            if (c == '\u25A1' || c == '+' || c == '-' || c == '=' || c == '(' || c == ')') {
                hasFormulaToken = true;
                continue;
            }
            return false;
        }
        return hasFormulaToken;
    }

    private static String withImplicitChemicalSubscripts(String script) {
        String token = chemicalFormulaToken(script);
        if (token.isEmpty()) return script;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            out.append(token.charAt(i));
            i++;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c < 'A' || c > 'Z') return script;
            String symbol = String.valueOf(c);
            int next = i + 1;
            if (next < token.length()) {
                char nc = token.charAt(next);
                if (nc >= 'a' && nc <= 'z') {
                    String two = "" + c + nc;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        next++;
                    }
                }
            }
            if (!isChemicalElement(symbol)) return script;
            out.append(symbol);
            i = next;
            StringBuilder digits = new StringBuilder();
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                digits.append(token.charAt(i));
                i++;
            }
            if (digits.length() > 0) {
                out.append("_{").append(digits).append("}");
            }
        }
        return out.toString();
    }

    private static final class FormulaScriptStats {
        boolean hasLetter;
        boolean hasDigit;
        boolean hasOperator;
        boolean hasBox;
        boolean hasArrow;
        boolean hasPositioned;
        boolean hasChemicalSymbol;
        char previousVisible;
    }

    private static FormulaScriptStats statsForFormulaScript(String script) {
        FormulaScriptStats stats = new FormulaScriptStats();
        if (script == null) return stats;
        char previous = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '{' || c == '}') continue;
            if (c == '_' || c == '^') {
                stats.hasPositioned = true;
                continue;
            }
            if (script.regionMatches(true, i, "rarrow", 0, "rarrow".length())) {
                stats.hasArrow = true;
                stats.hasOperator = true;
                previous = '\u2192';
                i += "rarrow".length() - 1;
                continue;
            }
            if (c == '\u25A1') {
                stats.hasBox = true;
                previous = c;
                continue;
            }
            if (c == '+' || c == '-' || c == '=') {
                stats.hasOperator = true;
                previous = c;
                continue;
            }
            if (isAsciiLetter(c)) {
                stats.hasLetter = true;
                stats.hasChemicalSymbol |= isLikelyChemicalElementAt(script, i);
                previous = c;
                continue;
            }
            if (Character.isDigit(c)) {
                stats.hasDigit = true;
                previous = c;
            }
        }
        stats.previousVisible = previous;
        return stats;
    }

    private static boolean isFormulaAnswerPlaceholder(ResolvedBuildContext ctx, ASTInlineObject obj) {
        if (ctx == null || obj == null || obj.sourceId() == null) return false;
        Integer sourceId = sourceIdToDomId(obj.sourceId());
        if (sourceId == null) return false;
        return isFormulaAnswerPlaceholderSource(ctx, sourceId);
    }

    static boolean isFormulaAnswerPlaceholderRun(ResolvedBuildContext ctx, IDMLCharacterRun run) {
        if (run == null) return false;
        boolean sawAnchor = false;
        if (run.inlineAnchors() != null) {
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                Integer id = inlineAnchorDomId(run, anchor);
                if (id == null) continue;
                sawAnchor = true;
                if (!isFormulaAnswerPlaceholderSource(ctx, id)) return false;
            }
        }
        if (sawAnchor) return true;
        String text = run.content();
        return text != null && !text.isEmpty() && text.replace("\uFFFC", "").isEmpty();
    }

    private static Integer inlineAnchorDomId(IDMLCharacterRun run, IDMLCharacterRun.InlineAnchor anchor) {
        if (run == null || anchor == null) return null;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                && run.inlineFrames() != null
                && anchor.index() >= 0
                && anchor.index() < run.inlineFrames().size()) {
            IDMLTextFrame tf = run.inlineFrames().get(anchor.index());
            return tf != null ? sourceIdToDomId(tf.selfId()) : null;
        }
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                && run.inlineGraphics() != null
                && anchor.index() >= 0
                && anchor.index() < run.inlineGraphics().size()) {
            IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
            return graphic != null ? sourceIdToDomId(graphic.selfId()) : null;
        }
        return null;
    }

    static boolean isFormulaAnswerPlaceholderSource(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null) return false;
        ObjectPlan plan = findInlinePlaceholderPlan(ctx, sourceId);
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (isContentInlineVisualPlan(plan)) return false;
        if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (isEmptyShellTextFrame(ctx, sourceId)) return true;
        if (plan.visualSourceObjectIds != null) {
            for (int id : plan.visualSourceObjectIds) {
                if (isEmptyShellTextFrame(ctx, id) || isInlineAnswerBoxShape(ctx, id)) return true;
            }
        }
        if (plan.sourceObjectIds != null) {
            for (int id : plan.sourceObjectIds) {
                if (isEmptyShellTextFrame(ctx, id) || isInlineAnswerBoxShape(ctx, id)) return true;
            }
        }
        return isInlineAnswerBoxShape(ctx, sourceId);
    }

    private static boolean isContentInlineVisualPlan(ObjectPlan plan) {
        if (plan == null) return false;
        String slotRole = plan.slotRole != null ? plan.slotRole : "";
        if ("CONTENT_VISUAL_SLOT".equals(slotRole)) return true;
        if ("COMPLETE_PNG_SLOT".equals(slotRole)) return true;
        return plan.visualLayer == VisualLayer.CONTENT_VISUAL
                && plan.visualAction == VisualAction.PLACE_INLINE_PNG;
    }

    private static ObjectPlan findInlinePlaceholderPlan(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.ownershipPlans == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (planContainsSource(plan, sourceId)) {
                if (plan.placement == Placement.INLINE
                        && (plan.visualAction == VisualAction.PLACE_INLINE_PNG
                        || plan.visualAction == VisualAction.PLACE_TEXT_SHELL)) {
                    return plan;
                }
            }
        }
        return null;
    }

    private static boolean planContainsSource(ObjectPlan plan, int sourceId) {
        return plan.domId == sourceId
                || contains(plan.sourceObjectIds, sourceId)
                || contains(plan.visualSourceObjectIds, sourceId)
                || contains(plan.exportSourceObjectIds, sourceId)
                || contains(plan.ownedTextFrameIds, sourceId);
    }

    private static boolean isEmptyShellTextFrame(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(sourceId));
        if (tf == null) return false;
        String text = tf.frameVisibleText();
        boolean emptyText = text == null || text.replace("\uFFFC", "").trim().isEmpty();
        if (!emptyText) return false;
        return visibleStroke(tf.strokeColor(), tf.strokeWeight()) || visibleFill(tf.fillColor());
    }

    private static boolean isInlineAnswerBoxShape(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        if (item == null) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"Polygon".equals(type) && !"Oval".equals(type)) return false;
        double[] b = item.visibleBounds();
        if (b == null || b.length < 4) b = item.geometricBounds();
        if (b == null || b.length < 4) return false;
        double width = Math.abs(b[3] - b[1]);
        double height = Math.abs(b[2] - b[0]);
        if (width <= 0.0 || height <= 0.0) return false;
        double ratio = Math.max(width / height, height / width);
        if (ratio > 6.0) return false;
        String storyAnchorPlacement = upper(item.storyAnchorPlacement());
        String anchoredPosition = upper(item.anchoredPosition());
        boolean inline = "INLINE".equals(storyAnchorPlacement)
                || "INLINE_POSITION".equals(anchoredPosition)
                || "INLINEPOSITION".equals(anchoredPosition);
        if (!inline) return false;
        return visibleStroke(item.strokeColorName(), item.strokeWeight()) || visibleFill(item.fillColorName());
    }

    private static boolean visibleStroke(String color, double weight) {
        return color != null && !color.isEmpty() && !"None".equals(color) && weight > 0.0;
    }

    private static boolean visibleFill(String color) {
        return color != null && !color.isEmpty() && !"None".equals(color);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static Integer sourceIdToDomId(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        String s = sourceId;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        int suffix = s.indexOf('_');
        if (suffix > 0) s = s.substring(0, suffix);
        if (s.startsWith("u") || s.startsWith("U")) {
            try {
                return Integer.parseInt(s.substring(1), 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean contains(int[] ids, int value) {
        if (ids == null) return false;
        for (int id : ids) if (id == value) return true;
        return false;
    }

    private static class FormulaCluster {
        final ASTEquation equation;
        final int endExclusive;

        FormulaCluster(ASTEquation equation, int endExclusive) {
            this.equation = equation;
            this.endExclusive = endExclusive;
        }
    }

    private static FormulaCluster collectFormulaEquationCluster(List<ASTInlineItem> items, int start) {
        if (items == null || start < 0 || start >= items.size()) return null;
        StringBuilder script = new StringBuilder();
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasOperator = false;
        boolean hasBox = false;
        boolean hasArrow = false;
        boolean hasPositioned = false;
        boolean hasChemicalSymbol = false;
        boolean hasFormulaFontEvidence = false;
        String color = null;
        Integer preferredBaseUnit = null;
        String preferredFontFamily = null;
        char previousVisible = 0;
        int end = start;

        for (int i = start; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) break;
            ASTTextRun tr = (ASTTextRun) item;
            applyPositionFromCharacterStyle(tr);
            String text = tr.text();
            if (text == null || text.isEmpty()) break;
            if (!isFormulaClusterText(text)) break;
            if (isFormulaBoundaryText(text)) {
                if (i == start) return null;
                break;
            }

            if (color == null) color = tr.textColor();
            if (preferredBaseUnit == null && tr.fontSizeHwpunits() != null && tr.fontSizeHwpunits() > 0) {
                preferredBaseUnit = tr.fontSizeHwpunits();
            }
            if (preferredFontFamily == null && tr.fontFamily() != null && !tr.fontFamily().isEmpty()) {
                preferredFontFamily = tr.fontFamily();
            }
            ScriptAppendResult result = appendFormulaScript(script, text, tr, previousVisible);
            if (!result.accepted) break;
            previousVisible = result.previousVisible;
            hasLetter |= result.hasLetter;
            hasDigit |= result.hasDigit;
            hasOperator |= result.hasOperator;
            hasBox |= result.hasBox;
            hasArrow |= result.hasArrow;
            hasPositioned |= tr.subscript() || tr.superscript();
            hasChemicalSymbol |= result.hasChemicalSymbol;
            hasFormulaFontEvidence |= isFormulaFontEvidence(tr);
            end = i + 1;
        }

        if (end == start) return null;
        String hwpScript = normalizeFormulaScript(script.toString());
        if (hwpScript.isEmpty() || hwpScript.length() > 128) return null;
        boolean chemicalElementSequence = isChemicalFormulaElementSequence(hwpScript)
                && (hasFormulaFontEvidence || hasChemicalFormulaDelimiterContext(items, start, end));
        if (!hasFormulaFontEvidence
                && !chemicalElementSequence
                && !hasDigit
                && !hasBox
                && !hasArrow
                && !hasPositioned
                && !looksLikeMathContent(hwpScript)) {
            return null;
        }
        if (!hasFormulaFontEvidence
                && !chemicalElementSequence
                && hasLongLatinWord(hwpScript, 3)
                && !hasDigit
                && !hasBox
                && !hasArrow
                && !hasPositioned
                && !looksLikeMathContent(hwpScript)) {
            return null;
        }
        if (!hasLetter) return null;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return null;
        if (hasBox && !hasDigit && !hasOperator && !hasArrow && !hasPositioned) return null;
        if (!chemicalElementSequence
                && !hasDigit
                && !hasOperator
                && !hasBox
                && !hasArrow
                && !hasPositioned) {
            return null;
        }
        if (!isFormulaEquationMaterializationCandidate(
                hwpScript,
                chemicalElementSequence,
                hasFormulaFontEvidence,
                false,
                hasOperator,
                hasBox,
                hasArrow,
                hasPositioned,
                hasChemicalSymbol)) {
            return null;
        }

        ASTEquation eq = new ASTEquation(hwpScript, "CHEM_FORMULA");
        color = resolvedFormulaTextColor(items, start, end, color);
        if (color != null) eq.textColor(color);
        applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
        return new FormulaCluster(eq, end);
    }

    private static boolean hasChemicalFormulaDelimiterContext(List<ASTInlineItem> items, int start, int endExclusive) {
        if (items == null || start < 0 || endExclusive <= start) return false;
        Character before = previousVisibleChar(items, start - 1);
        Character after = nextVisibleChar(items, endExclusive);
        return isOpeningFormulaDelimiter(before) || isClosingFormulaDelimiter(after);
    }

    private static boolean isFormulaEquationMaterializationCandidate(
            String hwpScript,
            boolean chemicalElementSequence,
            boolean hasFormulaFontEvidence,
            boolean hasEquation,
            boolean hasOperator,
            boolean hasBox,
            boolean hasArrow,
            boolean hasPositioned,
            boolean hasChemicalSymbol) {
        return FormulaClassifier.shouldMaterializeChemicalEquation(
                hwpScript,
                chemicalElementSequence,
                hasFormulaFontEvidence,
                hasEquation,
                hasOperator,
                hasBox,
                hasArrow,
                hasPositioned,
                hasChemicalSymbol);
    }

    private static Character previousVisibleChar(List<ASTInlineItem> items, int index) {
        for (int i = index; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text == null) continue;
            for (int j = text.length() - 1; j >= 0; j--) {
                char c = text.charAt(j);
                if (!isFormulaSpace(c)) return c;
            }
        }
        return null;
    }

    private static Character nextVisibleChar(List<ASTInlineItem> items, int index) {
        for (int i = index; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text == null) continue;
            for (int j = 0; j < text.length(); j++) {
                char c = text.charAt(j);
                if (!isFormulaSpace(c)) return c;
            }
        }
        return null;
    }

    private static boolean isOpeningFormulaDelimiter(Character c) {
        return c != null && (c == '(' || c == '[' || c == '{');
    }

    private static boolean isClosingFormulaDelimiter(Character c) {
        return c != null && (c == ')' || c == ']' || c == '}');
    }

    private static boolean isFormulaFontEvidence(ASTTextRun tr) {
        if (tr == null) return false;
        String ff = tr.fontFamily();
        return tr.grepMathFont()
                || (ff != null && (EHFontGlyphMap.isEHFontFamily(ff)
                || BTFontGlyphMap.isBTFontFamily(ff)
                || NPFontGlyphMap.isNPFont(ff)));
    }

    private static boolean isFormulaBoundaryText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasBoundary = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '\u2005' || c == '\u2007'
                    || c == '\u2009' || c == '\u200A') {
                continue;
            }
            if (c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == ',' || c == '.') {
                hasBoundary = true;
                continue;
            }
            return false;
        }
        return hasBoundary;
    }

    static void applyBodyTextEquationHints(ASTEquation equation, Integer preferredBaseUnit, String preferredFontFamily) {
        if (equation == null) return;
        if (preferredBaseUnit != null && preferredBaseUnit > 0) {
            equation.preferredBaseUnit(preferredBaseUnit);
        }
        if (preferredFontFamily != null && !preferredFontFamily.isEmpty()) {
            equation.preferredFontFamily(preferredFontFamily);
        }
    }

    private static class ScriptAppendResult {
        boolean accepted = true;
        boolean hasLetter;
        boolean hasDigit;
        boolean hasOperator;
        boolean hasBox;
        boolean hasArrow;
        boolean hasImplicitSubscript;
        boolean hasChemicalSymbol;
        char previousVisible;
    }

    private static ScriptAppendResult appendFormulaScript(
            StringBuilder out,
            String text,
            ASTTextRun run,
            char previousVisible) {
        ScriptAppendResult result = new ScriptAppendResult();
        result.previousVisible = previousVisible;
        boolean runSubscript = run.subscript();
        boolean runSuperscript = run.superscript();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            if (c == '\uFFFC' || c == '\u25A1') {
                out.append('\u25A1');
                result.hasBox = true;
                result.previousVisible = '\u25A1';
                continue;
            }
            if (c == '\u2192') {
                out.append(" rarrow ");
                result.hasArrow = true;
                result.hasOperator = true;
                result.previousVisible = c;
                continue;
            }
            if (c == '+' || c == '-' || c == '=') {
                out.append(c);
                result.hasOperator = true;
                result.previousVisible = c;
                continue;
            }
            if (isAsciiLetter(c)) {
                out.append(c);
                result.hasLetter = true;
                result.hasChemicalSymbol |= isLikelyChemicalElementAt(text, i);
                result.previousVisible = c;
                continue;
            }
            if (Character.isDigit(c)) {
                result.hasDigit = true;
                boolean subscript = runSubscript;
                boolean superscript = runSuperscript;
                if (subscript) {
                    out.append("_{").append(c).append("}");
                } else if (superscript) {
                    out.append("^{").append(c).append("}");
                } else {
                    out.append(c);
                }
                result.previousVisible = c;
                continue;
            }
            result.accepted = false;
            return result;
        }
        return result;
    }

    // toChemicalTextRuns 가 낱글자로 풀면 raw 노출되는 다글자 HWP 수식 키워드.
    // 이들이 스크립트에 있으면 화학식(원소기호+숫자)이 아니므로 CHEM_FORMULA
    // 낱글자 렌더 대상에서 제외한다. HWP 수식 예약어(EHFontEquationConverter
    // mapUnicodeToHwp 가 방출하는 것들)만 나열해 화학 원소기호(He/Na 등)와 혼동을
    // 피한다. 단어 경계로 검사(angle 이 triangle 등에 부분매칭되지 않게).
    private static final String[] NON_CHEMICAL_FORMULA_KEYWORDS = {
            "angle", "DEG", "TIMES", "div", "sqrt", "over",
            "LEQ", "GEQ", "INF", "rarrow", "equiv", "notin", "SUBSET"
    };

    /**
     * SPEC-055 Phase B: 일반 본문 폰트 + 첨자 문자속성으로 조판된 화학식 세그먼트를
     * ASTEquation("CHEM_FORMULA")으로 변환한다.
     *
     * <p>수식 폰트(BT/EH/NP) 증거가 없는 본문 속 H₂O·CO₂ 는 수식 그룹핑에 진입하지
     * 못하고 첨자 텍스트런으로 남는다. 여기서는 <b>아래첨자 숫자 런</b>을 앵커로,
     * 직전 런 꼬리의 원소기호와 후속 조각을 묶어 수식화한다. 보수 가드:
     * <ul>
     *   <li>앵커는 1~2자리 숫자 아래첨자 + 직전 문자가 원소기호일 때만</li>
     *   <li>세그먼트의 모든 라틴 문자열이 원소기호 화이트리스트에 있어야 함</li>
     *   <li>원소 직전 문자가 라틴 소문자면 제외 (pH2 같은 영문 꼬리 보호)</li>
     *   <li>수식 폰트(BT/EH/NP) 런은 기존 수식 파이프라인 소관 — 건드리지 않음</li>
     * </ul>
     */
    static void convertSubscriptChemicalSegments(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 2) return;
        for (int i = 1; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun sub = (ASTTextRun) item;
            if (!sub.subscript() || sub.superscript()) continue;
            String subText = sub.text();
            if (subText == null || !subText.matches("[0-9]{1,2}")) continue;
            if (!(items.get(i - 1) instanceof ASTTextRun)) continue;
            ASTTextRun base = (ASTTextRun) items.get(i - 1);
            if (base.subscript() || base.superscript()) continue;
            // 이탤릭 라틴은 수학 변수/기하 라벨(B_1 등) — 화학식으로 보지 않는다.
            if (base.fontStyle() != null && base.fontStyle().toLowerCase(java.util.Locale.ROOT).contains("italic")) continue;
            String baseText = base.text();
            if (baseText == null || baseText.isEmpty()) continue;

            // 직전 런 꼬리에서 원소 머리를 뗀다 ("물(H" → "H", "2H" → "2H").
            java.util.regex.Matcher m = TRAILING_CHEM_FRAGMENT.matcher(baseText);
            if (!m.find() || m.group().isEmpty()) continue;
            String head = m.group();
            if (head.endsWith("(")) head = head.substring(0, head.length() - 1);
            if (head.isEmpty() || !ChemicalFormulaPolicy.lettersAreKnownElements(head)) continue;
            String rest = baseText.substring(0, baseText.length() - m.group().length()
                    + (m.group().endsWith("(") ? 1 : 0));
            // 영문 단어 꼬리 보호: 원소 머리 직전이 라틴 문자면 화학식이 아니다.
            if (!rest.isEmpty()) {
                char before = rest.charAt(rest.length() - 1);
                if ((before >= 'a' && before <= 'z') || (before >= 'A' && before <= 'Z')) continue;
            }

            // 후속 조각 확장: 첨자 숫자 / 이온 전하 위첨자 / 원소·숫자 접두 일반 런.
            StringBuilder script = new StringBuilder(head);
            script.append("_{").append(subText).append('}');
            int end = i; // 마지막으로 흡수한 items 인덱스
            ASTTextRun tailPartialRun = null;
            String tailPartialRest = null;
            for (int j = i + 1; j < items.size(); j++) {
                if (!(items.get(j) instanceof ASTTextRun)) break;
                ASTTextRun r = (ASTTextRun) items.get(j);
                String t = r.text();
                if (t == null || t.isEmpty()) break;
                if (r.subscript() && t.matches("[0-9]{1,2}")) {
                    script.append("_{").append(t).append('}');
                    end = j;
                    continue;
                }
                if (r.superscript() && t.matches("[0-9]{0,2}[+−-]")) {
                    script.append("^{").append(t.replace('−', '-')).append('}');
                    end = j;
                    continue;
                }
                if (r.subscript() || r.superscript()) break;
                // 일반 런: 원소/숫자 접두만 흡수 (괄호·화살표·+ 는 Phase B 범위 밖)
                java.util.regex.Matcher p = LEADING_CHEM_CONTINUATION.matcher(t);
                if (!p.find() || p.group().isEmpty()) break;
                String prefix = p.group();
                if (!ChemicalFormulaPolicy.lettersAreKnownElements(prefix)
                        && !prefix.matches("[0-9]{1,2}")) {
                    break;
                }
                if (prefix.length() == t.length()) {
                    script.append(prefix);
                    end = j;
                    continue;
                }
                script.append(prefix);
                tailPartialRun = r;
                tailPartialRest = t.substring(prefix.length());
                end = j;
                break;
            }

            String rebuilt = kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier
                    .inferChemicalSubscriptScript(script.toString());
            if (rebuilt == null || rebuilt.isEmpty()) continue;

            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(rebuilt, "CHEM_FORMULA");
            if (base.fontSizeHwpunits() != null && base.fontSizeHwpunits() > 0) {
                eq.preferredBaseUnit(base.fontSizeHwpunits());
            }
            if (base.textColor() != null && !base.textColor().isEmpty()) {
                eq.textColor(base.textColor());
            }

            // items 반영: [base(rest)] [EQ] [tailPartial(rest)] — 흡수분 제거.
            if (tailPartialRun != null) tailPartialRun.text(tailPartialRest);
            int removeFrom = i;
            int removeTo = (tailPartialRun != null) ? end - 1 : end;
            for (int k = removeTo; k >= removeFrom; k--) items.remove(k);
            if (rest.isEmpty()) {
                items.set(i - 1, eq);
            } else {
                base.text(rest);
                items.add(i, eq);
            }
        }
    }

    private static final java.util.regex.Pattern LEADING_CHEM_CONTINUATION =
            java.util.regex.Pattern.compile("^(?:[0-9]{0,2}(?:[A-Z][a-z]?)+|[0-9]{1,2})");

    /** 스크립트가 화학식 연속부(첨자·계수·괄호)로 시작하는가 — 선행 원소가 잘려나간 표지. */
    private static boolean startsAsChemicalContinuation(String script) {
        if (script == null || script.isEmpty()) return false;
        char c = script.charAt(0);
        return Character.isDigit(c) || c == '_' || c == '(';
    }

    private static final java.util.regex.Pattern TRAILING_CHEM_FRAGMENT =
            java.util.regex.Pattern.compile(
                    "[0-9]{0,2}(?:[A-Z][a-z]?)+\\(?(?:\\+[0-9]{0,2}(?:[A-Z][a-z]?)+\\(?)*$");

    /** 직전 텍스트런 꼬리에서 화학식 선두 조각("H", "CH", "2Ca(")을 뗀다. 없으면 null. */
    private static String trailingChemicalFragment(String text) {
        if (text == null || text.isEmpty()) return null;
        java.util.regex.Matcher m = TRAILING_CHEM_FRAGMENT.matcher(text);
        if (!m.find()) return null;
        String frag = m.group();
        if (frag.isEmpty()) return null;
        if (!ChemicalFormulaPolicy.lettersAreKnownElements(frag)) return null;
        return frag;
    }

    /** 후행 런이 화학식 조각 문자만으로 이루어졌는가 (공백/숫자/연산/괄호/원소/화살표). */
    private static boolean isChemicalFragmentText(String text) {
        if (text == null) return false;
        // 빈/공백 런은 조각 사이 커넥터로 통과시킨다 (탭 정렬 반응식의 잔여 런).
        if (text.trim().isEmpty()) return true;
        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isDigit(c)
                    || c == '+' || c == '(' || c == ')' || c == '·' || c == '→') {
                continue;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                hasLetter = true;
                continue;
            }
            return false;
        }
        if (hasLetter && !ChemicalFormulaPolicy.lettersAreKnownElements(text)) return false;
        return true;
    }

    /** 이 조각이 흡수를 확정(commit)할 근거(원소기호 또는 화살표)를 갖는가. */
    private static boolean chemicalFragmentCommits(String text) {
        if (text == null) return false;
        return text.indexOf('→') >= 0 || ChemicalFormulaPolicy.containsElementText(text);
    }

    /** 화살표 치환 + 공백 정리("rarrow" 양쪽만 공백 유지) + 첨자 재추론. */
    private static String finalizeChemicalScript(String script) {
        if (script == null) return null;
        String s = script.replace("→", " rarrow ").replaceAll("\\s+", " ").trim();
        String[] parts = s.split("\\s*rarrow\\s*", -1);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) joined.append(" rarrow ");
            joined.append(parts[i].replace(" ", ""));
        }
        return kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier
                .inferChemicalSubscriptScript(joined.toString());
    }

    /**
     * SPEC-055: 화학식 수식 조각 봉합 + 스크립트 정규화.
     *
     * <p>수식 그룹핑은 수식 폰트/첨자 증거가 있는 런만 모으므로, 본문 폰트로 조판된
     * 화학식 선두 원소("CH₄"의 CH)나 화살표 뒤 조각이 텍스트런으로 남아 수식이
     * 쪼개진다("CH" + EQ["4+2O2 rarrow CO2+2H2O"]). 텍스트런 강등 시절에는 출력이
     * 이어 붙어 가려졌지만 hp:equation 방출(SPEC-055)에서는 조각이 그대로 보인다.
     * 인접 조각을 수식 스크립트로 흡수하고, 평문화된 첨자("N2")를 명시 토큰으로
     * 재추론한다. 흡수는 원소기호/화살표 근거가 있는 지점까지만 커밋한다
     * (화학식 뒤 무관한 숫자·괄호를 삼키지 않도록).
     */
    static void stitchChemicalFormulaFragments(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
            if (!"CHEM_FORMULA".equals(eq.sourceType())) continue;
            String script = eq.hwpScript() == null ? "" : eq.hwpScript();

            // (b) 후행 조각 — 원소/화살표 근거가 나오는 마지막 지점까지 수집
            int commitEnd = -1;
            java.util.List<String> tailTexts = new java.util.ArrayList<>();
            for (int j = i + 1; j < items.size(); j++) {
                ASTInlineItem cand = items.get(j);
                String candText;
                boolean commits;
                if (cand instanceof ASTTextRun) {
                    candText = ((ASTTextRun) cand).text();
                    if (!isChemicalFragmentText(candText)) break;
                    commits = chemicalFragmentCommits(candText);
                } else if (cand instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation
                        && "CHEM_FORMULA".equals(
                        ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) cand).sourceType())) {
                    candText = ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) cand).hwpScript();
                    commits = true;
                } else {
                    break;
                }
                tailTexts.add(candText == null ? "" : candText);
                if (commits) commitEnd = j;
            }

            // (a) 선행 조각 체인 — 빈/공백 런은 건너뛰고 [원소 조각] → [계수] 순으로 흡수.
            //     "2" | "Cu+O" | EQ["2 rarrow 2CuO"] → "2Cu+O" 까지 복원한다.
            boolean arrowEvidence =
                    (script + " " + String.join(" ", tailTexts)).contains("rarrow")
                    || (script + String.join("", tailTexts)).indexOf('→') >= 0;
            String prevFragment = null;
            ASTTextRun prevPartialRun = null;
            String prevPartialRest = null;
            java.util.List<Integer> prevRemoveIdx = new java.util.ArrayList<>();
            int p = i - 1;
            while (p >= 0 && items.get(p) instanceof ASTTextRun) {
                String t = ((ASTTextRun) items.get(p)).text();
                if (t != null && !t.trim().isEmpty()) break;
                p--;
            }
            if (p >= 0 && items.get(p) instanceof ASTTextRun) {
                ASTTextRun r1 = (ASTTextRun) items.get(p);
                String t1 = r1.text();
                String head = script;
                int coefficientProbe = -1;
                if (startsAsChemicalContinuation(script)) {
                    // 스크립트가 숫자/첨자/괄호로 시작 = 선행 원소가 잘렸다 ("CH"+"4+…").
                    String frag = trailingChemicalFragment(t1);
                    if (frag != null) {
                        prevFragment = frag;
                        head = frag;
                        String rest = t1.substring(0, t1.length() - frag.length());
                        if (rest.trim().isEmpty()) {
                            prevRemoveIdx.add(p);
                            coefficientProbe = p - 1;
                        } else {
                            prevPartialRun = r1;
                            prevPartialRest = rest;
                        }
                    }
                } else {
                    coefficientProbe = p;
                }
                // 계수 흡수: 머리가 원소로 시작하는 반응식 앞의 단독 숫자 런.
                // 런 전체가 숫자일 때만 — "실험 2" 같은 서술 꼬리는 흡수하지 않는다.
                if (arrowEvidence && coefficientProbe >= 0
                        && !head.isEmpty() && head.charAt(0) >= 'A' && head.charAt(0) <= 'Z') {
                    int p2 = coefficientProbe;
                    while (p2 >= 0 && items.get(p2) instanceof ASTTextRun) {
                        String t2 = ((ASTTextRun) items.get(p2)).text();
                        if (t2 != null && !t2.trim().isEmpty()) break;
                        p2--;
                    }
                    if (p2 >= 0 && items.get(p2) instanceof ASTTextRun) {
                        String t2 = ((ASTTextRun) items.get(p2)).text();
                        if (t2 != null && t2.matches("[0-9]{1,2}")) {
                            prevFragment = t2 + (prevFragment == null ? "" : prevFragment);
                            prevRemoveIdx.add(p2);
                        }
                    }
                }
            }

            // 최종 스크립트를 먼저 만들고 검증 통과 시에만 items 를 변경한다.
            StringBuilder merged = new StringBuilder();
            if (prevFragment != null) merged.append(prevFragment);
            merged.append(script);
            int tailCount = commitEnd - i;
            for (int t = 0; t < tailCount; t++) merged.append(' ').append(tailTexts.get(t));
            String rebuilt = finalizeChemicalScript(merged.toString());
            if (rebuilt == null || rebuilt.isEmpty()
                    || containsNonChemicalFormulaKeyword(stripRarrowKeyword(rebuilt))) {
                continue;
            }

            // 후행 제거를 먼저 (i 이후 인덱스는 선행 제거의 영향을 받지 않도록)
            for (int t = 0; t < tailCount; t++) {
                items.remove(i + 1);
            }
            if (prevPartialRun != null && prevPartialRest != null) {
                prevPartialRun.text(prevPartialRest);
            }
            java.util.Collections.sort(prevRemoveIdx, java.util.Collections.reverseOrder());
            for (int idx : prevRemoveIdx) {
                items.remove(idx);
                i--;
            }
            eq.hwpScript(rebuilt);
        }
    }

    private static String stripRarrowKeyword(String script) {
        return script == null ? null : script.replace("rarrow", " ");
    }

    private static boolean containsNonChemicalFormulaKeyword(String script) {
        if (script == null || script.isEmpty()) return false;
        for (String kw : NON_CHEMICAL_FORMULA_KEYWORDS) {
            int from = 0;
            while (true) {
                int idx = script.indexOf(kw, from);
                if (idx < 0) break;
                char before = idx > 0 ? script.charAt(idx - 1) : ' ';
                int after = idx + kw.length();
                char next = after < script.length() ? script.charAt(after) : ' ';
                // 앞뒤가 라틴 문자가 아니면(공백/연산자/숫자/경계) 독립 키워드로 인정
                if (!isAsciiLetter(before) && !isAsciiLetter(next)) {
                    return true;
                }
                from = idx + 1;
            }
        }
        return false;
    }

    private static boolean isFormulaClusterText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c)) continue;
            if (isAsciiLetter(c) || Character.isDigit(c)) continue;
            if (c == '\uFFFC' || c == '\u25A1' || c == '\u2192') continue;
            if (c == '+' || c == '-' || c == '=') continue;
            return false;
        }
        return true;
    }

    private static boolean hasLongLatinWord(String text, int minLen) {
        if (text == null) return false;
        int streak = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiLetter(c)) {
                streak++;
                if (streak >= minLen) return true;
            } else {
                streak = 0;
            }
        }
        return false;
    }

    private static String normalizeFormulaScript(String script) {
        if (script == null) return "";
        String normalized = script.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("\\s*\\+\\s*", "+");
        normalized = normalized.replaceAll("\\s*=\\s*", "=");
        normalized = normalized.replaceAll("(?i)\\s*RIGHT\\s*", " rarrow ");
        normalized = normalized.replaceAll("(?i)\\s*RARROW\\s*", " rarrow ");
        normalized = normalized.replaceAll("\\s*->\\s*", " rarrow ");
        if (isChemicalFormulaElementSequence(normalized)) {
            normalized = normalized.replace(" ", "");
            if (normalized.indexOf('_') < 0 && normalized.indexOf('^') < 0) {
                normalized = withImplicitChemicalSubscripts(normalized);
            }
        }
        return normalized.trim();
    }

    private static boolean isChemicalFormulaElementSequence(String script) {
        String token = chemicalFormulaToken(script);
        if (token.isEmpty()) return false;
        int i = 0;
        int elements = 0;
        boolean hasDigit = false;
        boolean hasLeadingCoefficient = false;
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            hasLeadingCoefficient = true;
            hasDigit = true;
            i++;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c < 'A' || c > 'Z') return false;
            String symbol = String.valueOf(c);
            if (i + 1 < token.length()) {
                char next = token.charAt(i + 1);
                if (next >= 'a' && next <= 'z') {
                    String two = "" + c + next;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        i += 2;
                    } else if (isChemicalElement(symbol)) {
                        i++;
                    } else {
                        return false;
                    }
                } else {
                    if (!isChemicalElement(symbol)) return false;
                    i++;
                }
            } else {
                if (!isChemicalElement(symbol)) return false;
                i++;
            }
            elements++;
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                hasDigit = true;
                i++;
            }
        }
        if (elements == 0) return false;
        return hasDigit || hasLeadingCoefficient || elements >= 2;
    }

    private static String chemicalFormulaToken(String script) {
        if (script == null || script.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '_' || c == '^' || c == '{' || c == '}') continue;
            if (Character.isDigit(c) || isAsciiLetter(c)) {
                out.append(c);
                continue;
            }
            return "";
        }
        return out.toString();
    }

    private static boolean containsFormulaArrow(String script) {
        return script != null
                && (script.contains("->")
                || script.contains("RIGHT")
                || script.contains("RARROW")
                || script.contains("rarrow"));
    }

    private static boolean isFormulaSpace(char c) {
        return Character.isWhitespace(c)
                || c == '\u2005' || c == '\u2007' || c == '\u2009' || c == '\u200A';
    }

    private static boolean isLikelyChemicalElementAt(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) return false;
        char first = text.charAt(index);
        if (first < 'A' || first > 'Z') return false;
        String one = String.valueOf(first);
        String two = one;
        if (index + 1 < text.length()) {
            char second = text.charAt(index + 1);
            if (second >= 'a' && second <= 'z') {
                two = "" + first + second;
            }
        }
        return isChemicalElement(one) || isChemicalElement(two);
    }

    private static boolean isChemicalElement(String symbol) {
        if (symbol == null) return false;
        switch (symbol) {
            case "H": case "He": case "Li": case "Be": case "B": case "C":
            case "N": case "O": case "F": case "Ne": case "Na": case "Mg":
            case "Al": case "Si": case "P": case "S": case "Cl": case "Ar":
            case "K": case "Ca": case "Fe": case "Cu": case "Zn": case "Ag":
            case "I": case "Ba": case "Pt": case "Au": case "Hg": case "Pb":
                return true;
            default:
                return false;
        }
    }

    /**
     * 이 텍스트가 수식처럼 보이는가? (수식 구조 문자를 담고 있는가)
     *
     * <p>"BT수식H" 계열 폰트 런이 수식 그룹에 들어갈지 판단하는 데 쓴다. 그 폰트는
     * 본문 영문/숫자 조판용이라, 내용을 봐야 수식인지 알 수 있다:
     * <pre>
     *   "H", "O", "1.", "2", ":"   → 평범한 텍스트 (수식 아님)
     *   "x+1", "a^2", "n_1"        → 수식
     * </pre>
     */
    private static boolean looksLikeMathContent(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '+' || c == '=' || c == '<' || c == '>' || c == '^' || c == '_'
                    || c == '√'   // √
                    || c == '∑'   // ∑
                    || c == '∫'   // ∫
                    || c == 'π'   // π
                    || c == '∞'   // ∞
                    || c == '≤' || c == '≥'   // ≤ ≥
                    || c == '±'                     // ±
                    || c == '×' || c == '÷'   // × ÷
                    || c == '²' || c == '³') { // ² ³
                return true;
            }
        }
        return BTFontEquationConverter.containsGreekKeyword(text);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static IDMLCharacterRun mathRunFromTextRun(ASTTextRun tr, String fontFamily) {
        IDMLCharacterRun cr = new IDMLCharacterRun();
        cr.content(tr.text());
        cr.fontFamily(fontFamily);
        if (tr.subscript()) {
            cr.position("Subscript");
        } else if (tr.superscript()) {
            cr.position("Superscript");
        }
        return cr;
    }

    private static void applyPositionFromCharacterStyle(ASTTextRun tr) {
        if (tr == null || tr.characterStyleRef() == null) return;
        String style = tr.characterStyleRef().toLowerCase(Locale.ROOT)
                .replace("%3a", ":")
                .replace("%25", "%");
        // "정체"(正體=똑바로 선 글자, 정상 위치)는 EH상부자/하부자 폰트를 쓰되 첨자
        // 위치가 아니라는 조판 표기다. 스타일 이름의 "상부자/하부자"만 보고 첨자화하면
        // 본문 라틴 주석이 첨자로 깨진다(실측: 1단원 "상부자(정체)" 스타일의
        // Pythagoras·B.C.569?~475? 가 위첨자화). "정체"면 첨자로 만들지 않는다.
        if (style.contains("정체") || style.contains("정자")) return;
        boolean sup = style.contains("superscript") || style.contains("상부자") || style.contains("위첨자");
        boolean sub = style.contains("subscript") || style.contains("하부자") || style.contains("아래첨자");
        if (!sup && !sub) return;
        // SPEC-053: IDML/resolved 가 position 을 명시적으로 NORMAL(첨자 아님)로 보고했으면
        // charStyle 폰트명이 "상부자"여도 첨자화하지 않는다. 상부자 폰트로 조판한 도형
        // 라벨(원 O, □ABCD)이 위첨자로 깨지던 문제(실측: u5 p174).
        if (tr.explicitNormalPosition()) return;
        // SPEC-053: charStyle 이름의 "상부자/하부자"만으론 첨자화하지 않는다. 이 이름은
        // "EH상부자 폰트로 조판"을 뜻할 뿐 반드시 위/아래첨자 위치는 아니다. 도형 라벨
        // (원 O, □ABCD)이나 한글 캡션(확산하기)까지 폰트명만 보고 위첨자화하면 깨진다
        // (실측: u5 p174 원 O·□ABCD, "확산하기"·"사고"). 진짜 첨자는 화학식/지수처럼
        // 숫자·1~2 라틴 글자의 짧은 토큰이다. 그 외(한글·3+ 라틴·기호)는 첨자로 보지
        // 않는다. 실제 SUPERSCRIPT/SUBSCRIPT position 은 상류 applyPositionStyle 이 이미
        // 처리했으므로 이 이름-폴백 축소가 진짜 첨자를 놓치지 않는다.
        if (!isPlausibleScriptToken(tr.text())) return;
        if (sup) {
            tr.superscript(true);
            tr.subscript(false);
        } else {
            tr.subscript(true);
            tr.superscript(false);
        }
    }

    /** 첨자에 타당한 짧은 토큰(숫자·1~2 라틴 글자, 지수/화학식 계수)인가. */
    private static boolean isPlausibleScriptToken(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 2) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '+' || c == '-' || c == '(' || c == ')';
            if (!ok) return false;
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
     * SPEC-042: flush 후, 어댑터 변환으로 손실된 원본 런 스타일(크기·굵기·첨자)을
     * 결과 텍스트런에 백필한다. 어댑터(IDMLCharacterRun)에 직접 실으면 그룹핑
     * 하류 경로가 바뀌어 첨자 회귀가 나므로(SPEC-042 실패 기록), flush 산출물에만
     * 비어 있는 속성을 채운다. 매칭은 순서 보존 텍스트 일치(1:1)로, 분절·병합된
     * 산출물(수식 스크립트 등)은 건드리지 않는다.
     */
    private static void flushResolvedMathGroupWithBackfill(ResolvedBuildContext ctx, List<IDMLCharacterRun> group,
                                         List<ASTTextRun> sources, String type,
                                         List<ASTInlineItem> out, ASTParagraph ignoredPara) {
        if (group == null || group.isEmpty()) return;
        ASTParagraph tempPara = new ASTParagraph();
        if ("EH".equals(type)) {
            ASTMathGrouper.flushEHMathGroup(group, tempPara);
        } else if ("BT".equals(type)) {
            ASTMathGrouper.flushMathGroup(group, tempPara);
        } else if ("NP".equals(type)) {
            ASTMathGrouper.flushNPMathGroup(group, tempPara);
        }
        // SPEC-042: 폴백이 소스와 1:1 동일한 평문 런만 냈다면 손실 복제본 대신
        // 원본 런(크기·굵기·색·첨자 보유)을 재사용한다. 텍스트 정리·디코딩이
        // 일어난 경우(텍스트 불일치)나 수식·분절 산출이 있으면 폴백 산출을 쓴다.
        if (reuseOriginalRunsForPlainFallback(tempPara.items(), sources, out)) {
            return;
        }
        backfillFlushedTextRunStyles(tempPara.items(), sources);
        backfillChemicalEquationStyleHints(tempPara.items(), sources);
        out.addAll(tempPara.items());
    }

    private static boolean reuseOriginalRunsForPlainFallback(
            List<ASTInlineItem> emitted, List<ASTTextRun> sources, List<ASTInlineItem> out) {
        if (emitted == null || sources == null || emitted.isEmpty()) return false;
        if (emitted.size() != sources.size()) return false;
        for (int i = 0; i < emitted.size(); i++) {
            ASTInlineItem item = emitted.get(i);
            ASTTextRun src = sources.get(i);
            if (!(item instanceof ASTTextRun) || src == null) return false;
            ASTTextRun run = (ASTTextRun) item;
            if (!flushedTextMatches(src, run)) return false;
            String ff = run.fontFamily();
            if (ff != null && src.fontFamily() != null && !ff.equals(src.fontFamily())) return false;
        }
        out.addAll(sources);
        return true;
    }

    /**
     * SPEC-042: CHEM_FORMULA 수식(스크립트 재해석으로 텍스트런이 되는 화학식)의
     * 스타일 힌트(크기·색)를 원본 런에서 채운다. FormulaStyleResolver 가
     * preferredBaseUnit/textColor 를 읽어 FormulaRenderer 텍스트런에 적용한다.
     * 어댑터(IDMLCharacterRun)는 건드리지 않으므로 그룹핑 분기는 불변.
     */
    private static void backfillChemicalEquationStyleHints(
            List<ASTInlineItem> emitted, List<ASTTextRun> sources) {
        if (emitted == null || sources == null || sources.isEmpty()) return;
        Integer size = null;
        String color = null;
        for (ASTTextRun src : sources) {
            if (src == null) continue;
            if (size == null && src.fontSizeHwpunits() != null && src.fontSizeHwpunits() > 0) {
                size = src.fontSizeHwpunits();
            }
            if (color == null && src.textColor() != null && !src.textColor().isEmpty()) {
                color = src.textColor();
            }
            if (size != null && color != null) break;
        }
        if (size == null && color == null) return;
        for (ASTInlineItem item : emitted) {
            if (!(item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
            if (!"CHEM_FORMULA".equals(eq.sourceType())) continue;
            if (size != null && eq.preferredBaseUnit() == null) eq.preferredBaseUnit(size);
            if (color != null && (eq.textColor() == null || eq.textColor().isEmpty())) {
                eq.textColor(color);
            }
        }
    }

    private static void backfillFlushedTextRunStyles(List<ASTInlineItem> emitted, List<ASTTextRun> sources) {
        if (emitted == null || sources == null || sources.isEmpty()) return;
        int s = 0;
        for (ASTInlineItem item : emitted) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun outRun = (ASTTextRun) item;
            int k = s;
            while (k < sources.size() && !flushedTextMatches(sources.get(k), outRun)) k++;
            if (k >= sources.size()) continue;
            ASTTextRun src = sources.get(k);
            s = k + 1;
            // 짧은 숫자 런(첨자 후보 — H₂의 2)은 백필하지 않는다. 첨자 판정이
            // 위치 기반이라 숫자 런에 속성을 선주입하면 재그룹핑·재해석 시 첨자가
            // 유실된다 (SPEC-042 실패 기록 3·4·5 — 계수만 백필해도 같은 문단의
            // 첨자가 깨지는 양방향 상호작용 실측). 숫자 런은 전부 제외한다.
            String outText = outRun.text() == null ? "" : outRun.text().trim();
            if (outText.matches("\\d{1,2}")) continue;
            if (outRun.fontSizeHwpunits() == null && src.fontSizeHwpunits() != null) {
                outRun.fontSizeHwpunits(src.fontSizeHwpunits());
            }
            if (outRun.fontStyle() == null && src.fontStyle() != null) {
                outRun.fontStyle(src.fontStyle());
            }
            if (!outRun.subscript() && !outRun.superscript()) {
                if (src.subscript()) outRun.subscript(true);
                else if (src.superscript()) outRun.superscript(true);
            }
        }
    }

    private static boolean flushedTextMatches(ASTTextRun src, ASTTextRun out) {
        String a = src != null ? src.text() : null;
        String b = out != null ? out.text() : null;
        if (a == null || b == null) return false;
        String at = a.trim();
        return !at.isEmpty() && at.equals(b.trim());
    }

    private static void flushResolvedMathGroup(ResolvedBuildContext ctx, List<IDMLCharacterRun> group, String type,
                                         List<ASTInlineItem> out, ASTParagraph ignoredPara) {
        if (group == null || group.isEmpty()) return;
        // flush 메서드는 para에 직접 추가하므로, 임시 para를 사용하여 결과를 꺼냄
        ASTParagraph tempPara = new ASTParagraph();
        if ("EH".equals(type)) {
            ASTMathGrouper.flushEHMathGroup(group, tempPara);
        } else if ("BT".equals(type)) {
            ASTMathGrouper.flushMathGroup(group, tempPara);
        } else if ("NP".equals(type)) {
            ASTMathGrouper.flushNPMathGroup(group, tempPara);
        }
        out.addAll(tempPara.items());
    }

    /**
     * 수식 그룹 flush 헬퍼: null이 아닌 그룹만 flush하고 clear.
     */
    static void flushMathGroups(ResolvedBuildContext ctx, List<IDMLCharacterRun> btGroup,
                                  List<IDMLCharacterRun> npGroup,
                                  List<IDMLCharacterRun> ehGroup,
                                  ASTParagraph para) {
        // SPEC-042: IDML FillColor 참조를 hex 로 푸는 리졸버 — 폴백 텍스트런 색 보존
        java.util.function.Function<String, String> colorToHex = ctx == null
                ? null
                : (color -> RunBuilder.resolveColorToHex(ctx, color));
        if (btGroup != null && !btGroup.isEmpty()) {
            String groupColor = firstGroupFillColorHex(ctx, btGroup);
            int before = para.items().size();
            ASTMathGrouper.flushMathGroup(btGroup, para, colorToHex);
            applyGroupColorHintToNewChemEquations(para, before, groupColor);
            btGroup.clear();
        }
        if (npGroup != null && !npGroup.isEmpty()) {
            ASTMathGrouper.flushNPMathGroup(npGroup, para);
            npGroup.clear();
        }
        if (ehGroup != null && !ehGroup.isEmpty()) {
            String groupColor = firstGroupFillColorHex(ctx, ehGroup);
            int before = para.items().size();
            ASTMathGrouper.flushEHMathGroup(ehGroup, para, colorToHex);
            applyGroupColorHintToNewChemEquations(para, before, groupColor);
            ehGroup.clear();
        }
    }

    /** SPEC-042: 그룹 첫 IDML FillColor 를 hex 로 (화학식 강조색 힌트용). */
    private static String firstGroupFillColorHex(ResolvedBuildContext ctx, List<IDMLCharacterRun> group) {
        if (ctx == null || group == null) return null;
        for (IDMLCharacterRun run : group) {
            if (run == null || run.fillColor() == null) continue;
            String hex = RunBuilder.resolveColorToHex(ctx, run.fillColor());
            if (hex != null && !hex.isEmpty()) return hex;
        }
        return null;
    }

    /**
     * SPEC-042: flush 가 새로 만든 CHEM_FORMULA 수식에 그룹 색 힌트를 주입.
     * 다이얼로그처럼 화살표 증거로 수식으로 직행하는 경우 폴백 텍스트런 색 주입이
     * 닿지 않으므로, FormulaRenderer 가 소비할 textColor 힌트를 여기서 채운다.
     */
    private static void applyGroupColorHintToNewChemEquations(ASTParagraph para, int fromIndex, String colorHex) {
        if (para == null || para.items() == null || colorHex == null || colorHex.isEmpty()) return;
        for (int i = Math.max(0, fromIndex); i < para.items().size(); i++) {
            ASTInlineItem item = para.items().get(i);
            if (!(item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
            if (!"CHEM_FORMULA".equals(eq.sourceType())) continue;
            if (eq.textColor() == null || eq.textColor().isEmpty()) {
                eq.textColor(colorHex);
            }
        }
    }

    /**
     * 텍스트 내 ;...; 분수 GREP 패턴을 인라인 수식(ASTEquation)으로 분리.
     * 예: "이므로 ;4!;의 제곱근은" → "이므로 " + ASTEquation({1} over {4}) + "의 제곱근은"
     */
    static void splitFractionPatternInText(ResolvedBuildContext ctx, String text, ASTTextRun templateRun, ASTParagraph para) {
        for (EHGrepFractionConverter.Segment seg : EHGrepFractionConverter.splitAndConvert(text)) {
            if (seg.type() == EHGrepFractionConverter.Segment.Type.EQUATION) {
                para.addItem(new ASTEquation(seg.content(), "EH_FONT"));
            } else {
                ASTTextRun tr = new ASTTextRun();
                tr.text(seg.content());
                tr.fontFamily(templateRun.fontFamily());
                tr.fontStyle(templateRun.fontStyle());
                tr.fontSizeHwpunits(templateRun.fontSizeHwpunits());
                tr.textColor(templateRun.textColor());
                tr.shadeColor(templateRun.shadeColor());
                para.addItem(tr);
            }
        }
    }

    /**
     * EH 그룹이 열려있고 마지막이 EH분수대문자(√)일 때,
     * 바로 뒤의 짧은 비EH 런이 루트 내용(radicand)인지 판단.
     * GREP 스타일이 IDML에 반영되지 않아 fontFamily=null인 런도 포함.
     */
    static boolean isEHSqrtContent(IDMLCharacterRun run,
                                            List<IDMLCharacterRun> ehGroup) {
        if (ehGroup.isEmpty()) return false;
        // 마지막 EH 런이 분수대문자(√)인지
        IDMLCharacterRun last = ehGroup.get(ehGroup.size() - 1);
        if (!EHFontGlyphMap.isFractionNumeratorFont(last.fontFamily())) return false;
        // 현재 런이 짧은 라틴/수학 텍스트인지 (한국어만으로 시작하면 제외)
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        // 첫 문자가 라틴 알파벳, 숫자, 수학 기호이면 루트 내용
        return Character.isLetterOrDigit(first)
                && !(first >= 0xAC00 && first <= 0xD7AF)
                && !(first >= 0x3131 && first <= 0x318E);
    }
}
