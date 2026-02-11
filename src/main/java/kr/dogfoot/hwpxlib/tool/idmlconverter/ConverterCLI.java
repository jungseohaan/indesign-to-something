package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer.IDMLAnalyzer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IDMLPageRenderer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.hwpxconverter.HwpxToIdmlConverter;

import java.util.Arrays;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

/**
 * IDML to HWPX 변환기 CLI 진입점.
 *
 * 사용법:
 *   java -jar converter.jar --analyze <idml-path>
 *   java -jar converter.jar --convert <input-idml> <output-hwpx> [options]
 *   java -jar converter.jar --render-vector <idml-path> <frame-id>
 *
 * 옵션:
 *   --progress           진행률을 JSON으로 출력
 *   --spread-mode        스프레드 단위로 변환
 *   --vector-dpi <dpi>   벡터 렌더링 DPI (기본 150)
 *   --include-images     이미지 포함
 *   --links-directory <path>  이미지 링크 디렉토리
 */
public class ConverterCLI {

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        try {
            if ("--analyze".equals(command)) {
                String idmlPath = args[1];
                IDMLAnalyzer.analyze(idmlPath, System.out);
            } else if ("--convert".equals(command)) {
                runConvert(args);
            } else if ("--render-vector".equals(command)) {
                runRenderVector(args);
            } else if ("--render-image".equals(command)) {
                runRenderImage(args);
            } else if ("--render-master-spread".equals(command)) {
                runRenderMasterSpread(args);
            } else if ("--text-frame-detail".equals(command)) {
                runTextFrameDetail(args);
            } else if ("--hwpx-to-idml".equals(command)) {
                runHwpxToIdml(args);
            } else if ("--create-from-masters".equals(command)) {
                runCreateFromMasters(args);
            } else if ("--validate-idml".equals(command)) {
                String idmlPath = args[1];
                IDMLValidator.Result vr = IDMLValidator.validate(idmlPath);
                System.out.println(vr.toJson());
            } else if ("--extract-schema".equals(command)) {
                String idmlPath = args[1];
                String schema = IDMLSchemaExtractor.extractSchema(idmlPath);
                System.out.println(schema);
            } else if ("--merge".equals(command)) {
                runMerge(args);
            } else {
                printUsage();
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runConvert(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing input or output path");
            printUsage();
            System.exit(1);
        }

        String inputPath = args[1];
        String outputPath = args[2];

        ConvertOptions options = ConvertOptions.defaults();
        ProgressReporter reporter = ProgressReporter.NONE;

        // Parse additional options
        for (int i = 3; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "--progress":
                    reporter = new JsonProgressReporter(System.out);
                    break;
                case "--spread-mode":
                    options = options.spreadBasedConversion(true);
                    break;
                case "--vector-dpi":
                    if (i + 1 < args.length) {
                        options = options.vectorDpi(Integer.parseInt(args[++i]));
                    }
                    break;
                case "--include-images":
                    options = options.includeImages(true);
                    break;
                case "--links-directory":
                    if (i + 1 < args.length) {
                        options = options.linksDirectory(args[++i]);
                    }
                    break;
                case "--start-page":
                    if (i + 1 < args.length) {
                        options = options.startPage(Integer.parseInt(args[++i]));
                    }
                    break;
                case "--end-page":
                    if (i + 1 < args.length) {
                        options = options.endPage(Integer.parseInt(args[++i]));
                    }
                    break;
                default:
                    System.err.println("Unknown option: " + arg);
            }
        }

        // Run conversion
        ConvertResult result = IDMLToHwpxConverter.convert(inputPath, outputPath, options, reporter);

        // If not using progress reporter, report result now
        if (reporter == ProgressReporter.NONE) {
            System.out.println("Conversion completed: " + result.summary());
        }
    }

    /**
     * 벡터 도형을 PNG로 렌더링하고 JSON 결과 출력.
     */
    private static void runRenderVector(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing idml path or frame id");
            printUsage();
            System.exit(1);
        }

        String idmlPath = args[1];
        String frameId = args[2];
        int dpi = 150;

        // DPI 옵션 파싱
        for (int i = 3; i < args.length; i++) {
            if ("--dpi".equals(args[i]) && i + 1 < args.length) {
                dpi = Integer.parseInt(args[++i]);
            }
        }

        // IDML 파일 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        if (idmlDoc == null) {
            outputJsonError("Failed to load IDML file: " + idmlPath);
            System.exit(1);
        }
        IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, dpi);

        // 벡터 도형 찾기
        IDMLVectorShape targetShape = null;
        IDMLPage targetPage = null;

        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                for (IDMLVectorShape shape : spread.getVectorShapesOnPage(page)) {
                    if (frameId.equals(shape.selfId())) {
                        targetShape = shape;
                        targetPage = page;
                        break;
                    }
                }
                if (targetShape != null) break;
            }
            if (targetShape != null) break;
        }

        if (targetShape == null) {
            outputJsonError("Vector shape not found: " + frameId);
            System.exit(1);
        }

        // PNG 렌더링
        IDMLPageRenderer.RenderResult result = renderer.renderVectorToPng(targetShape, targetPage);
        if (result == null) {
            outputJsonError("Failed to render vector shape");
            System.exit(1);
        }

        // Base64 인코딩하여 JSON 출력
        String base64Data = Base64.getEncoder().encodeToString(result.pngData());
        String dataUrl = "data:image/png;base64," + base64Data;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"data_url\": \"").append(dataUrl).append("\",\n");
        json.append("  \"width\": ").append(result.pixelWidth()).append(",\n");
        json.append("  \"height\": ").append(result.pixelHeight()).append(",\n");
        json.append("  \"filename\": \"").append(escapeJson(frameId + ".png")).append("\"\n");
        json.append("}");

        System.out.println(json);
    }

    /**
     * 이미지 프레임을 PNG로 렌더링하고 JSON 결과 출력 (트랜스폼, 클리핑 적용).
     */
    private static void runRenderImage(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing idml path or frame id");
            printUsage();
            System.exit(1);
        }

        String idmlPath = args[1];
        String frameId = args[2];
        int dpi = 150;
        String linksDirectory = null;

        // 옵션 파싱
        for (int i = 3; i < args.length; i++) {
            if ("--dpi".equals(args[i]) && i + 1 < args.length) {
                dpi = Integer.parseInt(args[++i]);
            } else if ("--links-directory".equals(args[i]) && i + 1 < args.length) {
                linksDirectory = args[++i];
            }
        }

        // IDML 파일 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        if (idmlDoc == null) {
            outputJsonError("Failed to load IDML file: " + idmlPath);
            System.exit(1);
        }
        IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, dpi);

        // 이미지 프레임 찾기
        IDMLImageFrame targetFrame = null;
        IDMLPage targetPage = null;

        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                for (IDMLImageFrame frame : spread.getImageFramesOnPage(page)) {
                    if (frameId.equals(frame.selfId())) {
                        targetFrame = frame;
                        targetPage = page;
                        break;
                    }
                }
                if (targetFrame != null) break;
            }
            if (targetFrame != null) break;
        }

        if (targetFrame == null) {
            outputJsonError("Image frame not found: " + frameId);
            System.exit(1);
        }

        // PNG 렌더링 (트랜스폼, 클리핑 적용)
        IDMLPageRenderer.RenderResult result = renderer.renderImageToPng(targetFrame, targetPage, linksDirectory);
        if (result == null) {
            outputJsonError("Failed to render image frame");
            System.exit(1);
        }

        // Base64 인코딩하여 JSON 출력
        String base64Data = Base64.getEncoder().encodeToString(result.pngData());
        String dataUrl = "data:image/png;base64," + base64Data;

        // 파일명 추출
        String filename = targetFrame.linkResourceURI();
        if (filename != null) {
            int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                filename = filename.substring(lastSlash + 1);
            }
            // URL 디코딩
            try {
                filename = java.net.URLDecoder.decode(filename, "UTF-8");
            } catch (Exception e) {
                // ignore
            }
        } else {
            filename = frameId + ".png";
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"data_url\": \"").append(dataUrl).append("\",\n");
        json.append("  \"width\": ").append(result.pixelWidth()).append(",\n");
        json.append("  \"height\": ").append(result.pixelHeight()).append(",\n");
        json.append("  \"filename\": \"").append(escapeJson(filename)).append("\"\n");
        json.append("}");

        System.out.println(json);
    }

    /**
     * 마스터 스프레드를 PNG로 렌더링하고 JSON 결과 출력.
     */
    private static void runRenderMasterSpread(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing idml path or master id");
            printUsage();
            System.exit(1);
        }

        String idmlPath = args[1];
        String masterId = args[2];
        int dpi = 150;
        String linksDirectory = null;

        // 옵션 파싱
        for (int i = 3; i < args.length; i++) {
            if ("--dpi".equals(args[i]) && i + 1 < args.length) {
                dpi = Integer.parseInt(args[++i]);
            } else if ("--links-directory".equals(args[i]) && i + 1 < args.length) {
                linksDirectory = args[++i];
            }
        }

        // IDML 파일 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        if (idmlDoc == null) {
            outputJsonError("Failed to load IDML file: " + idmlPath);
            System.exit(1);
        }

        // 마스터 스프레드 찾기
        IDMLSpread masterSpread = idmlDoc.getMasterSpread(masterId);
        if (masterSpread == null) {
            outputJsonError("Master spread not found: " + masterId);
            System.exit(1);
        }

        if (masterSpread.pages().isEmpty()) {
            outputJsonError("Master spread has no pages: " + masterId);
            System.exit(1);
        }

        // 모든 페이지를 나란히 렌더링
        IDMLPageRenderer renderer = new IDMLPageRenderer(idmlDoc, dpi);
        byte[] pngData = renderer.renderSpreadPages(masterSpread, linksDirectory, true, true);

        // 합산된 이미지 크기 계산
        int gap = (int) Math.ceil(2 * dpi / 72.0);
        int pixelWidth = 0, pixelHeight = 0;
        for (IDMLPage p : masterSpread.pages()) {
            int pw = (int) Math.ceil(p.widthPoints() * dpi / 72.0);
            int ph = (int) Math.ceil(p.heightPoints() * dpi / 72.0);
            pixelWidth += pw;
            pixelHeight = Math.max(pixelHeight, ph);
        }
        pixelWidth += gap * (masterSpread.pages().size() - 1);

        // Base64 인코딩하여 JSON 출력
        String base64Data = Base64.getEncoder().encodeToString(pngData);
        String dataUrl = "data:image/png;base64," + base64Data;

        // 마스터 이름 추출
        String masterName = masterId;
        if (!masterSpread.pages().isEmpty()) {
            String pageName = masterSpread.pages().get(0).name();
            if (pageName != null && !pageName.isEmpty()) {
                masterName = pageName;
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"data_url\": \"").append(dataUrl).append("\",\n");
        json.append("  \"width\": ").append(pixelWidth).append(",\n");
        json.append("  \"height\": ").append(pixelHeight).append(",\n");
        json.append("  \"filename\": \"").append(escapeJson(masterName + ".png")).append("\",\n");
        json.append("  \"page_count\": ").append(masterSpread.pages().size()).append("\n");
        json.append("}");

        System.out.println(json);
    }

    /**
     * 텍스트 프레임의 상세 정보를 JSON으로 출력 (단락별 텍스트 및 스타일).
     */
    private static void runTextFrameDetail(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing idml path or frame id");
            printUsage();
            System.exit(1);
        }

        String idmlPath = args[1];
        String frameId = args[2];

        // IDML 파일 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        if (idmlDoc == null) {
            outputJsonError("Failed to load IDML file: " + idmlPath);
            System.exit(1);
        }

        // 텍스트 프레임 찾기
        IDMLTextFrame targetFrame = null;
        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLTextFrame tf : spread.textFrames()) {
                if (frameId.equals(tf.selfId())) {
                    targetFrame = tf;
                    break;
                }
            }
            if (targetFrame != null) break;
        }

        if (targetFrame == null) {
            outputJsonError("Text frame not found: " + frameId);
            System.exit(1);
        }

        // Story 가져오기
        IDMLStory story = idmlDoc.getStory(targetFrame.parentStoryId());
        if (story == null) {
            outputJsonError("Story not found for frame: " + frameId);
            System.exit(1);
        }

        // JSON 출력 생성
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"frame_id\": \"").append(escapeJson(frameId)).append("\",\n");
        json.append("  \"story_id\": \"").append(escapeJson(story.selfId())).append("\",\n");

        // Frame outline properties
        json.append("  \"frame_properties\": {\n");
        json.append("    \"fill_color\": ").append(targetFrame.fillColor() != null ? "\"" + escapeJson(targetFrame.fillColor()) + "\"" : "null").append(",\n");
        json.append("    \"stroke_color\": ").append(targetFrame.strokeColor() != null ? "\"" + escapeJson(targetFrame.strokeColor()) + "\"" : "null").append(",\n");
        json.append("    \"stroke_weight\": ").append(targetFrame.strokeWeight()).append(",\n");
        json.append("    \"corner_radius\": ").append(targetFrame.cornerRadius()).append(",\n");

        // Corner radii array (if individual corners are set)
        double[] radii = targetFrame.cornerRadii();
        if (radii != null) {
            json.append("    \"corner_radii\": [").append(radii[0]).append(", ").append(radii[1]).append(", ").append(radii[2]).append(", ").append(radii[3]).append("],\n");
        } else {
            json.append("    \"corner_radii\": null,\n");
        }

        json.append("    \"fill_tint\": ").append(targetFrame.fillTint()).append(",\n");
        json.append("    \"stroke_tint\": ").append(targetFrame.strokeTint()).append(",\n");

        // Frame dimensions
        double[] bounds = targetFrame.geometricBounds();
        if (bounds != null && bounds.length >= 4) {
            double width = bounds[3] - bounds[1];  // right - left
            double height = bounds[2] - bounds[0]; // bottom - top
            json.append("    \"width\": ").append(width).append(",\n");
            json.append("    \"height\": ").append(height).append(",\n");
        } else {
            json.append("    \"width\": 0,\n");
            json.append("    \"height\": 0,\n");
        }

        // Column properties
        json.append("    \"column_count\": ").append(targetFrame.columnCount()).append(",\n");
        json.append("    \"column_gutter\": ").append(targetFrame.columnGutter()).append(",\n");
        json.append("    \"column_type\": \"").append(escapeJson(targetFrame.columnType())).append("\",\n");
        json.append("    \"column_fixed_width\": ").append(targetFrame.columnFixedWidth()).append(",\n");

        // Column widths array
        double[] colWidths = targetFrame.columnWidths();
        if (colWidths != null) {
            json.append("    \"column_widths\": [");
            for (int i = 0; i < colWidths.length; i++) {
                if (i > 0) json.append(", ");
                json.append(colWidths[i]);
            }
            json.append("],\n");
        } else {
            json.append("    \"column_widths\": null,\n");
        }

        // Vertical justification
        json.append("    \"vertical_justification\": \"").append(escapeJson(targetFrame.verticalJustification())).append("\",\n");

        // Ignore text wrap
        json.append("    \"ignore_wrap\": ").append(targetFrame.ignoreWrap()).append(",\n");

        // Column rule
        json.append("    \"use_column_rule\": ").append(targetFrame.useColumnRule()).append(",\n");
        json.append("    \"column_rule_width\": ").append(targetFrame.columnRuleWidth()).append(",\n");
        json.append("    \"column_rule_type\": \"").append(escapeJson(targetFrame.columnRuleType())).append("\",\n");
        json.append("    \"column_rule_color\": ").append(targetFrame.columnRuleColor() != null ? "\"" + escapeJson(targetFrame.columnRuleColor()) + "\"" : "null").append(",\n");
        json.append("    \"column_rule_tint\": ").append(targetFrame.columnRuleTint()).append(",\n");
        json.append("    \"column_rule_offset\": ").append(targetFrame.columnRuleOffset()).append(",\n");
        json.append("    \"column_rule_inset_width\": ").append(targetFrame.columnRuleInsetWidth()).append("\n");
        json.append("  },\n");

        json.append("  \"paragraphs\": [\n");

        boolean firstPara = true;
        for (IDMLParagraph para : story.paragraphs()) {
            if (!firstPara) json.append(",\n");
            firstPara = false;

            json.append("    {\n");

            // 단락 스타일 정보
            String paraStyleRef = para.appliedParagraphStyle();
            IDMLStyleDef paraStyle = idmlDoc.getParagraphStyle(paraStyleRef);

            json.append("      \"style_name\": \"").append(escapeJson(getStyleName(paraStyleRef))).append("\",\n");
            json.append("      \"style_ref\": \"").append(escapeJson(paraStyleRef != null ? paraStyleRef : "")).append("\",\n");

            // 스타일 속성
            json.append("      \"style\": {\n");
            if (paraStyle != null) {
                json.append("        \"font_family\": ").append(paraStyle.fontFamily() != null ? "\"" + escapeJson(paraStyle.fontFamily()) + "\"" : "null").append(",\n");
                json.append("        \"font_size\": ").append(paraStyle.fontSize() != null ? paraStyle.fontSize() : "null").append(",\n");
                json.append("        \"text_alignment\": ").append(paraStyle.textAlignment() != null ? "\"" + escapeJson(paraStyle.textAlignment()) + "\"" : "null").append(",\n");
                json.append("        \"first_line_indent\": ").append(paraStyle.firstLineIndent() != null ? paraStyle.firstLineIndent() : "null").append(",\n");
                json.append("        \"left_indent\": ").append(paraStyle.leftIndent() != null ? paraStyle.leftIndent() : "null").append(",\n");
                json.append("        \"space_before\": ").append(paraStyle.spaceBefore() != null ? paraStyle.spaceBefore() : "null").append(",\n");
                json.append("        \"space_after\": ").append(paraStyle.spaceAfter() != null ? paraStyle.spaceAfter() : "null").append(",\n");
                json.append("        \"leading\": ").append(paraStyle.leading() != null ? paraStyle.leading() : "null").append("\n");
            } else {
                json.append("        \"font_family\": null,\n");
                json.append("        \"font_size\": null,\n");
                json.append("        \"text_alignment\": null,\n");
                json.append("        \"first_line_indent\": null,\n");
                json.append("        \"left_indent\": null,\n");
                json.append("        \"space_before\": null,\n");
                json.append("        \"space_after\": null,\n");
                json.append("        \"leading\": null\n");
            }
            json.append("      },\n");

            // 인라인 단락 속성 (로컬 오버라이드)
            json.append("      \"inline\": {\n");
            json.append("        \"justification\": ").append(para.justification() != null ? "\"" + escapeJson(para.justification()) + "\"" : "null").append(",\n");
            json.append("        \"first_line_indent\": ").append(para.firstLineIndent() != null ? para.firstLineIndent() : "null").append(",\n");
            json.append("        \"left_indent\": ").append(para.leftIndent() != null ? para.leftIndent() : "null").append(",\n");
            json.append("        \"right_indent\": ").append(para.rightIndent() != null ? para.rightIndent() : "null").append(",\n");
            json.append("        \"space_before\": ").append(para.spaceBefore() != null ? para.spaceBefore() : "null").append(",\n");
            json.append("        \"space_after\": ").append(para.spaceAfter() != null ? para.spaceAfter() : "null").append(",\n");
            json.append("        \"leading\": ").append(para.leading() != null ? para.leading() : "null").append(",\n");
            json.append("        \"tracking\": ").append(para.tracking() != null ? para.tracking() : "null").append("\n");
            json.append("      },\n");

            // 단락 음영 (Paragraph Shading)
            json.append("      \"shading\": {\n");
            json.append("        \"on\": ").append(para.shadingOn()).append(",\n");
            json.append("        \"color\": ").append(para.shadingColor() != null ? "\"" + escapeJson(para.shadingColor()) + "\"" : "null").append(",\n");
            json.append("        \"tint\": ").append(para.shadingTint() != null ? para.shadingTint() : "null").append(",\n");
            json.append("        \"width\": ").append(para.shadingWidth() != null ? "\"" + escapeJson(para.shadingWidth()) + "\"" : "null").append(",\n");
            json.append("        \"offset_left\": ").append(para.shadingOffsetLeft() != null ? para.shadingOffsetLeft() : "null").append(",\n");
            json.append("        \"offset_right\": ").append(para.shadingOffsetRight() != null ? para.shadingOffsetRight() : "null").append(",\n");
            json.append("        \"offset_top\": ").append(para.shadingOffsetTop() != null ? para.shadingOffsetTop() : "null").append(",\n");
            json.append("        \"offset_bottom\": ").append(para.shadingOffsetBottom() != null ? para.shadingOffsetBottom() : "null").append("\n");
            json.append("      },\n");

            // 문자 런 정보
            json.append("      \"runs\": [\n");
            boolean firstRun = true;
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (!firstRun) json.append(",\n");
                firstRun = false;

                json.append("        {\n");
                json.append("          \"text\": \"").append(escapeJson(run.content() != null ? run.content() : "")).append("\",\n");

                // 문자 스타일
                String charStyleRef = run.appliedCharacterStyle();
                json.append("          \"char_style\": ").append(charStyleRef != null && !charStyleRef.contains("[No character style]") ? "\"" + escapeJson(getStyleName(charStyleRef)) + "\"" : "null").append(",\n");

                // 인라인 속성
                json.append("          \"font_family\": ").append(run.fontFamily() != null ? "\"" + escapeJson(run.fontFamily()) + "\"" : "null").append(",\n");
                json.append("          \"font_size\": ").append(run.fontSize() != null ? run.fontSize() : "null").append(",\n");
                json.append("          \"font_style\": ").append(run.fontStyle() != null ? "\"" + escapeJson(run.fontStyle()) + "\"" : "null").append(",\n");
                json.append("          \"fill_color\": ").append(run.fillColor() != null ? "\"" + escapeJson(run.fillColor()) + "\"" : "null").append(",\n");

                // 앵커 오브젝트
                json.append("          \"anchors\": [");
                boolean firstAnchor = true;
                for (IDMLTextFrame anchor : run.inlineFrames()) {
                    if (!firstAnchor) json.append(", ");
                    firstAnchor = false;
                    json.append("\"").append(escapeJson(anchor.selfId())).append("\"");
                }
                json.append("]\n");

                json.append("        }");
            }
            json.append("\n      ],\n");

            // 전체 텍스트
            json.append("      \"text\": \"").append(escapeJson(para.getPlainText() != null ? para.getPlainText() : "")).append("\"\n");
            json.append("    }");
        }

        json.append("\n  ]\n");
        json.append("}");

        System.out.println(json);
    }

    /**
     * HWPX를 IDML로 변환.
     */
    private static void runHwpxToIdml(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing input or output path");
            printUsage();
            System.exit(1);
        }

        String inputPath = args[1];
        String outputPath = args[2];
        boolean useProgressJson = false;

        // Parse options
        for (int i = 3; i < args.length; i++) {
            if ("--progress".equals(args[i])) {
                useProgressJson = true;
            }
        }

        try {
            if (useProgressJson) {
                // 진행률 JSON 출력
                System.out.println("{\"type\": \"progress\", \"current\": 1, \"total\": 4, \"message\": \"Loading HWPX file...\"}");
            }

            kr.dogfoot.hwpxlib.tool.hwpxconverter.ConvertResult result =
                    HwpxToIdmlConverter.convert(inputPath, outputPath);

            if (useProgressJson) {
                // 완료 결과 JSON 출력 (Rust ConvertResult 형식에 맞춤)
                StringBuilder json = new StringBuilder();
                json.append("{\"type\": \"complete\", \"result\": {");
                json.append("\"pages_converted\": ").append(result.pageCount());
                json.append(", \"frames_converted\": 0");
                json.append(", \"images_converted\": 0");
                json.append(", \"warnings\": [");
                boolean first = true;
                for (String warning : result.warnings()) {
                    if (!first) json.append(", ");
                    first = false;
                    json.append("\"").append(escapeJson(warning)).append("\"");
                }
                json.append("]}}");
                System.out.println(json);
            } else {
                System.out.println("Conversion completed: " + result.summary());
                if (result.hasWarnings()) {
                    System.out.println("Warnings:");
                    for (String warning : result.warnings()) {
                        System.out.println("  - " + warning);
                    }
                }
            }
        } catch (kr.dogfoot.hwpxlib.tool.hwpxconverter.ConvertException e) {
            if (useProgressJson) {
                System.out.println("{\"type\": \"error\", \"message\": \"" + escapeJson(e.getMessage()) + "\"}");
            } else {
                System.err.println("Conversion failed [" + e.phase() + "]: " + e.getMessage());
                if (e.getCause() != null) {
                    e.getCause().printStackTrace(System.err);
                }
            }
            System.exit(2);
        }
    }

    /**
     * 소스 IDML에서 마스터 스프레드를 복사하여 빈 IDML 생성.
     */
    private static void runCreateFromMasters(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing source or output path");
            printUsage();
            System.exit(1);
        }

        String sourcePath = args[1];
        String outputPath = args[2];
        List<String> masterIds = null;
        List<String> pageSpecs = null;
        List<double[]> textFrameSpecs = null;
        int inlineCount = 0;
        String tfMode = "master";
        boolean doValidate = false;

        for (int i = 3; i < args.length; i++) {
            if ("--masters".equals(args[i]) && i + 1 < args.length) {
                String idsStr = args[++i];
                masterIds = Arrays.asList(idsStr.split(","));
            } else if ("--pages".equals(args[i]) && i + 1 < args.length) {
                String pagesStr = args[++i];
                pageSpecs = Arrays.asList(pagesStr.split(","));
            } else if ("--text-frames".equals(args[i]) && i + 1 < args.length) {
                String tfStr = args[++i];
                textFrameSpecs = new ArrayList<double[]>();
                for (String spec : tfStr.split(",")) {
                    if ("none".equalsIgnoreCase(spec.trim())) {
                        textFrameSpecs.add(null);
                    } else if ("auto".equalsIgnoreCase(spec.trim())) {
                        textFrameSpecs.add(new double[]{-1, -1});
                    } else {
                        String[] parts = spec.trim().split("x");
                        if (parts.length == 2) {
                            textFrameSpecs.add(new double[]{
                                    Double.parseDouble(parts[0]),
                                    Double.parseDouble(parts[1])});
                        } else {
                            textFrameSpecs.add(new double[]{-1, -1});
                        }
                    }
                }
            } else if ("--inline-count".equals(args[i]) && i + 1 < args.length) {
                inlineCount = Integer.parseInt(args[++i]);
            } else if ("--tf-mode".equals(args[i]) && i + 1 < args.length) {
                tfMode = args[++i];
            } else if ("--validate".equals(args[i])) {
                doValidate = true;
            }
        }

        IDMLTemplateCreator.CreateResult result = IDMLTemplateCreator.create(
                sourcePath, outputPath, masterIds, pageSpecs, textFrameSpecs, inlineCount, tfMode);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"success\": ").append(result.success()).append(",\n");
        json.append("  \"master_count\": ").append(result.masterCount()).append(",\n");
        json.append("  \"page_count\": ").append(result.pageCount()).append(",\n");
        json.append("  \"page_size\": {\"width\": ").append(result.pageWidth())
                .append(", \"height\": ").append(result.pageHeight()).append("}");

        if (!result.warnings().isEmpty()) {
            json.append(",\n  \"warnings\": [");
            boolean first = true;
            for (String w : result.warnings()) {
                if (!first) json.append(", ");
                first = false;
                json.append("\"").append(escapeJson(w)).append("\"");
            }
            json.append("]");
        }

        if (doValidate) {
            IDMLTemplateCreator.ValidationResult vr = IDMLTemplateCreator.validate(outputPath);
            json.append(",\n  \"validation\": {\n");
            json.append("    \"valid\": ").append(vr.valid()).append(",\n");
            json.append("    \"errors\": [");
            boolean first = true;
            for (String e : vr.errors()) {
                if (!first) json.append(", ");
                first = false;
                json.append("\"").append(escapeJson(e)).append("\"");
            }
            json.append("],\n");
            json.append("    \"warnings\": [");
            first = true;
            for (String w : vr.warnings()) {
                if (!first) json.append(", ");
                first = false;
                json.append("\"").append(escapeJson(w)).append("\"");
            }
            json.append("]\n");
            json.append("  }");
        }

        json.append("\n}");
        System.out.println(json);
    }

    /**
     * --merge <source.idml> <data.json> <output.idml> [--validate]
     */
    private static void runMerge(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: --merge <source.idml> <data.json> <output.idml> [--validate]");
            System.exit(1);
        }

        String sourcePath = args[1];
        String dataPath = args[2];
        String outputPath = args[3];
        boolean validate = false;
        for (int i = 4; i < args.length; i++) {
            if ("--validate".equals(args[i])) validate = true;
        }

        String dataJson = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dataPath)), "UTF-8");
        IDMLTemplateCreator.CreateResult result = IDMLTemplateCreator.createFromData(sourcePath, outputPath, dataJson);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"success\": ").append(result.success()).append(",\n");
        json.append("  \"master_count\": ").append(result.masterCount()).append(",\n");
        json.append("  \"page_count\": ").append(result.pageCount()).append(",\n");
        json.append("  \"page_size\": {\"width\": ").append(result.pageWidth()).append(", \"height\": ").append(result.pageHeight()).append("}");

        if (validate) {
            IDMLTemplateCreator.ValidationResult vr = IDMLTemplateCreator.validate(outputPath);
            json.append(",\n  \"validation\": {\n");
            json.append("    \"valid\": ").append(vr.valid()).append(",\n");
            json.append("    \"errors\": [");
            for (int i = 0; i < vr.errors().size(); i++) {
                if (i > 0) json.append(", ");
                json.append("\"").append(vr.errors().get(i).replace("\"", "\\\"")).append("\"");
            }
            json.append("],\n    \"warnings\": [");
            for (int i = 0; i < vr.warnings().size(); i++) {
                if (i > 0) json.append(", ");
                json.append("\"").append(vr.warnings().get(i).replace("\"", "\\\"")).append("\"");
            }
            json.append("]\n  }");
        }

        json.append("\n}");
        System.out.println(json);
    }

    private static String getStyleName(String styleRef) {
        if (styleRef == null) return "";
        try {
            String decoded = java.net.URLDecoder.decode(styleRef, "UTF-8");
            int idx = decoded.lastIndexOf('/');
            return idx >= 0 ? decoded.substring(idx + 1) : decoded;
        } catch (Exception e) {
            int idx = styleRef.lastIndexOf('/');
            return idx >= 0 ? styleRef.substring(idx + 1) : styleRef;
        }
    }

    private static void outputJsonError(String message) {
        System.out.println("{\"error\": \"" + escapeJson(message) + "\"}");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void printUsage() {
        System.out.println("IDML / HWPX Converter");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar converter.jar --analyze <idml-path>");
        System.out.println("  java -jar converter.jar --convert <input-idml> <output-hwpx> [options]");
        System.out.println("  java -jar converter.jar --hwpx-to-idml <input-hwpx> <output-idml> [--progress]");
        System.out.println("  java -jar converter.jar --render-vector <idml-path> <frame-id> [--dpi <dpi>]");
        System.out.println();
        System.out.println("IDML to HWPX Options:");
        System.out.println("  --progress           Output progress as JSON");
        System.out.println("  --spread-mode        Convert by spread (default: by page)");
        System.out.println("  --vector-dpi <dpi>   Vector rendering DPI (default: 150)");
        System.out.println("  --include-images     Include images in output");
        System.out.println("  --links-directory <path>  Directory for image links");
        System.out.println("  --start-page <num>   Start page number (1-based)");
        System.out.println("  --end-page <num>     End page number (1-based)");
        System.out.println();
        System.out.println("HWPX to IDML Options:");
        System.out.println("  --progress           Output progress as JSON");
        System.out.println();
        System.out.println("Create from Masters:");
        System.out.println("  java -jar converter.jar --create-from-masters <source-idml> <output-idml> [options]");
        System.out.println("  --masters id1,id2    Master spread IDs to copy (default: all)");
        System.out.println("  --validate           Validate created IDML");
    }
}
