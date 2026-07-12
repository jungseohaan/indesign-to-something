package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

/**
 * 인라인 텍스트 프레임 변환 (W4 Step C).
 *
 * <p>Stage 1이 text shell을 추출 PNG로 결정한 경우에는 Rectangle+drawText의
 * imageFill로 native shell을 실행할 수 있다.  금지되는 것은 drawText 자체가
 * 아니라 executor가 shell을 Java style/fallback shape로 새로 그리는 것이다.</p>
 */
final class InlineFrameBuilder {
    private static final long INLINE_TEXT_FRAME_TRAILING_GAP = CoordinateConverter.pointsToHwpunits(2.0);

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;
    private final HwpxTextBoxBuilder textBoxBuilder;

    InlineFrameBuilder(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder,
                       HwpxTextBoxBuilder textBoxBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
        this.textBoxBuilder = textBoxBuilder;
    }

    /** resolveDropCapStyle 복제 (paragraphBuilder 의존). */
    private DropCapStyle resolveDropCapStyle(java.util.List<ASTParagraph> paragraphs) {
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

    // ── 인라인 텍스트 프레임 (글상자, treatAsChar=true) ──

    /**
     * 인라인 텍스트 프레임 / 그룹 → native shell imageFill drawText 또는 1x1 inline table.
     */
    void addInlineTextFrame(Para para, ASTInlineObject obj) {
        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        boolean hasDrawableShell = obj.imageFillData() != null && obj.imageFillData().length > 0;
        if (!hasParagraphs && !hasInlineTables && !hasDrawableShell) return;

        if (shouldFlattenToParentRuns(obj)) {
            flattenToParentRuns(para, obj);
            return;
        }

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;

        // 단일 단락 안에 2+ ITF 자식 → 다단 래퍼 → 부모 폭으로 확장
        // (InDesign에서 나란히 배치되는 다단 레이아웃)
        // 복수 단락 ITF(각 행이 독립 단락인 배지 그룹의 RIGHT 컬럼)는 확장하지 않음
        if (ctx.currentContainerWidth > w && obj.paragraphs() != null && obj.paragraphs().size() == 1) {
            int innerCount = 0;
            for (ASTParagraph p : obj.paragraphs()) {
                for (ASTInlineItem it : p.items()) {
                    if (it.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                            && ((ASTInlineObject) it).kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) {
                        innerCount++;
                    }
                }
            }
            if (innerCount >= 2) {
                w = ctx.currentContainerWidth;
            }
        }

        // IDML에서 높이가 명시된 경우 그대로 사용 (최소값 강제 안 함)
        long h = obj.height() > 0 ? obj.height() : ConverterConstants.MIN_TEXT_BOX_HEIGHT;

        // IDML 속성 기반 래핑 모드 결정 (크기 기반 폴백은 이미지에만 적용, 텍스트프레임은 인라인 유지)
        String wrapMode = obj.textWrapMode();
        boolean useWrapping = InlineFlowPolicy.usesNonFlowWrapping(obj);

        TextWrapMethod twm;
        TextFlowSide tfs;
        if (useWrapping) {
            twm = HwpxImageBuilder.mapTextWrapMethod(wrapMode);
            tfs = HwpxImageBuilder.mapTextFlowSide(obj.textWrapSide());
        } else {
            // 인라인(treatAsChar=true) rect: SQUARE로 같은 줄에 텍스트 배치
            // TOP_AND_BOTTOM은 rect 뒤 텍스트를 다음 줄로 밀어냄
            twm = TextWrapMethod.SQUARE;
            tfs = TextFlowSide.BOTH_SIDES;
        }

        long savedContainerWidth = ctx.currentContainerWidth;
        ctx.currentContainerWidth = w;
        if (obj.paragraphs() != null) {
            long redistWidth = w - obj.textMarginLeft() - obj.textMarginRight();
            if (redistWidth <= 0) redistWidth = w;
            HwpxTextBoxBuilder.redistributeInlineTextFrameWidths(obj.paragraphs(), redistWidth);
        }

        if (shouldUseInlineDrawTextShell(obj)) {
            addInlineExtractedShellTextFrame(para, obj, w, h, twm, tfs, useWrapping);
            queueInlineTextShellOverlays(obj);
            ctx.currentContainerWidth = savedContainerWidth;
            return;
        }

        Run run = para.addNewRun();
        run.charPrIDRef("0");
        Table table = run.addNewTable();
        String tableId = HwpxUtil.nextShapeId();

        table.idAnd(tableId)
                .zOrderAnd(inlineObjectZOrder(obj))
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
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
        if (useWrapping) {
            table.pos().treatAsCharAnd(false)
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
            table.pos().treatAsCharAnd(true)
                    .affectLSpacingAnd(InlineFlowPolicy.participatesInLineSpacing(obj))
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(inlineShellVertAlign(obj))
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);
        }

        table.createOutMargin();
        if (useWrapping) {
            table.outMargin().leftAnd(obj.textWrapLeft()).rightAnd(obj.textWrapRight())
                    .topAnd(obj.textWrapTop()).bottomAnd(obj.textWrapBottom());
        } else {
            table.outMargin().leftAnd(0L).rightAnd(INLINE_TEXT_FRAME_TRAILING_GAP).topAnd(0L).bottomAnd(0L);
        }
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();
        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(createInlineTextFrameBorderFill(obj));
        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(h);
        tc.createCellMargin();
        tc.cellMargin().leftAnd(obj.textMarginLeft())
                .rightAnd(obj.textMarginRight())
                .topAnd(obj.textMarginTop())
                .bottomAnd(obj.textMarginBottom());

        tc.createSubList();
        SubList subList = tc.subList();
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(HwpxTextBoxBuilder.inlineTextFrameLineWrap(obj))
                .vertAlignAnd(HwpxEnumMapper.mapVerticalJustification(obj.verticalJustification()))
                .linkListIDRefAnd("0").linkListNextIDRefAnd("0");
        if (obj.paragraphs() != null) {
            for (ASTParagraph paragraph : obj.paragraphs()) {
                paragraphBuilder.addParagraphToSubList(subList, paragraph);
            }
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }
        ctx.currentContainerWidth = savedContainerWidth;
    }

    private void queueInlineTextShellOverlays(ASTInlineObject obj) {
        if (obj == null || obj.overlayFrames() == null || obj.overlayFrames().isEmpty()) return;
        for (ASTInlineObject overlay : obj.overlayFrames()) {
            if (overlay == null || overlay.paragraphs() == null || overlay.paragraphs().isEmpty()) continue;
            HwpxConverterContext.DeferredOverlay d = new HwpxConverterContext.DeferredOverlay();
            d.overlay = overlay;
            if (ctx.insideTableCell) {
                d.pageX = ctx.blockPageX + ctx.blockInsetLeft + overlay.overlayX();
                d.pageY = ctx.blockPageY + ctx.blockInsetTop + ctx.cellContentYCursor + overlay.overlayY();
            } else if (obj.resolvedPageX() >= 0 && obj.resolvedPageY() >= 0) {
                // Inline text-shell child text must stay in the parent shell's
                // local coordinate channel. Using the child frame's own
                // resolved page box causes drift/duplication when the child
                // source frame spans more than the actual shell slot.
                d.pageX = obj.resolvedPageX() + overlay.overlayX();
                d.pageY = obj.resolvedPageY() + overlay.overlayY();
            } else if (overlay.resolvedPageX() >= 0 && overlay.resolvedPageY() >= 0) {
                d.pageX = overlay.resolvedPageX();
                d.pageY = overlay.resolvedPageY();
            } else {
                d.pageX = ctx.blockPageX + ctx.blockInsetLeft + overlay.overlayX();
                d.pageY = ctx.blockPageY + ctx.blockInsetTop + ctx.cellContentYCursor + overlay.overlayY();
            }
            ctx.deferredOverlays.add(d);
        }
    }

    private boolean shouldUseInlineDrawTextShell(ASTInlineObject obj) {
        if (obj != null && obj.imageFillData() != null && obj.imageFillData().length > 0) {
            return true;
        }
        return false;
    }

    private int inlineObjectZOrder(ASTInlineObject obj) {
        if (obj == null || obj.plannedZOrder() == Integer.MIN_VALUE) {
            return 0;
        }
        return obj.plannedZOrder();
    }

    private void addInlineExtractedShellTextFrame(Para para, ASTInlineObject obj, long w, long h,
                                                  TextWrapMethod twm, TextFlowSide tfs, boolean useWrapping) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Rectangle rect = run.addNewRectangle();
        rect.idAnd(HwpxUtil.nextShapeId())
                .zOrderAnd(inlineObjectZOrder(obj))
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(obj.paragraphs()));

        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        applyShapeComponentGeometry(rect, w, h);

        DrawTextBoxComposer.Spec spec = DrawTextBoxComposer.fromInlineObject(obj, w, h);
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) {
            spec.imageFillData = obj.imageFillData();
            spec.forceImageFill = true;
            spec.strokeColor = null;
            spec.strokeWeight = 0;
            spec.fillColor = null;
        }
        textBoxBuilder.drawTextBoxComposer().apply(rect, spec);
        if (rect.drawText() != null) {
            rect.drawText().editableAnd(true);
        }
        DrawTextBoxComposer.applyRectangleGeometry(
                rect,
                w,
                h,
                obj.cornerRadius(),
                h,
                obj.shellShapeType(),
                false);

        rect.createPos();
        if (useWrapping) {
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
            rect.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(InlineFlowPolicy.participatesInLineSpacing(obj))
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(inlineShellVertAlign(obj))
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);
        }
        rect.createOutMargin();
        if (useWrapping) {
            rect.outMargin().leftAnd(obj.textWrapLeft()).rightAnd(obj.textWrapRight())
                    .topAnd(obj.textWrapTop()).bottomAnd(obj.textWrapBottom());
        } else {
            rect.outMargin().leftAnd(0L).rightAnd(INLINE_TEXT_FRAME_TRAILING_GAP).topAnd(0L).bottomAnd(0L);
        }
    }

    private VertAlign inlineShellVertAlign(ASTInlineObject obj) {
        if (obj != null && obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) {
            return VertAlign.TOP;
        }
        return VertAlign.CENTER;
    }

    private void applyShapeComponentGeometry(
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapecomponent.ShapeComponent<?> shape,
            long w,
            long h) {
        shape.createOffset();
        shape.offset().set(0L, 0L);
        shape.createOrgSz();
        shape.orgSz().set(w, h);
        shape.createCurSz();
        shape.curSz().set(w, h);
        shape.createFlip();
        shape.flip().horizontalAnd(false).verticalAnd(false);
        shape.createRotationInfo();
        shape.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2)
                .centerYAnd(h / 2)
                .rotateimageAnd(true);
        shape.createRenderingInfo();
        shape.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        shape.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        shape.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        shape.createSZ();
        shape.sz().widthAnd(w)
                .widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h)
                .heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);
    }

    private String createInlineTextFrameBorderFill(ASTInlineObject obj) {
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

        String stroke = obj.strokeColor();
        boolean hasStroke = HwpxTextBoxBuilder.nativeTextBoxGraphicsEnabled()
                && stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0;
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

        if (obj.imageFillData() != null && obj.imageFillData().length > 0) {
            try {
                String itemId = ImageInserter.registerImage(ctx.hwpxFile, obj.imageFillData(), "png");
                if (itemId != null) {
                    bf.createFillBrush();
                    bf.fillBrush().createImgBrush();
                    bf.fillBrush().imgBrush().modeAnd(ImageBrushMode.TOTAL);
                    bf.fillBrush().imgBrush().createImg();
                    bf.fillBrush().imgBrush().img()
                            .binaryItemIDRefAnd(itemId)
                            .brightAnd(0)
                            .contrastAnd(0)
                            .effectAnd(ImageEffect.REAL_PIC);
                    return bfId;
                }
            } catch (Exception ignore) {
            }
        }

        String fill = obj.fillColor();
        if (HwpxTextBoxBuilder.nativeTextBoxGraphicsEnabled()
                && fill != null && fill.startsWith("#")) {
            String tinted = HwpxTextBoxBuilder.blendColorWithWhite(fill, obj.fillTint() / 100.0);
            bf.createFillBrush();
            VisualShellApplicator.applyWinBrushFill(bf.fillBrush(), tinted, "#FF000000");
        }
        return bfId;
    }

    private boolean shouldFlattenToParentRuns(ASTInlineObject obj) {
        if (obj == null || obj.isOverlay()) return false;
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return false;
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return false;
        if (obj.imageData() != null && obj.imageData().length > 0) return false;
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) return false;
        if (hasVisibleFill(obj)) return false;
        if (hasVisibleStroke(obj)) return false;

        boolean hasVisibleText = false;
        boolean onlyWhitespaceText = true;
        for (ASTParagraph paragraph : obj.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.trim().isEmpty()) {
                        hasVisibleText = true;
                        onlyWhitespaceText = false;
                    }
                } else if (item instanceof ASTBreak) {
                    onlyWhitespaceText = false;
                } else if (item instanceof ASTInlineObject) {
                    if (!shouldFlattenToParentRuns((ASTInlineObject) item)) return false;
                    hasVisibleText = true;
                    onlyWhitespaceText = false;
                } else {
                    return false;
                }
            }
        }
        if (onlyWhitespaceText && obj.width() >= 800) return false;
        return hasVisibleText;
    }

    private static boolean hasSemanticText(ASTInlineObject obj) {
        if (obj == null || obj.paragraphs() == null) return false;
        int visibleChars = 0;
        int visibleRuns = 0;
        int visibleParagraphs = 0;
        for (ASTParagraph paragraph : obj.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            boolean paraHasText = false;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.trim().isEmpty()) {
                        visibleRuns++;
                        visibleChars += text.trim().length();
                        paraHasText = true;
                    }
                } else if (item instanceof ASTInlineObject && hasSemanticText((ASTInlineObject) item)) {
                    visibleRuns++;
                    visibleChars += 20;
                    paraHasText = true;
                }
            }
            if (paraHasText) visibleParagraphs++;
        }
        return visibleParagraphs > 1 || visibleRuns > 1 || visibleChars > 20;
    }

    private static boolean hasVisibleFill(ASTInlineObject obj) {
        String fill = obj.fillColor();
        return fill != null && fill.startsWith("#");
    }

    private static boolean hasVisibleStroke(ASTInlineObject obj) {
        String stroke = obj.strokeColor();
        return stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0.5;
    }

    private void flattenToParentRuns(Para para, ASTInlineObject obj) {
        for (int pIdx = 0; pIdx < obj.paragraphs().size(); pIdx++) {
            ASTParagraph paragraph = obj.paragraphs().get(pIdx);
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    paragraphBuilder.addTextRun(para, (ASTTextRun) item, "0");
                } else if (item instanceof ASTBreak) {
                    addLineBreak(para);
                } else if (item instanceof ASTInlineObject) {
                    flattenToParentRuns(para, (ASTInlineObject) item);
                }
            }
            if (pIdx < obj.paragraphs().size() - 1) {
                addLineBreak(para);
            }
        }
    }

    private void addLineBreak(Para para) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addNewLineBreak();
    }
}
