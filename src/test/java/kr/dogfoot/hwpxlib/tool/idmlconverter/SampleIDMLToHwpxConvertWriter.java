package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IDMLToIntermediateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IntermediateToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.JsonDeserializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.JsonSerializer;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * IDML → HWPX 변환 E2E 테스트.
 * /tmp/idml_analysis/ 에 미리 해제된 IDML 구조가 있어야 실행됨.
 *
 * 테스트 항목:
 * 1. 전체 파이프라인: IDML → Intermediate → HWPX → 파일 저장 → 라운드트립
 * 2. 페이지 범위 필터링 (8~20)
 * 3. JSON 직렬화/역직렬화 라운드트립
 * 4. 파사드 API (IDMLToHwpxConverter)
 */
public class SampleIDMLToHwpxConvertWriter {

    private static final String IDML_DIR = "/tmp/idml_analysis";
    private static final String OUTPUT_DIR = "testFile/tool/";
    private static final String OUTPUT_FULL = OUTPUT_DIR + "idml_converted_full.hwpx";
    private static final String OUTPUT_RANGE = OUTPUT_DIR + "idml_converted_p8_20.hwpx";
    private static final String OUTPUT_JSON = OUTPUT_DIR + "idml_converted_from_json.hwpx";

    private IDMLDocument idmlDoc;

    @Before
    public void setUp() throws Exception {
        File dir = new File(IDML_DIR);
        Assume.assumeTrue("IDML test data not found: " + IDML_DIR, dir.exists());
        idmlDoc = IDMLLoader.loadFromDirectory(dir);
    }

    // ── 테스트 1: 전체 페이지 변환 ──

    @Test
    public void testFullConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults();
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);

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

    // ── 테스트 2: 페이지 범위 (8~20) 변환 ──

    @Test
    public void testPageRangeConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(20);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);

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

    // ── 테스트 3: JSON 직렬화/역직렬화 라운드트립 ──

    @Test
    public void testJsonRoundTrip() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(10);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();

        // Intermediate → JSON
        String json = JsonSerializer.toJson(intermediate);
        Assert.assertNotNull(json);
        Assert.assertTrue("JSON이 비어있지 않아야 합니다", json.length() > 100);
        System.out.println("=== JSON 라운드트립 ===");
        System.out.println("JSON 크기: " + json.length() + " chars");

        // JSON → Intermediate
        IntermediateDocument restored = JsonDeserializer.fromJson(json);
        Assert.assertNotNull(restored);
        Assert.assertEquals(intermediate.pages().size(), restored.pages().size());
        Assert.assertEquals(intermediate.fonts().size(), restored.fonts().size());
        Assert.assertEquals(intermediate.paragraphStyles().size(), restored.paragraphStyles().size());

        // JSON → HWPX
        ConvertResult result = IntermediateToHwpxConverter.convert(restored);
        Assert.assertNotNull(result.hwpxFile());
        Assert.assertTrue(result.pagesConverted() > 0);

        // 저장 + 라운드트립
        HWPXWriter.toFilepath(result.hwpxFile(), OUTPUT_JSON);
        System.out.println("저장: " + OUTPUT_JSON);

        HWPXFile readBack = HWPXReader.fromFilepath(OUTPUT_JSON);
        Assert.assertNotNull(readBack);
        Assert.assertTrue(readBack.sectionXMLFileList().get(0).countOfPara() > 0);
    }

    // ── 테스트 4: 수식 포함 변환 ──

    @Test
    public void testEquationConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(20)
                .includeEquations(true);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);

        System.out.println("=== 수식 변환 결과 ===");
        System.out.println("수식 수: " + result.equationsConverted());
        System.out.println(result.summary());

        Assert.assertNotNull(result.hwpxFile());
        // 8~20페이지에는 NP 폰트 수식이 포함되어 있어야 함
        System.out.println("수식 변환 수: " + result.equationsConverted());
    }

    // ── 테스트 5: 스타일 포함 변환 ──

    @Test
    public void testStyleConversion() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .includeStyles(true);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();

        System.out.println("=== 스타일 정보 ===");
        System.out.println("단락 스타일 수: " + intermediate.paragraphStyles().size());
        System.out.println("문자 스타일 수: " + intermediate.characterStyles().size());
        System.out.println("폰트 수: " + intermediate.fonts().size());

        Assert.assertTrue("단락 스타일이 존재해야 합니다",
                intermediate.paragraphStyles().size() > 0);

        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
        Assert.assertTrue("스타일이 변환되어야 합니다",
                result.stylesConverted() > 0);

        // CharPr/ParaPr/Style이 추가되었는지 확인
        int charPrCount = result.hwpxFile().headerXMLFile().refList().charProperties().count();
        int paraPrCount = result.hwpxFile().headerXMLFile().refList().paraProperties().count();
        int styleCount = result.hwpxFile().headerXMLFile().refList().styles().count();

        System.out.println("CharPr: " + charPrCount + ", ParaPr: " + paraPrCount + ", Style: " + styleCount);
        Assert.assertTrue("CharPr이 기본(7) 이상이어야 합니다", charPrCount > 7);
        Assert.assertTrue("ParaPr이 기본(16) 이상이어야 합니다", paraPrCount > 16);
        Assert.assertTrue("Style이 기본(18) 이상이어야 합니다", styleCount > 18);
    }

    // ── 테스트 6: 변환 통계 검증 ──

    @Test
    public void testConvertStatistics() throws Exception {
        ConvertOptions options = ConvertOptions.defaults();
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);

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

    // ── 테스트 7: HWPX 파일 구조 검증 ──

    @Test
    public void testHwpxStructure() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(10);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
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

    // ── 테스트 8: 수식 제외 옵션 ──

    @Test
    public void testWithoutEquations() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(12)
                .includeEquations(false);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();

        // 수식 제외 옵션이면 수식 단락이 없어야 함
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
        Assert.assertNotNull(result.hwpxFile());
        Assert.assertEquals("수식이 0이어야 합니다", 0, result.equationsConverted());
    }

    // ── 테스트 9: 파사드 fromJson 경로 ──

    @Test
    public void testFacadeFromJson() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(10);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        String json = JsonSerializer.toJson(intermediate);

        // IDMLToHwpxConverter.fromJson() 파사드 테스트
        HWPXFile hwpx = IDMLToHwpxConverter.fromJson(json);
        Assert.assertNotNull(hwpx);
        Assert.assertTrue(hwpx.sectionXMLFileList().count() > 0);
        Assert.assertTrue(hwpx.sectionXMLFileList().get(0).countOfPara() > 0);

        System.out.println("=== 파사드 fromJson 테스트 ===");
        System.out.println("단락 수: " + hwpx.sectionXMLFileList().get(0).countOfPara());
    }

    // ── 테스트 10: 파사드 fromJson + 파일 저장 ──

    @Test
    public void testFacadeFromJsonToFile() throws Exception {
        ConvertOptions options = ConvertOptions.defaults()
                .startPage(8)
                .endPage(10);
        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();
        String json = JsonSerializer.toJson(intermediate);

        String outputPath = OUTPUT_DIR + "idml_facade_json_output.hwpx";
        IDMLToHwpxConverter.fromJson(json, outputPath);

        // 파일 존재 확인
        Assert.assertTrue("출력 파일이 존재해야 합니다", new File(outputPath).exists());

        // 라운드트립
        HWPXFile readBack = HWPXReader.fromFilepath(outputPath);
        Assert.assertNotNull(readBack);
        Assert.assertTrue(readBack.sectionXMLFileList().get(0).countOfPara() > 0);

        System.out.println("=== 파사드 fromJson → 파일 저장 테스트 ===");
        System.out.println("출력: " + outputPath);
    }
}
