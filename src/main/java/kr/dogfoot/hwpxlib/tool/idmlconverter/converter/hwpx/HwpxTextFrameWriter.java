package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * IntermediateFrame(text)을 HWPX Rectangle+DrawText로 변환한다.
 */
public class HwpxTextFrameWriter {

    private final AtomicLong shapeIdCounter;
    private final AtomicLong paraIdCounter;
    private final HWPXFile hwpxFile;
    private final FontRegistry fontRegistry;
    private final StyleRegistry styleRegistry;
    private final Consumer<String> warningHandler;
    private final AtomicInteger borderFillIdCounter;

    public HwpxTextFrameWriter(AtomicLong shapeIdCounter,
                               AtomicLong paraIdCounter,
                               HWPXFile hwpxFile,
                               FontRegistry fontRegistry,
                               StyleRegistry styleRegistry,
                               Consumer<String> warningHandler,
                               AtomicInteger borderFillIdCounter) {
        this.shapeIdCounter = shapeIdCounter;
        this.paraIdCounter = paraIdCounter;
        this.hwpxFile = hwpxFile;
        this.fontRegistry = fontRegistry;
        this.styleRegistry = styleRegistry;
        this.warningHandler = warningHandler;
        this.borderFillIdCounter = borderFillIdCounter;
    }

    /**
     * 텍스트 프레임을 HWPX Rectangle+DrawText로 변환한다.
     * @return 수식 개수
     */
    public int write(Run anchorRun, IntermediateFrame frame) {
        if (frame.paragraphs() == null) return 0;

        // 다중 컬럼인 경우
        if (frame.columnCount() > 1) {
            return writeMultiColumnTextFrame(anchorRun, frame);
        }

        return writeSingleColumnTextFrame(anchorRun, frame);
    }

    /**
     * 단일 컬럼 텍스트 프레임을 Rectangle+DrawText로 변환한다.
     */
    private int writeSingleColumnTextFrame(Run anchorRun, IntermediateFrame frame) {
        // PAPER 기준 좌표
        long x = frame.x();
        long y = frame.y();
        long w = frame.width();
        long h = frame.height();

        Rectangle rect = anchorRun.addNewRectangle();

        // ShapeObject 기본 속성
        rect.idAnd(nextShapeId())
                .zOrderAnd(frame.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(nextShapeId());

        rect.createOffset();
        rect.offset().set(0L, 0L);

        rect.createOrgSz();
        rect.orgSz().set(w, h);

        rect.createCurSz();
        rect.curSz().set(w, h);

        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);

        // 회전 정보 적용
        double hwpxAngle = frame.rotationAngle();
        short rotationAngleDegrees = (short) Math.round(hwpxAngle);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd(rotationAngleDegrees)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);

        // 회전 행렬 계산
        double radians = Math.toRadians(hwpxAngle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);

        // ShapeSize (크기 고정)
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(true);

        // ShapePosition — 용지(PAPER) 기준 절대 좌표
        rect.createPos();
        rect.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(y)
                .horzOffset(x);

        // OutMargin
        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // Rectangle 고유 속성 — 4 코너, 직각
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // LineShape (테두리/획)
        setupLineShape(rect, frame);

        // FillBrush (채우기)
        setupFillBrush(rect, frame);

        // DrawText — 내부 텍스트
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false).lineShapeFixedAnd(true);
        dt.createTextMargin();
        dt.textMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        dt.createSubList();
        SubList subList = dt.subList();
        TextDirection textDir = frame.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        // SubList에 단락/런 추가
        return addParagraphsToParaList(subList, frame);
    }

    /**
     * 다중 컬럼 텍스트 프레임을 연결된 글상자(Linked Text Boxes)로 변환한다.
     */
    private int writeMultiColumnTextFrame(Run anchorRun, IntermediateFrame frame) {
        int equationCount = 0;
        int colCount = frame.columnCount();

        // PAPER 기준 좌표
        long x = frame.x();
        long y = frame.y();
        long totalWidth = frame.width();
        long h = frame.height();
        long gutter = frame.columnGutter();

        // 컬럼 너비 = (전체너비 - 간격합계) / 컬럼수
        long totalGutter = gutter * (colCount - 1);
        long colWidth = (totalWidth - totalGutter) / colCount;

        // 회전 정보
        double hwpxAngle = frame.rotationAngle();
        short rotationAngleDegrees = (short) Math.round(hwpxAngle);
        double radians = Math.toRadians(hwpxAngle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        // 단락 분배: 균등 분배 전략
        List<IntermediateParagraph> allParas = frame.paragraphs();
        int totalParas = allParas.size();
        int parasPerColumn = Math.max(1, (totalParas + colCount - 1) / colCount);

        // 컬럼별 ID 먼저 생성 (next/prev 링크 설정에 필요)
        List<String> boxIds = new ArrayList<>();
        for (int col = 0; col < colCount; col++) {
            boxIds.add(nextShapeId());
        }

        // N개 Rectangle 생성
        for (int col = 0; col < colCount; col++) {
            String boxId = boxIds.get(col);
            long colX = x + (colWidth + gutter) * col;

            Rectangle rect = anchorRun.addNewRectangle();

            // ShapeObject 기본 속성
            rect.idAnd(boxId)
                    .zOrderAnd(frame.zOrder())
                    .numberingTypeAnd(NumberingType.PICTURE)
                    .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                    .textFlowAnd(TextFlowSide.BOTH_SIDES)
                    .lockAnd(false)
                    .dropcapstyleAnd(DropCapStyle.None);

            // ShapeComponent
            rect.hrefAnd("");
            rect.groupLevelAnd((short) 0);
            rect.instidAnd(nextShapeId());

            rect.createOffset();
            rect.offset().set(0L, 0L);

            rect.createOrgSz();
            rect.orgSz().set(colWidth, h);

            rect.createCurSz();
            rect.curSz().set(colWidth, h);

            rect.createFlip();
            rect.flip().horizontalAnd(false).verticalAnd(false);

            // 회전 정보
            rect.createRotationInfo();
            rect.rotationInfo().angleAnd(rotationAngleDegrees)
                    .centerXAnd(colWidth / 2).centerYAnd(h / 2).rotateimageAnd(true);

            rect.createRenderingInfo();
            rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            rect.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);

            // ShapeSize
            rect.createSZ();
            rect.sz().widthAnd(colWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                    .protectAnd(true);

            // ShapePosition — 용지(PAPER) 기준 절대 좌표
            rect.createPos();
            rect.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(false)
                    .allowOverlapAnd(true)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PAPER)
                    .horzRelToAnd(HorzRelTo.PAPER)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(y)
                    .horzOffset(colX);

            // OutMargin
            rect.createOutMargin();
            rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

            // Rectangle 고유 속성 — 4 코너, 직각
            rect.ratioAnd((short) 0);
            rect.createPt0();
            rect.pt0().set(0L, 0L);
            rect.createPt1();
            rect.pt1().set(colWidth, 0L);
            rect.createPt2();
            rect.pt2().set(colWidth, h);
            rect.createPt3();
            rect.pt3().set(0L, h);

            // LineShape (테두리/획)
            setupLineShape(rect, frame);

            // FillBrush (채우기) - 첫 번째 컬럼만 배경색 적용
            setupFillBrush(rect, frame);

            // DrawText — 내부 텍스트
            rect.createDrawText();
            DrawText dt = rect.drawText();
            dt.lastWidthAnd(colWidth).nameAnd("col_" + col).editableAnd(false).lineShapeFixedAnd(true);

            // 연결된 글상자 링크 설정
            if (col > 0) {
                dt.prevAnd(boxIds.get(col - 1));
            }
            if (col < colCount - 1) {
                dt.nextAnd(boxIds.get(col + 1));
            }

            dt.createTextMargin();
            // 프레임 여백 (첫/마지막 컬럼에만 좌우 여백 적용)
            long leftMargin = (col == 0) ? frame.insetLeft() : 0L;
            long rightMargin = (col == colCount - 1) ? frame.insetRight() : 0L;
            dt.textMargin().leftAnd(leftMargin).rightAnd(rightMargin)
                    .topAnd(frame.insetTop()).bottomAnd(frame.insetBottom());

            dt.createSubList();
            SubList subList = dt.subList();
            TextDirection textDir = frame.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
            subList.idAnd("").textDirectionAnd(textDir)
                    .lineWrapAnd(LineWrapMethod.BREAK)
                    .vertAlignAnd(VerticalAlign2.TOP);

            // 이 컬럼에 배정된 단락들 추가
            int startIdx = col * parasPerColumn;
            int endIdx = Math.min(startIdx + parasPerColumn, totalParas);

            for (int i = startIdx; i < endIdx; i++) {
                IntermediateParagraph iPara = allParas.get(i);
                equationCount += addParagraphToSubList(subList, iPara);
            }

            // 빈 박스 방지 - 최소 빈 단락 추가
            if (subList.countOfPara() == 0) {
                Para emptyPara = subList.addNewPara();
                emptyPara.idAnd(nextParaId())
                        .paraPrIDRefAnd("3")
                        .styleIDRefAnd("0")
                        .pageBreakAnd(false)
                        .columnBreakAnd(false)
                        .merged(false);
                Run run = emptyPara.addNewRun();
                run.charPrIDRef("0");
                run.addNewT();
            }
        }

        return equationCount;
    }

    /**
     * 단락을 SubList에 추가한다.
     */
    public int addParagraphToSubList(SubList subList, IntermediateParagraph iPara) {
        int equationCount = 0;

        String paraPrId = "3";
        String styleId = "0";
        String paraCharPrId = "0";
        IntermediateStyleDef paraStyleDef = null;

        if (iPara.paragraphStyleRef() != null) {
            String mapped = styleRegistry.getParaPrId(iPara.paragraphStyleRef());
            if (mapped != null) paraPrId = mapped;
            String mappedStyle = styleRegistry.getStyleId(iPara.paragraphStyleRef());
            if (mappedStyle != null) styleId = mappedStyle;
            String mappedCharPr = styleRegistry.getCharPrId(iPara.paragraphStyleRef());
            if (mappedCharPr != null) paraCharPrId = mappedCharPr;
            paraStyleDef = styleRegistry.getParagraphStyleDef(iPara.paragraphStyleRef());
        }

        // 인라인 단락 속성이 있으면 새 ParaPr 생성
        if (hasInlineParagraphOverrides(iPara)) {
            paraPrId = createInlineParaPr(iPara, paraStyleDef);
        }

        Para para = subList.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        if (iPara.contentItems().isEmpty()) {
            Run run = para.addNewRun();
            run.charPrIDRef(paraCharPrId);
            run.addNewT();
            return 0;
        }

        for (IntermediateParagraph.ContentItem item : iPara.contentItems()) {
            if (item.isEquation()) {
                addInlineEquationRun(para, item.equation());
                equationCount++;
            } else if (item.isTextRun()) {
                String charPrId = resolveCharPrId(item.textRun(), paraCharPrId, paraStyleDef);
                String text = sanitizeText(item.textRun().text() != null ? item.textRun().text() : "");

                Run run = para.addNewRun();
                run.charPrIDRef(charPrId);
                run.addNewT().addText(text);
            }
        }

        if (para.countOfRun() == 0) {
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT();
        }

        return equationCount;
    }

    /**
     * IntermediateFrame의 단락들을 ParaListCore(SubList 또는 SectionXMLFile)에 추가한다.
     */
    public int addParagraphsToParaList(ParaListCore paraList, IntermediateFrame frame) {
        int equationCount = 0;

        for (IntermediateParagraph iPara : frame.paragraphs()) {
            String paraPrId = "3";
            String styleId = "0";
            String paraCharPrId = "0";
            IntermediateStyleDef paraStyleDef = null;
            if (iPara.paragraphStyleRef() != null) {
                String mapped = styleRegistry.getParaPrId(iPara.paragraphStyleRef());
                if (mapped != null) paraPrId = mapped;
                String mappedStyle = styleRegistry.getStyleId(iPara.paragraphStyleRef());
                if (mappedStyle != null) styleId = mappedStyle;
                String mappedCharPr = styleRegistry.getCharPrId(iPara.paragraphStyleRef());
                if (mappedCharPr != null) paraCharPrId = mappedCharPr;
                paraStyleDef = styleRegistry.getParagraphStyleDef(iPara.paragraphStyleRef());
            }

            // 인라인 단락 속성이 있으면 새 ParaPr 생성
            if (hasInlineParagraphOverrides(iPara)) {
                paraPrId = createInlineParaPr(iPara, paraStyleDef);
            }

            if (iPara.contentItems().isEmpty()) {
                // 빈 단락
                Para para = createTextPara(paraList, paraPrId, styleId);
                Run run = para.addNewRun();
                run.charPrIDRef(paraCharPrId);
                run.addNewT();
                continue;
            }

            Para currentPara = createTextPara(paraList, paraPrId, styleId);

            for (IntermediateParagraph.ContentItem item : iPara.contentItems()) {
                if (item.isEquation()) {
                    // 인라인 수식: 같은 Para 안에 수식 Run 추가
                    addInlineEquationRun(currentPara, item.equation());
                    equationCount++;
                } else if (item.isTextRun()) {
                    // 텍스트 런
                    String charPrId = resolveCharPrId(item.textRun(), paraCharPrId, paraStyleDef);
                    String text = item.textRun().text() != null ? item.textRun().text() : "";
                    text = sanitizeText(text);

                    if (!text.contains("\n")) {
                        Run run = currentPara.addNewRun();
                        run.charPrIDRef(charPrId);
                        run.addNewT().addText(text);
                    } else {
                        String[] parts = text.split("\n", -1);
                        for (int pi = 0; pi < parts.length; pi++) {
                            if (pi > 0) {
                                ensureParaHasRun(currentPara);
                                currentPara = createTextPara(paraList, paraPrId, styleId);
                            }
                            String part = parts[pi];
                            if (!part.isEmpty()) {
                                Run run = currentPara.addNewRun();
                                run.charPrIDRef(charPrId);
                                run.addNewT().addText(part);
                            }
                        }
                    }
                }
            }

            ensureParaHasRun(currentPara);
        }

        // ParaList가 비어있으면 빈 단락 추가
        if (paraList.countOfPara() == 0) {
            Para para = createTextPara(paraList, "3", "0");
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT();
        }

        return equationCount;
    }

    // ── Helper Methods ──

    private void setupLineShape(Rectangle rect, IntermediateFrame frame) {
        rect.createLineShape();
        String strokeColor = frame.strokeColor();
        double strokeWeight = frame.strokeWeight();
        double strokeTint = frame.strokeTint();

        if (strokeColor != null && strokeWeight > 0) {
            int strokeWidthHwp = (int) (strokeWeight * 100);
            if (strokeWidthHwp < 14) strokeWidthHwp = 14;
            float strokeAlpha = (float) ((100.0 - strokeTint) / 100.0);

            rect.lineShape().colorAnd(strokeColor).widthAnd(strokeWidthHwp)
                    .styleAnd(LineType2.SOLID)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alpha(strokeAlpha);
        } else {
            rect.lineShape().colorAnd("#000000").widthAnd(0)
                    .styleAnd(LineType2.NONE)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alpha(0f);
        }
    }

    private void setupFillBrush(Rectangle rect, IntermediateFrame frame) {
        String textFrameFillColor = frame.textFrameFillColor();
        double fillTint = frame.fillTint();
        if (textFrameFillColor != null) {
            float fillAlpha = (float) ((100.0 - fillTint) / 100.0);
            rect.createFillBrush();
            rect.fillBrush().createWinBrush();
            rect.fillBrush().winBrush().faceColorAnd(textFrameFillColor)
                    .hatchColorAnd(textFrameFillColor)
                    .alpha(fillAlpha);
        }
    }

    private String sanitizeText(String text) {
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC);
        text = text.replace('\t', ' ');
        return text;
    }

    private boolean hasInlineParagraphOverrides(IntermediateParagraph iPara) {
        return iPara.inlineAlignment() != null
                || iPara.inlineFirstLineIndent() != null
                || iPara.inlineLeftMargin() != null
                || iPara.inlineRightMargin() != null
                || iPara.inlineSpaceBefore() != null
                || iPara.inlineSpaceAfter() != null
                || iPara.inlineLineSpacing() != null
                || iPara.shadingOn();
    }

    private String createInlineParaPr(IntermediateParagraph iPara, IntermediateStyleDef baseStyle) {
        String paraPrId = styleRegistry.nextParaPrId();
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();

        paraPr.idAnd(paraPrId)
                .tabPrIDRefAnd("0")
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        // 정렬
        String alignment = iPara.inlineAlignment();
        if (alignment == null && baseStyle != null) {
            alignment = baseStyle.alignment();
        }
        HorizontalAlign2 hAlign = mapAlignmentString(alignment);
        paraPr.createAlign();
        paraPr.align().horizontalAnd(hAlign).vertical(VerticalAlign1.BASELINE);

        paraPr.createHeading();
        paraPr.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);

        paraPr.createBreakSetting();
        paraPr.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.KEEP_WORD)
                .widowOrphanAnd(false)
                .keepWithNextAnd(false)
                .keepLinesAnd(false)
                .pageBreakBeforeAnd(false)
                .lineWrap(LineWrap.BREAK);

        paraPr.createAutoSpacing();
        paraPr.autoSpacing().eAsianEngAnd(false).eAsianNum(false);

        // 마진
        int indent = iPara.inlineFirstLineIndent() != null ? iPara.inlineFirstLineIndent().intValue()
                : (baseStyle != null && baseStyle.firstLineIndent() != null ? baseStyle.firstLineIndent().intValue() : 0);
        int left = iPara.inlineLeftMargin() != null ? iPara.inlineLeftMargin().intValue()
                : (baseStyle != null && baseStyle.leftMargin() != null ? baseStyle.leftMargin().intValue() : 0);
        int right = iPara.inlineRightMargin() != null ? iPara.inlineRightMargin().intValue()
                : (baseStyle != null && baseStyle.rightMargin() != null ? baseStyle.rightMargin().intValue() : 0);
        int prev = iPara.inlineSpaceBefore() != null ? iPara.inlineSpaceBefore().intValue()
                : (baseStyle != null && baseStyle.spaceBefore() != null ? baseStyle.spaceBefore().intValue() : 0);
        int next = iPara.inlineSpaceAfter() != null ? iPara.inlineSpaceAfter().intValue()
                : (baseStyle != null && baseStyle.spaceAfter() != null ? baseStyle.spaceAfter().intValue() : 0);

        paraPr.createMargin();
        paraPr.margin().createIntent();
        paraPr.margin().intent().valueAnd(indent).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createLeft();
        paraPr.margin().left().valueAnd(left).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createRight();
        paraPr.margin().right().valueAnd(right).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createPrev();
        paraPr.margin().prev().valueAnd(prev).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createNext();
        paraPr.margin().next().valueAnd(next).unit(ValueUnit2.HWPUNIT);

        // 줄 간격
        LineSpacingType lsType = LineSpacingType.PERCENT;
        int lsValue = 130;
        if (iPara.inlineLineSpacing() != null) {
            lsType = LineSpacingType.PERCENT;
            lsValue = iPara.inlineLineSpacing();
        } else if (baseStyle != null) {
            if ("fixed".equals(baseStyle.lineSpacingType())) {
                lsType = LineSpacingType.FIXED;
            }
            if (baseStyle.lineSpacingPercent() != null) {
                lsValue = baseStyle.lineSpacingPercent();
            }
        }
        paraPr.createLineSpacing();
        paraPr.lineSpacing().typeAnd(lsType).valueAnd(lsValue).unit(ValueUnit2.HWPUNIT);

        // 단락 음영 처리
        String borderFillId = "2";
        int offsetLeft = 0, offsetRight = 0, offsetTop = 0, offsetBottom = 0;
        if (iPara.shadingOn() && iPara.shadingColor() != null) {
            borderFillId = createShadingBorderFill(iPara.shadingColor());
            if (iPara.shadingOffsetLeft() != null) {
                offsetLeft = iPara.shadingOffsetLeft().intValue();
            }
            if (iPara.shadingOffsetRight() != null) {
                offsetRight = iPara.shadingOffsetRight().intValue();
            }
            if (iPara.shadingOffsetTop() != null) {
                offsetTop = iPara.shadingOffsetTop().intValue();
            }
            if (iPara.shadingOffsetBottom() != null) {
                offsetBottom = iPara.shadingOffsetBottom().intValue();
            }
        }

        paraPr.createBorder();
        paraPr.border().borderFillIDRefAnd(borderFillId)
                .offsetLeftAnd(offsetLeft).offsetRightAnd(offsetRight)
                .offsetTopAnd(offsetTop).offsetBottomAnd(offsetBottom)
                .connectAnd(false).ignoreMargin(false);

        return paraPrId;
    }

    private String createShadingBorderFill(String fillColor) {
        String bfId = String.valueOf(borderFillIdCounter.getAndIncrement());
        BorderFill bf = hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        bf.createLeftBorder();
        bf.leftBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createRightBorder();
        bf.rightBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createTopBorder();
        bf.topBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");

        bf.createFillBrush();
        bf.fillBrush().createWinBrush();
        bf.fillBrush().winBrush()
                .faceColorAnd(fillColor)
                .hatchColorAnd("#FF000000")
                .alpha(0f);

        return bfId;
    }

    private static HorizontalAlign2 mapAlignmentString(String alignment) {
        if (alignment == null) return HorizontalAlign2.JUSTIFY;
        switch (alignment) {
            case "left": return HorizontalAlign2.LEFT;
            case "center": return HorizontalAlign2.CENTER;
            case "right": return HorizontalAlign2.RIGHT;
            case "justify": return HorizontalAlign2.JUSTIFY;
            default: return HorizontalAlign2.JUSTIFY;
        }
    }

    private Para createTextPara(ParaListCore paraList, String paraPrId, String styleId) {
        Para para = paraList.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        return para;
    }

    private void ensureParaHasRun(Para para) {
        if (para.countOfRun() == 0) {
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT();
        }
    }

    private String resolveCharPrId(IntermediateTextRun iRun, String paraCharPrId,
                                   IntermediateStyleDef paraStyleDef) {
        if (hasRunOverrides(iRun)) {
            return createRunCharPr(iRun, paraStyleDef);
        }

        if (iRun.characterStyleRef() != null) {
            IntermediateStyleDef charStyleDef = styleRegistry.getCharacterStyleDef(iRun.characterStyleRef());
            if (charStyleDef != null && paraStyleDef != null && hasCharStyleOverrides(charStyleDef)) {
                return createMergedCharPr(paraStyleDef, charStyleDef);
            }
            if (charStyleDef != null && paraStyleDef != null) {
                return paraCharPrId;
            }
            String id = styleRegistry.getCharPrId(iRun.characterStyleRef());
            if (id != null) return id;
        }

        return paraCharPrId;
    }

    private boolean hasCharStyleOverrides(IntermediateStyleDef charStyleDef) {
        return charStyleDef.fontSizeHwpunits() != null
                || charStyleDef.textColor() != null
                || charStyleDef.fontFamily() != null
                || charStyleDef.bold() != null
                || charStyleDef.italic() != null;
    }

    private String createMergedCharPr(IntermediateStyleDef paraStyle, IntermediateStyleDef charStyle) {
        String charPrId = styleRegistry.nextCharPrId();
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = charStyle.fontSizeHwpunits() != null ? charStyle.fontSizeHwpunits()
                : (paraStyle.fontSizeHwpunits() != null ? paraStyle.fontSizeHwpunits() : 1000);
        String textColor = charStyle.textColor() != null ? charStyle.textColor()
                : (paraStyle.textColor() != null ? paraStyle.textColor() : "#000000");

        charPr.idAnd(charPrId)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        boolean bold = charStyle.bold() != null ? Boolean.TRUE.equals(charStyle.bold())
                : Boolean.TRUE.equals(paraStyle.bold());
        boolean italic = charStyle.italic() != null ? Boolean.TRUE.equals(charStyle.italic())
                : Boolean.TRUE.equals(paraStyle.italic());
        if (bold) charPr.createBold();
        if (italic) charPr.createItalic();

        String fontFamily = charStyle.fontFamily() != null ? charStyle.fontFamily() : paraStyle.fontFamily();
        String fontId = fontRegistry.resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        short spacing = charStyle.letterSpacing() != null ? charStyle.letterSpacing()
                : (paraStyle.letterSpacing() != null ? paraStyle.letterSpacing() : (short) 0);
        charPr.createSpacing();
        charPr.spacing().set(spacing, spacing, spacing, spacing, spacing, spacing, spacing);

        charPr.createRelSz();
        charPr.relSz().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);
        charPr.createOffset();
        charPr.offset().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        charPr.createUnderline();
        charPr.underline().typeAnd(UnderlineType.NONE).shapeAnd(LineType3.SOLID).color("#000000");
        charPr.createStrikeout();
        charPr.strikeout().shapeAnd(LineType2.NONE).color("#000000");
        charPr.createOutline();
        charPr.outline().type(LineType1.NONE);
        charPr.createShadow();
        charPr.shadow().typeAnd(CharShadowType.NONE).colorAnd("#B2B2B2")
                .offsetXAnd((short) 10).offsetY((short) 10);

        return charPrId;
    }

    private boolean hasRunOverrides(IntermediateTextRun iRun) {
        return iRun.bold() != null || iRun.italic() != null
                || iRun.fontSizeHwpunits() != null
                || iRun.textColor() != null
                || iRun.fontFamily() != null
                || iRun.letterSpacing() != null;
    }

    private String createRunCharPr(IntermediateTextRun iRun, IntermediateStyleDef paraStyleDef) {
        String charPrId = styleRegistry.nextCharPrId();
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int baseHeight = 1000;
        String baseTextColor = "#000000";
        String baseFontFamily = null;
        Boolean baseBold = null;
        Boolean baseItalic = null;
        short baseLetterSpacing = 0;
        if (paraStyleDef != null) {
            if (paraStyleDef.fontSizeHwpunits() != null) baseHeight = paraStyleDef.fontSizeHwpunits();
            if (paraStyleDef.textColor() != null) baseTextColor = paraStyleDef.textColor();
            baseFontFamily = paraStyleDef.fontFamily();
            baseBold = paraStyleDef.bold();
            baseItalic = paraStyleDef.italic();
            if (paraStyleDef.letterSpacing() != null) baseLetterSpacing = paraStyleDef.letterSpacing();
        }

        int height = iRun.fontSizeHwpunits() != null ? iRun.fontSizeHwpunits() : baseHeight;
        String textColor = iRun.textColor() != null ? iRun.textColor() : baseTextColor;

        charPr.idAnd(charPrId)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        boolean bold = Boolean.TRUE.equals(iRun.bold())
                || (iRun.bold() == null && Boolean.TRUE.equals(baseBold));
        boolean italic = Boolean.TRUE.equals(iRun.italic())
                || (iRun.italic() == null && Boolean.TRUE.equals(baseItalic));
        if (bold) charPr.createBold();
        if (italic) charPr.createItalic();

        String fontFamily = iRun.fontFamily() != null ? iRun.fontFamily() : baseFontFamily;
        String fontId = fontRegistry.resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        short letterSpacing = iRun.letterSpacing() != null ? iRun.letterSpacing() : baseLetterSpacing;
        charPr.createSpacing();
        charPr.spacing().set(letterSpacing, letterSpacing, letterSpacing, letterSpacing, letterSpacing, letterSpacing, letterSpacing);

        charPr.createRelSz();
        charPr.relSz().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        charPr.createOffset();
        charPr.offset().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);

        charPr.createUnderline();
        charPr.underline().typeAnd(UnderlineType.NONE).shapeAnd(LineType3.SOLID).color("#000000");

        charPr.createStrikeout();
        charPr.strikeout().shapeAnd(LineType2.NONE).color("#000000");

        charPr.createOutline();
        charPr.outline().type(LineType1.NONE);

        charPr.createShadow();
        charPr.shadow().typeAnd(CharShadowType.NONE).colorAnd("#B2B2B2")
                .offsetXAnd((short) 10).offsetY((short) 10);

        return charPrId;
    }

    private void addInlineEquationRun(Para para, IntermediateEquation iEq) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        try {
            Equation equation = EquationBuilder.fromHwpScript(iEq.hwpScript());
            Equation eq = run.addNewEquation();
            eq.versionAnd(equation.version())
                    .textColorAnd(equation.textColor())
                    .baseUnitAnd(equation.baseUnit())
                    .lineModeAnd(equation.lineMode())
                    .fontAnd(equation.font());
            eq.createScript();
            eq.script().addText(iEq.hwpScript());
        } catch (Exception e) {
            if (warningHandler != null) {
                warningHandler.accept("Equation conversion failed: " + iEq.hwpScript() + " - " + e.getMessage());
            }
            run.addNewT().addText("[수식: " + iEq.hwpScript() + "]");
        }
    }

    private String nextShapeId() {
        return String.valueOf(shapeIdCounter.getAndIncrement());
    }

    private String nextParaId() {
        return String.valueOf(paraIdCounter.getAndIncrement());
    }
}
