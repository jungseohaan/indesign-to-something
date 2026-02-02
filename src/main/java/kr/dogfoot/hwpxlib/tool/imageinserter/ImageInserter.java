package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageEffect;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HWPX 문서에 이미지를 삽입하는 유틸리티 클래스.
 *
 * <p>이미지 삽입은 2단계로 이루어진다:</p>
 * <ol>
 *   <li><b>등록</b>: 이미지 바이너리를 ManifestItem에 등록 ({@link #registerImage}, {@link #registerImageFromFile})</li>
 *   <li><b>삽입</b>: 등록된 이미지를 참조하는 Picture 객체를 Run에 추가 ({@link #insertInline})</li>
 * </ol>
 *
 * <p>편의 메서드로 등록과 삽입을 한번에 수행할 수도 있다:</p>
 * <ul>
 *   <li>{@link #insertInlineFromBytes} - byte[] 데이터에서 바로 삽입</li>
 *   <li>{@link #insertInlineFromFile} - 파일에서 바로 삽입</li>
 * </ul>
 *
 * <p>같은 이미지를 여러 곳에서 재사용하려면 등록과 삽입을 분리하여 사용한다:</p>
 * <pre>{@code
 * String itemId = ImageInserter.registerImage(hwpxFile, pngBytes, "png");
 * ImageInserter.insertInline(run1, itemId, 200, 150, 8000L, 6000L);
 * ImageInserter.insertInline(run2, itemId, 200, 150, 4000L, 3000L);
 * }</pre>
 */
public class ImageInserter {

    private static final AtomicLong ID_COUNTER = new AtomicLong(System.currentTimeMillis() % 1000000);

    // ── 이미지 등록 ──

    /**
     * byte[] 이미지 데이터를 ManifestItem에 등록한다.
     *
     * @param hwpxFile  대상 HWPX 파일
     * @param imageData 이미지 바이너리 데이터
     * @param format    이미지 포맷 ("png", "jpeg", "jpg", "gif", "bmp")
     * @return 등록된 itemId (insertInline에서 사용)
     */
    public static String registerImage(HWPXFile hwpxFile, byte[] imageData, String format) {
        String itemId = nextImageId(hwpxFile);
        String ext = toExtension(format);
        String mediaType = toMediaType(format);

        ObjectList<ManifestItem> manifest = hwpxFile.contentHPFFile().manifest();
        ManifestItem item = manifest.addNew();
        item.idAnd(itemId)
                .hrefAnd("BinData/" + itemId + "." + ext)
                .mediaTypeAnd(mediaType)
                .embeddedAnd(true);
        item.createAttachedFile();
        item.attachedFile().data(imageData);

        return itemId;
    }

    /**
     * 파일 시스템의 이미지 파일을 ManifestItem에 등록한다.
     * 파일 확장자로부터 포맷을 자동 감지한다.
     *
     * @param hwpxFile  대상 HWPX 파일
     * @param imageFile 이미지 파일
     * @return 등록된 itemId (insertInline에서 사용)
     * @throws IOException 파일 읽기 실패 시
     */
    public static String registerImageFromFile(HWPXFile hwpxFile, File imageFile) throws IOException {
        byte[] data = Files.readAllBytes(imageFile.toPath());
        String format = detectFormat(imageFile);
        return registerImage(hwpxFile, data, format);
    }

    // ── 인라인 Picture 삽입 ──

    /**
     * 등록된 이미지를 참조하는 인라인 Picture를 Run에 추가한다.
     * Picture는 treatAsChar=true로 텍스트 흐름에 맞춰 배치된다.
     *
     * @param run           Picture를 추가할 Run
     * @param itemId        registerImage/registerImageFromFile이 반환한 ID
     * @param pixelWidth    원본 이미지 너비 (픽셀)
     * @param pixelHeight   원본 이미지 높이 (픽셀)
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     */
    public static Picture insertInline(Run run, String itemId,
                                       int pixelWidth, int pixelHeight,
                                       long displayWidth, long displayHeight) {
        return buildInlinePicture(run, itemId, pixelWidth, pixelHeight, displayWidth, displayHeight);
    }

    /**
     * byte[] 이미지 데이터를 등록하고 인라인 Picture를 Run에 추가한다.
     * 이미지 픽셀 크기는 자동 감지된다.
     *
     * @param hwpxFile      대상 HWPX 파일
     * @param run           Picture를 추가할 Run
     * @param imageData     이미지 바이너리 데이터
     * @param format        이미지 포맷 ("png", "jpeg", "jpg", "gif", "bmp")
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     * @throws IOException 이미지 데이터 읽기 실패 시
     */
    public static Picture insertInlineFromBytes(HWPXFile hwpxFile, Run run,
                                                byte[] imageData, String format,
                                                long displayWidth, long displayHeight) throws IOException {
        String itemId = registerImage(hwpxFile, imageData, format);
        int[] size = detectPixelSize(imageData);
        return buildInlinePicture(run, itemId, size[0], size[1], displayWidth, displayHeight);
    }

    /**
     * 파일 시스템의 이미지를 등록하고 인라인 Picture를 Run에 추가한다.
     * 이미지 포맷과 픽셀 크기는 자동 감지된다.
     *
     * @param hwpxFile      대상 HWPX 파일
     * @param run           Picture를 추가할 Run
     * @param imageFile     이미지 파일
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     * @throws IOException 파일 읽기 실패 시
     */
    public static Picture insertInlineFromFile(HWPXFile hwpxFile, Run run,
                                               File imageFile,
                                               long displayWidth, long displayHeight) throws IOException {
        byte[] data = Files.readAllBytes(imageFile.toPath());
        String format = detectFormat(imageFile);
        String itemId = registerImage(hwpxFile, data, format);
        int[] size = detectPixelSize(data);
        return buildInlinePicture(run, itemId, size[0], size[1], displayWidth, displayHeight);
    }

    // ── 내부 헬퍼 ──

    private static String nextImageId(HWPXFile hwpxFile) {
        int imageCount = 0;
        ObjectList<ManifestItem> manifest = hwpxFile.contentHPFFile().manifest();
        for (int i = 0; i < manifest.count(); i++) {
            ManifestItem item = manifest.get(i);
            if (item.mediaType() != null && item.mediaType().startsWith("image/")) {
                imageCount++;
            }
        }
        return "image" + (imageCount + 1);
    }

    private static String toMediaType(String format) {
        String f = format.toLowerCase();
        switch (f) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "svg":
                return "image/svg+xml";
            case "tiff":
            case "tif":
                return "image/tiff";
            default:
                return "image/" + f;
        }
    }

    private static String toExtension(String format) {
        String f = format.toLowerCase();
        if ("jpeg".equals(f)) {
            return "jpg";
        }
        return f;
    }

    private static String detectFormat(File imageFile) {
        String name = imageFile.getName().toLowerCase();
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < name.length() - 1) {
            return name.substring(dotIdx + 1);
        }
        return "png";
    }

    /**
     * byte[]로부터 이미지 픽셀 크기를 감지한다.
     *
     * @return [width, height]
     * @throws IOException 이미지 읽기 실패
     */
    static int[] detectPixelSize(byte[] imageData) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
        if (img == null) {
            throw new IOException("Cannot read image data. Unsupported image format.");
        }
        return new int[]{img.getWidth(), img.getHeight()};
    }

    private static String nextUniqueId() {
        return String.valueOf(5000000 + ID_COUNTER.incrementAndGet());
    }

    private static Picture buildInlinePicture(Run run, String itemId,
                                              int pixelW, int pixelH,
                                              long displayW, long displayH) {
        Picture pic = run.addNewPicture();
        String picId = nextUniqueId();

        // ShapeObject
        pic.idAnd(picId).zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.SQUARE)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(nextUniqueId());
        pic.reverseAnd(false);

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
                .centerXAnd(displayW / 2).centerYAnd(displayH / 2)
                .rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // Position (인라인)
        pic.createPos();
        pic.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(true)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.COLUMN)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        // OutMargin
        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageRect (4 corners in displaySize coordinate)
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(displayW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(displayW, displayH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, displayH);

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

        return pic;
    }
}
