package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * PSD (Photoshop), AI (Adobe Illustrator) 등 디자인 파일을 PNG로 변환하여
 * HWPX 문서에 삽입하는 유틸리티 클래스.
 *
 * <p>외부 도구를 사용하여 변환한다:</p>
 * <ul>
 *   <li><b>PSD</b>: ImageMagick ({@code magick} 또는 {@code convert}) 사용</li>
 *   <li><b>AI</b>: Ghostscript ({@code gs}) 사용 (AI 파일은 PostScript/PDF 기반)</li>
 * </ul>
 *
 * <p>사용 예:</p>
 * <pre>{@code
 * // PSD 파일을 PNG로 변환 후 삽입
 * Picture pic = DesignFileConverter.insertInlineFromDesignFile(
 *     hwpxFile, run, new File("design.psd"), 15000L, 10000L);
 *
 * // AI 파일을 PNG byte[]로 변환만 수행
 * byte[] pngData = DesignFileConverter.convertAiToPng(new File("logo.ai"), 300);
 * }</pre>
 *
 * <p>외부 도구가 설치되어 있지 않으면 {@link IOException}을 발생시킨다.
 * {@link #isImageMagickAvailable()}, {@link #isGhostscriptAvailable()}로 사전 확인 가능.</p>
 */
public class DesignFileConverter {

    private static final long PROCESS_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_AI_DPI = 300;

    private static volatile Boolean imageMagickAvailable;
    private static volatile Boolean ghostscriptAvailable;
    private static volatile String imageMagickCommand;

    private DesignFileConverter() {
    }

    // ── 도구 감지 ──

    /**
     * ImageMagick이 시스템에 설치되어 있는지 확인한다.
     * 결과는 캐시되므로 반복 호출 시 프로세스를 재실행하지 않는다.
     *
     * @return ImageMagick 사용 가능 여부
     */
    public static boolean isImageMagickAvailable() {
        if (imageMagickAvailable == null) {
            detectImageMagick();
        }
        return imageMagickAvailable;
    }

    /**
     * Ghostscript이 시스템에 설치되어 있는지 확인한다.
     * 결과는 캐시되므로 반복 호출 시 프로세스를 재실행하지 않는다.
     *
     * @return Ghostscript 사용 가능 여부
     */
    public static boolean isGhostscriptAvailable() {
        if (ghostscriptAvailable == null) {
            detectGhostscript();
        }
        return ghostscriptAvailable;
    }

    private static synchronized void detectImageMagick() {
        if (imageMagickAvailable != null) {
            return;
        }
        if (tryCommand("magick", "--version")) {
            imageMagickCommand = "magick";
            imageMagickAvailable = true;
            return;
        }
        if (tryCommand("convert", "--version")) {
            imageMagickCommand = "convert";
            imageMagickAvailable = true;
            return;
        }
        imageMagickAvailable = false;
    }

    private static synchronized void detectGhostscript() {
        if (ghostscriptAvailable != null) {
            return;
        }
        ghostscriptAvailable = tryCommand("gs", "--version");
    }

    private static boolean tryCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            drainStream(process.getInputStream());
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── 변환 ──

    /**
     * 디자인 파일 (PSD, AI, TIFF)을 PNG byte[]로 변환한다.
     * 파일 확장자로부터 형식을 자동 감지한다.
     *
     * @param inputFile PSD, AI 또는 TIFF 파일
     * @return PNG 이미지 데이터
     * @throws IOException 변환 실패, 외부 도구 미설치, 또는 지원하지 않는 형식
     */
    public static byte[] convertToPng(File inputFile) throws IOException {
        String ext = getExtension(inputFile).toLowerCase();
        switch (ext) {
            case "psd":
                return convertPsdToPng(inputFile);
            case "ai":
                return convertAiToPng(inputFile);
            case "pdf":
                return convertPdfToPng(inputFile);
            case "eps":
                return convertEpsToPng(inputFile);
            case "tiff":
            case "tif":
                return convertTiffToPng(inputFile);
            default:
                throw new IOException(
                        "Unsupported design file format: ." + ext
                                + " (supported: .psd, .ai, .pdf, .eps, .tiff)");
        }
    }

    /**
     * 디자인 파일을 지정 DPI로 PNG byte[]로 변환한다.
     * AI 파일의 경우 지정 DPI로 래스터화하고, PSD/TIFF는 DPI 무관하게 변환한다.
     *
     * @param inputFile PSD, AI 또는 TIFF 파일
     * @param dpi       AI 래스터화 해상도
     * @return PNG 이미지 데이터
     * @throws IOException 변환 실패, 외부 도구 미설치, 또는 지원하지 않는 형식
     */
    public static byte[] convertToPng(File inputFile, int dpi) throws IOException {
        String ext = getExtension(inputFile).toLowerCase();
        switch (ext) {
            case "psd":
                return convertPsdToPng(inputFile);
            case "ai":
                return convertAiToPng(inputFile, dpi);
            case "pdf":
                return convertPdfToPng(inputFile, dpi);
            case "eps":
                return convertEpsToPng(inputFile, dpi);
            case "tiff":
            case "tif":
                return convertTiffToPng(inputFile);
            default:
                throw new IOException(
                        "Unsupported design file format: ." + ext
                                + " (supported: .psd, .ai, .pdf, .eps, .tiff)");
        }
    }

    /**
     * PSD (Photoshop) 파일을 PNG로 변환한다.
     * ImageMagick을 사용하여 모든 레이어를 합친 (flatten) 이미지를 생성한다.
     *
     * @param psdFile PSD 파일
     * @return PNG 이미지 데이터
     * @throws IOException ImageMagick 미설치 또는 변환 실패
     */
    public static byte[] convertPsdToPng(File psdFile) throws IOException {
        if (!psdFile.exists()) {
            throw new IOException("PSD file not found: " + psdFile.getAbsolutePath());
        }
        if (!isImageMagickAvailable()) {
            throw new IOException(
                    "ImageMagick is not installed. "
                            + "Install it to convert PSD files. "
                            + "(macOS: brew install imagemagick, "
                            + "Linux: apt install imagemagick)");
        }

        File tempPng = File.createTempFile("hwpxlib_psd_", ".png");
        tempPng.deleteOnExit();

        try {
            // 투명 PNG 생성: -background none으로 투명 배경 유지
            ProcessBuilder pb = new ProcessBuilder(
                    imageMagickCommand,
                    psdFile.getAbsolutePath() + "[0]",
                    "-background", "none",
                    "-colorspace", "sRGB",
                    "-depth", "8",
                    "PNG32:" + tempPng.getAbsolutePath()  // 32비트 RGBA PNG
            );
            executeProcess(pb, "PSD to PNG conversion");

            return Files.readAllBytes(tempPng.toPath());
        } finally {
            tempPng.delete();
        }
    }

    /**
     * AI (Adobe Illustrator) 파일을 PNG로 변환한다.
     * Ghostscript을 사용하며 기본 300 DPI로 래스터화한다.
     *
     * @param aiFile AI 파일
     * @return PNG 이미지 데이터
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static byte[] convertAiToPng(File aiFile) throws IOException {
        return convertAiToPng(aiFile, DEFAULT_AI_DPI);
    }

    /**
     * AI (Adobe Illustrator) 파일을 지정 DPI로 PNG로 변환한다.
     * Ghostscript을 사용하여 벡터 이미지를 래스터화한다.
     *
     * @param aiFile AI 파일
     * @param dpi    래스터화 해상도 (예: 72, 150, 300)
     * @return PNG 이미지 데이터
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static byte[] convertAiToPng(File aiFile, int dpi) throws IOException {
        if (!aiFile.exists()) {
            throw new IOException("AI file not found: " + aiFile.getAbsolutePath());
        }
        if (dpi <= 0) {
            throw new IllegalArgumentException("DPI must be positive: " + dpi);
        }
        if (!isGhostscriptAvailable()) {
            throw new IOException(
                    "Ghostscript is not installed. "
                            + "Install it to convert AI files. "
                            + "(macOS: brew install ghostscript, "
                            + "Linux: apt install ghostscript)");
        }

        File tempPng = File.createTempFile("hwpxlib_ai_", ".png");
        tempPng.deleteOnExit();

        try {
            // 투명 PNG 생성: pngalpha 디바이스 + ArtBox 크롭 (AI 파일은 PDF 기반이므로
            // MediaBox가 A4 전체인 경우가 많음. ArtBox만 렌더링해야 실제 그래픽 영역만 추출됨)
            ProcessBuilder pb = new ProcessBuilder(
                    "gs",
                    "-dNOPAUSE",
                    "-dBATCH",
                    "-dSAFER",
                    "-dUseArtBox",
                    "-sDEVICE=pngalpha",
                    "-r" + dpi,
                    "-sOutputFile=" + tempPng.getAbsolutePath(),
                    aiFile.getAbsolutePath()
            );
            executeProcess(pb, "AI to PNG conversion");

            return Files.readAllBytes(tempPng.toPath());
        } finally {
            tempPng.delete();
        }
    }

    /**
     * PDF 파일을 PNG로 변환한다.
     * Ghostscript을 사용하며 기본 300 DPI로 래스터화한다.
     *
     * @param pdfFile PDF 파일
     * @return PNG 이미지 데이터
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static byte[] convertPdfToPng(File pdfFile) throws IOException {
        return convertPdfToPng(pdfFile, DEFAULT_AI_DPI);
    }

    /**
     * PDF 파일을 지정 DPI로 PNG로 변환한다.
     * Ghostscript을 사용하여 벡터 이미지를 래스터화한다.
     * 멀티페이지 PDF의 경우 첫 페이지만 변환한다.
     *
     * @param pdfFile PDF 파일
     * @param dpi     래스터화 해상도 (예: 72, 150, 300)
     * @return PNG 이미지 데이터
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static byte[] convertPdfToPng(File pdfFile, int dpi) throws IOException {
        if (!pdfFile.exists()) {
            throw new IOException("PDF file not found: " + pdfFile.getAbsolutePath());
        }
        if (dpi <= 0) {
            throw new IllegalArgumentException("DPI must be positive: " + dpi);
        }
        if (!isGhostscriptAvailable()) {
            throw new IOException(
                    "Ghostscript is not installed. "
                            + "Install it to convert PDF files. "
                            + "(macOS: brew install ghostscript, "
                            + "Linux: apt install ghostscript)");
        }

        File tempPng = File.createTempFile("hwpxlib_pdf_", ".png");
        tempPng.deleteOnExit();

        try {
            // 투명 PNG 생성: pngalpha 디바이스 사용 (32비트 RGBA)
            ProcessBuilder pb = new ProcessBuilder(
                    "gs",
                    "-dNOPAUSE",
                    "-dBATCH",
                    "-dSAFER",
                    "-dFirstPage=1",
                    "-dLastPage=1",
                    "-sDEVICE=pngalpha",
                    "-r" + dpi,
                    "-sOutputFile=" + tempPng.getAbsolutePath(),
                    pdfFile.getAbsolutePath()
            );
            executeProcess(pb, "PDF to PNG conversion");

            return Files.readAllBytes(tempPng.toPath());
        } finally {
            tempPng.delete();
        }
    }

    /**
     * EPS (Encapsulated PostScript) 파일을 PNG로 변환한다.
     * Ghostscript을 사용하며 기본 300 DPI로 래스터화한다.
     *
     * @param epsFile EPS 파일
     * @return PNG 이미지 데이터
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static byte[] convertEpsToPng(File epsFile) throws IOException {
        return convertEpsToPng(epsFile, DEFAULT_AI_DPI);
    }

    /**
     * EPS 파일을 지정 DPI로 PNG로 변환한다.
     * Ghostscript을 사용하여 벡터 이미지를 래스터화한다.
     *
     * @param epsFile EPS 파일
     * @param dpi     래스터화 해상도 (예: 72, 150, 300)
     * @return PNG 이미지 데이터
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static byte[] convertEpsToPng(File epsFile, int dpi) throws IOException {
        if (!epsFile.exists()) {
            throw new IOException("EPS file not found: " + epsFile.getAbsolutePath());
        }
        if (dpi <= 0) {
            throw new IllegalArgumentException("DPI must be positive: " + dpi);
        }
        if (!isGhostscriptAvailable()) {
            throw new IOException(
                    "Ghostscript is not installed. "
                            + "Install it to convert EPS files. "
                            + "(macOS: brew install ghostscript, "
                            + "Linux: apt install ghostscript)");
        }

        File tempPng = File.createTempFile("hwpxlib_eps_", ".png");
        tempPng.deleteOnExit();

        try {
            // 투명 PNG 생성: pngalpha 디바이스 사용 (32비트 RGBA)
            ProcessBuilder pb = new ProcessBuilder(
                    "gs",
                    "-dNOPAUSE",
                    "-dBATCH",
                    "-dSAFER",
                    "-dEPSCrop",
                    "-sDEVICE=pngalpha",
                    "-r" + dpi,
                    "-sOutputFile=" + tempPng.getAbsolutePath(),
                    epsFile.getAbsolutePath()
            );
            executeProcess(pb, "EPS to PNG conversion");

            return Files.readAllBytes(tempPng.toPath());
        } finally {
            tempPng.delete();
        }
    }

    /**
     * TIFF 파일을 PNG로 변환한다.
     * ImageMagick을 사용하며, 멀티페이지 TIFF의 경우 첫 페이지만 변환한다.
     *
     * @param tiffFile TIFF 파일
     * @return PNG 이미지 데이터
     * @throws IOException ImageMagick 미설치 또는 변환 실패
     */
    public static byte[] convertTiffToPng(File tiffFile) throws IOException {
        if (!tiffFile.exists()) {
            throw new IOException("TIFF file not found: " + tiffFile.getAbsolutePath());
        }

        // Java 네이티브 시도 (Java 9+ 또는 TIFF ImageIO 플러그인이 있는 경우)
        byte[] javaNative = tryJavaNativeToPng(tiffFile);
        if (javaNative != null) return javaNative;

        // ImageMagick 폴백
        if (!isImageMagickAvailable()) {
            throw new IOException(
                    "TIFF conversion requires Java 9+ or ImageMagick. "
                            + "(macOS: brew install imagemagick, "
                            + "Linux: apt install imagemagick)");
        }

        File tempPng = File.createTempFile("hwpxlib_tiff_", ".png");
        tempPng.deleteOnExit();

        try {
            // 투명 PNG 생성: -background none으로 투명 배경 유지
            ProcessBuilder pb = new ProcessBuilder(
                    imageMagickCommand,
                    tiffFile.getAbsolutePath() + "[0]",
                    "-background", "none",
                    "PNG32:" + tempPng.getAbsolutePath()  // 32비트 RGBA PNG
            );
            executeProcess(pb, "TIFF to PNG conversion");

            return Files.readAllBytes(tempPng.toPath());
        } finally {
            tempPng.delete();
        }
    }

    // ── ImageInserter 연동 ──

    /**
     * 디자인 파일 (PSD, AI, TIFF)을 PNG로 변환한 후, HWPX 문서에 인라인 이미지로 삽입한다.
     * 파일 확장자로부터 형식을 자동 감지한다.
     *
     * @param hwpxFile      대상 HWPX 파일
     * @param run           Picture를 추가할 Run
     * @param designFile    PSD 또는 AI 파일
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     * @throws IOException 변환 실패, 외부 도구 미설치, 또는 지원하지 않는 형식
     */
    public static Picture insertInlineFromDesignFile(
            HWPXFile hwpxFile, Run run, File designFile,
            long displayWidth, long displayHeight) throws IOException {
        byte[] pngData = convertToPng(designFile);
        return ImageInserter.insertInlineFromBytes(
                hwpxFile, run, pngData, "png", displayWidth, displayHeight);
    }

    /**
     * PSD (Photoshop) 파일을 PNG로 변환한 후, HWPX 문서에 인라인 이미지로 삽입한다.
     *
     * @param hwpxFile      대상 HWPX 파일
     * @param run           Picture를 추가할 Run
     * @param psdFile       PSD 파일
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     * @throws IOException ImageMagick 미설치 또는 변환 실패
     */
    public static Picture insertInlineFromPSD(
            HWPXFile hwpxFile, Run run, File psdFile,
            long displayWidth, long displayHeight) throws IOException {
        byte[] pngData = convertPsdToPng(psdFile);
        return ImageInserter.insertInlineFromBytes(
                hwpxFile, run, pngData, "png", displayWidth, displayHeight);
    }

    /**
     * AI (Adobe Illustrator) 파일을 PNG로 변환한 후, HWPX 문서에 인라인 이미지로 삽입한다.
     *
     * @param hwpxFile      대상 HWPX 파일
     * @param run           Picture를 추가할 Run
     * @param aiFile        AI 파일
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static Picture insertInlineFromAI(
            HWPXFile hwpxFile, Run run, File aiFile,
            long displayWidth, long displayHeight) throws IOException {
        byte[] pngData = convertAiToPng(aiFile);
        return ImageInserter.insertInlineFromBytes(
                hwpxFile, run, pngData, "png", displayWidth, displayHeight);
    }

    /**
     * AI (Adobe Illustrator) 파일을 지정 DPI로 PNG로 변환한 후,
     * HWPX 문서에 인라인 이미지로 삽입한다.
     *
     * @param hwpxFile      대상 HWPX 파일
     * @param run           Picture를 추가할 Run
     * @param aiFile        AI 파일
     * @param dpi           래스터화 해상도
     * @param displayWidth  화면 표시 너비 (hwpunit)
     * @param displayHeight 화면 표시 높이 (hwpunit)
     * @return 생성된 Picture 객체
     * @throws IOException Ghostscript 미설치 또는 변환 실패
     */
    public static Picture insertInlineFromAI(
            HWPXFile hwpxFile, Run run, File aiFile, int dpi,
            long displayWidth, long displayHeight) throws IOException {
        byte[] pngData = convertAiToPng(aiFile, dpi);
        return ImageInserter.insertInlineFromBytes(
                hwpxFile, run, pngData, "png", displayWidth, displayHeight);
    }

    // ── Java 네이티브 변환 ──

    /**
     * Java의 ImageIO를 사용하여 이미지를 PNG로 변환을 시도한다.
     * Java 9+에서는 TIFF를 지원하며, ImageIO 플러그인이 있으면 다른 형식도 지원할 수 있다.
     *
     * @param imageFile 변환할 이미지 파일
     * @return PNG 데이터 또는 null (Java가 형식을 지원하지 않는 경우)
     */
    private static byte[] tryJavaNativeToPng(File imageFile) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imageFile);
            if (img == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(img, "png", baos)) return null;
            return baos.toByteArray();
        } catch (Exception e) {
            // Java가 이 형식을 읽을 수 없음 → 외부 도구 폴백
            return null;
        }
    }

    // ── 내부 헬퍼 ──

    private static void executeProcess(ProcessBuilder pb, String operationName)
            throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();

        byte[] output = readAllBytes(process.getInputStream());

        try {
            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(
                        operationName + " timed out after "
                                + PROCESS_TIMEOUT_SECONDS + " seconds.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(operationName + " was interrupted.", e);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorOutput = new String(output).trim();
            String message = operationName + " failed with exit code " + exitCode;
            if (!errorOutput.isEmpty()) {
                message += ": " + errorOutput;
            }
            throw new IOException(message);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            buffer.write(buf, 0, n);
        }
        return buffer.toByteArray();
    }

    private static void drainStream(InputStream is) {
        try {
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) {
                // discard
            }
        } catch (IOException ignored) {
        }
    }

    private static String getExtension(File file) {
        String name = file.getName();
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < name.length() - 1) {
            return name.substring(dotIdx + 1);
        }
        return "";
    }

    /**
     * 도구 감지 캐시를 초기화한다. 테스트 목적으로만 사용한다.
     */
    static void resetDetectionCache() {
        imageMagickAvailable = null;
        ghostscriptAvailable = null;
        imageMagickCommand = null;
    }
}
