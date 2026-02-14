package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;

/**
 * IDML 정규화 진입점 — 4단계 파이프라인 오케스트레이션.
 *
 * Stage 1: 컨테이너 평탄화 (Group/Frame/Rectangle 계층 제거)
 * Stage 2: 인라인 감지 (ParentStory, AnchoredObjectSetting으로 분류)
 * Stage 3: 인라인 서브트리 축소 (인라인 객체 → 리프 노드)
 * Stage 4: 스토리 우선 AST 구축
 */
public class IDMLNormalizer {

    public static ASTDocument normalize(IDMLDocument idmlDoc,
                                         ConvertOptions options,
                                         String sourceFileName) {
        System.err.println("[IDMLNormalizer] Starting 4-stage normalization...");

        // Stage 1: 컨테이너 평탄화
        FlattenedObjectPool pool = Stage1_Flatten.flatten(idmlDoc);

        // Stage 2: 인라인/플로팅 분류
        Stage2_InlineDetect.classify(pool, idmlDoc);

        // Stage 3: 인라인 서브트리 축소
        Stage3_CollapseInlines.collapse(pool, idmlDoc, options);

        // Stage 4: 스토리 우선 AST 구축
        ASTDocument ast = Stage4_BuildAST.build(pool, idmlDoc, options, sourceFileName);

        System.err.println("[IDMLNormalizer] Normalization complete. Sections: " + ast.sections().size());
        return ast;
    }
}
