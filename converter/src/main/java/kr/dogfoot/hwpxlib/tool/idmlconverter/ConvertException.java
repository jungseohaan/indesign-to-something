package kr.dogfoot.hwpxlib.tool.idmlconverter;

/**
 * IDML -> HWPX 변환 중 발생하는 예외.
 */
public class ConvertException extends Exception {

    public enum Phase {
        LOADING,
        PARSING,
        STYLE_MAPPING,
        COORDINATE,
        EQUATION,
        IMAGE,
        HWPX_GENERATION,
        JSON_SERIALIZATION,
        JSON_DESERIALIZATION,
        VALIDATION
    }

    private final Phase phase;
    private final String detail;

    public ConvertException(Phase phase, String message) {
        super(message);
        this.phase = phase;
        this.detail = null;
    }

    public ConvertException(Phase phase, String message, String detail) {
        super(message);
        this.phase = phase;
        this.detail = detail;
    }

    public ConvertException(Phase phase, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.detail = null;
    }

    public Phase phase() {
        return phase;
    }

    public String detail() {
        return detail;
    }
}
