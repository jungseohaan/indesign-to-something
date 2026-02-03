package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;

/**
 * IntermediateDocument → JSON 문자열 직렬화.
 * Gson 기반.
 */
public class JsonSerializer {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private static final Gson GSON_COMPACT = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    /**
     * IntermediateDocument를 포맷된 JSON 문자열로 직렬화한다.
     */
    public static String toJson(IntermediateDocument doc) throws ConvertException {
        try {
            return GSON.toJson(doc);
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.JSON_SERIALIZATION,
                    "Failed to serialize to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * IntermediateDocument를 압축된(한줄) JSON 문자열로 직렬화한다.
     */
    public static String toJsonCompact(IntermediateDocument doc) throws ConvertException {
        try {
            return GSON_COMPACT.toJson(doc);
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.JSON_SERIALIZATION,
                    "Failed to serialize to JSON: " + e.getMessage(), e);
        }
    }
}
