package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;

/**
 * JSON 문자열 → IntermediateDocument 역직렬화.
 * Gson 기반.
 */
public class JsonDeserializer {

    private static final Gson GSON = new GsonBuilder()
            .create();

    /**
     * JSON 문자열을 IntermediateDocument로 역직렬화한다.
     */
    public static IntermediateDocument fromJson(String json) throws ConvertException {
        if (json == null || json.trim().isEmpty()) {
            throw new ConvertException(ConvertException.Phase.JSON_DESERIALIZATION,
                    "JSON string is null or empty");
        }
        try {
            IntermediateDocument doc = GSON.fromJson(json, IntermediateDocument.class);
            if (doc == null) {
                throw new ConvertException(ConvertException.Phase.JSON_DESERIALIZATION,
                        "Failed to deserialize JSON: result is null");
            }
            return doc;
        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.JSON_DESERIALIZATION,
                    "Failed to deserialize JSON: " + e.getMessage(), e);
        }
    }
}
