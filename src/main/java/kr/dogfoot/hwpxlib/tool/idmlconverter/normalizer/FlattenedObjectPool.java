package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 평탄화된 객체 풀 — 모든 IDML 콘텐츠 객체를 selfId로 조회 가능.
 */
public class FlattenedObjectPool {
    private final Map<String, FlatObject> objects;
    private final List<FlatObject> orderedList;

    public FlattenedObjectPool() {
        this.objects = new LinkedHashMap<>();
        this.orderedList = new ArrayList<>();
    }

    public void add(FlatObject obj) {
        objects.put(obj.selfId(), obj);
        orderedList.add(obj);
    }

    public FlatObject get(String selfId) {
        return objects.get(selfId);
    }

    public boolean contains(String selfId) {
        return objects.containsKey(selfId);
    }

    public List<FlatObject> all() {
        return orderedList;
    }

    public int size() {
        return orderedList.size();
    }

    /**
     * 지정 페이지의 객체 중 인라인이 아닌 것만 반환 (플로팅 객체).
     */
    public List<FlatObject> getFloatingOnPage(int pageNumber) {
        List<FlatObject> result = new ArrayList<>();
        for (FlatObject obj : orderedList) {
            if (obj.pageNumber() == pageNumber && !obj.isInline()) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * 지정 페이지의 텍스트 프레임만 반환 (인라인 여부 무관).
     */
    public List<FlatObject> getTextFramesOnPage(int pageNumber) {
        List<FlatObject> result = new ArrayList<>();
        for (FlatObject obj : orderedList) {
            if (obj.pageNumber() == pageNumber
                    && obj.contentType() == FlatObject.ContentType.TEXT_FRAME) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * 인라인으로 분류된 객체 중, 지정 스토리에 앵커된 것만 반환.
     */
    public List<FlatObject> getInlinesForStory(String storyId) {
        List<FlatObject> result = new ArrayList<>();
        for (FlatObject obj : orderedList) {
            if (obj.isInline() && storyId.equals(obj.parentStoryId())) {
                result.add(obj);
            }
        }
        return result;
    }

    /**
     * 통계 요약.
     */
    public String summary() {
        int text = 0, image = 0, vector = 0, group = 0, inline = 0;
        for (FlatObject obj : orderedList) {
            switch (obj.contentType()) {
                case TEXT_FRAME: text++; break;
                case IMAGE_FRAME: image++; break;
                case VECTOR_SHAPE: vector++; break;
                case GROUP: group++; break;
            }
            if (obj.isInline()) inline++;
        }
        return String.format("Pool: %d objects (text=%d, image=%d, vector=%d, group=%d, inline=%d)",
                orderedList.size(), text, image, vector, group, inline);
    }
}
