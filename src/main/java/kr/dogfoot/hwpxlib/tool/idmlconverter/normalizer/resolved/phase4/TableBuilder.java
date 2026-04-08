package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SPEC-013 Phase 4: 테이블 포함 TextFrame → ASTTable / ASTFigure 변환.
 *
 * <p>{@code ResolvedToASTBuilder.placeTablesFromIDML / hasInlineObjectsInTable / renderTableAsImage}
 * 에서 stateless static helper로 발췌. 동작은 동일.</p>
 *
 * <p>의존: ctx.idmlDir, resolvedData, scaleFactor, basePath, toSectionIndex, loadIDMLStory,
 * ensureIdmlInfra + idmlDocumentSupplier/colorResolverSupplier/imageLoaderSupplier.</p>
 */
public final class TableBuilder {

    private TableBuilder() {}

    public static void placeTablesFromIDML(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.idmlDir == null) return;
        int tableCount = 0;

        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            // Story에 테이블이 있는지 먼저 확인
            String storyId = tf.storyId();
            if (storyId == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
            if (idmlStory == null || !idmlStory.hasTables()) continue;

            // inline + non-editable이면 테이블이 있어도 건너뜀 (단, 테이블 포함 TF는 예외)
            if (tf.isInline() && ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            if (!tf.isInline() && !ctx.resolvedData.isEditableTextFrame(tf.id())) continue;

            // 페이지 결정 (document offset → section index 매핑)
            int pageIdx = ctx.toSectionIndex.applyAsInt(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 좌표 계산
            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            ResolvedPage rPage = (pageIdx < ctx.resolvedData.pages().size())
                    ? ctx.resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;
            boolean gbAlreadyPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
            double x = gbAlreadyPageRelative ? gb[1] : (gb[1] - pageLeft);
            double y = gb[0] - pageTop;

            long hx = CoordinateConverter.pointsToHwpunits(x);
            long hy = CoordinateConverter.pointsToHwpunits(y);

            // 프레임 insetSpacing 반영 (테이블 위치에 인셋 추가)
            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                hy += CoordinateConverter.pointsToHwpunits(inset[0]); // top
                hx += CoordinateConverter.pointsToHwpunits(inset[1]); // left
            }

            // 테이블 앞 텍스트 높이 계산 (테이블 Y 오프셋)
            // IDML paragraphIndexBefore로 테이블 앞 단락 수 파악
            // 중첩 테이블 감지: selfId가 다른 테이블의 selfId를 접두사로 포함하면 중첩
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> allTables = idmlStory.tables();
            // 중첩 테이블 부모 탐색: O(n) — selfId를 HashMap에 등록 후 접두사로 부모 lookup
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> tableById = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                tableById.put(t.selfId(), t);
            }
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> parentTableMap = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                // selfId 형식: "부모ID/Cell:N/Table" — 접두사에서 부모 테이블 ID 추출
                String sid = t.selfId();
                int lastSlash = sid.lastIndexOf('/');
                if (lastSlash > 0) {
                    // 부모 후보: "부모ID/Cell:N" → 그 앞의 테이블 ID
                    String parentPart = sid.substring(0, lastSlash);
                    int prevSlash = parentPart.lastIndexOf('/');
                    if (prevSlash > 0) {
                        String candidateId = parentPart.substring(0, prevSlash);
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parent = tableById.get(candidateId);
                        if (parent != null) {
                            parentTableMap.put(sid, parent);
                        }
                    }
                }
            }

            long tableYOffset = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable : allTables) {
                long thisX = hx;
                long thisY = hy;

                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parentTable = parentTableMap.get(idmlTable.selfId());
                if (parentTable != null) {
                    // 중첩 테이블: 부모 테이블의 행 높이를 합산하여 y 오프셋 계산
                    // selfId에서 셀 인덱스 추출: "u1cf74i1cf91i6i1cf9b" → "i6" → 셀 row=6
                    String parentId = parentTable.selfId();
                    String remainder = idmlTable.selfId().substring(parentId.length()); // "i6i1cf9b"
                    int cellRowIdx = -1;
                    if (remainder.startsWith("i")) {
                        // "i6i..." → 6
                        String cellPart = remainder.substring(1);
                        int nextI = cellPart.indexOf('i');
                        String rowStr = (nextI > 0) ? cellPart.substring(0, nextI) : cellPart;
                        try { cellRowIdx = Integer.parseInt(rowStr); } catch (NumberFormatException e) { /* ignore */ }
                    }
                    if (cellRowIdx >= 0) {
                        // 부모 테이블의 row 0 ~ cellRowIdx-1 높이 합산
                        long rowHeightSum = 0;
                        int ri = 0;
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow pr : parentTable.rows()) {
                            if (ri >= cellRowIdx) break;
                            rowHeightSum += CoordinateConverter.pointsToHwpunits(pr.rowHeight());
                            ri++;
                        }
                        thisY = hy + rowHeightSum;
                    }
                } else {
                    // 최상위 테이블
                    int parasBefore = idmlTable.paragraphIndexBefore();
                    if (parasBefore > 0) {
                        double estLineHeight = 8.0 * ctx.scaleFactor;
                        tableYOffset = CoordinateConverter.pointsToHwpunits(parasBefore * estLineHeight);
                    }
                    thisY = hy + tableYOffset;
                }

                // 테이블 셀 복잡도 체크: 인라인 객체가 포함된 테이블은 플로팅 이미지로 변환 시도
                if (hasInlineObjectsInTable(idmlTable)) {
                    ASTFigure fig = renderTableAsImage(ctx, idmlTable, tf, thisX, thisY, pageIdx);
                    if (fig != null) {
                        sections.get(pageIdx).addBlock(fig);
                        tableCount++;
                        continue;
                    }
                    // PNG 없으면 일반 테이블로 폴백
                }

                ctx.ensureIdmlInfra.run();
                ASTTable astTable = ASTTableConverter.convertTableSimple(
                        idmlTable, thisX, thisY, tf.zOrder(),
                        ctx.idmlDocumentSupplier.get(), ctx.colorResolverSupplier.get(),
                        ctx.imageLoaderSupplier.get(), ctx.resolvedData);
                sections.get(pageIdx).addBlock(astTable);
                tableCount++;
            }
        }

        if (tableCount > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4: " + tableCount + " tables from IDML");
        }
    }

    /**
     * 테이블이 배경 PNG fallback 대상인지 판정.
     * 셀 텍스트가 30자 미만이면서 인라인 객체를 포함하면 → 배경 PNG로 처리.
     * (짧은 텍스트 + 인라인 배지/아이콘 = 글상자 변환 시 레이아웃 깨짐)
     */
    private static boolean hasInlineObjectsInTable(kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table) {
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow row : table.rows()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell : row.cells()) {
                boolean hasInline = false;
                int textLen = 0;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para : cell.paragraphs()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : para.characterRuns()) {
                        if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) hasInline = true;
                        if (run.inlineFrames() != null && !run.inlineFrames().isEmpty()) hasInline = true;
                        if (run.content() != null) textLen += run.content().replace("\uFFFC", "").trim().length();
                    }
                }
                if (hasInline && textLen < 30) return true;
            }
        }
        return false;
    }

    /**
     * 인라인 객체가 포함된 테이블을 rendered PNG 이미지(ASTFigure)로 변환.
     * renderedFloatingItems에서 type="table_inline"인 항목을 찾아 사용.
     */
    private static ASTFigure renderTableAsImage(ResolvedBuildContext ctx,
                                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                                ResolvedTextFrame tf, long x, long y, int pageIdx) {
        if (ctx.basePath == null || ctx.resolvedData == null) return null;

        // TextFrame의 DOM ID로 rendered PNG 찾기
        String tfDomId = tf.id();
        int domId = -1;
        try { domId = Integer.parseInt(tfDomId); } catch (NumberFormatException e) { return null; }

        // 1. 직접 파일 (table_XXXXX.png) 또는 renderedFloatingItems에서 검색
        File directFile = new File(ctx.basePath, "rendered_frames/table_" + domId + ".png");
        File pngFile = null;
        double[] rgBounds = null;

        if (directFile.exists()) {
            pngFile = directFile;
        }
        // renderedFloatingItems에서도 검색
        if (pngFile == null) {
            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == domId && rg.file() != null) {
                    File f = new File(ctx.basePath, rg.file());
                    if (f.exists()) {
                        pngFile = f;
                        rgBounds = rg.bounds();
                        break;
                    }
                }
            }
        }
        if (pngFile == null) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
            if (img == null) return null;

            ASTFigure fig = new ASTFigure();
            fig.sourceId("tbl_" + table.selfId());
            fig.x(x);
            fig.y(y);
            fig.zOrder(tf.zOrder());
            fig.imageData(imageData);
            fig.imageFormat("png");
            fig.pixelWidth(img.getWidth());
            fig.pixelHeight(img.getHeight());

            // 크기: resolved bounds 또는 테이블 크기
            if (rgBounds != null && rgBounds.length >= 4) {
                double bw = Math.abs(rgBounds[3] - rgBounds[1]) * ctx.scaleFactor;
                double bh = Math.abs(rgBounds[2] - rgBounds[0]) * ctx.scaleFactor;
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
            } else {
                // 테이블 행 높이 + 컬럼 너비 합산
                long tw = 0, th = 0;
                for (double cw : table.columnWidths()) tw += CoordinateConverter.pointsToHwpunits(cw);
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow r : table.rows())
                    th += CoordinateConverter.pointsToHwpunits(r.rowHeight());
                fig.width(tw);
                fig.height(th);
            }
            return fig;
        } catch (Exception e) {
            return null;
        }
    }
}
