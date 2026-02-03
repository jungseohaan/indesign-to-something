package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 문서 루트.
 * IDML → (이 포맷) → HWPX 변환의 중간 단계.
 * Gson으로 직렬화/역직렬화된다.
 */
public class IntermediateDocument {
    private String version;
    private String sourceFormat;
    private String sourceFile;
    private DocumentLayout layout;
    private List<IntermediateFontDef> fonts;
    private List<IntermediateStyleDef> paragraphStyles;
    private List<IntermediateStyleDef> characterStyles;
    private List<IntermediatePage> pages;

    public IntermediateDocument() {
        this.version = "1.0";
        this.fonts = new ArrayList<IntermediateFontDef>();
        this.paragraphStyles = new ArrayList<IntermediateStyleDef>();
        this.characterStyles = new ArrayList<IntermediateStyleDef>();
        this.pages = new ArrayList<IntermediatePage>();
    }

    public String version() { return version; }
    public void version(String v) { this.version = v; }

    public String sourceFormat() { return sourceFormat; }
    public void sourceFormat(String v) { this.sourceFormat = v; }

    public String sourceFile() { return sourceFile; }
    public void sourceFile(String v) { this.sourceFile = v; }

    public DocumentLayout layout() { return layout; }
    public void layout(DocumentLayout v) { this.layout = v; }

    public List<IntermediateFontDef> fonts() { return fonts; }
    public void addFont(IntermediateFontDef font) { fonts.add(font); }

    public List<IntermediateStyleDef> paragraphStyles() { return paragraphStyles; }
    public void addParagraphStyle(IntermediateStyleDef style) { paragraphStyles.add(style); }

    public List<IntermediateStyleDef> characterStyles() { return characterStyles; }
    public void addCharacterStyle(IntermediateStyleDef style) { characterStyles.add(style); }

    public List<IntermediatePage> pages() { return pages; }
    public void addPage(IntermediatePage page) { pages.add(page); }
}
