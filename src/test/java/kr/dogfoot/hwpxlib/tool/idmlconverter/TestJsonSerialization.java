package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import org.junit.Assert;
import org.junit.Test;

public class TestJsonSerialization {

    /**
     * 테스트용 IntermediateDocument를 생성한다.
     */
    private IntermediateDocument createSampleDocument() {
        IntermediateDocument doc = new IntermediateDocument();
        doc.sourceFormat("IDML");
        doc.sourceFile("sample.idml");

        // Layout
        DocumentLayout layout = new DocumentLayout();
        layout.defaultPageWidth(11906);
        layout.defaultPageHeight(16838);
        layout.marginTop(2835);
        layout.marginBottom(2126);
        layout.marginLeft(4252);
        layout.marginRight(4252);
        doc.layout(layout);

        // Font
        IntermediateFontDef font = new IntermediateFontDef();
        font.id("font_0");
        font.familyName("MinionPro");
        font.styleName("Regular");
        font.fontType("OTF");
        font.hwpxMappedName("함초롬바탕");
        doc.addFont(font);

        // ParagraphStyle
        IntermediateStyleDef pStyle = new IntermediateStyleDef();
        pStyle.id("pstyle_0");
        pStyle.name("Body");
        pStyle.type("paragraph");
        pStyle.fontFamily("MinionPro");
        pStyle.fontSizeHwpunits(1000);
        pStyle.textColor("#000000");
        pStyle.bold(false);
        pStyle.italic(false);
        pStyle.alignment("justify");
        pStyle.firstLineIndent(0L);
        pStyle.leftMargin(0L);
        pStyle.rightMargin(0L);
        pStyle.spaceBefore(0L);
        pStyle.spaceAfter(200L);
        pStyle.lineSpacingPercent(120);
        pStyle.lineSpacingType("percent");
        doc.addParagraphStyle(pStyle);

        // CharacterStyle
        IntermediateStyleDef cStyle = new IntermediateStyleDef();
        cStyle.id("cstyle_0");
        cStyle.name("Default");
        cStyle.type("character");
        doc.addCharacterStyle(cStyle);

        // Page with text frame
        IntermediatePage page = new IntermediatePage();
        page.pageNumber(8);
        page.pageWidth(11906);
        page.pageHeight(16838);

        // Text frame
        IntermediateFrame textFrame = new IntermediateFrame();
        textFrame.frameId("frame_u123");
        textFrame.frameType("text");
        textFrame.x(4252);
        textFrame.y(2835);
        textFrame.width(3402);
        textFrame.height(11977);
        textFrame.zOrder(0);

        // Paragraph with text
        IntermediateParagraph para1 = new IntermediateParagraph();
        para1.paragraphStyleRef("pstyle_0");
        IntermediateTextRun run1 = new IntermediateTextRun();
        run1.characterStyleRef("cstyle_0");
        run1.text("본문 텍스트");
        para1.addRun(run1);
        textFrame.addParagraph(para1);

        // Paragraph with equation
        IntermediateParagraph para2 = new IntermediateParagraph();
        para2.paragraphStyleRef("pstyle_0");
        para2.equation(new IntermediateEquation("lim _{n -> inf} a _{n}", "NP_FONT"));
        textFrame.addParagraph(para2);

        page.addFrame(textFrame);

        // Image frame
        IntermediateFrame imageFrame = new IntermediateFrame();
        imageFrame.frameId("frame_u456");
        imageFrame.frameType("image");
        imageFrame.x(4252);
        imageFrame.y(14000);
        imageFrame.width(3402);
        imageFrame.height(2268);
        imageFrame.zOrder(1);

        IntermediateImage img = new IntermediateImage();
        img.imageId("img_1");
        img.originalPath("Links/figure.psd");
        img.format("png");
        img.pixelWidth(800);
        img.pixelHeight(600);
        img.displayWidth(3402);
        img.displayHeight(2268);
        img.base64Data("iVBORw0KGgoAAAANSUhEUg==");
        imageFrame.image(img);

        page.addFrame(imageFrame);
        doc.addPage(page);

        return doc;
    }

    @Test
    public void testSerialize() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertNotNull(json);
        Assert.assertTrue(json.contains("\"version\""));
        Assert.assertTrue(json.contains("\"sourceFormat\""));
        Assert.assertTrue(json.contains("IDML"));
        Assert.assertTrue(json.contains("sample.idml"));
    }

    @Test
    public void testSerializeContainsLayout() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertTrue(json.contains("\"defaultPageWidth\""));
        Assert.assertTrue(json.contains("11906"));
        Assert.assertTrue(json.contains("16838"));
    }

    @Test
    public void testSerializeContainsFont() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertTrue(json.contains("MinionPro"));
        Assert.assertTrue(json.contains("함초롬바탕"));
    }

    @Test
    public void testSerializeContainsStyle() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertTrue(json.contains("pstyle_0"));
        Assert.assertTrue(json.contains("justify"));
    }

    @Test
    public void testSerializeContainsText() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertTrue(json.contains("본문 텍스트"));
    }

    @Test
    public void testSerializeContainsEquation() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertTrue(json.contains("lim _{n -> inf} a _{n}"));
        Assert.assertTrue(json.contains("NP_FONT"));
    }

    @Test
    public void testSerializeContainsImage() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String json = JsonSerializer.toJson(doc);
        Assert.assertTrue(json.contains("img_1"));
        Assert.assertTrue(json.contains("Links/figure.psd"));
        Assert.assertTrue(json.contains("iVBORw0KGgoAAAANSUhEUg=="));
    }

    @Test
    public void testCompactSerialize() throws Exception {
        IntermediateDocument doc = createSampleDocument();
        String compact = JsonSerializer.toJsonCompact(doc);
        String pretty = JsonSerializer.toJson(doc);
        Assert.assertNotNull(compact);
        // Compact should be shorter (no indentation)
        Assert.assertTrue(compact.length() < pretty.length());
        // Both should contain same data
        Assert.assertTrue(compact.contains("본문 텍스트"));
    }

    @Test
    public void testRoundtrip() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        Assert.assertNotNull(restored);
        Assert.assertEquals("1.0", restored.version());
        Assert.assertEquals("IDML", restored.sourceFormat());
        Assert.assertEquals("sample.idml", restored.sourceFile());
    }

    @Test
    public void testRoundtripLayout() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        Assert.assertNotNull(restored.layout());
        Assert.assertEquals(11906, restored.layout().defaultPageWidth());
        Assert.assertEquals(16838, restored.layout().defaultPageHeight());
        Assert.assertEquals(2835, restored.layout().marginTop());
        Assert.assertEquals(4252, restored.layout().marginLeft());
    }

    @Test
    public void testRoundtripFonts() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        Assert.assertEquals(1, restored.fonts().size());
        IntermediateFontDef font = restored.fonts().get(0);
        Assert.assertEquals("font_0", font.id());
        Assert.assertEquals("MinionPro", font.familyName());
        Assert.assertEquals("함초롬바탕", font.hwpxMappedName());
    }

    @Test
    public void testRoundtripStyles() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        Assert.assertEquals(1, restored.paragraphStyles().size());
        IntermediateStyleDef pStyle = restored.paragraphStyles().get(0);
        Assert.assertEquals("pstyle_0", pStyle.id());
        Assert.assertEquals("Body", pStyle.name());
        Assert.assertEquals("justify", pStyle.alignment());
        Assert.assertEquals(Integer.valueOf(1000), pStyle.fontSizeHwpunits());
        Assert.assertEquals(Long.valueOf(200), pStyle.spaceAfter());

        Assert.assertEquals(1, restored.characterStyles().size());
        IntermediateStyleDef cStyle = restored.characterStyles().get(0);
        Assert.assertEquals("cstyle_0", cStyle.id());
        Assert.assertNull(cStyle.bold());
    }

    @Test
    public void testRoundtripPages() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        Assert.assertEquals(1, restored.pages().size());
        IntermediatePage page = restored.pages().get(0);
        Assert.assertEquals(8, page.pageNumber());
        Assert.assertEquals(11906, page.pageWidth());
        Assert.assertEquals(2, page.frames().size());
    }

    @Test
    public void testRoundtripTextFrame() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        IntermediateFrame textFrame = restored.pages().get(0).frames().get(0);
        Assert.assertEquals("frame_u123", textFrame.frameId());
        Assert.assertEquals("text", textFrame.frameType());
        Assert.assertEquals(4252, textFrame.x());
        Assert.assertEquals(3402, textFrame.width());
        Assert.assertEquals(2, textFrame.paragraphs().size());

        // Text paragraph
        IntermediateParagraph para1 = textFrame.paragraphs().get(0);
        Assert.assertEquals("pstyle_0", para1.paragraphStyleRef());
        Assert.assertNull(para1.equation());
        Assert.assertEquals(1, para1.runs().size());
        Assert.assertEquals("본문 텍스트", para1.runs().get(0).text());

        // Equation paragraph
        IntermediateParagraph para2 = textFrame.paragraphs().get(1);
        Assert.assertNotNull(para2.equation());
        Assert.assertEquals("lim _{n -> inf} a _{n}", para2.equation().hwpScript());
        Assert.assertEquals("NP_FONT", para2.equation().sourceType());
    }

    @Test
    public void testRoundtripImageFrame() throws Exception {
        IntermediateDocument original = createSampleDocument();
        String json = JsonSerializer.toJson(original);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        IntermediateFrame imageFrame = restored.pages().get(0).frames().get(1);
        Assert.assertEquals("frame_u456", imageFrame.frameId());
        Assert.assertEquals("image", imageFrame.frameType());

        IntermediateImage img = imageFrame.image();
        Assert.assertNotNull(img);
        Assert.assertEquals("img_1", img.imageId());
        Assert.assertEquals("Links/figure.psd", img.originalPath());
        Assert.assertEquals("png", img.format());
        Assert.assertEquals(800, img.pixelWidth());
        Assert.assertEquals(3402, img.displayWidth());
        Assert.assertEquals("iVBORw0KGgoAAAANSUhEUg==", img.base64Data());
    }

    @Test
    public void testEmptyDocument() throws Exception {
        IntermediateDocument doc = new IntermediateDocument();
        String json = JsonSerializer.toJson(doc);
        IntermediateDocument restored = JsonDeserializer.fromJson(json);

        Assert.assertNotNull(restored);
        Assert.assertEquals("1.0", restored.version());
        Assert.assertEquals(0, restored.pages().size());
        Assert.assertEquals(0, restored.fonts().size());
    }

    @Test(expected = ConvertException.class)
    public void testDeserializeNull() throws Exception {
        JsonDeserializer.fromJson(null);
    }

    @Test(expected = ConvertException.class)
    public void testDeserializeEmpty() throws Exception {
        JsonDeserializer.fromJson("");
    }

    @Test(expected = ConvertException.class)
    public void testDeserializeInvalidJson() throws Exception {
        JsonDeserializer.fromJson("{invalid json!!");
    }
}
