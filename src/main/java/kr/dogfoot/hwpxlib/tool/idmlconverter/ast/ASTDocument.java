package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST 루트 노드 — 정규화된 IDML 문서.
 */
public class ASTDocument {
    private String sourceFile;
    private String sourceFormat = "IDML";

    private List<ASTStory> stories;
    private List<ASTSection> sections;
    private List<ASTPageBackground> backgrounds;
    private List<ASTFontDef> fonts;
    private List<ASTStyleDef> paragraphStyles;
    private List<ASTStyleDef> characterStyles;
    private Map<String, String> colors;

    // 클리핑 도형의 자식 ID (orphan injection 제외용)
    private java.util.Set<String> clippedChildIds;

    public ASTDocument() {
        this.stories = new ArrayList<>();
        this.sections = new ArrayList<>();
        this.backgrounds = new ArrayList<>();
        this.fonts = new ArrayList<>();
        this.paragraphStyles = new ArrayList<>();
        this.characterStyles = new ArrayList<>();
        this.colors = new LinkedHashMap<>();
        this.clippedChildIds = new java.util.HashSet<>();
    }

    public java.util.Set<String> clippedChildIds() { return clippedChildIds; }
    public void addClippedChildId(String id) { clippedChildIds.add(id); }

    public String sourceFile() { return sourceFile; }
    public void sourceFile(String v) { this.sourceFile = v; }

    public String sourceFormat() { return sourceFormat; }
    public void sourceFormat(String v) { this.sourceFormat = v; }

    public List<ASTStory> stories() { return stories; }
    public void addStory(ASTStory s) { stories.add(s); }

    public List<ASTSection> sections() { return sections; }
    public void addSection(ASTSection s) { sections.add(s); }

    public List<ASTPageBackground> backgrounds() { return backgrounds; }
    public void addBackground(ASTPageBackground bg) { backgrounds.add(bg); }

    public List<ASTFontDef> fonts() { return fonts; }
    public void addFont(ASTFontDef f) { fonts.add(f); }

    public List<ASTStyleDef> paragraphStyles() { return paragraphStyles; }
    public void addParagraphStyle(ASTStyleDef s) { paragraphStyles.add(s); }

    public List<ASTStyleDef> characterStyles() { return characterStyles; }
    public void addCharacterStyle(ASTStyleDef s) { characterStyles.add(s); }

    public Map<String, String> colors() { return colors; }
    public void putColor(String ref, String hex) { colors.put(ref, hex); }

    // 정규화 단계 경고 전달용
    private final List<String> warnings = new ArrayList<>();
    public List<String> warnings() { return warnings; }
    public void addWarning(String w) { warnings.add(w); }

    // IDML selfId → z-order 맵 (orphan graphic z-order 복원용)
    private Map<String, Integer> idmlZOrders;
    public Map<String, Integer> idmlZOrders() { return idmlZOrders; }
    public void idmlZOrders(Map<String, Integer> m) { this.idmlZOrders = m; }
}
