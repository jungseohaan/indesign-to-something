package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

public class LLMConfig {

    public static final String MODEL_GROQ_LLAMA = "llama-3.3-70b-versatile";
    public static final String MODEL_CLAUDE_HAIKU = "claude-haiku-4-5-20251001";

    private final String groqApiKey;
    private final String anthropicApiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int chunkDelayMs;  // rate-limit 대비 청크 간 딜레이

    private LLMConfig(Builder b) {
        this.groqApiKey      = b.groqApiKey;
        this.anthropicApiKey = b.anthropicApiKey;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs   = b.readTimeoutMs;
        this.chunkDelayMs    = b.chunkDelayMs;
    }

    public String groqApiKey()       { return groqApiKey; }
    public String anthropicApiKey()  { return anthropicApiKey; }
    public int connectTimeoutMs()    { return connectTimeoutMs; }
    public int readTimeoutMs()       { return readTimeoutMs; }
    public int chunkDelayMs()        { return chunkDelayMs; }

    public boolean hasGroq()         { return groqApiKey != null && !groqApiKey.isEmpty(); }
    public boolean hasAnthropic()    { return anthropicApiKey != null && !anthropicApiKey.isEmpty(); }
    public boolean hasAnyKey()       { return hasGroq() || hasAnthropic(); }

    /** 사용 중인 API provider 이름 */
    public String activeProvider() {
        if (hasGroq()) return "GROQ";
        if (hasAnthropic()) return "Anthropic";
        return "none";
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String groqApiKey;
        private String anthropicApiKey;
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs    = 60_000;
        private int chunkDelayMs     = 1_000;

        public Builder groqApiKey(String v)       { this.groqApiKey = v; return this; }
        public Builder anthropicApiKey(String v)  { this.anthropicApiKey = v; return this; }
        public Builder connectTimeoutMs(int v)    { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v)       { this.readTimeoutMs = v; return this; }
        public Builder chunkDelayMs(int v)        { this.chunkDelayMs = v; return this; }
        public LLMConfig build()                  { return new LLMConfig(this); }
    }
}
