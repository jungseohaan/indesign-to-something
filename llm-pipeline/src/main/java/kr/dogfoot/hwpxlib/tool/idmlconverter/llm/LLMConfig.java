package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

public class LLMConfig {

    public static final String MODEL_GROQ_LLAMA = "llama-3.3-70b-versatile";
    public static final String MODEL_CLAUDE_HAIKU = "claude-haiku-4-5-20251001";
    public static final String MODEL_OPENAI = "gpt-4o-mini";

    private final String openaiApiKey;
    private final String groqApiKey;
    private final String anthropicApiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private LLMConfig(Builder b) {
        this.openaiApiKey    = b.openaiApiKey;
        this.groqApiKey      = b.groqApiKey;
        this.anthropicApiKey = b.anthropicApiKey;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs   = b.readTimeoutMs;
    }

    public String openaiApiKey()     { return openaiApiKey; }
    public String groqApiKey()       { return groqApiKey; }
    public String anthropicApiKey()  { return anthropicApiKey; }
    public int connectTimeoutMs()    { return connectTimeoutMs; }
    public int readTimeoutMs()       { return readTimeoutMs; }

    public boolean hasOpenAI()       { return openaiApiKey != null && !openaiApiKey.isEmpty(); }
    public boolean hasGroq()         { return groqApiKey != null && !groqApiKey.isEmpty(); }
    public boolean hasAnthropic()    { return anthropicApiKey != null && !anthropicApiKey.isEmpty(); }
    public boolean hasAnyKey()       { return hasOpenAI() || hasAnthropic() || hasGroq(); }

    /** 사용 중인 API provider 이름 */
    public String activeProvider() {
        if (hasOpenAI()) return "OpenAI";
        if (hasAnthropic()) return "Anthropic";
        if (hasGroq()) return "GROQ";
        return "none";
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String openaiApiKey;
        private String groqApiKey;
        private String anthropicApiKey;
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs    = 60_000;

        public Builder openaiApiKey(String v)     { this.openaiApiKey = v; return this; }
        public Builder groqApiKey(String v)       { this.groqApiKey = v; return this; }
        public Builder anthropicApiKey(String v)  { this.anthropicApiKey = v; return this; }
        public Builder connectTimeoutMs(int v)    { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v)       { this.readTimeoutMs = v; return this; }
        public LLMConfig build()                  { return new LLMConfig(this); }
    }
}
