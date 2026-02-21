package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;
import org.junit.Assert;
import org.junit.Test;

public class TestFontMapper {

    // ── mapToHwpxFont ──

    @Test
    public void testMapToHwpxFont_Null() {
        Assert.assertEquals(FontMapper.DEFAULT_HWPX_FONT, FontMapper.mapToHwpxFont(null));
    }

    @Test
    public void testMapToHwpxFont_Korean_Batang() {
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("바탕"));
    }

    @Test
    public void testMapToHwpxFont_Korean_Dotum() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("돋움"));
    }

    @Test
    public void testMapToHwpxFont_Korean_Gulim() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("굴림"));
    }

    @Test
    public void testMapToHwpxFont_Korean_NanumMyeongjo() {
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("나눔명조"));
    }

    @Test
    public void testMapToHwpxFont_Korean_NanumGothic() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("나눔고딕"));
    }

    @Test
    public void testMapToHwpxFont_Korean_MalgunGothic() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("맑은 고딕"));
    }

    @Test
    public void testMapToHwpxFont_Korean_SubstringMatch() {
        // "윤명조" 포함
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("윤명조120"));
    }

    @Test
    public void testMapToHwpxFont_Western_MinionPro() {
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("Minion Pro"));
    }

    @Test
    public void testMapToHwpxFont_Western_MyriadPro() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("Myriad Pro"));
    }

    @Test
    public void testMapToHwpxFont_Western_TimesNewRoman() {
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("Times New Roman"));
    }

    @Test
    public void testMapToHwpxFont_Western_Arial() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("Arial"));
    }

    @Test
    public void testMapToHwpxFont_Western_Helvetica() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("Helvetica"));
    }

    @Test
    public void testMapToHwpxFont_SerifDetection() {
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("DejaVu Serif"));
    }

    @Test
    public void testMapToHwpxFont_SansDetection() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("Open Sans"));
    }

    @Test
    public void testMapToHwpxFont_GothicDetection() {
        Assert.assertEquals("함초롬돋움", FontMapper.mapToHwpxFont("Century Gothic"));
    }

    @Test
    public void testMapToHwpxFont_GaramondDetection() {
        Assert.assertEquals("함초롬바탕", FontMapper.mapToHwpxFont("Garamond"));
    }

    @Test
    public void testMapToHwpxFont_Default() {
        Assert.assertEquals(FontMapper.DEFAULT_HWPX_FONT, FontMapper.mapToHwpxFont("SomeUnknownFont"));
    }

    // ── mapFontType ──

    @Test
    public void testMapFontType_Null() {
        Assert.assertEquals("TTF", FontMapper.mapFontType(null));
    }

    @Test
    public void testMapFontType_OpenType() {
        Assert.assertEquals("OTF", FontMapper.mapFontType("OpenTypeCFF"));
    }

    @Test
    public void testMapFontType_OTF() {
        Assert.assertEquals("OTF", FontMapper.mapFontType("OTF"));
    }

    @Test
    public void testMapFontType_TrueType() {
        Assert.assertEquals("TTF", FontMapper.mapFontType("TrueType"));
    }

    @Test
    public void testMapFontType_TTF() {
        Assert.assertEquals("TTF", FontMapper.mapFontType("TTF"));
    }

    @Test
    public void testMapFontType_Unknown() {
        Assert.assertEquals("TTF", FontMapper.mapFontType("SomeOther"));
    }
}
