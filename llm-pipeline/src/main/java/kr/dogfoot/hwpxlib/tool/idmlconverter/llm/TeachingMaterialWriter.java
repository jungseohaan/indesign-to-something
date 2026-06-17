package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** LLM 결과 JSON 직렬화 */
public final class TeachingMaterialWriter {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private TeachingMaterialWriter() {}

    public static void write(TeachingMaterial material, File outputFile) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            GSON.toJson(material, w);
        }
    }

    public static void write(TeachingMaterial material, String outputPath) throws IOException {
        write(material, new File(outputPath));
    }

    public static void write(JsonElement json, File outputFile) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            GSON.toJson(json, w);
        }
    }

    public static void write(JsonElement json, String outputPath) throws IOException {
        write(json, new File(outputPath));
    }
}
