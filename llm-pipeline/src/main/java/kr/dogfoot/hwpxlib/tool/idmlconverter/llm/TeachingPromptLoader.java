package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 기본 프롬프트 + 교과서별 추가 프롬프트를 로드해 병합한다.
 *
 * <pre>
 * [기본 지시]
 * {base_prompt.txt 전체}
 *
 * [이 교과서 추가 지시]
 * {extra prompt 전체}   ← 없으면 이 섹션 생략
 * </pre>
 */
public final class TeachingPromptLoader {

    private TeachingPromptLoader() {}

    public static final class AgentPrompts {
        private final String agentA;
        private final String agentB;

        public AgentPrompts(String agentA, String agentB) {
            this.agentA = agentA;
            this.agentB = agentB;
        }

        public String agentA() { return agentA; }
        public String agentB() { return agentB; }
    }

    /** 기본 프롬프트만 로드 */
    public static String load(String basePromptPath) throws IOException {
        return readFile(basePromptPath);
    }

    /** 기본 + extra 병합 */
    public static String load(String basePromptPath, String extraPromptPath) throws IOException {
        String base = readFile(basePromptPath);
        if (extraPromptPath == null || extraPromptPath.isEmpty()) return base;
        File extra = new File(extraPromptPath);
        if (!extra.exists()) return base;
        String extraText = readFile(extraPromptPath);
        return base.trim() + "\n\n[이 교과서 추가 지시]\n" + extraText.trim();
    }

    public static AgentPrompts loadAgents(String basePromptPath, String extraPromptPath) throws IOException {
        File base = new File(basePromptPath);
        File dir = base.getParentFile();
        File agentA = dir != null ? new File(dir, "agent_a_prompt.txt") : null;
        File agentB = dir != null ? new File(dir, "agent_b_prompt.txt") : null;

        String aPrompt;
        String bPrompt;
        if (agentA != null && agentA.exists() && agentB != null && agentB.exists()) {
            aPrompt = readFile(agentA.getAbsolutePath());
            bPrompt = readFile(agentB.getAbsolutePath());
        } else {
            String basePrompt = load(basePromptPath, null);
            aPrompt = basePrompt;
            bPrompt = basePrompt;
        }

        if (extraPromptPath != null && !extraPromptPath.isEmpty()) {
            File extra = new File(extraPromptPath);
            if (extra.exists()) {
                String extraText = readFile(extraPromptPath).trim();
                aPrompt = aPrompt.trim() + "\n\n[이 교과서 추가 지시]\n" + extraText;
                bPrompt = bPrompt.trim() + "\n\n[이 교과서 추가 지시]\n" + extraText;
            }
        }

        return new AgentPrompts(aPrompt, bPrompt);
    }

    /**
     * prompts 디렉토리 + textbookId로 자동 탐색.
     *
     * base:  {dir}/base_prompt.txt
     * extra: {dir}/extra/{textbookId}.txt  (없으면 생략)
     */
    public static String loadFromDir(String promptsDir, String textbookId) throws IOException {
        File base = new File(promptsDir, "base_prompt.txt");
        if (!base.exists()) {
            throw new IOException("base_prompt.txt 없음: " + base.getAbsolutePath());
        }
        String extraPath = null;
        if (textbookId != null && !textbookId.isEmpty()) {
            File extra = new File(new File(promptsDir, "extra"), textbookId + ".txt");
            if (extra.exists()) extraPath = extra.getAbsolutePath();
        }
        return load(base.getAbsolutePath(), extraPath);
    }

    public static AgentPrompts loadAgentsFromDir(String promptsDir, String textbookId) throws IOException {
        File base = new File(promptsDir, "base_prompt.txt");
        if (!base.exists()) {
            throw new IOException("base_prompt.txt 없음: " + base.getAbsolutePath());
        }
        String extraPath = null;
        if (textbookId != null && !textbookId.isEmpty()) {
            File extra = new File(new File(promptsDir, "extra"), textbookId + ".txt");
            if (extra.exists()) extraPath = extra.getAbsolutePath();
        }
        return loadAgents(base.getAbsolutePath(), extraPath);
    }

    private static String readFile(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) throw new IOException("프롬프트 파일 없음: " + f.getAbsolutePath());
        StringBuilder sb = new StringBuilder((int) f.length());
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
