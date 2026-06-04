package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.writer.HWPXWriter;
// HwpxToHwpConverter는 리플렉션으로 호출 (hwpexport 패키지 컴파일 제외 시 대응)
import kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer.IDMLAnalyzer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBundleReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBundleWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSerializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IDMLPageRenderer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.legacy.IDMLNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.devtool.IDMLTemplateCreator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.devtool.IDMLValidator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.devtool.IDMLSchemaExtractor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.devtool.CoordinateDiagnoser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedDataReader;
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
 *   (Links 폴더는 IDML 옆에서 자동 감지)
 */
public class ConverterCLI {

    public static void main(String[] args) {
        // .env 파일 자동 로드 (JAR 디렉토리 → CWD 순)
        kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader env =
                kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader.load();
        if (!env.isEmpty()) {
            System.out.println("[CLI] .env 로드 완료: " + env.size() + "개 키");
        }

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
            } else if ("--spread-tree".equals(command)) {
                runSpreadTree(args);
            } else if ("--export-ast".equals(command)) {
                runExportAst(args);
            } else if ("--extract-ast".equals(command)) {
                runExtractAst(args);
            } else if ("--convert-ast".equals(command)) {
                runConvertAst(args);
            } else if ("--diagnose".equals(command)) {
                runDiagnose(args);
            } else if ("--analyze-fonts".equals(command)) {
                runAnalyzeFonts(args);
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

        // --teach 관련 옵션
        String teachPromptPath  = null;
        String teachExtraPath   = null;
        String teachDir         = null;
        String textbookId       = null;

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
                case "--margin-guide":
                    options = options.drawMarginGuide(true);
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
                case "--layout-mode":
                    if (i + 1 < args.length) {
                        options = options.layoutMode(args[++i]);
                    }
                    break;
                case "--resolved":
                    if (i + 1 < args.length) {
                        options = options.resolvedJsonPath(args[++i]);
                    }
                    break;
                case "--font-map":
                    if (i + 1 < args.length) {
                        options = options.fontMapPath(args[++i]);
                    }
                    break;
                case "--config":
                    if (i + 1 < args.length) {
                        options = options.configPath(args[++i]);
                    }
                    break;
                case "--links-directory":
                    if (i + 1 < args.length) {
                        options = options.linksDirectory(args[++i]);
                    }
                    break;
                case "--debug-ast":
                    // SPEC-015: AST 디버그 메타데이터 출력 활성화 (export-ast 시 의미 있음)
                    options = options.debugAst(true);
                    break;
                case "--extract-semantics":
                    // SPEC-018 M3: 변환 후 시멘틱 레이어 JSON 자동 추출
                    options = options.extractSemantics(true);
                    break;
                case "--semantic-schema":
                    if (i + 1 < args.length) {
                        // 단순 ID(common, math-reference-v1) | classpath 경로 | 절대/상대 파일 경로
                        options = options.semanticSchema(args[++i]);
                    }
                    break;
                case "--semantic-output":
                    if (i + 1 < args.length) {
                        // 미지정 시 hwpx 옆에 .semantic.json
                        options = options.semanticOutput(args[++i]);
                    }
                    break;
                case "--teach":
                    if (i + 1 < args.length) teachPromptPath = args[++i];
                    break;
                case "--teach-extra":
                    if (i + 1 < args.length) teachExtraPath = args[++i];
                    break;
                case "--teach-dir":
                    if (i + 1 < args.length) teachDir = args[++i];
                    break;
                case "--textbook-id":
                    if (i + 1 < args.length) textbookId = args[++i];
                    break;
                default:
                    System.err.println("Unknown option: " + arg);
            }
        }

        // --config 미지정 시 자동 탐색: JAR 위치 → 현재 디렉토리 → 입력 파일 디렉토리
        if (options.configPath() == null) {
            String[] searchPaths = {
                    // JAR 파일과 같은 디렉토리
                    new java.io.File(ConverterCLI.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(),
                    // 현재 작업 디렉토리
                    System.getProperty("user.dir"),
                    // 입력 파일 디렉토리
                    new java.io.File(inputPath).getParent()
            };
            for (String dir : searchPaths) {
                if (dir == null) continue;
                java.io.File configFile = new java.io.File(dir, "conversion-config.json");
                if (configFile.exists()) {
                    options = options.configPath(configFile.getAbsolutePath());
                    System.out.println("[CLI] Auto-detected config: " + configFile.getAbsolutePath());
                    break;
                }
            }
        }

        // --teach → 교수자료 JSON 생성
        boolean isTeachMode = teachPromptPath != null || teachDir != null;
        if (isTeachMode && outputPath.toLowerCase().endsWith(".json")) {
            ASTDocument teachAst = IDMLToHwpxConverter.buildAst(inputPath, options, reporter);
            kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader envR =
                    kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader.load();
            kr.dogfoot.hwpxlib.tool.idmlconverter.llm.LLMConfig llmCfg =
                    kr.dogfoot.hwpxlib.tool.idmlconverter.llm.LLMConfig.builder()
                            .groqApiKey(envR.getOrEnv(
                                    kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader.GROQ_API_KEY))
                            .anthropicApiKey(envR.getOrEnv(
                                    kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader.ANTHROPIC_API_KEY))
                            .build();
            String resolvedPrompt = teachDir != null
                    ? kr.dogfoot.hwpxlib.tool.idmlconverter.llm.TeachingPromptLoader
                            .loadFromDir(teachDir, textbookId)
                    : kr.dogfoot.hwpxlib.tool.idmlconverter.llm.TeachingPromptLoader
                            .load(teachPromptPath, teachExtraPath);
            kr.dogfoot.hwpxlib.tool.idmlconverter.llm.TeachingMaterial material =
                    kr.dogfoot.hwpxlib.tool.idmlconverter.llm.TeachingMaterialGenerator
                            .generate(teachAst, resolvedPrompt, llmCfg);
            kr.dogfoot.hwpxlib.tool.idmlconverter.llm.TeachingMaterialWriter
                    .write(material, outputPath);
            System.out.println("Teaching material 생성 완료: " + outputPath);
            return;
        }

        // .md 확장자 감지 → Markdown 내보내기
        if (outputPath.toLowerCase().endsWith(".md")) {
            ASTDocument mdAst = IDMLToHwpxConverter.buildAst(inputPath, options, reporter);
            try {
                new kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToMarkdownConverter()
                        .convert(mdAst, new java.io.File(outputPath));
            } catch (java.io.IOException e) {
                throw new RuntimeException("Markdown 내보내기 실패: " + e.getMessage(), e);
            }
            System.out.println("Markdown export completed: " + outputPath);
            return;
        }

        // .hwp 확장자 감지
        boolean exportHwp = outputPath.toLowerCase().endsWith(".hwp");
        String hwpxPath = exportHwp
                ? outputPath.substring(0, outputPath.length() - 4) + ".hwpx"
                : outputPath;

        // Run conversion (IDML → HWPX)
        ConvertResult result = IDMLToHwpxConverter.convert(inputPath, hwpxPath, options, reporter);

        // HWP 내보내기
        if (exportHwp) {
            try {
                if (reporter != ProgressReporter.NONE) {
                    reporter.reportProgress(97, 100, "HWP 파일 변환 중...");
                }
                Class<?> cls = Class.forName("kr.dogfoot.hwpxlib.tool.idmlconverter.hwpexport.HwpxToHwpConverter");
                cls.getMethod("toFile", String.class, String.class).invoke(null, result.hwpxFile(), outputPath);
                if (reporter == ProgressReporter.NONE) {
                    System.out.println("HWP export: " + outputPath);
                }
            } catch (Exception e) {
                System.err.println("HWP export failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        // If not using progress reporter, report result now
        if (reporter == ProgressReporter.NONE) {
            System.out.println("Conversion completed: " + result.summary());
            if (result.hasWarnings()) {
                System.out.println("Warnings (" + result.warnings().size() + "):");
                for (String warning : result.summarizedWarnings()) {
                    System.out.println("  - " + warning);
                }
            }
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

        // 옵션 파싱
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
        IDMLPageRenderer.RenderResult result = renderer.renderImageToPng(targetFrame, targetPage, null);
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

        // 옵션 파싱
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
        byte[] pngData = renderer.renderSpreadPages(masterSpread, null, true, true);

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

    /**
     * 스프레드의 원본 XML 계층 트리 구조를 JSON으로 출력.
     * --spread-tree <idml-path> <spread-index|master-id>
     */
    private static void runSpreadTree(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing idml path or spread identifier");
            System.exit(1);
        }
        String idmlPath = args[1];
        String spreadId = args[2];

        // IDML zip에서 스프레드 XML 직접 읽기
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        org.w3c.dom.Element spreadElem = null;

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(idmlPath)) {
            // designmap.xml에서 스프레드 소스 목록 구하기
            java.util.zip.ZipEntry designEntry = zip.getEntry("designmap.xml");
            org.w3c.dom.Document designDoc = factory.newDocumentBuilder().parse(zip.getInputStream(designEntry));
            org.w3c.dom.Element root = designDoc.getDocumentElement();

            String targetEntry = null;

            // master ID로 찾기 (예: ue9)
            org.w3c.dom.NodeList masterNodes = root.getElementsByTagName("idPkg:MasterSpread");
            for (int i = 0; i < masterNodes.getLength(); i++) {
                String src = ((org.w3c.dom.Element) masterNodes.item(i)).getAttribute("src");
                if (src != null && src.contains(spreadId)) {
                    targetEntry = src;
                    break;
                }
            }

            // 일반 스프레드: 인덱스 또는 ID로 찾기
            if (targetEntry == null) {
                List<String> spreadSources = new ArrayList<>();
                org.w3c.dom.NodeList spreadNodes = root.getElementsByTagName("idPkg:Spread");
                for (int i = 0; i < spreadNodes.getLength(); i++) {
                    String src = ((org.w3c.dom.Element) spreadNodes.item(i)).getAttribute("src");
                    if (src != null) spreadSources.add(src);
                }

                try {
                    int idx = Integer.parseInt(spreadId);
                    if (idx >= 0 && idx < spreadSources.size()) {
                        targetEntry = spreadSources.get(idx);
                    }
                } catch (NumberFormatException e) {
                    for (String src : spreadSources) {
                        if (src.contains(spreadId)) {
                            targetEntry = src;
                            break;
                        }
                    }
                }
            }

            if (targetEntry == null) {
                outputJsonError("Spread not found: " + spreadId);
                System.exit(1);
            }

            java.util.zip.ZipEntry spreadEntry = zip.getEntry(targetEntry);
            if (spreadEntry == null) {
                outputJsonError("Spread file not found in IDML: " + targetEntry);
                System.exit(1);
            }

            org.w3c.dom.Document spreadDoc = factory.newDocumentBuilder().parse(zip.getInputStream(spreadEntry));
            spreadElem = spreadDoc.getDocumentElement();

            // idPkg:MasterSpread / idPkg:Spread 래퍼인 경우 내부 MasterSpread/Spread 요소 추출
            String rootTag = spreadElem.getTagName();
            if (rootTag.startsWith("idPkg:")) {
                org.w3c.dom.NodeList inner = spreadElem.getChildNodes();
                for (int i = 0; i < inner.getLength(); i++) {
                    if (inner.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                        spreadElem = (org.w3c.dom.Element) inner.item(i);
                        break;
                    }
                }
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"id\": \"").append(escapeJson(spreadElem.getAttribute("Self"))).append("\",\n");
        json.append("  \"type\": \"").append(escapeJson(spreadElem.getTagName())).append("\",\n");
        json.append("  \"children\": [\n");

        boolean first = true;
        org.w3c.dom.NodeList children = spreadElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element elem = (org.w3c.dom.Element) child;
            String tag = elem.getTagName();

            // 주요 객체만 포함 (Page, TextFrame, Group, Rectangle, Polygon, Oval, GraphicLine)
            if (!"Page".equals(tag) && !"TextFrame".equals(tag) && !"Group".equals(tag)
                    && !"Rectangle".equals(tag) && !"Polygon".equals(tag)
                    && !"Oval".equals(tag) && !"GraphicLine".equals(tag)) {
                continue;
            }

            if (!first) json.append(",\n");
            first = false;
            buildTreeNode(json, elem, "    ");
        }

        json.append("\n  ]\n");
        json.append("}");
        System.out.println(json);
    }

    /**
     * XML 요소를 트리 노드 JSON으로 변환 (재귀).
     */
    private static void buildTreeNode(StringBuilder json, org.w3c.dom.Element elem, String indent) {
        String tag = elem.getTagName();
        String selfId = elem.getAttribute("Self");
        String name = elem.getAttribute("Name");

        json.append(indent).append("{\n");
        json.append(indent).append("  \"type\": \"").append(escapeJson(tag)).append("\",\n");
        json.append(indent).append("  \"id\": \"").append(escapeJson(selfId != null ? selfId : "")).append("\"");

        if (name != null && !name.isEmpty() && !"$ID/".equals(name)) {
            json.append(",\n").append(indent).append("  \"name\": \"").append(escapeJson(name)).append("\"");
        }

        // 도형 속성
        String fillColor = elem.getAttribute("FillColor");
        if (fillColor != null && !fillColor.isEmpty()) {
            json.append(",\n").append(indent).append("  \"fillColor\": \"").append(escapeJson(fillColor)).append("\"");
        }
        String strokeColor = elem.getAttribute("StrokeColor");
        if (strokeColor != null && !strokeColor.isEmpty()) {
            json.append(",\n").append(indent).append("  \"strokeColor\": \"").append(escapeJson(strokeColor)).append("\"");
        }

        // 텍스트프레임: story 참조
        if ("TextFrame".equals(tag)) {
            String storyId = elem.getAttribute("ParentStory");
            if (storyId != null && !storyId.isEmpty()) {
                json.append(",\n").append(indent).append("  \"storyId\": \"").append(escapeJson(storyId)).append("\"");
            }
        }

        // 이미지 포함 여부
        if ("Rectangle".equals(tag) || "Polygon".equals(tag) || "Oval".equals(tag)) {
            org.w3c.dom.NodeList images = elem.getElementsByTagName("Image");
            if (images.getLength() > 0) {
                json.append(",\n").append(indent).append("  \"hasImage\": true");
                org.w3c.dom.NodeList links = elem.getElementsByTagName("Link");
                if (links.getLength() > 0) {
                    String linkUri = ((org.w3c.dom.Element) links.item(0)).getAttribute("LinkResourceURI");
                    if (linkUri != null && !linkUri.isEmpty()) {
                        // 파일명만 추출
                        String fileName = linkUri;
                        int lastSlash = linkUri.lastIndexOf('/');
                        if (lastSlash >= 0) fileName = linkUri.substring(lastSlash + 1);
                        json.append(",\n").append(indent).append("  \"imageFile\": \"").append(escapeJson(fileName)).append("\"");
                    }
                }
            }
        }

        // bounds
        String bounds = elem.getAttribute("GeometricBounds");
        if (bounds != null && !bounds.isEmpty()) {
            json.append(",\n").append(indent).append("  \"bounds\": \"").append(escapeJson(bounds)).append("\"");
        }

        // transform
        String transform = elem.getAttribute("ItemTransform");
        if (transform != null && !transform.isEmpty()) {
            json.append(",\n").append(indent).append("  \"transform\": \"").append(escapeJson(transform)).append("\"");
        }

        // 자식 요소 (Group, Rectangle 등의 내부 중첩)
        List<org.w3c.dom.Element> childElements = new ArrayList<>();
        org.w3c.dom.NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element childElem = (org.w3c.dom.Element) child;
            String childTag = childElem.getTagName();
            if ("TextFrame".equals(childTag) || "Group".equals(childTag)
                    || "Rectangle".equals(childTag) || "Polygon".equals(childTag)
                    || "Oval".equals(childTag) || "GraphicLine".equals(childTag)) {
                childElements.add(childElem);
            }
        }

        if (!childElements.isEmpty()) {
            json.append(",\n").append(indent).append("  \"children\": [\n");
            boolean first = true;
            for (org.w3c.dom.Element childElem : childElements) {
                if (!first) json.append(",\n");
                first = false;
                buildTreeNode(json, childElem, indent + "    ");
            }
            json.append("\n").append(indent).append("  ]");
        }

        json.append("\n").append(indent).append("}");
    }



    /**
     * AST 중간 표현을 JSON으로 내보내기.
     * --export-ast <idml-path>
     */
    private static void runExportAst(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Error: Missing IDML path");
            printUsage();
            System.exit(1);
        }

        String idmlPath = args[1];
        boolean debugAst = false;
        // SPEC-015: --debug-ast 플래그 파싱 (resolved 등 추가 옵션은 후속 단계에서 확장)
        for (int i = 2; i < args.length; i++) {
            if ("--debug-ast".equals(args[i])) {
                debugAst = true;
            }
        }

        // IDML 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        if (idmlDoc == null) {
            outputJsonError("Failed to load IDML file: " + idmlPath);
            System.exit(1);
        }

        // 파일명 추출
        String fileName = idmlPath;
        int lastSlash = Math.max(idmlPath.lastIndexOf('/'), idmlPath.lastIndexOf('\\'));
        if (lastSlash >= 0) fileName = idmlPath.substring(lastSlash + 1);

        // 4단계 정규화 → AST (이미지 포함하여 마스터 페이지 객체도 처리)
        ConvertOptions options = ConvertOptions.defaults().includeImages(true).debugAst(debugAst);
        ASTDocument ast = IDMLNormalizer.normalize(idmlDoc, options, fileName);

        // JSON 직렬화
        String json = ASTSerializer.toJson(ast);
        System.out.println(json);

        // 임시 디렉토리 정리
        idmlDoc.cleanup();
    }


    /**
     * IDML → AST 번들 디렉토리 추출.
     * --extract-ast <idml-path> <output-dir> [options]
     */
    private static void runExtractAst(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing IDML path or output directory");
            printUsage();
            System.exit(1);
        }

        String idmlPath = args[1];
        String outputDirPath = args[2];
        int vectorDpi = 150;
        int startPage = 0;
        int endPage = 0;

        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--vector-dpi":
                    if (i + 1 < args.length) vectorDpi = Integer.parseInt(args[++i]);
                    break;
                case "--start-page":
                    if (i + 1 < args.length) startPage = Integer.parseInt(args[++i]);
                    break;
                case "--end-page":
                    if (i + 1 < args.length) endPage = Integer.parseInt(args[++i]);
                    break;
            }
        }

        // IDML 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        if (idmlDoc == null) {
            outputJsonError("Failed to load IDML file: " + idmlPath);
            System.exit(1);
        }

        String fileName = idmlPath;
        int lastSlash = Math.max(idmlPath.lastIndexOf('/'), idmlPath.lastIndexOf('\\'));
        if (lastSlash >= 0) fileName = idmlPath.substring(lastSlash + 1);

        java.io.File outputDir = new java.io.File(outputDirPath);
        java.io.File imagesDir = new java.io.File(outputDir, "images");
        imagesDir.mkdirs();

        ConvertOptions options = ConvertOptions.defaults()
                .includeImages(true)
                .vectorDpi(vectorDpi)
                .imageCacheDir(imagesDir.getAbsolutePath());
        if (startPage > 0) options = options.startPage(startPage);
        if (endPage > 0) options = options.endPage(endPage);

        ASTDocument ast = IDMLNormalizer.normalize(idmlDoc, options, fileName);
        ASTBundleWriter.write(ast, outputDir);

        System.out.println("AST bundle written to: " + outputDir.getAbsolutePath());
        idmlDoc.cleanup();
    }

    /**
     * AST 번들 디렉토리 → HWPX 변환.
     * --convert-ast <ast-dir> <output-hwpx>
     */
    private static void runConvertAst(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Error: Missing AST directory or output HWPX path");
            printUsage();
            System.exit(1);
        }

        String astDirPath = args[1];
        String hwpxPath = args[2];

        java.io.File astDir = new java.io.File(astDirPath);
        if (!new java.io.File(astDir, "ast.json").exists()) {
            System.err.println("Error: ast.json not found in " + astDirPath);
            System.exit(1);
        }

        ASTDocument ast = ASTBundleReader.read(astDir);
        ConvertResult result = ASTToHwpxConverter.convert(ast);

        HWPXWriter.toFilepath(result.hwpxFile(), hwpxPath);
        System.out.println("Conversion completed: " + result.summary());
    }

    private static void runDiagnose(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Error: Missing IDML path");
            System.err.println("Usage: --diagnose <input.idml> [--resolved <resolved.json>] [--start-page N] [--end-page N]");
            System.exit(1);
        }

        String idmlPath = args[1];
        String resolvedPath = null;
        int startPage = 0;
        int endPage = 0;

        for (int i = 2; i < args.length; i++) {
            if ("--resolved".equals(args[i]) && i + 1 < args.length) {
                resolvedPath = args[++i];
            } else if ("--start-page".equals(args[i]) && i + 1 < args.length) {
                startPage = Integer.parseInt(args[++i]);
            } else if ("--end-page".equals(args[i]) && i + 1 < args.length) {
                endPage = Integer.parseInt(args[++i]);
            }
        }

        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        ResolvedData resolved = null;
        if (resolvedPath != null) {
            resolved = ResolvedDataReader.read(resolvedPath);
        }

        CoordinateDiagnoser.diagnose(idmlDoc, resolved, startPage, endPage, System.out);

        idmlDoc.cleanup();
    }

    /**
     * SPEC-014: IDML 폰트 목록을 추출해 후보 매핑 분석 결과를 JSON으로 출력.
     * Usage: --analyze-fonts &lt;input.idml&gt; [--config &lt;conversion-config.json&gt;]
     */
    private static void runAnalyzeFonts(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Error: Missing IDML path");
            System.err.println("Usage: --analyze-fonts <input.idml> [--config <conversion-config.json>]");
            System.exit(1);
        }
        String idmlPath = args[1];
        String configPath = null;
        for (int i = 2; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            }
        }

        if (configPath == null) {
            // 기본 위치: 작업 디렉토리의 conversion-config.json
            java.io.File def = new java.io.File("conversion-config.json");
            if (def.exists()) configPath = def.getAbsolutePath();
        }
        ConversionConfig config = ConversionConfig.load(configPath);

        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            java.util.List<String> fontNames = new java.util.ArrayList<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLFontDef def : idmlDoc.fonts().values()) {
                String fam = def.fontFamily();
                if (fam != null && !fontNames.contains(fam)) fontNames.add(fam);
            }
            java.util.Collections.sort(fontNames);

            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.font.FontCandidateMatcher.FontAnalysis> analyses =
                    kr.dogfoot.hwpxlib.tool.idmlconverter.font.FontCandidateMatcher.analyzeAll(
                            fontNames, null, config);

            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(java.util.Collections.singletonMap("fonts", analyses)));
        } finally {
            idmlDoc.cleanup();
        }
    }

    private static void printUsage() {
        System.out.println("IDML / HWPX Converter");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar converter.jar --analyze <idml-path>");
        System.out.println("  java -jar converter.jar --convert <input-idml> <output-hwpx> [options]");
        System.out.println("  java -jar converter.jar --hwpx-to-idml <input-hwpx> <output-idml> [--progress]");
        System.out.println("  java -jar converter.jar --export-ast <idml-path>");
        System.out.println("  java -jar converter.jar --extract-ast <input-idml> <output-dir> [options]");
        System.out.println("  java -jar converter.jar --convert-ast <ast-dir> <output-hwpx>");
        System.out.println("  java -jar converter.jar --render-vector <idml-path> <frame-id> [--dpi <dpi>]");
        System.out.println();
        System.out.println("IDML to HWPX Options:");
        System.out.println("  --progress           Output progress as JSON");
        System.out.println("  --spread-mode        Convert by spread (default: by page)");
        System.out.println("  --vector-dpi <dpi>   Vector rendering DPI (default: 150)");
        System.out.println("  --include-images     Include images in output");
        System.out.println("  (Links folder is auto-detected next to IDML file)");
        System.out.println("  --start-page <num>   Start page number (1-based)");
        System.out.println("  --end-page <num>     End page number (1-based)");
        System.out.println("  --debug-ast          Emit AST debug metadata (createdAt, appliedFrom, notes)");
        System.out.println("  --extract-semantics            Extract semantic layer JSON after conversion (SPEC-018)");
        System.out.println("  --semantic-schema <id|path>    Schema id (common, math-reference-v1) or .schema.json path");
        System.out.println("  --semantic-output <path>       Output path for .semantic.json (default: <hwpx>.semantic.json)");
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
