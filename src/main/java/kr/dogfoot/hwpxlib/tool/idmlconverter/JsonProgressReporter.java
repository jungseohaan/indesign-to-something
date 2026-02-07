package kr.dogfoot.hwpxlib.tool.idmlconverter;

import java.io.PrintStream;

/**
 * JSON 형식으로 진행률을 출력하는 ProgressReporter.
 * Tauri 앱에서 스트리밍으로 읽을 수 있도록 한 줄씩 JSON 출력.
 */
public class JsonProgressReporter implements ProgressReporter {

    private final PrintStream out;

    public JsonProgressReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void reportProgress(int current, int total, String message) {
        out.println("{\"type\": \"progress\", \"current\": " + current +
                ", \"total\": " + total +
                ", \"message\": " + jsonString(message) + "}");
        out.flush();
    }

    @Override
    public void reportComplete(ConvertResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\": \"complete\", \"result\": {");
        sb.append("\"pages_converted\": ").append(result.pagesConverted()).append(", ");
        sb.append("\"frames_converted\": ").append(result.framesConverted()).append(", ");
        sb.append("\"images_converted\": ").append(result.imagesConverted()).append(", ");
        sb.append("\"warnings\": [");
        boolean first = true;
        for (String warning : result.warnings()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(jsonString(warning));
        }
        sb.append("]}}");
        out.println(sb.toString());
        out.flush();
    }

    @Override
    public void reportError(String message) {
        out.println("{\"type\": \"error\", \"message\": " + jsonString(message) + "}");
        out.flush();
    }

    private static String jsonString(String value) {
        if (value == null) return "null";

        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
