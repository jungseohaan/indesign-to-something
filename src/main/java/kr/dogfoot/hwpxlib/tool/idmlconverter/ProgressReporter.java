package kr.dogfoot.hwpxlib.tool.idmlconverter;

/**
 * 변환 진행률 보고 인터페이스.
 */
public interface ProgressReporter {

    /**
     * 진행률 보고.
     *
     * @param current 현재 진행 수
     * @param total   총 항목 수
     * @param message 진행 메시지
     */
    void reportProgress(int current, int total, String message);

    /**
     * 변환 완료 보고.
     *
     * @param result 변환 결과
     */
    void reportComplete(ConvertResult result);

    /**
     * 오류 보고.
     *
     * @param message 오류 메시지
     */
    void reportError(String message);

    /**
     * 아무 것도 하지 않는 기본 구현.
     */
    ProgressReporter NONE = new ProgressReporter() {
        @Override public void reportProgress(int current, int total, String message) {}
        @Override public void reportComplete(ConvertResult result) {}
        @Override public void reportError(String message) {}
    };
}
