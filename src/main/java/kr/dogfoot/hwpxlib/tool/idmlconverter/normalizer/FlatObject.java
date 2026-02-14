package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

/**
 * 평탄화된 객체 레코드 — Stage1에서 생성, Stage2에서 인라인 분류.
 */
public class FlatObject {
    public enum ContentType { TEXT_FRAME, IMAGE_FRAME, VECTOR_SHAPE, GROUP }

    private String selfId;
    private ContentType contentType;
    private String storyId;            // 텍스트 프레임만 (ParentStory)
    private double[] absoluteBbox;     // 변환 적용된 절대 좌표 [minX, minY, maxX, maxY] (points)
    private double[] geometricBounds;  // 원본 바운드
    private double[] itemTransform;    // 원본 변환 행렬
    private Object sourceObject;       // 원본 IDML 객체 참조
    private int pageNumber;
    private int zOrder;

    // Stage2에서 설정
    private boolean isInline;
    private String parentStoryId;      // 인라인인 경우, 앵커된 스토리 ID
    private int anchorIndex;           // 스토리 내 문자 위치

    // 그룹에서 추출된 객체인 경우
    private boolean fromGroup;
    private String parentGroupId;

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public ContentType contentType() { return contentType; }
    public void contentType(ContentType v) { this.contentType = v; }

    public String storyId() { return storyId; }
    public void storyId(String v) { this.storyId = v; }

    public double[] absoluteBbox() { return absoluteBbox; }
    public void absoluteBbox(double[] v) { this.absoluteBbox = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public Object sourceObject() { return sourceObject; }
    public void sourceObject(Object v) { this.sourceObject = v; }

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public boolean isInline() { return isInline; }
    public void isInline(boolean v) { this.isInline = v; }

    public String parentStoryId() { return parentStoryId; }
    public void parentStoryId(String v) { this.parentStoryId = v; }

    public int anchorIndex() { return anchorIndex; }
    public void anchorIndex(int v) { this.anchorIndex = v; }

    public boolean fromGroup() { return fromGroup; }
    public void fromGroup(boolean v) { this.fromGroup = v; }

    public String parentGroupId() { return parentGroupId; }
    public void parentGroupId(String v) { this.parentGroupId = v; }
}
