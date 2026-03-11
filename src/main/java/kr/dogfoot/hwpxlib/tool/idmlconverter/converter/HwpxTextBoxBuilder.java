package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * ASTTextFrameBlock / ASTInlineObject(INLINE_TEXT_FRAME) → HWPX 글상자(Rectangle+DrawText)
 * 또는 1x1 테이블(플로팅 텍스트 프레임)로 변환한다.
 */
public class HwpxTextBoxBuilder {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;

    public HwpxTextBoxBuilder(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
    }

    // ── 인라인 글상자 (treatAsChar=true) ──

    /**
     * ASTTextFrameBlock → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    void addTextBox(SectionXMLFile sectionFile, ASTTextFrameBlock block) {
        // 앵커 단락
        Para framePara = sectionFile.addNewPara();
        framePara.idAnd(HwpxUtil.nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        long w = block.effectiveWidth();
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(block.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(block.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        long textBoxMinH = ConverterConstants.MIN_TEXT_BOX_HEIGHT;
        rect.createOrgSz();
        rect.orgSz().set(w, textBoxMinH);
        rect.createCurSz();
        rect.curSz().set(w, 0L); // height=0: 한컴이 내용에 맞게 자동 계산
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        short rotAngle = (short) Math.round(block.rotationAngle());
        rect.rotationInfo().angleAnd(rotAngle)
                .centerXAnd(w / 2).centerYAnd(textBoxMinH / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        if (rotAngle != 0) {
            double radians = Math.toRadians(rotAngle);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            rect.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);
        } else {
            rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        }

        // LineShape (테두리)
        setupTextBoxLineShape(rect, block.strokeColor(), block.strokeWeight(),
                block.strokeType(), block.strokeTint());

        // FillBrush (배경색)
        setupTextBoxFillBrush(rect, block.fillColor(), block.fillTint());

        // DrawText (글상자 내용)
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);

        dt.createTextMargin();
        dt.textMargin()
                .leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        dt.createSubList();
        SubList subList = dt.subList();
        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        VerticalAlign2 vAlign = mapVerticalJustification(block.verticalJustification());

        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(vAlign)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);

        // 내용 단락
        for (ASTParagraph para : block.paragraphs()) {
            paragraphBuilder.addParagraphToSubList(subList, para);
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점 — 최소 높이, curSz height=0 으로 내용에 맞게 자동 확장
        rect.ratioAnd(computeCornerRatio(block.cornerRadius(), w, block.height()));
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, textBoxMinH);
        rect.createPt3();
        rect.pt3().set(0L, textBoxMinH);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(textBoxMinH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 글자처럼 취급 (인라인)
        rect.createPos();
        rect.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        // OutMargin
        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 앵커 런에 빈 텍스트 추가
        anchorRun.addNewT();
    }

    // ── 글상자 테두리/배경 ──

    /**
     * 글상자 테두리 설정
     */
    void setupTextBoxLineShape(Rectangle rect, String strokeColor, double strokeWeightPt,
                                String strokeType, double strokeTint) {
        rect.createLineShape();
        boolean hasStroke = strokeColor != null && strokeColor.startsWith("#") && strokeWeightPt > 0;

        if (hasStroke) {
            // points → hwpunit (1pt = 약 100 hwpunit for line width)
            int strokeW = (int) Math.round(strokeWeightPt * 100);
            if (strokeW < 14) strokeW = 14;
            float alpha = (float) ((100.0 - strokeTint) / 100.0);
            LineType2 lineType = HwpxParagraphBuilder.strokeTypeToLineType(strokeType);

            rect.lineShape().colorAnd(strokeColor).widthAnd(strokeW)
                    .styleAnd(lineType)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.SMALL_SMALL).tailSzAnd(ArrowSize.SMALL_SMALL)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alphaAnd(alpha);
        } else {
            rect.lineShape().colorAnd("#000000").widthAnd(0)
                    .styleAnd(LineType2.NONE)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.SMALL_SMALL).tailSzAnd(ArrowSize.SMALL_SMALL)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alphaAnd(0f);
        }
    }

    /**
     * 글상자 배경색 설정.
     * fillTint는 색상 농도(흰색 블렌딩)로 처리하고, alpha=0(불투명)으로 설정.
     */
    void setupTextBoxFillBrush(Rectangle rect, String fillColor, double fillTint) {
        if (fillColor != null && fillColor.startsWith("#")) {
            String tinted = blendColorWithWhite(fillColor, fillTint / 100.0);
            rect.createFillBrush();
            rect.fillBrush().createWinBrush();
            rect.fillBrush().winBrush()
                    .faceColorAnd(tinted)
                    .hatchColorAnd("#000000")
                    .alphaAnd(0f);
        }
    }

    /**
     * IDML verticalJustification → HWPX VerticalAlign2 매핑
     */
    VerticalAlign2 mapVerticalJustification(String vj) {
        return HwpxEnumMapper.mapVerticalJustification(vj);
    }

    // ── 플로팅 텍스트 프레임 블록 변환 (1x1 테이블) ──

    /**
     * 플로팅 TEXT_FRAME_BLOCK → 1x1 Table (PAPER 기준 절대 좌표).
     * 회전이 있는 경우 Table은 회전을 지원하지 않으므로 Rectangle(DrawTextBox)을 사용.
     * 글상자(rect+drawText) 대신 1x1 테이블을 사용하여 클릭만으로 텍스트 편집 가능.
     */
    public void convertTextFrameBlock(Para framePara, ASTTextFrameBlock block) {
        long w = block.effectiveWidth();
        long h = block.height();

        // 음수 또는 0 크기 블록 건너뜀 (페이지 밖 객체)
        if (w <= 0 || h <= 0) return;

        // 회전이 있는 블록은 Table 대신 Rectangle(DrawTextBox)로 변환
        short rotAngle = (short) Math.round(block.rotationAngle());
        if (rotAngle != 0) {
            convertRotatedFloatingBlock(framePara, block, w, h, rotAngle);
            return;
        }

        // 라운드 코너 + 유효 fill/stroke가 있는 단일 컬럼 블록은 Rectangle(DrawTextBox)로 변환
        int colCount = Math.max(block.columnCount(), 1);
        if (colCount <= 1 && block.cornerRadius() > 0) {
            boolean hasFill = block.fillColor() != null && block.fillColor().startsWith("#");
            boolean hasStroke = block.strokeColor() != null && block.strokeColor().startsWith("#")
                    && block.strokeWeight() > 0;
            if (hasFill || hasStroke) {
                convertRoundedFloatingBlock(framePara, block, w, h);
                return;
            }
        }

        if (colCount <= 1) {
            // 래퍼 fill 또는 프레임 자체 fill이 있으면 배경 사각형 추가
            boolean hasOwnVisibleFill = block.fillColor() != null
                    && block.fillColor().startsWith("#") && !block.fillColor().equals("#FFFFFF");
            boolean hasWrapper = block.hasWrapperFill() || hasOwnVisibleFill;
            if (hasWrapper) {
                addWrapperRoundedRect(framePara, block, w, h);
            }
            convertSingleColumnTable(framePara, block, block.x(), block.y(), w, h,
                    block.paragraphs(), hasWrapper);
        } else {
            // 다단 — N개 독립 글상자(1×1 테이블)를 수평 배치
            long[] colWidths = computeColumnWidths(block, colCount);
            java.util.List<java.util.List<ASTParagraph>> distributed =
                    distributeParagraphs(block.paragraphs(), colCount);

            // 다단 프레임에 테두리/라운드코너가 있으면 전체를 감싸는 배경 사각형 추가
            boolean hasFrameBorder = block.strokeColor() != null
                    && block.strokeColor().startsWith("#")
                    && block.strokeWeight() > 0;
            boolean hasFrameStyle = hasFrameBorder || block.cornerRadius() > 0;
            if (hasFrameStyle) {
                addMultiColumnFrameBorder(framePara, block, w, h);
            }

            long xCursor = block.x();
            long gutter = block.columnGutter();
            for (int c = 0; c < colCount; c++) {
                convertSingleColumnTable(framePara, block, xCursor, block.y(),
                        colWidths[c], h, distributed.get(c), hasFrameStyle);
                xCursor += colWidths[c] + gutter;
            }
        }
    }

    /**
     * 다단 프레임의 테두리/배경을 별도 Rectangle로 추가.
     * 다단 분할 시 원본 프레임의 라운드 사각형 테두리가 유지되도록
     * 전체 프레임 영역을 감싸는 배경 rect를 BEHIND_TEXT로 생성한다.
     */
    private void addMultiColumnFrameBorder(Para framePara, ASTTextFrameBlock block,
                                            long w, long h) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, h);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape (테두리)
        setupTextBoxLineShape(rect, block.strokeColor(), block.strokeWeight(),
                block.strokeType(), block.strokeTint());

        // FillBrush (배경색)
        setupTextBoxFillBrush(rect, block.fillColor(), block.fillTint());

        // Rectangle 꼭짓점 + 라운드 코너
        rect.ratioAnd(computeCornerRatio(block.cornerRadius(), w, h));
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // ShapeSize — PAPER 기준 절대 좌표
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

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
                .vertOffsetAnd(block.y())
                .horzOffset(block.x());

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    /**
     * 래퍼 사각형(부모 Rectangle)의 fill을 배경 사각형으로 변환.
     * InDesign에서 "큰 채우기 사각형 + 안쪽 텍스트 프레임" 패턴을
     * HWPX에서 "배경 채움 사각형 + 셀" 패턴으로 근사한다.
     */
    private void addWrapperRoundedRect(Para framePara, ASTTextFrameBlock block,
                                        long w, long h) {
        // 배경 fill: 래퍼 fill → 프레임 자체 fill → 없음
        String bgColor = null;
        double bgTint = 100;
        if (block.hasWrapperFill()) {
            double tint = block.wrapperFillTint();
            if (tint < 0) tint = 100;
            bgColor = blendColorWithWhite(block.wrapperFillColor(), tint / 100.0);
        } else if (block.fillColor() != null && block.fillColor().startsWith("#")) {
            bgColor = block.fillColor();
            bgTint = block.fillTint();
        }
        // 스트로크 (프레임 자체 stroke 속성)
        String strokeColor = null;
        double strokeWeightPt = 0;
        if (block.strokeColor() != null && block.strokeColor().startsWith("#")) {
            strokeColor = block.strokeColor();
            strokeWeightPt = block.strokeWeight();
        }
        boolean hasBg = bgColor != null && bgColor.startsWith("#");
        // 배경도 스트로크도 라운드 코너도 없으면 건너뜀
        if (!hasBg && strokeColor == null && block.cornerRadius() <= 0) return;

        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, h);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // 스트로크 (프레임 자체 stroke)
        setupTextBoxLineShape(rect, strokeColor, strokeWeightPt, "Solid", 100);

        // 배경 fill (래퍼 fill 또는 프레임 fill)
        if (hasBg) {
            setupTextBoxFillBrush(rect, bgColor, bgTint);
        } else {
            setupTextBoxFillBrush(rect, "#FFFFFF", 100);
        }

        // 라운드 코너
        rect.ratioAnd(computeCornerRatio(block.cornerRadius(), w, h));
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

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
                .vertOffsetAnd(block.y())
                .horzOffset(block.x());

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    /**
     * 회전이 있는 플로팅 텍스트 프레임 → Rectangle(DrawTextBox)로 변환.
     * HWPX Table은 회전을 지원하지 않으므로, 회전이 필요한 블록은 DrawTextBox를 사용한다.
     */
    private void convertRotatedFloatingBlock(Para framePara, ASTTextFrameBlock block,
                                              long w, long h, short rotAngle) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();

        rect.idAnd(shapeId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(wrapMethod)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(block.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, 0L);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd(rotAngle)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        double radians = Math.toRadians(rotAngle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        rect.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);

        // LineShape (테두리)
        setupTextBoxLineShape(rect, block.strokeColor(), block.strokeWeight(),
                block.strokeType(), block.strokeTint());

        // FillBrush (배경색)
        setupTextBoxFillBrush(rect, block.fillColor(), block.fillTint());

        // DrawText (글상자 내용)
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);

        dt.createTextMargin();
        dt.textMargin().leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        VerticalAlign2 cellVAlign = mapVerticalJustification(block.verticalJustification());
        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(cellVAlign);
        subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");

        for (ASTParagraph para : block.paragraphs()) {
            paragraphBuilder.addParagraphToSubList(subList, para);
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점 (필수 요소)
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // ShapeSize — PAPER 기준 절대 좌표
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

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
                .vertOffsetAnd(block.y())
                .horzOffset(block.x());

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    /**
     * 라운드 코너가 있는 플로팅 블록 → Rectangle + DrawText로 변환.
     * Table 방식 대신 사용하여 cornerRadius가 직접 반영되도록 한다.
     */
    private void convertRoundedFloatingBlock(Para framePara, ASTTextFrameBlock block,
                                              long w, long h) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();

        rect.idAnd(shapeId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(wrapMethod)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(block.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, 0L);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape (테두리)
        setupTextBoxLineShape(rect, block.strokeColor(), block.strokeWeight(),
                block.strokeType(), block.strokeTint());

        // FillBrush (배경색) — 프레임 자체 fill이 없으면 래퍼 배경(흰색) 사용
        String fillColor = block.fillColor();
        double fillTint = block.fillTint();
        if ((fillColor == null || !fillColor.startsWith("#")) && block.hasWrapperFill()) {
            fillColor = "#FFFFFF";
            fillTint = 100;
        }
        setupTextBoxFillBrush(rect, fillColor, fillTint);

        // DrawText (글상자 내용)
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);

        dt.createTextMargin();
        dt.textMargin().leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        VerticalAlign2 cellVAlign = mapVerticalJustification(block.verticalJustification());
        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(cellVAlign);
        subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");

        for (ASTParagraph para : block.paragraphs()) {
            paragraphBuilder.addParagraphToSubList(subList, para);
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점 + 라운드 코너
        rect.ratioAnd(computeCornerRatio(block.cornerRadius(), w, h));
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // ShapeSize — PAPER 기준 절대 좌표
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

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
                .vertOffsetAnd(block.y())
                .horzOffset(block.x());

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    /**
     * 단일 1×1 테이블(글상자) 생성.
     * 다단인 경우 각 컬럼마다 호출된다.
     */
    private void convertSingleColumnTable(Para framePara, ASTTextFrameBlock block,
                                           long x, long y, long w, long h,
                                           java.util.List<ASTParagraph> paragraphs) {
        convertSingleColumnTable(framePara, block, x, y, w, h, paragraphs, false);
    }

    private void convertSingleColumnTable(Para framePara, ASTTextFrameBlock block,
                                           long x, long y, long w, long h,
                                           java.util.List<ASTParagraph> paragraphs,
                                           boolean suppressBorder) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Table table = anchorRun.addNewTable();

        // ShapeObject — 배경 전용 블록은 BEHIND_TEXT + z-order=0, 일반은 IN_FRONT_OF_TEXT
        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();
        String tableId = HwpxUtil.nextShapeId();
        table.idAnd(tableId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(wrapMethod)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(paragraphs));

        // 테이블 속성 — 1행 1열
        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 1)
                .colCntAnd((short) 1)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        // ShapeSize
        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — PAPER 기준 절대 좌표
        table.createPos();
        table.pos().treatAsCharAnd(false)
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
        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // InMargin
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 1행 1열 셀 생성
        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();

        // suppressBorder: 배경 사각형이 테두리를 담당하므로 개별 컬럼은 테두리/배경 없이
        String cellBfId = suppressBorder ? "1" : createTextFrameBorderFill(block);

        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(cellBfId);

        // 셀 주소
        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);

        // 셀 병합 (1x1이므로 span=1)
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);

        // 셀 크기
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(h);

        // 셀 여백 — 텍스트 프레임의 inset 값 사용
        tc.createCellMargin();
        tc.cellMargin().leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        // 셀 내부 SubList
        tc.createSubList();
        SubList subList = tc.subList();
        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        // 플로팅 글상자: 절대 좌표로 배치되므로 TOP 강제 (CENTER 시 텍스트 흐름과 어긋남)
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        // 연결 글상자 링크 설정
        // resolved 기반 문단 재배치가 완료된 프레임은 링크 해제 (각 프레임이 독립적으로 표시)
        if (block.distributed()) {
            subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");
        } else {
            applySubListLink(subList, block.storyId());
        }

        // 블록 위치 추적 (오버레이 좌표 계산용)
        ctx.blockPageX = x;
        ctx.blockPageY = y;
        ctx.blockInsetLeft = block.insetLeft();
        ctx.blockInsetTop = block.insetTop();
        ctx.cellContentYCursor = 0;

        // 단락 추가 (인라인 테이블 포함)
        for (ASTParagraph para : paragraphs) {
            if (para.inlineTable() != null && ctx.tableBuilderRef != null) {
                ctx.tableBuilderRef.addInlineTableToSubList(subList, para.inlineTable());
            } else {
                paragraphBuilder.addParagraphToSubList(subList, para);
            }
        }
        // 빈 텍스트 프레임 방지
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }
    }

    /**
     * SubList에 연결 글상자 링크를 설정한다.
     * storyId가 2개 이상의 블록을 공유하면, 사전 할당된 linkId로 체인을 구성.
     */
    private void applySubListLink(SubList subList, String storyId) {
        if (storyId == null) {
            subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");
            return;
        }

        java.util.List<String> linkIds = ctx.storyLinkIds.get(storyId);
        if (linkIds == null) {
            // 연결 글상자 아님 — 단독 프레임
            subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");
            return;
        }

        int idx = ctx.storyLinkIndex.getOrDefault(storyId, 0);
        String myLinkId = (idx < linkIds.size()) ? linkIds.get(idx) : "0";
        String nextLinkId = (idx + 1 < linkIds.size()) ? linkIds.get(idx + 1) : "0";

        subList.linkListIDRefAnd(myLinkId).linkListNextIDRefAnd(nextLinkId);

        // 인덱스 진행
        ctx.storyLinkIndex.put(storyId, idx + 1);
    }

    // ── 다단 글상자 헬퍼 ──

    /**
     * 각 컬럼의 폭을 계산한다.
     * columnWidths가 지정되어 있으면 비율 기반으로 테이블 폭에 맞게 조정,
     * 아니면 (전체폭 - 거터합) / N으로 균등 분할.
     */
    private long[] computeColumnWidths(ASTTextFrameBlock block, int colCount) {
        long totalWidth = block.effectiveWidth();
        long gutter = block.columnGutter();
        long totalGutter = gutter * (colCount - 1);
        long contentWidth = totalWidth - totalGutter;

        long[] result = new long[colCount];

        if (block.columnWidths() != null && block.columnWidths().length == colCount) {
            // FlexibleWidth / FixedWidth: 비율 기반 분배
            long specSum = 0;
            for (long w : block.columnWidths()) specSum += w;
            if (specSum > 0) {
                long assigned = 0;
                for (int i = 0; i < colCount - 1; i++) {
                    result[i] = contentWidth * block.columnWidths()[i] / specSum;
                    assigned += result[i];
                }
                result[colCount - 1] = contentWidth - assigned; // 나머지 보정
            } else {
                java.util.Arrays.fill(result, contentWidth / colCount);
                result[colCount - 1] = contentWidth - (contentWidth / colCount) * (colCount - 1);
            }
        } else {
            // FixedNumber (균등 분할)
            long baseWidth = contentWidth / colCount;
            java.util.Arrays.fill(result, baseWidth);
            result[colCount - 1] = contentWidth - baseWidth * (colCount - 1);
        }

        return result;
    }

    /**
     * 단락들을 N개 컬럼에 문자 수 기반으로 균등 분배한다.
     * 그리디 방식: 왼쪽 컬럼부터 채우고, 할당량 초과 시 다음 컬럼으로.
     */
    private java.util.List<java.util.List<ASTParagraph>> distributeParagraphs(
            java.util.List<ASTParagraph> paragraphs, int colCount) {
        java.util.List<java.util.List<ASTParagraph>> result = new java.util.ArrayList<>();
        for (int i = 0; i < colCount; i++) result.add(new java.util.ArrayList<>());

        if (paragraphs.isEmpty() || colCount <= 1) {
            result.get(0).addAll(paragraphs);
            return result;
        }

        // columnBreakAfter가 있으면 명시적 컬럼 분배
        boolean hasExplicitBreak = false;
        for (ASTParagraph p : paragraphs) {
            if (p.columnBreakAfter()) { hasExplicitBreak = true; break; }
        }

        if (hasExplicitBreak) {
            int currentCol = 0;
            for (ASTParagraph p : paragraphs) {
                if (currentCol >= colCount) currentCol = colCount - 1;
                result.get(currentCol).add(p);
                if (p.columnBreakAfter() && currentCol < colCount - 1) {
                    currentCol++;
                }
            }
            return result;
        }

        // fallback: 문자 수 기반 균등 분배
        int totalChars = 0;
        int[] paraChars = new int[paragraphs.size()];
        for (int i = 0; i < paragraphs.size(); i++) {
            paraChars[i] = countParagraphChars(paragraphs.get(i));
            totalChars += paraChars[i];
        }

        int charsPerCol = Math.max(totalChars / colCount, 1);
        int currentCol = 0;
        int currentChars = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            result.get(currentCol).add(paragraphs.get(i));
            currentChars += paraChars[i];

            if (currentChars >= charsPerCol && currentCol < colCount - 1) {
                currentCol++;
                currentChars = 0;
            }
        }

        return result;
    }

    /**
     * 단락 내 텍스트 런의 문자 수 합계.
     */
    private int countParagraphChars(ASTParagraph para) {
        int count = 0;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null) count += text.length();
            }
        }
        return Math.max(count, 1); // 빈 단락도 최소 1로 카운트
    }

    /**
     * 텍스트 프레임 블록의 테두리/배경을 BorderFill로 생성.
     * 테두리가 없는 경우 NONE으로, 배경이 없는 경우 투명으로 설정.
     */
    String createTextFrameBorderFill(ASTTextFrameBlock block) {
        String bfId = String.valueOf(ctx.borderFillIdCounter.getAndIncrement());
        BorderFill bf = ctx.hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(block.dropShadow())
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        // 테두리 — 텍스트 프레임의 strokeColor/strokeWeight 반영
        String stroke = block.strokeColor();
        boolean hasStroke = stroke != null && stroke.startsWith("#") && block.strokeWeight() > 0;

        LineType2 lineType = LineType2.NONE;
        LineWidth lineWidth = LineWidth.MM_0_1;
        String borderColor = "#000000";

        if (hasStroke) {
            lineType = LineType2.SOLID;
            lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth((long) Math.round(block.strokeWeight()));
            borderColor = stroke;
        }

        bf.createLeftBorder();
        bf.leftBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createRightBorder();
        bf.rightBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createTopBorder();
        bf.topBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);

        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");

        // 배경 채우기 (fillTint는 색상 농도로 RGB에 적용, 불투명 처리)
        String fill = block.fillColor();
        if (fill != null && fill.startsWith("#")) {
            String tinted = blendColorWithWhite(fill, block.fillTint() / 100.0);
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(tinted)
                    .hatchColorAnd("#FF000000")
                    .alphaAnd(0f);
        }

        return bfId;
    }

    // ── 인라인 텍스트 프레임 (글상자, treatAsChar=true) ──

    /**
     * 인라인 텍스트 프레임 / 그룹 → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    void addInlineTextFrame(Para para, ASTInlineObject obj) {
        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        if (!hasParagraphs && !hasInlineTables) return;

        // 테이블 셀 내부 오버레이 → 페이지 레벨로 승격
        // 한글(HWPX 렌더러)이 테이블 셀 SubList 내부의 플로팅 객체를 지원하지 않으므로
        // 페이지 레벨 PAPER 기준 절대 좌표로 변환한다.
        if (obj.isOverlay() && ctx.insideTableCell) {
            HwpxConverterContext.DeferredOverlay d = new HwpxConverterContext.DeferredOverlay();
            d.overlay = obj;
            d.pageX = ctx.blockPageX + ctx.blockInsetLeft + obj.overlayX();
            d.pageY = ctx.blockPageY + ctx.blockInsetTop + obj.overlayY();
            ctx.deferredOverlays.add(d);
            return;
        }

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;
        // IDML에서 높이가 명시된 경우 그대로 사용 (최소값 강제 안 함)
        long h = obj.height() > 0 ? obj.height() : ConverterConstants.MIN_TEXT_BOX_HEIGHT;

        // IDML 속성 기반 래핑 모드 결정 (크기 기반 폴백은 이미지에만 적용, 텍스트프레임은 인라인 유지)
        boolean isAnchored = "Anchored".equals(obj.anchoredPosition());
        boolean isAboveLine = obj.anchoredPosition() != null
                && obj.anchoredPosition().startsWith("AboveLine");
        String wrapMode = obj.textWrapMode();
        boolean hasExplicitWrap = wrapMode != null && !"None".equals(wrapMode);
        // anchoredPosition이 있는 경우에만 어울림 적용
        // anchoredPosition 없는 순수 인라인 TextFrame은 treatAsChar=true로 유지 (나란히 배치)
        boolean useWrapping = isAboveLine
                || (isAnchored && hasExplicitWrap);

        TextWrapMethod twm;
        TextFlowSide tfs;
        if (obj.isOverlay()) {
            // 오버레이: 이미지 위에 겹쳐서 표시, 텍스트 흐름에 영향 없음
            twm = TextWrapMethod.IN_FRONT_OF_TEXT;
            tfs = TextFlowSide.BOTH_SIDES;
        } else if (useWrapping) {
            twm = HwpxImageBuilder.mapTextWrapMethod(wrapMode);
            tfs = HwpxImageBuilder.mapTextFlowSide(obj.textWrapSide());
        } else {
            // 인라인(treatAsChar=true) rect: SQUARE로 같은 줄에 텍스트 배치
            // TOP_AND_BOTTOM은 rect 뒤 텍스트를 다음 줄로 밀어냄
            twm = TextWrapMethod.SQUARE;
            tfs = TextFlowSide.BOTH_SIDES;
        }

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Rectangle rect = run.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(obj.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, h); // treatAsChar=true일 때 줄 높이에 반영되도록 명시적 높이 설정
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape — 부모 Group 배경 사각형의 테두리
        setupTextBoxLineShape(rect, obj.strokeColor(), obj.strokeWeight(), "Solid", obj.strokeTint());

        // FillBrush — 부모 Group 배경 사각형의 채우기 색상
        setupTextBoxFillBrush(rect, obj.fillColor(), obj.fillTint());

        // DrawText
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);
        dt.createTextMargin();
        dt.textMargin().leftAnd(obj.textMarginLeft()).rightAnd(obj.textMarginRight())
                .topAnd(obj.textMarginTop()).bottomAnd(obj.textMarginBottom());

        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);

        // 내용 단락 (풀 버전 — 인라인 객체도 재귀 처리)
        if (obj.paragraphs() != null) {
            for (ASTParagraph astPara : obj.paragraphs()) {
                paragraphBuilder.addParagraphToSubList(subList, astPara);
            }
        }

        // 인라인 테이블 → SubList 내 인라인 테이블
        if (obj.inlineTables() != null && ctx.tableBuilderRef != null) {
            for (ASTTable astTable : obj.inlineTables()) {
                ctx.tableBuilderRef.addInlineTableToSubList(subList, astTable);
            }
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점
        rect.ratioAnd(computeCornerRatio(obj.cornerRadius(), w, h));
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition
        rect.createPos();
        if (obj.isOverlay()) {
            // 오버레이 모드 — 이미지 컨테이너(rect+imgBrush) 내부의 drawText 단락 기준
            // PARA 기준 상대 좌표로 배치 (컨테이너 내부이므로 PARA = 이미지 영역)
            rect.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(true)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(obj.overlayY())
                    .horzOffset(obj.overlayX());
        } else if (useWrapping) {
            // 어울리기/자리차지 — 단락 기준 플로팅
            rect.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.CENTER)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);
        } else {
            // 기존 인라인 (글자처럼 취급)
            rect.pos().treatAsCharAnd(true)
                    .affectLSpacingAnd(true)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.BOTTOM)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);
        }

        // OutMargin — IDML TextWrapOffset 반영
        rect.createOutMargin();
        if (obj.isOverlay()) {
            // 오버레이: 겹침 허용, 마진 없음
            rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        } else if (useWrapping) {
            rect.outMargin().leftAnd(obj.textWrapLeft()).rightAnd(obj.textWrapRight())
                    .topAnd(obj.textWrapTop()).bottomAnd(obj.textWrapBottom());
        } else {
            rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        }
    }

    // ── 페이지 레벨 오버레이 (셀 내부에서 승격된 플로팅 텍스트박스) ──

    /**
     * 셀 내부 오버레이를 페이지 레벨 PAPER 기준 절대 좌표 rect로 변환한다.
     * 한글(HWPX 렌더러)이 테이블 셀 SubList 내부의 플로팅 객체를 지원하지 않으므로,
     * 오버레이 텍스트프레임을 페이지 레벨 IN_FRONT_OF_TEXT로 승격한다.
     */
    public void addPageLevelOverlay(Para anchorPara, ASTInlineObject obj, long pageX, long pageY) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return;

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;
        long h = obj.height() > 0 ? obj.height() : 1000;

        Run run = anchorPara.addNewRun();
        run.charPrIDRef("0");

        // hp:tbl (1x1 table) — 한글에서 hp:rect의 IN_FRONT_OF_TEXT가
        // 이미지 위에 올바르게 렌더링되지 않으므로 hp:tbl 사용
        Table table = run.addNewTable();
        String tableId = HwpxUtil.nextShapeId();

        table.idAnd(tableId)
                .zOrderAnd(1000)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(obj.paragraphs()));

        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 1)
                .colCntAnd((short) 1)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        table.createPos();
        table.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(pageY)
                .horzOffset(pageX);

        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 1행 1열 셀
        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();

        String cellBfId = createOverlayBorderFill(obj);

        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(cellBfId);

        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(h);
        // 셀 여백: 페이지 레벨 승격된 오버레이는 위치가 절대 좌표로 이미 처리되므로
        // applyImplicitTextMargin()이 설정한 위치 기반 여백은 사용하지 않는다.
        tc.createCellMargin();
        tc.cellMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        tc.createSubList();
        SubList subList = tc.subList();
        VerticalAlign2 vAlign = mapVerticalJustification(obj.verticalJustification());
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(vAlign)
                .linkListIDRefAnd("0").linkListNextIDRefAnd("0");

        for (ASTParagraph astPara : obj.paragraphs()) {
            paragraphBuilder.addParagraphToSubList(subList, astPara);
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }
    }

    /**
     * 오버레이 ASTInlineObject의 fill/stroke를 반영하는 BorderFill을 생성한다.
     */
    private String createOverlayBorderFill(ASTInlineObject obj) {
        String bfId = String.valueOf(ctx.borderFillIdCounter.getAndIncrement());
        BorderFill bf = ctx.hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        // 테두리
        String stroke = obj.strokeColor();
        boolean hasStroke = stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0;

        LineType2 lineType = LineType2.NONE;
        LineWidth lineWidth = LineWidth.MM_0_1;
        String borderColor = "#000000";

        if (hasStroke) {
            lineType = LineType2.SOLID;
            lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth((long) Math.round(obj.strokeWeight()));
            borderColor = stroke;
        }

        bf.createLeftBorder();
        bf.leftBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createRightBorder();
        bf.rightBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createTopBorder();
        bf.topBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);

        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");

        // 배경 채우기 (fillTint는 색상 농도로 RGB에 적용, 불투명 처리)
        String fill = obj.fillColor();
        if (fill != null && fill.startsWith("#")) {
            String tinted = blendColorWithWhite(fill, obj.fillTint() / 100.0);
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(tinted)
                    .hatchColorAnd("#FF000000")
                    .alphaAnd(0f);
        }

        return bfId;
    }

    // ── 스페이서 인라인 사각형 (빈 인라인 Rectangle, 글자 취급) ──

    /**
     * 빈 인라인 Rectangle → hp:rect (내용 없음, treatAsChar="1")
     * InDesign에서 항목 사이 공간 확보 역할.
     */
    /**
     * 스페이서를 1x1 투명 PNG 이미지로 추가.
     * 빈 Rectangle 대신 이미지를 사용하여 한컴 편집기에서 안정적으로 간격 유지.
     */
    void addSpacerRect(Para para, ASTInlineObject obj) {
        long w = obj.width() > 0 ? obj.width() : 100;
        long h = obj.height() > 0 ? obj.height() : 100;

        String itemId = getOrCreateSpacerImage();

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Picture pic = run.addNewPicture();
        String picId = HwpxUtil.nextShapeId();

        pic.idAnd(picId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(HwpxUtil.nextShapeId());
        pic.createOffset();
        pic.offset().set(0L, 0L);
        pic.createOrgSz();
        pic.orgSz().set(w, h);
        pic.createCurSz();
        pic.curSz().set(w, h);
        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);
        pic.createRotationInfo();
        pic.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition
        pic.createPos();
        if (obj.isOverlay()) {
            pic.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(true)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(obj.overlayY())
                    .horzOffset(obj.overlayX());
        } else {
            pic.pos().treatAsCharAnd(true)
                    .affectLSpacingAnd(true)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.BOTTOM)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);
        }

        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageRect
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(w, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(w, h);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, h);

        // ImageClip/Dim — 1x1 픽셀 원본
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(75L).topAnd(0L).bottomAnd(75L);
        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        pic.createImgDim();
        pic.imgDim().dimwidthAnd(75L).dimheightAnd(75L);

        // Image 참조
        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);
    }

    /** 1x1 투명 PNG를 한 번만 생성하여 재사용 */
    private String getOrCreateSpacerImage() {
        if (ctx.spacerImageId != null) return ctx.spacerImageId;
        ctx.spacerImageId = ImageInserter.registerImage(ctx.hwpxFile, SPACER_PNG_1x1, "png");
        return ctx.spacerImageId;
    }

    /** 1x1 투명 PNG (67 bytes) */
    private static final byte[] SPACER_PNG_1x1;
    static {
        // Minimal valid 1x1 transparent PNG
        int[] raw = {
            0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A, // PNG signature
            0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52, // IHDR chunk
            0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01, // 1x1
            0x08,0x06,0x00,0x00,0x00,0x1F,0x15,0xC4, // RGBA, CRC
            0x89,0x00,0x00,0x00,0x0A,0x49,0x44,0x41, // IDAT chunk
            0x54,0x78,0x9C,0x62,0x00,0x00,0x00,0x02, // deflated data
            0x00,0x01,0xE5,0x27,0xDE,0xFC,0x00,0x00, // CRC
            0x00,0x00,0x49,0x45,0x4E,0x44,0xAE,0x42, // IEND chunk
            0x60,0x82
        };
        SPACER_PNG_1x1 = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) SPACER_PNG_1x1[i] = (byte) raw[i];
    }

    // ── DropCap 해석 ──

    /**
     * 단락 목록의 첫 번째 단락 스타일에서 DropCapStyle을 결정한다.
     * IDML DropCapLines → HWPX DropCapStyle 매핑:
     *   2 → DoubleLine, 3+ → TripleLine
     */
    DropCapStyle resolveDropCapStyle(java.util.List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return DropCapStyle.None;
        ASTParagraph firstPara = paragraphs.get(0);
        String styleRef = firstPara.paragraphStyleRef();
        if (styleRef == null) return DropCapStyle.None;
        ASTStyleDef style = paragraphBuilder.findParagraphStyle(styleRef);
        if (style == null || style.dropCapLines() == null) return DropCapStyle.None;
        if (style.dropCapLines() >= 3) return DropCapStyle.TripleLine;
        if (style.dropCapLines() >= 2) return DropCapStyle.DoubleLine;
        return DropCapStyle.None;
    }

    /**
     * IDML cornerRadius (pts) → HWPX 둥근 사각형 ratio (0~50).
     * ratio: 0=직각, 20=둥근 모양, 50=반원.
     * HWPX ratio는 가로/세로 양축에 동일 비율로 적용되므로,
     * 비정사각형에서 ratio=50이면 타원이 됨.
     * 가로세로 비율이 1.5:1 이상일 때 ratio를 제한하여 라운드 사각형 유지.
     */
    private static short computeCornerRatio(double cornerRadiusPts, long widthHwp, long heightHwp) {
        if (cornerRadiusPts <= 0) return 0;
        long minSide = Math.min(widthHwp, heightHwp);
        long maxSide = Math.max(widthHwp, heightHwp);
        if (minSide <= 0) return 0;
        long cornerHwp = Math.round(cornerRadiusPts * 100); // 1pt = 100 HWPUNIT
        short ratio = (short) Math.min(50, Math.round(cornerHwp * 100.0 / minSide));

        // ratio=50 + 비정사각형 → 타원 방지: maxSide 기준으로 재계산
        if (ratio >= 50 && maxSide > minSide * 3 / 2) {
            ratio = (short) Math.max(1, Math.round(minSide * 50.0 / maxSide));
        }
        return ratio > 0 ? ratio : 1;
    }

    /**
     * 색상 hex를 fraction 비율로 흰색과 블렌딩.
     * fraction=1.0 → 원래 색상, fraction=0.0 → 흰색.
     */
    private static String blendColorWithWhite(String hex, double fraction) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return hex;
        try {
            int rgb = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = (int) Math.round(255 + (r - 255) * fraction);
            g = (int) Math.round(255 + (g - 255) * fraction);
            b = (int) Math.round(255 + (b - 255) * fraction);
            return String.format("#%02X%02X%02X",
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            return hex;
        }
    }
}
