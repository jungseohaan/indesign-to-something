package kr.dogfoot.hwpxlib.reader.section_xml.object.table;

import kr.dogfoot.hwpxlib.commonstrings.AttributeNames;
import kr.dogfoot.hwpxlib.object.common.SwitchableObject;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Label;
import kr.dogfoot.hwpxlib.reader.common.ElementReader;
import kr.dogfoot.hwpxlib.reader.common.ElementReaderSort;
import kr.dogfoot.hwpxlib.reader.util.ValueConvertor;

public class LabelReader extends ElementReader {
    private Label label;
    @Override
    public ElementReaderSort sort() {
        return ElementReaderSort.Label;
    }

    public void label(Label label) {
        this.label = label;
    }

    @Override
    protected void setAttribute(String name, String value) {
        switch (name) {
            case AttributeNames.topmargin:
                label.topMargin(ValueConvertor.toLong(value));
                break;
            case AttributeNames.leftmargin:
                label.leftMargin(ValueConvertor.toLong(value));
                break;
            case AttributeNames.boxwidth:
                label.boxWidth(ValueConvertor.toLong(value));
                break;
            case AttributeNames.boxlength:
                label.boxLength(ValueConvertor.toLong(value));
                break;
            case AttributeNames.boxmarginhor:
                label.boxMarginHor(ValueConvertor.toLong(value));
                break;
            case AttributeNames.boxmarginver:
                label.boxMarginVer(ValueConvertor.toLong(value));
                break;
            case AttributeNames.labelcols:
                label.labelCols(ValueConvertor.toInteger(value));
                break;
            case AttributeNames.labelrows:
                label.labelRows(ValueConvertor.toInteger(value));
                break;
            case AttributeNames.landscape:
                label.landscape(value);
                break;
            case AttributeNames.pagewidth:
                label.pageWidth(ValueConvertor.toLong(value));
                break;
            case AttributeNames.pageheight:
                label.pageHeight(ValueConvertor.toLong(value));
                break;
        }
    }

    @Override
    public SwitchableObject switchableObject() {
        return null;
    }
}
