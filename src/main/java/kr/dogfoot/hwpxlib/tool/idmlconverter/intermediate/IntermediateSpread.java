package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 스프레드.
 * IDML 스프레드를 HWPX 페이지로 1:1 매핑하기 위한 구조.
 * 스프레드의 전체 크기가 HWPX 용지 크기가 되고,
 * 포함된 모든 프레임은 스프레드 좌표계로 배치된다.
 */
public class IntermediateSpread {
    private String spreadId;
    private long spreadWidth;   // 스프레드 전체 너비 (HWPUNIT)
    private long spreadHeight;  // 스프레드 전체 높이 (HWPUNIT)
    private List<IntermediatePageInfo> pageInfos;  // 포함된 페이지 정보
    private List<IntermediateFrame> frames;        // 모든 프레임 (스프레드 좌표)

    // 스프레드 배경 이미지 (벡터 그래픽이 포함된 스프레드를 PNG로 렌더링한 결과)
    private IntermediateImage backgroundImage;

    public IntermediateSpread() {
        this.pageInfos = new ArrayList<IntermediatePageInfo>();
        this.frames = new ArrayList<IntermediateFrame>();
    }

    public String spreadId() { return spreadId; }
    public void spreadId(String v) { this.spreadId = v; }

    public long spreadWidth() { return spreadWidth; }
    public void spreadWidth(long v) { this.spreadWidth = v; }

    public long spreadHeight() { return spreadHeight; }
    public void spreadHeight(long v) { this.spreadHeight = v; }

    public List<IntermediatePageInfo> pageInfos() { return pageInfos; }
    public void addPageInfo(IntermediatePageInfo info) { pageInfos.add(info); }

    public List<IntermediateFrame> frames() { return frames; }
    public void addFrame(IntermediateFrame frame) { frames.add(frame); }

    /**
     * 스프레드 배경 이미지 (벡터+이미지를 렌더링한 결과).
     * 이 이미지가 있으면 z-order 0으로 스프레드 전체 크기에 배치된다.
     */
    public IntermediateImage backgroundImage() { return backgroundImage; }
    public void backgroundImage(IntermediateImage v) { this.backgroundImage = v; }

    public boolean hasBackgroundImage() {
        return backgroundImage != null && backgroundImage.base64Data() != null;
    }

    /**
     * 스프레드에 포함된 첫 번째 페이지 번호.
     * HWPX 출력 시 섹션 번호로 사용된다.
     */
    public int firstPageNumber() {
        if (pageInfos.isEmpty()) return 1;
        return pageInfos.get(0).pageNumber();
    }
}
