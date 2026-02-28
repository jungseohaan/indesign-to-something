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
}
