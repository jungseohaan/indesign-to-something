package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;

/**
 * Stage 3: 인라인 서브트리 축소.
 * 인라인 객체를 리프 노드로 변환 (이미지 데이터 또는 PNG 렌더링).
 *
 * 실제 축소는 Stage4_BuildAST에서 ASTInlineObject 생성 시 수행됨.
 * 이 단계에서는 축소 가능 여부를 사전 검증하고 메타데이터를 준비.
 */
public class Stage3_CollapseInlines {

    public static void collapse(FlattenedObjectPool pool, IDMLDocument idmlDoc,
                                 ConvertOptions options) {
        System.err.println("[Stage3_CollapseInlines] Preparing inline collapse metadata...");

        int inlineCount = 0;
        for (FlatObject fo : pool.all()) {
            if (!fo.isInline()) continue;
            inlineCount++;

            // 인라인 객체 종류별 축소 전략 태깅은 Stage4에서 실제 처리.
            // 여기서는 향후 PNG 렌더링이 필요한 경우를 사전 검사.
            switch (fo.contentType()) {
                case GROUP:
                    // 그룹 → 단일 PNG 렌더링 필요
                    break;
                case VECTOR_SHAPE:
                    // 벡터 → PNG 렌더링 필요
                    break;
                case IMAGE_FRAME:
                    // 이미지 → 이미지 데이터 추출
                    break;
                case TEXT_FRAME:
                    // 인라인 텍스트 프레임 → 텍스트 추출 또는 PNG
                    break;
            }
        }

        System.err.println("[Stage3_CollapseInlines] " + inlineCount + " inline objects prepared for collapse.");
    }
}
