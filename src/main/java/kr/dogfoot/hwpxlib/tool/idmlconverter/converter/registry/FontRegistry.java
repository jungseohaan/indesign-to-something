package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.FontFamilyType;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.FontType;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Fontface;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.fontface.Font;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;

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
    private final Map<String, String> customFontMap;
    private FontMapper fontMapperInstance;  // 3계층 매핑 인스턴스
    private final Map<String, String[]> fontPairCache = new LinkedHashMap<>();
    private final Map<String, FontMapper.MappingResult> fontPairMappingCache = new LinkedHashMap<>();
    private int nextFontId;

    public FontRegistry(HWPXFile hwpxFile) {
        this(hwpxFile, null);
    }

    public FontRegistry(HWPXFile hwpxFile, Map<String, String> customFontMap) {
        this.hwpxFile = hwpxFile;
        this.customFontMap = customFontMap;

        // BlankFileMaker에서 이미 등록된 기본 폰트
        fontNameToId.put("함초롬돋움", "0");
        fontNameToId.put("함초롬바탕", "1");
        registeredFonts.add("함초롬돋움");
        registeredFonts.add("함초롬바탕");
        nextFontId = 2;
    }

    /**
     * 3계층 FontMapper 인스턴스를 설정한다.
     */
    public void setFontMapper(FontMapper mapper) {
        this.fontMapperInstance = mapper;
    }

    public FontMapper fontMapper() {
        return fontMapperInstance;
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

            // BT수식/EH 수식 폰트는 FontMapper를 거치지 않고 직접 등록
            if (fontFamily.contains("BT수식") || fontFamily.startsWith("EH")) {
                return registerDirectFont(fontFamily);
            }

            // FontMapper 매핑 (커스텀 맵 우선)
            String hwpxName = FontMapper.mapToHwpxFont(fontFamily, customFontMap);
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
     * 폰트 이름에 해당하는 HWPX 폰트 ID 쌍을 반환한다.
     * [0] = hangul 슬롯용 ID, [1] = latin 슬롯용 ID
     * 서양 폰트: hangul에 한글 폴백, latin에 원본 서양 폰트
     * 한글 폰트: 양쪽 동일
     */
    public String[] resolveFontIdPair(String fontFamily) {
        if (fontFamily == null) return new String[]{"1", "1"};

        // 수식 등 특수 폰트 → 양쪽 동일 (FontMapper를 거치면 안 됨)
        if (fontFamily.contains("BT수식") || fontFamily.startsWith("EH")
                || "HYhwpEQ".equals(fontFamily)) {
            String id = ensureRegistered(fontFamily);
            return new String[]{id, id};
        }

        // 매핑 결과 캐시 조회
        String[] cached = fontPairCache.get(fontFamily);
        if (cached != null) {
            lastMappingResult = fontPairMappingCache.get(fontFamily);
            return cached;
        }

        String[] result;

        // 3계층 FontMapper 인스턴스가 있으면 사용
        if (fontMapperInstance != null) {
            FontMapper.MappingResult mr = fontMapperInstance.mapFont(fontFamily);
            lastMappingResult = mr;
            String hangulId = ensureRegistered(mr.koFont);
            String latinId = ensureRegistered(mr.enFont);
            result = new String[]{hangulId, latinId};
            fontPairMappingCache.put(fontFamily, mr);
        } else {
            // 폴백: 정적 매핑
            String[] pair = FontMapper.mapToHwpxFontPair(fontFamily, customFontMap);
            lastMappingResult = null;
            String hangulId = ensureRegistered(pair[0]);
            String latinId = ensureRegistered(pair[1]);
            result = new String[]{hangulId, latinId};
        }

        fontPairCache.put(fontFamily, result);
        return result;
    }

    /** 마지막 resolveFontIdPair 호출의 MappingResult (자간 보정값 포함) */
    private FontMapper.MappingResult lastMappingResult;

    /**
     * 마지막 resolveFontIdPair() 호출 결과의 자간 보정값을 반환한다.
     */
    public int lastSpacingAdjust() {
        return lastMappingResult != null ? lastMappingResult.spacingAdjustPercent : 0;
    }

    /**
     * 마지막 resolveFontIdPair() 호출 결과의 장평(horizontalScale) 보정값을 반환한다.
     */
    public int lastScaleAdjust() {
        return lastMappingResult != null ? lastMappingResult.scaleAdjust : 0;
    }

    /**
     * 폰트 이름이 등록되어 있으면 ID 반환, 없으면 등록 후 반환.
     */
    private String ensureRegistered(String fontName) {
        String id = fontNameToId.get(fontName);
        if (id != null) return id;

        String fontId = String.valueOf(nextFontId);
        addFontToAllLanguages(fontName, fontId);
        fontNameToId.put(fontName, fontId);
        registeredFonts.add(fontName);
        nextFontId++;
        return fontId;
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
