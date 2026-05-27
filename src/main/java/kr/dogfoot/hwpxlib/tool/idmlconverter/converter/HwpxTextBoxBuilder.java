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
    private final PageOverlayBuilder overlayBuilder;
    private final InlineFrameBuilder inlineFrameBuilder;
    private final SingleColumnTableConverter singleColumnTableConverter;
    private final FrameTransformations frameTransformations;

    public HwpxTextBoxBuilder(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
        this.overlayBuilder = new PageOverlayBuilder(ctx, paragraphBuilder);
        this.inlineFrameBuilder = new InlineFrameBuilder(ctx, paragraphBuilder, this);
        this.singleColumnTableConverter = new SingleColumnTableConverter(ctx, paragraphBuilder, this);
        this.frameTransformations = new FrameTransformations(ctx, paragraphBuilder, this);
    }

    /** addPageLevelOverlay delegate — 외부 호출자(ASTToHwpxConverter, FlatToHwpxConverter) 호환 유지. */
    public void addPageLevelOverlay(Para anchorPara, ASTInlineObject obj, long pageX, long pageY) {
        overlayBuilder.addPageLevelOverlay(anchorPara, obj, pageX, pageY);
    }

    /** addInlineTextFrame delegate — 외부 호출자(HwpxParagraphBuilder) 호환 유지. */
    void addInlineTextFrame(Para para, ASTInlineObject obj) {
        inlineFrameBuilder.addInlineTextFrame(para, obj);
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
        // 텍스트 글상자는 회전 미적용 (HWPX 회전이 위치 오프셋 유발, 텍스트는 항상 정방향)
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(textBoxMinH / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        // rotationInfo가 회전 처리 → rotMatrix는 항등 행렬
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

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

        // JustifyAlign 시뮬레이션: 문단 간 간격을 균등 분배
        // HWPX에는 수직 균등 배분이 없으므로, 문단 사이 spaceAfter로 대체
        if ("JustifyAlign".equalsIgnoreCase(block.verticalJustification())
                && block.paragraphs().size() >= 2) {
            TextBoxLayoutHelpers.distributeVerticalSpace(block);
        }

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
        if (w <= 0 || h <= 0) {
            return;
        }
        // Phase 4가 ASTTable로 처리한 table-story TF — paragraphs 없음, 래퍼 1×1 생성 불필요
        if (block.paragraphs() == null || block.paragraphs().isEmpty()) {
            return;
        }


        // inlineToFloating: 배지 단일-child — fill 없으면 hp:tbl 흰 배경 방지를 위해 투명 DrawText 경로 사용
        if (block.inlineToFloating() && block.fillColor() == null && !block.isBackgroundOnly()) {
            frameTransformations.convertRoundedFloatingBlock(framePara, block, w, h);
            return;
        }

        // 회전이 있는 블록은 Table 대신 Rectangle(DrawTextBox)로 변환
        // 단, 180도 배수 회전(0, 180, -180, 360...)은 텍스트 방향에 영향이 없으므로 일반 경로로 처리.
        // InDesign에서 부모 Group과 함께 180도 회전된 TF는 시각적으로 정방향으로 보이며
        // geometricBounds가 이미 실제 페이지 좌표로 주어지므로 회전 없이 배치해야 함.
        short rotAngle = (short) Math.round(block.rotationAngle() % 180);
        if (rotAngle != 0) {
            frameTransformations.convertRotatedFloatingBlock(framePara, block, w, h, rotAngle);
            return;
        }

        // 라운드 코너 + 유효 fill/stroke가 있는 단일 컬럼 블록은 Rectangle(DrawTextBox)로 변환
        int colCount = Math.max(block.columnCount(), 1);
        if (colCount <= 1 && block.cornerRadius() > 0) {
            boolean hasFill = block.fillColor() != null && block.fillColor().startsWith("#");
            boolean hasStroke = block.strokeColor() != null && block.strokeColor().startsWith("#")
                    && block.strokeWeight() > 0;
            if (hasFill || hasStroke) {
                frameTransformations.convertRoundedFloatingBlock(framePara, block, w, h);
                return;
            }
        }

        if (colCount <= 1) {
            // 래퍼 fill 또는 프레임 자체 fill이 있으면 배경 사각형 추가
            boolean hasOwnVisibleFill = block.fillColor() != null
                    && block.fillColor().startsWith("#") && !block.fillColor().equals("#FFFFFF");
            boolean hasWrapper = block.hasWrapperFill() || hasOwnVisibleFill;
            // 둥근 모서리 래퍼: Table 대신 Rectangle+DrawText 단일 객체 사용
            // (Table은 사각형이라 래퍼 Rectangle의 둥근 모서리를 덮어버림)
            if (hasWrapper && block.cornerRadius() > 0) {
                convertRoundedWrapperDrawText(framePara, block, w, h);
            } else {
                // 단락 하나 + inlineTable만 있는 경우: 1x1 외곽 래퍼 없이 표를 직접 배치
                // (SingleColumnTableConverter가 만드는 외곽 1x1 표가 불필요한 표-안-표 구조 생성)
                java.util.List<ASTParagraph> _paras = block.paragraphs();
                ASTTable _singleInnerTable = null;
                if (!hasWrapper && ctx.tableBuilderRef != null
                        && _paras != null && _paras.size() == 1
                        && (_paras.get(0).items() == null || _paras.get(0).items().isEmpty())
                        && _paras.get(0).inlineTable() != null) {
                    _singleInnerTable = _paras.get(0).inlineTable();
                }
                if (_singleInnerTable != null) {
                    _singleInnerTable.x(block.effectiveX());
                    _singleInnerTable.y(block.y());
                    _singleInnerTable.width(w);
                    _singleInnerTable.zOrder(block.zOrder());
                    ctx.tableBuilderRef.convertTable(framePara, _singleInnerTable);
                } else {
                    if (hasWrapper) {
                        addWrapperRoundedRect(framePara, block, w, h);
                    }
                    singleColumnTableConverter.convertSingleColumnTable(framePara, block, block.effectiveX(), block.y(), w, h,
                            block.paragraphs(), hasWrapper);
                }
            }
        } else {
            // 다단 — N개 독립 글상자(1×1 테이블)를 수평 배치
            long[] colWidths = TextBoxLayoutHelpers.computeColumnWidths(block, colCount);
            java.util.List<java.util.List<ASTParagraph>> distributed =
                    TextBoxLayoutHelpers.distributeParagraphs(block.paragraphs(), colCount);

            // 다단 프레임에 테두리/라운드코너가 있으면 전체를 감싸는 배경 사각형 추가
            boolean hasFrameBorder = block.strokeColor() != null
                    && block.strokeColor().startsWith("#")
                    && block.strokeWeight() > 0;
            boolean hasFrameStyle = hasFrameBorder || block.cornerRadius() > 0;
            if (hasFrameStyle) {
                addMultiColumnFrameBorder(framePara, block, w, h);
            }

            long xCursor = block.effectiveX();
            long gutter = block.columnGutter();
            for (int c = 0; c < colCount; c++) {
                singleColumnTableConverter.convertSingleColumnTable(framePara, block, xCursor, block.y(),
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

        // 스트로크 (프레임 자체 stroke, tint 반영)
        setupTextBoxLineShape(rect, strokeColor, strokeWeightPt, "Solid", block.strokeTint());

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
     * 둥근 모서리 래퍼가 있는 텍스트 프레임 → Rectangle(DrawTextBox)로 변환.
     * Table은 사각형이라 래퍼 Rectangle의 둥근 모서리를 덮어버리므로,
     * 단일 Rectangle에 fill + 둥근 모서리 + DrawText를 합쳐서 출력한다.
     */
    private void convertRoundedWrapperDrawText(Para framePara, ASTTextFrameBlock block,
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

        // FillBrush — 래퍼 fill 사용
        String bgColor = block.wrapperFillColor();
        double bgTint = block.wrapperFillTint();
        if (bgTint < 0) bgTint = 100;
        String tinted = blendColorWithWhite(bgColor, bgTint / 100.0);
        setupTextBoxFillBrush(rect, tinted, 100);

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

        // 둥근 모서리
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
     * 연속 단락에 각각 1개의 인라인 TextFrame이 포함된 패턴을 감지하여
     * 각 프레임의 폭을 컨테이너 폭 기준으로 균등 분배한다.
     * (InDesign에서 인라인 TextFrame을 나란히 배치하여 다단처럼 보이는 레이아웃)
     */
    static void redistributeInlineTextFrameWidths(
            java.util.List<ASTParagraph> paragraphs, long containerWidth) {
        if (containerWidth <= 0 || paragraphs == null) return;

        long halfWidth = containerWidth / 2;

        // 모든 단락에 걸쳐 좁은 인라인 TextFrame을 수집
        // (InDesign에서 각 인라인 TF가 별도 단락에 배치되는 경우가 많음)
        // 단, 작은 정사각형 박스 (자모 배지 등 ≤4000 hwpu) 는 다단 layout 이 아니라 데코 박스 → 제외
        java.util.List<ASTInlineObject> narrowFrames = new java.util.ArrayList<>();
        for (ASTParagraph para : paragraphs) {
            for (ASTInlineItem item : para.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                    ASTInlineObject obj = (ASTInlineObject) item;
                    if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME
                            && obj.width() < halfWidth
                            && obj.height() >= ConverterConstants.MIN_TEXT_BOX_HEIGHT
                            // 데코 박스 (예: 자모 ㅍㅎㅂㅅ 배지) 는 작고 정사각형에 가까움 → 다단 후보 아님
                            && !(obj.width() < 4000 && obj.height() < 4000)) {
                        narrowFrames.add(obj);
                    }
                }
            }
        }
        if (narrowFrames.size() < 2) return;

        // 한 행에 배치할 프레임 수 추정
        long totalOrigWidth = 0;
        for (ASTInlineObject f : narrowFrames) {
            totalOrigWidth += f.width();
        }
        int framesPerRow;
        if (totalOrigWidth <= containerWidth) {
            framesPerRow = narrowFrames.size();
        } else {
            long avgWidth = totalOrigWidth / narrowFrames.size();
            framesPerRow = Math.max(2, (int) (containerWidth / avgWidth));
        }

        long equalWidth = containerWidth * 95 / 100 / framesPerRow;
        for (ASTInlineObject obj : narrowFrames) {
            obj.width(equalWidth);
        }
        // 좌우 마진 제거 (줄바꿈 방지)
        for (ASTParagraph para : paragraphs) {
            para.leftMargin(0L);
            para.rightMargin(0L);
        }
    }


    /**
     * SubList에 연결 글상자 링크를 설정한다.
     * storyId가 2개 이상의 블록을 공유하면, 사전 할당된 linkId로 체인을 구성.
     */
    void applySubListLink(SubList subList, String storyId) {
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


    // ── 페이지 레벨 오버레이 (셀 내부에서 승격된 플로팅 텍스트박스) ──

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
    static short computeCornerRatio(double cornerRadiusPts, long widthHwp, long heightHwp) {
        if (cornerRadiusPts <= 0) return 0;
        long minSide = Math.min(widthHwp, heightHwp);
        long maxSide = Math.max(widthHwp, heightHwp);
        if (minSide <= 0) return 0;
        long cornerHwp = Math.round(cornerRadiusPts * 100); // 1pt = 100 HWPUNIT

        // stadium (완전 라운드 양 끝): cornerRadius >= minSide/2 → ratio=50 고정
        // (장축 기준 보정으로 변경하면 stadium 의도가 깨짐 — 예: page 23 cut/cutter 단어 박스)
        if (cornerHwp >= minSide / 2) return 50;

        short ratio = (short) Math.min(50, Math.round(cornerHwp * 100.0 / minSide));

        // HWPX에서 ratio≈50이면 타원이 되므로, 비정사각형 도형에서 과도한 둥글기를 방지.
        // ratio > 25이고 세로/가로 비율이 1.5:1 이상이면 장축 기준으로 전환.
        if (ratio > 25 && maxSide > minSide * 3 / 2) {
            ratio = (short) Math.max(1, Math.round(cornerHwp * 100.0 / maxSide));
        }
        return ratio > 0 ? ratio : 1;
    }

    /**
     * 색상 hex를 fraction 비율로 흰색과 블렌딩.
     * fraction=1.0 → 원래 색상, fraction=0.0 → 흰색.
     */
    static String blendColorWithWhite(String hex, double fraction) {
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
