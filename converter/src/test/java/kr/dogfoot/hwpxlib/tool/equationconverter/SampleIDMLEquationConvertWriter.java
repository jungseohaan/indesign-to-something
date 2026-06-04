package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.IDMLEquationExtractor;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.IDMLEquationExtractor.ExtractedEquation;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * IDML 파일의 8~20페이지에서 NP 폰트 기반 수식을 추출하여
 * HWPX 수식으로 변환하는 테스트.
 */
public class SampleIDMLEquationConvertWriter {

    private static final String IDML_STORIES_DIR = "/tmp/idml_analysis/Stories";
    private static final String OUTPUT_PATH = "testFile/tool/idml_equations.hwpx";

    /**
     * Pages 8-20에 해당하는 Story 파일 목록 (NP 폰트가 포함된 것들만)
     */
    private static final String[] EQUATION_STORIES = {
            // Pages 8-9 (Spread_u4cb)
            "Story_u658.xml",
            "Story_u68c.xml",
            // Pages 10-11 (Spread_u626)
            "Story_u111cf.xml",
            "Story_u11378.xml",
            "Story_u11390.xml",
            "Story_ue6a8.xml",
            "Story_ue6d8.xml",
            "Story_ue720.xml",
            // Pages 12-13 (Spread_u111b2)
            "Story_u11733.xml",
            "Story_u1180c.xml",
            "Story_u1191c.xml",
            "Story_u119c5.xml",
            "Story_u11abc.xml",
            // Pages 14-15 (Spread_u1b454)
            "Story_u1b467.xml",
            "Story_u1b5a3.xml",
            "Story_u1b6f0.xml",
            "Story_u1bb70.xml",
            "Story_u11c39.xml",
            "Story_u11d9c.xml",
            "Story_u11dc8.xml",
            // Pages 16-17 (Spread_u111b3)
            "Story_u1229e.xml",
            "Story_u12588.xml",
            "Story_u12acb.xml",
            "Story_u12e1f.xml",
            "Story_u12e5e.xml",
            // Pages 18-19 (Spread_u12263)
            "Story_u1334f.xml",
            "Story_u13504.xml",
            "Story_u135e8.xml",
            "Story_u13604.xml",
            "Story_u138ec.xml",
            "Story_u13b66.xml",
            "Story_u13e50.xml",
            "Story_u13f70.xml",
            "Story_u13f8f.xml",
            "Story_u13fc8.xml",
            // Page 20 (Spread_u12264)
            "Story_u144a8.xml",
            "Story_u144d3.xml",
            "Story_u14908.xml",
            "Story_u14b93.xml",
            "Story_u1ddc7.xml",
            "Story_u1df07.xml",
            "Story_u211f1.xml",
            "Story_u2397c.xml",
            "Story_u23a82.xml",
            "Story_u23f2a.xml",
            "Story_u24240.xml",
            "Story_u24263.xml",
            "Story_u24b8e.xml",
    };

    @Test
    public void convertIDMLEquationsToHwpx() throws Exception {
        // IDML Stories 디렉토리 존재 확인
        File storiesDir = new File(IDML_STORIES_DIR);
        if (!storiesDir.exists()) {
            System.out.println("IDML stories directory not found: " + IDML_STORIES_DIR);
            System.out.println("Please extract the IDML file first.");
            return;
        }

        IDMLEquationExtractor extractor = new IDMLEquationExtractor(IDML_STORIES_DIR);

        // 모든 Story에서 수식 추출
        List<ExtractedEquation> allEquations = new ArrayList<ExtractedEquation>();
        for (String storyFile : EQUATION_STORIES) {
            List<ExtractedEquation> eqs = extractor.extractFromStory(storyFile);
            if (!eqs.isEmpty()) {
                allEquations.addAll(eqs);
            }
        }

        System.out.println("=== IDML 수식 추출 결과 ===");
        System.out.println("총 추출된 수식 수: " + allEquations.size());
        for (int i = 0; i < allEquations.size(); i++) {
            System.out.println("[" + (i + 1) + "] " + allEquations.get(i).hwpScript);
        }

        // HWPX 파일 생성
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // 제목 추가
        addTextPara(section, "IDML NP-Font 수식 → HWPX 수식 변환 결과 (p.8~20)");
        addTextPara(section, "총 " + allEquations.size() + "개 수식");
        addTextPara(section, "");

        int count = 0;
        for (int i = 0; i < allEquations.size(); i++) {
            ExtractedEquation eq = allEquations.get(i);
            // 수식 번호와 원본 스크립트 표시
            addTextPara(section, "[" + (i + 1) + "] " + eq.hwpScript);
            // 수식 객체 추가
            addEquationPara(section, eq.hwpScript);
            addTextPara(section, "");
            count++;
        }

        // 파일 저장
        HWPXWriter.toFilepath(hwpxFile, OUTPUT_PATH);
        System.out.println("\nHWPX 파일 저장: " + OUTPUT_PATH);
        System.out.println("총 수식 수: " + count);

        // 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(OUTPUT_PATH);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        int paraCount = readSection.countOfPara();
        System.out.println("라운드트립 검증 - 총 단락 수: " + paraCount);
        Assert.assertTrue("수식이 하나 이상 추출되어야 합니다", count > 0);
        Assert.assertTrue("파라그래프가 존재해야 합니다", paraCount > 0);
    }

    private void addTextPara(SectionXMLFile section, String text) {
        Para para = section.addNewPara();
        para.idAnd(String.valueOf(System.nanoTime()))
                .paraPrIDRefAnd("0")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText(text);
    }

    private void addEquationPara(SectionXMLFile section, String hwpScript) {
        Para para = section.addNewPara();
        para.idAnd(String.valueOf(System.nanoTime()))
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Equation equation = EquationBuilder.fromHwpScript(hwpScript);
        run.addRunItem(equation);
    }
}
