package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.FontFamilyType;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.FontType;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Fontface;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.fontface.Font;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFontDef;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HWPX 폰트 레지스트리.
 * 폰트 등록 및 이름→ID 매핑을 관리한다.
 */
public class FontRegistry {

    // 폰트 이름 → HWPX 폰트 ID
    private final Map<String, String> fontNameToId = new LinkedHashMap<>();

    // 등록된 폰트 이름 집합
    private final Set<String> registeredFonts = new HashSet<>();

    private final HWPXFile hwpxFile;
    private int nextFontId;

    public FontRegistry(HWPXFile hwpxFile) {
        this.hwpxFile = hwpxFile;

        // BlankFileMaker에서 이미 등록된 기본 폰트
        fontNameToId.put("함초롬돋움", "0");
        fontNameToId.put("함초롬바탕", "1");
        registeredFonts.add("함초롬돋움");
        registeredFonts.add("함초롬바탕");
        nextFontId = 2;
    }

    /**
     * IntermediateDocument의 폰트 정의를 HWPX에 등록한다.
     */
    public void registerFonts(IntermediateDocument doc) {
        if (doc.fonts() == null || doc.fonts().isEmpty()) return;

        for (IntermediateFontDef fontDef : doc.fonts()) {
            String hwpxName = fontDef.hwpxMappedName();
            if (hwpxName == null) {
                hwpxName = FontMapper.DEFAULT_HWPX_FONT;
            }

            if (!registeredFonts.contains(hwpxName)) {
                String fontId = String.valueOf(nextFontId);
                addFontToAllLanguages(hwpxName, fontId);
                fontNameToId.put(hwpxName, fontId);
                registeredFonts.add(hwpxName);
                nextFontId++;
            }
        }
    }

    /**
     * 폰트 이름에 해당하는 HWPX 폰트 ID를 반환한다.
     * 매핑되지 않은 폰트는 기본 폰트(함초롬바탕, id=1)를 반환한다.
     */
    public String resolveFontId(String fontFamily) {
        if (fontFamily != null) {
            String hwpxName = FontMapper.mapToHwpxFont(fontFamily);
            String id = fontNameToId.get(hwpxName);
            if (id != null) return id;
        }
        // 기본: 함초롬바탕 (id=1)
        return "1";
    }

    /**
     * 폰트 이름으로 직접 ID를 조회한다.
     */
    public String getFontId(String fontName) {
        return fontNameToId.get(fontName);
    }

    /**
     * 등록된 폰트 수를 반환한다.
     */
    public int fontCount() {
        return fontNameToId.size();
    }

    // ── Private helpers ──

    private void addFontToAllLanguages(String fontName, String fontId) {
        for (Fontface face : hwpxFile.headerXMLFile().refList().fontfaces().fontfaces()) {
            Font font = face.addNewFont();
            font.idAnd(fontId)
                    .faceAnd(fontName)
                    .typeAnd(FontType.TTF)
                    .isEmbeddedAnd(false);
            font.createTypeInfo();
            font.typeInfo()
                    .familyTypeAnd(FontFamilyType.FCAT_GOTHIC)
                    .weightAnd(8)
                    .proportionAnd(4)
                    .contrastAnd(0)
                    .strokeVariationAnd(1)
                    .armStyleAnd(true)
                    .letterformAnd(true)
                    .midlineAnd(1)
                    .xHeight(1);
        }
    }
}
