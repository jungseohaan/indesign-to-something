package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLResourceParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

/**
 * IDML ParagraphStyle/CharacterStyle의 속성을 BasedOn 상속 체인까지 resolve하여 캐시.
 * Styles.xml 파싱 1회, 이후 캐시에서 조회.
 */
public class StylePropertyResolver {

    private final Map<String, IDMLStyleDef> rawParaStyles;   // Self → raw
    private final Map<String, IDMLStyleDef> rawCharStyles;   // Self → raw
    private final Map<String, IDMLStyleDef> resolvedParaCache = new HashMap<>();
    private final Map<String, IDMLStyleDef> resolvedCharCache = new HashMap<>();

    // 스타일명 정규화 역방향 맵: 모든 이름 형태 → canonical Self
    private final Map<String, String> paraNameToSelf = new HashMap<>();
    private final Map<String, String> charNameToSelf = new HashMap<>();

    public StylePropertyResolver(Map<String, IDMLStyleDef> paraStyles,
                                  Map<String, IDMLStyleDef> charStyles) {
        this.rawParaStyles = paraStyles != null ? paraStyles : Collections.<String, IDMLStyleDef>emptyMap();
        this.rawCharStyles = charStyles != null ? charStyles : Collections.<String, IDMLStyleDef>emptyMap();
        buildNameIndex(rawParaStyles, paraNameToSelf);
        buildNameIndex(rawCharStyles, charNameToSelf);
    }

    /**
     * IDML 압축 해제 디렉토리에서 Styles.xml을 파싱하여 생성.
     */
    public static StylePropertyResolver fromIdmlDir(File idmlDir) {
        if (idmlDir == null) return new StylePropertyResolver(null, null);

        Map<String, IDMLStyleDef> paraStyles = new LinkedHashMap<>();
        Map<String, IDMLStyleDef> charStyles = new LinkedHashMap<>();

        try {
            File stylesFile = new File(new File(idmlDir, "Resources"), "Styles.xml");
            if (!stylesFile.exists()) return new StylePropertyResolver(null, null);

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try { dbf.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "0"); } catch (Exception e) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document xmlDoc = db.parse(stylesFile);

            org.w3c.dom.NodeList paraNodes = xmlDoc.getElementsByTagName("ParagraphStyle");
            for (int i = 0; i < paraNodes.getLength(); i++) {
                IDMLStyleDef def = IDMLResourceParser.parseStyleDef((org.w3c.dom.Element) paraNodes.item(i));
                if (def.selfRef() != null) {
                    paraStyles.put(def.selfRef(), def);
                }
            }

            org.w3c.dom.NodeList charNodes = xmlDoc.getElementsByTagName("CharacterStyle");
            for (int i = 0; i < charNodes.getLength(); i++) {
                IDMLStyleDef def = IDMLResourceParser.parseStyleDef((org.w3c.dom.Element) charNodes.item(i));
                if (def.selfRef() != null) {
                    charStyles.put(def.selfRef(), def);
                }
            }

            System.out.println("[StylePropertyResolver] Loaded " + paraStyles.size() + " paragraph styles, " + charStyles.size() + " character styles");
        } catch (Exception e) {
            System.err.println("[StylePropertyResolver] Styles.xml 파싱 실패: " + e.getMessage());
        }

        return new StylePropertyResolver(paraStyles, charStyles);
    }

    // ═══════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════

    /**
     * ParagraphStyle의 모든 속성을 BasedOn 상속까지 resolve하여 반환.
     * styleRef: "ParagraphStyle/그룹%3a스타일명" 형태
     */
    public IDMLStyleDef getResolvedParagraphStyle(String styleRef) {
        return resolveAndGet(styleRef, rawParaStyles, resolvedParaCache, paraNameToSelf);
    }

    /**
     * CharacterStyle의 모든 속성을 BasedOn 상속까지 resolve하여 반환.
     */
    public IDMLStyleDef getResolvedCharacterStyle(String styleRef) {
        return resolveAndGet(styleRef, rawCharStyles, resolvedCharCache, charNameToSelf);
    }

    /**
     * ParagraphStyle 또는 CharacterStyle에서 검색 (para 우선).
     */
    public IDMLStyleDef getResolvedStyle(String styleRef) {
        IDMLStyleDef result = getResolvedParagraphStyle(styleRef);
        if (result != null) return result;
        return getResolvedCharacterStyle(styleRef);
    }

    // ═══════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════

    private IDMLStyleDef resolveAndGet(String styleRef,
                                        Map<String, IDMLStyleDef> rawMap,
                                        Map<String, IDMLStyleDef> cache,
                                        Map<String, String> nameIndex) {
        if (styleRef == null) return null;

        // 1차: 캐시에서 직접 검색
        IDMLStyleDef cached = cache.get(styleRef);
        if (cached != null) return cached;

        // canonical Self 찾기
        String canonicalSelf = findCanonicalSelf(styleRef, nameIndex);
        if (canonicalSelf == null) return null;

        // 캐시 확인
        cached = cache.get(canonicalSelf);
        if (cached != null) {
            cache.put(styleRef, cached); // 빠른 재조회를 위해 원래 키도 등록
            return cached;
        }

        // BasedOn 체인 resolve
        IDMLStyleDef resolved = resolveBasedOnChain(canonicalSelf, rawMap, new HashSet<String>());
        if (resolved != null) {
            cache.put(canonicalSelf, resolved);
            cache.put(styleRef, resolved);
        }
        return resolved;
    }

    /**
     * BasedOn 상속 체인을 재귀적으로 resolve하여 병합된 IDMLStyleDef 반환.
     */
    private IDMLStyleDef resolveBasedOnChain(String selfRef,
                                              Map<String, IDMLStyleDef> rawMap,
                                              Set<String> visited) {
        if (selfRef == null || visited.contains(selfRef)) return null;
        visited.add(selfRef);

        IDMLStyleDef raw = rawMap.get(selfRef);
        if (raw == null) return null;

        // BasedOn이 있으면 부모부터 resolve
        String basedOn = raw.basedOn();
        if (basedOn != null && !basedOn.isEmpty()
                && !basedOn.contains("[No Paragraph Style]")
                && !basedOn.contains("[Root Paragraph Style]")
                && !basedOn.contains("[No character style]")) {
            IDMLStyleDef parent = resolveBasedOnChain(basedOn, rawMap, visited);
            if (parent != null) {
                // 부모 속성에 자식 속성 오버라이드
                return mergeStyles(parent, raw);
            }
        }

        // BasedOn 없음 → raw 그대로 반환 (복사본)
        return copyStyleDef(raw);
    }

    /**
     * parent의 속성을 base로, child의 non-null 속성으로 오버라이드.
     */
    private IDMLStyleDef mergeStyles(IDMLStyleDef parent, IDMLStyleDef child) {
        IDMLStyleDef merged = copyStyleDef(parent);

        // child의 non-null 속성으로 오버라이드
        if (child.fontFamily() != null) merged.fontFamily(child.fontFamily());
        if (child.fontStyle() != null) merged.fontStyle(child.fontStyle());
        if (child.fontSize() != null) merged.fontSize(child.fontSize());
        if (child.leading() != null) merged.leading(child.leading());
        if (child.fillColor() != null) merged.fillColor(child.fillColor());
        if (child.tracking() != null) merged.tracking(child.tracking());
        if (child.horizontalScale() != null) merged.horizontalScale(child.horizontalScale());
        if (child.verticalScale() != null) merged.verticalScale(child.verticalScale());
        if (child.baselineShift() != null) merged.baselineShift(child.baselineShift());
        if (child.position() != null) merged.position(child.position());
        if (child.underline() != null) merged.underline(child.underline());
        if (child.underlineType() != null) merged.underlineType(child.underlineType());
        if (child.underlineWeight() != null) merged.underlineWeight(child.underlineWeight());
        if (child.underlineOffset() != null) merged.underlineOffset(child.underlineOffset());
        if (child.underlineColor() != null) merged.underlineColor(child.underlineColor());
        if (child.shadeColor() != null) merged.shadeColor(child.shadeColor());
        if (child.shadeTint() != null) merged.shadeTint(child.shadeTint());
        if (child.strikeThrough() != null) merged.strikeThrough(child.strikeThrough());
        if (child.ruleAboveLineWeight() != null) merged.ruleAboveLineWeight(child.ruleAboveLineWeight());
        if (child.ruleBelowLineWeight() != null) merged.ruleBelowLineWeight(child.ruleBelowLineWeight());
        if (child.ruleAboveColor() != null) merged.ruleAboveColor(child.ruleAboveColor());
        if (child.ruleBelowColor() != null) merged.ruleBelowColor(child.ruleBelowColor());
        // SPEC-064: ruleBelowOn 이 merge 에서 빠지면 resolve 된 스타일이 RuleBelow=true 를
        // 잃어 답란 밑줄 빈칸 변환이 침묵 실패한다 (영어 u1 p22 실측)
        if (child.ruleBelowOn() != null) merged.ruleBelowOn(child.ruleBelowOn());
        if (child.textAlignment() != null) merged.textAlignment(child.textAlignment());
        if (child.leftIndent() != null) merged.leftIndent(child.leftIndent());
        if (child.firstLineIndent() != null) merged.firstLineIndent(child.firstLineIndent());
        if (child.spaceBefore() != null) merged.spaceBefore(child.spaceBefore());
        if (child.spaceAfter() != null) merged.spaceAfter(child.spaceAfter());
        if (child.rightIndent() != null) merged.rightIndent(child.rightIndent());
        if (child.bulletsCharacterStyle() != null) merged.bulletsCharacterStyle(child.bulletsCharacterStyle());
        if (child.bulletsFont() != null) merged.bulletsFont(child.bulletsFont());
        if (child.bulletsFontStyle() != null) merged.bulletsFontStyle(child.bulletsFontStyle());
        if (child.grepStyles() != null) merged.grepStyles(new ArrayList<>(child.grepStyles()));
        if (child.nestedStyles() != null) merged.nestedStyles(new ArrayList<>(child.nestedStyles()));
        if (child.tabStops() != null) merged.tabStops(new ArrayList<>(child.tabStops()));

        // selfRef/name은 child 것으로
        merged.selfRef(child.selfRef());
        merged.name(child.name());
        merged.basedOn(child.basedOn());

        return merged;
    }

    private IDMLStyleDef copyStyleDef(IDMLStyleDef src) {
        IDMLStyleDef copy = new IDMLStyleDef();
        copy.selfRef(src.selfRef());
        copy.name(src.name());
        copy.basedOn(src.basedOn());
        copy.fontFamily(src.fontFamily());
        copy.fontStyle(src.fontStyle());
        copy.fontSize(src.fontSize());
        copy.leading(src.leading());
        copy.fillColor(src.fillColor());
        copy.tracking(src.tracking());
        copy.horizontalScale(src.horizontalScale());
        copy.verticalScale(src.verticalScale());
        copy.baselineShift(src.baselineShift());
        copy.position(src.position());
        copy.underline(src.underline());
        copy.underlineType(src.underlineType());
        copy.underlineWeight(src.underlineWeight());
        copy.underlineOffset(src.underlineOffset());
        copy.underlineColor(src.underlineColor());
        copy.shadeColor(src.shadeColor());
        copy.shadeTint(src.shadeTint());
        copy.strikeThrough(src.strikeThrough());
        copy.ruleAboveLineWeight(src.ruleAboveLineWeight());
        copy.ruleBelowLineWeight(src.ruleBelowLineWeight());
        copy.ruleAboveColor(src.ruleAboveColor());
        copy.ruleBelowColor(src.ruleBelowColor());
        copy.ruleBelowOn(src.ruleBelowOn());
        copy.textAlignment(src.textAlignment());
        copy.leftIndent(src.leftIndent());
        copy.firstLineIndent(src.firstLineIndent());
        copy.spaceBefore(src.spaceBefore());
        copy.spaceAfter(src.spaceAfter());
        copy.rightIndent(src.rightIndent());
        copy.bulletsCharacterStyle(src.bulletsCharacterStyle());
        copy.bulletsFont(src.bulletsFont());
        copy.bulletsFontStyle(src.bulletsFontStyle());
        if (src.grepStyles() != null) copy.grepStyles(new ArrayList<>(src.grepStyles()));
        if (src.nestedStyles() != null) copy.nestedStyles(new ArrayList<>(src.nestedStyles()));
        if (src.tabStops() != null) copy.tabStops(new ArrayList<>(src.tabStops()));
        return copy;
    }

    /**
     * styleRef의 다양한 이름 형태로 canonical Self를 찾음.
     */
    private String findCanonicalSelf(String styleRef, Map<String, String> nameIndex) {
        // 1차: 직접 매칭
        if (nameIndex.containsKey(styleRef)) return nameIndex.get(styleRef);

        // 2차: 경로에서 이름 추출
        String name = styleRef.contains("/") ? styleRef.substring(styleRef.lastIndexOf('/') + 1) : styleRef;
        if (nameIndex.containsKey(name)) return nameIndex.get(name);

        // 3차: URL decode
        String decoded = name.replace("%3a", ":").replace("%25", "%");
        if (nameIndex.containsKey(decoded)) return nameIndex.get(decoded);

        // 4차: 그룹 경로 제거
        if (decoded.contains(":")) {
            String shortName = decoded.substring(decoded.lastIndexOf(':') + 1);
            if (nameIndex.containsKey(shortName)) return nameIndex.get(shortName);
        }

        return null;
    }

    /**
     * 각 스타일의 Self, Name, 그룹 경로 제거된 이름을 역방향 맵에 등록.
     */
    private void buildNameIndex(Map<String, IDMLStyleDef> rawMap, Map<String, String> nameIndex) {
        for (Map.Entry<String, IDMLStyleDef> entry : rawMap.entrySet()) {
            String self = entry.getKey();
            IDMLStyleDef def = entry.getValue();

            nameIndex.put(self, self);

            String name = def.name();
            if (name != null && !name.isEmpty()) {
                nameIndex.put(name, self);
            }

            // Self에서 경로 부분 추출
            if (self.contains("/")) {
                String afterSlash = self.substring(self.lastIndexOf('/') + 1);
                nameIndex.put(afterSlash, self);
                String decoded = afterSlash.replace("%3a", ":").replace("%25", "%");
                nameIndex.put(decoded, self);
                if (decoded.contains(":")) {
                    nameIndex.put(decoded.substring(decoded.lastIndexOf(':') + 1), self);
                }
            }
        }
    }
}
