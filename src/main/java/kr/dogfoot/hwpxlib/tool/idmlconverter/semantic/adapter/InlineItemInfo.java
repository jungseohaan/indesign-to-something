package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

/**
 * 인라인 아이템 정보 (TS {@code InlineItemInfo} 와 1:1).
 *
 * <p>itemType에 따라 사용하는 필드가 다르다. 사용하지 않는 필드는 null.</p>
 */
public class InlineItemInfo {
    public enum ItemType { TEXT_RUN, INLINE_OBJECT, BREAK, EQUATION }
    public enum InlineObjectKind { IMAGE, RENDERED_GROUP, INLINE_TEXT_FRAME, SPACER_RECT }

    public ItemType itemType = ItemType.TEXT_RUN;

    // TEXT_RUN
    public String text;
    public String fontFamily;
    public String fontStyle;
    /** HWPUNIT (Integer 박싱 — null 의미 있음). */
    public Integer fontSize;
    public String textColor;
    public Boolean bold;
    public Boolean underline;
    public Boolean strikeThrough;
    public Boolean subscript;
    public Boolean superscript;
    public String characterStyleRef;

    // INLINE_OBJECT
    public InlineObjectKind objectKind;
    public String objectSourceId;
    public Long objectWidth;
    public Long objectHeight;

    // EQUATION
    public String equationScript;
    public String equationSourceType;
    public String equationColor;

    // BREAK
    public String breakType;
}
