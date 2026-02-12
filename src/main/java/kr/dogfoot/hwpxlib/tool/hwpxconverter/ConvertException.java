package kr.dogfoot.hwpxlib.tool.hwpxconverter;

/**
 * HWPX → IDML 변환 중 발생하는 예외.
 */
public class ConvertException extends Exception {
    private final String phase;

    public ConvertException(String phase, String message) {
        super(message);
        this.phase = phase;
    }

    public ConvertException(String phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
    }

    public String phase() { return phase; }
}
