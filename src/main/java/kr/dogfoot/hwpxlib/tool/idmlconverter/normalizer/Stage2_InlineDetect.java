package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;

import java.util.List;
import java.util.Map;

/**
 * Stage 2: 인라인/플로팅 분류.
 * 각 FlatObject에 isInline, parentStoryId, anchorIndex 설정.
 */
public class Stage2_InlineDetect {

    public static void classify(FlattenedObjectPool pool, IDMLDocument idmlDoc) {
        System.err.println("[Stage2_InlineDetect] Classifying inline vs floating...");

        // 1) Story 내 CharacterRun에서 참조된 인라인 프레임/그래픽 탐지
        classifyFromStories(pool, idmlDoc);

        // 2) AnchoredObjectSetting 기반 분류 (IDMLTextFrame.anchoredPosition)
        classifyFromAnchoredSettings(pool);

        // 통계 출력
        int inlineCount = 0;
        for (FlatObject fo : pool.all()) {
            if (fo.isInline()) inlineCount++;
        }
        System.err.println("[Stage2_InlineDetect] Inline: " + inlineCount
                + ", Floating: " + (pool.size() - inlineCount));
    }

    /**
     * Story의 CharacterRun에 있는 inlineFrames/inlineGraphics와 Pool 객체를 매칭.
     */
    private static void classifyFromStories(FlattenedObjectPool pool, IDMLDocument idmlDoc) {
        for (Map.Entry<String, IDMLStory> entry : idmlDoc.stories().entrySet()) {
            String storyId = entry.getKey();
            IDMLStory story = entry.getValue();

            int charOffset = 0;
            for (IDMLParagraph para : story.paragraphs()) {
                for (IDMLCharacterRun run : para.characterRuns()) {
                    // 텍스트 내용의 문자 수 추적
                    String text = run.content();
                    int textLen = (text != null) ? text.length() : 0;

                    // 인라인 텍스트 프레임
                    for (IDMLTextFrame inlineTf : run.inlineFrames()) {
                        FlatObject fo = pool.get(inlineTf.selfId());
                        if (fo != null && !fo.isInline()) {
                            fo.isInline(true);
                            fo.parentStoryId(storyId);
                            fo.anchorIndex(charOffset);
                        }
                    }

                    // 인라인 그래픽 (Rectangle, Polygon, Oval, Group)
                    for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                        FlatObject fo = pool.get(ig.selfId());
                        if (fo != null && !fo.isInline()) {
                            fo.isInline(true);
                            fo.parentStoryId(storyId);
                            fo.anchorIndex(charOffset);
                        }
                        // 재귀: 그룹 내 자식도 인라인 마킹
                        markChildrenInline(ig, pool, storyId, charOffset);
                    }

                    charOffset += textLen;
                }
            }
        }
    }

    /**
     * InlineGraphic의 자식 (그룹 내 텍스트프레임/그래픽)도 인라인으로 마킹.
     */
    private static void markChildrenInline(IDMLCharacterRun.InlineGraphic ig,
                                            FlattenedObjectPool pool,
                                            String storyId, int anchorIndex) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            FlatObject fo = pool.get(childTf.selfId());
            if (fo != null && !fo.isInline()) {
                fo.isInline(true);
                fo.parentStoryId(storyId);
                fo.anchorIndex(anchorIndex);
            }
        }
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            FlatObject fo = pool.get(childIg.selfId());
            if (fo != null && !fo.isInline()) {
                fo.isInline(true);
                fo.parentStoryId(storyId);
                fo.anchorIndex(anchorIndex);
            }
            markChildrenInline(childIg, pool, storyId, anchorIndex);
        }
    }

    /**
     * AnchoredObjectSetting 기반 인라인 분류.
     * IDMLTextFrame에 anchoredPosition이 설정된 경우 처리.
     */
    private static void classifyFromAnchoredSettings(FlattenedObjectPool pool) {
        for (FlatObject fo : pool.all()) {
            if (fo.isInline()) continue; // 이미 인라인으로 분류됨

            if (fo.contentType() == FlatObject.ContentType.TEXT_FRAME) {
                IDMLTextFrame tf = (IDMLTextFrame) fo.sourceObject();
                String anchored = tf.anchoredPosition();
                if (anchored != null) {
                    if ("InlinePosition".equals(anchored) || "AboveLinePosition".equals(anchored)) {
                        fo.isInline(true);
                        // parentStoryId는 Story에서 이 프레임을 참조하는 곳에서 결정됨
                        // 여기서는 anchoredPosition만 기반으로 마킹
                    }
                }
            }
        }
    }
}
