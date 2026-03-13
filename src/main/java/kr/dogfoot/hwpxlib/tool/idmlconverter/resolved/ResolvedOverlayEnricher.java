package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

import java.util.Iterator;
import java.util.List;

/**
 * IMAGE 그룹의 오버레이 텍스트프레임에 resolved.json 페이지 절대 좌표를 설정한다.
 *
 * IDML 그룹 내 transform 체인 계산 대신 InDesign이 계산한 정확한 좌표를 직접 사용하여
 * 오버레이 텍스트프레임을 페이지 레벨에 독립 배치할 수 있게 한다.
 */
public class ResolvedOverlayEnricher {

    public static void enrich(ASTDocument astDoc, ResolvedData resolved) {
        if (astDoc == null || resolved == null) return;

        for (ASTSection section : astDoc.sections()) {
            for (ASTBlock block : section.blocks()) {
                visitBlock(block, resolved);
            }
        }
    }

    /** 오버레이가 속한 페이지의 크기를 조회한다. 페이지를 찾지 못하면 첫 페이지 폴백. */
    private static double[] getPageSize(ResolvedPageItem item, ResolvedData resolved) {
        if (item != null && item.pageIndex() >= 0) {
            ResolvedPage page = resolved.getPage(item.pageIndex());
            if (page != null) return new double[]{page.width(), page.height()};
        }
        if (!resolved.pages().isEmpty()) {
            ResolvedPage p = resolved.pages().get(0);
            return new double[]{p.width(), p.height()};
        }
        return new double[]{0, 0};
    }

    private static void visitBlock(ASTBlock block, ResolvedData resolved) {
        if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            for (ASTParagraph para : tf.paragraphs()) {
                visitParagraph(para, resolved);
            }
        } else if (block.blockType() == ASTBlock.BlockType.TABLE) {
            ASTTable table = (ASTTable) block;
            for (ASTTableRow row : table.rows()) {
                for (ASTTableCell cell : row.cells()) {
                    for (ASTParagraph para : cell.paragraphs()) {
                        visitParagraph(para, resolved);
                    }
                }
            }
        }
    }

    private static void visitParagraph(ASTParagraph para, ResolvedData resolved) {
        if (para.items() == null) return;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() != ASTInlineItem.ItemType.INLINE_OBJECT) continue;
            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.kind() != ASTInlineObject.ObjectKind.IMAGE) continue;

            List<ASTInlineObject> overlays = obj.overlayFrames();
            if (overlays == null || overlays.isEmpty()) continue;

            Iterator<ASTInlineObject> it = overlays.iterator();
            while (it.hasNext()) {
                ASTInlineObject overlay = it.next();
                if (!enrichOverlay(overlay, resolved)) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 오버레이 텍스트프레임에 resolved 페이지 좌표를 설정한다.
     *
     * resolved.json의 geometricBounds는 InDesign DOM이 반환하는
     * 페이지 기준 상대 좌표 (rulerOrigin=PAGE_ORIGIN)이므로
     * 페이지 오프셋 빼기 없이 직접 사용한다.
     */
    /**
     * @return true = 유지, false = 페이지 범위 밖 → 제거 대상
     */
    private static boolean enrichOverlay(ASTInlineObject overlay, ResolvedData resolved) {
        String sourceId = overlay.sourceId();
        if (sourceId == null) return true;

        ResolvedPageItem item = resolved.getPageItemByIdmlId(sourceId);
        if (item == null || item.geometricBounds() == null) return true;

        double[] gb = item.geometricBounds(); // [top, left, bottom, right] (points, normalized)
        double x = gb[1];
        double y = gb[0];
        double w = gb[3] - gb[1];
        double h = gb[2] - gb[0];

        // 페이지 범위 검증 — 오버레이가 속한 페이지 크기 기준으로 판별
        double[] pageSize = getPageSize(item, resolved);
        double pgW = pageSize[0];
        double pgH = pageSize[1];
        if (pgW > 0 && pgH > 0) {
            if (x < -10 || y < -10 || x + w > pgW + 10 || y + h > pgH + 10) {
                System.out.println("[ResolvedOverlay] REMOVE " + sourceId
                        + " — out of page bounds: (" + String.format("%.1f", x)
                        + "," + String.format("%.1f", y) + ") page("
                        + String.format("%.0f", pgW) + "x"
                        + String.format("%.0f", pgH) + ")");
                return false;
            }
        }

        overlay.resolvedPageX(CoordinateConverter.pointsToHwpunits(x));
        overlay.resolvedPageY(CoordinateConverter.pointsToHwpunits(y));
        overlay.resolvedWidth(CoordinateConverter.pointsToHwpunits(w));
        overlay.resolvedHeight(CoordinateConverter.pointsToHwpunits(h));

        System.out.println("[ResolvedOverlay] " + sourceId
                + " → page(" + String.format("%.1f", x) + "," + String.format("%.1f", y) + ") pts"
                + " size(" + String.format("%.1f", w) + "x" + String.format("%.1f", h) + ") pts"
                + " → HWPUNIT(" + overlay.resolvedPageX() + "," + overlay.resolvedPageY() + ")"
                + " " + overlay.resolvedWidth() + "x" + overlay.resolvedHeight());
        return true;
    }
}
