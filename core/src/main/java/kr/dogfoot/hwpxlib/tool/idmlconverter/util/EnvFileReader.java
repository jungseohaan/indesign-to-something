package kr.dogfoot.hwpxlib.tool.idmlconverter.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * .env 파일(KEY=VALUE 형식)에서 API 키를 읽는 유틸리티.
 *
 * 탐색 순서:
 *   1. JAR 파일과 같은 디렉토리
 *   2. 현재 작업 디렉토리
 *
 * 로드된 키는 {@link #get(String)}로 조회하거나,
 * {@link #loadIntoSystemProperties()}로 시스템 프로퍼티에 일괄 등록할 수 있다.
 *
 * .env 파일 예시:
 *   ANTHROPIC_API_KEY=sk-ant-...
 *   OPENAI_API_KEY=sk-...
 *   # 주석은 무시됨
 *   EMPTY_KEY=
 */
public final class EnvFileReader {

    private static final String ENV_FILENAME = ".env";

    // 잘 알려진 API 키 이름 상수
    public static final String GROQ_API_KEY       = "GROQ_API_KEY";
    public static final String ANTHROPIC_API_KEY  = "ANTHROPIC_API_KEY";
    public static final String OPENAI_API_KEY     = "OPENAI_API_KEY";

    private final Map<String, String> entries;

    private EnvFileReader(Map<String, String> entries) {
        this.entries = entries;
    }

    // -------------------------------------------------------
    // 팩토리
    // -------------------------------------------------------

    /**
     * JAR 디렉토리 → CWD 순으로 .env 파일을 탐색해 로드한다.
     * 파일을 찾지 못하면 빈 인스턴스를 반환한다 (예외 없음).
     */
    public static EnvFileReader load() {
        // 탐색 후보 디렉토리 목록 (순서대로 시도)
        java.util.List<String> candidates = new java.util.ArrayList<String>();

        // 1. JAR 위치 (예: converter/target/) 및 그 상위 2단계 (project root)
        String jar = jarDir();
        if (jar != null) {
            candidates.add(jar);
            File f1 = new File(jar).getParentFile();          // converter/
            if (f1 != null) candidates.add(f1.getAbsolutePath());
            File f2 = f1 != null ? f1.getParentFile() : null; // project root
            if (f2 != null) candidates.add(f2.getAbsolutePath());
        }

        // 2. 현재 작업 디렉토리 및 그 상위 2단계
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            candidates.add(cwd);
            File c1 = new File(cwd).getParentFile();
            if (c1 != null) candidates.add(c1.getAbsolutePath());
            File c2 = c1 != null ? c1.getParentFile() : null;
            if (c2 != null) candidates.add(c2.getAbsolutePath());
        }

        for (String dir : candidates) {
            if (dir == null) continue;
            File f = new File(dir, ENV_FILENAME);
            if (f.exists() && f.isFile()) {
                try {
                    EnvFileReader result = loadFrom(f);
                    System.err.println("[EnvFileReader] .env 로드: " + f.getAbsolutePath()
                            + " (" + result.size() + "개 키)");
                    return result;
                } catch (Exception e) {
                    System.err.println("[EnvFileReader] .env 로드 실패 (" + f.getAbsolutePath() + "): " + e.getMessage());
                }
            }
        }
        System.err.println("[EnvFileReader] .env 파일을 찾지 못했습니다. 탐색 경로: " + candidates);
        return new EnvFileReader(new LinkedHashMap<>());
    }

    /**
     * 지정한 파일 경로에서 직접 로드한다.
     */
    public static EnvFileReader loadFrom(String path) throws Exception {
        return loadFrom(new File(path));
    }

    public static EnvFileReader loadFrom(File file) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eq = line.indexOf('=');
                if (eq < 0) continue;

                String key   = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                // 따옴표 제거: "value" 또는 'value'
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last  = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }

                if (!key.isEmpty()) {
                    map.put(key, value);
                }
            }
        }
        return new EnvFileReader(map);
    }

    // -------------------------------------------------------
    // 조회
    // -------------------------------------------------------

    /** 키로 값을 조회. 없으면 null. */
    public String get(String key) {
        return entries.get(key);
    }

    /**
     * 키로 값을 조회. 없으면 환경변수에서 폴백.
     * 우선순위: .env 파일 > 환경변수
     */
    public String getOrEnv(String key) {
        String v = entries.get(key);
        return v != null ? v : System.getenv(key);
    }

    /** 모든 항목 반환 (읽기 전용). */
    public Map<String, String> all() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    /** 로드된 키 수. */
    public int size() {
        return entries.size();
    }

    /** 로드된 항목이 하나도 없으면 true. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 로드된 모든 항목을 시스템 프로퍼티에 등록한다.
     * 이미 존재하는 프로퍼티는 덮어쓰지 않는다.
     */
    public void loadIntoSystemProperties() {
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (System.getProperty(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
            }
        }
    }

    // -------------------------------------------------------
    // 내부
    // -------------------------------------------------------

    private static String jarDir() {
        try {
            String jarPath = EnvFileReader.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            return new File(jarPath).getParent();
        } catch (Exception e) {
            return null;
        }
    }
}
