package kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer.IDMLStructure.*;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

                // 그룹 객체 (먼저 처리하여 자식 ID를 수집)
                List<IDMLGroup> groups = spread.getGroupsOnPage(page);
                Set<String> groupChildIds = new java.util.HashSet<>();
                for (IDMLGroup group : groups) {
                    collectGroupChildIds(group, groupChildIds);
                    FrameInfo frameInfo = createGroupFrameInfo(group, doc);
                    pageInfo.addFrame(frameInfo);
                }

                // 텍스트 프레임 (그룹 자식 제외)
                List<IDMLTextFrame> textFrames = spread.getTextFramesOnPage(page);
                for (IDMLTextFrame frame : textFrames) {
                    if (groupChildIds.contains(frame.selfId())) continue;
                    FrameInfo frameInfo = createTextFrameInfo(frame, doc);
                    pageInfo.addFrame(frameInfo);
                }

                // 이미지 프레임 (그룹 자식 제외)
                List<IDMLImageFrame> imageFrames = spread.getImageFramesOnPage(page);
                for (IDMLImageFrame frame : imageFrames) {
                    if (groupChildIds.contains(frame.selfId())) continue;
                    FrameInfo frameInfo = createImageFrameInfo(frame);
                    pageInfo.addFrame(frameInfo);
                }

                // 벡터 도형 (그룹 자식 제외)
                List<IDMLVectorShape> vectorShapes = spread.getVectorShapesOnPage(page);
                for (IDMLVectorShape shape : vectorShapes) {
                    if (groupChildIds.contains(shape.selfId())) continue;
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

        // 마스터 스프레드 분석
        for (Map.Entry<String, IDMLSpread> entry : doc.masterSpreads().entrySet()) {
            String masterId = entry.getKey();
            IDMLSpread ms = entry.getValue();

            MasterSpreadInfo msInfo = new MasterSpreadInfo();
            msInfo.setId(masterId);

            // 마스터 이름: 첫 번째 페이지의 이름 또는 ID에서 추출
            String masterName = masterId;
            if (!ms.pages().isEmpty()) {
                String pageName = ms.pages().get(0).name();
                if (pageName != null && !pageName.isEmpty()) {
                    masterName = pageName;
                }
            }
            msInfo.setName(masterName);
            msInfo.setPageCount(ms.pages().size());
            msInfo.setTextFrameCount(ms.textFrames().size());
            msInfo.setImageFrameCount(ms.imageFrames().size());
            msInfo.setVectorCount(ms.vectorShapes().size());
            msInfo.setGroupCount(ms.groups().size());

            // 첫 번째 페이지에서 레이아웃 정보 추출
            if (!ms.pages().isEmpty()) {
                IDMLPage firstPage = ms.pages().get(0);
                msInfo.setPageWidth(firstPage.widthPoints());
                msInfo.setPageHeight(firstPage.heightPoints());
                msInfo.setMarginTop(firstPage.marginTop());
                msInfo.setMarginBottom(firstPage.marginBottom());
                msInfo.setMarginLeft(firstPage.marginLeft());
                msInfo.setMarginRight(firstPage.marginRight());
                msInfo.setColumnCount(firstPage.columnCount());
            }

            // 이 마스터를 사용하는 일반 페이지 수집
            for (IDMLSpread spread : doc.spreads()) {
                for (IDMLPage page : spread.pages()) {
                    if (masterId.equals(page.appliedMasterSpread())) {
                        msInfo.addAppliedPage(String.valueOf(page.pageNumber()));
                    }
                }
            }

            structure.addMasterSpread(msInfo);
        }

        // 일반 스프레드의 마스터 이름 설정
        for (SpreadInfo si : structure.getSpreads()) {
            if (!si.getPages().isEmpty()) {
                String masterRef = si.getPages().get(0).getMasterSpread();
                if (masterRef != null) {
                    // 마스터 스프레드에서 이름 찾기
                    for (MasterSpreadInfo msInfo : structure.getMasterSpreads()) {
                        if (masterRef.equals(msInfo.getId())) {
                            si.setMasterSpreadName(msInfo.getName());
                            break;
                        }
                    }
                }
            }
        }

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

        // Story 내용 탐색
        String storyId = frame.parentStoryId();
        if (storyId != null) {
            IDMLStory story = doc.getStory(storyId);
            if (story != null) {
                // story_content 수집
                StoryContentInfo sci = new StoryContentInfo();
                sci.setStoryId(storyId);
                sci.setParagraphCount(story.paragraphs().size());

                for (IDMLParagraph para : story.paragraphs()) {
                    ParagraphSummary ps = new ParagraphSummary();
                    // 단락 스타일명
                    String styleRef = para.appliedParagraphStyle();
                    if (styleRef != null) {
                        int lastSlash = styleRef.lastIndexOf('/');
                        ps.setStyleName(lastSlash >= 0 ? styleRef.substring(lastSlash + 1) : styleRef);
                    }

                    StringBuilder paraText = new StringBuilder();
                    for (IDMLCharacterRun run : para.characterRuns()) {
                        // 텍스트 런
                        if (run.content() != null && !run.content().isEmpty()) {
                            RunSummary rs = new RunSummary();
                            rs.setType("text");
                            rs.setText(run.content());
                            rs.setFontStyle(run.fontStyle());
                            rs.setFontSize(run.fontSize());
                            ps.addRun(rs);
                            paraText.append(run.content());
                        }
                        // 인라인 TextFrame (재귀 + 런 요약)
                        if (run.inlineFrames() != null) {
                            for (IDMLTextFrame inlineTf : run.inlineFrames()) {
                                info.addChild(createTextFrameInfo(inlineTf, doc));

                                RunSummary rs = new RunSummary();
                                rs.setType("inline_frame");
                                rs.setFrameId(inlineTf.selfId());
                                double[] ib = inlineTf.geometricBounds();
                                if (ib != null && ib.length >= 4) {
                                    rs.setWidth(ib[3] - ib[1]);
                                    rs.setHeight(ib[2] - ib[0]);
                                }
                                ps.addRun(rs);
                            }
                        }
                        // 인라인 그래픽/그룹 (재귀 + 런 요약)
                        if (run.inlineGraphics() != null) {
                            for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                                info.addChild(createInlineGraphicFrameInfo(ig, doc));

                                RunSummary rs = new RunSummary();
                                rs.setType("inline_graphic");
                                rs.setGraphicType(ig.type());
                                rs.setFrameId(ig.selfId());
                                rs.setWidth(ig.widthPoints());
                                rs.setHeight(ig.heightPoints());
                                ps.addRun(rs);
                            }
                        }
                    }
                    ps.setText(paraText.toString());
                    sci.addParagraph(ps);
                }
                info.setStoryContent(sci);
            }
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

    private static FrameInfo createGroupFrameInfo(IDMLGroup group, IDMLDocument doc) {
        FrameInfo info = new FrameInfo();
        info.setId(group.selfId());
        info.setType("group");

        // 그룹 내 자식 수 요약
        int textCount = group.textFrames().size();
        int imageCount = group.imageFrames().size();
        int vectorCount = group.vectorShapes().size();
        int childGroupCount = group.childGroups().size();
        StringBuilder label = new StringBuilder("Group");
        List<String> parts = new java.util.ArrayList<>();
        if (textCount > 0) parts.add(textCount + "T");
        if (imageCount > 0) parts.add(imageCount + "I");
        if (vectorCount > 0) parts.add(vectorCount + "V");
        if (childGroupCount > 0) parts.add(childGroupCount + "G");
        if (!parts.isEmpty()) {
            label.append(" (").append(String.join(" ", parts)).append(")");
        }
        info.setLabel(label.toString());

        double[] bounds = group.geometricBounds();
        if (bounds != null && bounds.length >= 4) {
            info.setX(bounds[1]);
            info.setY(bounds[0]);
            info.setWidth(bounds[3] - bounds[1]);
            info.setHeight(bounds[2] - bounds[0]);
        }

        // 자식 요소를 FrameInfo children으로 추가
        for (IDMLTextFrame tf : group.textFrames()) {
            info.addChild(createTextFrameInfo(tf, doc));
        }
        for (IDMLImageFrame img : group.imageFrames()) {
            info.addChild(createImageFrameInfo(img));
        }
        for (IDMLVectorShape vs : group.vectorShapes()) {
            info.addChild(createVectorFrameInfo(vs));
        }
        for (IDMLGroup childGroup : group.childGroups()) {
            info.addChild(createGroupFrameInfo(childGroup, doc));
        }

        return info;
    }

    private static void collectGroupChildIds(IDMLGroup group, Set<String> ids) {
        for (IDMLTextFrame tf : group.textFrames()) {
            ids.add(tf.selfId());
        }
        for (IDMLImageFrame img : group.imageFrames()) {
            ids.add(img.selfId());
        }
        for (IDMLVectorShape vs : group.vectorShapes()) {
            ids.add(vs.selfId());
        }
        for (IDMLGroup child : group.childGroups()) {
            ids.add(child.selfId());
            collectGroupChildIds(child, ids);
        }
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
     * 인라인 그래픽/그룹을 FrameInfo로 변환.
     * Group의 경우 내부 TextFrame과 자식 그래픽을 재귀적으로 children에 추가.
     */
    private static FrameInfo createInlineGraphicFrameInfo(IDMLCharacterRun.InlineGraphic ig, IDMLDocument doc) {
        FrameInfo info = new FrameInfo();
        info.setId(ig.selfId());

        if ("group".equals(ig.type())) {
            info.setType("group");

            // 라벨 생성
            int tfCount = ig.childTextFrames().size();
            int gCount = ig.childGraphics().size();
            StringBuilder label = new StringBuilder("InlineGroup");
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (tfCount > 0) parts.add(tfCount + "T");
            if (gCount > 0) parts.add(gCount + "G/V");
            if (!parts.isEmpty()) {
                label.append(" (").append(String.join(" ", parts)).append(")");
            }
            info.setLabel(label.toString());
            info.setWidth(ig.widthPoints());
            info.setHeight(ig.heightPoints());

            // 자식 TextFrame (재귀)
            for (IDMLTextFrame tf : ig.childTextFrames()) {
                info.addChild(createTextFrameInfo(tf, doc));
            }
            // 자식 그래픽/중첩 그룹 (재귀)
            for (IDMLCharacterRun.InlineGraphic childG : ig.childGraphics()) {
                info.addChild(createInlineGraphicFrameInfo(childG, doc));
            }
        } else {
            info.setType("vector");
            info.setLabel("Inline " + (ig.type() != null ? ig.type() : "graphic"));
            info.setWidth(ig.widthPoints());
            info.setHeight(ig.heightPoints());
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
