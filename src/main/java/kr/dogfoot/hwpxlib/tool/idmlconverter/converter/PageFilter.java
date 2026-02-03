package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * 페이지 범위 필터.
 * startPage/endPage는 1-based. 0이면 제한 없음.
 */
public class PageFilter {
    private final int startPage;
    private final int endPage;

    public PageFilter(ConvertOptions options) {
        this.startPage = options.startPage();
        this.endPage = options.endPage();
    }

    public PageFilter(int startPage, int endPage) {
        this.startPage = startPage;
        this.endPage = endPage;
    }

    /**
     * 1-based 페이지 번호가 필터 범위에 포함되는지 확인.
     */
    public boolean shouldInclude(int pageNumber) {
        if (startPage > 0 && pageNumber < startPage) {
            return false;
        }
        if (endPage > 0 && pageNumber > endPage) {
            return false;
        }
        return true;
    }

    /**
     * 페이지 번호 목록에서 필터를 적용하여 포함되는 번호만 반환.
     */
    public List<Integer> filter(List<Integer> pageNumbers) {
        List<Integer> result = new ArrayList<Integer>();
        for (int pageNumber : pageNumbers) {
            if (shouldInclude(pageNumber)) {
                result.add(pageNumber);
            }
        }
        return result;
    }

    /**
     * 필터가 모든 페이지를 포함하는지 (범위 제한 없음).
     */
    public boolean includesAll() {
        return startPage <= 0 && endPage <= 0;
    }

    public int startPage() {
        return startPage;
    }

    public int endPage() {
        return endPage;
    }
}
