package kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer.IDMLStructure.*;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;

/**
 * IDML 문서 구조 분석기.
 * 문서의 스프레드, 페이지, 프레임 구조를 추출하여 JSON으로 출력.
 */
public class IDMLAnalyzer {

    /**
     * IDML 파일을 분석하고 구조를 JSON으로 출력.
     */
    public static void analyze(String idmlPath, PrintStream out) throws Exception {
        IDMLDocument doc = IDMLLoader.load(idmlPath);
        try {
            IDMLStructure structure = analyzeDocument(doc);
            String json = IDMLStructureSerializer.toJson(structure);
            out.println(json);
        } finally {
            doc.cleanup();
        }
    }

    /**
     * IDMLDocument를 분석하여 IDMLStructure로 변환.
     */
    public static IDMLStructure analyzeDocument(IDMLDocument doc) {
        IDMLStructure structure = new IDMLStructure();

        int totalTextFrames = 0;
        int totalImageFrames = 0;
        int totalVectorShapes = 0;
        int totalTables = 0;

        for (IDMLSpread spread : doc.spreads()) {
            SpreadInfo spreadInfo = new SpreadInfo();
            spreadInfo.setId(spread.selfId());
            spreadInfo.setPageCount(spread.pages().size());
            spreadInfo.setTextFrameCount(spread.textFrames().size());
            spreadInfo.setImageFrameCount(spread.imageFrames().size());
            spreadInfo.setVectorCount(spread.vectorShapes().size());

            // 스프레드 전체 바운드 계산
            double[] spreadBounds = calculateSpreadBounds(spread);
            if (spreadBounds != null) {
                spreadInfo.setBoundsTop(spreadBounds[0]);
                spreadInfo.setBoundsLeft(spreadBounds[1]);
                spreadInfo.setBoundsBottom(spreadBounds[2]);
                spreadInfo.setBoundsRight(spreadBounds[3]);
                spreadInfo.setTotalWidth(spreadBounds[3] - spreadBounds[1]);
                spreadInfo.setTotalHeight(spreadBounds[2] - spreadBounds[0]);
            }

            totalTextFrames += spread.textFrames().size();
            totalImageFrames += spread.imageFrames().size();
            totalVectorShapes += spread.vectorShapes().size();

            // 각 페이지별 프레임 정보
            for (IDMLPage page : spread.pages()) {
                PageInfo pageInfo = new PageInfo();
                pageInfo.setId(page.selfId());
                pageInfo.setName(page.name() != null ? page.name() : "Page " + page.selfId());
                pageInfo.setPageNumber(page.pageNumber());
                pageInfo.setWidth(page.widthPoints());
                pageInfo.setHeight(page.heightPoints());

                // 페이지 레이아웃 상세 정보
                pageInfo.setGeometricBounds(page.geometricBounds());
                pageInfo.setItemTransform(page.itemTransform());
                pageInfo.setMarginTop(page.marginTop());
                pageInfo.setMarginBottom(page.marginBottom());
                pageInfo.setMarginLeft(page.marginLeft());
                pageInfo.setMarginRight(page.marginRight());
                pageInfo.setColumnCount(page.columnCount());
                pageInfo.setMasterSpread(page.appliedMasterSpread());

                // 텍스트 프레임
                List<IDMLTextFrame> textFrames = spread.getTextFramesOnPage(page);
                for (IDMLTextFrame frame : textFrames) {
                    FrameInfo frameInfo = createTextFrameInfo(frame, doc);
                    pageInfo.addFrame(frameInfo);
                }

                // 이미지 프레임
                List<IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);
                for (IDMLImageFrame frame : imageFrames) {
                    FrameInfo frameInfo = createImageFrameInfo(frame);
                    pageInfo.addFrame(frameInfo);
                }

                // 벡터 도형
                List<IDMLVectorShape> vectorShapes = spread.getVectorShapesOnPage(page);
                for (IDMLVectorShape shape : vectorShapes) {
                    FrameInfo frameInfo = createVectorFrameInfo(shape);
                    pageInfo.addFrame(frameInfo);
                }

                spreadInfo.addPage(pageInfo);
            }

            structure.addSpread(spreadInfo);
        }

        structure.setTotalTextFrames(totalTextFrames);
        structure.setTotalImageFrames(totalImageFrames);
        structure.setTotalVectorShapes(totalVectorShapes);
        structure.setTotalTables(totalTables);

        return structure;
    }

    private static FrameInfo createTextFrameInfo(IDMLTextFrame frame, IDMLDocument doc) {
        FrameInfo info = new FrameInfo();
        info.setId(frame.selfId());
        info.setType("text");
        info.setLabel(extractTextLabel(frame, doc));

        double[] bounds = frame.geometricBounds();
        if (bounds != null && bounds.length >= 4) {
            info.setX(bounds[1]);
            info.setY(bounds[0]);
            info.setWidth(bounds[3] - bounds[1]);
            info.setHeight(bounds[2] - bounds[0]);
        }

        return info;
    }

    private static FrameInfo createImageFrameInfo(IDMLImageFrame frame) {
        FrameInfo info = new FrameInfo();
        info.setId(frame.selfId());
        info.setType("image");

        String linkPath = frame.linkResourceURI();
        String label = linkPath;
        if (label != null) {
            // 파일명만 추출
            int lastSlash = Math.max(label.lastIndexOf('/'), label.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                label = label.substring(lastSlash + 1);
            }
            // URL 인코딩된 한글 파일명 디코딩
            try {
                label = URLDecoder.decode(label, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // UTF-8은 항상 지원되므로 이 예외는 발생하지 않음
            }

            // PSD, AI, EPS 파일 여부 확인
            String lowerLabel = label.toLowerCase();
            boolean needsPreview = lowerLabel.endsWith(".psd") ||
                                   lowerLabel.endsWith(".ai") ||
                                   lowerLabel.endsWith(".eps");
            info.setNeedsPreview(needsPreview);
        }
        info.setLabel(label != null ? label : "Image");
        info.setLinkPath(linkPath);

        double[] bounds = frame.geometricBounds();
        if (bounds != null && bounds.length >= 4) {
            info.setX(bounds[1]);
            info.setY(bounds[0]);
            info.setWidth(bounds[3] - bounds[1]);
            info.setHeight(bounds[2] - bounds[0]);
        }

        return info;
    }

    private static FrameInfo createVectorFrameInfo(IDMLVectorShape shape) {
        FrameInfo info = new FrameInfo();
        info.setId(shape.selfId());
        info.setType("vector");
        info.setLabel(shape.shapeType() != null ? shape.shapeType().name() : "Vector");

        double[] bounds = shape.geometricBounds();
        if (bounds != null && bounds.length >= 4) {
            info.setX(bounds[1]);
            info.setY(bounds[0]);
            info.setWidth(bounds[3] - bounds[1]);
            info.setHeight(bounds[2] - bounds[0]);
        }

        return info;
    }

    /**
     * 텍스트 프레임에서 미리보기용 텍스트 라벨 추출 (최대 50자).
     */
    private static String extractTextLabel(IDMLTextFrame frame, IDMLDocument doc) {
        String storyId = frame.parentStoryId();
        if (storyId == null) return "TextFrame";

        IDMLStory story = doc.getStory(storyId);
        if (story == null || story.paragraphs().isEmpty()) return "TextFrame";

        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (run.content() != null) {
                    sb.append(run.content());
                    if (sb.length() > 50) {
                        return sb.substring(0, 50) + "...";
                    }
                }
            }
        }

        return sb.length() > 0 ? sb.toString() : "TextFrame";
    }

    /**
     * 스프레드 전체 바운드 계산 (모든 페이지를 포함하는 범위).
     * @return [top, left, bottom, right] in points
     */
    private static double[] calculateSpreadBounds(IDMLSpread spread) {
        if (spread.pages().isEmpty()) return null;

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;

        for (IDMLPage page : spread.pages()) {
            double[] bounds = page.geometricBounds();
            double[] transform = page.itemTransform();

            if (bounds != null && transform != null) {
                // 페이지 네 모서리를 변환하여 실제 좌표 계산
                double[] topLeft = CoordinateConverter.applyTransform(transform, bounds[1], bounds[0]);
                double[] topRight = CoordinateConverter.applyTransform(transform, bounds[3], bounds[0]);
                double[] bottomLeft = CoordinateConverter.applyTransform(transform, bounds[1], bounds[2]);
                double[] bottomRight = CoordinateConverter.applyTransform(transform, bounds[3], bounds[2]);

                minX = Math.min(minX, Math.min(Math.min(topLeft[0], topRight[0]), Math.min(bottomLeft[0], bottomRight[0])));
                maxX = Math.max(maxX, Math.max(Math.max(topLeft[0], topRight[0]), Math.max(bottomLeft[0], bottomRight[0])));
                minY = Math.min(minY, Math.min(Math.min(topLeft[1], topRight[1]), Math.min(bottomLeft[1], bottomRight[1])));
                maxY = Math.max(maxY, Math.max(Math.max(topLeft[1], topRight[1]), Math.max(bottomLeft[1], bottomRight[1])));
            }
        }

        if (minX == Double.MAX_VALUE) return null;

        return new double[] { minY, minX, maxY, maxX };
    }

}
