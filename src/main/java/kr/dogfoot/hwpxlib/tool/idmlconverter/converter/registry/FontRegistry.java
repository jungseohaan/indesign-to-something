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
     * 1. 직접 등록된 폰트명 확인 (HYhwpEQ 등 특수 폰트)
     * 2. FontMapper로 매핑 후 조회
     * 3. 미등록이면 동적 등록
     */
    public String resolveFontId(String fontFamily) {
        if (fontFamily != null) {
            // 직접 등록 폰트 확인 (HYhwpEQ 등 FontMapper를 거치면 안 되는 폰트)
            String directId = fontNameToId.get(fontFamily);
            if (directId != null) return directId;

            // FontMapper 매핑
            String hwpxName = FontMapper.mapToHwpxFont(fontFamily);
            String id = fontNameToId.get(hwpxName);
            if (id != null) return id;

            // 동적 등록: FontMapper 매핑 결과가 기존에 없으면 새로 등록
            if (!registeredFonts.contains(hwpxName)) {
                String fontId = String.valueOf(nextFontId);
                addFontToAllLanguages(hwpxName, fontId);
                fontNameToId.put(hwpxName, fontId);
                registeredFonts.add(hwpxName);
                nextFontId++;
                return fontId;
            }
        }
        // 기본: 함초롬바탕 (id=1)
        return "1";
    }

    /**
     * HWPX 폰트 이름을 직접 등록한다.
     * FontMapper를 거치지 않고 지정된 이름 그대로 등록.
     */
    public String registerDirectFont(String fontName) {
        String existing = fontNameToId.get(fontName);
        if (existing != null) return existing;

        String fontId = String.valueOf(nextFontId);
        addFontToAllLanguages(fontName, fontId);
        fontNameToId.put(fontName, fontId);
        registeredFonts.add(fontName);
        nextFontId++;
        return fontId;
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
