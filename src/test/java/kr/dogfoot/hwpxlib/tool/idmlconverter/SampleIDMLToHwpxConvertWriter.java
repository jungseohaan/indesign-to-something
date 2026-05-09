package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSerializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.legacy.IDMLNormalizer;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * IDML -> HWPX 변환 E2E 테스트 (AST 파이프라인).
 * /tmp/idml_analysis/ 에 미리 해제된 IDML 구조가 있어야 실행됨.
 *
 * 테스트 항목:
 * 1. 전체 파이프라인: IDML -> AST -> HWPX -> 파일 저장 -> 라운드트립
 * 2. 페이지 범위 필터링 (8~20)
 * 3. AST JSON 직렬화
 * 4. 파사드 API (IDMLToHwpxConverter)
 */
public class SampleIDMLToHwpxConvertWriter {

    private static final String IDML_DIR = "/tmp/idml_analysis";
    private static final String OUTPUT_DIR = "testFile/tool/";
    private static final String OUTPUT_FULL = OUTPUT_DIR + "idml_converted_full.hwpx";
    private static final String OUTPUT_RANGE = OUTPUT_DIR + "idml_converted_p8_20.hwpx";

    private IDMLDocument idmlDoc;

    @Before
    public void setUp() throws Exception {
        File dir = new File(IDML_DIR);
        Assume.assumeTrue("IDML test data not found: " + IDML_DIR, dir.exists());
        idmlDoc = IDMLLoader.loadFromDirectory(dir);
    }

    // -- 테스트 1: 전체 페이지 변환 --

    @Test
    public void testFullConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults();
        String sourceFileName = "test.idml";

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName);
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);

        System.out.println("=== 전체 변환 결과 ===");
        System.out.println(result.summary());

        Assert.assertNotNull(result.hwpxFile());
        Assert.assertTrue("페이지가 1개 이상 변환되어야 합니다",
                result.pagesConverted() > 0);
        Assert.assertTrue("프레임이 1개 이상 변환되어야 합니다",
                result.framesConverted() > 0);

        // HWPX 파일 저장
        HWPXWriter.toFilepath(result.hwpxFile(), OUTPUT_FULL);
        System.out.println("저장: " + OUTPUT_FULL);

        // 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(OUTPUT_FULL);
        Assert.assertNotNull(readBack);
        Assert.assertTrue(readBack.sectionXMLFileList().count() > 0);

        SectionXMLFile section = readBack.sectionXMLFileList().get(0);
        int paraCount = section.countOfPara();
        System.out.println("라운드트립 단락 수: " + paraCount);
        Assert.assertTrue("단락이 존재해야 합니다", paraCount > 0);

        if (result.hasWarnings()) {
            System.out.println("경고 (" + result.warnings().size() + "건):");
            for (String w : result.warnings()) {
                System.out.println("  - " + w);
            }
        }
    }

    // -- 테스트 2: 페이지 범위 (8~20) 변환 --

    @Test
    public void testPageRangeConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(20);

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);

        System.out.println("=== 페이지 8~20 변환 결과 ===");
        System.out.println(result.summary());

        Assert.assertNotNull(result.hwpxFile());
        Assert.assertTrue("페이지가 1개 이상 변환되어야 합니다",
                result.pagesConverted() > 0);
        Assert.assertTrue("범위 필터: 최대 13페이지",
                result.pagesConverted() <= 13);

        // 파일 저장 + 라운드트립
        HWPXWriter.toFilepath(result.hwpxFile(), OUTPUT_RANGE);
        System.out.println("저장: " + OUTPUT_RANGE);

        HWPXFile readBack = HWPXReader.fromFilepath(OUTPUT_RANGE);
        Assert.assertNotNull(readBack);
        Assert.assertTrue(readBack.sectionXMLFileList().get(0).countOfPara() > 0);
    }

    // -- 테스트 3: AST JSON 직렬화 --

    @Test
    public void testAstJsonSerialization() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(10);

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");

        // AST -> JSON
        String json = ASTSerializer.toJson(astDoc);
        Assert.assertNotNull(json);
        Assert.assertTrue("JSON이 비어있지 않아야 합니다", json.length() > 100);
        System.out.println("=== AST JSON 직렬화 ===");
        System.out.println("JSON 크기: " + json.length() + " chars");
    }

    // -- 테스트 4: 수식 포함 변환 --

    @Test
    public void testEquationConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(20)
                .includeEquations(true);

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);

        System.out.println("=== 수식 변환 결과 ===");
        System.out.println("수식 수: " + result.equationsConverted());
        System.out.println(result.summary());

        Assert.assertNotNull(result.hwpxFile());
        System.out.println("수식 변환 수: " + result.equationsConverted());
    }

    // -- 테스트 5: 스타일 포함 변환 --

    @Test
    public void testStyleConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .includeStyles(true);

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);

        Assert.assertNotNull(result.hwpxFile());
        Assert.assertTrue("스타일이 변환되어야 합니다",
                result.stylesConverted() > 0);

        // CharPr/ParaPr/Style이 추가되었는지 확인
        int charPrCount = result.hwpxFile().headerXMLFile().refList().charProperties().count();
        int paraPrCount = result.hwpxFile().headerXMLFile().refList().paraProperties().count();
        int styleCount = result.hwpxFile().headerXMLFile().refList().styles().count();

        System.out.println("=== 스타일 정보 ===");
        System.out.println("CharPr: " + charPrCount + ", ParaPr: " + paraPrCount + ", Style: " + styleCount);
        Assert.assertTrue("CharPr이 기본(7) 이상이어야 합니다", charPrCount > 7);
        Assert.assertTrue("ParaPr이 기본(16) 이상이어야 합니다", paraPrCount > 16);
        Assert.assertTrue("Style이 기본(18) 이상이어야 합니다", styleCount > 18);
    }

    // -- 테스트 6: 변환 통계 검증 --

    @Test
    public void testConvertStatistics() throws Exception {
        ConvertOptions options = ConvertOptions.defaults();

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);

        System.out.println("=== 변환 통계 ===");
        System.out.println(result.summary());

        // summary 문자열 검증
        String summary = result.summary();
        Assert.assertTrue(summary.contains("pages="));
        Assert.assertTrue(summary.contains("frames="));
        Assert.assertTrue(summary.contains("equations="));
        Assert.assertTrue(summary.contains("images="));
        Assert.assertTrue(summary.contains("styles="));
        Assert.assertTrue(summary.contains("warnings="));
    }

    // -- 테스트 7: HWPX 파일 구조 검증 --

    @Test
    public void testHwpxStructure() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(10);

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);
        HWPXFile hwpx = result.hwpxFile();

        // 필수 구조 검증
        Assert.assertNotNull(hwpx.settingsXMLFile());
        Assert.assertNotNull(hwpx.versionXMLFile());
        Assert.assertNotNull(hwpx.containerXMLFile());
        Assert.assertNotNull(hwpx.contentHPFFile());
        Assert.assertNotNull(hwpx.headerXMLFile());
        Assert.assertNotNull(hwpx.headerXMLFile().refList());
        Assert.assertNotNull(hwpx.headerXMLFile().refList().fontfaces());
        Assert.assertTrue(hwpx.headerXMLFile().refList().charProperties().count() > 0);
        Assert.assertTrue(hwpx.headerXMLFile().refList().paraProperties().count() > 0);
        Assert.assertTrue(hwpx.headerXMLFile().refList().styles().count() > 0);
        Assert.assertTrue(hwpx.sectionXMLFileList().count() > 0);

        // 섹션 내용 검증
        SectionXMLFile section = hwpx.sectionXMLFileList().get(0);
        Assert.assertTrue("단락이 존재해야 합니다", section.countOfPara() > 0);

        System.out.println("=== HWPX 구조 검증 ===");
        System.out.println("CharPr: " + hwpx.headerXMLFile().refList().charProperties().count());
        System.out.println("ParaPr: " + hwpx.headerXMLFile().refList().paraProperties().count());
        System.out.println("Styles: " + hwpx.headerXMLFile().refList().styles().count());
        System.out.println("Sections: " + hwpx.sectionXMLFileList().count());
        System.out.println("Paragraphs: " + section.countOfPara());
    }

    // -- 테스트 8: 수식 제외 옵션 --

    @Test
    public void testWithoutEquations() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(12)
                .includeEquations(false);

        ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, "test.idml");
        ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);

        Assert.assertNotNull(result.hwpxFile());
        Assert.assertEquals("수식이 0이어야 합니다", 0, result.equationsConverted());
    }
}
