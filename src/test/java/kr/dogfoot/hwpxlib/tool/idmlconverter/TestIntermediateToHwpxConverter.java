package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IntermediateToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import org.junit.Assert;
import org.junit.Test;

public class TestIntermediateToHwpxConverter {

    // ── 헬퍼 ──

    private IntermediateDocument createMinimalDoc() {
        IntermediateDocument doc = new IntermediateDocument();
        doc.sourceFormat("IDML");
        doc.sourceFile("test.idml");

        DocumentLayout layout = new DocumentLayout();
        layout.defaultPageWidth(59528);
        layout.defaultPageHeight(84188);
        layout.marginTop(5668);
        layout.marginBottom(4252);
        layout.marginLeft(8504);
        layout.marginRight(8504);
        doc.layout(layout);

        return doc;
    }

    private IntermediatePage createPageWithText(int pageNum, String text) {
        IntermediatePage page = new IntermediatePage();
        page.pageNumber(pageNum);
        page.pageWidth(59528);
        page.pageHeight(84188);

        IntermediateFrame frame = new IntermediateFrame();
        frame.frameId("frame_1");
        frame.frameType("text");
        frame.x(0);
        frame.y(0);
        frame.width(42520);
        frame.height(70000);

        IntermediateParagraph para = new IntermediateParagraph();
        IntermediateTextRun run = new IntermediateTextRun();
        run.text(text);
        para.addRun(run);
        frame.addParagraph(para);

        page.addFrame(frame);
        return page;
    }

    // ── 테스트: 빈 문서 ──

    @Test
    public void testConvert_EmptyDocument() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertNotNull(result);
        Assert.assertNotNull(result.hwpxFile());
        Assert.assertEquals(0, result.pagesConverted());
        // 빈 문서에도 최소 1 Para 존재
        SectionXMLFile section = result.hwpxFile().sectionXMLFileList().get(0);
        Assert.assertTrue(section.countOfPara() > 0);
    }

    // ── 테스트: 단일 페이지 + 텍스트 ──

    @Test
    public void testConvert_SinglePageText() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();
        doc.addPage(createPageWithText(1, "Hello World"));

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertEquals(1, result.pagesConverted());
        Assert.assertEquals(1, result.framesConverted());
        Assert.assertNotNull(result.hwpxFile());

        // 섹션에 Para가 생성되었는지 확인
        SectionXMLFile section = result.hwpxFile().sectionXMLFileList().get(0);
        Assert.assertTrue(section.countOfPara() >= 2); // SecPr para + text para
    }

    // ── 테스트: 복수 페이지 ──

    @Test
    public void testConvert_MultiplePages() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();
        doc.addPage(createPageWithText(1, "Page 1 text"));
        doc.addPage(createPageWithText(2, "Page 2 text"));
        doc.addPage(createPageWithText(3, "Page 3 text"));

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertEquals(3, result.pagesConverted());
        Assert.assertEquals(3, result.framesConverted());
    }

    // ── 테스트: 스타일 포함 ──

    @Test
    public void testConvert_WithStyles() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        // 단락 스타일 추가
        IntermediateStyleDef paraStyle = new IntermediateStyleDef();
        paraStyle.id("pstyle_0");
        paraStyle.name("Body");
        paraStyle.type("paragraph");
        paraStyle.fontFamily("Minion Pro");
        paraStyle.fontSizeHwpunits(1000);
        paraStyle.alignment("justify");
        paraStyle.bold(false);
        paraStyle.italic(false);
        doc.addParagraphStyle(paraStyle);

        // 페이지에 스타일 참조하는 단락
        IntermediatePage page = new IntermediatePage();
        page.pageNumber(1);
        page.pageWidth(59528);
        page.pageHeight(84188);

        IntermediateFrame frame = new IntermediateFrame();
        frame.frameId("frame_1");
        frame.frameType("text");

        IntermediateParagraph para = new IntermediateParagraph();
        para.paragraphStyleRef("pstyle_0");
        IntermediateTextRun run = new IntermediateTextRun();
        run.text("Styled text");
        para.addRun(run);
        frame.addParagraph(para);
        page.addFrame(frame);
        doc.addPage(page);

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertEquals(1, result.pagesConverted());
        Assert.assertTrue(result.stylesConverted() > 0);
    }

    // ── 테스트: 폰트 등록 ──

    @Test
    public void testConvert_WithFonts() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        IntermediateFontDef font = new IntermediateFontDef();
        font.id("font_0");
        font.familyName("Minion Pro");
        font.styleName("Regular");
        font.fontType("OTF");
        font.hwpxMappedName("함초롬바탕");
        doc.addFont(font);

        doc.addPage(createPageWithText(1, "Font test"));

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertNotNull(result.hwpxFile());
        Assert.assertEquals(1, result.pagesConverted());
    }

    // ── 테스트: 수식 ──

    @Test
    public void testConvert_WithEquation() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        IntermediatePage page = new IntermediatePage();
        page.pageNumber(1);
        page.pageWidth(59528);
        page.pageHeight(84188);

        IntermediateFrame frame = new IntermediateFrame();
        frame.frameId("frame_eq");
        frame.frameType("text");

        // 수식 단락
        IntermediateParagraph eqPara = new IntermediateParagraph();
        eqPara.equation(new IntermediateEquation("{a} over {b}", "NP_FONT"));
        frame.addParagraph(eqPara);

        // 일반 텍스트 단락
        IntermediateParagraph textPara = new IntermediateParagraph();
        IntermediateTextRun run = new IntermediateTextRun();
        run.text("Normal text");
        textPara.addRun(run);
        frame.addParagraph(textPara);

        page.addFrame(frame);
        doc.addPage(page);

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertEquals(1, result.pagesConverted());
        Assert.assertEquals(1, result.equationsConverted());
    }

    // ── 테스트: 다른 페이지 크기 ──

    @Test
    public void testConvert_DifferentPageSizes() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        // A4 페이지
        IntermediatePage page1 = createPageWithText(1, "A4 page");
        page1.pageWidth(59528);
        page1.pageHeight(84188);
        doc.addPage(page1);

        // A5 페이지 (다른 크기)
        IntermediatePage page2 = createPageWithText(2, "A5 page");
        page2.pageWidth(42120);
        page2.pageHeight(59528);
        doc.addPage(page2);

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertEquals(2, result.pagesConverted());
        // 페이지 크기가 다르므로 2개의 SecPr이 생성되어야 함
        SectionXMLFile section = result.hwpxFile().sectionXMLFileList().get(0);
        Assert.assertTrue(section.countOfPara() >= 4);
    }

    // ── 테스트: 런 오버라이드 ──

    @Test
    public void testConvert_WithRunOverrides() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        IntermediatePage page = new IntermediatePage();
        page.pageNumber(1);
        page.pageWidth(59528);
        page.pageHeight(84188);

        IntermediateFrame frame = new IntermediateFrame();
        frame.frameId("frame_1");
        frame.frameType("text");

        IntermediateParagraph para = new IntermediateParagraph();

        // 일반 런
        IntermediateTextRun run1 = new IntermediateTextRun();
        run1.text("Normal ");
        para.addRun(run1);

        // Bold 오버라이드 런
        IntermediateTextRun run2 = new IntermediateTextRun();
        run2.text("Bold");
        run2.bold(true);
        run2.fontSizeHwpunits(1200);
        run2.textColor("#FF0000");
        para.addRun(run2);

        frame.addParagraph(para);
        page.addFrame(frame);
        doc.addPage(page);

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        Assert.assertEquals(1, result.pagesConverted());
        Assert.assertNotNull(result.hwpxFile());
        // CharPr이 추가로 생성되었을 것
        int charPrCount = result.hwpxFile().headerXMLFile().refList().charProperties().count();
        Assert.assertTrue(charPrCount > 7); // 기본 7개 + 추가분
    }

    // ── 테스트: 빈 단락 ──

    @Test
    public void testConvert_EmptyParagraph() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();

        IntermediatePage page = new IntermediatePage();
        page.pageNumber(1);
        page.pageWidth(59528);
        page.pageHeight(84188);

        IntermediateFrame frame = new IntermediateFrame();
        frame.frameId("frame_1");
        frame.frameType("text");

        // 런 없는 빈 단락
        IntermediateParagraph emptyPara = new IntermediateParagraph();
        frame.addParagraph(emptyPara);

        // 런 있는 단락
        IntermediateParagraph textPara = new IntermediateParagraph();
        IntermediateTextRun run = new IntermediateTextRun();
        run.text("After empty");
        textPara.addRun(run);
        frame.addParagraph(textPara);

        page.addFrame(frame);
        doc.addPage(page);

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);
        Assert.assertEquals(1, result.pagesConverted());
    }

    // ── 테스트: ConvertResult summary ──

    @Test
    public void testConvertResult_Summary() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();
        doc.addPage(createPageWithText(1, "Test"));

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);

        String summary = result.summary();
        Assert.assertNotNull(summary);
        Assert.assertTrue(summary.contains("pages=1"));
        Assert.assertTrue(summary.contains("frames=1"));
    }

    // ── 테스트: HWPXFile 구조 검증 ──

    @Test
    public void testConvert_HwpxFileStructure() throws ConvertException {
        IntermediateDocument doc = createMinimalDoc();
        doc.addPage(createPageWithText(1, "Structure test"));

        ConvertResult result = IntermediateToHwpxConverter.convert(doc);
        HWPXFile hwpx = result.hwpxFile();

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
    }
}
