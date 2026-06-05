package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * TeachingMaterial → HTML 슬라이드 프레젠테이션 변환기.
 *
 * 템플릿 주입 방식:
 *   템플릿 HTML 안에 아래 플레이스홀더가 있어야 한다:
 *     {@code / *__TEACHING_DATA__* /null/ *__END__* /}
 *   (공백 없이: /*__TEACHING_DATA__* /null/*__END__* /)
 *   이 부분을 실제 JSON 데이터로 교체하여 완성된 HTML을 출력한다.
 *
 * 템플릿 우선순위:
 *   1. 호출자가 지정한 외부 templateFile (null이면 스킵)
 *   2. 클래스패스 html-templates/default.html (빌트인)
 */
public final class HtmlSlideGenerator {

    /** 템플릿 안에서 JSON 데이터로 교체될 플레이스홀더 */
    static final String PLACEHOLDER = "/*__TEACHING_DATA__*/null/*__END__*/";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private HtmlSlideGenerator() {}

    // -------------------------------------------------------
    // 공개 API
    // -------------------------------------------------------

    /**
     * 빌트인 기본 템플릿으로 HTML 생성.
     */
    public static void write(TeachingMaterial material, File outputFile) throws IOException {
        write(material, (File) null, outputFile);
    }

    /**
     * 외부 templateFile을 사용하거나, null이면 빌트인 템플릿 사용.
     */
    public static void write(TeachingMaterial material, File templateFile, File outputFile) throws IOException {
        String template = templateFile != null ? readUtf8(templateFile) : loadBuiltinTemplate();
        String html = inject(template, material);

        File parent = outputFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            w.write(html);
        }
    }

    // -------------------------------------------------------
    // 내부
    // -------------------------------------------------------

    private static String inject(String template, TeachingMaterial material) {
        String json = GSON.toJson(material);
        return template.replace(PLACEHOLDER, json);
    }

    private static String loadBuiltinTemplate() throws IOException {
        InputStream is = HtmlSlideGenerator.class.getResourceAsStream(
                "/html-templates/default.html");
        if (is == null) {
            throw new IOException("빌트인 HTML 템플릿을 찾을 수 없습니다: /html-templates/default.html");
        }
        try {
            return readUtf8(is);
        } finally {
            is.close();
        }
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return readUtf8(is);
        }
    }

    private static String readUtf8(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }
}
