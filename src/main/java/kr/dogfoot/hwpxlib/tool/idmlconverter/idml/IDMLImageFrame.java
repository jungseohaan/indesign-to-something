package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

/**
 * IDML 이미지 프레임 (Rectangle + Image + Link).
 */
public class IDMLImageFrame {
    private String selfId;
    private double[] geometricBounds;
    private double[] itemTransform;
    private String linkResourceURI;
    private String linkStoredState;
    private String linkResourceFormat;
    private String appliedObjectStyle;

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public String linkResourceURI() { return linkResourceURI; }
    public void linkResourceURI(String v) { this.linkResourceURI = v; }

    public String linkStoredState() { return linkStoredState; }
    public void linkStoredState(String v) { this.linkStoredState = v; }

    public String linkResourceFormat() { return linkResourceFormat; }
    public void linkResourceFormat(String v) { this.linkResourceFormat = v; }

    public String appliedObjectStyle() { return appliedObjectStyle; }
    public void appliedObjectStyle(String v) { this.appliedObjectStyle = v; }

    public double widthPoints() {
        return geometricBounds != null ? IDMLGeometry.width(geometricBounds) : 0;
    }

    public double heightPoints() {
        return geometricBounds != null ? IDMLGeometry.height(geometricBounds) : 0;
    }

    public boolean isEmbedded() {
        return "Embedded".equals(linkStoredState);
    }
}
