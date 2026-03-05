package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

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
        boolean useWrapping = idmlWrapping;

        // TextWrapMethod / TextFlowSide 결정
        TextWrapMethod twm;
        TextFlowSide tfs;
        if (idmlWrapping) {
            twm = mapTextWrapMethod(wrapMode);
            tfs = mapTextFlowSide(obj.textWrapSide());
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
     * IMAGE 그룹에서 추출된 오버레이 텍스트프레임을 이미지와 함께 변환.
     *
     * resolved.json 좌표가 있는 경우 (ResolvedOverlayEnricher 보강 완료):
     *   - 이미지: 자체 크기로 인라인 배치 (컨테이너 확장 없음)
     *   - 오버레이: resolved 페이지 절대 좌표로 직접 deferred
     *
     * resolved 좌표가 없는 경우 (폴백):
     *   - 이미지: 컨테이너 크기 + imgRect 오프셋
     *   - 오버레이: IDML transform 기반 상대 좌표 → blockPageX 기반 승격
     */
    void addInlineImageWithOverlays(Para para, ASTInlineObject obj,
                                     HwpxTextBoxBuilder textBoxBuilder,
                                     HwpxParagraphBuilder paragraphBuilder) {
        byte[] imageData = obj.imageData();
        if (imageData == null || imageData.length == 0) return;

        String format = obj.imageFormat() != null ? obj.imageFormat() : "png";
        String itemId = ImageInserter.registerImage(ctx.hwpxFile, imageData, format);

        long imgW = obj.width() > 0 ? obj.width() : 1000;
        long imgH = obj.height() > 0 ? obj.height() : 1000;
        long clipW = (long) obj.pixelWidth() * 75;
        long clipH = (long) obj.pixelHeight() * 75;
        if (clipW <= 0) clipW = imgW;
        if (clipH <= 0) clipH = imgH;

        boolean useResolved = hasResolvedOverlays(obj);

        if (useResolved) {
            // ── resolved 경로: 이미지 자체 크기, 오버레이는 절대 좌표 + 센터링 델타 ──
            addInlinePicture(para, itemId, imgW, imgH,
                    imgW, imgH, 0, 0, clipW, clipH);

            for (ASTInlineObject overlay : obj.overlayFrames()) {
                if (overlay.resolvedPageX() < 0 || overlay.resolvedPageY() < 0) continue;
                if (overlay.resolvedWidth() > 0) overlay.width(overlay.resolvedWidth());
                if (overlay.resolvedHeight() > 0) overlay.height(overlay.resolvedHeight());

                HwpxConverterContext.DeferredOverlay d = new HwpxConverterContext.DeferredOverlay();
                d.overlay = overlay;
                // resolved 절대 좌표를 직접 사용 (InDesign DOM의 정확한 위치)
                d.pageX = overlay.resolvedPageX();
                d.pageY = overlay.resolvedPageY();
                ctx.deferredOverlays.add(d);
            }
        } else {
            // ── 폴백: 컨테이너 + IDML 상대 좌표 ──
            long frameW = imgW;
            long frameH = imgH;
            long imgOffX = 0;
            long imgOffY = 0;
            if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()
                    && obj.containerWidth() > 0 && obj.containerHeight() > 0) {
                frameW = obj.containerWidth();
                frameH = obj.containerHeight();
                imgOffX = obj.imageOffsetX();
                imgOffY = obj.imageOffsetY();
            }

            addInlinePicture(para, itemId, frameW, frameH,
                    imgW, imgH, imgOffX, imgOffY, clipW, clipH);

            for (ASTInlineObject overlay : obj.overlayFrames()) {
                HwpxConverterContext.DeferredOverlay d = new HwpxConverterContext.DeferredOverlay();
                d.overlay = overlay;
                d.pageX = ctx.blockPageX + ctx.blockInsetLeft + overlay.overlayX();
                d.pageY = ctx.blockPageY + ctx.blockInsetTop + ctx.cellContentYCursor + overlay.overlayY();
                ctx.deferredOverlays.add(d);
            }
        }

        ctx.imagesConverted++;
    }

    private boolean hasResolvedOverlays(ASTInlineObject obj) {
        if (obj.overlayFrames() == null || obj.overlayFrames().isEmpty()) return false;
        for (ASTInlineObject ov : obj.overlayFrames()) {
            if (ov.resolvedPageX() >= 0 && ov.resolvedPageY() >= 0) return true;
        }
        return false;
    }

    /**
     * 이미지(hp:pic)를 단락에 인라인으로 추가한다.
     * treatAsChar=true로 배치하여 단락 높이가 프레임 높이와 일치한다.
     *
     * @param frameW   프레임(picture) 전체 폭 (HWPUNIT) — 컨테이너 크기 또는 이미지 크기
     * @param frameH   프레임 전체 높이
     * @param imgW     이미지 표시 폭
     * @param imgH     이미지 표시 높이
     * @param imgOffX  프레임 내 이미지 X 오프셋 (컨테이너 모드에서 사용)
     * @param imgOffY  프레임 내 이미지 Y 오프셋
     * @param clipW    소스 이미지 클립 폭
     * @param clipH    소스 이미지 클립 높이
     */
    private void addInlinePicture(Para para, String itemId,
                                   long frameW, long frameH,
                                   long imgW, long imgH,
                                   long imgOffX, long imgOffY,
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
        pic.orgSz().set(frameW, frameH);
        pic.createCurSz();
        pic.curSz().set(frameW, frameH);
        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);
        pic.createRotationInfo();
        pic.rotationInfo().angleAnd((short) 0)
                .centerXAnd(frameW / 2).centerYAnd(frameH / 2).rotateimageAnd(true);
        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        pic.createSZ();
        pic.sz().widthAnd(frameW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(frameH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // 자리차지 (TOP_AND_BOTTOM) — 후속 텍스트가 이미지 아래로 밀림
        // treatAsChar=false + flowWithText=true → 단락 내 플로팅, 텍스트 위/아래로만 배치
        pic.createPos();
        pic.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // imgRect: 이미지를 프레임 내 오프셋 위치에 배치
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(imgOffX, imgOffY);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(imgOffX + imgW, imgOffY);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(imgOffX + imgW, imgOffY + imgH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(imgOffX, imgOffY + imgH);

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

        long x = figure.x();
        long y = figure.y();
        long displayW = figure.width();
        long displayH = figure.height();
        int pixelW = figure.pixelWidth();
        int pixelH = figure.pixelHeight();

        // 페이지 크롭 적용 (스프레드 걸침 이미지) — 픽셀 레벨 크롭
        if (figure.hasCrop()) {
            // 표시 크기를 보이는 영역으로 축소
            displayW = Math.round(figure.width() * (1.0 - figure.cropLeftFraction() - figure.cropRightFraction()));
            displayH = Math.round(figure.height() * (1.0 - figure.cropTopFraction() - figure.cropBottomFraction()));

            // 위치 조정
            if (x < 0) x = 0;
            if (y < 0) y = 0;

            // 이미지 데이터를 직접 픽셀 크롭 (imgClip 대신)
            imageData = pixelCrop(imageData, pixelW, pixelH,
                    figure.cropLeftFraction(), figure.cropTopFraction(),
                    figure.cropRightFraction(), figure.cropBottomFraction());
            if (imageData == null || imageData.length == 0) return;

            // 크롭된 이미지의 픽셀 크기 재계산
            int cropX = (int) Math.round(pixelW * figure.cropLeftFraction());
            int cropY = (int) Math.round(pixelH * figure.cropTopFraction());
            int cropR = (int) Math.round(pixelW * figure.cropRightFraction());
            int cropB = (int) Math.round(pixelH * figure.cropBottomFraction());
            pixelW = Math.max(1, pixelW - cropX - cropR);
            pixelH = Math.max(1, pixelH - cropY - cropB);
            format = "png";
        }

        long clipW = (long) pixelW * 75;
        long clipH = (long) pixelH * 75;
        if (clipW <= 0) clipW = displayW;
        if (clipH <= 0) clipH = displayH;

        String itemId = ImageInserter.registerImage(ctx.hwpxFile, imageData, format);

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

        // ImageClip — 픽셀 크롭 완료이므로 전체 영역
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(clipW)
                .topAnd(0L).bottomAnd(clipH);

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

    // ── 비사각형 배경 블록 → PNG 래스터화 ──

    /**
     * 비사각형 폴리곤 배경 블록을 PNG로 래스터화하여 BEHIND_TEXT 이미지로 배치한다.
     * pathPointsX/Y는 페이지 상대 HWPUNIT 좌표. 블록의 x,y,w,h가 뷰포트(클리핑 영역).
     * 캔버스가 클리핑된 영역이므로, 폴리곤이 자연스럽게 페이지 경계에서 잘린다.
     */
    void convertNonRectBackground(Para anchorPara, ASTTextFrameBlock block) {
        long[] px = block.pathPointsX();
        long[] py = block.pathPointsY();
        if (px == null || py == null || px.length < 3) return;

        String fillColor = block.fillColor();
        if (fillColor == null || !fillColor.startsWith("#")) return;

        // 래스터 크기 결정: HWPUNIT → 1pt=100hu, 72dpi 기준 1px=1pt
        int pixW = (int) Math.max(1, block.width() / 100);
        int pixH = (int) Math.max(1, block.height() / 100);
        if (pixW > 2000) pixW = 2000;
        if (pixH > 2000) pixH = 2000;

        // 색상 파싱 + tint 적용 (흰색 블렌딩으로 색상 농도 조절, 불투명 유지)
        int rgb = Integer.parseInt(fillColor.substring(1, 7), 16);
        double fraction = block.fillTint() / 100.0;
        int r = (int) Math.round(255 + (((rgb >> 16) & 0xFF) - 255) * fraction);
        int g = (int) Math.round(255 + (((rgb >> 8) & 0xFF) - 255) * fraction);
        int b = (int) Math.round(255 + ((rgb & 0xFF) - 255) * fraction);
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        // 폴리곤 래스터화 — 페이지 상대 HWPUNIT 좌표를 캔버스 픽셀로 매핑
        // 캔버스는 block의 클리핑된 영역 [x, y, w, h]에 대응
        double scaleX = (double) pixW / block.width();
        double scaleY = (double) pixH / block.height();

        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                pixW, pixH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        path.moveTo((px[0] - block.x()) * scaleX, (py[0] - block.y()) * scaleY);
        for (int i = 1; i < px.length; i++) {
            path.lineTo((px[i] - block.x()) * scaleX, (py[i] - block.y()) * scaleY);
        }
        path.closePath();

        g2.setColor(new java.awt.Color(r, g, b));
        g2.fill(path);
        g2.dispose();

        // PNG 인코딩
        byte[] pngData;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            pngData = baos.toByteArray();
        } catch (java.io.IOException e) {
            System.err.println("[NonRectBG] PNG encoding failed: " + e.getMessage());
            return;
        }

        // HWPX Picture 배치
        String itemId = ImageInserter.registerImage(ctx.hwpxFile, pngData, "png");

        long x = block.x();
        long y = block.y();
        long displayW = block.width();
        long displayH = block.height();
        long clipW = (long) pixW * 75;
        long clipH = (long) pixH * 75;

        Run anchorRun = anchorPara.addNewRun();
        anchorRun.charPrIDRef("0");

        Picture pic = anchorRun.addNewPicture();
        String picId = ASTToHwpxConverter.nextShapeId();

        pic.idAnd(picId)
                .zOrderAnd(block.zOrder())
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
        pic.imgClip().leftAnd(0L).rightAnd(clipW)
                .topAnd(0L).bottomAnd(clipH);

        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        ctx.imagesConverted++;
    }

    // ── 이미지 픽셀 크롭 ──

    /**
     * 이미지를 crop fraction에 따라 픽셀 레벨로 자른다.
     * imgClip에 의존하지 않고 이미지 데이터 자체를 잘라서 한글 호환성을 보장한다.
     */
    private static byte[] pixelCrop(byte[] imageData, int pixelW, int pixelH,
                                     double cropLeft, double cropTop,
                                     double cropRight, double cropBottom) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
            if (img == null) return imageData;

            int imgW = img.getWidth();
            int imgH = img.getHeight();

            int cx = Math.max(0, (int) Math.round(imgW * cropLeft));
            int cy = Math.max(0, (int) Math.round(imgH * cropTop));
            int cr = Math.max(0, (int) Math.round(imgW * cropRight));
            int cb = Math.max(0, (int) Math.round(imgH * cropBottom));

            int cw = Math.max(1, imgW - cx - cr);
            int ch = Math.max(1, imgH - cy - cb);

            // 범위 보정
            if (cx + cw > imgW) cw = imgW - cx;
            if (cy + ch > imgH) ch = imgH - cy;
            if (cw <= 0 || ch <= 0) return imageData;

            BufferedImage cropped = img.getSubimage(cx, cy, cw, ch);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[HwpxImageBuilder] pixelCrop failed: " + e.getMessage());
            return imageData;
        }
    }

    // ── TextWrap 매핑 유틸리티 (delegate to HwpxEnumMapper) ──

    static TextWrapMethod mapTextWrapMethod(String idmlMode) {
        return HwpxEnumMapper.mapTextWrapMethod(idmlMode);
    }

    static TextFlowSide mapTextFlowSide(String idmlSide) {
        return HwpxEnumMapper.mapTextFlowSide(idmlSide);
    }
}
