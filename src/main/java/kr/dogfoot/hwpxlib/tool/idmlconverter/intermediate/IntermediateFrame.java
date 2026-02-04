package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 프레임 (text 또는 image).
 * HWPUNIT 좌표.
 */
public class IntermediateFrame {
    /**
     * 콘텐츠 카테고리 (HWPX 내보내기 시 z-order 정렬에 사용).
     * 낮은 숫자가 먼저 내보내짐 (아래에 배치).
     */
    public enum ContentCategory {
        BACKGROUND_IMAGE(0),      // 배경 이미지 (스프레드 전체 크기)
        DESIGN_FORMAT_IMAGE(1),   // PSD, AI, PDF, EPS, TIFF 이미지
        VECTOR_GRAPHIC_IMAGE(2),  // 벡터 그래픽을 렌더링한 이미지
        REGULAR_IMAGE(3),         // 일반 이미지 (PNG, JPEG 등)
        TEXT_FRAME(4);            // 텍스트 프레임

        private final int order;
        ContentCategory(int order) { this.order = order; }
        public int order() { return order; }
    }

    private String frameId;
    private String frameType;  // "text" or "image"
    private long x;
    private long y;
    private long width;
    private long height;
    private int zOrder;
    private boolean isBackgroundImage;  // 배경 이미지 여부
    private List<IntermediateParagraph> paragraphs;
    private IntermediateImage image;

    public IntermediateFrame() {
        this.paragraphs = new ArrayList<IntermediateParagraph>();
    }

    public String frameId() { return frameId; }
    public void frameId(String v) { this.frameId = v; }

    public String frameType() { return frameType; }
    public void frameType(String v) { this.frameType = v; }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public boolean isBackgroundImage() { return isBackgroundImage; }
    public void isBackgroundImage(boolean v) { this.isBackgroundImage = v; }

    public List<IntermediateParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IntermediateParagraph para) { paragraphs.add(para); }

    public IntermediateImage image() { return image; }
    public void image(IntermediateImage v) { this.image = v; }

    /**
     * 프레임의 콘텐츠 카테고리를 반환한다.
     * HWPX 내보내기 시 z-order 정렬에 사용된다.
     */
    public ContentCategory contentCategory() {
        // 텍스트 프레임
        if ("text".equals(frameType)) {
            return ContentCategory.TEXT_FRAME;
        }

        // 이미지 프레임
        if ("image".equals(frameType) && image != null) {
            // 배경 이미지
            if (isBackgroundImage) {
                return ContentCategory.BACKGROUND_IMAGE;
            }

            // 벡터 그래픽 렌더링 이미지 (frameId가 "vector_"로 시작)
            if (frameId != null && frameId.startsWith("vector_")) {
                return ContentCategory.VECTOR_GRAPHIC_IMAGE;
            }

            // 디자인 포맷 이미지 (PSD, AI, PDF, EPS, TIFF)
            String format = image.format();
            if (format != null) {
                String lowerFormat = format.toLowerCase();
                if ("psd".equals(lowerFormat) || "ai".equals(lowerFormat) ||
                    "pdf".equals(lowerFormat) || "eps".equals(lowerFormat) ||
                    "tiff".equals(lowerFormat) || "tif".equals(lowerFormat)) {
                    return ContentCategory.DESIGN_FORMAT_IMAGE;
                }
            }

            // originalPath로도 확인 (변환 후 format이 png로 바뀔 수 있음)
            String originalPath = image.originalPath();
            if (originalPath != null) {
                String lowerPath = originalPath.toLowerCase();
                if (lowerPath.endsWith(".psd") || lowerPath.endsWith(".ai") ||
                    lowerPath.endsWith(".pdf") || lowerPath.endsWith(".eps") ||
                    lowerPath.endsWith(".tiff") || lowerPath.endsWith(".tif")) {
                    return ContentCategory.DESIGN_FORMAT_IMAGE;
                }
            }

            // 일반 이미지
            return ContentCategory.REGULAR_IMAGE;
        }

        // 기본값
        return ContentCategory.TEXT_FRAME;
    }

    /**
     * HWPX 내보내기용 정렬 순서를 반환한다.
     * 낮은 값이 먼저 내보내짐 (아래에 배치).
     */
    public int exportOrder() {
        return contentCategory().order() * 10000 + zOrder;
    }
}
