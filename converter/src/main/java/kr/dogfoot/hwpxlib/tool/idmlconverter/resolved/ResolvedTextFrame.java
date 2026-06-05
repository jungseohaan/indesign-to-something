package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * resolved.json의 textFrame 항목.
 * InDesign DOM에서 수집한 프레임별 문단 범위 정보.
 *
 * ID는 InDesign DOM의 10진수 문자열 (예: "5959").
 * IDML의 "u" + 16진수 형식(예: "u1747")과 변환 필요.
 */
public class ResolvedTextFrame {
    private String id;              // InDesign DOM id (10진수 문자열)
    private String storyId;         // InDesign DOM storyId (10진수 문자열)
    private int paragraphStart;     // Story 내 시작 문단 인덱스
    private int paragraphEnd;       // Story 내 끝 문단 인덱스
    private int lineCount;
    private boolean overflows;
    private double[] paragraphYOffsets;  // 프레임 내 각 단락의 Y오프셋 (points, 프레임 상단 기준)

    // Phase 3 보강 필드
    private double[] geometricBounds;   // [top, left, bottom, right] (pts)
    private int columnCount;
    private double columnGutter;
    private double[] insetSpacing;      // [top, left, bottom, right] (pts)
    private String verticalJustification;
    private double rotationAngle;

    // IDML-Free 파이프라인 보강 필드
    private String previousFrameId;     // 스레드 체인 이전 프레임 DOM ID
    private String nextFrameId;         // 스레드 체인 다음 프레임 DOM ID
    private boolean isInline;           // 인라인(앵커) 여부
    private int pageIndex = -1;         // 페이지 인덱스 (0-based)
    private int zOrder;                 // 페이지 내 스태킹 순서
    private double[] pageRelativeBounds; // [top, left, bottom, right] page-relative
    private String fillColor;
    private double fillTint;
    private String strokeColor;
    private double strokeWeight;
    private double opacity = 100;
    private double cornerRadius;
    private java.util.List<String> frameParaTexts;  // 프레임에 보이는 각 단락의 실제 텍스트
    private String frameVisibleText;  // 프레임에 실제 보이는 전체 텍스트 (오버플로우 제외)
    private boolean onHiddenLayer;    // InDesign 숨김 레이어에 있는 TF → 변환 불필요
    private boolean nonprinting;      // InDesign 인쇄 안 함/숨김 성격의 TF → 변환 불필요
    private boolean isMasterInstance; // 마스터 페이지 아이템 인스턴스 (regular page에 배치된 마스터 아이템)

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public String storyId() { return storyId; }
    public void storyId(String v) { this.storyId = v; }

    public int paragraphStart() { return paragraphStart; }
    public void paragraphStart(int v) { this.paragraphStart = v; }

    public int paragraphEnd() { return paragraphEnd; }
    public void paragraphEnd(int v) { this.paragraphEnd = v; }

    public int lineCount() { return lineCount; }
    public void lineCount(int v) { this.lineCount = v; }

    public boolean overflows() { return overflows; }
    public void overflows(boolean v) { this.overflows = v; }

    public double[] paragraphYOffsets() { return paragraphYOffsets; }
    public void paragraphYOffsets(double[] v) { this.paragraphYOffsets = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public double columnGutter() { return columnGutter; }
    public void columnGutter(double v) { this.columnGutter = v; }

    public double[] insetSpacing() { return insetSpacing; }
    public void insetSpacing(double[] v) { this.insetSpacing = v; }

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    public double rotationAngle() { return rotationAngle; }
    public void rotationAngle(double v) { this.rotationAngle = v; }

    // IDML-Free 파이프라인 보강 접근자
    public String previousFrameId() { return previousFrameId; }
    public void previousFrameId(String v) { this.previousFrameId = v; }

    public String nextFrameId() { return nextFrameId; }
    public void nextFrameId(String v) { this.nextFrameId = v; }

    public boolean isInline() { return isInline; }
    public void isInline(boolean v) { this.isInline = v; }

    public int pageIndex() { return pageIndex; }
    public void pageIndex(int v) { this.pageIndex = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public double[] pageRelativeBounds() { return pageRelativeBounds; }
    public void pageRelativeBounds(double[] v) { this.pageRelativeBounds = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public double opacity() { return opacity; }
    public void opacity(double v) { this.opacity = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public java.util.List<String> frameParaTexts() { return frameParaTexts; }
    public void frameParaTexts(java.util.List<String> v) { this.frameParaTexts = v; }
    public String frameVisibleText() { return frameVisibleText; }
    public void frameVisibleText(String v) { this.frameVisibleText = v; }

    public boolean onHiddenLayer() { return onHiddenLayer; }
    public void onHiddenLayer(boolean v) { this.onHiddenLayer = v; }

    public boolean nonprinting() { return nonprinting; }
    public void nonprinting(boolean v) { this.nonprinting = v; }

    public boolean isMasterInstance() { return isMasterInstance; }
    public void isMasterInstance(boolean v) { this.isMasterInstance = v; }

    // Phase 4: 조판 결과 (composed lines)
    private java.util.List<ComposedLine> composedLines;

    public java.util.List<ComposedLine> composedLines() { return composedLines; }
    public void composedLines(java.util.List<ComposedLine> v) { this.composedLines = v; }

    /**
     * InDesign 조판 엔진이 배치한 실제 라인 정보.
     */
    public static class ComposedLine {
        private double[] bounds;  // [top, left, bottom, right] (document units)
        private String text;
        private int paraIndex;
        private java.util.List<ComposedRun> runs;
        private double wrapIndentLeft;   // 왼쪽 밀림 (points, wrap에 의한)
        private double wrapIndentRight;  // 오른쪽 밀림 (points, wrap에 의한)

        public double[] bounds() { return bounds; }
        public void bounds(double[] v) { this.bounds = v; }
        public String text() { return text; }
        public void text(String v) { this.text = v; }
        public int paraIndex() { return paraIndex; }
        public void paraIndex(int v) { this.paraIndex = v; }
        public java.util.List<ComposedRun> runs() { return runs; }
        public void runs(java.util.List<ComposedRun> v) { this.runs = v; }
        public double wrapIndentLeft() { return wrapIndentLeft; }
        public void wrapIndentLeft(double v) { this.wrapIndentLeft = v; }
        public double wrapIndentRight() { return wrapIndentRight; }
        public void wrapIndentRight(double v) { this.wrapIndentRight = v; }
    }

    public static class ComposedRun {
        private String text;
        private String fillColor;
        private Double fontSize;
        private String fontFamily;
        private String fontStyle;

        public String text() { return text; }
        public void text(String v) { this.text = v; }
        public String fillColor() { return fillColor; }
        public void fillColor(String v) { this.fillColor = v; }
        public Double fontSize() { return fontSize; }
        public void fontSize(Double v) { this.fontSize = v; }
        public String fontFamily() { return fontFamily; }
        public void fontFamily(String v) { this.fontFamily = v; }
        public String fontStyle() { return fontStyle; }
        public void fontStyle(String v) { this.fontStyle = v; }
    }
}
