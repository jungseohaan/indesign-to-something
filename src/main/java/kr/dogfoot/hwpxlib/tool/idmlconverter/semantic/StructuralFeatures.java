package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * SemanticNode 의 구조적 특징(분류 입력 신호).
 *
 * <p>TypeScript {@code StructuralFeatures} 와 1:1 매핑. 필드명/순서/기본값
 * 모두 동일하게 유지한다 — 파리티 테스트(JSON 비교)가 필드명에 의존하므로
 * 추가/제거 시 양 언어를 동시에 갱신할 것.</p>
 *
 * <p>좌표·크기는 모두 HWPUNIT(double)이다. TS 는 number 단일 타입이므로
 * Java도 모두 double로 통일.</p>
 */
public class StructuralFeatures {
    // ─── A. 위치 & 레이아웃 ─────────────────────────
    public int pageNumber;
    public double x;
    public double y;
    public double width;
    public double height;
    public int zOrder;
    public SemanticTypes.RegionTag regionTag = SemanticTypes.RegionTag.MIDDLE;
    public int columnIndex;
    public double relativeYInPage;

    // ─── B. 스토리 & 텍스트 흐름 ─────────────────────
    public String storyId;
    public int storyFrameCount;
    public int storyPageSpan;
    public int frameIndexInStory;
    public boolean isStoryStart;
    public boolean isStoryEnd;

    // ─── C. 텍스트 속성 ─────────────────────────────
    public String textContent = "";
    public int textLength;
    public int paragraphCount;
    public int dominantFontSize;
    public int maxFontSize;
    public String dominantFontFamily = "";
    public boolean hasBoldText;
    public String dominantAlignment;
    public boolean hasNumberPrefix;
    public String numberPrefixPattern;
    public String firstLineText = "";

    // ─── D. 스타일 ─────────────────────────────────
    public List<String> paragraphStyleNames = new ArrayList<>();
    public List<String> characterStyleNames = new ArrayList<>();
    public String dominantParagraphStyle;

    // ─── E. 프레임 속성 ─────────────────────────────
    public boolean hasFill;
    public String fillColor;
    public boolean hasStroke;
    public boolean isBackgroundOnly;
    public int columnCount;
    public double rotationAngle;

    // ─── F. 콘텐츠 구성 ─────────────────────────────
    public boolean hasTable;
    public boolean hasImage;
    public boolean hasEquation;
    public boolean hasInlineFrame;
    public int inlineObjectCount;
    public String blockType = "TEXT_FRAME";

    // ─── G. 공간 근접도 ─────────────────────────────
    public SpatialProximityFeatures spatial = new SpatialProximityFeatures();

    /**
     * SpatialProximityFeatures — 공간 근접도 신호.
     */
    public static class SpatialProximityFeatures {
        public String nearestContentNodeId;
        /** -1 이면 없음(TS의 -1과 동일). */
        public double nearestContentDistance = -1;
        public List<String> overlappingNodeIds = new ArrayList<>();
        public String isVisuallyContainedBy;
        public double visualContainmentRatio;
    }
}
