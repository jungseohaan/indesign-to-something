package kr.dogfoot.hwpxlib.writer.section_xml.object;

import kr.dogfoot.hwpxlib.commonstrings.AttributeNames;
import kr.dogfoot.hwpxlib.commonstrings.ElementNames;
import kr.dogfoot.hwpxlib.object.common.HWPXObject;
import kr.dogfoot.hwpxlib.object.common.baseobject.LeftRightTopBottom;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Label;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.writer.common.ElementWriterManager;
import kr.dogfoot.hwpxlib.writer.common.ElementWriterSort;
import kr.dogfoot.hwpxlib.writer.section_xml.object.shapeobject.ShapeObjectWriter;

public class TableWriter extends ShapeObjectWriter {
    public TableWriter(ElementWriterManager elementWriterManager) {
        super(elementWriterManager);
    }

    @Override
    public ElementWriterSort sort() {
        return ElementWriterSort.Table;
    }

    @Override
    public void write(HWPXObject object) {
        Table table = (Table) object;
        switchList(table.switchList());

        xsb()
                .openElement(ElementNames.hp_tbl)
                .elementWriter(this);
        writeAttributeForShapeObject(table);
        xsb()
                .attribute(AttributeNames.pageBreak, table.pageBreak())
                .attribute(AttributeNames.repeatHeader, table.repeatHeader())
                .attribute(AttributeNames.rowCnt, table.rowCnt())
                .attribute(AttributeNames.colCnt, table.colCnt())
                .attribute(AttributeNames.cellSpacing, table.cellSpacing())
                .attribute(AttributeNames.borderFillIDRef, table.borderFillIDRef())
                .attribute(AttributeNames.noAdjust, table.noAdjust());

        writeChildrenForShapeObject(table);

        if (table.inMargin() != null) {
            leftRightTopBottom(ElementNames.hp_inMargin, table.inMargin());
        }

        if (table.cellzoneList() != null && !table.cellzoneList().empty()) {
            writeChild(ElementWriterSort.CellZoneList, table.cellzoneList());
        }

        for (Tr tr : table.trs()) {
            writeChild(ElementWriterSort.Tr, tr);
        }

        if (table.parameterSet() != null) {
            writeChild(ElementWriterSort.ParameterListCore, table.parameterSet());
        }

        if (table.label() != null) {
            label(table.label());
        }

        xsb().closeElement();
        releaseMe();
    }

    @Override
    protected void childInSwitch(HWPXObject child) {
        switch (child._objectType()) {
            case hp_inMargin:
                leftRightTopBottom(ElementNames.hp_inMargin, (LeftRightTopBottom) child);
                break;
            case hp_cellzoneList:
                writeChild(ElementWriterSort.CellZoneList, child);
                break;
            case hp_tr:
                writeChild(ElementWriterSort.Tr, child);
                break;
            case hp_parameterset:
                writeChild(ElementWriterSort.ParameterListCore, child);
                break;
            default:
                super.childInSwitch(child);
                break;
        }
    }

    private void label(Label label) {
        xsb()
                .openElement(ElementNames.hp_label)
                .attribute(AttributeNames.topmargin, label.topMargin())
                .attribute(AttributeNames.leftmargin, label.leftMargin())
                .attribute(AttributeNames.boxwidth, label.boxWidth())
                .attribute(AttributeNames.boxlength, label.boxLength())
                .attribute(AttributeNames.boxmarginhor, label.boxMarginHor())
                .attribute(AttributeNames.boxmarginver, label.boxMarginHor())
                .attribute(AttributeNames.labelcols, label.labelCols())
                .attribute(AttributeNames.labelrows, label.labelRows())
                .attribute(AttributeNames.landscape, label.landscape())
                .attribute(AttributeNames.pagewidth, label.pageWidth())
                .attribute(AttributeNames.pageheight, label.pageHeight())
                .closeElement();
    }
}
