package kr.dogfoot.hwpxlib.object.content.section_xml.paragraph;

import kr.dogfoot.hwpxlib.object.common.ObjectType;

/**
 * 형광펜 시작
 */
public class MarkpenBeginForRun extends RunItem {
    /**
     * 형광펜 색
     */
    private String beginColor;

    public MarkpenBeginForRun() {
    }

    @Override
    public ObjectType _objectType() {
        return ObjectType.hp_markpenBegin;
    }

    public String beginColor() {
        return beginColor;
    }

    public void beginColor(String beginColor) {
        this.beginColor = beginColor;
    }

    public MarkpenBeginForRun beginColorAnd(String beginColor) {
        this.beginColor = beginColor;
        return this;
    }

    @Override
    public MarkpenBeginForRun clone() {
        MarkpenBeginForRun cloned = new MarkpenBeginForRun();
        cloned.copyFrom(this);
        return cloned;
    }

    public void copyFrom(MarkpenBeginForRun from) {
        this.beginColor = from.beginColor;
    }
}
