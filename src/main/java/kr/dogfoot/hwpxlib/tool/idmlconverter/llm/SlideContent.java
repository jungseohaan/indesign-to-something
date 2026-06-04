package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import java.util.ArrayList;
import java.util.List;

public class SlideContent {

    /** title | bullets | vocabulary | qa | image | custom */
    public String layout;
    public int    index;
    public String title;
    public String subtitle;   // layout=title 전용
    public List<String> bullets;
    public List<VocabItem> items;  // layout=vocabulary 전용
    public String notes;

    public static class VocabItem {
        public String word;
        public String meaning;
        public String example;
    }

    public static SlideContent title(int idx, String title, String subtitle, String notes) {
        SlideContent s = new SlideContent();
        s.layout   = "title";
        s.index    = idx;
        s.title    = title;
        s.subtitle = subtitle;
        s.notes    = notes;
        return s;
    }

    public static SlideContent bullets(int idx, String title, List<String> bullets, String notes) {
        SlideContent s = new SlideContent();
        s.layout  = "bullets";
        s.index   = idx;
        s.title   = title;
        s.bullets = bullets != null ? bullets : new ArrayList<String>();
        s.notes   = notes;
        return s;
    }

    /** LLM 응답 파싱 실패 시 반환하는 플레이스홀더 슬라이드 */
    public static SlideContent fallback(int idx, String rawText) {
        SlideContent s = new SlideContent();
        s.layout  = "custom";
        s.index   = idx;
        s.title   = "(LLM 파싱 실패)";
        s.bullets = new ArrayList<String>();
        s.bullets.add(rawText.length() > 200 ? rawText.substring(0, 200) + "..." : rawText);
        s.notes   = "";
        return s;
    }
}
