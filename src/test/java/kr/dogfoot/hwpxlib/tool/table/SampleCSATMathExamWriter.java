package kr.dogfoot.hwpxlib.tool.table;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.common.compatibility.Case;
import kr.dogfoot.hwpxlib.object.common.compatibility.Default;
import kr.dogfoot.hwpxlib.object.common.compatibility.Switch;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.parapr.LineSpacing;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.parapr.ParaMargin;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.ColPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 2026 대학수학능력시험 수학 영역 형식의 샘플 HWPX 파일을 생성하는 테스트.
 *
 * - 30문제 (객관식 1~22, 주관식 23~30)
 * - 2단 컬럼 레이아웃
 * - 수학 수식 (EquationBuilder.fromLatex)
 * - 5지선다 객관식 (①②③④⑤)
 * - 점수 표기 [2점], [3점], [4점]
 */
public class SampleCSATMathExamWriter {

    private int idCounter = 0;

    // CharPr IDs
    private static final String CP_DEFAULT = "0";      // 10pt normal (기존)
    private static final String CP_HEADER = "7";        // 14pt bold (교시)
    private static final String CP_TITLE = "8";         // 24pt bold (수학 영역)
    private static final String CP_PROB_NUM = "9";      // 10pt bold (문제번호)
    private static final String CP_POINTS = "10";       // 9pt normal (점수)

    // ParaPr IDs
    private static final String PP_CENTER = "16";       // 중앙정렬 (헤더/제목)
    private static final String PP_PROBLEM = "17";      // 문제 본문
    private static final String PP_CHOICE = "18";       // 보기

    // 원문자
    private static final String[] CIRCLED = {
            "\u2460", "\u2461", "\u2462", "\u2463", "\u2464"
    };

    // ── 문제 데이터 ──

    private static class ProblemData {
        int number;
        int points;
        String text;
        String latex;       // null이면 수식 없음
        String[] choices;   // null이면 주관식

        ProblemData(int number, int points, String text, String latex, String[] choices) {
            this.number = number;
            this.points = points;
            this.text = text;
            this.latex = latex;
            this.choices = choices;
        }
    }

    private static final ProblemData[] PROBLEMS = {
            // 객관식 1~22
            new ProblemData(1, 2, "다음 식의 값은?",
                    "3^{2} + 2^{3}",
                    new String[]{"11", "15", "17", "19", "21"}),
            new ProblemData(2, 2, "다음 식의 값은?",
                    "\\frac{1}{2} + \\frac{1}{3}",
                    new String[]{"\\frac{1}{6}", "\\frac{5}{6}", "\\frac{2}{3}", "\\frac{1}{5}", "\\frac{5}{12}"}),
            new ProblemData(3, 2, "함수 f(x) = 2x + 1에 대하여 f(3)의 값은?",
                    "f(x) = 2x + 1",
                    new String[]{"5", "6", "7", "8", "9"}),
            new ProblemData(4, 2, "다음의 값은?",
                    "\\log_{2} 8",
                    new String[]{"1", "2", "3", "4", "5"}),
            new ProblemData(5, 2, "다항식 (x+1)(x+2)를 전개한 식은?",
                    "(x+1)(x+2)",
                    new String[]{"x^{2}+3x+2", "x^{2}+2x+2", "x^{2}+3x+3", "x^{2}+x+2", "x^{2}+2x+1"}),
            new ProblemData(6, 2, "sin 30\u00B0의 값은?",
                    "\\sin 30^{\\circ}",
                    new String[]{"\\frac{1}{2}", "\\frac{\\sqrt{2}}{2}", "\\frac{\\sqrt{3}}{2}", "1", "0"}),
            new ProblemData(7, 2, "첫째항이 2이고 공차가 3인 등차수열 {a_n}에서 a_5의 값은?",
                    "a_{n} = a_{1} + (n-1)d",
                    new String[]{"11", "14", "17", "20", "23"}),
            new ProblemData(8, 3, "이차방정식 x\u00B2 - 5x + 6 = 0의 두 근의 합은?",
                    "x^{2} - 5x + 6 = 0",
                    new String[]{"3", "4", "5", "6", "7"}),
            new ProblemData(9, 3, "함수 f(x) = x\u00B3 - 3x에 대하여 f'(1)의 값은?",
                    "f(x) = x^{3} - 3x",
                    new String[]{"-2", "0", "1", "2", "3"}),
            new ProblemData(10, 3, "다음 정적분의 값은?",
                    "\\int_{0}^{2} (3x^{2}+1) \\, dx",
                    new String[]{"8", "10", "12", "14", "16"}),
            new ProblemData(11, 3, "다음 극한값은?",
                    "\\lim_{x \\to 0} \\frac{\\sin x}{x}",
                    new String[]{"0", "\\frac{1}{2}", "1", "2", "\uBD88\uC815"}),
            new ProblemData(12, 3, "행렬 A의 행렬식의 값은? (A = ((1, 2), (3, 4)))",
                    "\\det(A) = 1 \\times 4 - 2 \\times 3",
                    new String[]{"-5", "-2", "0", "2", "5"}),
            new ProblemData(13, 3, "함수 f(x) = e^{2x}의 도함수 f'(x)는?",
                    "f(x) = e^{2x}",
                    new String[]{"e^{2x}", "2e^{2x}", "2xe^{x}", "e^{x}", "xe^{2x}"}),
            new ProblemData(14, 3, "다음의 값은?",
                    "\\sum_{k=1}^{10} k",
                    new String[]{"45", "50", "55", "60", "65"}),
            new ProblemData(15, 3, "다음의 값은?",
                    "\\cos^{2} \\theta + \\sin^{2} \\theta",
                    new String[]{"0", "\\frac{1}{2}", "1", "2", "\\theta\uC5D0 \uB530\uB77C \uB2E4\uB984"}),
            new ProblemData(16, 3, "(1+x)^5의 전개식에서 x^3의 계수는?",
                    "(1+x)^{5}",
                    new String[]{"5", "10", "15", "20", "25"}),
            new ProblemData(17, 3, "함수 f(x) = ln(x\u00B2+1)에 대하여 f'(0)의 값은?",
                    "f(x) = \\ln(x^{2}+1)",
                    new String[]{"-1", "0", "1", "2", "\uC874\uC7AC\uD558\uC9C0 \uC54A\uC74C"}),
            new ProblemData(18, 3, "부등식 x\u00B2 - 4 < 0의 해는?",
                    "x^{2} - 4 < 0",
                    new String[]{"x < -2", "-2 < x < 2", "x > 2", "x < -2 \uB610\uB294 x > 2", "\uBAA8\uB4E0 \uC2E4\uC218"}),
            new ProblemData(19, 3, "첫째항이 1이고 공비가 2인 등비수열의 첫째항부터 제5항까지의 합 S_5의 값은?",
                    "S_{n} = \\frac{a_{1}(r^{n}-1)}{r-1}",
                    new String[]{"15", "21", "31", "32", "63"}),
            new ProblemData(20, 3, "tan(\\pi/4)의 값은?",
                    "\\tan \\frac{\\pi}{4}",
                    new String[]{"0", "\\frac{1}{2}", "1", "\\sqrt{3}", "\uC815\uC758 \uC548\uB428"}),
            new ProblemData(21, 3, "함수 f(x) = x\u00B2에서 x = 1에서 x = 3까지의 평균변화율은?",
                    "f(x) = x^{2}",
                    new String[]{"2", "3", "4", "5", "6"}),
            new ProblemData(22, 3, "다음의 값은?",
                    "\\binom{5}{2}",
                    new String[]{"5", "10", "15", "20", "25"}),

            // 주관식 23~30
            new ProblemData(23, 3, "다항식 (x+2)\u00B3의 전개식에서 x\u00B2의 계수를 구하시오.",
                    "(x+2)^{3}",
                    null),
            new ProblemData(24, 3, "함수 f(x) = 3x\u00B2 - 2x + 1에 대하여 f'(2)의 값을 구하시오.",
                    "f(x) = 3x^{2} - 2x + 1",
                    null),
            new ProblemData(25, 3, "등차수열 {a_n}에서 a_3 = 7, a_7 = 19일 때, a_{10}의 값을 구하시오.",
                    null,
                    null),
            new ProblemData(26, 4, "다음 정적분의 값을 구하시오.",
                    "\\int_{1}^{3} (2x+1) \\, dx",
                    null),
            new ProblemData(27, 4, "다음 급수의 합을 구하시오.",
                    "\\sum_{n=1}^{\\infty} \\left(\\frac{1}{2}\\right)^{n}",
                    null),
            new ProblemData(28, 4, "방정식 e^x = 3의 해를 x = ln a라 할 때, a의 값을 구하시오.",
                    "e^{x} = 3",
                    null),
            new ProblemData(29, 4, "함수 f(x) = x\u00B3 - 6x\u00B2 + 9x + 1이 극대가 되는 x의 값을 구하시오.",
                    "f(x) = x^{3} - 6x^{2} + 9x + 1",
                    null),
            new ProblemData(30, 4, "좌표평면 위의 두 점 A(1, 2), B(4, 6) 사이의 거리를 구하시오.",
                    "d = \\sqrt{(4-1)^{2} + (6-2)^{2}}",
                    null),
    };

    // ── 메인 테스트 ──

    @Test
    public void createCSATMathExam() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // 1. 스타일 등록
        registerCharProperties(hwpxFile);
        registerParaProperties(hwpxFile);

        // 2. 2단 컬럼 설정
        setup2ColumnLayout(section);

        // 3. 헤더 / 제목
        addPageHeader(section);
        addTitle(section);
        addSeparatorLine(section);
        addEmptyPara(section, PP_CENTER);

        // 4. 객관식 (1~22)
        int eqFailCount = 0;
        for (int i = 0; i < 22; i++) {
            eqFailCount += addProblem(section, PROBLEMS[i]);
        }

        // 5. 주관식 구분
        addEmptyPara(section, PP_PROBLEM);
        addSectionLabel(section, "\u3014\uC8FC\uAD00\uC2DD\u3015");  // 【주관식】
        addEmptyPara(section, PP_PROBLEM);

        // 6. 주관식 (23~30)
        for (int i = 22; i < 30; i++) {
            eqFailCount += addProblem(section, PROBLEMS[i]);
        }

        // 7. 저장
        String filepath = "testFile/tool/sample_csat_math_2026.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== CSAT Math Exam Generation Result ===");
        System.out.println("Problems: 30 (22 MC + 8 Short Answer)");
        System.out.println("Equation conversion failures: " + eqFailCount);
        System.out.println("File: " + filepath);

        // 8. 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have many paragraphs", readSection.countOfPara() > 50);

        // 수식 존재 확인
        boolean foundEquation = false;
        for (int i = 0; i < readSection.countOfPara() && !foundEquation; i++) {
            Para para = readSection.getPara(i);
            for (int r = 0; r < para.countOfRun() && !foundEquation; r++) {
                Run run = para.getRun(r);
                for (int ri = 0; ri < run.countOfRunItem(); ri++) {
                    if (run.getRunItem(ri) instanceof Equation) {
                        Equation eq = (Equation) run.getRunItem(ri);
                        Assert.assertEquals("Equation Version 60", eq.version());
                        Assert.assertNotNull(eq.script());
                        foundEquation = true;
                        break;
                    }
                }
            }
        }
        Assert.assertTrue("Should contain at least one equation", foundEquation);
        Assert.assertEquals("No equation conversion failures", 0, eqFailCount);

        System.out.println("Round-trip verification passed!");
        System.out.println("Paragraphs: " + readSection.countOfPara());
    }

    // ── 스타일 등록 ──

    private void registerCharProperties(HWPXFile hwpxFile) {
        ObjectList<CharPr> cps = hwpxFile.headerXMLFile().refList().charProperties();
        addCharPr(cps, CP_HEADER, 1400, "#000000", true, "0");
        addCharPr(cps, CP_TITLE, 2400, "#000000", true, "0");
        addCharPr(cps, CP_PROB_NUM, 1000, "#000000", true, "0");
        addCharPr(cps, CP_POINTS, 900, "#000000", false, "0");
    }

    private void addCharPr(ObjectList<CharPr> cps, String id, int height, String color,
                           boolean bold, String fontRef) {
        CharPr cp = cps.addNew();
        cp.idAnd(id).heightAnd(height).textColorAnd(color)
                .shadeColorAnd("none").useFontSpaceAnd(false).useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE).borderFillIDRef("2");
        cp.createFontRef();
        cp.fontRef().set(fontRef, fontRef, fontRef, fontRef, fontRef, fontRef, fontRef);
        cp.createRatio();
        cp.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);
        cp.createSpacing();
        cp.spacing().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        cp.createRelSz();
        cp.relSz().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);
        cp.createOffset();
        cp.offset().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        if (bold) {
            cp.createBold();
        }
        cp.createUnderline();
        cp.underline().typeAnd(UnderlineType.NONE).shapeAnd(LineType3.SOLID).color("#000000");
        cp.createStrikeout();
        cp.strikeout().shapeAnd(LineType2.NONE).color("#000000");
        cp.createOutline();
        cp.outline().type(LineType1.NONE);
        cp.createShadow();
        cp.shadow().typeAnd(CharShadowType.NONE).colorAnd("#B2B2B2")
                .offsetXAnd((short) 10).offsetY((short) 10);
    }

    private void registerParaProperties(HWPXFile hwpxFile) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();
        addParaPr(pps, PP_CENTER, HorizontalAlign2.CENTER, 130, 0, 0);
        addParaPr(pps, PP_PROBLEM, HorizontalAlign2.JUSTIFY, 150, 200, 100);
        addParaPr(pps, PP_CHOICE, HorizontalAlign2.LEFT, 140, 50, 200);
    }

    private void addParaPr(ObjectList<ParaPr> pps, String id, HorizontalAlign2 hAlign,
                           int lineSpacingValue, int prevMargin, int nextMargin) {
        ParaPr pp = pps.addNew();
        pp.idAnd(id).tabPrIDRefAnd("0").condenseAnd((byte) 0)
                .fontLineHeightAnd(false).snapToGridAnd(true)
                .suppressLineNumbersAnd(false).checked(false);

        pp.createAlign();
        pp.align().horizontalAnd(hAlign).vertical(VerticalAlign1.BASELINE);

        pp.createHeading();
        pp.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);

        pp.createBreakSetting();
        pp.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.BREAK_WORD)
                .widowOrphanAnd(false).keepWithNextAnd(false).keepLinesAnd(false)
                .pageBreakBeforeAnd(false).lineWrap(LineWrap.BREAK);

        pp.createAutoSpacing();
        pp.autoSpacing().eAsianEngAnd(false).eAsianNum(false);

        Switch sw = pp.addNewSwitch();
        sw.position(4);
        Case c = sw.addNewCaseObject();
        c.requiredNamespace("http://www.hancom.co.kr/hwpml/2016/HwpUnitChar");
        c.addChild(makeParaMargin(0, 0, 0, prevMargin, nextMargin));
        c.addChild(makeLineSpacing(LineSpacingType.PERCENT, lineSpacingValue, ValueUnit2.HWPUNIT));
        sw.createDefaultObject();
        Default def = sw.defaultObject();
        def.addChild(makeParaMargin(0, 0, 0, prevMargin, nextMargin));
        def.addChild(makeLineSpacing(LineSpacingType.PERCENT, lineSpacingValue, ValueUnit2.HWPUNIT));

        pp.createBorder();
        pp.border().borderFillIDRefAnd("2")
                .offsetLeftAnd(0).offsetRightAnd(0)
                .offsetTopAnd(0).offsetBottomAnd(0)
                .connectAnd(false).ignoreMargin(false);
    }

    private static ParaMargin makeParaMargin(int indent, int left, int right, int prev, int next) {
        ParaMargin margin = new ParaMargin();
        margin.createIntent();
        margin.intent().valueAnd(indent).unit(ValueUnit2.HWPUNIT);
        margin.createLeft();
        margin.left().valueAnd(left).unit(ValueUnit2.HWPUNIT);
        margin.createRight();
        margin.right().valueAnd(right).unit(ValueUnit2.HWPUNIT);
        margin.createPrev();
        margin.prev().valueAnd(prev).unit(ValueUnit2.HWPUNIT);
        margin.createNext();
        margin.next().valueAnd(next).unit(ValueUnit2.HWPUNIT);
        return margin;
    }

    private static LineSpacing makeLineSpacing(LineSpacingType type, int value, ValueUnit2 unit) {
        LineSpacing ls = new LineSpacing();
        ls.typeAnd(type).valueAnd(value).unit(unit);
        return ls;
    }

    // ── 레이아웃 ──

    private void setup2ColumnLayout(SectionXMLFile section) {
        Para firstPara = section.getPara(0);
        Run firstRun = firstPara.getRun(0);
        Ctrl firstCtrl = (Ctrl) firstRun.getRunItem(0);
        ColPr colPr = (ColPr) firstCtrl.getCtrlItem(0);
        colPr.colCountAnd(2).sameSzAnd(true).sameGap(850);
    }

    // ── 헤더 / 제목 ──

    private void addPageHeader(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd(PP_CENTER).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef(CP_HEADER);
        run.addNewT().addText("\uC81C 2 \uAD50\uC2DC");   // 제 2 교시
    }

    private void addTitle(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd(PP_CENTER).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef(CP_TITLE);
        run.addNewT().addText("\uC218\uD559 \uC601\uC5ED");   // 수학 영역
    }

    private void addSeparatorLine(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd(PP_CENTER).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef(CP_DEFAULT);
        run.addNewT().addText("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501" +
                "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501" +
                "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501" +
                "\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
    }

    private void addSectionLabel(SectionXMLFile section, String label) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd(PP_CENTER).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef(CP_PROB_NUM);
        run.addNewT().addText(label);
    }

    private void addEmptyPara(SectionXMLFile section, String paraPrId) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd(paraPrId).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef(CP_DEFAULT);
        run.addNewT().addText("");
    }

    // ── 문제 생성 ──

    /**
     * 문제 1개를 추가한다.
     * @return 수식 변환 실패 수 (0 또는 1)
     */
    private int addProblem(SectionXMLFile section, ProblemData data) {
        int failCount = 0;

        // 문제 텍스트 Para: [번호(bold)] [본문(normal)] [점수(small)]
        Para textPara = section.addNewPara();
        textPara.idAnd(nextId()).paraPrIDRefAnd(PP_PROBLEM).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);

        Run numRun = textPara.addNewRun();
        numRun.charPrIDRef(CP_PROB_NUM);
        numRun.addNewT().addText(data.number + ". ");

        Run textRun = textPara.addNewRun();
        textRun.charPrIDRef(CP_DEFAULT);
        textRun.addNewT().addText(data.text);

        Run pointsRun = textPara.addNewRun();
        pointsRun.charPrIDRef(CP_POINTS);
        pointsRun.addNewT().addText(" [" + data.points + "\uC810]");    // [N점]

        // 수식 Para (있는 경우)
        if (data.latex != null) {
            Para eqPara = section.addNewPara();
            eqPara.idAnd(nextId()).paraPrIDRefAnd(PP_PROBLEM).styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);
            Run eqRun = eqPara.addNewRun();
            eqRun.charPrIDRef(CP_DEFAULT);

            try {
                Equation eq = EquationBuilder.fromLatex(data.latex);
                eq.textColorAnd("#000000");
                eq.baseUnitAnd(1100);
                eqRun.addRunItem(eq);
            } catch (Exception e) {
                eqRun.addNewT().addText("[" + data.latex + "]");
                failCount++;
                System.err.println("Equation conversion failed for problem " + data.number + ": " + e.getMessage());
            }
        }

        // 보기 Para (객관식인 경우)
        if (data.choices != null) {
            Para choicePara = section.addNewPara();
            choicePara.idAnd(nextId()).paraPrIDRefAnd(PP_CHOICE).styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);

            // 보기가 수식 형태인지 텍스트인지 판별
            boolean hasLatexChoices = false;
            for (String ch : data.choices) {
                if (ch.contains("\\") || ch.contains("^") || ch.contains("{")) {
                    hasLatexChoices = true;
                    break;
                }
            }

            if (hasLatexChoices) {
                // 수식이 포함된 보기: 각 보기마다 원문자 텍스트 + 수식 인라인
                Run choiceRun = choicePara.addNewRun();
                choiceRun.charPrIDRef(CP_DEFAULT);

                for (int i = 0; i < 5; i++) {
                    choiceRun.addNewT().addText(CIRCLED[i] + " ");
                    try {
                        Equation choiceEq = EquationBuilder.fromLatex(data.choices[i]);
                        choiceEq.textColorAnd("#000000");
                        choiceEq.baseUnitAnd(1000);
                        choiceRun.addRunItem(choiceEq);
                    } catch (Exception e) {
                        choiceRun.addNewT().addText(data.choices[i]);
                        failCount++;
                    }
                    if (i < 4) {
                        choiceRun.addNewT().addText("   ");
                    }
                }
            } else {
                // 텍스트만 있는 보기: 한 줄로
                Run choiceRun = choicePara.addNewRun();
                choiceRun.charPrIDRef(CP_DEFAULT);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    sb.append(CIRCLED[i]).append(" ").append(data.choices[i]);
                    if (i < 4) sb.append("   ");
                }
                choiceRun.addNewT().addText(sb.toString());
            }
        }

        return failCount;
    }

    // ── 유틸리티 ──

    private String nextId() {
        return String.valueOf(4000000 + (idCounter++));
    }
}
