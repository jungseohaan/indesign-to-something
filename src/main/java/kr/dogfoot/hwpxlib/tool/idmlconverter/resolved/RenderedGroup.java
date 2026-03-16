package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * InDesign ExtendScript에서 렌더링된 텍스트 프레임/그룹 메타데이터.
 * resolved.json의 renderedTextFrames 배열에서 파싱된다.
 */
public class RenderedGroup {
    private int id;              // InDesign DOM ID (decimal)
    private String file;         // 상대 경로 (예: "group_renders/group_5941.jpg")
    private double[] bounds;     // [top, left, bottom, right] (points)
    private int pageIndex;       // 0-based 페이지 인덱스
    private double[] visibleExpansion;  // [widthRatio, heightRatio, offsetRatioX, offsetRatioY]
                                        // geometricBounds → visibleBounds 보정 비율
    private String type;         // null(기존 text_frame) | "badge_group" | "badge_group_child"
    private int[] childIds;      // 배지 그룹 자식 DOM ID 목록 (type=badge_group일 때)
    private int[] childTextFrameIds;  // 배지 그룹 자식 TextFrame DOM ID (type=badge_group일 때)
    private int badgeGroupId;    // 배지 그룹 부모 DOM ID (type=badge_group_child일 때)
    private int[] childImageIds; // 그룹 렌더링 시 자식 이미지 프레임 DOM ID 목록

    public int id() { return id; }
    public void id(int v) { this.id = v; }

    public String file() { return file; }
    public void file(String v) { this.file = v; }

    public double[] bounds() { return bounds; }
    public void bounds(double[] v) { this.bounds = v; }

    public int pageIndex() { return pageIndex; }
    public void pageIndex(int v) { this.pageIndex = v; }

    public double[] visibleExpansion() { return visibleExpansion; }
    public void visibleExpansion(double[] v) { this.visibleExpansion = v; }

    public String type() { return type; }
    public void type(String v) { this.type = v; }

    public int[] childIds() { return childIds; }
    public void childIds(int[] v) { this.childIds = v; }

    public int[] childTextFrameIds() { return childTextFrameIds; }
    public void childTextFrameIds(int[] v) { this.childTextFrameIds = v; }

    public int badgeGroupId() { return badgeGroupId; }
    public void badgeGroupId(int v) { this.badgeGroupId = v; }

    public int[] childImageIds() { return childImageIds; }
    public void childImageIds(int[] v) { this.childImageIds = v; }

    public boolean isBadgeGroup() { return "badge_group".equals(type); }
    public boolean isBadgeGroupChild() { return "badge_group_child".equals(type); }
}
