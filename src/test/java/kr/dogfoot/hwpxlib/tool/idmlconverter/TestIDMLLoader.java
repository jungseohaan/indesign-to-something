package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * IDMLLoader 테스트.
 * /tmp/idml_analysis/ 에 미리 해제된 IDML 구조가 있어야 실행됨.
 */
public class TestIDMLLoader {

    private static final String IDML_DIR = "/tmp/idml_analysis";

    private IDMLDocument doc;

    @Before
    public void setUp() throws Exception {
        File dir = new File(IDML_DIR);
        Assume.assumeTrue("IDML test data not found: " + IDML_DIR, dir.exists());
        doc = IDMLLoader.loadFromDirectory(dir);
    }

    @Test
    public void testDocumentNotNull() {
        Assert.assertNotNull(doc);
        Assert.assertEquals(IDML_DIR, doc.basePath());
    }

    @Test
    public void testSpreadsLoaded() {
        // designmap.xml에 19개의 Spread 참조
        Assert.assertTrue("Spreads should be loaded",
                doc.spreads().size() > 0);
    }

    @Test
    public void testPagesLoaded() {
        int totalPages = doc.totalPageCount();
        Assert.assertTrue("Should have pages loaded, got: " + totalPages,
                totalPages > 0);

        // 모든 페이지에 geometricBounds가 설정되어야 함
        for (IDMLPage page : doc.getAllPages()) {
            Assert.assertNotNull("Page should have selfId", page.selfId());
            Assert.assertNotNull("Page should have geometricBounds",
                    page.geometricBounds());
            Assert.assertTrue("Page width should be positive",
                    page.widthPoints() > 0);
            Assert.assertTrue("Page height should be positive",
                    page.heightPoints() > 0);
        }
    }

    @Test
    public void testPageNumbersAssigned() {
        // Section 정보에 따라 페이지 번호가 할당되어야 함
        for (IDMLPage page : doc.getAllPages()) {
            Assert.assertTrue("Page number should be positive: " + page.pageNumber(),
                    page.pageNumber() > 0);
        }
    }

    @Test
    public void testTextFramesLoaded() {
        int totalTextFrames = 0;
        for (IDMLSpread spread : doc.spreads()) {
            totalTextFrames += spread.textFrames().size();
        }
        Assert.assertTrue("Should have text frames, got: " + totalTextFrames,
                totalTextFrames > 0);
    }

    @Test
    public void testTextFrameAttributes() {
        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLTextFrame frame : spread.textFrames()) {
                Assert.assertNotNull("TextFrame should have selfId",
                        frame.selfId());
                Assert.assertNotNull("TextFrame should have geometricBounds",
                        frame.geometricBounds());
                Assert.assertTrue("TextFrame width should be positive",
                        frame.widthPoints() > 0);
            }
        }
    }

    @Test
    public void testStoriesLoaded() {
        Assert.assertTrue("Should have stories loaded, got: " + doc.stories().size(),
                doc.stories().size() > 0);
    }

    @Test
    public void testStoryContent() {
        // 적어도 일부 Story에는 텍스트 내용이 있어야 함
        boolean foundContent = false;
        for (IDMLStory story : doc.stories().values()) {
            Assert.assertNotNull("Story should have selfId", story.selfId());
            for (IDMLParagraph para : story.paragraphs()) {
                String text = para.getPlainText();
                if (text != null && !text.trim().isEmpty()) {
                    foundContent = true;
                    break;
                }
            }
            if (foundContent) break;
        }
        Assert.assertTrue("Should find at least one story with text content",
                foundContent);
    }

    @Test
    public void testParagraphStyles() {
        Assert.assertTrue("Should have paragraph styles, got: " + doc.paraStyles().size(),
                doc.paraStyles().size() > 0);
    }

    @Test
    public void testCharacterStyles() {
        Assert.assertTrue("Should have character styles, got: " + doc.charStyles().size(),
                doc.charStyles().size() > 0);
    }

    @Test
    public void testStyleAttributes() {
        for (IDMLStyleDef style : doc.paraStyles().values()) {
            Assert.assertNotNull("Style should have selfRef", style.selfRef());
        }
        for (IDMLStyleDef style : doc.charStyles().values()) {
            Assert.assertNotNull("Style should have selfRef", style.selfRef());
        }
    }

    @Test
    public void testFontsLoaded() {
        Assert.assertTrue("Should have fonts loaded, got: " + doc.fonts().size(),
                doc.fonts().size() > 0);
    }

    @Test
    public void testFontAttributes() {
        for (IDMLFontDef font : doc.fonts().values()) {
            Assert.assertNotNull("Font should have selfRef", font.selfRef());
            Assert.assertNotNull("Font should have fontFamily", font.fontFamily());
        }
    }

    @Test
    public void testColorsLoaded() {
        Assert.assertTrue("Should have colors loaded, got: " + doc.colors().size(),
                doc.colors().size() > 0);
    }

    @Test
    public void testColorHexFormat() {
        for (String hex : doc.colors().values()) {
            Assert.assertTrue("Color should start with #: " + hex,
                    hex.startsWith("#"));
            Assert.assertEquals("Color should be #RRGGBB format: " + hex,
                    7, hex.length());
        }
    }

    @Test
    public void testColorConversionBlack() {
        // C=0 M=0 Y=0 K=100 -> #000000
        String hex = IDMLLoader.convertColorToHex("0 0 0 100", "Process", "CMYK");
        Assert.assertEquals("#000000", hex);
    }

    @Test
    public void testColorConversionWhite() {
        // C=0 M=0 Y=0 K=0 -> #FFFFFF
        String hex = IDMLLoader.convertColorToHex("0 0 0 0", "Process", "CMYK");
        Assert.assertEquals("#FFFFFF", hex);
    }

    @Test
    public void testColorConversionCyan() {
        // C=100 M=0 Y=0 K=0 -> #00FFFF
        String hex = IDMLLoader.convertColorToHex("100 0 0 0", "Process", "CMYK");
        Assert.assertEquals("#00FFFF", hex);
    }

    @Test
    public void testColorConversionRGB() {
        String hex = IDMLLoader.convertColorToHex("255 0 128", "Process", "RGB");
        Assert.assertEquals("#FF0080", hex);
    }

    @Test
    public void testTextFramesOnPage() {
        // 각 페이지에 대해 getTextFramesOnPage가 작동하는지 확인
        for (IDMLSpread spread : doc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                // 예외 없이 호출되어야 함
                spread.getTextFramesOnPage(page);
                spread.getImageFramesOnPage(page);
            }
        }
    }

    @Test
    public void testNPFontStoryExists() {
        // NP 폰트가 포함된 Story가 있는지 확인
        boolean foundNPFont = false;
        for (IDMLStory story : doc.stories().values()) {
            for (IDMLParagraph para : story.paragraphs()) {
                if (para.hasEquationContent()) {
                    foundNPFont = true;
                    break;
                }
            }
            if (foundNPFont) break;
        }
        // NP 폰트가 없을 수도 있으므로 경고만 출력
        if (!foundNPFont) {
            System.out.println("[TestIDMLLoader] Warning: No NP font content found in stories");
        }
    }

    @Test(expected = ConvertException.class)
    public void testLoadNonExistentDirectory() throws ConvertException {
        IDMLLoader.loadFromDirectory("/tmp/non_existent_idml_dir_12345");
    }

    @Test(expected = ConvertException.class)
    public void testLoadNonExistentFile() throws ConvertException {
        IDMLLoader.load("/tmp/non_existent_file_12345.idml");
    }
}
