package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IDMLToIntermediateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.IntermediateToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.JsonDeserializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.JsonSerializer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.IDMLNormalizer;

import java.io.File;

/**
 * IDML → HWPX 변환 메인 파사드.
 *
 * 사용 예:
 * <pre>{@code
 * // 직접 변환
 * ConvertResult result = IDMLToHwpxConverter.convert("input.idml", "output.hwpx",
 *                                                     ConvertOptions.defaults());
 *
 * // IDML → JSON
 * String json = IDMLToHwpxConverter.toJson("input.idml", ConvertOptions.defaults());
 *
 * // JSON → HWPX
 * IDMLToHwpxConverter.fromJson(json, "output.hwpx");
 * }</pre>
 *
 * CLI:
 * <pre>
 * java IDMLToHwpxConverter input.idml output.hwpx [startPage] [endPage]
 * </pre>
 */
public class IDMLToHwpxConverter {

    /**
     * IDML 파일을 HWPX 파일로 변환한다.
     *
     * @param idmlPath IDML 파일 경로
     * @param hwpxPath 출력 HWPX 파일 경로
     * @param options  변환 옵션
     * @return 변환 결과 (경고, 통계)
     */
    public static ConvertResult convert(String idmlPath, String hwpxPath,
                                         ConvertOptions options) throws ConvertException {
        return convert(idmlPath, hwpxPath, options, ProgressReporter.NONE);
    }

    /**
     * IDML 파일을 HWPX 파일로 변환한다 (진행률 보고 포함).
     *
     * @param idmlPath IDML 파일 경로
     * @param hwpxPath 출력 HWPX 파일 경로
     * @param options  변환 옵션
     * @param reporter 진행률 보고기
     * @return 변환 결과 (경고, 통계)
     */
    public static ConvertResult convert(String idmlPath, String hwpxPath,
                                         ConvertOptions options,
                                         ProgressReporter reporter) throws ConvertException {
        // Phase 1: IDML 로드
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            ConvertResult result;
            String sourceFileName = new File(idmlPath).getName();

            if (options.useEventStream()) {
                // === 새 파이프라인: 4단계 정규화 → AST → HWPX ===
                System.err.println("[Pipeline] Using event-stream (AST) pipeline");

                // Phase 2: IDML → ASTDocument (4단계 정규화)
                ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName);

                // Phase 3: AST → HWPX
                result = ASTToHwpxConverter.convert(astDoc);
            } else {
                // === 기존 파이프라인: Intermediate → HWPX ===
                // Phase 2: IDML → Intermediate
                IDMLToIntermediateConverter.Result intermediateResult =
                        IDMLToIntermediateConverter.convert(idmlDoc, options, sourceFileName);
                IntermediateDocument intermediate = intermediateResult.document();

                // Phase 3: Intermediate → HWPX
                result = IntermediateToHwpxConverter.convert(intermediate);

                // 중간 변환 경고 전파
                for (String warning : intermediateResult.warnings()) {
                    result.addWarning(warning);
                }
            }

            // Phase 4: HWPX 파일 저장
            try {
                HWPXWriter.toFilepath(result.hwpxFile(), hwpxPath);
            } catch (Exception e) {
                throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                        "Failed to write HWPX file: " + e.getMessage(), e);
            }

            // 변환 완료 보고
            reporter.reportComplete(result);

            return result;
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * IDML 파일을 HWPXFile 객체로 변환한다 (파일 저장 없이).
     *
     * @param idmlPath IDML 파일 경로
     * @param options  변환 옵션
     * @return HWPXFile 객체
     */
    public static HWPXFile convertToHwpxFile(String idmlPath,
                                              ConvertOptions options) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();
            IDMLToIntermediateConverter.Result intermediateResult =
                    IDMLToIntermediateConverter.convert(idmlDoc, options, sourceFileName);
            ConvertResult result = IntermediateToHwpxConverter.convert(intermediateResult.document());
            return result.hwpxFile();
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * IDML 파일을 중간 JSON 문자열로 변환한다.
     *
     * @param idmlPath IDML 파일 경로
     * @param options  변환 옵션
     * @return JSON 문자열
     */
    public static String toJson(String idmlPath, ConvertOptions options) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();
            IDMLToIntermediateConverter.Result intermediateResult =
                    IDMLToIntermediateConverter.convert(idmlDoc, options, sourceFileName);
            return JsonSerializer.toJson(intermediateResult.document());
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * 중간 JSON 문자열을 HWPXFile로 변환한다.
     *
     * @param json JSON 문자열
     * @return HWPXFile 객체
     */
    public static HWPXFile fromJson(String json) throws ConvertException {
        IntermediateDocument intermediate = JsonDeserializer.fromJson(json);
        ConvertResult result = IntermediateToHwpxConverter.convert(intermediate);
        return result.hwpxFile();
    }

    /**
     * 중간 JSON 문자열을 HWPX 파일로 변환하여 저장한다.
     *
     * @param json     JSON 문자열
     * @param hwpxPath 출력 HWPX 파일 경로
     */
    public static void fromJson(String json, String hwpxPath) throws ConvertException {
        HWPXFile hwpxFile = fromJson(json);
        try {
            HWPXWriter.toFilepath(hwpxFile, hwpxPath);
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                    "Failed to write HWPX file: " + e.getMessage(), e);
        }
    }

    /**
     * CLI 메인 메서드.
     *
     * 사용법: java IDMLToHwpxConverter input.idml output.hwpx [startPage] [endPage]
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: IDMLToHwpxConverter <input.idml> <output.hwpx> [startPage] [endPage]");
            System.err.println("  startPage, endPage: 1-based page numbers (0 = no limit)");
            System.exit(1);
            return;
        }

        String idmlPath = args[0];
        String hwpxPath = args[1];
        int startPage = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int endPage = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        ConvertOptions options = ConvertOptions.defaults()
                .startPage(startPage)
                .endPage(endPage);

        try {
            System.out.println("Converting: " + idmlPath + " -> " + hwpxPath);
            if (startPage > 0 || endPage > 0) {
                System.out.println("Page range: " + (startPage > 0 ? startPage : "start")
                        + " ~ " + (endPage > 0 ? endPage : "end"));
            }

            ConvertResult result = convert(idmlPath, hwpxPath, options);

            System.out.println("Conversion completed: " + result.summary());
            if (result.hasWarnings()) {
                System.out.println("Warnings:");
                for (String warning : result.warnings()) {
                    System.out.println("  - " + warning);
                }
            }
        } catch (ConvertException e) {
            System.err.println("Conversion failed [" + e.phase() + "]: " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace(System.err);
            }
            System.exit(2);
        }
    }
}
