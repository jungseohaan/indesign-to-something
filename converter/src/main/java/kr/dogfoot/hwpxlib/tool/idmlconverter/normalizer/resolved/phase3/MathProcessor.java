package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHGrepFractionConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
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
            return;
        }

        List<ASTInlineItem> newItems = new ArrayList<>();
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        String mathType = null; // "EH", "BT", "NP"

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                mathGroup.clear();
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
                flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                mathGroup.clear();
                mathType = null;
                newItems.add(formulaCluster.equation);
                i = formulaCluster.endExclusive - 1;
                continue;
            }

            if (currentType != null && isSimplePositionedTextRun(tr)) {
                flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                mathGroup.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            if (currentType != null) {
                if (mathType == null || mathType.equals(currentType)) {
                    mathType = currentType;
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, ff);
                    mathGroup.add(cr);
                } else {
                    flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = currentType;
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, ff);
                    mathGroup.add(cr);
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
                } else {
                    flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = null;
                    newItems.add(item);
                }
            }
        }
        flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);

        if (newItems.size() != items.size() || !newItems.equals(items)) {
            items.clear();
            items.addAll(newItems);
        }
        collapseMixedFormulaEquationClusters(ctx, para);
    }

    private static void collapseMixedFormulaEquationClusters(ResolvedBuildContext ctx, ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

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
                end = i + 1;
                continue;
            }

            if (item instanceof ASTEquation) {
                ASTEquation eq = (ASTEquation) item;
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

        if (end - start < 2) return null;
        String hwpScript = normalizeFormulaScript(script.toString());
        if (hwpScript.isEmpty() || hwpScript.length() > 160) return null;
        if (!hasLetter) return null;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return null;
        if (!hasEquation && !hasBox) return null;
        if (!hasDigit && !hasOperator && !hasBox && !hasArrow && !hasPositioned) return null;

        ASTEquation eq = new ASTEquation(hwpScript, "CHEM_FORMULA");
        if (color != null) eq.textColor(color);
        applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
        return new FormulaCluster(eq, end);
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
            end = i + 1;
        }

        if (end == start) return null;
        String hwpScript = normalizeFormulaScript(script.toString());
        if (hwpScript.isEmpty() || hwpScript.length() > 128) return null;
        if (!hasLetter) return null;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return null;
        if (!hasDigit && !hasOperator && !hasBox && !hasArrow && !hasPositioned) return null;

        ASTEquation eq = new ASTEquation(hwpScript, "CHEM_FORMULA");
        if (color != null) eq.textColor(color);
        applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
        return new FormulaCluster(eq, end);
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

    private static String normalizeFormulaScript(String script) {
        if (script == null) return "";
        String normalized = script.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("\\s*\\+\\s*", "+");
        normalized = normalized.replaceAll("\\s*=\\s*", "=");
        normalized = normalized.replaceAll("(?i)\\s*RIGHT\\s*", " rarrow ");
        normalized = normalized.replaceAll("(?i)\\s*RARROW\\s*", " rarrow ");
        normalized = normalized.replaceAll("\\s*->\\s*", " rarrow ");
        return normalized.trim();
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

    private static boolean isChemicalFormulaTextRun(
            List<ASTInlineItem> items,
            int index,
            ASTTextRun run,
            String currentMathType) {
        if (run == null) return false;
        String text = run.text();
        if (text == null || text.trim().isEmpty()) return false;
        if (!isFormulaAlphabetDigitText(text)) return false;
        if (currentMathType != null || run.grepMathFont()) return true;
        char prev = previousVisibleChar(items, index);
        return containsChemicalSubscriptDigit(text, prev);
    }

    private static boolean isFormulaAlphabetDigitText(String text) {
        if (text == null) return false;
        boolean hasAsciiLetter = false;
        boolean hasDigit = false;
        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u2005' || c == '\u2007'
                    || c == '\u2009' || c == '\u200A') {
                continue;
            }
            visible++;
            if (isAsciiLetter(c)) {
                hasAsciiLetter = true;
                continue;
            }
            if (Character.isDigit(c)) {
                hasDigit = true;
                continue;
            }
            if (c == '+' || c == '-' || c == '\u2192') continue;
            return false;
        }
        return hasAsciiLetter && hasDigit && visible <= 32;
    }

    private static boolean containsChemicalSubscriptDigit(String text, char previous) {
        char prev = previous;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u2005' || c == '\u2007'
                    || c == '\u2009' || c == '\u200A') {
                continue;
            }
            if (Character.isDigit(c) && isAsciiLetter(prev)) return true;
            prev = c;
        }
        return false;
    }

    private static void addChemicalFormulaTextRuns(List<ASTInlineItem> out, ASTTextRun source) {
        String text = source.text();
        if (text == null || text.isEmpty()) return;

        StringBuilder buf = new StringBuilder();
        boolean bufSubscript = false;
        char prev = previousVisibleChar(out, out.size());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean subscriptDigit = Character.isDigit(c) && isAsciiLetter(prev);
            if (buf.length() > 0 && bufSubscript != subscriptDigit) {
                out.add(copyTextRun(source, buf.toString(), bufSubscript));
                buf.setLength(0);
            }
            buf.append(c);
            if (!Character.isWhitespace(c) && c != '\u2005' && c != '\u2007'
                    && c != '\u2009' && c != '\u200A') {
                prev = c;
            }
            bufSubscript = subscriptDigit;
        }
        if (buf.length() > 0) {
            out.add(copyTextRun(source, buf.toString(), bufSubscript));
        }
    }

    private static ASTTextRun copyTextRun(ASTTextRun source, String text, boolean subscript) {
        ASTTextRun copy = new ASTTextRun();
        copy.characterStyleRef(source.characterStyleRef());
        copy.text(text);
        copy.fontFamily(source.fontFamily());
        copy.fontStyle(source.fontStyle());
        copy.fontSizeHwpunits(source.fontSizeHwpunits());
        copy.textColor(source.textColor());
        copy.shadeColor(source.shadeColor());
        copy.letterSpacing(source.letterSpacing());
        copy.subscript(subscript || source.subscript());
        copy.superscript(!subscript && source.superscript());
        copy.grepMathFont(source.grepMathFont());
        copy.underline(source.underline());
        copy.underlineColor(source.underlineColor());
        copy.underlineShape(source.underlineShape());
        copy.strikeThrough(source.strikeThrough());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.grepStyleApplied(source.grepStyleApplied());
        return copy;
    }

    private static char previousVisibleChar(List<ASTInlineItem> items, int beforeIndex) {
        if (items == null) return 0;
        int start = Math.min(beforeIndex - 1, items.size() - 1);
        for (int i = start; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text == null) continue;
            for (int j = text.length() - 1; j >= 0; j--) {
                char c = text.charAt(j);
                if (!Character.isWhitespace(c) && c != '\u2005' && c != '\u2007'
                        && c != '\u2009' && c != '\u200A') {
                    return c;
                }
            }
        }
        return 0;
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
        if (style.contains("superscript") || style.contains("상부자") || style.contains("위첨자")) {
            tr.superscript(true);
            tr.subscript(false);
        } else if (style.contains("subscript") || style.contains("하부자") || style.contains("아래첨자")) {
            tr.subscript(true);
            tr.superscript(false);
        }
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
        if (btGroup != null && !btGroup.isEmpty()) {
            ASTMathGrouper.flushMathGroup(btGroup, para);
            btGroup.clear();
        }
        if (npGroup != null && !npGroup.isEmpty()) {
            ASTMathGrouper.flushNPMathGroup(npGroup, para);
            npGroup.clear();
        }
        if (ehGroup != null && !ehGroup.isEmpty()) {
            ASTMathGrouper.flushEHMathGroup(ehGroup, para);
            ehGroup.clear();
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
