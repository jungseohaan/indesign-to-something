package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.StyleMapper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateStyleDef;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class TestStyleMapper {

    // ── mapAlignment ──

    @Test
    public void testMapAlignment_LeftAlign() {
        Assert.assertEquals("left", StyleMapper.mapAlignment("LeftAlign"));
    }

    @Test
    public void testMapAlignment_CenterAlign() {
        Assert.assertEquals("center", StyleMapper.mapAlignment("CenterAlign"));
    }

    @Test
    public void testMapAlignment_RightAlign() {
        Assert.assertEquals("right", StyleMapper.mapAlignment("RightAlign"));
    }

    @Test
    public void testMapAlignment_LeftJustified() {
        Assert.assertEquals("justify", StyleMapper.mapAlignment("LeftJustified"));
    }

    @Test
    public void testMapAlignment_CenterJustified() {
        Assert.assertEquals("justify", StyleMapper.mapAlignment("CenterJustified"));
    }

    @Test
    public void testMapAlignment_FullyJustified() {
        Assert.assertEquals("justify", StyleMapper.mapAlignment("FullyJustified"));
    }

    @Test
    public void testMapAlignment_Null() {
        Assert.assertNull(StyleMapper.mapAlignment(null));
    }

    @Test
    public void testMapAlignment_Unknown() {
        Assert.assertEquals("left", StyleMapper.mapAlignment("SomeOtherValue"));
    }

    // ── mapParagraphStyle ──

    @Test
    public void testMapParagraphStyle_Basic() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("ParagraphStyle/Body");
        idmlStyle.fontFamily("MinionPro");
        idmlStyle.fontSize(10.0);
        idmlStyle.textAlignment("LeftAlign");
        idmlStyle.bold(false);
        idmlStyle.italic(false);

        Map<String, String> colorMap = new HashMap<String, String>();
        IntermediateStyleDef result = StyleMapper.mapParagraphStyle(idmlStyle, "pstyle_0", colorMap);

        Assert.assertEquals("pstyle_0", result.id());
        Assert.assertEquals("Body", result.name());
        Assert.assertEquals("paragraph", result.type());
        Assert.assertEquals("MinionPro", result.fontFamily());
        Assert.assertEquals(Integer.valueOf(1000), result.fontSizeHwpunits()); // 10pt * 100
        Assert.assertEquals("left", result.alignment());
        Assert.assertEquals(Boolean.FALSE, result.bold());
        Assert.assertEquals(Boolean.FALSE, result.italic());
    }

    @Test
    public void testMapParagraphStyle_WithMargins() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("ParagraphStyle/Indented");
        idmlStyle.firstLineIndent(12.0);  // 12pt
        idmlStyle.leftIndent(24.0);       // 24pt
        idmlStyle.rightIndent(6.0);       // 6pt
        idmlStyle.spaceBefore(10.0);      // 10pt
        idmlStyle.spaceAfter(5.0);        // 5pt

        Map<String, String> colorMap = new HashMap<String, String>();
        IntermediateStyleDef result = StyleMapper.mapParagraphStyle(idmlStyle, "pstyle_1", colorMap);

        Assert.assertEquals(Long.valueOf(1200), result.firstLineIndent());  // 12 * 100
        Assert.assertEquals(Long.valueOf(2400), result.leftMargin());       // 24 * 100
        Assert.assertEquals(Long.valueOf(600), result.rightMargin());      // 6 * 100
        Assert.assertEquals(Long.valueOf(1000), result.spaceBefore());      // 10 * 100
        Assert.assertEquals(Long.valueOf(500), result.spaceAfter());       // 5 * 100
    }

    @Test
    public void testMapParagraphStyle_WithLeading() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("ParagraphStyle/FixedLeading");
        idmlStyle.leading(14.4);

        Map<String, String> colorMap = new HashMap<String, String>();
        IntermediateStyleDef result = StyleMapper.mapParagraphStyle(idmlStyle, "pstyle_2", colorMap);

        Assert.assertEquals("fixed", result.lineSpacingType());
        Assert.assertEquals(Integer.valueOf(1440), result.lineSpacingPercent()); // 14.4pt * 100 = 1440 HWPUNIT
    }

    @Test
    public void testMapParagraphStyle_AutoLeading() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("ParagraphStyle/AutoLeading");
        idmlStyle.leadingType("Auto");
        idmlStyle.autoLeading(120.0);

        Map<String, String> colorMap = new HashMap<String, String>();
        IntermediateStyleDef result = StyleMapper.mapParagraphStyle(idmlStyle, "pstyle_3", colorMap);

        Assert.assertEquals("percent", result.lineSpacingType());
        Assert.assertEquals(Integer.valueOf(120), result.lineSpacingPercent());
    }

    @Test
    public void testMapParagraphStyle_AutoLeading_DefaultPercent() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("ParagraphStyle/AutoDefault");
        idmlStyle.leadingType("Auto");

        Map<String, String> colorMap = new HashMap<String, String>();
        IntermediateStyleDef result = StyleMapper.mapParagraphStyle(idmlStyle, "pstyle_4", colorMap);

        Assert.assertEquals("percent", result.lineSpacingType());
        Assert.assertEquals(Integer.valueOf(120), result.lineSpacingPercent());
    }

    @Test
    public void testMapParagraphStyle_WithColor() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("ParagraphStyle/Colored");
        idmlStyle.fillColor("Color/Custom_Red");

        Map<String, String> colorMap = new HashMap<String, String>();
        colorMap.put("Color/Custom_Red", "#FF0000");
        IntermediateStyleDef result = StyleMapper.mapParagraphStyle(idmlStyle, "pstyle_5", colorMap);

        Assert.assertEquals("#FF0000", result.textColor());
    }

    // ── mapCharacterStyle ──

    @Test
    public void testMapCharacterStyle_Basic() {
        IDMLStyleDef idmlStyle = new IDMLStyleDef();
        idmlStyle.selfRef("CharacterStyle/Bold");
        idmlStyle.fontFamily("Arial");
        idmlStyle.fontSize(12.0);
        idmlStyle.bold(true);
        idmlStyle.italic(false);

        Map<String, String> colorMap = new HashMap<String, String>();
        IntermediateStyleDef result = StyleMapper.mapCharacterStyle(idmlStyle, "cstyle_0", colorMap);

        Assert.assertEquals("cstyle_0", result.id());
        Assert.assertEquals("Bold", result.name());
        Assert.assertEquals("character", result.type());
        Assert.assertEquals("Arial", result.fontFamily());
        Assert.assertEquals(Integer.valueOf(1200), result.fontSizeHwpunits());
        Assert.assertEquals(Boolean.TRUE, result.bold());
        Assert.assertEquals(Boolean.FALSE, result.italic());
    }

    // ── buildParagraphStyleMap ──

    @Test
    public void testBuildParagraphStyleMap() {
        Map<String, IDMLStyleDef> idmlStyles = new LinkedHashMap<String, IDMLStyleDef>();

        IDMLStyleDef style1 = new IDMLStyleDef();
        style1.selfRef("ParagraphStyle/Body");
        style1.fontSize(10.0);
        idmlStyles.put("ParagraphStyle/Body", style1);

        IDMLStyleDef style2 = new IDMLStyleDef();
        style2.selfRef("ParagraphStyle/Heading");
        style2.fontSize(16.0);
        idmlStyles.put("ParagraphStyle/Heading", style2);

        Map<String, String> colorMap = new HashMap<String, String>();
        List<IntermediateStyleDef> outStyles = new ArrayList<IntermediateStyleDef>();

        Map<String, String> refToId = StyleMapper.buildParagraphStyleMap(idmlStyles, colorMap, outStyles);

        Assert.assertEquals(2, outStyles.size());
        Assert.assertEquals(2, refToId.size());
        Assert.assertEquals("pstyle_0", refToId.get("ParagraphStyle/Body"));
        Assert.assertEquals("pstyle_1", refToId.get("ParagraphStyle/Heading"));
        Assert.assertEquals("pstyle_0", outStyles.get(0).id());
        Assert.assertEquals("pstyle_1", outStyles.get(1).id());
    }

    // ── resolveColor ──

    @Test
    public void testResolveColor_Null() {
        Assert.assertNull(StyleMapper.resolveColor(null, new HashMap<String, String>()));
    }

    @Test
    public void testResolveColor_Empty() {
        Assert.assertNull(StyleMapper.resolveColor("", new HashMap<String, String>()));
    }

    @Test
    public void testResolveColor_HexPassthrough() {
        Assert.assertEquals("#FF0000", StyleMapper.resolveColor("#FF0000", new HashMap<String, String>()));
    }

    @Test
    public void testResolveColor_ColorMapLookup() {
        Map<String, String> colorMap = new HashMap<String, String>();
        colorMap.put("Color/MyRed", "#CC0000");
        Assert.assertEquals("#CC0000", StyleMapper.resolveColor("Color/MyRed", colorMap));
    }

    @Test
    public void testResolveColor_Black() {
        Assert.assertEquals("#000000", StyleMapper.resolveColor("Color/Black", new HashMap<String, String>()));
    }
}
