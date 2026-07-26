package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * GREP+수식 전수 조사용 덤퍼.
 * <p>환경변수 {@code MATH_CENSUS=<path>} 가 있을 때만 활성화된다. 최종 AST 단락 중
 * ASTEquation 을 포함하거나 GREP 수식 서체(grepMathFont/grepStyleApplied) 런을 포함한
 * 단락을, 소스 텍스트 + 각 항목의 종류/스크립트/sourceType 과 함께 JSONL 한 줄로 덤프한다.
 * <p>재설계용 데이터 수집 전용 — 프로덕션 동작에는 영향이 없다.
 */
public final class MathCensusDumper {
    private static Writer writer;
    private static boolean initialized;
    private static boolean enabled;

    private MathCensusDumper() {}

    private static synchronized void ensureInit() {
        if (initialized) return;
        initialized = true;
        String path = System.getenv("MATH_CENSUS");
        if (path == null || path.isEmpty()) { enabled = false; return; }
        try {
            writer = new FileWriter(path, false);
            enabled = true;
        } catch (IOException e) {
            enabled = false;
        }
    }

    public static void dumpParagraph(ASTParagraph para) {
        ensureInit();
        if (!enabled || para == null || para.items() == null) return;

        boolean hasEquation = false;
        boolean hasGrepMath = false;
        for (ASTInlineItem it : para.items()) {
            if (it instanceof ASTEquation) hasEquation = true;
            if (it instanceof ASTTextRun) {
                ASTTextRun tr = (ASTTextRun) it;
                if (tr.grepMathFont() || tr.grepStyleApplied()) hasGrepMath = true;
            }
        }
        if (!hasEquation && !hasGrepMath) return;

        StringBuilder src = new StringBuilder();
        StringBuilder items = new StringBuilder("[");
        boolean first = true;
        for (ASTInlineItem it : para.items()) {
            if (!first) items.append(",");
            first = false;
            if (it instanceof ASTTextRun) {
                ASTTextRun tr = (ASTTextRun) it;
                String t = tr.text() == null ? "" : tr.text();
                src.append(t);
                items.append("{\"k\":\"T\",\"t\":").append(js(t))
                    .append(",\"grepMath\":").append(tr.grepMathFont())
                    .append(",\"grepApplied\":").append(tr.grepStyleApplied())
                    .append(",\"sup\":").append(tr.superscript())
                    .append(",\"sub\":").append(tr.subscript())
                    .append(",\"font\":").append(js(tr.fontFamily()))
                    .append(",\"color\":").append(js(tr.textColor()))
                    .append("}");
            } else if (it instanceof ASTEquation) {
                ASTEquation eq = (ASTEquation) it;
                src.append("〖").append(eq.hwpScript() == null ? "" : eq.hwpScript()).append("〗");
                items.append("{\"k\":\"EQ\",\"script\":").append(js(eq.hwpScript()))
                    .append(",\"src\":").append(js(eq.sourceType()))
                    .append(",\"italic\":").append(eq.sourceItalic())
                    .append(",\"color\":").append(js(eq.textColor()))
                    .append("}");
            } else {
                items.append("{\"k\":\"").append(it.itemType()).append("\"}");
            }
        }
        items.append("]");

        String line = "{\"src\":" + js(src.toString()) + ",\"items\":" + items + "}\n";
        try {
            synchronized (MathCensusDumper.class) {
                writer.write(line);
                writer.flush();
            }
        } catch (IOException ignored) {}
    }

    private static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }
}
