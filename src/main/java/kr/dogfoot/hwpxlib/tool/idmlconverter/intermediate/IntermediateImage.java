package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

/**
 * 중간 포맷 이미지.
 */
public class IntermediateImage {
    private String imageId;
    private String originalPath;
    private String format;          // "png", "jpeg", etc.
    private int pixelWidth;
    private int pixelHeight;
    private long displayWidth;      // HWPUNIT
    private long displayHeight;     // HWPUNIT
    private String base64Data;

    public String imageId() { return imageId; }
    public void imageId(String v) { this.imageId = v; }

    public String originalPath() { return originalPath; }
    public void originalPath(String v) { this.originalPath = v; }

    public String format() { return format; }
    public void format(String v) { this.format = v; }

    public int pixelWidth() { return pixelWidth; }
    public void pixelWidth(int v) { this.pixelWidth = v; }

    public int pixelHeight() { return pixelHeight; }
    public void pixelHeight(int v) { this.pixelHeight = v; }

    public long displayWidth() { return displayWidth; }
    public void displayWidth(long v) { this.displayWidth = v; }

    public long displayHeight() { return displayHeight; }
    public void displayHeight(long v) { this.displayHeight = v; }

    public String base64Data() { return base64Data; }
    public void base64Data(String v) { this.base64Data = v; }
}
