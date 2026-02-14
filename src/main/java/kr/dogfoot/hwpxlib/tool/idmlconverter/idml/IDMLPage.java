package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

/**
 * IDML 페이지.
 */
public class IDMLPage {
    private String selfId;
    private String name;
    private int pageNumber;
    private double[] geometricBounds;
    private double[] itemTransform;
    private String appliedMasterSpread;
    private double marginTop;
    private double marginBottom;
    private double marginLeft;
    private double marginRight;
    private int columnCount;
    private double columnGutter;

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public String name() { return name; }
    public void name(String v) { this.name = v; }

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public String appliedMasterSpread() { return appliedMasterSpread; }
    public void appliedMasterSpread(String v) { this.appliedMasterSpread = v; }

    public double marginTop() { return marginTop; }
    public void marginTop(double v) { this.marginTop = v; }

    public double marginBottom() { return marginBottom; }
    public void marginBottom(double v) { this.marginBottom = v; }

    public double marginLeft() { return marginLeft; }
    public void marginLeft(double v) { this.marginLeft = v; }

    public double marginRight() { return marginRight; }
    public void marginRight(double v) { this.marginRight = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public double columnGutter() { return columnGutter; }
    public void columnGutter(double v) { this.columnGutter = v; }

    public double widthPoints() {
        return geometricBounds != null ? IDMLGeometry.width(geometricBounds) : 0;
    }

    public double heightPoints() {
        return geometricBounds != null ? IDMLGeometry.height(geometricBounds) : 0;
    }

    public long widthHwpunits() {
        return CoordinateConverter.pointsToHwpunits(widthPoints());
    }

    public long heightHwpunits() {
        return CoordinateConverter.pointsToHwpunits(heightPoints());
    }
}
