package kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SemanticBlockWriter {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private SemanticBlockWriter() {}

    public static String toJson(SemanticBlockDocument document) {
        return GSON.toJson(document);
    }

    public static void write(SemanticBlockDocument document, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, toJson(document).getBytes(StandardCharsets.UTF_8));
    }
}
