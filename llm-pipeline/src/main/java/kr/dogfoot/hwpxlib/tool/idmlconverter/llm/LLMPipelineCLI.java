package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBundleReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.EnvFileReader;

import java.io.File;

/**
 * Standalone CLI for the LLM teaching-material pipeline.
 *
 * Usage:
 *   java -jar llm-pipeline-standalone.jar \
 *        --teach-ast ast.json \
 *        --output   teaching_material.json \
 *        [--teach   prompts/base_prompt.txt] \
 *        [--teach-extra prompts/extra/textbook.txt] \
 *        [--teach-dir  prompts/ --textbook-id middle3-english] \
 *        [--test-llm]
 *
 * Env (.env file in working directory or JAR directory):
 *   OPENAI_API_KEY=sk-...
 *   ANTHROPIC_API_KEY=sk-ant-...
 *   GROQ_API_KEY=gsk_...
 */
public class LLMPipelineCLI {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        EnvFileReader env = EnvFileReader.load();

        if (args[0].equals("--test-llm")) {
            runTestLlm(env);
            return;
        }

        String astPath      = null;
        String outputPath   = "teaching_material.json";
        String teachPrompt  = null;
        String teachExtra   = null;
        String teachDir     = null;
        String textbookId   = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--teach-ast":   astPath    = args[++i]; break;
                case "--output":      outputPath = args[++i]; break;
                case "--teach":       teachPrompt = args[++i]; break;
                case "--teach-extra": teachExtra  = args[++i]; break;
                case "--teach-dir":   teachDir    = args[++i]; break;
                case "--textbook-id": textbookId  = args[++i]; break;
            }
        }

        if (astPath == null) {
            System.err.println("ERROR: --teach-ast <ast.json> is required.");
            printUsage();
            System.exit(1);
        }

        System.err.println("[LLM] AST 로드: " + astPath);
        ASTDocument doc = ASTBundleReader.read(new File(astPath));
        if (doc == null) {
            System.err.println("ERROR: AST 로드 실패: " + astPath);
            System.exit(1);
        }

        TeachingPromptLoader.AgentPrompts systemPrompts;
        if (teachDir != null && textbookId != null) {
            systemPrompts = TeachingPromptLoader.loadAgentsFromDir(teachDir, textbookId);
        } else if (teachPrompt != null) {
            systemPrompts = TeachingPromptLoader.loadAgents(teachPrompt, teachExtra);
        } else {
            System.err.println("ERROR: --teach <prompt> 또는 --teach-dir <dir> --textbook-id <id> 필요.");
            printUsage();
            System.exit(1);
            return;
        }

        String openaiKey  = nullToEmpty(env.getOrEnv(EnvFileReader.OPENAI_API_KEY));
        String claudeKey  = nullToEmpty(env.getOrEnv(EnvFileReader.ANTHROPIC_API_KEY));
        String groqKey    = nullToEmpty(env.getOrEnv(EnvFileReader.GROQ_API_KEY));

        LLMConfig llmCfg = LLMConfig.builder()
                .openaiApiKey(openaiKey)
                .anthropicApiKey(claudeKey)
                .groqApiKey(groqKey)
                .build();

        if (!llmCfg.hasAnyKey()) {
            System.err.println("ERROR: .env에 OPENAI_API_KEY, ANTHROPIC_API_KEY, GROQ_API_KEY 중 하나가 필요합니다.");
            System.exit(1);
        }

        com.google.gson.JsonObject result = TeachingMaterialGenerator.generate(doc, systemPrompts, llmCfg);
        TeachingMaterialWriter.write(result, new File(outputPath));
        System.out.println("Teaching semantic blocks 생성 완료: " + outputPath);
    }

    private static void runTestLlm(EnvFileReader env) {
        String openaiKey = nullToEmpty(env.getOrEnv(EnvFileReader.OPENAI_API_KEY));
        String claudeKey = nullToEmpty(env.getOrEnv(EnvFileReader.ANTHROPIC_API_KEY));
        String groqKey   = nullToEmpty(env.getOrEnv(EnvFileReader.GROQ_API_KEY));

        if (!openaiKey.isEmpty()) System.out.println("OPENAI_API_KEY:    " + maskKey(openaiKey));
        if (!claudeKey.isEmpty()) System.out.println("ANTHROPIC_API_KEY: " + maskKey(claudeKey));
        if (!groqKey.isEmpty())   System.out.println("GROQ_API_KEY:      " + maskKey(groqKey));

        if (openaiKey.isEmpty() && claudeKey.isEmpty() && groqKey.isEmpty()) {
            System.out.println("API 키 없음. .env에 OPENAI_API_KEY, ANTHROPIC_API_KEY, GROQ_API_KEY 중 하나를 설정하세요.");
            System.exit(1);
        }

        LLMConfig cfg = LLMConfig.builder()
                .openaiApiKey(openaiKey)
                .anthropicApiKey(claudeKey)
                .groqApiKey(groqKey)
                .build();

        System.out.println("Provider: " + cfg.activeProvider());
        System.out.print("API 연결 테스트...");
        try {
            String result = new AIClient(cfg).ping();
            System.out.println(" OK — " + result.substring(0, Math.min(result.length(), 60)));
        } catch (LLMException e) {
            System.out.println(" FAIL");
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String maskKey(String key) {
        if (key.length() <= 12) return "****";
        return key.substring(0, 8) + "****..." + key.substring(key.length() - 4);
    }

    private static void printUsage() {
        System.out.println("LLM Teaching Material Pipeline");
        System.out.println("  --teach-ast <ast.json>          AST bundle (from converter --export-ast)");
        System.out.println("  --output    <out.json>           Output path (default: teaching_material.json)");
        System.out.println("  --teach     <base_prompt.txt>    Base system prompt file");
        System.out.println("  --teach-extra <extra.txt>        Per-textbook additional prompt (optional)");
        System.out.println("  --teach-dir <dir>                Prompts directory (use with --textbook-id)");
        System.out.println("  --textbook-id <id>               Textbook identifier for extra prompt");
        System.out.println("  --test-llm                       Quick API connectivity test");
    }
}
