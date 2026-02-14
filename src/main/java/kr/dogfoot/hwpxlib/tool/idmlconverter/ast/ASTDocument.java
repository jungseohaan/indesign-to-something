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

    private List<ASTSection> sections;
    private List<ASTPageBackground> backgrounds;
    private List<ASTFontDef> fonts;
    private List<ASTStyleDef> paragraphStyles;
    private List<ASTStyleDef> characterStyles;
    private Map<String, String> colors;

    public ASTDocument() {
        this.sections = new ArrayList<>();
        this.backgrounds = new ArrayList<>();
        this.fonts = new ArrayList<>();
        this.paragraphStyles = new ArrayList<>();
        this.characterStyles = new ArrayList<>();
        this.colors = new LinkedHashMap<>();
    }

    public String sourceFile() { return sourceFile; }
    public void sourceFile(String v) { this.sourceFile = v; }

    public String sourceFormat() { return sourceFormat; }
    public void sourceFormat(String v) { this.sourceFormat = v; }

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
}
