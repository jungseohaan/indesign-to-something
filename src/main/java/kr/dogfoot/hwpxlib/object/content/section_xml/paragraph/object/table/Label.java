package kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table;

import kr.dogfoot.hwpxlib.object.common.HWPXObject;
import kr.dogfoot.hwpxlib.object.common.ObjectType;

public class Label extends HWPXObject {
    private Long topMargin;
    private Long leftMargin;
    private Long boxWidth;
    private Long boxLength;
    private Long boxMarginHor;
    private Long boxMarginVer;
    private Integer labelCols;
    private Integer labelRows;
    private String landscape;
    private Long pageWidth;
    private Long pageHeight;

    @Override
    public ObjectType _objectType() {
        return ObjectType.hp_label;
    }

    public Long topMargin() {
        return topMargin;
    }

    public void topMargin(Long topMargin) {
        this.topMargin = topMargin;
    }

    public Label topMarginAnd(Long topMargin) {
        this.topMargin = topMargin;
        return this;
    }

    public Long leftMargin() {
        return leftMargin;
    }

    public void leftMargin(Long leftMargin) {
        this.leftMargin = leftMargin;
    }

    public Label leftMarginAnd(Long leftMargin) {
        this.leftMargin = leftMargin;
        return this;
    }

    public Long boxWidth() {
        return boxWidth;
    }

    public void boxWidth(Long boxWidth) {
        this.boxWidth = boxWidth;
    }

    public Label boxWidthAnd(Long boxWidth) {
        this.boxWidth = boxWidth;
        return this;
    }

    public Long boxLength() {
        return boxLength;
    }

    public void boxLength(Long boxLength) {
        this.boxLength = boxLength;
    }

    public Label boxLengthAnd(Long boxLength) {
        this.boxLength = boxLength;
        return this;
    }

    public Long boxMarginHor() {
        return boxMarginHor;
    }

    public void boxMarginHor(Long boxMarginHor) {
        this.boxMarginHor = boxMarginHor;
    }

    public Label boxMarginHorAnd(Long boxMarginHor) {
        this.boxMarginHor = boxMarginHor;
        return this;
    }

    public Long boxMarginVer() {
        return boxMarginVer;
    }

    public void boxMarginVer(Long boxMarginVer) {
        this.boxMarginVer = boxMarginVer;
    }

    public Label boxMarginVerAnd(Long boxMarginVer) {
        this.boxMarginVer = boxMarginVer;
        return this;
    }

    public Integer labelCols() {
        return labelCols;
    }

    public void labelCols(Integer labelCols) {
        this.labelCols = labelCols;
    }

    public Label labelColsAnd(Integer labelCols) {
        this.labelCols = labelCols;
        return this;
    }

    public Integer labelRows() {
        return labelRows;
    }

    public void labelRows(Integer labelRows) {
        this.labelRows = labelRows;
    }

    public Label labelRowsAnd(Integer labelRows) {
        this.labelRows = labelRows;
        return this;
    }

    public String landscape() {
        return landscape;
    }

    public void landscape(String landscape) {
        this.landscape = landscape;
    }

    public Label landscapeAnd(String landscape) {
        this.landscape = landscape;
        return this;
    }

    public Long pageWidth() {
        return pageWidth;
    }

    public void pageWidth(Long pageWidth) {
        this.pageWidth = pageWidth;
    }

    public Label pageWidthAnd(Long pageWidth) {
        this.pageWidth = pageWidth;
        return this;
    }

    public Long pageHeight() {
        return pageWidth;
    }

    public void pageHeight(Long pageHeight) {
        this.pageWidth = pageWidth;
    }

    public Label pageHeightAnd(Long pageHeight) {
        this.pageHeight = pageHeight;
        return this;
    }

    @Override
    public Label clone() {
        Label cloned = new Label();
        cloned.copyFrom(this);
        return cloned;
    }

    public void copyFrom(Label from) {
        this.topMargin = from.topMargin;
        this.leftMargin = from.leftMargin;
        this.boxWidth = from.boxWidth;
        this.boxLength = from.boxLength;
        this.boxMarginHor = from.boxMarginHor;
        this.boxMarginVer = from.boxMarginVer;
        this.labelCols = from.labelCols;
        this.labelRows = from.labelRows;
        this.landscape = from.landscape;
        this.pageWidth = from.pageWidth;
        this.pageHeight = from.pageHeight;
    }
}
