package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.List;

/**
 * 도비라 소단원명 앞 장식 마커(.2 등)는 원본에서 제목 표식으로 쓰이기도 하고,
 * 같은 페이지에 이미 별도 제목이 있을 때 중복 장식으로 남기도 한다.
 */
public final class DoviraSubunitMarkerPolicy {

    private DoviraSubunitMarkerPolicy() {}

    public static boolean isDuplicateMarkerRender(ResolvedData resolvedData, RenderedGroup rg) {
        if (resolvedData == null || rg == null) return false;
        return isDuplicateMarkerStory(resolvedData, rg.parentStoryId());
    }

    public static boolean isDuplicateMarkerStory(ResolvedData resolvedData, String storyId) {
        if (resolvedData == null || storyId == null || storyId.isEmpty()) return false;
        if (resolvedData.ownershipPlans() != null && !resolvedData.ownershipPlans().isEmpty()) {
            return false;
        }
        ResolvedStory story = resolvedData.getStory(storyId);
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        if (!isStandaloneMarkerParagraph(story.paragraphs().get(0))) return false;

        Integer markerId = standaloneMarkerAnchorId(story.paragraphs().get(0));
        if (markerId == null || !hasSamePlaceFloatingMarker(resolvedData, storyId, markerId)) {
            return false;
        }

        String title = firstSubunitTitleAfterMarker(story);
        if (title == null || title.isEmpty()) return false;

        int pageIndex = firstPageIndexForStory(resolvedData, storyId);
        for (String otherStoryId : resolvedData.allStoryIds()) {
            if (storyId.equals(otherStoryId)) continue;
            int otherPageIndex = firstPageIndexForStory(resolvedData, otherStoryId);
            if (pageIndex >= 0 && otherPageIndex >= 0 && otherPageIndex != pageIndex) {
                continue;
            }
            ResolvedStory otherStory = resolvedData.getStory(otherStoryId);
            if (storyContainsTitle(otherStory, title)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDoviraSubunitParagraph(ResolvedParagraph paragraph) {
        return paragraph != null && isDoviraSubunitStyleName(paragraph.styleName());
    }

    public static boolean isDoviraSubunitStyleName(String styleName) {
        if (styleName == null || styleName.isEmpty()) return false;
        if ("01_도비라-소단원명".equals(styleName)) return true;
        return styleName.endsWith(":01_도비라-소단원명")
                || styleName.endsWith("/01_도비라-소단원명");
    }

    private static boolean isStandaloneMarkerParagraph(ResolvedParagraph paragraph) {
        if (!isDoviraSubunitParagraph(paragraph)) return false;
        List<ResolvedRun> runs = paragraph.runs();
        if (runs == null || runs.isEmpty()) return false;
        boolean hasAnchor = false;
        for (ResolvedRun run : runs) {
            if (run == null) continue;
            if (run.isInlineAnchor()) {
                hasAnchor = true;
                continue;
            }
            String text = normalize(run.text());
            if (!text.isEmpty()) return false;
        }
        return hasAnchor;
    }

    private static Integer standaloneMarkerAnchorId(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return null;
        Integer id = null;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null) continue;
            if (run.isInlineAnchor()) {
                if (id != null) return null;
                id = run.anchoredObjectId();
                continue;
            }
            String text = normalize(run.text());
            if (!text.isEmpty()) return null;
        }
        return id;
    }

    private static boolean hasSamePlaceFloatingMarker(ResolvedData resolvedData,
                                                      String storyId,
                                                      int markerId) {
        if (resolvedData.allRenderedFloatingItems() == null) return false;
        RenderedGroup inlineMarker = null;
        for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != markerId) continue;
            if (storyId.equals(rg.parentStoryId())) {
                inlineMarker = rg;
                break;
            }
        }
        if (inlineMarker == null || inlineMarker.bounds() == null) return false;
        for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg == inlineMarker || rg.id() != markerId) continue;
            if (storyId.equals(rg.parentStoryId())) continue;
            if (rg.bounds() == null) continue;
            if (samePage(inlineMarker, rg) && closeBounds(inlineMarker.bounds(), rg.bounds())) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePage(RenderedGroup a, RenderedGroup b) {
        return a.pageIndex() < 0 || b.pageIndex() < 0 || a.pageIndex() == b.pageIndex();
    }

    private static boolean closeBounds(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        double acx = (a[1] + a[3]) / 2.0;
        double acy = (a[0] + a[2]) / 2.0;
        double bcx = (b[1] + b[3]) / 2.0;
        double bcy = (b[0] + b[2]) / 2.0;
        return Math.abs(acx - bcx) <= 2.0 && Math.abs(acy - bcy) <= 2.0;
    }

    private static String firstSubunitTitleAfterMarker(ResolvedStory story) {
        List<ResolvedParagraph> paragraphs = story.paragraphs();
        for (int i = 1; i < paragraphs.size(); i++) {
            ResolvedParagraph paragraph = paragraphs.get(i);
            if (!isDoviraSubunitParagraph(paragraph)) continue;
            String text = paragraphText(paragraph);
            if (!text.isEmpty()) return text;
        }
        return null;
    }

    private static boolean storyContainsTitle(ResolvedStory story, String title) {
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            String text = paragraphText(paragraph);
            if (text.equals(title) || text.endsWith(" " + title) || text.contains(title)) {
                return true;
            }
        }
        return false;
    }

    private static String paragraphText(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.text() == null) continue;
            sb.append(run.text());
        }
        return normalize(sb.toString());
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int firstPageIndexForStory(ResolvedData resolvedData, String storyId) {
        if (storyId == null) return -1;
        List<ResolvedTextFrame> frames = resolvedData.getTextFramesForStory(storyId);
        if (frames == null || frames.isEmpty()) return -1;
        int best = -1;
        for (ResolvedTextFrame frame : frames) {
            if (frame == null || frame.pageIndex() < 0) continue;
            if (best < 0 || frame.pageIndex() < best) {
                best = frame.pageIndex();
            }
        }
        return best;
    }
}
