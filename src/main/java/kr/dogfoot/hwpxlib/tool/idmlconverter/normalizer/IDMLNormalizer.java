package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ProgressReporter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

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
        return normalize(idmlDoc, options, sourceFileName, null, ProgressReporter.NONE);
    }

    public static ASTDocument normalize(IDMLDocument idmlDoc,
                                         ConvertOptions options,
                                         String sourceFileName,
                                         ResolvedData resolvedData) {
        return normalize(idmlDoc, options, sourceFileName, resolvedData, ProgressReporter.NONE);
    }

    public static ASTDocument normalize(IDMLDocument idmlDoc,
                                         ConvertOptions options,
                                         String sourceFileName,
                                         ResolvedData resolvedData,
                                         ProgressReporter reporter) {
        System.err.println("[IDMLNormalizer] Starting 4-stage normalization...");

        // Stage 1: 컨테이너 평탄화
        reporter.reportProgress(5, 100, "IDML 정규화 중... (1/4 평탄화)");
        FlattenedObjectPool pool = Stage1_Flatten.flatten(idmlDoc);

        // Stage 2: 인라인/플로팅 분류
        reporter.reportProgress(5, 100, "IDML 정규화 중... (2/4 인라인 분류)");
        Stage2_InlineDetect.classify(pool, idmlDoc);

        // Stage 3: 인라인 서브트리 축소
        reporter.reportProgress(6, 100, "IDML 정규화 중... (3/4 인라인 정리)");
        Stage3_CollapseInlines.collapse(pool, idmlDoc, options);

        // Stage 4: 스토리 우선 AST 구축 (resolved 좌표 활용, 페이지별 진행률)
        reporter.reportProgress(6, 100, "IDML 정규화 중... (4/4 AST 구축)");
        ASTDocument ast = Stage4_BuildAST.build(pool, idmlDoc, options, sourceFileName, resolvedData, reporter);

        // orphan injection 제외 ID 수집 — 클리핑 도형의 자식 (재귀)
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread spread : idmlDoc.spreads()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape vs : spread.vectorShapes()) {
                collectClippedChildIdsRecursive(vs, ast);
            }
        }

        // IDML selfId → z-order 맵 빌드 (orphan graphic z-order 복원용)
        // 풀의 모든 객체 + 그룹 자식 객체도 부모 그룹 z-order로 등록
        java.util.Map<String, Integer> zMap = new java.util.HashMap<>();
        for (FlatObject fo : pool.all()) {
            zMap.put(fo.selfId(), fo.zOrder());
        }
        // 그룹 내부 자식(TextFrame, ImageFrame, VectorShape)도 부모 z-order로 등록
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread spread : idmlDoc.spreads()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGroup grp : spread.groups()) {
                addGroupChildrenToZMap(zMap, grp, grp.zOrder());
            }
        }
        ast.idmlZOrders(zMap);

        System.err.println("[IDMLNormalizer] Normalization complete. Sections: " + ast.sections().size());
        return ast;
    }

    /**
     * 그룹 내부 자식 객체의 selfId를 부모 그룹의 z-order로 등록 (재귀).
     */
    private static void addGroupChildrenToZMap(java.util.Map<String, Integer> zMap,
                                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGroup grp,
                                                int rootZ) {
        // 파싱 성공 여부와 무관하게 모든 자식 Self ID를 루트 그룹의 z-order로 등록
        for (String childId : grp.allChildSelfIds()) {
            zMap.putIfAbsent(childId, rootZ);
        }
        // 하위 그룹도 같은 루트 z-order로 재귀 처리
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGroup child : grp.childGroups()) {
            addGroupChildrenToZMap(zMap, child, rootZ);
        }
    }

    /**
     * 벡터 도형의 clippedChildren ID를 재귀적으로 수집하여 orphanExcludeIds에 추가.
     */
    private static void collectClippedChildIdsRecursive(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape vs,
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument ast) {
        if (vs.hasClippedChildren()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape child : vs.clippedChildren()) {
                if (child.selfId() != null) {
                    ast.addOrphanExcludeId(child.selfId());
                }
                collectClippedChildIdsRecursive(child, ast);
            }
        }
        if (vs.hasClippedChild()) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape child = vs.clippedChild();
            if (child.selfId() != null) {
                ast.addOrphanExcludeId(child.selfId());
            }
            collectClippedChildIdsRecursive(child, ast);
        }
    }
}
