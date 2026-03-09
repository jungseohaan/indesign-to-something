package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageBackground;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flat AST 루트 문서.
 * 3개 레이어(Page, LayoutNode, Component)를 평탄한 리스트로 보유하며,
 * 기존 AST의 리프 타입(ASTFontDef, ASTStyleDef, ASTStory, ASTPageBackground)을 재사용한다.
 */
public class FlatDocument {
    private String sourceFile;
    private String sourceFormat = "IDML";

    // 공유 정의
    private List<ASTFontDef> fonts;
    private List<ASTStyleDef> paragraphStyles;
    private List<ASTStyleDef> characterStyles;
    private Map<String, String> colors;
    private List<ASTStory> stories;

    // Layer 0: 페이지 컨텍스트
    private List<FlatPage> pages;

    // Layer 1: 레이아웃 구조
    private List<FlatLayoutNode> layoutNodes;

    // Layer 2: 원자 컴포넌트
    private List<FlatComponent> components;

    // 페이지 배경
    private List<ASTPageBackground> backgrounds;

    public FlatDocument() {
        this.fonts = new ArrayList<>();
        this.paragraphStyles = new ArrayList<>();
        this.characterStyles = new ArrayList<>();
        this.colors = new LinkedHashMap<>();
        this.stories = new ArrayList<>();
        this.pages = new ArrayList<>();
        this.layoutNodes = new ArrayList<>();
        this.components = new ArrayList<>();
        this.backgrounds = new ArrayList<>();
    }

    public String sourceFile() { return sourceFile; }
    public void sourceFile(String v) { this.sourceFile = v; }

    public String sourceFormat() { return sourceFormat; }
    public void sourceFormat(String v) { this.sourceFormat = v; }

    public List<ASTFontDef> fonts() { return fonts; }
    public void fonts(List<ASTFontDef> v) { this.fonts = v; }

    public List<ASTStyleDef> paragraphStyles() { return paragraphStyles; }
    public void paragraphStyles(List<ASTStyleDef> v) { this.paragraphStyles = v; }

    public List<ASTStyleDef> characterStyles() { return characterStyles; }
    public void characterStyles(List<ASTStyleDef> v) { this.characterStyles = v; }

    public Map<String, String> colors() { return colors; }
    public void colors(Map<String, String> v) { this.colors = v; }

    public List<ASTStory> stories() { return stories; }
    public void stories(List<ASTStory> v) { this.stories = v; }

    public List<FlatPage> pages() { return pages; }
    public void pages(List<FlatPage> v) { this.pages = v; }

    public List<FlatLayoutNode> layoutNodes() { return layoutNodes; }
    public void layoutNodes(List<FlatLayoutNode> v) { this.layoutNodes = v; }

    public List<FlatComponent> components() { return components; }
    public void components(List<FlatComponent> v) { this.components = v; }

    public List<ASTPageBackground> backgrounds() { return backgrounds; }
    public void backgrounds(List<ASTPageBackground> v) { this.backgrounds = v; }
}
