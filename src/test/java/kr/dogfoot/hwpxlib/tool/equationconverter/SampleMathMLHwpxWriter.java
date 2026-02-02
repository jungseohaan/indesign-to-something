package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 300개 유명 수학 공식을 MathML 로 변환하여 HWPX 파일을 생성하는 테스트.
 */
public class SampleMathMLHwpxWriter {

    @Test
    public void createSampleHwpx() throws Exception {
        String[][] equations = SampleMathMLEquationData.getEquations();

        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < equations.length; i++) {
            String label = equations[i][0];
            String mathml = equations[i][1];

            addTextPara(section, (i + 1) + ". " + label);

            try {
                Equation eq = EquationBuilder.fromMathML(mathml);
                addEquationPara(section, eq);
                successCount++;
            } catch (Exception e) {
                addTextPara(section, "[ERROR: " + e.getMessage() + "]");
                failCount++;
                System.err.println("FAIL #" + (i + 1) + " " + label + ": " + e.getMessage());
            }

            addTextPara(section, "");
        }

        String filepath = "testFile/tool/sample_mathml_equations.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== MathML Sample HWPX Generation Result ===");
        System.out.println("Total: " + equations.length);
        System.out.println("Success: " + successCount);
        System.out.println("Failed: " + failCount);
        System.out.println("File: " + filepath);

        // Round-trip 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs", readSection.countOfPara() > 1);

        Assert.assertEquals("All equations should convert successfully", 0, failCount);
        System.out.println("\nAll " + successCount + " MathML equations converted and written successfully!");
    }

    private void addTextPara(SectionXMLFile section, String text) {
        Para para = section.addNewPara();
        para.idAnd(String.valueOf(System.nanoTime()))
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText(text);
    }

    private void addEquationPara(SectionXMLFile section, Equation equation) {
        Para para = section.addNewPara();
        para.idAnd(String.valueOf(System.nanoTime()))
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addRunItem(equation);
    }
}
