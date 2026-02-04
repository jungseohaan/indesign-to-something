package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IDMLToIntermediateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IntermediateToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediatePage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediatePageInfo;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateSpread;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateParagraph;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

/**
 * 4페이지 변환 테스트.
 */
public class ConvertPage4Test {
    private static final String IDML_DIR = "/tmp/idml_analysis";
    private IDMLDocument idmlDoc;

    @Before
    public void setUp() throws Exception {
        File dir = new File(IDML_DIR);
        Assume.assumeTrue("IDML test data not found", dir.exists());
        idmlDoc = IDMLLoader.loadFromDirectory(dir);
    }

    @Test
    public void convertPage1Cover() throws Exception {
        System.out.println("=== 1페이지 (표지) 변환 ===\n");

        ConvertOptions options = ConvertOptions.defaults()
                .startPage(1)
                .endPage(1)
                .includeImages(true);

        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();

        for (IntermediatePage page : intermediate.pages()) {
            System.out.println("Page " + page.pageNumber() + ": " + page.frames().size() + " frames");
            int vectorCount = 0, imageCount = 0, textCount = 0;
            for (IntermediateFrame frame : page.frames()) {
                if (frame.frameId().startsWith("vector_")) vectorCount++;
                else if ("image".equals(frame.frameType())) imageCount++;
                else if ("text".equals(frame.frameType())) textCount++;
            }
            System.out.println("  벡터: " + vectorCount + ", 이미지: " + imageCount + ", 텍스트: " + textCount);
        }

        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
        System.out.println("\n" + result.summary());

        String outputPath = "testFile/tool/page1_cover.hwpx";
        HWPXWriter.toFilepath(result.hwpxFile(), outputPath);
        System.out.println("저장: " + outputPath);
    }

    @Test
    public void convertPage4Only() throws Exception {
        System.out.println("=== 4페이지만 변환 ===\n");

        ConvertOptions options = ConvertOptions.defaults()
                .startPage(4)
                .endPage(6)
                .includeImages(true)
                .mergeAllPages(true);

        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();

        System.out.println("=== Intermediate 결과 ===");
        for (IntermediatePage page : intermediate.pages()) {
            System.out.println("\nPage " + page.pageNumber() + ":");
            System.out.println("  총 프레임 수: " + page.frames().size());

            int imageCount = 0;
            int textCount = 0;
            for (IntermediateFrame frame : page.frames()) {
                if ("image".equals(frame.frameType())) {
                    imageCount++;
                    System.out.printf("  [IMAGE] %s: zOrder=%d, %.1f x %.1f at (%.1f, %.1f)\n",
                            frame.frameId(), frame.zOrder(),
                            frame.width() / 7200.0, frame.height() / 7200.0,
                            frame.x() / 7200.0, frame.y() / 7200.0);
                } else if ("text".equals(frame.frameType())) {
                    textCount++;
                    System.out.printf("  [TEXT] %s: zOrder=%d\n",
                            frame.frameId(), frame.zOrder());
                }
            }
            System.out.println("  이미지 수: " + imageCount);
            System.out.println("  텍스트 수: " + textCount);
        }

        // 경고 출력
        if (!intermediateResult.warnings().isEmpty()) {
            System.out.println("\n=== 경고 ===");
            for (String w : intermediateResult.warnings()) {
                System.out.println("  - " + w);
            }
        }

        // HWPX 변환 및 저장
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
        System.out.println("\n=== HWPX 변환 결과 ===");
        System.out.println(result.summary());

        String outputPath = "testFile/tool/page4_only.hwpx";
        HWPXWriter.toFilepath(result.hwpxFile(), outputPath);
        System.out.println("\n저장: " + outputPath);
    }

    /**
     * 이미지 프레임 클리핑 정보 디버그 출력.
     */
    @Test
    public void debugImageClipping() throws Exception {
        System.out.println("=== 이미지 프레임 클리핑 분석 ===\n");

        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread spread : idmlDoc.spreads()) {
            System.out.println("\n=== Spread: " + spread.selfId() + " ===");

            int count = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLImageFrame img : spread.imageFrames()) {
                if (count++ >= 5) break;

                double[] frameBounds = img.geometricBounds();
                double[] frameTransform = img.itemTransform();
                double[] imageTransform = img.imageTransform();
                double[] graphicBounds = img.graphicBounds();

                System.out.printf("\n[IMAGE] %s\n", img.selfId());
                System.out.printf("  URI: %s\n", img.linkResourceURI());

                if (frameBounds != null) {
                    System.out.printf("  frameBounds: [top=%.2f, left=%.2f, bottom=%.2f, right=%.2f]\n",
                            frameBounds[0], frameBounds[1], frameBounds[2], frameBounds[3]);
                    System.out.printf("  frameSize: %.2f x %.2f pt\n",
                            frameBounds[3] - frameBounds[1], frameBounds[2] - frameBounds[0]);
                }

                if (frameTransform != null) {
                    System.out.printf("  frameTransform: [%.3f, %.3f, %.3f, %.3f, tx=%.2f, ty=%.2f]\n",
                            frameTransform[0], frameTransform[1], frameTransform[2], frameTransform[3],
                            frameTransform[4], frameTransform[5]);
                }

                if (imageTransform != null) {
                    System.out.printf("  imageTransform: [scaleX=%.3f, %.3f, %.3f, scaleY=%.3f, tx=%.2f, ty=%.2f]\n",
                            imageTransform[0], imageTransform[1], imageTransform[2], imageTransform[3],
                            imageTransform[4], imageTransform[5]);
                }

                if (graphicBounds != null) {
                    System.out.printf("  graphicBounds: [left=%.2f, top=%.2f, right=%.2f, bottom=%.2f]\n",
                            graphicBounds[0], graphicBounds[1], graphicBounds[2], graphicBounds[3]);
                    System.out.printf("  graphicSize: %.2f x %.2f pt\n",
                            graphicBounds[2] - graphicBounds[0], graphicBounds[3] - graphicBounds[1]);
                }
            }
        }
    }

    /**
     * 벡터 도형 SubPath 디버그 출력.
     */
    @Test
    public void debugVectorSubPaths() throws Exception {
        System.out.println("=== 벡터 도형 SubPath 분석 ===\n");

        int totalShapes = 0;
        int shapesWithSubPaths = 0;
        int shapesWithPathPoints = 0;
        int emptyShapes = 0;

        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread spread : idmlDoc.spreads()) {
            System.out.println("\n=== Spread: " + spread.selfId() + " ===");
            System.out.println("Vector shapes: " + spread.vectorShapes().size());

            int count = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape shape : spread.vectorShapes()) {
                totalShapes++;

                boolean hasSubPaths = shape.hasSubPaths();
                boolean hasPathPoints = !shape.pathPoints().isEmpty();

                if (hasSubPaths) shapesWithSubPaths++;
                if (hasPathPoints) shapesWithPathPoints++;
                if (!hasSubPaths && !hasPathPoints) emptyShapes++;

                // 처음 10개만 상세 출력
                if (count < 10) {
                    System.out.printf("\n[%s] %s: fill=%s, stroke=%s, weight=%.1f\n",
                            shape.shapeType(), shape.selfId(),
                            shape.fillColor(), shape.strokeColor(), shape.strokeWeight());
                    System.out.printf("  SubPaths: %d, PathPoints: %d\n",
                            shape.subPaths().size(), shape.pathPoints().size());

                    if (hasSubPaths) {
                        for (int i = 0; i < Math.min(3, shape.subPaths().size()); i++) {
                            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape.SubPath sp = shape.subPaths().get(i);
                            System.out.printf("    SubPath[%d]: %d points, open=%b\n",
                                    i, sp.points().size(), sp.isOpen());
                        }
                        if (shape.subPaths().size() > 3) {
                            System.out.printf("    ... and %d more SubPaths\n", shape.subPaths().size() - 3);
                        }
                    }

                    if (hasPathPoints) {
                        System.out.printf("    PathPoints: %d points\n", shape.pathPoints().size());
                    }
                }
                count++;
            }
        }

        System.out.println("\n=== 요약 ===");
        System.out.println("총 벡터 도형: " + totalShapes);
        System.out.println("SubPath 있는 도형: " + shapesWithSubPaths);
        System.out.println("PathPoints 있는 도형: " + shapesWithPathPoints);
        System.out.println("비어있는 도형 (bounds만): " + emptyShapes);
    }

    /**
     * IDML 원본 좌표 디버그 출력.
     */
    @Test
    public void debugIdmlCoordinates() throws Exception {
        System.out.println("=== IDML 원본 좌표 분석 ===\n");

        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread spread : idmlDoc.spreads()) {
            System.out.println("Spread: " + spread.selfId());

            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLPage page : spread.pages()) {
                double[] bounds = page.geometricBounds();
                double[] transform = page.itemTransform();
                double[] absPos = kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry.absoluteTopLeft(bounds, transform);

                System.out.printf("  Page %d: bounds=[%.1f, %.1f, %.1f, %.1f] transform=[%.3f, %.3f, %.3f, %.3f, %.1f, %.1f]\n",
                        page.pageNumber(),
                        bounds[0], bounds[1], bounds[2], bounds[3],
                        transform[0], transform[1], transform[2], transform[3], transform[4], transform[5]);
                System.out.printf("          absoluteTopLeft=(%.1f, %.1f) size=%.1f x %.1f pt\n",
                        absPos[0], absPos[1],
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry.width(bounds),
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry.height(bounds));
            }

            // 첫 3개 이미지 프레임만 출력
            int count = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLImageFrame img : spread.imageFrames()) {
                if (count++ >= 3) break;
                double[] bounds = img.geometricBounds();
                double[] transform = img.itemTransform();
                if (bounds == null || transform == null) continue;
                double[] absPos = kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry.absoluteTopLeft(bounds, transform);
                System.out.printf("  Image %s: bounds=[%.1f, %.1f, %.1f, %.1f] transform=[%.3f, %.3f, %.3f, %.3f, %.1f, %.1f]\n",
                        img.selfId(),
                        bounds[0], bounds[1], bounds[2], bounds[3],
                        transform[0], transform[1], transform[2], transform[3], transform[4], transform[5]);
                System.out.printf("            absoluteTopLeft=(%.1f, %.1f)\n", absPos[0], absPos[1]);
            }

            // 첫 3개 벡터 도형만 출력
            count = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape vec : spread.vectorShapes()) {
                if (count++ >= 3) break;
                double[] bounds = vec.geometricBounds();
                double[] transform = vec.itemTransform();
                if (bounds == null || transform == null) continue;
                double[] absPos = kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry.absoluteTopLeft(bounds, transform);
                System.out.printf("  Vector %s: bounds=[%.1f, %.1f, %.1f, %.1f] transform=[%.3f, %.3f, %.3f, %.3f, %.1f, %.1f]\n",
                        vec.selfId(),
                        bounds[0], bounds[1], bounds[2], bounds[3],
                        transform[0], transform[1], transform[2], transform[3], transform[4], transform[5]);
                System.out.printf("             absoluteTopLeft=(%.1f, %.1f)\n", absPos[0], absPos[1]);
            }

            System.out.println();
        }
    }

    /**
     * 스프레드 기반 변환 테스트.
     * IDML 스프레드를 HWPX 페이지로 1:1 매핑한다.
     */
    @Test
    public void convertSpreadBased() throws Exception {
        System.out.println("=== 스프레드 기반 변환 ===\n");

        ConvertOptions options = ConvertOptions.defaults()
                .startPage(1)
                .endPage(100)  // 전체 문서
                .includeImages(true)
                .spreadBasedConversion(true);  // 스프레드 모드

        IDMLToIntermediateConverter.Result intermediateResult =
                IDMLToIntermediateConverter.convert(idmlDoc, options, "test.idml");
        IntermediateDocument intermediate = intermediateResult.document();

        System.out.println("스프레드 모드: " + intermediate.useSpreadMode());
        System.out.println("스프레드 수: " + intermediate.spreads().size());

        for (IntermediateSpread spread : intermediate.spreads()) {
            System.out.println("\n=== Spread " + spread.spreadId() + " ===");
            System.out.printf("  크기: %.1f x %.1f mm\n",
                    spread.spreadWidth() / 2834.6,  // HWPUNIT to mm
                    spread.spreadHeight() / 2834.6);

            System.out.println("  포함된 페이지:");
            for (IntermediatePageInfo pageInfo : spread.pageInfos()) {
                System.out.printf("    Page %d: offset=(%.1f, %.1f) mm, size=%.1f x %.1f mm\n",
                        pageInfo.pageNumber(),
                        pageInfo.offsetX() / 2834.6,
                        pageInfo.offsetY() / 2834.6,
                        pageInfo.pageWidth() / 2834.6,
                        pageInfo.pageHeight() / 2834.6);
            }

            System.out.println("  프레임 수: " + spread.frames().size());
            int imageCount = 0;
            int textCount = 0;
            double spreadW = spread.spreadWidth() / 2834.6;  // mm
            double spreadH = spread.spreadHeight() / 2834.6;
            System.out.println("\n  === 프레임 좌표 (mm) ===");
            for (IntermediateFrame frame : spread.frames()) {
                double x = frame.x() / 2834.6;
                double y = frame.y() / 2834.6;
                double w = frame.width() / 2834.6;
                double h = frame.height() / 2834.6;
                boolean outOfBounds = (x < 0 || y < 0 || x + w > spreadW || y + h > spreadH);
                if ("image".equals(frame.frameType())) {
                    imageCount++;
                    if (outOfBounds) {
                        System.out.printf("  [IMAGE] %s: (%.1f, %.1f) size=%.1f x %.1f ** OUT OF BOUNDS **\n",
                                frame.frameId(), x, y, w, h);
                    }
                } else if ("text".equals(frame.frameType())) {
                    textCount++;
                    if (outOfBounds) {
                        System.out.printf("  [TEXT] %s: (%.1f, %.1f) size=%.1f x %.1f ** OUT OF BOUNDS **\n",
                                frame.frameId(), x, y, w, h);
                    }
                }
            }
            System.out.println("    이미지: " + imageCount);
            System.out.println("    텍스트: " + textCount);

            // 텍스트 프레임 상세 정보
            System.out.println("\n  === 텍스트 프레임 상세 ===");
            for (IntermediateFrame frame : spread.frames()) {
                if ("text".equals(frame.frameType())) {
                    double x = frame.x() / 2834.6;
                    double y = frame.y() / 2834.6;
                    String preview = "";
                    if (frame.paragraphs() != null && !frame.paragraphs().isEmpty()) {
                        IntermediateParagraph firstPara = frame.paragraphs().get(0);
                        if (firstPara.runs() != null && !firstPara.runs().isEmpty()) {
                            preview = firstPara.runs().get(0).text();
                            if (preview != null && preview.length() > 30) {
                                preview = preview.substring(0, 30) + "...";
                            }
                        }
                    }
                    System.out.printf("  [TEXT] %s: (%.1f, %.1f) \"%s\"\n",
                            frame.frameId(), x, y, preview);
                }
            }
        }

        // 경고 출력
        if (!intermediateResult.warnings().isEmpty()) {
            System.out.println("\n=== 경고 ===");
            for (String w : intermediateResult.warnings()) {
                System.out.println("  - " + w);
            }
        }

        // HWPX 변환 및 저장
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
        System.out.println("\n=== HWPX 변환 결과 ===");
        System.out.println(result.summary());

        String outputPath = "testFile/tool/spread_based.hwpx";
        HWPXWriter.toFilepath(result.hwpxFile(), outputPath);
        System.out.println("\n저장: " + outputPath);
    }
}
