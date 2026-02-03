package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFontDef;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

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

    // ── mapFont ──

    @Test
    public void testMapFont() {
        IDMLFontDef idmlFont = new IDMLFontDef();
        idmlFont.fontFamily("Minion Pro");
        idmlFont.fontStyleName("Regular");
        idmlFont.fontType("OpenTypeCFF");

        IntermediateFontDef result = FontMapper.mapFont(idmlFont, "font_0");
        Assert.assertEquals("font_0", result.id());
        Assert.assertEquals("Minion Pro", result.familyName());
        Assert.assertEquals("Regular", result.styleName());
        Assert.assertEquals("OTF", result.fontType());
        Assert.assertEquals("함초롬바탕", result.hwpxMappedName());
    }

    // ── buildFontList ──

    @Test
    public void testBuildFontList_Dedup() {
        Map<String, IDMLFontDef> idmlFonts = new LinkedHashMap<String, IDMLFontDef>();

        IDMLFontDef font1 = new IDMLFontDef();
        font1.fontFamily("Minion Pro");
        font1.fontStyleName("Regular");
        font1.fontType("OpenTypeCFF");
        idmlFonts.put("font1", font1);

        IDMLFontDef font2 = new IDMLFontDef();
        font2.fontFamily("Minion Pro");
        font2.fontStyleName("Bold");
        font2.fontType("OpenTypeCFF");
        idmlFonts.put("font2", font2);

        IDMLFontDef font3 = new IDMLFontDef();
        font3.fontFamily("Myriad Pro");
        font3.fontStyleName("Regular");
        font3.fontType("OpenTypeCFF");
        idmlFonts.put("font3", font3);

        List<IntermediateFontDef> result = FontMapper.buildFontList(idmlFonts);

        // 중복 제거: Minion Pro + Myriad Pro = 2
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("font_0", result.get(0).id());
        Assert.assertEquals("Minion Pro", result.get(0).familyName());
        Assert.assertEquals("font_1", result.get(1).id());
        Assert.assertEquals("Myriad Pro", result.get(1).familyName());
    }

    @Test
    public void testBuildFontList_Empty() {
        Map<String, IDMLFontDef> idmlFonts = new LinkedHashMap<String, IDMLFontDef>();
        List<IntermediateFontDef> result = FontMapper.buildFontList(idmlFonts);
        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void testBuildFontList_NullFamily() {
        Map<String, IDMLFontDef> idmlFonts = new LinkedHashMap<String, IDMLFontDef>();

        IDMLFontDef font = new IDMLFontDef();
        font.fontFamily(null);
        idmlFonts.put("font1", font);

        List<IntermediateFontDef> result = FontMapper.buildFontList(idmlFonts);
        Assert.assertTrue(result.isEmpty());
    }
}
