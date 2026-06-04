package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticLayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SemanticLayerWriter — SemanticLayer 를 JSON 으로 직렬화.
 *
 * <p>TS 측 SemanticLayer JSON과 호환되는 출력. enum은 자동으로 name() 으로
 * 직렬화됨 (Gson 기본 동작).</p>
 */
public final class SemanticLayerWriter {

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private static final Gson COMPACT = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private SemanticLayerWriter() {}

    /** SemanticLayer → JSON 문자열 (pretty printed). */
    public static String toJson(SemanticLayer layer) {
        return PRETTY.toJson(layer);
    }

    /** SemanticLayer → JSON 문자열 (compact). */
    public static String toJsonCompact(SemanticLayer layer) {
        return COMPACT.toJson(layer);
    }

    /** SemanticLayer → 파일. */
    public static void write(SemanticLayer layer, Path path) throws IOException {
        Files.write(path, toJson(layer).getBytes(StandardCharsets.UTF_8));
    }
}
