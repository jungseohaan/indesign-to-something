package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 페이지 단위 섹션 — HWPX SecPr에 대응.
 */
public class ASTSection {
    private int pageNumber;
    private ASTPageLayout layout;
    private List<ASTBlock> blocks;

    public ASTSection() {
        this.blocks = new ArrayList<>();
    }

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

    public ASTPageLayout layout() { return layout; }
    public void layout(ASTPageLayout v) { this.layout = v; }

    public List<ASTBlock> blocks() { return blocks; }
    public void addBlock(ASTBlock b) { blocks.add(b); }
}
