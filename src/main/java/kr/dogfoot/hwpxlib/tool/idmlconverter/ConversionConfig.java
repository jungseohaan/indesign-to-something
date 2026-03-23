package kr.dogfoot.hwpxlib.tool.idmlconverter;

import com.google.gson.*;

import java.io.FileReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * conversion-config.json 통합 설정 로더.
 * ExtendScript와 Java CLI가 공용으로 사용하는 변환 설정.
 */
public class ConversionConfig {

    // --- rendering ---
    private int renderMaxTextLength = 30;
    private boolean decorativeLargeTextEnabled = true;
    private int decorativeMinFontSize = 16;
    private boolean decorativeExcludeBlack = true;
    private double decorativeBlackThreshold = 0.90;
    private int badgeMaxTextLength = 15;
    private int badgeMaxTotalTextLength = 40;
    private int badgeMaxFontSize = 12;
    private int opacityThreshold = 100;
    private int tintThreshold = 30;
    private double rotationMinAngle = 0.1;
    private int pngExportResolution = 300;

    // --- fontMapping ---
    private String defaultSerifKo = "한컴바탕";
    private String defaultSansKo = "한컴돋움";
    private String defaultSerifEn = "Times New Roman";
    private String defaultSansEn = "Arial";
    private Map<String, FontMappingEntry> fontMappings = new HashMap<String, FontMappingEntry>();
    private Map<String, FontMetricEntry> hwpxFontMetrics = new HashMap<String, FontMetricEntry>();

    // --- spacing ---
    private int spaceCondenseRatio = 50;

    // --- orphanInjection ---
    private double orphanOverlapThreshold = 0.70;
    private double orphanContainmentThreshold = 0.80;
    private double orphanDuplicateSizeRatio = 0.90;

    // --- vectorRendering ---
    private int vectorDpi = 300;

    // --- image ---
    private int imageDpi = 72;
    private int maxPixelDimension = 2000;

    /**
     * JSON 파일에서 설정을 로드한다.
     */
    public static ConversionConfig load(String jsonPath) {
        ConversionConfig config = new ConversionConfig();
        if (jsonPath == null) return config;

        try {
            Reader reader = new FileReader(jsonPath);
            try {
                JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
                config.parseRoot(root);
                System.out.println("[ConversionConfig] 로드: " + jsonPath);
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            System.err.println("[ConversionConfig] 로드 실패: " + e.getMessage() + " → 기본값 사용");
        }
        return config;
    }

    private void parseRoot(JsonObject root) {
        // rendering
        if (root.has("rendering")) {
            JsonObject r = root.getAsJsonObject("rendering");
            if (r.has("pngExportResolution")) pngExportResolution = r.get("pngExportResolution").getAsInt();

            if (r.has("textFrame")) {
                JsonObject tf = r.getAsJsonObject("textFrame");
                if (tf.has("maxTextLength")) renderMaxTextLength = tf.get("maxTextLength").getAsInt();
                if (tf.has("decorativeLargeText")) {
                    JsonObject dlt = tf.getAsJsonObject("decorativeLargeText");
                    if (dlt.has("enabled")) decorativeLargeTextEnabled = dlt.get("enabled").getAsBoolean();
                    if (dlt.has("minFontSize")) decorativeMinFontSize = dlt.get("minFontSize").getAsInt();
                    if (dlt.has("excludeBlack")) decorativeExcludeBlack = dlt.get("excludeBlack").getAsBoolean();
                    if (dlt.has("blackThreshold")) decorativeBlackThreshold = dlt.get("blackThreshold").getAsDouble();
                }
            }
            if (r.has("badge")) {
                JsonObject b = r.getAsJsonObject("badge");
                if (b.has("maxTextLength")) badgeMaxTextLength = b.get("maxTextLength").getAsInt();
                if (b.has("maxTotalTextLength")) badgeMaxTotalTextLength = b.get("maxTotalTextLength").getAsInt();
                if (b.has("maxFontSize")) badgeMaxFontSize = b.get("maxFontSize").getAsInt();
            }
            if (r.has("transparency")) {
                JsonObject t = r.getAsJsonObject("transparency");
                if (t.has("opacityThreshold")) opacityThreshold = t.get("opacityThreshold").getAsInt();
                if (t.has("tintThreshold")) tintThreshold = t.get("tintThreshold").getAsInt();
            }
            if (r.has("rotation")) {
                JsonObject rot = r.getAsJsonObject("rotation");
                if (rot.has("minAngle")) rotationMinAngle = rot.get("minAngle").getAsDouble();
            }
        }

        // fontMapping
        if (root.has("fontMapping")) {
            JsonObject fm = root.getAsJsonObject("fontMapping");
            if (fm.has("defaultSerifKo")) defaultSerifKo = fm.get("defaultSerifKo").getAsString();
            if (fm.has("defaultSansKo")) defaultSansKo = fm.get("defaultSansKo").getAsString();
            if (fm.has("defaultSerifEn")) defaultSerifEn = fm.get("defaultSerifEn").getAsString();
            if (fm.has("defaultSansEn")) defaultSansEn = fm.get("defaultSansEn").getAsString();

            if (fm.has("mappings")) {
                JsonObject mappings = fm.getAsJsonObject("mappings");
                for (Map.Entry<String, JsonElement> entry : mappings.entrySet()) {
                    JsonObject val = entry.getValue().getAsJsonObject();
                    FontMappingEntry me = new FontMappingEntry();
                    me.ko = val.has("ko") ? val.get("ko").getAsString() : defaultSansKo;
                    me.en = val.has("en") ? val.get("en").getAsString() : me.ko;
                    me.spacing = val.has("spacing") ? val.get("spacing").getAsInt() : 0;
                    me.scaleAdjust = val.has("scaleAdjust") ? val.get("scaleAdjust").getAsInt() : 0;
                    fontMappings.put(entry.getKey(), me);
                }
            }

            if (fm.has("hwpxFontMetrics")) {
                JsonObject metrics = fm.getAsJsonObject("hwpxFontMetrics");
                for (Map.Entry<String, JsonElement> entry : metrics.entrySet()) {
                    if (entry.getKey().startsWith("_")) continue;
                    JsonObject val = entry.getValue().getAsJsonObject();
                    FontMetricEntry hm = new FontMetricEntry();
                    hm.korWidth = val.has("korWidth") ? val.get("korWidth").getAsDouble() : 0;
                    hm.latWidth = val.has("latWidth") ? val.get("latWidth").getAsDouble() : 0;
                    hm.weight = val.has("weight") ? val.get("weight").getAsInt() : 400;
                    hm.xHeight = val.has("xHeight") ? val.get("xHeight").getAsDouble() : 0;
                    hm.ascent = val.has("ascent") ? val.get("ascent").getAsDouble() : 0;
                    hm.descent = val.has("descent") ? val.get("descent").getAsDouble() : 0;
                    hm.category = val.has("category") ? val.get("category").getAsString() : "sans";
                    hwpxFontMetrics.put(entry.getKey(), hm);
                }
            }
        }

        // spacing
        if (root.has("spacing")) {
            JsonObject s = root.getAsJsonObject("spacing");
            if (s.has("spaceCondenseRatio")) spaceCondenseRatio = s.get("spaceCondenseRatio").getAsInt();
        }

        // orphanInjection
        if (root.has("orphanInjection")) {
            JsonObject o = root.getAsJsonObject("orphanInjection");
            if (o.has("overlapThreshold")) orphanOverlapThreshold = o.get("overlapThreshold").getAsDouble();
            if (o.has("containmentThreshold")) orphanContainmentThreshold = o.get("containmentThreshold").getAsDouble();
            if (o.has("duplicateSizeRatio")) orphanDuplicateSizeRatio = o.get("duplicateSizeRatio").getAsDouble();
        }

        // vectorRendering
        if (root.has("vectorRendering")) {
            JsonObject v = root.getAsJsonObject("vectorRendering");
            if (v.has("dpi")) vectorDpi = v.get("dpi").getAsInt();
        }

        // image
        if (root.has("image")) {
            JsonObject img = root.getAsJsonObject("image");
            if (img.has("defaultDpi")) imageDpi = img.get("defaultDpi").getAsInt();
            if (img.has("maxPixelDimension")) maxPixelDimension = img.get("maxPixelDimension").getAsInt();
        }
    }

    // --- Getters ---

    // rendering
    public int renderMaxTextLength() { return renderMaxTextLength; }
    public boolean decorativeLargeTextEnabled() { return decorativeLargeTextEnabled; }
    public int decorativeMinFontSize() { return decorativeMinFontSize; }
    public boolean decorativeExcludeBlack() { return decorativeExcludeBlack; }
    public double decorativeBlackThreshold() { return decorativeBlackThreshold; }
    public int badgeMaxTextLength() { return badgeMaxTextLength; }
    public int badgeMaxTotalTextLength() { return badgeMaxTotalTextLength; }
    public int badgeMaxFontSize() { return badgeMaxFontSize; }
    public int opacityThreshold() { return opacityThreshold; }
    public int tintThreshold() { return tintThreshold; }
    public double rotationMinAngle() { return rotationMinAngle; }
    public int pngExportResolution() { return pngExportResolution; }

    // fontMapping
    public String defaultSerifKo() { return defaultSerifKo; }
    public String defaultSansKo() { return defaultSansKo; }
    public String defaultSerifEn() { return defaultSerifEn; }
    public String defaultSansEn() { return defaultSansEn; }
    public Map<String, FontMappingEntry> fontMappings() { return fontMappings; }
    public Map<String, FontMetricEntry> hwpxFontMetrics() { return hwpxFontMetrics; }

    // spacing
    public int spaceCondenseRatio() { return spaceCondenseRatio; }

    // orphanInjection
    public double orphanOverlapThreshold() { return orphanOverlapThreshold; }
    public double orphanContainmentThreshold() { return orphanContainmentThreshold; }
    public double orphanDuplicateSizeRatio() { return orphanDuplicateSizeRatio; }

    // vectorRendering
    public int vectorDpi() { return vectorDpi; }

    // image
    public int imageDpi() { return imageDpi; }
    public int maxPixelDimension() { return maxPixelDimension; }

    // --- Inner DTOs ---

    public static class FontMappingEntry {
        public String ko;
        public String en;
        public int spacing;
        public int scaleAdjust;
        public double ratio = 1.0;
    }

    public static class FontMetricEntry {
        public double korWidth;
        public double latWidth;
        public int weight;
        public double xHeight;
        public double ascent;
        public double descent;
        public String category;
    }
}