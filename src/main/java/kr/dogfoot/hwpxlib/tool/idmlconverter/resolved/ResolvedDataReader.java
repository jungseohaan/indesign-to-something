package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * resolved.json 파서.
 * Gson JsonParser 기반 수동 필드 매핑 (ASTDeserializer 패턴).
 */
public class ResolvedDataReader {

    /**
     * resolved.json 파일을 읽어 ResolvedData로 변환한다.
     */
    public static ResolvedData read(String filePath) throws IOException {
        String json = new String(Files.readAllBytes(Paths.get(filePath)), "UTF-8");
        return fromJson(json);
    }

    public static ResolvedData fromJson(String json) {
        // ExtendScript JSON 폴리필이 제어 문자를 이스케이프하지 못할 수 있으므로 lenient 모드 사용
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        ResolvedData data = new ResolvedData();

        // colors → colorHexMap
        if (root.has("colors")) {
            for (JsonElement e : root.getAsJsonArray("colors")) {
                JsonObject c = e.getAsJsonObject();
                String name = getString(c, "name");
                String hex = getString(c, "hex");
                if (name != null && hex != null) {
                    data.addColor(name, hex);
                }
            }
        }

        // stories
        if (root.has("stories")) {
            for (JsonElement e : root.getAsJsonArray("stories")) {
                data.addStory(parseStory(e.getAsJsonObject()));
            }
        }

        // textFrames
        if (root.has("textFrames")) {
            for (JsonElement e : root.getAsJsonArray("textFrames")) {
                data.addTextFrame(parseTextFrame(e.getAsJsonObject()));
            }
        }

        return data;
    }

    private static ResolvedTextFrame parseTextFrame(JsonObject o) {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id(getString(o, "id"));
        tf.storyId(getString(o, "storyId"));
        tf.paragraphStart(getInt(o, "paragraphStart", -1));
        tf.paragraphEnd(getInt(o, "paragraphEnd", -1));
        tf.lineCount(getInt(o, "lineCount", 0));
        tf.overflows(getBool(o, "overflows", false));
        return tf;
    }

    private static ResolvedStory parseStory(JsonObject o) {
        ResolvedStory story = new ResolvedStory();
        story.id(getString(o, "id"));

        if (o.has("paragraphs")) {
            for (JsonElement e : o.getAsJsonArray("paragraphs")) {
                story.addParagraph(parseParagraph(e.getAsJsonObject()));
            }
        }
        return story;
    }

    private static ResolvedParagraph parseParagraph(JsonObject o) {
        ResolvedParagraph para = new ResolvedParagraph();
        para.styleName(getString(o, "styleName"));
        para.autoLeading(getBoxedDouble(o, "autoLeading"));
        para.spaceBefore(getBoxedDouble(o, "spaceBefore"));
        para.spaceAfter(getBoxedDouble(o, "spaceAfter"));
        para.firstLineIndent(getBoxedDouble(o, "firstLineIndent"));
        para.leftIndent(getBoxedDouble(o, "leftIndent"));
        para.justification(getString(o, "justification"));

        // leading: can be number or string "Auto"
        if (o.has("leading") && !o.get("leading").isJsonNull()) {
            JsonElement leadingEl = o.get("leading");
            if (leadingEl.isJsonPrimitive()) {
                if (leadingEl.getAsJsonPrimitive().isNumber()) {
                    para.leading(leadingEl.getAsDouble());
                } else {
                    para.leading(leadingEl.getAsString());
                }
            }
        }

        if (o.has("runs")) {
            for (JsonElement e : o.getAsJsonArray("runs")) {
                para.addRun(parseRun(e.getAsJsonObject()));
            }
        }
        return para;
    }

    private static ResolvedRun parseRun(JsonObject o) {
        ResolvedRun run = new ResolvedRun();
        run.text(getString(o, "text"));
        run.fontFamily(getString(o, "fontFamily"));
        run.fontSize(getBoxedDouble(o, "fontSize"));
        run.fontStyle(getString(o, "fontStyle"));
        run.fillColor(getString(o, "fillColor"));
        run.charStyle(getString(o, "charStyle"));
        run.tracking(getBoxedDouble(o, "tracking"));
        run.horizontalScale(getBoxedDouble(o, "horizontalScale"));
        run.verticalScale(getBoxedDouble(o, "verticalScale"));
        run.baselineShift(getBoxedDouble(o, "baselineShift"));
        run.position(getString(o, "position"));
        run.underline(getBoxedBool(o, "underline"));
        run.strikeThru(getBoxedBool(o, "strikeThru"));
        return run;
    }

    // ─── JSON helpers ────────────────────────────────────────

    private static String getString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static Double getBoxedDouble(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsDouble();
    }

    private static Boolean getBoxedBool(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsBoolean();
    }

    private static int getInt(JsonObject o, String key, int defaultValue) {
        if (!o.has(key) || o.get(key).isJsonNull()) return defaultValue;
        return o.get(key).getAsInt();
    }

    private static boolean getBool(JsonObject o, String key, boolean defaultValue) {
        if (!o.has(key) || o.get(key).isJsonNull()) return defaultValue;
        return o.get(key).getAsBoolean();
    }
}
