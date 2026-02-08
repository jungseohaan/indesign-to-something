package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateImage;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * IntermediateFrame(image)을 HWPX Picture로 변환한다.
 */
public class HwpxImageWriter {

    private final AtomicLong shapeIdCounter;
    private final HWPXFile hwpxFile;
    private final Consumer<String> warningHandler;

    public HwpxImageWriter(AtomicLong shapeIdCounter, HWPXFile hwpxFile, Consumer<String> warningHandler) {
        this.shapeIdCounter = shapeIdCounter;
        this.hwpxFile = hwpxFile;
        this.warningHandler = warningHandler;
    }

    /**
     * 이미지 프레임을 HWPX Picture로 변환한다.
     * @return 성공 시 true
     */
    public boolean write(Run anchorRun, IntermediateFrame frame) {
        IntermediateImage image = frame.image();
        if (image == null) {
            return false;
        }

        if (image.base64Data() == null) {
            return false;
        }

        try {
            byte[] imageData = Base64.getDecoder().decode(image.base64Data());
            String format = image.format() != null ? image.format() : "png";

            String itemId = ImageInserter.registerImage(hwpxFile, imageData, format);

            int pixelW = image.pixelWidth() > 0 ? image.pixelWidth() : 100;
            int pixelH = image.pixelHeight() > 0 ? image.pixelHeight() : 100;
            long displayW = frame.width();
            long displayH = frame.height();

            // PAPER 기준 좌표: 음수 좌표는 bleed 영역(용지 바깥)을 의미하며 유효함
            long posX = frame.x();
            long posY = frame.y();
            long croppedW = displayW;
            long croppedH = displayH;

            Picture pic = anchorRun.addNewPicture();
            String picId = nextShapeId();

            // ShapeObject (이미지는 텍스트 뒤에 배치)
            pic.idAnd(picId).zOrderAnd(frame.zOrder())
                    .numberingTypeAnd(NumberingType.PICTURE)
                    .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                    .textFlowAnd(TextFlowSide.BOTH_SIDES)
                    .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);

            // ShapeComponent
            pic.hrefAnd("");
            pic.groupLevelAnd((short) 0);
            pic.instidAnd(picId);
            pic.reverseAnd(false);

            pic.createOffset();
            pic.offset().set(0L, 0L);

            pic.createOrgSz();
            pic.orgSz().set(displayW, displayH);

            pic.createCurSz();
            pic.curSz().set(croppedW, croppedH);

            pic.createFlip();
            pic.flip().horizontalAnd(false).verticalAnd(false);

            pic.createRotationInfo();
            pic.rotationInfo().angleAnd((short) 0)
                    .centerXAnd(croppedW / 2).centerYAnd(croppedH / 2)
                    .rotateimageAnd(true);

            pic.createRenderingInfo();
            pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

            // ShapeSize
            pic.createSZ();
            pic.sz().widthAnd(croppedW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(croppedH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                    .protectAnd(false);

            // ShapePosition — 용지(PAPER) 기준 절대 좌표
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
                    .vertOffsetAnd(posY)
                    .horzOffset(posX);

            // OutMargin
            pic.createOutMargin();
            pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

            // ImageRect (크롭 후 크기)
            pic.createImgRect();
            pic.imgRect().createPt0();
            pic.imgRect().pt0().set(0L, 0L);
            pic.imgRect().createPt1();
            pic.imgRect().pt1().set(croppedW, 0L);
            pic.imgRect().createPt2();
            pic.imgRect().pt2().set(croppedW, croppedH);
            pic.imgRect().createPt3();
            pic.imgRect().pt3().set(0L, croppedH);

            // ImageClip (pixel * 75)
            long clipW = (long) pixelW * 75;
            long clipH = (long) pixelH * 75;
            pic.createImgClip();
            pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

            // InMargin
            pic.createInMargin();
            pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

            // ImageDim (pixel * 75)
            pic.createImgDim();
            pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

            // Image reference
            pic.createImg();
            pic.img().binaryItemIDRefAnd(itemId)
                    .brightAnd(0).contrastAnd(0)
                    .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

            return true;
        } catch (Exception e) {
            if (warningHandler != null) {
                warningHandler.accept("Image insertion failed for " + frame.frameId() + ": " + e.getMessage());
            }
            return false;
        }
    }

    private String nextShapeId() {
        return String.valueOf(shapeIdCounter.incrementAndGet());
    }
}
