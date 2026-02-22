package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

/**
 * ASTFigure / ASTInlineObject(IMAGE) / ASTPageBackground → HWPX Picture로 변환한다.
 */
class HwpxImageBuilder {

    private static final long INLINE_IMAGE_HEIGHT_THRESHOLD = ConverterConstants.INLINE_IMAGE_HEIGHT_THRESHOLD;

    private final HwpxConverterContext ctx;

    HwpxImageBuilder(HwpxConverterContext ctx) {
        this.ctx = ctx;
    }

    // ── 인라인 이미지 ──

    void addInlineImage(Para para, ASTInlineObject obj) {
        byte[] imageData = obj.imageData();
        if (imageData == null || imageData.length == 0) return;

        String format = obj.imageFormat() != null ? obj.imageFormat() : "png";
        String itemId = ImageInserter.registerImage(ctx.hwpxFile, imageData, format);

        long displayW = obj.width() > 0 ? obj.width() : 1000;
        long displayH = obj.height() > 0 ? obj.height() : 1000;
        long clipW = (long) obj.pixelWidth() * 75;
        long clipH = (long) obj.pixelHeight() * 75;
        if (clipW <= 0) clipW = displayW;
        if (clipH <= 0) clipH = displayH;

        // IDML 속성 기반 래핑 모드 결정
        boolean isAnchored = "Anchored".equals(obj.anchoredPosition());
        String wrapMode = obj.textWrapMode();
        boolean idmlWrapping = isAnchored && wrapMode != null && !"None".equals(wrapMode);
        // 크기 기반 폴백: IDML 래핑 속성이 없지만 이미지가 큰 경우 자리차지로 전환
        boolean sizeFallback = !idmlWrapping && displayH > INLINE_IMAGE_HEIGHT_THRESHOLD;
        boolean useWrapping = idmlWrapping || sizeFallback;

        // TextWrapMethod / TextFlowSide 결정
        TextWrapMethod twm;
        TextFlowSide tfs;
        if (idmlWrapping) {
            twm = mapTextWrapMethod(wrapMode);
            tfs = mapTextFlowSide(obj.textWrapSide());
        } else if (sizeFallback) {
            twm = TextWrapMethod.TOP_AND_BOTTOM;
            tfs = TextFlowSide.BOTH_SIDES;
        } else {
            twm = TextWrapMethod.TOP_AND_BOTTOM;
            tfs = TextFlowSide.BOTH_SIDES;
        }

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Picture pic = run.addNewPicture();
        String picId = ASTToHwpxConverter.nextShapeId();

        // ShapeObject
        pic.idAnd(picId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None)
                .reverseAnd(false);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(ASTToHwpxConverter.nextShapeId());

        pic.createOffset();
        pic.offset().set(0L, 0L);

        pic.createOrgSz();
        pic.orgSz().set(displayW, displayH);

        pic.createCurSz();
        pic.curSz().set(displayW, displayH);

        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);

        pic.createRotationInfo();
        pic.rotationInfo().angleAnd((short) 0)
                .centerXAnd(displayW / 2).centerYAnd(displayH / 2).rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition
        pic.createPos();
        if (useWrapping) {
            // 어울리기/자리차지 — 단락 기준 플로팅
            pic.pos().treatAsCharAnd(false)
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

        // OutMargin — IDML TextWrapOffset 반영
        pic.createOutMargin();
        if (useWrapping) {
            pic.outMargin().leftAnd(obj.textWrapLeft()).rightAnd(obj.textWrapRight())
                    .topAnd(obj.textWrapTop()).bottomAnd(obj.textWrapBottom());
        } else {
            pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        }

        // ImageRect — 표시 영역 (HWPUNIT)
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(displayW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(displayW, displayH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, displayH);

        // ImageClip/Dim — pixel * 75 (96 DPI 기준 HWPUNIT)
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        // Image 참조
        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        ctx.imagesConverted++;
    }

    // ── 오버레이 텍스트를 포함하는 인라인 이미지 ──

    /**
     * IMAGE 그룹에서 추출된 오버레이 텍스트프레임을 이미지와 함께 컨테이너 글상자에 중첩하여 변환.
     * 투명 컨테이너 rect(drawText) 안에 표준 hp:pic + 오버레이 hp:rect를 배치하여
     * 오버레이가 이미지 영역 내에서 PARA 기준 상대 좌표로 정확히 위치하게 한다.
     */
    void addInlineImageWithOverlays(Para para, ASTInlineObject obj,
                                     HwpxTextBoxBuilder textBoxBuilder,
                                     HwpxParagraphBuilder paragraphBuilder) {
        byte[] imageData = obj.imageData();
        if (imageData == null || imageData.length == 0) return;

        String format = obj.imageFormat() != null ? obj.imageFormat() : "png";
        String itemId = ImageInserter.registerImage(ctx.hwpxFile, imageData, format);

        long displayW = obj.width() > 0 ? obj.width() : 1000;
        long displayH = obj.height() > 0 ? obj.height() : 1000;
        long clipW = (long) obj.pixelWidth() * 75;
        long clipH = (long) obj.pixelHeight() * 75;
        if (clipW <= 0) clipW = displayW;
        if (clipH <= 0) clipH = displayH;

        // 컨테이너 크기 = 콘텐츠 바운딩 박스 표시 크기 (이미지보다 클 수 있음)
        long containerW = obj.containerWidth() > 0 ? obj.containerWidth() : displayW;
        long containerH = obj.containerHeight() > 0 ? obj.containerHeight() : displayH;

        // 일반 대형 인라인 이미지와 동일한 래핑 모드
        TextWrapMethod twm = TextWrapMethod.TOP_AND_BOTTOM;
        TextFlowSide tfs = TextFlowSide.BOTH_SIDES;

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        // ── 1. 투명 컨테이너 rect (테두리 없음, 채우기 없음) ──
        //     크기 = 그룹 바운딩 박스 (이미지 + 오버레이 텍스트프레임 전체를 포함)

        Rectangle rect = run.addNewRectangle();
        String shapeId = ASTToHwpxConverter.nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(ASTToHwpxConverter.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(containerW, containerH);
        rect.createCurSz();
        rect.curSz().set(containerW, containerH);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(containerW / 2).centerYAnd(containerH / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape — 테두리 없음
        textBoxBuilder.setupTextBoxLineShape(rect, null, 0, "Solid", 100);

        // FillBrush — 없음 (투명 컨테이너)

        // ── 2. DrawText → SubList → 내부 단락 ──

        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(containerW).nameAnd("").editableAnd(false);
        dt.createTextMargin();
        dt.textMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        Para innerPara = subList.addNewPara();
        innerPara.idAnd(ASTToHwpxConverter.nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        // ── 3. 인라인 이미지 (hp:pic) ──
        //     이미지가 그룹 원점에서 오프셋이 있으면 부유 배치 (BEHIND_TEXT)

        addPictureToContainer(innerPara, itemId, displayW, displayH, clipW, clipH);

        // ── 4. 오버레이 텍스트 박스 (IN_FRONT_OF_TEXT, PARA 기준) ──

        for (ASTInlineObject overlay : obj.overlayFrames()) {
            textBoxBuilder.addInlineTextFrame(innerPara, overlay);
        }

        // Rectangle 꼭짓점
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(containerW, 0L);
        rect.createPt2();
        rect.pt2().set(containerW, containerH);
        rect.createPt3();
        rect.pt3().set(0L, containerH);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(containerW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(containerH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 자리 차지 (TOP_AND_BOTTOM)
        // 컨테이너를 단락 기준 플로팅으로 배치하여 후속 텍스트가 아래로 밀려남
        // (내부 hp:pic가 treatAsChar=true이므로 DrawText 내부에 실제 높이가 있어 렌더링됨)
        rect.createPos();
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

        // OutMargin
        rect.createOutMargin();
        rect.outMargin().leftAnd(obj.textWrapLeft()).rightAnd(obj.textWrapRight())
                .topAnd(obj.textWrapTop()).bottomAnd(obj.textWrapBottom());

        ctx.imagesConverted++;
    }

    /**
     * 컨테이너 글상자 내부에 이미지(hp:pic)를 추가한다.
     * 항상 treatAsChar=true (인라인)으로 배치하여 단락에 실제 높이를 부여한다.
     * (floating 이미지는 단락이 비어있으면 렌더링되지 않는 문제 방지)
     */
    private void addPictureToContainer(Para para, String itemId,
                                        long displayW, long displayH,
                                        long clipW, long clipH) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Picture pic = run.addNewPicture();
        String picId = ASTToHwpxConverter.nextShapeId();

        pic.idAnd(picId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None)
                .reverseAnd(false);

        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(ASTToHwpxConverter.nextShapeId());

        pic.createOffset();
        pic.offset().set(0L, 0L);
        pic.createOrgSz();
        pic.orgSz().set(displayW, displayH);
        pic.createCurSz();
        pic.curSz().set(displayW, displayH);
        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);
        pic.createRotationInfo();
        pic.rotationInfo().angleAnd((short) 0)
                .centerXAnd(displayW / 2).centerYAnd(displayH / 2).rotateimageAnd(true);
        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        pic.createSZ();
        pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        pic.createPos();
        // 항상 인라인 (글자처럼 취급) — 단락에 실제 높이를 부여
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

        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(displayW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(displayW, displayH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, displayH);

        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);
    }

    // ── 플로팅 Figure ──

    /**
     * 공유 앵커 단락을 사용하는 figure 변환 (페이지 넘침 방지).
     */
    void convertFigure(Para anchorPara, ASTFigure figure) {
        convertFigureImage(anchorPara, figure);
    }

    /**
     * 개별 앵커 단락을 생성하는 figure 변환 (배경 이미지 등).
     */
    void convertFigure(SectionXMLFile sectionFile, ASTFigure figure) {
        Para framePara = ASTToHwpxConverter.createFloatingObjectPara(sectionFile);
        convertFigureImage(framePara, figure);
    }

    private void convertFigureImage(Para anchorPara, ASTFigure figure) {
        byte[] imageData = figure.imageData();
        if (imageData == null || imageData.length == 0) return;
        if (figure.width() <= 0 || figure.height() <= 0) return;

        String format = figure.imageFormat() != null ? figure.imageFormat() : "png";
        String itemId = ImageInserter.registerImage(ctx.hwpxFile, imageData, format);

        long x = figure.x();
        long y = figure.y();
        long displayW = figure.width();
        long displayH = figure.height();
        long clipW = (long) figure.pixelWidth() * 75;
        long clipH = (long) figure.pixelHeight() * 75;
        if (clipW <= 0) clipW = displayW;
        if (clipH <= 0) clipH = displayH;

        // 페이지 크롭 적용 (스프레드 걸침 이미지)
        long imgClipLeft = 0, imgClipTop = 0, imgClipRight = clipW, imgClipBottom = clipH;
        if (figure.hasCrop()) {
            imgClipLeft = Math.round(clipW * figure.cropLeftFraction());
            imgClipTop = Math.round(clipH * figure.cropTopFraction());
            imgClipRight = clipW - Math.round(clipW * figure.cropRightFraction());
            imgClipBottom = clipH - Math.round(clipH * figure.cropBottomFraction());

            // 표시 크기를 보이는 영역으로 축소
            displayW = Math.round(figure.width() * (1.0 - figure.cropLeftFraction() - figure.cropRightFraction()));
            displayH = Math.round(figure.height() * (1.0 - figure.cropTopFraction() - figure.cropBottomFraction()));

            // 위치 조정: 크롭된 부분만큼 페이지 경계로 이동
            if (x < 0) x = 0;
            if (y < 0) y = 0;
        }

        Run anchorRun = anchorPara.addNewRun();
        anchorRun.charPrIDRef("0");

        Picture pic = anchorRun.addNewPicture();
        String picId = ASTToHwpxConverter.nextShapeId();

        // ShapeObject
        pic.idAnd(picId)
                .zOrderAnd(figure.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None)
                .reverseAnd(false);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(ASTToHwpxConverter.nextShapeId());

        pic.createOffset();
        pic.offset().set(0L, 0L);

        pic.createOrgSz();
        pic.orgSz().set(displayW, displayH);

        pic.createCurSz();
        pic.curSz().set(displayW, displayH);

        pic.createFlip();
        pic.flip().horizontalAnd(figure.flipHorizontal()).verticalAnd(figure.flipVertical());

        pic.createRotationInfo();
        short rotAngle = (short) Math.round(figure.rotationAngle());
        pic.rotationInfo().angleAnd(rotAngle)
                .centerXAnd(displayW / 2).centerYAnd(displayH / 2).rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        if (rotAngle != 0) {
            double radians = Math.toRadians(rotAngle);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            pic.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);
        } else {
            pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        }

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — PAPER 기준 절대 좌표
        pic.createPos();
        pic.pos().treatAsCharAnd(false)
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
        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageRect — 표시 영역 (HWPUNIT)
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(displayW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(displayW, displayH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, displayH);

        // ImageClip — 원본 이미지 내 표시 영역 (pixel * 75)
        pic.createImgClip();
        pic.imgClip().leftAnd(imgClipLeft).rightAnd(imgClipRight)
                .topAnd(imgClipTop).bottomAnd(imgClipBottom);

        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImgDim — 원본 이미지 전체 크기 (pixel * 75)
        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        // Image 참조
        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        ctx.imagesConverted++;
    }

    // ── 배경 PNG ──

    void addBackgroundImage(SectionXMLFile sectionFile, ASTPageBackground bg) {
        byte[] pngData = bg.pngData();
        if (pngData == null || pngData.length == 0) return;

        String itemId = ImageInserter.registerImage(ctx.hwpxFile, pngData, "png");

        long w = bg.pageWidth();
        long h = bg.pageHeight();
        long bgClipW, bgClipH;
        try {
            int[] sz = ImageInserter.detectPixelSize(pngData);
            bgClipW = (long) sz[0] * 75;
            bgClipH = (long) sz[1] * 75;
        } catch (Exception e) {
            bgClipW = w;
            bgClipH = h;
        }

        Para framePara = ASTToHwpxConverter.createFloatingObjectPara(sectionFile);
        Run anchorRun = framePara.runs().iterator().next();

        Picture pic = anchorRun.addNewPicture();
        String picId = ASTToHwpxConverter.nextShapeId();

        // ShapeObject — 배경: z-order=0, BEHIND_TEXT
        pic.idAnd(picId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None)
                .reverseAnd(false);

        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(ASTToHwpxConverter.nextShapeId());

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

        // ShapePosition — (0,0) from PAPER
        pic.createPos();
        pic.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(w, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(w, h);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, h);

        // ImageClip/Dim — pixel * 75 (96 DPI 기준 HWPUNIT)
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(bgClipW).topAnd(0L).bottomAnd(bgClipH);

        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(bgClipW).dimheightAnd(bgClipH);

        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);
    }

    // ── TextWrap 매핑 유틸리티 (delegate to HwpxEnumMapper) ──

    static TextWrapMethod mapTextWrapMethod(String idmlMode) {
        return HwpxEnumMapper.mapTextWrapMethod(idmlMode);
    }

    static TextFlowSide mapTextFlowSide(String idmlSide) {
        return HwpxEnumMapper.mapTextFlowSide(idmlSide);
    }
}
