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
    private int badgeGroupId;    // 배지 그룹 부모 DOM ID (type=badge_group_child일 때)
    private int[] childImageIds; // 그룹 렌더링 시 자식 이미지 프레임 DOM ID 목록
    private int zOrder;          // ExtendScript 할당 z-order (renderedFloatingItems)
    private boolean zOrderKnown; // true: IDML z-order normalizer가 실제 원본 순서를 확인함
    private String itemType;     // "vector" | "group" | "text_decoration" | "image" | "other"
    private boolean textHiddenBeforeExport; // true: PNG 내보내기 전 TF 텍스트를 숨겼음 → PNG는 텍스트 없음
    private String imageFormat;   // "jpg", "jpeg", "png" 등 (소스 파일 직접 복사 시 설정)
    private String visualOwner;   // "indesign_png" | "hwpx_shape" | ...
    private String textOwner;     // "hwpx_tf" | "indesign_png" | "hidden_semantic" | "none"
    private Boolean containsText;
    private Boolean containsEditableText;
    private Boolean placementAllowed;
    private String[] editableTextFrameIds;
    private int[] visualOnlyChildIds;
    private int[] tfInlineVisualIds;
    private int[] sourceObjectIds;
    private String overlapPolicy;
    private String reason;
    private String parentStoryId;

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

    public int badgeGroupId() { return badgeGroupId; }
    public void badgeGroupId(int v) { this.badgeGroupId = v; }

    public int[] childImageIds() { return childImageIds; }
    public void childImageIds(int[] v) { this.childImageIds = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public boolean zOrderKnown() { return zOrderKnown; }
    public void zOrderKnown(boolean v) { this.zOrderKnown = v; }

    public String itemType() { return itemType; }
    public void itemType(String v) { this.itemType = v; }

    private String pdfFile;      // PDF 배경 파일 상대 경로 (page_background 타입)
    private int pdfPageIndex;    // PDF 파일 내 페이지 인덱스 (0-based)

    public String pdfFile() { return pdfFile; }
    public void pdfFile(String v) { this.pdfFile = v; }

    public int pdfPageIndex() { return pdfPageIndex; }
    public void pdfPageIndex(int v) { this.pdfPageIndex = v; }

    public boolean isTextHiddenBeforeExport() { return textHiddenBeforeExport; }
    public void textHiddenBeforeExport(boolean v) { this.textHiddenBeforeExport = v; }

    public String imageFormat() { return imageFormat; }
    public void imageFormat(String v) { this.imageFormat = v; }

    public String visualOwner() { return visualOwner; }
    public void visualOwner(String v) { this.visualOwner = v; }

    public String textOwner() { return textOwner; }
    public void textOwner(String v) { this.textOwner = v; }

    public Boolean containsText() { return containsText; }
    public void containsText(Boolean v) { this.containsText = v; }

    public Boolean containsEditableText() { return containsEditableText; }
    public void containsEditableText(Boolean v) { this.containsEditableText = v; }

    public Boolean placementAllowed() { return placementAllowed; }
    public void placementAllowed(Boolean v) { this.placementAllowed = v; }

    public String[] editableTextFrameIds() { return editableTextFrameIds; }
    public void editableTextFrameIds(String[] v) { this.editableTextFrameIds = v; }

    public int[] visualOnlyChildIds() { return visualOnlyChildIds; }
    public void visualOnlyChildIds(int[] v) { this.visualOnlyChildIds = v; }

    public int[] tfInlineVisualIds() { return tfInlineVisualIds; }
    public void tfInlineVisualIds(int[] v) { this.tfInlineVisualIds = v; }

    public int[] sourceObjectIds() { return sourceObjectIds; }
    public void sourceObjectIds(int[] v) { this.sourceObjectIds = v; }

    public String overlapPolicy() { return overlapPolicy; }
    public void overlapPolicy(String v) { this.overlapPolicy = v; }

    public String reason() { return reason; }
    public void reason(String v) { this.reason = v; }

    public String parentStoryId() { return parentStoryId; }
    public void parentStoryId(String v) { this.parentStoryId = v; }

    private boolean whiteStroke; // true: 획이 흰색(Paper)이었던 PNG → 검은 픽셀을 흰색으로 반전 필요

    public boolean isWhiteStroke() { return whiteStroke; }
    public void whiteStroke(boolean v) { this.whiteStroke = v; }

    public boolean isPageBackground() { return "page_background".equals(type); }

    public boolean shouldSkipByOwnership() {
        if (Boolean.FALSE.equals(placementAllowed)) return true;
        if (Boolean.TRUE.equals(containsEditableText)) {
            return !"indesign_png".equals(textOwner);
        }
        return false;
    }

    public boolean hasEditableTextHiddenFromPng() {
        return "hwpx_tf".equals(textOwner)
                && (Boolean.TRUE.equals(textHiddenBeforeExport)
                    || Boolean.FALSE.equals(containsText));
    }
}
