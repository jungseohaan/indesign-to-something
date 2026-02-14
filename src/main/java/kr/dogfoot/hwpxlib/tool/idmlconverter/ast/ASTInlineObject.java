package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 인라인 객체 — 텍스트 흐름 내 앵커된 리프 노드.
 * 이미지, 렌더링된 그룹/벡터, 수식 등.
 * 자식 노드를 가질 수 없음 (Stage3에서 축소 완료).
 */
public class ASTInlineObject extends ASTInlineItem {
    public enum ObjectKind { IMAGE, RENDERED_GROUP, INLINE_TEXT_FRAME }

    private ObjectKind kind;
    private String sourceId;

    // 크기 (HWPUNIT)
    private long width;
    private long height;

    // 이미지 데이터 (IMAGE, RENDERED_GROUP)
    private String imageFormat;
    private byte[] imageData;
    private String imagePath;
    private int pixelWidth;
    private int pixelHeight;

    // 인라인 텍스트 프레임 데이터 (INLINE_TEXT_FRAME)
    private java.util.List<ASTParagraph> paragraphs;
    private java.util.List<ASTTable> inlineTables;

    public ItemType itemType() { return ItemType.INLINE_OBJECT; }

    public ObjectKind kind() { return kind; }
    public void kind(ObjectKind v) { this.kind = v; }

    public String sourceId() { return sourceId; }
    public void sourceId(String v) { this.sourceId = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public String imageFormat() { return imageFormat; }
    public void imageFormat(String v) { this.imageFormat = v; }

    public byte[] imageData() { return imageData; }
    public void imageData(byte[] v) { this.imageData = v; }

    public String imagePath() { return imagePath; }
    public void imagePath(String v) { this.imagePath = v; }

    public int pixelWidth() { return pixelWidth; }
    public void pixelWidth(int v) { this.pixelWidth = v; }

    public int pixelHeight() { return pixelHeight; }
    public void pixelHeight(int v) { this.pixelHeight = v; }

    public java.util.List<ASTParagraph> paragraphs() { return paragraphs; }
    public void paragraphs(java.util.List<ASTParagraph> v) { this.paragraphs = v; }
    public void addParagraph(ASTParagraph p) {
        if (this.paragraphs == null) this.paragraphs = new java.util.ArrayList<>();
        this.paragraphs.add(p);
    }

    public java.util.List<ASTTable> inlineTables() { return inlineTables; }
    public void addInlineTable(ASTTable t) {
        if (this.inlineTables == null) this.inlineTables = new java.util.ArrayList<>();
        this.inlineTables.add(t);
    }
}
