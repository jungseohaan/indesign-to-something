package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBreak;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math.MathSpanPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math.MathSpanPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MathProcessorTest {
    @Test
    public void radicalBraceIsClosedBeforeItsOuterParenthesis() {
        Assert.assertEquals(
                "sqrt{(-9)^{2}}+left(-sqrt{13} right)^{2}",
                MathProcessor.repairCrossingFormulaDelimiters(
                        "sqrt{(-9)^{2}}+left(-sqrt{13 right)^{2}}"));
    }

    @Test
    public void crossingDelimiterRepairIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation equation = new ASTEquation(
                "sqrt{(-9)^{2}}+left(-sqrt{13 right)^{2}}",
                "EH_FONT");
        para.addItem(equation);

        java.util.List<MathProcessor.EquationStitchPlan> plans =
                MathProcessor.planCrossingDelimiterRepairs(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(0, plans.get(0).startInclusive);
        Assert.assertEquals(1, plans.get(0).endExclusive);
        Assert.assertEquals(
                "crossing-formula-delimiter-order",
                plans.get(0).reason);
        Assert.assertEquals(
                "sqrt{(-9)^{2}}+left(-sqrt{13} right)^{2}",
                plans.get(0).mergedScript);
        Assert.assertEquals(
                "sqrt{(-9)^{2}}+left(-sqrt{13 right)^{2}}",
                equation.hwpScript());

        MathProcessor.materializeEquationStitchPlans(para.items(), plans);
        Assert.assertEquals(
                "sqrt{(-9)^{2}}+left(-sqrt{13} right)^{2}",
                equation.hwpScript());
    }

    @Test
    public void normalLatinTextWithHyphenDoesNotBecomeFormulaEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("Self-Discovery and Growth");
        run.fontFamily("Helvetica Neue LT Std");
        run.fontStyle("55 Roman");
        para.addItem(run);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertEquals("Self-Discovery and Growth", ((ASTTextRun) para.items().get(0)).text());
    }

    @Test
    public void formulaFontEvidenceMayStillProduceEquation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("C□");
        run.fontFamily("BT수식M");
        run.grepMathFont(true);
        para.addItem(run);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertTrue(para.items().stream().anyMatch(it -> it instanceof ASTEquation));
    }

    @Test
    public void inlineFractionEquationIsNotCollapsedWithBodyGlyphFormulaRuns() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("상대 습도(", "Sandoll 고딕NeoRound"));
        para.addItem(text("%", "BT수식H-분수N"));
        para.addItem(text(") ", "Sandoll 고딕NeoRound"));
        para.addItem(text("=", "BT수식H-분수N"));

        ASTEquation fraction = new ASTEquation(
                "{현재 공기의 수증기압(hPa)} over {현재 기온에서의 포화 수증기압(hPa)}",
                "INLINE_FRACTION");
        para.addItem(fraction);
        para.addItem(text("×100", "BT수식H-분수N"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(6, para.items().size());
        Assert.assertSame(fraction, para.items().get(4));
        Assert.assertEquals(
                "{현재 공기의 수증기압(hPa)} over {현재 기온에서의 포화 수증기압(hPa)}",
                ((ASTEquation) para.items().get(4)).hwpScript());
        Assert.assertEquals("×100", ((ASTTextRun) para.items().get(5)).text());
    }

    @Test
    public void ehPlainDecimalSurvivesWhenParagraphAlsoHasEquation() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("⑶ ", "Yoon가변 윤명조100Std_OTF"));
        para.addItem(text("0.36", "EH상부자"));
        para.addItem(text("\t\t⑷ ", "Yoon가변 윤명조100Std_OTF"));
        para.addItem(new ASTEquation("{4} over {81}", "EH_FONT"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(4, para.items().size());
        Assert.assertEquals("0.36", ((ASTTextRun) para.items().get(1)).text());
        Assert.assertTrue(para.items().get(3) instanceof ASTEquation);
    }

    @Test
    public void btBodyTextChemicalReactionContextProducesEquation() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("(가) ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("HCO", "BT수식H-분수N"));
        para.addItem(text("3", "BT수식H-분수N"));
        para.addItem(sup("-", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("+", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("H", "BT수식H-분수N"));
        para.addItem(text("2", "BT수식H-분수N"));
        para.addItem(text("O", "BT수식H-분수N"));
        para.addItem(text(" ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("\u2192", "BT수식-편한글씨"));
        para.addItem(text(" ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("CO", "BT수식H-분수N"));
        para.addItem(text("3", "BT수식H-분수N"));
        para.addItem(sup("2-", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("+", "BT수식H-분수N"));
        para.addItem(text("\u2005", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("H", "BT수식H-분수N"));
        para.addItem(text("3", "BT수식H-분수N"));
        para.addItem(text("O", "BT수식H-분수N"));
        para.addItem(sup("+", "BT수식H-분수N"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(2, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertTrue(para.items().get(1) instanceof ASTEquation);
        Assert.assertEquals(
                "HCO_{3}^{-}+H_{2}O ~ rarrow ~ CO_{3}^{2-}+H_{3}O^{+}",
                ((ASTEquation) para.items().get(1)).hwpScript());
    }

    @Test
    public void formulaAnswerPlaceholdersDoNotBreakEquationFontReactionRange() {
        ResolvedBuildContext ctx = contextWithFormulaAnswerPlaceholders(7001, 7002);
        ASTParagraph para = new ASTParagraph();
        para.addItem(formula("N"));
        para.addItem(formula("2"));
        para.addItem(formula("+"));
        para.addItem(placeholder(7001));
        para.addItem(formula("H"));
        para.addItem(formula("2"));
        para.addItem(formula("\u2192"));
        para.addItem(placeholder(7002));
        para.addItem(formula("NH"));
        para.addItem(formula("3"));

        MathProcessor.convertMathRunsInParagraph(ctx, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals(
                "N_{2}+□H_{2} ~ rarrow ~ □NH_{3}",
                ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void equationFontReactionPlanningDoesNotMutateSourceItems() {
        ResolvedBuildContext ctx = contextWithFormulaAnswerPlaceholders(7001);
        ASTParagraph para = new ASTParagraph();
        para.addItem(formula("N"));
        para.addItem(formula("2"));
        para.addItem(placeholder(7001));
        para.addItem(formula("\u2192"));
        para.addItem(formula("N"));
        para.addItem(formula("H"));
        para.addItem(formula("3"));
        int originalSize = para.items().size();
        Object firstItem = para.items().get(0);

        java.util.List<MathProcessor.FormulaRangePlan> plans =
                MathProcessor.planEquationFontReactionRanges(ctx, para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(0, plans.get(0).startInclusive);
        Assert.assertEquals(originalSize, plans.get(0).endExclusive);
        Assert.assertEquals(
                "equation-font-reaction-with-arrow-and-bilateral-font-evidence",
                plans.get(0).reason);
        Assert.assertEquals("N2□ rarrow NH3",
                plans.get(0).equation.hwpScript());
        Assert.assertEquals(originalSize, para.items().size());
        Assert.assertSame(firstItem, para.items().get(0));

        MathProcessor.materializeFormulaRangePlans(para.items(), plans);
        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
    }

    @Test
    public void grepSplitDelimiterStitchIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation lead = new ASTEquation("left(-sqrt{a}", "EH_FONT");
        para.addItem(lead);
        para.addItem(text(" ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("right)^{2}", "EH_FONT"));

        java.util.List<MathProcessor.EquationStitchPlan> plans =
                MathProcessor.planGrepSplitFormulaEquations(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(0, plans.get(0).startInclusive);
        Assert.assertEquals(3, plans.get(0).endExclusive);
        Assert.assertEquals("left(-sqrt{a}right)^{2}", plans.get(0).mergedScript);
        Assert.assertEquals("grep-split-unbalanced-delimiters", plans.get(0).reason);
        Assert.assertEquals("left(-sqrt{a}", lead.hwpScript());
        Assert.assertEquals(3, para.items().size());

        MathProcessor.materializeEquationStitchPlans(para.items(), plans);
        Assert.assertEquals(1, para.items().size());
        Assert.assertSame(lead, para.items().get(0));
        Assert.assertEquals("left(-sqrt{a}right)^{2}", lead.hwpScript());
    }

    @Test
    public void emptyRadicandStitchHasExplicitPlanReason() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(new ASTEquation("sqrt{25} TIMES sqrt{ }", "EH_FONT"));
        para.addItem(text(" ", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("(-7)^{2}", "EH_FONT"));

        java.util.List<MathProcessor.EquationStitchPlan> plans =
                MathProcessor.planGrepSplitFormulaEquations(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(
                "empty-radicand-followed-by-balanced-equation",
                plans.get(0).reason);
        Assert.assertEquals(
                "sqrt{25} TIMES sqrt{(-7)^{2}}",
                plans.get(0).mergedScript);
    }

    @Test
    public void textSeparatedAlgebraFragmentsAreStitched() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(new ASTEquation("1^{2}", "EH_FONT"));
        para.addItem(text("=1,", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("2^{2}", "EH_FONT"));
        para.addItem(text("=4", "[Yoon가변] 윤명조100_OTF"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("1^{2}=1,2^{2}=4", ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void textSeparatedAlgebraStitchIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation lead = new ASTEquation("1^{2}", "EH_FONT");
        para.addItem(lead);
        para.addItem(text("=1,", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("2^{2}", "EH_FONT"));
        para.addItem(text("=4", "[Yoon가변] 윤명조100_OTF"));

        java.util.List<MathProcessor.EquationStitchPlan> plans =
                MathProcessor.planTextSeparatedFormulaEquationFragments(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(0, plans.get(0).startInclusive);
        Assert.assertEquals(4, plans.get(0).endExclusive);
        Assert.assertEquals("1^{2}=1,2^{2}=4", plans.get(0).mergedScript);
        Assert.assertEquals(
                "text-connector-between-stitchable-equations",
                plans.get(0).reason);
        Assert.assertEquals("1^{2}", lead.hwpScript());
        Assert.assertEquals(4, para.items().size());
    }

    @Test
    public void fragmentedChemicalFormulaIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation hydrogen = new ASTEquation("H", "EH_FONT");
        hydrogen.textColor("#225588");
        hydrogen.preferredBaseUnit(880);
        hydrogen.preferredFontFamily("수식본문");
        para.addItem(hydrogen);
        para.addItem(text("2", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("O", "EH_FONT"));

        java.util.List<MathProcessor.FormulaRangePlan> plans =
                MathProcessor.planFragmentedChemicalFormulas(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(0, plans.get(0).startInclusive);
        Assert.assertEquals(3, plans.get(0).endExclusive);
        Assert.assertEquals(
                "fragmented-bare-elements-and-numeric-subscripts",
                plans.get(0).reason);
        Assert.assertEquals("H_{2}O", plans.get(0).equation.hwpScript());
        Assert.assertEquals("#225588", plans.get(0).equation.textColor());
        Assert.assertEquals(Integer.valueOf(880),
                plans.get(0).equation.preferredBaseUnit());
        Assert.assertEquals("수식본문", plans.get(0).equation.preferredFontFamily());
        Assert.assertEquals(3, para.items().size());
        Assert.assertSame(hydrogen, para.items().get(0));

        MathProcessor.materializeFormulaRangePlans(para.items(), plans);
        Assert.assertEquals(1, para.items().size());
        Assert.assertEquals("CHEM_FORMULA",
                ((ASTEquation) para.items().get(0)).sourceType());
    }

    @Test
    public void fragmentedChemicalReactionRequiresArrowPlanEvidence() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("2Mg", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("+", "[Yoon가변] 윤명조100_OTF"));
        ASTEquation reactionTail =
                new ASTEquation("O_{2} rarrow 2MgO", "CHEM_FORMULA");
        reactionTail.textColor("#AA3377");
        reactionTail.preferredBaseUnit(920);
        para.addItem(reactionTail);

        java.util.List<MathProcessor.FormulaRangePlan> plans =
                MathProcessor.planFragmentedChemicalReactions(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals(0, plans.get(0).startInclusive);
        Assert.assertEquals(3, plans.get(0).endExclusive);
        Assert.assertEquals(
                "fragmented-chemical-reaction-with-arrow-evidence",
                plans.get(0).reason);
        Assert.assertEquals(
                "2Mg+O_{2} ~ rarrow ~ 2MgO",
                plans.get(0).equation.hwpScript());
        Assert.assertEquals("#AA3377", plans.get(0).equation.textColor());
        Assert.assertEquals(Integer.valueOf(920),
                plans.get(0).equation.preferredBaseUnit());
        Assert.assertEquals(3, para.items().size());

        ASTParagraph withoutArrow = new ASTParagraph();
        withoutArrow.addItem(new ASTEquation("H", "EH_FONT"));
        withoutArrow.addItem(text("2", "[Yoon가변] 윤명조100_OTF"));
        withoutArrow.addItem(new ASTEquation("O", "EH_FONT"));
        Assert.assertTrue(
                MathProcessor.planFragmentedChemicalReactions(withoutArrow.items()).isEmpty());
    }

    @Test
    public void chemicalFormulaStitchPlansPrecedingAndTrailingFragments() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun prefix = text("반응 CH", "[Yoon가변] 윤명조100_OTF");
        para.addItem(prefix);
        ASTEquation equation =
                new ASTEquation("4+2O2 rarrow CO2", "CHEM_FORMULA");
        para.addItem(equation);
        para.addItem(text("+2H", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(text("2O", "[Yoon가변] 윤명조100_OTF"));

        java.util.List<MathProcessor.ChemicalStitchPlan> plans =
                MathProcessor.planChemicalFormulaStitches(para.items());

        Assert.assertEquals(1, plans.size());
        MathProcessor.ChemicalStitchPlan plan = plans.get(0);
        Assert.assertEquals(1, plan.equationIndex);
        Assert.assertEquals(3, plan.tailEndInclusive);
        Assert.assertEquals(0, plan.partialTextIndex);
        Assert.assertEquals("반응 ", plan.partialTextRemainder);
        Assert.assertEquals(
                "chemical-equation-with-adjacent-source-fragments",
                plan.reason);
        Assert.assertEquals(
                "CH_{4}+2O_{2} ~ rarrow ~ CO_{2}+2H_{2}O",
                plan.mergedScript);
        Assert.assertEquals("반응 CH", prefix.text());
        Assert.assertEquals("4+2O2 rarrow CO2", equation.hwpScript());
        Assert.assertEquals(4, para.items().size());

        MathProcessor.materializeChemicalStitchPlans(para.items(), plans);
        Assert.assertEquals(2, para.items().size());
        Assert.assertEquals("반응 ", ((ASTTextRun) para.items().get(0)).text());
        Assert.assertEquals(
                "CH_{4}+2O_{2} ~ rarrow ~ CO_{2}+2H_{2}O",
                ((ASTEquation) para.items().get(1)).hwpScript());
    }

    @Test
    public void bodyTextSubscriptChemicalSegmentIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun base = text("물(H", "[Yoon가변] 윤명조100_OTF");
        base.fontSizeHwpunits(860);
        base.textColor("#117755");
        para.addItem(base);
        ASTTextRun subscript = text("2", "[Yoon가변] 윤명조100_OTF");
        subscript.subscript(true);
        para.addItem(subscript);
        ASTTextRun tail = text("O이다", "[Yoon가변] 윤명조100_OTF");
        para.addItem(tail);

        java.util.List<MathProcessor.SubscriptChemicalPlan> plans =
                MathProcessor.planSubscriptChemicalSegments(para.items());

        Assert.assertEquals(1, plans.size());
        MathProcessor.SubscriptChemicalPlan plan = plans.get(0);
        Assert.assertEquals(0, plan.baseIndex);
        Assert.assertEquals(2, plan.absorbedEndInclusive);
        Assert.assertEquals(2, plan.tailPartialIndex);
        Assert.assertEquals("물(", plan.baseRemainder);
        Assert.assertEquals("이다", plan.tailPartialRemainder);
        Assert.assertEquals(
                "body-text-element-followed-by-subscript-evidence",
                plan.reason);
        Assert.assertEquals("H_{2}O", plan.equation.hwpScript());
        Assert.assertEquals(Integer.valueOf(860), plan.equation.preferredBaseUnit());
        Assert.assertEquals("#117755", plan.equation.textColor());
        Assert.assertEquals("물(H", base.text());
        Assert.assertEquals("O이다", tail.text());

        MathProcessor.materializeSubscriptChemicalPlans(para.items(), plans);
        Assert.assertEquals(3, para.items().size());
        Assert.assertEquals("물(", ((ASTTextRun) para.items().get(0)).text());
        Assert.assertEquals("H_{2}O", ((ASTEquation) para.items().get(1)).hwpScript());
        Assert.assertEquals("이다", ((ASTTextRun) para.items().get(2)).text());
    }

    @Test
    public void boundaryWrappedEquationSplitIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation wrapped = new ASTEquation(",a=1:", "EH_FONT");
        wrapped.textColor("#445566");
        wrapped.preferredBaseUnit(840);
        wrapped.preferredFontFamily("수식본문");
        para.addItem(wrapped);

        java.util.List<MathProcessor.BoundarySplitPlan> plans =
                MathProcessor.planBoundaryWrappedFormulaEquations(para.items());

        Assert.assertEquals(1, plans.size());
        MathProcessor.BoundarySplitPlan plan = plans.get(0);
        Assert.assertEquals(0, plan.itemIndex);
        Assert.assertEquals(
                "formula-core-wrapped-by-text-boundary-characters",
                plan.reason);
        Assert.assertEquals(3, plan.replacements.size());
        Assert.assertEquals(",", ((ASTTextRun) plan.replacements.get(0)).text());
        ASTEquation core = (ASTEquation) plan.replacements.get(1);
        Assert.assertEquals("a=1", core.hwpScript());
        Assert.assertEquals("#445566", core.textColor());
        Assert.assertEquals(Integer.valueOf(840), core.preferredBaseUnit());
        Assert.assertEquals("수식본문", core.preferredFontFamily());
        Assert.assertEquals(":", ((ASTTextRun) plan.replacements.get(2)).text());
        Assert.assertEquals(",a=1:", wrapped.hwpScript());
        Assert.assertEquals(1, para.items().size());

        MathProcessor.materializeBoundarySplitPlans(para.items(), plans);
        Assert.assertEquals(3, para.items().size());
        Assert.assertTrue(para.items().get(1) instanceof ASTEquation);
    }

    @Test
    public void mixedFormulaClusterCollapseIsPlannedWithoutMutation() {
        ASTParagraph para = new ASTParagraph();
        ASTEquation nitrogen = new ASTEquation("N_{2}", "CHEM_FORMULA");
        nitrogen.textColor("#884422");
        nitrogen.preferredBaseUnit(900);
        para.addItem(nitrogen);
        ASTTextRun continuation = text("+O2", "[Yoon가변] 윤명조100_OTF");
        para.addItem(continuation);

        java.util.List<MathProcessor.ClusterCollapsePlan> plans =
                MathProcessor.planMixedFormulaEquationClusters(null, para.items());

        Assert.assertEquals(1, plans.size());
        MathProcessor.ClusterCollapsePlan plan = plans.get(0);
        Assert.assertEquals(0, plan.startInclusive);
        Assert.assertEquals(2, plan.endExclusive);
        Assert.assertEquals(
                "mixed-formula-text-equation-placeholder-cluster",
                plan.reason);
        Assert.assertEquals("N_{2}+O2", plan.equation.hwpScript());
        Assert.assertEquals("#884422", plan.equation.textColor());
        Assert.assertEquals(Integer.valueOf(900), plan.equation.preferredBaseUnit());
        Assert.assertEquals("N_{2}", nitrogen.hwpScript());
        Assert.assertEquals("+O2", continuation.text());
        Assert.assertEquals(2, para.items().size());

        MathProcessor.materializeClusterCollapsePlans(para.items(), plans);
        Assert.assertEquals(1, para.items().size());
        Assert.assertEquals(
                "N_{2}+O2",
                ((ASTEquation) para.items().get(0)).hwpScript());
    }

    @Test
    public void overlineEquationsSeparatedByEqualsAreNotStitched() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(new ASTEquation("overline{AB}", "EH_FONT"));
        para.addItem(text("=1,", "[Yoon가변] 윤명조100_OTF"));
        para.addItem(new ASTEquation("overline{CD}", "EH_FONT"));

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(3, para.items().size());
        Assert.assertEquals("overline{AB}", ((ASTEquation) para.items().get(0)).hwpScript());
        Assert.assertEquals("=1,", ((ASTTextRun) para.items().get(1)).text());
        Assert.assertEquals("overline{CD}", ((ASTEquation) para.items().get(2)).hwpScript());
        Assert.assertTrue(
                MathProcessor.planTextSeparatedFormulaEquationFragments(para.items()).isEmpty());
    }

    @Test
    public void sourceMathItalicLowercaseIsPlannedBeforeMaterialization() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun variable = text("a", "[Yoon가변] 윤명조100_OTF");
        variable.fontStyle("Italic");
        variable.fontSizeHwpunits(1000);
        variable.textColor("#336699");
        para.addItem(variable);

        MathSpanPlan plan = MathSpanPlanner.plan(para);

        Assert.assertEquals(1, plan.spans().size());
        Assert.assertEquals(0, plan.spans().get(0).itemIndex());
        Assert.assertEquals(
                MathSpanPlanner.REASON_SINGLE_LATIN_SOURCE_MATH_TYPOGRAPHY,
                plan.spans().get(0).reason());
        Assert.assertTrue("planning must not mutate the AST",
                para.items().get(0) instanceof ASTTextRun);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTEquation);
        ASTEquation equation = (ASTEquation) para.items().get(0);
        Assert.assertEquals("a", equation.hwpScript());
        Assert.assertEquals(Integer.valueOf(1000), equation.preferredBaseUnit());
        Assert.assertEquals("#336699", equation.textColor());
        Assert.assertTrue(equation.sourceItalic());
    }

    @Test
    public void isolatedUppercaseInitialRemainsTextAfterPlanAndConversion() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun initial = text("A", "[Yoon가변] 윤명조100_OTF");
        initial.fontStyle("Italic");
        para.addItem(initial);

        MathProcessor.convertMathRunsInParagraph(null, para);

        Assert.assertEquals(1, para.items().size());
        Assert.assertTrue(para.items().get(0) instanceof ASTTextRun);
        Assert.assertEquals("A", ((ASTTextRun) para.items().get(0)).text());
    }

    @Test
    public void embeddedLineBreakPlanningDoesNotMutateSource() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun source = text("a+b\t\n=c+d", "EH수식");
        para.addItem(source);

        List<MathProcessor.ItemReplacementPlan> plans =
                MathProcessor.planEmbeddedSourceLineBreaks(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals("EMBEDDED_SOURCE_LINE_BREAK", plans.get(0).reason);
        Assert.assertSame(source, para.items().get(0));
        Assert.assertEquals("a+b\t\n=c+d", source.text());

        MathProcessor.materializeItemReplacementPlans(para.items(), plans);

        Assert.assertEquals(3, para.items().size());
        Assert.assertEquals("a+b", ((ASTTextRun) para.items().get(0)).text());
        Assert.assertEquals(ASTBreak.BreakType.LINE,
                ((ASTBreak) para.items().get(1)).breakType());
        Assert.assertEquals("=c+d", ((ASTTextRun) para.items().get(2)).text());
    }

    @Test
    public void ehKoreanSplitPlanningPreservesSourceAndStyles() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun source = text("a가 더 크다", "EH상부자");
        source.fontSizeHwpunits(1000);
        source.textColor("#CC0066");
        para.addItem(source);

        List<MathProcessor.ItemReplacementPlan> plans =
                MathProcessor.planEHKoreanMixedTextRuns(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertSame(source, para.items().get(0));
        Assert.assertEquals("a가 더 크다", source.text());
        Assert.assertEquals("EH상부자", source.fontFamily());

        MathProcessor.materializeItemReplacementPlans(para.items(), plans);

        Assert.assertEquals(2, para.items().size());
        ASTTextRun head = (ASTTextRun) para.items().get(0);
        ASTTextRun tail = (ASTTextRun) para.items().get(1);
        Assert.assertEquals("a", head.text());
        Assert.assertEquals("EH상부자", head.fontFamily());
        Assert.assertEquals("가 더 크다", tail.text());
        Assert.assertNull(tail.fontFamily());
        Assert.assertEquals(Integer.valueOf(1000), tail.fontSizeHwpunits());
        Assert.assertEquals("#CC0066", tail.textColor());
    }

    @Test
    public void discardableEhResidueIsRemovedOnlyDuringMaterialization() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun residue = text("", "EH선모음");
        para.addItem(residue);
        para.addItem(text("본문", "Yoon가변 윤명조100Std_OTF"));

        List<MathProcessor.ItemReplacementPlan> plans =
                MathProcessor.planDiscardableEHStructureResidues(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertSame(residue, para.items().get(0));

        MathProcessor.materializeItemReplacementPlans(para.items(), plans);

        Assert.assertEquals(1, para.items().size());
        Assert.assertEquals("본문", ((ASTTextRun) para.items().get(0)).text());
    }

    @Test
    public void characterStylePositionIsPlannedBeforeMutation() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("x", "EH수식"));
        ASTTextRun exponent = text("2", "EH상부자");
        exponent.characterStyleRef("00_수식(첨자-상부자)");
        para.addItem(exponent);

        List<MathProcessor.PositionStylePlan> plans =
                MathProcessor.planPositionStyles(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals("CHARACTER_STYLE_SUPERSCRIPT", plans.get(0).reason);
        Assert.assertFalse("planning must not mutate source", exponent.superscript());
        Assert.assertFalse(exponent.subscript());

        MathProcessor.materializePositionStylePlans(para.items(), plans);

        Assert.assertTrue(exponent.superscript());
        Assert.assertFalse(exponent.subscript());
    }

    @Test
    public void nameOnlySuperscriptWithoutBaseIsNotPlanned() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun ordinaryNumber = text("25", "EH상부자");
        ordinaryNumber.characterStyleRef("00_상부자(이탤릭)");
        para.addItem(ordinaryNumber);

        Assert.assertTrue(
                MathProcessor.planPositionStyles(para.items()).isEmpty());
        Assert.assertFalse(ordinaryNumber.superscript());
    }

    @Test
    public void formulaBoundaryColorIsPlannedWithoutMutatingRun() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun body = text("설명", "Yoon가변 윤명조100Std_OTF");
        body.textColor("#CC0066");
        para.addItem(body);
        ASTTextRun comma = text(",", "EH수식");
        comma.textColor("#000000");
        para.addItem(comma);

        List<MathProcessor.TextColorPlan> plans =
                MathProcessor.planFormulaBoundaryTextColors(para.items());

        Assert.assertEquals(1, plans.size());
        Assert.assertEquals("#CC0066", plans.get(0).color);
        Assert.assertEquals("FORMULA_BOUNDARY_NEARBY_BODY_COLOR",
                plans.get(0).reason);
        Assert.assertEquals("#000000", comma.textColor());

        MathProcessor.materializeTextColorPlans(para.items(), plans);

        Assert.assertEquals("#CC0066", comma.textColor());
    }

    @Test
    public void explicitBoundaryColorIsNotOverridden() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun body = text("설명", "Yoon가변 윤명조100Std_OTF");
        body.textColor("#CC0066");
        para.addItem(body);
        ASTTextRun comma = text(",", "EH수식");
        comma.textColor("#336699");
        para.addItem(comma);

        Assert.assertTrue(
                MathProcessor.planFormulaBoundaryTextColors(para.items()).isEmpty());
        Assert.assertEquals("#336699", comma.textColor());
    }

    @Test
    public void convertedItemSequenceIsAppliedOnlyByMaterializer() {
        ASTParagraph para = new ASTParagraph();
        ASTTextRun source = text("a+b", "EH수식");
        para.addItem(source);
        List<ASTInlineItem> converted = new ArrayList<>();
        ASTEquation equation = new ASTEquation("a+b", "EH_FONT");
        converted.add(equation);

        MathProcessor.ConvertedItemsPlan plan =
                MathProcessor.planConvertedItemsReplacement(
                        para.items(), converted);

        Assert.assertNotNull(plan);
        Assert.assertEquals("MATH_RUN_CONVERSION_RESULT", plan.reason);
        Assert.assertSame(source, para.items().get(0));

        MathProcessor.materializeConvertedItemsPlan(para.items(), plan);

        Assert.assertEquals(1, para.items().size());
        Assert.assertSame(equation, para.items().get(0));
    }

    @Test
    public void unchangedItemSequenceNeedsNoMaterializationPlan() {
        ASTParagraph para = new ASTParagraph();
        para.addItem(text("본문", "Yoon가변 윤명조100Std_OTF"));

        Assert.assertNull(MathProcessor.planConvertedItemsReplacement(
                para.items(), new ArrayList<>(para.items())));
    }

    private static ASTTextRun text(String text, String fontFamily) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        run.fontFamily(fontFamily);
        return run;
    }

    private static ASTTextRun formula(String text) {
        ASTTextRun run = text(text, "BT수식M");
        run.grepMathFont(true);
        return run;
    }

    private static ASTTextRun sup(String text, String fontFamily) {
        ASTTextRun run = text(text, fontFamily);
        run.characterStyleRef("00_수식(첨자-상부자)");
        return run;
    }

    private static ASTInlineObject placeholder(int sourceId) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        obj.sourceId(String.valueOf(sourceId));
        return obj;
    }

    private static ResolvedBuildContext contextWithFormulaAnswerPlaceholders(int... sourceIds) {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ResolvedData data = new ResolvedData();
        ctx.resolvedData = data;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = new ResolvedPageItem();
            item.id(String.valueOf(sourceId));
            item.type("Rectangle");
            item.geometricBounds(new double[] { 0, 0, 8, 8 });
            item.storyAnchorPlacement("INLINE");
            item.strokeColorName("Black");
            item.strokeWeight(1.0);
            data.addPageItem(item);
            ctx.ownershipPlans.add(ObjectPlan.legacyDefaulted(
                    sourceId,
                    "formula_answer_placeholder",
                    0,
                    TextAction.DROP_TEXT,
                    VisualAction.PLACE_INLINE_PNG,
                    VisualLayer.CONTENT_VISUAL,
                    Placement.INLINE,
                    sourceId,
                    new int[] { sourceId },
                    0,
                    "test_formula_answer_placeholder",
                    "rendered_frames/inline_" + sourceId + ".png",
                    null));
        }
        return ctx;
    }
}
