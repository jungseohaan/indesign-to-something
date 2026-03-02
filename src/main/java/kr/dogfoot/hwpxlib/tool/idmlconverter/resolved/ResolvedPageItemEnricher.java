package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * resolved pageItems 데이터로 ASTFigure 블록을 보강한다.
 *
 * 현재: 매칭만 수행 (ID 연결 확인).
 * 위치/크기 보정은 비활성화 — 벡터 도형이 IDML 좌표계 기준으로
 * 래스터화된 후 넣어지므로, visibleBounds로 좌표만 바꾸면
 * 이미 렌더링된 이미지가 찌그러진다.
 *
 * TODO: 래스터화 단계(Stage4_BuildAST)에서 resolved visibleBounds를
 *       렌더링 영역으로 사용하여 정확한 좌표+이미지를 동시 생성.
 */
public class ResolvedPageItemEnricher {

    /**
     * ASTDocument의 모든 ASTFigure 블록에 resolved pageItem 정보를 반영한다.
     */
    public static void enrich(ASTDocument astDoc, ResolvedData resolved) {
        if (resolved.pageItems().isEmpty()) return;

        int matched = 0;
        int total = 0;

        for (ASTSection section : astDoc.sections()) {
            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTFigure) {
                    total++;
                    ASTFigure fig = (ASTFigure) block;
                    if (matchFigure(fig, resolved)) {
                        matched++;
                    }
                }
            }
        }

        if (total > 0) {
            System.err.println("[ResolvedPageItemEnricher] " + matched + "/" + total
                    + " figures matched with resolved pageItem data");
        }
    }

    /**
     * ASTFigure와 resolved pageItem을 ID로 매칭한다.
     * 현재는 매칭 확인만 수행. 향후 래스터화 통합 시 활용.
     */
    private static boolean matchFigure(ASTFigure fig, ResolvedData resolved) {
        String sourceId = fig.sourceId();
        if (sourceId == null) return false;

        ResolvedPageItem item = resolved.getPageItemByIdmlId(sourceId);
        return item != null;
    }
}
