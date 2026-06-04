package kr.dogfoot.hwpxlib.tool.hwpxconverter;

/**
 * HWPX → IDML 변환기.
 * HWPX 파일을 IDML(InDesign Markup Language) 형식으로 변환한다.
 */
public class HwpxToIdmlConverter {

    /**
     * HWPX 파일을 IDML 파일로 변환한다.
     *
     * @param inputHwpxPath  입력 HWPX 파일 경로
     * @param outputIdmlPath 출력 IDML 파일 경로
     * @return 변환 결과
     * @throws ConvertException 변환 실패 시
     */
    public static ConvertResult convert(String inputHwpxPath, String outputIdmlPath) throws ConvertException {
        ConvertResult result = new ConvertResult();

        try {
            // TODO: HWPX → IDML 변환 구현
            result.addWarning("HWPX to IDML conversion is not yet implemented");
            result.pageCount(0);
        } catch (Exception e) {
            throw new ConvertException("conversion", "Failed to convert HWPX to IDML: " + e.getMessage(), e);
        }

        return result;
    }
}
