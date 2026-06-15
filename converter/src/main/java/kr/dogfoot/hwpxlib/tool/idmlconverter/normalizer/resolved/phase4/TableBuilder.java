package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.GroupedFlowStackPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TableFrameOwnershipPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.SimpleButtonLabelInlineFactory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_7.NumberedSideHeadTableNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * SPEC-013 Phase 4 + SPEC-017 v2: 테이블 포함 TextFrame → ASTTable / ASTFigure 변환.
 *
 * <p>분기 정책 (SPEC-017):</p>
 * <ul>
 *   <li><b>중첩 테이블</b> + {@code nestedTableForcesPng} → 표 전체 PNG fallback</li>
 *   <li><b>cell-level 모드</b>(기본) → ASTTable로 변환 후 트리거 셀의 인라인 객체만
 *       개별 floating ASTFigure로 추출. 본문 셀은 ASTTable로 유지되어 검색/편집 가능</li>
 *   <li><b>preferCellLevel = false</b>(레거시) → 인라인 감지 시 표 전체 PNG fallback</li>
 *   <li>PNG fallback이 필요한데 PNG 못 찾으면 ASTTable로 폴백 + "배지 중복 위험" 카운트</li>
 * </ul>
 *
 * <p>의존: ctx.idmlDir, resolvedData, scaleFactor, basePath, toSectionIndex, loadIDMLStory,
 * tableQualityGate, debugAst.</p>
 */
public final class TableBuilder {

    private TableBuilder() {}

    /** 변환 종료 시 stderr에 출력할 분기 카운터. */
    private static final class Phase4Report {
        int asTableCleanCount;          // ASTTable, 인라인 추출 0건
        int asTableWithExtraction;      // ASTTable + 셀 인라인 추출 1건 이상
        int totalCellsExtracted;        // 추출된 셀 수
        int totalInlinesExtracted;      // 추출된 인라인 객체 수
        int wholeTablePngRendered;      // 표 전체 PNG (rendered_frames에서 발견)
        int wholeTablePngForced;        // 중첩 테이블 등 정책상 강제 PNG
        int pngMissingFallback;         // 인라인 있지만 PNG 없어서 ASTTable로 떨어진 케이스 (배지 중복 위험)
        int cellBackgroundsAbsorbed;    // 별도 page_object → 실제 셀 fill로 흡수한 수
        int tableBordersAbsorbed;       // 별도 page_object 선분/외곽선 → 실제 셀 border로 흡수한 수
        int detachedInlinePageLevel;    // table-only inline TF → page-level table
        int tableOnlyPlansPlaced;       // ObjectPlan(text_frame:table_only) → ASTTable
        int duplicateInlineTablesRemoved;
        int total;
    }

    private static final class TablePlacement {
        final ASTTable table;
        final int pageIdx;

        TablePlacement(ASTTable table, int pageIdx) {
            this.table = table;
            this.pageIdx = pageIdx;
        }
    }

    public static void placeTablesFromIDML(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.idmlDir == null) return;
        ConversionConfig.TableQualityGateConfig policy = ctx.tableQualityGate != null
                ? ctx.tableQualityGate
                : new ConversionConfig.TableQualityGateConfig();
        Phase4Report report = new Phase4Report();
        // 같은 Table이 연결 글상자 체인의 복수 TF에서 중복 처리되는 것을 방지
        Set<String> processedTableIds = new HashSet<>();
        Set<String> pageLevelTableSourceIds = new HashSet<>();

        placeTableOnlyPlans(ctx, sections, processedTableIds, report);

        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            String storyId = tf.storyId();
            if (storyId == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
            if (idmlStory == null || !idmlStory.hasTables()) continue;
            if (allTablesConsumedByAnchoredPlan(ctx, idmlStory)) continue;

            if (tf.onHiddenLayer() || tf.nonprinting()) continue;
            boolean editableTextFrame = ctx.resolvedData.isEditableTextFrame(tf.id());
            boolean tableAnchorOnlyFrame = TableFrameOwnershipPolicy.isTableAnchorOnlyFrame(tf);
            boolean detachedInlineTableFrame =
                    TableFrameOwnershipPolicy.shouldPlaceInlineTableAsPageLevel(ctx, tf, idmlStory);
            if (detachedInlineTableFrame) report.detachedInlinePageLevel++;
            // Inline TextFrame에 포함된 table은 해당 anchor 위치에서 ASTInlineObject.inlineTables로
            // 배치한다. 단, 부모 page item 없이 자기 pageIndex/bounds만 가진 table-only inline
            // frame은 anchor flow가 아니라 frame 자체가 배치 소유자다.
            if (tf.isInline() && !detachedInlineTableFrame) continue;
            if (!tf.isInline() && !editableTextFrame && !tableAnchorOnlyFrame) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

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

            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                hy += CoordinateConverter.pointsToHwpunits(inset[0]);
                hx += CoordinateConverter.pointsToHwpunits(inset[1]);
            }

            // 중첩 테이블 부모 탐색 (selfId 접두사 매칭)
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> allTables = idmlStory.tables();
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> tableById = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                tableById.put(t.selfId(), t);
            }
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> parentTableMap = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                String sid = t.selfId();
                int lastSlash = sid.lastIndexOf('/');
                if (lastSlash > 0) {
                    String parentPart = sid.substring(0, lastSlash);
                    int prevSlash = parentPart.lastIndexOf('/');
                    if (prevSlash > 0) {
                        String candidateId = parentPart.substring(0, prevSlash);
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parent = tableById.get(candidateId);
                        if (parent != null) parentTableMap.put(sid, parent);
                    }
                }
            }

            long tableYOffset = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable : allTables) {
                if (ctx.isAnchoredTableSource(idmlTable.selfId())) continue;
                // 같은 Table이 TF 연결 체인에서 중복 배치되는 것을 방지
                if (!processedTableIds.add(idmlTable.selfId())) continue;

                int tablePageIdx = tablePlacementPageIndex(ctx, idmlTable, pageIdx);
                if (tablePageIdx < 0 || tablePageIdx >= sections.size()) continue;

                long thisX = hx;
                long thisY = hy;
                boolean placedFromResolvedTableBounds = false;

                double[] resolvedTableBounds = ctx.resolvedData != null
                        ? ctx.resolvedData.getTablePlacementBounds(idmlTable.selfId()) : null;
                if (resolvedTableBounds != null && resolvedTableBounds.length >= 4) {
                    double scale = ctx.resolvedData.scaleFactor();
                    thisX = CoordinateConverter.pointsToHwpunits(resolvedTableBounds[1] * scale);
                    thisY = CoordinateConverter.pointsToHwpunits(resolvedTableBounds[0] * scale);
                    placedFromResolvedTableBounds = true;
                }

                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parentTable = parentTableMap.get(idmlTable.selfId());
                boolean isNested = parentTable != null;

                if (placedFromResolvedTableBounds) {
                    // InDesign DOM already gave the table's page-relative bounds.
                    // Prefer that over story-flow estimates so tables stay below their preceding text.
                } else if (isNested) {
                    String parentId = parentTable.selfId();
                    String remainder = idmlTable.selfId().substring(parentId.length());
                    int cellRowIdx = -1;
                    if (remainder.startsWith("i")) {
                        String cellPart = remainder.substring(1);
                        int nextI = cellPart.indexOf('i');
                        String rowStr = (nextI > 0) ? cellPart.substring(0, nextI) : cellPart;
                        try { cellRowIdx = Integer.parseInt(rowStr); } catch (NumberFormatException e) { /* ignore */ }
                    }
                    if (cellRowIdx >= 0) {
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
                    int parasBefore = idmlTable.paragraphIndexBefore();
                    if (parasBefore > 0) {
                        double estLineHeight = 8.0 * ctx.scaleFactor;
                        tableYOffset = CoordinateConverter.pointsToHwpunits(parasBefore * estLineHeight);
                    }
                    thisY = hy + tableYOffset;
                }

                report.total++;
                ASTSection section = sections.get(tablePageIdx);
                if (detachedInlineTableFrame) pageLevelTableSourceIds.add(idmlTable.selfId());

                // 분기 1: 중첩 테이블 + 정책상 강제 PNG
                if (isNested && policy.nestedTableForcesPng) {
                    ASTFigure fig = renderTableAsImage(ctx, idmlTable, tf, thisX, thisY, tablePageIdx);
                    if (fig == null && policy.fallbackToBackgroundCrop) {
                        fig = cropTableFromPageBackground(ctx, idmlTable, thisX, thisY, tf.zOrder(), tablePageIdx);
                    }
                    if (fig != null) {
                        if (ctx.debugAst) fig.debugOrNew().note("nested table forced to PNG");
                        section.addBlock(fig);
                        report.wholeTablePngForced++;
                        continue;
                    }
                    System.err.println("[Phase 4] 중첩 테이블이지만 PNG 없음 → ASTTable 폴백, tf=" + tf.id());
                }

                // 분기 2: 레거시 모드 (preferCellLevel=false) — 표 단위 게이트 + 표 전체 PNG
                if (!policy.preferCellLevel) {
                    if (idmlTableHasInlineWithShortText(idmlTable, policy.maxTextLengthWithInline)) {
                        ASTFigure fig = renderTableAsImage(ctx, idmlTable, tf, thisX, thisY, tablePageIdx);
                        if (fig == null && policy.fallbackToBackgroundCrop) {
                            fig = cropTableFromPageBackground(ctx, idmlTable, thisX, thisY, tf.zOrder(), tablePageIdx);
                            if (fig != null) {
                                section.addBlock(fig);
                                report.wholeTablePngRendered++; // crop도 같은 카운터 (rendered로 간주)
                                if (ctx.debugAst) fig.debugOrNew().note("background-cropped");
                                continue;
                            }
                        }
                        if (fig != null) {
                            section.addBlock(fig);
                            report.wholeTablePngRendered++;
                            continue;
                        }
                        report.pngMissingFallback++;
                        System.err.println("[Phase 4] (legacy) 인라인 감지했지만 PNG 없음 → ASTTable, tf="
                                + tf.id() + " — 배지 중복 위험");
                    }
                }

                // 분기 3 (기본): ASTTable로 변환
                ASTTable astTable = buildPreparedAstTable(ctx, idmlTable, thisX, thisY, tf.zOrder());
                if (ctx.isAnchoredNestedTableSource(idmlTable.selfId())) {
                    astTable.flowWithText(true);
                }
                if (GroupedFlowStackPolicy.isFlowStackTableTextFrame(ctx, tf)
                        || hasFlowStackTitleAboveTableBounds(ctx, tf, resolvedTableBounds, thisX, thisY, astTable)) {
                    astTable.anchoredFlowWithText(true);
                }
                absorbTextFrameOutlineIntoTable(ctx, tf, astTable);

                report.tableBordersAbsorbed += absorbTableBorderPageObjects(ctx, tf, astTable, tablePageIdx);
                completeVisibleTableOuterBorder(astTable);

                List<TablePlacement> tablePlacements = splitSpreadWideTable(
                        ctx, astTable, tablePageIdx, resolvedTableBounds, sections.size());
                for (TablePlacement placement : tablePlacements) {
                    if (placement.pageIdx < 0 || placement.pageIdx >= sections.size()) continue;
                    report.cellBackgroundsAbsorbed += absorbCellBackgroundPageObjects(
                            ctx, placement.table, placement.pageIdx);
                    completeVisibleTableOuterBorder(placement.table);
                    sections.get(placement.pageIdx).addBlock(placement.table);
                }
                suppressRenderedVisualsOwnedByTable(ctx, tf, astTable);

                // 분기 3a: cell-level 모드 — 트리거 셀의 인라인 객체를 floating으로 추출
                int extractedInThisTable = 0;
                int triggerCells = 0;
                if (policy.preferCellLevel) {
                    for (TablePlacement placement : tablePlacements) {
                        if (placement.pageIdx < 0 || placement.pageIdx >= sections.size()) continue;
                        int[] result = extractInlinesFromCells(
                                placement.table, sections.get(placement.pageIdx), policy, ctx);
                        extractedInThisTable += result[0];
                        triggerCells += result[1];
                    }
                    report.totalInlinesExtracted += extractedInThisTable;
                    report.totalCellsExtracted += triggerCells;
                }
                if (extractedInThisTable > 0) {
                    report.asTableWithExtraction++;
                    if (ctx.debugAst) astTable.debugOrNew().note("cell-level extraction: " + triggerCells
                            + " cells, " + extractedInThisTable + " inlines");
                } else {
                    report.asTableCleanCount++;
                }
            }
        }

        report.duplicateInlineTablesRemoved =
                removeInlineTablesBySourceId(sections, pageLevelTableSourceIds);
        printReport(report);
    }

    private static void placeTableOnlyPlans(
            ResolvedBuildContext ctx,
            List<ASTSection> sections,
            Set<String> processedTableIds,
            Phase4Report report) {
        if (ctx == null || ctx.resolvedData == null || ctx.loadIDMLStory == null
                || ctx.ownershipPlans == null || ctx.ownershipPlans.isEmpty()) {
            return;
        }
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null || plan.visualAction != VisualAction.PLACE_TABLE_STYLE) continue;
            if (plan.kind == null || !plan.kind.startsWith("text_frame:table_only")) continue;

            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(plan.domId));
            if (tf == null || tf.storyId() == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(tf.storyId());
            if (!TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, idmlStory)) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;
            long[] origin = tableOwnerOrigin(ctx, tf, pageIdx);
            long hx = origin[0];
            long hy = origin[1];

            for (IDMLTable idmlTable : idmlStory.tables()) {
                if (idmlTable == null || idmlTable.selfId() == null) continue;
                if (ctx.isAnchoredWrapperTableSource(idmlTable.selfId())) {
                    continue;
                }
                if (!processedTableIds.add(idmlTable.selfId())) continue;

                int tablePageIdx = tablePlacementPageIndex(ctx, idmlTable, pageIdx);
                if (tablePageIdx < 0 || tablePageIdx >= sections.size()) continue;

                long thisX = hx;
                long thisY = hy;
                double[] resolvedTableBounds = ctx.resolvedData.getTablePlacementBounds(idmlTable.selfId());
                if (resolvedTableBounds != null && resolvedTableBounds.length >= 4) {
                    double scale = ctx.resolvedData.scaleFactor();
                    thisX = CoordinateConverter.pointsToHwpunits(resolvedTableBounds[1] * scale);
                    thisY = CoordinateConverter.pointsToHwpunits(resolvedTableBounds[0] * scale);
                }

                ASTTable astTable = buildPreparedAstTable(ctx, idmlTable, thisX, thisY, tf.zOrder());
                if (ctx.isAnchoredNestedTableSource(idmlTable.selfId())) {
                    astTable.flowWithText(true);
                }
                if (GroupedFlowStackPolicy.isFlowStackTableTextFrame(ctx, tf)
                        || hasFlowStackTitleAboveTableBounds(ctx, tf, resolvedTableBounds, thisX, thisY, astTable)) {
                    astTable.anchoredFlowWithText(true);
                }
                absorbTextFrameOutlineIntoTable(ctx, tf, astTable);
                report.tableBordersAbsorbed += absorbTableBorderPageObjects(ctx, tf, astTable, tablePageIdx);
                completeVisibleTableOuterBorder(astTable);

                List<TablePlacement> tablePlacements = splitSpreadWideTable(
                        ctx, astTable, tablePageIdx, resolvedTableBounds, sections.size());
                for (TablePlacement placement : tablePlacements) {
                    if (placement.pageIdx < 0 || placement.pageIdx >= sections.size()) continue;
                    report.cellBackgroundsAbsorbed += absorbCellBackgroundPageObjects(
                            ctx, placement.table, placement.pageIdx);
                    completeVisibleTableOuterBorder(placement.table);
                    sections.get(placement.pageIdx).addBlock(placement.table);
                }
                suppressRenderedVisualsOwnedByTable(ctx, tf, astTable);
                report.asTableCleanCount++;
                report.tableOnlyPlansPlaced++;
                report.total++;
            }
        }
    }

    private static boolean hasFlowStackTitleAboveTableBounds(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            double[] resolvedTableBounds,
            long tableX,
            long tableY,
            ASTTable table) {
        if (ctx == null || tf == null || table == null) return false;
        double top;
        double left;
        double bottom;
        double right;
        if (resolvedTableBounds != null && resolvedTableBounds.length >= 4 && ctx.resolvedData != null) {
            double scale = ctx.resolvedData.scaleFactor();
            top = resolvedTableBounds[0] * scale;
            left = resolvedTableBounds[1] * scale;
            bottom = resolvedTableBounds[2] * scale;
            right = resolvedTableBounds[3] * scale;
        } else {
            top = CoordinateConverter.hwpunitsToPoints(tableY);
            left = CoordinateConverter.hwpunitsToPoints(tableX);
            bottom = top + CoordinateConverter.hwpunitsToPoints(table.height());
            right = left + CoordinateConverter.hwpunitsToPoints(table.width());
        }
        return GroupedFlowStackPolicy.hasFlowStackTitleAboveBounds(
                ctx, tf.pageIndex(), top, left, bottom, right);
    }

    private static long[] tableOwnerOrigin(ResolvedBuildContext ctx, ResolvedTextFrame tf, int pageIdx) {
        double x = 0;
        double y = 0;
        double[] b = tf.pageRelativeBounds();
        if (b != null && b.length >= 4) {
            y = b[0];
            x = b[1];
        } else {
            double[] gb = tf.geometricBounds();
            ResolvedPage rPage = (ctx.resolvedData != null && pageIdx < ctx.resolvedData.pages().size())
                    ? ctx.resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;
            if (gb != null && gb.length >= 4) {
                boolean gbAlreadyPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
                x = gbAlreadyPageRelative ? gb[1] : (gb[1] - pageLeft);
                y = gb[0] - pageTop;
            }
        }

        long hx = CoordinateConverter.pointsToHwpunits(x);
        long hy = CoordinateConverter.pointsToHwpunits(y);
        if (tf.insetSpacing() != null) {
            double[] inset = tf.insetSpacing();
            hy += CoordinateConverter.pointsToHwpunits(inset[0]);
            hx += CoordinateConverter.pointsToHwpunits(inset[1]);
        }
        return new long[] { hx, hy };
    }

    private static int tablePlacementPageIndex(
            ResolvedBuildContext ctx,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable,
            int fallbackPageIdx) {
        if (ctx == null || ctx.resolvedData == null || idmlTable == null) return fallbackPageIdx;
        Integer placementPageIndex = ctx.resolvedData.getTablePlacementPageIndex(idmlTable.selfId());
        if (placementPageIndex == null) return fallbackPageIdx;
        return ctx.toSectionIndex.applyAsInt(placementPageIndex);
    }

    private static boolean allTablesConsumedByAnchoredPlan(ResolvedBuildContext ctx, IDMLStory idmlStory) {
        if (ctx == null || idmlStory == null || idmlStory.tables() == null || idmlStory.tables().isEmpty()) return false;
        for (IDMLTable table : idmlStory.tables()) {
            if (table == null || !ctx.isAnchoredTableSource(table.selfId())) return false;
        }
        return true;
    }

    public static ASTTable buildPreparedAstTable(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            long x,
            long y,
            int zOrder) {
        final ResolvedBuildContext cellCtx = ctx;
        ASTTable astTable = ASTTableConverter.convertTableSimple(
                idmlTable, x, y, zOrder,
                null, null, null,
                ctx != null ? ctx.resolvedData : null,
                ctx != null ? ctx.styleResolver : null,
                cellCtx == null ? null : (idmlCell -> StoryLoader.astParagraphsForCell(cellCtx, idmlCell)));
        Map<String, List<ASTParagraph>> sourceTextByCell = snapshotVisibleTextParagraphsByCell(astTable);
        restoreNestedTextFrameTables(ctx, astTable, idmlTable);
        restoreAnchorOnlyEditableInlineGraphics(ctx, astTable, idmlTable);
        restoreRenderedCellInlineGraphics(ctx, astTable, idmlTable);
        promoteNestedTextFrameParagraphsInCells(ctx, astTable);

        ASTTable expandedTable = tryExpandInlineGroupColumns(ctx, astTable, idmlTable);
        ASTTable result = expandedTable != null ? expandedTable : astTable;
        restoreLostCellTextFromSnapshot(result, sourceTextByCell);
        completeVisibleTableOuterBorder(result);
        if (NumberedSideHeadTableNormalizer.normalizePlanned(ctx, result) && ctx != null && ctx.debugAst) {
            result.debugOrNew().note("side-head flow table normalized from Stage 1 plan");
        }
        return result;
    }

    private static Map<String, List<ASTParagraph>> snapshotVisibleTextParagraphsByCell(ASTTable table) {
        Map<String, List<ASTParagraph>> snapshot = new HashMap<>();
        if (table == null || table.rows() == null) return snapshot;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null) continue;
                String text = normalizedCellText(cell);
                if (text.isEmpty()) continue;
                List<ASTParagraph> paragraphs = copyTextParagraphs(cell.paragraphs());
                if (!paragraphs.isEmpty()) {
                    snapshot.put(cellKey(cell.rowIndex(), cell.columnIndex()), paragraphs);
                }
            }
        }
        return snapshot;
    }

    private static void restoreLostCellTextFromSnapshot(
            ASTTable table,
            Map<String, List<ASTParagraph>> sourceTextByCell) {
        if (table == null || table.rows() == null || sourceTextByCell == null || sourceTextByCell.isEmpty()) {
            return;
        }
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                List<ASTParagraph> sourceParagraphs =
                        sourceTextByCell.get(cellKey(cell.rowIndex(), cell.columnIndex()));
                if (sourceParagraphs == null || sourceParagraphs.isEmpty()) continue;
                String sourceText = normalizedParagraphsText(sourceParagraphs);
                if (sourceText.isEmpty()) continue;
                String currentText = normalizedCellText(cell);
                if (currentText.contains(sourceText)) continue;

                List<ASTParagraph> restored = copyTextParagraphs(sourceParagraphs);
                if (!restored.isEmpty()) {
                    reinsertInlineObjectsByParagraphText(cell.paragraphs(), restored);
                    cell.paragraphs().clear();
                    cell.paragraphs().addAll(restored);
                }
            }
        }
    }

    private static String cellKey(int rowIndex, int columnIndex) {
        return rowIndex + ":" + columnIndex;
    }

    /**
     * 원래 셀 단락들의 인라인 객체(배지/라벨 박스 등)를, 텍스트가 일치하는 복원 단락에 재배치한다.
     * 모든 박스를 첫 단락에 몰아넣던 기존 방식은 다단락 셀에서 라벨을 엉뚱한 단락(예: 교사 지도문)으로
     * 옮겨 "예시 답안" 곡선 라벨이 답안과 분리되는 버그를 유발했다 → 단락별 텍스트 매칭으로 보존.
     */
    private static void reinsertInlineObjectsByParagraphText(
            List<ASTParagraph> originalParagraphs, List<ASTParagraph> restored) {
        if (originalParagraphs == null || restored == null || restored.isEmpty()) return;
        boolean[] used = new boolean[restored.size()];
        List<ASTInlineItem> unmatched = new ArrayList<>();
        for (ASTParagraph origPara : originalParagraphs) {
            if (origPara == null || origPara.items() == null) continue;
            List<ASTInlineItem> boxes = new ArrayList<>();
            for (ASTInlineItem item : origPara.items()) {
                if (item != null && !(item instanceof ASTTextRun)) boxes.add(item);
            }
            if (boxes.isEmpty()) continue;
            String origText = normalizedParagraphText(copyParagraphTextOnly(origPara));
            int target = -1;
            if (!origText.isEmpty()) {
                for (int r = 0; r < restored.size(); r++) {
                    if (used[r]) continue;
                    if (origText.equals(normalizedParagraphText(restored.get(r)))) { target = r; break; }
                }
            }
            if (target >= 0) {
                used[target] = true;
                restored.get(target).items().addAll(0, boxes);
            } else {
                unmatched.addAll(boxes); // 텍스트 없는 박스 단독 단락 등 → 첫 단락 폴백
            }
        }
        if (!unmatched.isEmpty()) restored.get(0).items().addAll(0, unmatched);
    }

    private static List<ASTParagraph> copyTextParagraphs(List<ASTParagraph> paragraphs) {
        List<ASTParagraph> copies = new ArrayList<>();
        if (paragraphs == null) return copies;
        for (ASTParagraph paragraph : paragraphs) {
            ASTParagraph copy = copyParagraphTextOnly(paragraph);
            if (!normalizedParagraphText(copy).isEmpty()) {
                copies.add(copy);
            }
        }
        return copies;
    }

    private static ASTParagraph copyParagraphTextOnly(ASTParagraph source) {
        ASTParagraph copy = new ASTParagraph();
        if (source == null) return copy;
        copyParagraphProperties(source, copy);
        if (source.items() != null) {
            for (ASTInlineItem item : source.items()) {
                if (item instanceof ASTTextRun) {
                    ASTTextRun run = (ASTTextRun) item;
                    if (run.text() != null && !normalizeComparableText(run.text()).isEmpty()) {
                        copy.addItem(copyTextRun(run, run.text()));
                    }
                }
            }
        }
        return copy;
    }

    private static void copyParagraphProperties(ASTParagraph source, ASTParagraph copy) {
        copy.paragraphStyleRef(source.paragraphStyleRef());
        copy.alignment(source.alignment());
        copy.firstLineIndent(source.firstLineIndent());
        copy.leftMargin(source.leftMargin());
        copy.rightMargin(source.rightMargin());
        copy.spaceBefore(source.spaceBefore());
        copy.spaceAfter(source.spaceAfter());
        copy.lineSpacing(source.lineSpacing());
        copy.lineSpacingType(source.lineSpacingType());
        copy.letterSpacing(source.letterSpacing());
        copy.shadingOn(source.shadingOn());
        copy.shadingColor(source.shadingColor());
        copy.shadingTint(source.shadingTint());
        copy.shadingLeftOffset(source.shadingLeftOffset());
        copy.shadingRightOffset(source.shadingRightOffset());
        copy.shadingTopOffset(source.shadingTopOffset());
        copy.shadingBottomOffset(source.shadingBottomOffset());
        copy.yOffsetInFrame(source.yOffsetInFrame());
        copy.pageX(source.pageX());
        copy.pageY(source.pageY());
        copy.pageWidth(source.pageWidth());
        copy.pageHeight(source.pageHeight());
        copy.columnBreakAfter(source.columnBreakAfter());
        copy.keepWithNext(source.keepWithNext());
        copy.keepLinesTogether(source.keepLinesTogether());
        copy.pageBreakBefore(source.pageBreakBefore());
        copy.indentToHerePosition(source.indentToHerePosition());
        copy.pendingUnderlineColor(source.pendingUnderlineColor());
        copy.bulletParagraph(source.bulletParagraph());
        copy.dropLeadingSmallInlineObjects(source.dropLeadingSmallInlineObjects());
        if (source.tabStops() != null) {
            for (ASTTabStop tabStop : source.tabStops()) {
                if (tabStop != null) {
                    copy.addTabStop(new ASTTabStop(
                            tabStop.position(), tabStop.alignment(), tabStop.leader()));
                }
            }
        }
    }

    private static ASTTextRun copyTextRun(ASTTextRun source, String text) {
        ASTTextRun copy = new ASTTextRun();
        copy.characterStyleRef(source.characterStyleRef());
        copy.text(text);
        copy.fontFamily(source.fontFamily());
        copy.fontStyle(source.fontStyle());
        copy.fontSizeHwpunits(source.fontSizeHwpunits());
        copy.textColor(source.textColor());
        copy.shadeColor(source.shadeColor());
        copy.letterSpacing(source.letterSpacing());
        copy.subscript(source.subscript());
        copy.superscript(source.superscript());
        copy.grepMathFont(source.grepMathFont());
        copy.underline(source.underline());
        copy.underlineColor(source.underlineColor());
        copy.underlineShape(source.underlineShape());
        copy.strikeThrough(source.strikeThrough());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.grepStyleApplied(source.grepStyleApplied());
        return copy;
    }

    private static String normalizedCellText(ASTTableCell cell) {
        if (cell == null) return "";
        return normalizedParagraphsText(cell.paragraphs());
    }

    private static String normalizedParagraphsText(List<ASTParagraph> paragraphs) {
        if (paragraphs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph paragraph : paragraphs) {
            sb.append(normalizedParagraphText(paragraph));
        }
        return sb.toString();
    }

    private static String normalizeComparableText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0016' || ch == '\u0018'
                    || ch == '\u0003' || ch == '\u0007' || ch == '\u0008') {
                continue;
            }
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static List<TablePlacement> splitSpreadWideTable(
            ResolvedBuildContext ctx,
            ASTTable table,
            int pageIdx,
            double[] visibleBounds,
            int sectionCount) {
        List<TablePlacement> result = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || table == null
                || table.columnWidths() == null || table.columnWidths().size() < 2
                || pageIdx + 1 >= sectionCount) {
            result.add(new TablePlacement(table, pageIdx));
            return result;
        }

        ResolvedPage page = pageIdx >= 0 && pageIdx < ctx.resolvedData.pages().size()
                ? ctx.resolvedData.pages().get(pageIdx) : null;
        if (page == null || page.width() <= 0) {
            result.add(new TablePlacement(table, pageIdx));
            return result;
        }

        long pageWidth = CoordinateConverter.pointsToHwpunits(page.width());
        long totalWidth = tableWidth(table);
        long availableOnPage = pageWidth - Math.max(0L, table.x());
        if (availableOnPage <= 0 || totalWidth <= availableOnPage) {
            result.add(new TablePlacement(table, pageIdx));
            return result;
        }

        long visibleWidth = visibleBoundsWidthHwpunits(ctx, visibleBounds);
        long targetFirstSliceWidth = visibleWidth > 0
                ? Math.min(visibleWidth, availableOnPage)
                : Math.max(1L, availableOnPage);
        int splitCol = nearestColumnBoundary(table.columnWidths(), targetFirstSliceWidth);
        if (splitCol <= 0 || splitCol >= table.columnWidths().size()) {
            result.add(new TablePlacement(table, pageIdx));
            return result;
        }

        long firstWidth = columnWidthSum(table.columnWidths(), 0, splitCol);
        long secondWidth = totalWidth - firstWidth;
        long minSecondWidth = Math.max(1L, pageWidth / 8);
        if (secondWidth < minSecondWidth) {
            result.add(new TablePlacement(table, pageIdx));
            return result;
        }

        int rightStartCol = skipLeadingEmptySplitColumns(table, splitCol, table.columnWidths().size());
        ASTTable left = sliceTableColumns(table, 0, splitCol);
        ASTTable right = sliceTableColumns(table, rightStartCol, table.columnWidths().size());
        long rightSourceX = table.x() + columnWidthSum(table.columnWidths(), 0, rightStartCol) - pageWidth;
        right.x(Math.max(0L, rightSourceX));
        fitTableToPage(left, pageWidth);
        fitTableToPage(right, pageWidth);
        if (ctx.debugAst) {
            left.debugOrNew().note("spread-wide table slice: columns 0-" + (splitCol - 1));
            right.debugOrNew().note("spread-wide table slice: columns " + splitCol
                    + "-" + (table.columnWidths().size() - 1));
        }
        result.add(new TablePlacement(left, pageIdx));
        result.add(new TablePlacement(right, pageIdx + 1));
        return result;
    }

    private static int skipLeadingEmptySplitColumns(ASTTable table, int startCol, int endCol) {
        if (table == null || table.columnWidths() == null) return startCol;
        int col = Math.max(0, startCol);
        int end = Math.min(endCol, table.columnWidths().size());
        while (col + 1 < end && !columnHasVisibleContent(table, col)) {
            col++;
        }
        return col;
    }

    private static boolean columnHasVisibleContent(ASTTable table, int columnIndex) {
        if (table == null || table.rows() == null) return false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int start = cell.columnIndex();
                int end = start + Math.max(1, cell.columnSpan());
                if (columnIndex < start || columnIndex >= end) continue;
                if (cellHasVisibleContent(cell)) return true;
            }
        }
        return false;
    }

    private static boolean cellHasVisibleContent(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null) return true;
            if (paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item == null) continue;
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.replace("\r", "").replace("\n", "").trim().isEmpty()) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    private static long visibleBoundsWidthHwpunits(ResolvedBuildContext ctx, double[] visibleBounds) {
        if (ctx == null || visibleBounds == null || visibleBounds.length < 4) return 0;
        double width = visibleBounds[3] - visibleBounds[1];
        if (width <= 0) return 0;
        return CoordinateConverter.pointsToHwpunits(width);
    }

    private static int nearestColumnBoundary(List<Long> columnWidths, long targetWidth) {
        if (columnWidths == null || columnWidths.size() < 2 || targetWidth <= 0) return -1;
        int best = -1;
        long x = 0;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < columnWidths.size() - 1; i++) {
            x += Math.max(0L, columnWidths.get(i));
            long delta = Math.abs(x - targetWidth);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i + 1;
            }
        }
        return best;
    }

    private static ASTTable sliceTableColumns(ASTTable src, int startCol, int endCol) {
        ASTTable out = new ASTTable();
        out.sourceId(src.sourceId());
        out.x(src.x());
        out.y(src.y());
        out.zOrder(src.zOrder());
        out.flowWithText(src.flowWithText());
        out.anchoredFlowWithText(src.anchoredFlowWithText());
        out.appliedTableStyle(src.appliedTableStyle());
        out.borderColor(src.borderColor());
        out.borderWidth(src.borderWidth());
        for (int c = startCol; c < endCol && c < src.columnWidths().size(); c++) {
            out.addColumnWidth(src.columnWidths().get(c));
        }
        out.colCount(out.columnWidths().size());

        int rowCount = 0;
        if (src.rows() != null) {
            for (ASTTableRow srcRow : src.rows()) {
                ASTTableRow outRow = new ASTTableRow();
                outRow.rowIndex(srcRow.rowIndex());
                outRow.rowHeight(srcRow.rowHeight());
                outRow.autoGrow(srcRow.autoGrow());
                if (srcRow.cells() != null) {
                    for (ASTTableCell srcCell : srcRow.cells()) {
                        ASTTableCell sliced = sliceCell(srcCell, startCol, endCol, src.columnWidths());
                        if (sliced != null) outRow.addCell(sliced);
                    }
                }
                out.addRow(outRow);
                rowCount++;
            }
        }
        out.rowCount(rowCount);
        out.width(tableWidth(out));
        out.height(tableHeight(out));
        completeVisibleTableOuterBorder(out);
        return out;
    }

    private static ASTTableCell sliceCell(
            ASTTableCell src,
            int startCol,
            int endCol,
            List<Long> sourceColumnWidths) {
        if (src == null) return null;
        int cellStart = src.columnIndex();
        int cellEnd = cellStart + Math.max(1, src.columnSpan());
        int clippedStart = Math.max(cellStart, startCol);
        int clippedEnd = Math.min(cellEnd, endCol);
        if (clippedStart >= clippedEnd) return null;

        ASTTableCell out = cloneCellFull(src);
        out.columnIndex(clippedStart - startCol);
        out.columnSpan(clippedEnd - clippedStart);
        out.width(columnWidthSum(sourceColumnWidths, clippedStart, clippedEnd));
        return out;
    }

    private static void fitTableToPage(ASTTable table, long pageWidth) {
        if (table == null || table.columnWidths() == null || table.columnWidths().isEmpty()) return;
        long available = pageWidth - Math.max(0L, table.x());
        long width = tableWidth(table);
        if (available <= 0 || width <= available) return;
        double scale = (double) available / (double) width;
        long scaledTotal = 0;
        for (int i = 0; i < table.columnWidths().size(); i++) {
            long oldWidth = table.columnWidths().get(i);
            long newWidth = Math.max(1L, Math.round(oldWidth * scale));
            table.columnWidths().set(i, newWidth);
            scaledTotal += newWidth;
        }
        if (table.rows() != null) {
            for (ASTTableRow row : table.rows()) {
                if (row == null || row.cells() == null) continue;
                for (ASTTableCell cell : row.cells()) {
                    int start = Math.max(0, cell.columnIndex());
                    int end = Math.min(table.columnWidths().size(), start + Math.max(1, cell.columnSpan()));
                    cell.width(columnWidthSum(table.columnWidths(), start, end));
                }
            }
        }
        table.width(scaledTotal);
    }

    private static long tableWidth(ASTTable table) {
        return table != null ? columnWidthSum(table.columnWidths(), 0,
                table.columnWidths() != null ? table.columnWidths().size() : 0) : 0;
    }

    private static long tableHeight(ASTTable table) {
        long h = 0;
        if (table == null || table.rows() == null) return 0;
        for (ASTTableRow row : table.rows()) {
            if (row != null) h += row.rowHeight();
        }
        return h;
    }

    private static long columnWidthSum(List<Long> widths, int startInclusive, int endExclusive) {
        if (widths == null) return 0;
        long sum = 0;
        int start = Math.max(0, startInclusive);
        int end = Math.min(widths.size(), Math.max(start, endExclusive));
        for (int i = start; i < end; i++) sum += Math.max(0L, widths.get(i));
        return sum;
    }

    private static void suppressRenderedVisualsOwnedByTable(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || tf == null || tf.id() == null) return;
        int tfId;
        try {
            tfId = Integer.parseInt(tf.id());
        } catch (NumberFormatException ignored) {
            return;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.sourceObjectIds() == null) continue;
            if (!containsSourceId(rg.sourceObjectIds(), tfId)) continue;
            if (!isTableCompositeVisual(rg)) continue;
            // 배지 셸(작은 알약 그래픽 + 편집 텍스트 별도 소유)은 테이블이 재구성하지 못하는
            // 장식 chrome이다. 억제하면 알약이 사라져 셀 텍스트만 남는다(구조도/개관 배지). 억제 제외 →
            // Phase 6/7이 셀 텍스트 뒤 backdrop으로 배치한다.
            if (isBadgeShellOwnedByTable(rg)) continue;
            ctx.setRenderedDisposition(rg.id(), FrameDisposition.TEXT_BLOCK_PLACED);
            ctx.phase6PlacedIds.add(rg.id());
            if (ctx.debugAst && table != null) {
                table.debugOrNew().note("table composite visual suppressed: rendered " + rg.id());
            }
        }
    }

    /**
     * 테이블 TF 소유의 배지 셸: 편집 텍스트(별도 TF)를 가진 작은 알약/라벨 그래픽.
     * 테이블은 텍스트만 재구성하므로 이 chrome을 억제하면 알약이 사라진다.
     * 큰 합성 PNG(셀 전체 베이킹)는 height로 배제한다.
     */
    private static boolean isBadgeShellOwnedByTable(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = rg.reason();
        if (reason == null || !reason.contains("mixed_group")) return false;
        String[] ed = rg.editableTextFrameIds();
        if (ed == null || ed.length == 0) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        return Math.abs(b[2] - b[0]) <= 60.0; // 배지 한 줄 높이
    }

    private static boolean isTableCompositeVisual(RenderedGroup rg) {
        String itemType = rg.itemType();
        String type = rg.type();
        if ("inline_object".equals(itemType) || "inline_object".equals(type)) return false;
        if (!"page_object".equals(itemType) && !"page_object".equals(type)) return false;
        String textOwner = rg.textOwner();
        if (textOwner != null && !"hwpx_tf".equals(textOwner)) return false;
        String reason = rg.reason();
        return reason == null
                || reason.contains("text_hidden")
                || reason.contains("text_composite")
                || reason.contains("mixed_group")
                || reason.contains("complex_graphic");
    }

    private static boolean containsSourceId(int[] sourceIds, int id) {
        if (sourceIds == null) return false;
        for (int sourceId : sourceIds) {
            if (sourceId == id) return true;
        }
        return false;
    }

    private static void absorbTextFrameOutlineIntoTable(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || tf == null || table == null) return;
        ResolvedPageItem outline = findTextFrameOutlineItem(ctx, tf.id());
        if (!hasVisibleStroke(outline)) return;
        ASTTableCell.CellBorder border = pageItemStrokeBorder(ctx, outline);
        if (border == null) return;
        applyOuterBorder(table, border);
        int outlineId = parseId(outline.id());
        if (outlineId >= 0) {
            markPageItemHandled(ctx, outlineId);
        }
        if (ctx.debugAst) {
            table.debugOrNew().note("text frame outline absorbed into table: page_item " + outline.id());
        }
    }

    private static ResolvedPageItem findTextFrameOutlineItem(ResolvedBuildContext ctx, String textFrameId) {
        if (textFrameId == null) return null;
        Map<Integer, ResolvedPageItem> pageItemById = new HashMap<>();
        ResolvedPageItem textFrameItem = null;
        int tfId = parseId(textFrameId);
        for (ResolvedPageItem item : ctx.resolvedData.pageItems()) {
            int id = parseId(item != null ? item.id() : null);
            if (id < 0) continue;
            pageItemById.put(id, item);
            if (id == tfId) textFrameItem = item;
        }
        if (hasVisibleStroke(textFrameItem)) return textFrameItem;
        if (textFrameItem == null || textFrameItem.parentId() == null) return null;
        ResolvedPageItem parent = pageItemById.get(parseId(textFrameItem.parentId()));
        return hasVisibleStroke(parent) ? parent : null;
    }

    private static boolean hasVisibleStroke(ResolvedPageItem item) {
        if (item == null || item.strokeWeight() <= 0) return false;
        String color = item.strokeColorName();
        return color != null && !color.contains("None");
    }

    private static ASTTableCell.CellBorder pageItemStrokeBorder(
            ResolvedBuildContext ctx,
            ResolvedPageItem item) {
        if (!hasVisibleStroke(item)) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(item.strokeWeight());
        border.strokeType("Solid");
        border.tint(item.strokeTint());
        String color = ctx.resolvedData.resolveTintedColorHex(item.strokeColorName(), item.strokeTint());
        if (color == null || color.isEmpty() || !color.startsWith("#")) color = "#000000";
        border.color(color);
        return border;
    }

    private static void applyOuterBorder(ASTTable table, ASTTableCell.CellBorder border) {
        if (table.rows() == null || table.rows().isEmpty()) return;
        int lastRow = Math.max(0, table.rowCount() - 1);
        int lastCol = Math.max(0, table.colCount() - 1);
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan()) - 1;
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan()) - 1;
                if (rowStart <= 0) cell.topBorder(preferExistingBorder(cell.topBorder(), border));
                if (rowEnd >= lastRow) cell.bottomBorder(preferExistingBorder(cell.bottomBorder(), border));
                if (colStart <= 0) cell.leftBorder(preferExistingBorder(cell.leftBorder(), border));
                if (colEnd >= lastCol) cell.rightBorder(preferExistingBorder(cell.rightBorder(), border));
            }
        }
    }

    private static ASTTableCell.CellBorder preferExistingBorder(
            ASTTableCell.CellBorder existing,
            ASTTableCell.CellBorder fallback) {
        if (isVisibleCellBorder(existing)) return existing;
        return cloneBorder(fallback);
    }

    private static boolean isVisibleCellBorder(ASTTableCell.CellBorder border) {
        if (border == null || border.weight() <= 0) return false;
        if (isNoneLike(border.color())) return false;
        return !isNoneLike(border.strokeType());
    }

    private static boolean isNoneLike(String value) {
        return value != null && value.toLowerCase().contains("none");
    }

    private static ASTTableCell.CellBorder cloneBorder(ASTTableCell.CellBorder src) {
        if (src == null) return null;
        ASTTableCell.CellBorder copy = new ASTTableCell.CellBorder();
        copy.color(src.color());
        copy.weight(src.weight());
        copy.strokeType(src.strokeType());
        copy.tint(src.tint());
        return copy;
    }

    private static int parseId(String id) {
        if (id == null) return -1;
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int parseFlexibleId(String id) {
        int parsed = parseId(id);
        if (parsed >= 0) return parsed;
        if (id == null || id.isEmpty()) return -1;
        int marker = Math.max(id.lastIndexOf('u'), id.lastIndexOf('U'));
        marker = Math.max(marker, Math.max(id.lastIndexOf('i'), id.lastIndexOf('I')));
        if (marker < 0 || marker + 1 >= id.length()) return -1;
        String tail = id.substring(marker + 1);
        int slash = tail.indexOf('/');
        if (slash >= 0) tail = tail.substring(0, slash);
        try {
            return Integer.parseInt(tail, 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int removeInlineTablesBySourceId(List<ASTSection> sections, Set<String> sourceIds) {
        if (sections == null || sourceIds == null || sourceIds.isEmpty()) return 0;
        int removed = 0;
        for (ASTSection section : sections) {
            if (section == null || section.blocks() == null) continue;
            for (ASTBlock block : section.blocks()) {
                removed += removeInlineTablesFromBlock(block, sourceIds);
            }
        }
        return removed;
    }

    private static int removeInlineTablesFromBlock(ASTBlock block, Set<String> sourceIds) {
        if (block instanceof ASTTextFrameBlock) {
            return removeInlineTablesFromParagraphs(((ASTTextFrameBlock) block).paragraphs(), sourceIds);
        }
        if (block instanceof ASTTable) {
            return removeInlineTablesFromTable((ASTTable) block, sourceIds);
        }
        return 0;
    }

    private static int removeInlineTablesFromTable(ASTTable table, Set<String> sourceIds) {
        if (table == null || table.rows() == null) return 0;
        int removed = 0;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                removed += removeInlineTablesFromParagraphs(cell.paragraphs(), sourceIds);
            }
        }
        return removed;
    }

    private static int removeInlineTablesFromParagraphs(List<ASTParagraph> paragraphs, Set<String> sourceIds) {
        if (paragraphs == null) return 0;
        int removed = 0;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null && sourceIds.contains(paragraph.inlineTable().sourceId())) {
                paragraph.inlineTable(null);
                removed++;
            }
            if (paragraph.items() == null) continue;
            Iterator<ASTInlineItem> it = paragraph.items().iterator();
            while (it.hasNext()) {
                ASTInlineItem item = it.next();
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject obj = (ASTInlineObject) item;
                int before = obj.inlineTables() != null ? obj.inlineTables().size() : 0;
                if (before > 0) {
                    obj.inlineTables().removeIf(t -> t != null && sourceIds.contains(t.sourceId()));
                    int after = obj.inlineTables().size();
                    removed += before - after;
                }
                removed += removeInlineTablesFromParagraphs(obj.paragraphs(), sourceIds);
                if ((obj.paragraphs() == null || obj.paragraphs().isEmpty())
                        && (obj.inlineTables() == null || obj.inlineTables().isEmpty())
                        && obj.imageData() == null && obj.imagePath() == null) {
                    it.remove();
                }
            }
        }
        return removed;
    }

    private static void restoreNestedTextFrameTables(
            ResolvedBuildContext ctx,
            ASTTable astTable,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable) {
        if (ctx == null || ctx.loadIDMLStory == null || astTable == null || idmlTable == null) return;
        List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow> idmlRows = idmlTable.rows();
        for (int ri = 0; ri < idmlRows.size() && ri < astTable.rows().size(); ri++) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow idmlRow = idmlRows.get(ri);
            ASTTableRow astRow = astTable.rows().get(ri);
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell : idmlRow.cells()) {
                if (idmlCell.textFrameStoryRefs() == null || idmlCell.textFrameStoryRefs().isEmpty()) continue;
                ASTTableCell astCell = findAstCell(astRow, idmlCell.columnIndex());
                if (astCell == null) continue;
                for (String storyRef : idmlCell.textFrameStoryRefs()) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory nestedStory =
                            ctx.loadIDMLStory.apply(storyRef);
                    if (nestedStory == null) continue;
                    if (!nestedStory.hasTables()) {
                        if (replaceCellWithTextFrameStory(ctx, astCell, storyRef)) {
                            continue;
                        }
                    }
                    if (!nestedStory.hasTables()) continue;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable nestedTable : nestedStory.tables()) {
                        final ResolvedBuildContext nestedCtx = ctx;
                        ASTTable nestedAst = ASTTableConverter.convertTableSimple(
                                nestedTable, 0, 0, 0, null, null, null,
                                ctx.resolvedData, ctx.styleResolver,
                                nestedCtx == null ? null : (nestedCell -> StoryLoader.astParagraphsForCell(nestedCtx, nestedCell)));
                        if (nestedAst == null) continue;
                        restoreNestedTextFrameTables(ctx, nestedAst, nestedTable);
                        restoreAnchorOnlyEditableInlineGraphics(ctx, nestedAst, nestedTable);
                        restoreRenderedCellInlineGraphics(ctx, nestedAst, nestedTable);
                        ASTParagraph paragraph = new ASTParagraph();
                        paragraph.inlineTable(nestedAst);
                        astCell.addParagraph(paragraph);
                    }
                }
            }
        }
    }

    private static boolean replaceCellWithTextFrameStory(
            ResolvedBuildContext ctx,
            ASTTableCell astCell,
            String storyRef) {
        if (ctx == null || ctx.resolvedData == null || astCell == null || storyRef == null) return false;
        ResolvedStory story = ctx.resolvedData.getStory(toDecimalStoryId(storyRef));
        if (story == null) {
            story = ctx.resolvedData.getStory(storyRef);
        }
        if (!hasAuthoritativeResolvedStructure(story)) return false;

        List<ASTParagraph> paragraphs = StoryConverter.convertStoryParagraphs(ctx, story, false);
        if (paragraphs == null || paragraphs.isEmpty()) return false;

        astCell.paragraphs().clear();
        astCell.paragraphs().addAll(paragraphs);
        return true;
    }

    private static boolean hasAuthoritativeResolvedStructure(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int nonEmptyParagraphs = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph para : story.paragraphs()) {
            if (para == null || para.runs() == null) continue;
            int visibleRuns = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun run : para.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                String text = run.text();
                if (text != null && !text.trim().isEmpty()) visibleRuns++;
            }
            if (visibleRuns > 0) nonEmptyParagraphs++;
            if (visibleRuns > 1) return true;
        }
        return nonEmptyParagraphs > 1;
    }

    private static void promoteNestedTextFrameParagraphsInCells(ResolvedBuildContext ctx, ASTTable table) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) continue;
                List<ASTParagraph> paragraphs = cell.paragraphs();
                for (int i = 0; i < paragraphs.size(); i++) {
                    ASTInlineObject nested = firstNestedTextFrame(paragraphs.get(i));
                    if (nested == null || nested.paragraphs() == null || nested.paragraphs().isEmpty()) continue;
                    List<ASTParagraph> authoritative = authoritativeParagraphsForNestedTextFrame(ctx, nested);
                    List<ASTParagraph> sourceParagraphs =
                            authoritative != null && !authoritative.isEmpty() ? authoritative : nested.paragraphs();
                    if (shouldReplaceAnchorParagraphWithNestedFirst(paragraphs, i, sourceParagraphs)) {
                        paragraphs.set(i, sourceParagraphs.get(0));
                    } else if (paragraphs.size() == 1
                            && isParagraphOnlyNestedTextFrame(paragraphs.get(i), nested)) {
                        paragraphs.clear();
                        paragraphs.addAll(sourceParagraphs);
                    }
                }
            }
        }
    }

    private static boolean isParagraphOnlyNestedTextFrame(ASTParagraph paragraph, ASTInlineObject nested) {
        if (paragraph == null || paragraph.items() == null || nested == null) return false;
        boolean foundNested = false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item == nested) {
                foundNested = true;
                continue;
            }
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !normalizedTextWithoutControl(text).isEmpty()) {
                    return false;
                }
                continue;
            }
            return false;
        }
        return foundNested;
    }

    private static String normalizedTextWithoutControl(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008') continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static List<ASTParagraph> authoritativeParagraphsForNestedTextFrame(
            ResolvedBuildContext ctx,
            ASTInlineObject nested) {
        if (ctx == null || ctx.resolvedData == null || nested == null || nested.sourceId() == null) return null;
        String domId = toDecimalStoryId(nested.sourceId());
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(domId);
        if (tf == null || tf.storyId() == null) return null;
        ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
        if (!hasAuthoritativeResolvedStructure(story)) return null;
        List<ASTParagraph> paragraphs = StoryConverter.convertStoryParagraphs(ctx, story, false);
        return paragraphs == null || paragraphs.isEmpty() ? null : paragraphs;
    }

    private static ASTInlineObject firstNestedTextFrame(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return null;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME
                    && obj.paragraphs() != null
                    && !obj.paragraphs().isEmpty()) {
                return obj;
            }
        }
        return null;
    }

    private static boolean shouldReplaceAnchorParagraphWithNestedFirst(
            List<ASTParagraph> cellParagraphs,
            int anchorIndex,
            List<ASTParagraph> nestedParagraphs) {
        if (cellParagraphs == null || nestedParagraphs == null || nestedParagraphs.size() < 2) return false;
        int comparable = Math.min(nestedParagraphs.size() - 1, cellParagraphs.size() - anchorIndex - 1);
        if (comparable <= 0) return false;
        int matches = 0;
        for (int offset = 1; offset <= comparable; offset++) {
            String expected = normalizedParagraphText(nestedParagraphs.get(offset));
            String actual = normalizedParagraphText(cellParagraphs.get(anchorIndex + offset));
            if (!expected.isEmpty() && expected.equals(actual)) {
                matches++;
            }
        }
        return matches >= Math.min(2, comparable);
    }

    private static String normalizedParagraphText(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return "";
        StringBuilder sb = new StringBuilder();
        appendParagraphText(paragraph, sb);
        String raw = sb.toString();
        StringBuilder normalized = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008') continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            normalized.append(ch);
        }
        return normalized.toString();
    }

    private static void appendParagraphText(ASTParagraph paragraph, StringBuilder sb) {
        if (paragraph == null || paragraph.items() == null || sb == null) return;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null) sb.append(text);
            } else if (item instanceof ASTInlineObject) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (obj.paragraphs() == null) continue;
                for (ASTParagraph child : obj.paragraphs()) {
                    appendParagraphText(child, sb);
                }
            }
        }
    }

    private static String toDecimalStoryId(String storyRef) {
        if (storyRef == null || storyRef.isEmpty()) return null;
        String s = storyRef;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        if (s.startsWith("Story_")) s = s.substring("Story_".length());
        if (s.startsWith("u") && s.length() > 1) {
            try {
                return String.valueOf(Integer.parseInt(s.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return storyRef;
            }
        }
        return storyRef;
    }

    private static ASTTableCell findAstCell(ASTTableRow row, int columnIndex) {
        if (row == null || row.cells() == null) return null;
        for (ASTTableCell cell : row.cells()) {
            if (cell.columnIndex() == columnIndex) return cell;
        }
        return null;
    }

    private static void restoreAnchorOnlyEditableInlineGraphics(
            ResolvedBuildContext ctx,
            ASTTable astTable,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable) {
        if (ctx == null || astTable == null || idmlTable == null) return;
        if (astTable.rows() == null || idmlTable.rows() == null) return;

        int rowCount = Math.min(astTable.rows().size(), idmlTable.rows().size());
        for (int ri = 0; ri < rowCount; ri++) {
            ASTTableRow astRow = astTable.rows().get(ri);
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow idmlRow = idmlTable.rows().get(ri);
            if (astRow == null || idmlRow == null || astRow.cells() == null || idmlRow.cells() == null) continue;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell : idmlRow.cells()) {
                ASTTableCell astCell = findAstCell(astRow, idmlCell.columnIndex());
                if (astCell == null || idmlCell == null || astCell.paragraphs() == null || idmlCell.paragraphs() == null) {
                    continue;
                }
                int paraCount = Math.min(astCell.paragraphs().size(), idmlCell.paragraphs().size());
                for (int pi = 0; pi < paraCount; pi++) {
                    IDMLParagraph idmlPara = idmlCell.paragraphs().get(pi);
                    int groupId = anchorOnlyGraphicDomId(idmlPara);
                    if (groupId <= 0) continue;
                    List<ASTInlineObject> boxes =
                            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                                    .buildChildEditableBoxes(ctx, groupId);
                    if (boxes == null || boxes.isEmpty()) continue;

                    ASTParagraph astPara = astCell.paragraphs().get(pi);
                    astPara.items().clear();
                    for (ASTInlineObject box : boxes) {
                        astPara.addItem(box);
                    }
                    ctx.setInlineDisposition(groupId, FrameDisposition.TEXT_BLOCK_PLACED);
                }
            }
        }
    }

    private static int anchorOnlyGraphicDomId(IDMLParagraph para) {
        if (para == null || para.characterRuns() == null) return -1;
        int groupId = -1;
        int anchorCount = 0;
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run == null) continue;
            String content = run.content();
            if (content != null) {
                String visible = content.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim();
                if (!visible.isEmpty()) return -1;
            }
            if (run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (anchor == null) continue;
                if (anchor.type() != IDMLCharacterRun.InlineAnchorType.GRAPHIC) return -1;
                if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
                    return -1;
                }
                IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
                int parsed = parseInlineGraphicDomId(graphic);
                if (parsed <= 0) return -1;
                if (groupId > 0 && groupId != parsed) return -1;
                groupId = parsed;
                anchorCount++;
            }
        }
        return anchorCount == 1 ? groupId : -1;
    }

    private static int parseInlineGraphicDomId(IDMLCharacterRun.InlineGraphic graphic) {
        if (graphic == null || graphic.selfId() == null) return -1;
        String id = graphic.selfId();
        if (id.startsWith("u") || id.startsWith("U")) id = id.substring(1);
        try {
            return Integer.parseInt(id, 16);
        } catch (NumberFormatException e) {
            try {
                return Integer.parseInt(id);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
    }

    private static void restoreRenderedCellInlineGraphics(
            ResolvedBuildContext ctx,
            ASTTable astTable,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable) {
        if (ctx == null || astTable == null || idmlTable == null) return;
        if (astTable.rows() == null || idmlTable.rows() == null) return;

        int rowCount = Math.min(astTable.rows().size(), idmlTable.rows().size());
        for (int ri = 0; ri < rowCount; ri++) {
            ASTTableRow astRow = astTable.rows().get(ri);
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow idmlRow = idmlTable.rows().get(ri);
            if (astRow == null || idmlRow == null || astRow.cells() == null || idmlRow.cells() == null) continue;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell : idmlRow.cells()) {
                ASTTableCell astCell = findAstCell(astRow, idmlCell.columnIndex());
                if (astCell == null || idmlCell == null || astCell.paragraphs() == null || idmlCell.paragraphs() == null) {
                    continue;
                }
                int paraCount = Math.min(astCell.paragraphs().size(), idmlCell.paragraphs().size());
                for (int pi = 0; pi < paraCount; pi++) {
                    IDMLParagraph idmlPara = idmlCell.paragraphs().get(pi);
                    int anchorOnlyId = anchorOnlyGraphicDomId(idmlPara);
                    if (anchorOnlyId > 0
                            && !isAtomicRenderedInlineGraphic(ctx, anchorOnlyId)
                            && !kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                                    .buildChildEditableBoxes(ctx, anchorOnlyId)
                                    .isEmpty()) {
                        continue;
                    }
                    ASTParagraph astPara = astCell.paragraphs().get(pi);
                    if (astPara == null) continue;
                    restoreRenderedCellInlineGraphics(ctx, astPara, idmlPara);
                }
            }
        }
    }

    private static void restoreRenderedCellInlineGraphics(
            ResolvedBuildContext ctx,
            ASTParagraph astPara,
            IDMLParagraph idmlPara) {
        if (idmlPara == null || idmlPara.characterRuns() == null) return;
        Set<Integer> restoredIds = new HashSet<>();
        for (IDMLCharacterRun run : idmlPara.characterRuns()) {
            if (run == null || run.inlineAnchors() == null || run.inlineAnchors().isEmpty()) continue;
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                if (anchor == null || anchor.type() != IDMLCharacterRun.InlineAnchorType.GRAPHIC) continue;
                if (run.inlineGraphics() == null || anchor.index() < 0 || anchor.index() >= run.inlineGraphics().size()) {
                    continue;
                }
                int domId = parseInlineGraphicDomId(run.inlineGraphics().get(anchor.index()));
                if (domId <= 0 || !restoredIds.add(domId)) continue;

                if (kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                        .isSimpleButtonLabelAnchor(ctx, domId)) {
                    ASTInlineObject labelObject = SimpleButtonLabelInlineFactory.create(ctx, domId);
                    if (labelObject != null) {
                        labelObject.keepInline(true);
                        SimpleButtonLabelPlan plan = ctx.simpleButtonLabelPlan(domId);
                        replaceLabelTextWithInlineObject(astPara, plan != null ? plan.labelText : null, labelObject);
                        continue;
                    }
                }

                if (isExpandableCellInlineGroup(ctx, domId, idmlPara)) {
                    continue;
                }
                if (!isAtomicRenderedInlineGraphic(ctx, domId)) {
                    continue;
                }
                ASTInlineObject inline =
                        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                                .loadInlineObject(ctx, domId);
                if (inline == null) {
                    inline = loadOwnershipPlannedAtomicInline(ctx, domId);
                    if (inline == null) continue;
                }
                // 셀 단락이 공용 루틴(buildParagraphContent)으로 빌드되면서 동일 앵커를 이미
                // 인라인(배지 drawText 등)으로 임베드한 경우, 렌더 PNG를 또 얹지 않는다.
                // (안 그러면 텍스트 없는 배지 배경 PNG가 인라인 텍스트를 가림 — 노란 박스 가림 버그)
                if (containsInlineSource(astPara, inline.sourceId())) continue;
                inline.keepInline(true);
                astPara.addItem(inline);
            }
        }
    }

    private static void replaceLabelTextWithInlineObject(ASTParagraph astPara,
                                                         String labelText,
                                                         ASTInlineObject inline) {
        if (astPara == null || inline == null || astPara.items() == null) return;
        if (containsInlineSource(astPara, inline.sourceId())) return;
        if (labelText == null || labelText.trim().isEmpty()) {
            astPara.items().add(0, inline);
            return;
        }
        String label = labelText.trim();
        for (int i = 0; i < astPara.items().size(); i++) {
            ASTInlineItem item = astPara.items().get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.isEmpty()) continue;
            String compact = text.replaceAll("\\s+", "");
            if (label.equals(compact)) {
                astPara.items().set(i, inline);
                return;
            }
            if (text.startsWith(label)) {
                String rest = text.substring(label.length());
                if (rest.isEmpty()) {
                    astPara.items().set(i, inline);
                } else {
                    run.text(rest);
                    astPara.items().add(i, inline);
                }
                return;
            }
        }
        astPara.items().add(0, inline);
    }

    private static boolean containsInlineSource(ASTParagraph astPara, String sourceId) {
        if (astPara == null || astPara.items() == null || sourceId == null) return false;
        for (ASTInlineItem item : astPara.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            ASTInlineObject inline = (ASTInlineObject) item;
            if (sourceId.equals(inline.sourceId())) return true;
        }
        return false;
    }

    private static ASTInlineObject loadOwnershipPlannedAtomicInline(ResolvedBuildContext ctx, int domId) {
        RenderedGroup rg = findRenderedInlineObject(ctx, domId);
        if (rg == null || !isAtomicRenderedInlineGraphic(ctx, domId)) return null;
        if (!ctx.hasOwnershipPlan(rg) || !ctx.shouldPlaceInlinePngByOwnershipPlan(rg)) return null;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return null;
        if (!"indesign_png".equals(rg.visualOwner())) return null;
        if (Boolean.TRUE.equals(rg.containsEditableText())) return null;
        String textOwner = rg.textOwner();
        if (textOwner != null && !"none".equals(textOwner)) return null;

        File pngFile = new File(ctx.basePath, rg.file());
        if (!pngFile.exists()) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
            if (img == null || (img.getWidth() <= 2 && img.getHeight() <= 2)) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.imageData(imageData);
            obj.imageFormat("png");
            obj.pixelWidth(img.getWidth());
            obj.pixelHeight(img.getHeight());
            obj.sourceId("u" + Integer.toHexString(domId));
            obj.keepInline(true);

            double[] bounds = rg.bounds();
            if (bounds != null && bounds.length >= 4) {
                double bw = Math.abs(bounds[3] - bounds[1]) * ctx.scaleFactor;
                double bh = Math.abs(bounds[2] - bounds[0]) * ctx.scaleFactor;
                obj.boundsX(bounds[1]);
                obj.width(CoordinateConverter.pointsToHwpunits(bw));
                obj.height(CoordinateConverter.pointsToHwpunits(bh));
            } else if (ctx.pngExportDpi > 0) {
                obj.width(CoordinateConverter.pointsToHwpunits(img.getWidth() * 72.0 / ctx.pngExportDpi));
                obj.height(CoordinateConverter.pointsToHwpunits(img.getHeight() * 72.0 / ctx.pngExportDpi));
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAtomicRenderedInlineGraphic(ResolvedBuildContext ctx, int domId) {
        RenderedGroup rg = findRenderedInlineObject(ctx, domId);
        if (rg == null) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        String visualOwner = rg.visualOwner();
        if (visualOwner != null && !"indesign_png".equals(visualOwner)) return false;
        if (Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String textOwner = rg.textOwner();
        return textOwner == null || "none".equals(textOwner);
    }

    private static RenderedGroup findRenderedInlineObject(ResolvedBuildContext ctx, int domId) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != domId) continue;
            if (!"inline_object".equals(rg.type()) && !"inline_object".equals(rg.itemType())) continue;
            if (rg.file() == null) continue;
            return rg;
        }
        return null;
    }

    private static boolean isExpandableCellInlineGroup(
            ResolvedBuildContext ctx,
            int domId,
            IDMLParagraph para) {
        List<ASTInlineObject> boxList =
                kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                        .tryInlineGroupAsBoxList(ctx, domId);
        if (boxList == null || boxList.size() < 2) return false;
        if (para == null || para.characterRuns() == null) return true;
        for (IDMLCharacterRun otherRun : para.characterRuns()) {
            String ct = otherRun.content();
            if (ct != null && !ct.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 테이블 셀에 포함된 인라인 그룹(tryInlineGroupAsBoxList가 [leftITF, rightITF]를 반환)이
     * 있으면 해당 컬럼을 2개로 분할하여 3컬럼 테이블로 재구성한다.
     * 처리된 그룹 ID는 TEXT_BLOCK_PLACED로 등록하여 Phase 7 중복 배치를 방지한다.
     *
     * @return 재구성된 ASTTable, 또는 확장 불필요 시 null
     */
    private static ASTTable tryExpandInlineGroupColumns(
            ResolvedBuildContext ctx,
            ASTTable original,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable) {

        if (ctx == null || ctx.resolvedData == null) return null;

        // (rowIdx_colIdx) → [leftITF, rightITF]
        Map<String, List<ASTInlineObject>> expansionMap = new HashMap<>();
        Set<Integer> expandColIndices = new HashSet<>();
        Set<Integer> suppressGroupIds = new HashSet<>();
        long firstLeftW = 0, firstRightW = 0;

        int rowIdx = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow idmlRow : idmlTable.rows()) {
            int colIdx = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell : idmlRow.cells()) {
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para : idmlCell.paragraphs()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : para.characterRuns()) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                            if (anchor.type() != kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineAnchorType.GRAPHIC) continue;
                            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineGraphic> igs = run.inlineGraphics();
                            if (anchor.index() >= igs.size()) continue;
                            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineGraphic ig = igs.get(anchor.index());
                            if (ig == null || ig.selfId() == null) continue;
                            String hexId = ig.selfId().startsWith("u") ? ig.selfId().substring(1) : ig.selfId();
                            int domId;
                            try { domId = Integer.parseInt(hexId, 16); } catch (NumberFormatException e) { continue; }

                            List<ASTInlineObject> boxList =
                                kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler.tryInlineGroupAsBoxList(ctx, domId);
                            if (boxList != null && boxList.size() >= 2) {
                                // 인라인 그룹이 텍스트 흐름 안에 박혀있는 경우 (앞뒤에 텍스트 있음)
                                // → 테이블 컬럼 분할 대상이 아님. 단독 셀 콘텐츠일 때만 확장.
                                boolean hasTextBesideAnchor = false;
                                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun otherRun : para.characterRuns()) {
                                    String ct = otherRun.content();
                                    if (ct != null && !ct.trim().isEmpty()) {
                                        hasTextBesideAnchor = true;
                                        break;
                                    }
                                }
                                if (hasTextBesideAnchor) continue;

                                String key = rowIdx + "_" + colIdx;
                                if (!expansionMap.containsKey(key)) {
                                    expansionMap.put(key, boxList);
                                    expandColIndices.add(colIdx);
                                    suppressGroupIds.add(domId);
                                    if (firstLeftW == 0) {
                                        firstLeftW = boxList.get(0).width();
                                        firstRightW = boxList.get(1).width();
                                    }
                                }
                            }
                        }
                    }
                }
                colIdx++;
            }
            rowIdx++;
        }

        if (expansionMap.isEmpty()) return null;

        // Phase 7 중복 배치 방지: 부모 그룹 ID + 같은 파일을 공유하는 TF ID + 자손 TF ID 모두 등록
        suppressAllDescendantsFromPhase7(ctx, suppressGroupIds);

        // 확장 컬럼을 2개로 분할한 새 컬럼 너비 목록
        List<Long> origColWidths = original.columnWidths();
        List<Long> newColWidths = new java.util.ArrayList<>();
        Map<Integer, Integer> oldToNewCol = new HashMap<>();
        int nc = 0;
        for (int c = 0; c < origColWidths.size(); c++) {
            oldToNewCol.put(c, nc);
            if (expandColIndices.contains(c)) {
                newColWidths.add(firstLeftW);
                newColWidths.add(firstRightW);
                nc += 2;
            } else {
                newColWidths.add(origColWidths.get(c));
                nc++;
            }
        }

        ASTTable newTable = new ASTTable();
        newTable.sourceId(original.sourceId());
        newTable.x(original.x());
        newTable.y(original.y());
        newTable.zOrder(original.zOrder());
        newTable.flowWithText(original.flowWithText());
        newTable.anchoredFlowWithText(original.anchoredFlowWithText());
        newTable.appliedTableStyle(original.appliedTableStyle());
        newTable.borderColor(original.borderColor());
        newTable.borderWidth(original.borderWidth());
        for (long cw : newColWidths) newTable.addColumnWidth(cw);
        newTable.colCount(newColWidths.size());

        int ri = 0;
        for (ASTTableRow origRow : original.rows()) {
            ASTTableRow newRow = new ASTTableRow();
            newRow.rowIndex(ri);
            newRow.rowHeight(origRow.rowHeight());
            newRow.autoGrow(origRow.autoGrow());

            for (ASTTableCell origCell : origRow.cells()) {
                int oldCol = origCell.columnIndex();
                int newBaseCol = oldToNewCol.getOrDefault(oldCol, oldCol);
                String key = ri + "_" + oldCol;
                List<ASTInlineObject> boxList = expansionMap.get(key);

                if (boxList != null) {
                    // 확장 셀: leftITF 셀 + rightITF 셀
                    ASTInlineObject leftITF = boxList.get(0);
                    leftITF.height(origRow.rowHeight());

                    ASTTableCell leftCell = cloneCellMeta(origCell);
                    leftCell.rowIndex(ri);
                    leftCell.columnIndex(newBaseCol);
                    leftCell.width(newColWidths.get(newBaseCol));
                    leftCell.height(origCell.height());
                    ASTParagraph leftPara = new ASTParagraph();
                    leftPara.addItem(leftITF);
                    leftCell.addParagraph(leftPara);
                    newRow.addCell(leftCell);

                    ASTInlineObject rightITF = boxList.get(1);
                    rightITF.height(origRow.rowHeight());

                    ASTTableCell rightCell = cloneCellMeta(origCell);
                    rightCell.rowIndex(ri);
                    rightCell.columnIndex(newBaseCol + 1);
                    rightCell.width(newColWidths.get(newBaseCol + 1));
                    rightCell.height(origCell.height());
                    ASTParagraph rightPara = new ASTParagraph();
                    rightPara.addItem(rightITF);
                    rightCell.addParagraph(rightPara);
                    newRow.addCell(rightCell);
                } else {
                    // 일반 셀: 복사 후 columnIndex 갱신
                    ASTTableCell newCell = cloneCellFull(origCell);
                    newCell.rowIndex(ri);
                    newCell.columnIndex(newBaseCol);
                    // 확장 컬럼이지만 이 행에 inline group 없음 → 두 분할 열을 colSpan=2로 병합
                    if (expandColIndices.contains(oldCol)) {
                        long mergedW = 0;
                        if (newBaseCol < newColWidths.size()) mergedW += newColWidths.get(newBaseCol);
                        if (newBaseCol + 1 < newColWidths.size()) mergedW += newColWidths.get(newBaseCol + 1);
                        newCell.width(mergedW);
                        newCell.columnSpan(2);
                    } else {
                        if (newBaseCol < newColWidths.size()) newCell.width(newColWidths.get(newBaseCol));
                    }
                    newRow.addCell(newCell);
                }
            }

            newTable.addRow(newRow);
            ri++;
        }
        newTable.rowCount(ri);

        long totalW = 0;
        for (long cw : newColWidths) totalW += cw;
        newTable.width(totalW);
        long totalH = 0;
        for (ASTTableRow row : newTable.rows()) totalH += row.rowHeight();
        newTable.height(totalH);

        return newTable;
    }

    /** 셀 메타데이터(테두리/여백/색상)만 복사, 내용(단락) 제외 */
    private static ASTTableCell cloneCellMeta(ASTTableCell src) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(src.rowIndex());
        cell.columnIndex(src.columnIndex());
        cell.rowSpan(src.rowSpan());
        cell.columnSpan(src.columnSpan());
        cell.fillColor(src.fillColor());
        cell.topBorder(src.topBorder());
        cell.bottomBorder(src.bottomBorder());
        cell.leftBorder(src.leftBorder());
        cell.rightBorder(src.rightBorder());
        cell.topLeftDiagonalLine(src.topLeftDiagonalLine());
        cell.topRightDiagonalLine(src.topRightDiagonalLine());
        cell.diagonalBorder(src.diagonalBorder());
        cell.marginTop(src.marginTop());
        cell.marginBottom(src.marginBottom());
        cell.marginLeft(src.marginLeft());
        cell.marginRight(src.marginRight());
        cell.verticalAlign(src.verticalAlign());
        cell.width(src.width());
        cell.height(src.height());
        return cell;
    }

    /** 셀 전체(메타+단락) 복사 */
    private static ASTTableCell cloneCellFull(ASTTableCell src) {
        ASTTableCell cell = cloneCellMeta(src);
        if (src.paragraphs() != null) {
            for (ASTParagraph p : src.paragraphs()) cell.addParagraph(p);
        }
        return cell;
    }

    /**
     * 편집 우선 테이블 정책:
     * 표 셀 영역을 거의 덮는 별도 page_object 사각형은 PNG로 다시 얹지 않고
     * HWP 셀 배경색으로 흡수한다. 셀 내부 라벨/부분 장식은 그대로 둔다.
     */
    private static int absorbCellBackgroundPageObjects(
            ResolvedBuildContext ctx,
            ASTTable table,
            int pageIdx) {
        if (ctx == null || ctx.resolvedData == null || table == null) return 0;

        Map<Integer, ResolvedPageItem> pageItemById = new HashMap<>();
        for (ResolvedPageItem item : ctx.resolvedData.pageItems()) {
            if (item == null || item.id() == null) continue;
            try {
                pageItemById.put(Integer.parseInt(item.id()), item);
            } catch (NumberFormatException ignored) {
            }
        }
        if (pageItemById.isEmpty()) return 0;

        double tableLeftPt = CoordinateConverter.hwpunitsToPoints(table.x());
        double tableTopPt = CoordinateConverter.hwpunitsToPoints(table.y());

        long[] colLeft = buildColumnOffsets(table.columnWidths());
        long[] rowTop = buildRowOffsets(table.rows());

        int absorbed = 0;
        Set<Integer> absorbedItemIds = new HashSet<>();
        for (ResolvedPageItem item : ctx.resolvedData.pageItems()) {
            if (item == null || item.id() == null) continue;
            int itemPageIdx = ctx.toSectionIndex.applyAsInt(item.pageIndex());
            if (itemPageIdx != pageIdx) continue;
            int itemId;
            try {
                itemId = Integer.parseInt(item.id());
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (ctx.resolvedData.isInlineObjectId(itemId)) continue;
            if (ctx.isRenderedDisposed(itemId, FrameDisposition.TEXT_BLOCK_PLACED)) continue;
            if (!isAbsorbableCellBackground(item)) continue;

            double[] b = pageRelativeBoundsInPoints(ctx, item);
            if (b == null || b.length < 4) continue;
            double rectTop = b[0], rectLeft = b[1], rectBottom = b[2], rectRight = b[3];
            if (rectRight <= rectLeft || rectBottom <= rectTop) continue;

            String fill = resolveFillHex(ctx, item);
            if (fill == null) continue;

            List<ASTTableCell> matchedCells = findCoveredCells(
                    table, colLeft, rowTop, tableLeftPt, tableTopPt,
                    rectLeft, rectTop, rectRight, rectBottom);
            if (matchedCells.isEmpty()) {
                ASTTableCell matched = findCoveredCell(
                        table, colLeft, rowTop, tableLeftPt, tableTopPt,
                        rectLeft, rectTop, rectRight, rectBottom);
                if (matched != null) matchedCells.add(matched);
            }
            if (matchedCells.isEmpty()) continue;

            for (ASTTableCell matched : matchedCells) {
                matched.fillColor(fill);
            }
            markPageItemHandled(ctx, itemId);
            absorbedItemIds.add(itemId);
            absorbed++;
            if (ctx.debugAst) {
                table.debugOrNew().note("cell background absorbed: page_item " + itemId
                        + " -> cells=" + matchedCells.size());
            }
        }
        suppressRenderedGroupsCoveredByAbsorbedCellBackgrounds(ctx, absorbedItemIds, pageItemById);
        return absorbed;
    }

    /**
     * 테이블화된 영역 위에 남아 있는 InDesign 선분/외곽선을 HWP 셀 border로 흡수한다.
     * 셀 자체의 IDML edge 정보가 없고 별도 GraphicLine/Rectangle이 선을 소유하는
     * 문서가 있으므로, 테이블 grid와 실제 source object bounds가 맞는 경우만 처리한다.
     */
    private static int absorbTableBorderPageObjects(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            ASTTable table,
            int pageIdx) {
        if (ctx == null || ctx.resolvedData == null || table == null) return 0;

        Map<Integer, ResolvedPageItem> pageItemById = new HashMap<>();
        for (ResolvedPageItem item : ctx.resolvedData.pageItems()) {
            int itemId = parseId(item != null ? item.id() : null);
            if (itemId >= 0) pageItemById.put(itemId, item);
        }
        if (pageItemById.isEmpty()) return 0;

        double tableLeftPt = CoordinateConverter.hwpunitsToPoints(table.x());
        double tableTopPt = CoordinateConverter.hwpunitsToPoints(table.y());
        long[] colLeft = buildColumnOffsets(table.columnWidths());
        long[] rowTop = buildRowOffsets(table.rows());

        int absorbed = 0;
        Set<Integer> absorbedItemIds = new HashSet<>();
        for (ResolvedPageItem item : ctx.resolvedData.pageItems()) {
            if (!isAbsorbableTableBorderItem(ctx, item)) continue;
            int itemPageIdx = ctx.toSectionIndex.applyAsInt(item.pageIndex());
            boolean sourceLinked = isTableBorderSourceLinkedToFrame(item, tf, pageItemById);
            if (itemPageIdx != pageIdx && !sourceLinked) continue;
            int itemId = parseId(item.id());
            if (itemId < 0) continue;

            double[] b = pageRelativeBoundsInPoints(ctx, item);
            if (b == null || b.length < 4) continue;
            ASTTableCell.CellBorder border = pageItemStrokeBorder(ctx, item);
            if (border == null) continue;

            boolean matched = applyBorderItemToTable(
                    table, colLeft, rowTop, tableLeftPt, tableTopPt, item, b, border);
            if (!matched) continue;

            markPageItemHandled(ctx, itemId);
            absorbedItemIds.add(itemId);
            absorbed++;
            if (ctx.debugAst) {
                table.debugOrNew().note("table border absorbed: page_item " + itemId
                        + " type=" + item.type());
            }
        }

        suppressRenderedGroupsCoveredByAbsorbedCellBackgrounds(ctx, absorbedItemIds, pageItemById);
        return absorbed;
    }

    private static void completeVisibleTableOuterBorder(ASTTable table) {
        ASTTableCell.CellBorder border = representativeVisibleBorder(table);
        if (border == null || table == null || table.rows() == null || table.rows().isEmpty()) return;
        int lastRow = Math.max(0, table.rowCount() - 1);
        int lastCol = Math.max(0, table.colCount() - 1);
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan()) - 1;
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan()) - 1;
                if (rowStart <= 0) cell.topBorder(preferExistingBorder(cell.topBorder(), border));
                if (rowEnd >= lastRow) cell.bottomBorder(preferExistingBorder(cell.bottomBorder(), border));
                if (colStart <= 0) cell.leftBorder(preferExistingBorder(cell.leftBorder(), border));
                if (colEnd >= lastCol) cell.rightBorder(preferExistingBorder(cell.rightBorder(), border));
            }
        }
    }

    private static ASTTableCell.CellBorder representativeVisibleBorder(ASTTable table) {
        if (table == null || table.rows() == null) return null;
        ASTTableCell.CellBorder best = null;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                best = strongerBorder(best, cell.leftBorder());
                best = strongerBorder(best, cell.rightBorder());
                best = strongerBorder(best, cell.topBorder());
                best = strongerBorder(best, cell.bottomBorder());
            }
        }
        return best != null ? cloneBorder(best) : null;
    }

    private static ASTTableCell.CellBorder strongerBorder(
            ASTTableCell.CellBorder current,
            ASTTableCell.CellBorder candidate) {
        if (!isVisibleCellBorder(candidate)) return current;
        if (current == null || candidate.weight() > current.weight()) return candidate;
        return current;
    }

    private static boolean isTableBorderSourceLinkedToFrame(
            ResolvedPageItem item,
            ResolvedTextFrame tf,
            Map<Integer, ResolvedPageItem> pageItemById) {
        if (item == null || tf == null) return false;
        int itemId = parseId(item.id());
        int tfId = parseId(tf.id());
        if (itemId < 0 || tfId < 0) return false;
        if (itemId == tfId) return true;
        if (item.parentId() != null && parseId(item.parentId()) == tfId) return true;
        ResolvedPageItem tfItem = pageItemById != null ? pageItemById.get(tfId) : null;
        if (tfItem != null && tfItem.parentId() != null && parseId(tfItem.parentId()) == itemId) return true;
        if (item.childIds() != null) {
            for (int childId : item.childIds()) {
                if (childId == tfId) return true;
            }
        }
        return false;
    }

    private static boolean isAbsorbableTableBorderItem(ResolvedBuildContext ctx, ResolvedPageItem item) {
        if (ctx == null || ctx.resolvedData == null || item == null) return false;
        int itemId = parseId(item.id());
        if (itemId >= 0 && ctx.resolvedData.isInlineObjectId(itemId)) return false;
        if (item.isInline()) return false;
        if (!hasVisibleStroke(item)) return false;
        if (Math.abs(item.absoluteRotationAngle()) > 0.1) return false;
        if (Math.abs(item.absoluteShearAngle()) > 0.1) return false;
        if (item.hasDropShadow()) return false;
        if (item.gradientFeatherApplied()) return false;

        String type = item.type();
        return "Rectangle".equals(type)
                || "TextFrame".equals(type)
                || "GraphicLine".equals(type);
    }

    private static boolean applyBorderItemToTable(
            ASTTable table,
            long[] colLeft,
            long[] rowTop,
            double tableLeftPt,
            double tableTopPt,
            ResolvedPageItem item,
            double[] bounds,
            ASTTableCell.CellBorder border) {
        double top = bounds[0];
        double left = bounds[1];
        double bottom = bounds[2];
        double right = bounds[3];
        if (right < left) {
            double tmp = left;
            left = right;
            right = tmp;
        }
        if (bottom < top) {
            double tmp = top;
            top = bottom;
            bottom = tmp;
        }
        double width = right - left;
        double height = bottom - top;
        if (width < 0 || height < 0) return false;

        double tolerance = borderMatchTolerance(border);
        if ("GraphicLine".equals(item.type()) || width <= tolerance || height <= tolerance) {
            return applyLineBorderToTable(
                    table, colLeft, rowTop, tableLeftPt, tableTopPt,
                    left, top, right, bottom, border, tolerance);
        }
        return applyRectangleBorderToTable(
                table, colLeft, rowTop, tableLeftPt, tableTopPt,
                left, top, right, bottom, border, tolerance);
    }

    private static boolean applyRectangleBorderToTable(
            ASTTable table,
            long[] colLeft,
            long[] rowTop,
            double tableLeftPt,
            double tableTopPt,
            double left,
            double top,
            double right,
            double bottom,
            ASTTableCell.CellBorder border,
            double tolerance) {
        int c0 = findGridEdgeIndex(colLeft, tableLeftPt, left, tolerance);
        int c1 = findGridEdgeIndex(colLeft, tableLeftPt, right, tolerance);
        int r0 = findGridEdgeIndex(rowTop, tableTopPt, top, tolerance);
        int r1 = findGridEdgeIndex(rowTop, tableTopPt, bottom, tolerance);
        if (c0 < 0 || c1 < 0 || r0 < 0 || r1 < 0 || c1 <= c0 || r1 <= r0) return false;

        boolean matched = false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan());
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan());
                if (!spansOverlap(rowStart, rowEnd, r0, r1)
                        || !spansOverlap(colStart, colEnd, c0, c1)) {
                    continue;
                }
                if (rowStart == r0) {
                    cell.topBorder(preferExistingBorder(cell.topBorder(), border));
                    matched = true;
                }
                if (rowEnd == r1) {
                    cell.bottomBorder(preferExistingBorder(cell.bottomBorder(), border));
                    matched = true;
                }
                if (colStart == c0) {
                    cell.leftBorder(preferExistingBorder(cell.leftBorder(), border));
                    matched = true;
                }
                if (colEnd == c1) {
                    cell.rightBorder(preferExistingBorder(cell.rightBorder(), border));
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean applyLineBorderToTable(
            ASTTable table,
            long[] colLeft,
            long[] rowTop,
            double tableLeftPt,
            double tableTopPt,
            double left,
            double top,
            double right,
            double bottom,
            ASTTableCell.CellBorder border,
            double tolerance) {
        double width = right - left;
        double height = bottom - top;
        if (width <= 0 && height <= 0) return false;

        if (width >= height) {
            int rowEdge = findGridEdgeIndex(rowTop, tableTopPt, (top + bottom) / 2.0, tolerance);
            int c0 = findGridEdgeIndex(colLeft, tableLeftPt, left, tolerance);
            int c1 = findGridEdgeIndex(colLeft, tableLeftPt, right, tolerance);
            if (rowEdge < 0 || c0 < 0 || c1 < 0 || c1 <= c0) return false;
            return applyHorizontalGridBorder(table, rowEdge, c0, c1, border);
        }

        int colEdge = findGridEdgeIndex(colLeft, tableLeftPt, (left + right) / 2.0, tolerance);
        int r0 = findGridEdgeIndex(rowTop, tableTopPt, top, tolerance);
        int r1 = findGridEdgeIndex(rowTop, tableTopPt, bottom, tolerance);
        if (colEdge < 0 || r0 < 0 || r1 < 0 || r1 <= r0) return false;
        return applyVerticalGridBorder(table, colEdge, r0, r1, border);
    }

    private static boolean applyHorizontalGridBorder(
            ASTTable table,
            int rowEdge,
            int c0,
            int c1,
            ASTTableCell.CellBorder border) {
        boolean matched = false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan());
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan());
                if (!spansOverlap(colStart, colEnd, c0, c1)) continue;
                if (rowEnd == rowEdge) {
                    cell.bottomBorder(preferExistingBorder(cell.bottomBorder(), border));
                    matched = true;
                }
                if (rowStart == rowEdge) {
                    cell.topBorder(preferExistingBorder(cell.topBorder(), border));
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static boolean applyVerticalGridBorder(
            ASTTable table,
            int colEdge,
            int r0,
            int r1,
            ASTTableCell.CellBorder border) {
        boolean matched = false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan());
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan());
                if (!spansOverlap(rowStart, rowEnd, r0, r1)) continue;
                if (colEnd == colEdge) {
                    cell.rightBorder(preferExistingBorder(cell.rightBorder(), border));
                    matched = true;
                }
                if (colStart == colEdge) {
                    cell.leftBorder(preferExistingBorder(cell.leftBorder(), border));
                    matched = true;
                }
            }
        }
        return matched;
    }

    private static int findGridEdgeIndex(long[] offsets, double originPt, double valuePt, double tolerancePt) {
        if (offsets == null) return -1;
        int best = -1;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < offsets.length; i++) {
            double edge = originPt + CoordinateConverter.hwpunitsToPoints(offsets[i]);
            double delta = Math.abs(edge - valuePt);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return bestDelta <= tolerancePt ? best : -1;
    }

    private static boolean spansOverlap(int a0, int a1, int b0, int b1) {
        return Math.max(a0, b0) < Math.min(a1, b1);
    }

    private static double borderMatchTolerance(ASTTableCell.CellBorder border) {
        double weight = border != null ? Math.max(0.0, border.weight()) : 0.0;
        return Math.max(3.0, weight * 3.0 + 1.0);
    }

    private static long[] buildColumnOffsets(List<Long> widths) {
        int n = widths == null ? 0 : widths.size();
        long[] offsets = new long[n + 1];
        for (int i = 0; i < n; i++) offsets[i + 1] = offsets[i] + widths.get(i);
        return offsets;
    }

    private static long[] buildRowOffsets(List<ASTTableRow> rows) {
        int n = rows == null ? 0 : rows.size();
        long[] offsets = new long[n + 1];
        for (int i = 0; i < n; i++) offsets[i + 1] = offsets[i] + rows.get(i).rowHeight();
        return offsets;
    }

    private static boolean isAbsorbableCellBackground(ResolvedPageItem item) {
        if (item == null) return false;
        if (!"Rectangle".equals(item.type()) && !"TextFrame".equals(item.type())) return false;
        String fill = item.fillColorName();
        if (fill == null || fill.contains("None")) return false;
        if (item.opacity() > 0 && item.opacity() < 95.0) return false;
        if (Math.abs(item.absoluteRotationAngle()) > 0.1) return false;
        if (Math.abs(item.absoluteShearAngle()) > 0.1) return false;
        if (item.hasDropShadow()) return false;
        if (item.gradientFeatherApplied()) return false;
        return true;
    }

    private static String resolveFillHex(ResolvedBuildContext ctx, ResolvedPageItem item) {
        return ctx.resolvedData.resolveTintedColorHex(item.fillColorName(), item.fillTint());
    }

    private static double[] pageRelativeBoundsInPoints(ResolvedBuildContext ctx, ResolvedPageItem item) {
        double[] b = item.pageRelativeBounds();
        if (b != null && b.length >= 4) {
            return new double[]{b[0], b[1], b[2], b[3]};
        }
        double[] fallback = item.geometricBounds() != null ? item.geometricBounds() : item.visibleBounds();
        if (fallback == null || fallback.length < 4) return fallback;
        double pageTop = 0.0;
        double pageLeft = 0.0;
        if (ctx != null && ctx.resolvedData != null
                && item.pageIndex() >= 0
                && item.pageIndex() < ctx.resolvedData.pages().size()) {
            ResolvedPage page = ctx.resolvedData.pages().get(item.pageIndex());
            if (page != null && page.bounds() != null && page.bounds().length >= 4) {
                pageTop = page.bounds()[0];
                pageLeft = page.bounds()[1];
            }
        }
        double[] pageRelative = new double[]{
                fallback[0] - pageTop,
                fallback[1] - pageLeft,
                fallback[2] - pageTop,
                fallback[3] - pageLeft
        };
        return pageRelative;
    }

    private static List<ASTTableCell> findCoveredCells(
            ASTTable table,
            long[] colLeft,
            long[] rowTop,
            double tableLeftPt,
            double tableTopPt,
            double rectLeft,
            double rectTop,
            double rectRight,
            double rectBottom) {
        List<ASTTableCell> matches = new ArrayList<>();
        double tolerance = 4.0;
        int c0 = findGridEdgeIndex(colLeft, tableLeftPt, rectLeft, tolerance);
        int c1 = findGridEdgeIndex(colLeft, tableLeftPt, rectRight, tolerance);
        int r0 = findGridEdgeIndex(rowTop, tableTopPt, rectTop, tolerance);
        int r1 = findGridEdgeIndex(rowTop, tableTopPt, rectBottom, tolerance);
        if (c0 < 0 || c1 < 0 || r0 < 0 || r1 < 0 || c1 <= c0 || r1 <= r0) {
            return findMostlyCoveredCells(
                    table, colLeft, rowTop, tableLeftPt, tableTopPt,
                    rectLeft, rectTop, rectRight, rectBottom);
        }

        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan());
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan());
                if (spansOverlap(rowStart, rowEnd, r0, r1)
                        && spansOverlap(colStart, colEnd, c0, c1)) {
                    matches.add(cell);
                }
            }
        }
        return matches;
    }

    private static List<ASTTableCell> findMostlyCoveredCells(
            ASTTable table,
            long[] colLeft,
            long[] rowTop,
            double tableLeftPt,
            double tableTopPt,
            double rectLeft,
            double rectTop,
            double rectRight,
            double rectBottom) {
        List<ASTTableCell> matches = new ArrayList<>();
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int c0 = cell.columnIndex();
                int c1 = Math.min(c0 + Math.max(1, cell.columnSpan()), colLeft.length - 1);
                int r0 = cell.rowIndex();
                int r1 = Math.min(r0 + Math.max(1, cell.rowSpan()), rowTop.length - 1);
                if (c0 < 0 || c0 >= colLeft.length || r0 < 0 || r0 >= rowTop.length) continue;

                double cellLeft = tableLeftPt + CoordinateConverter.hwpunitsToPoints(colLeft[c0]);
                double cellRight = tableLeftPt + CoordinateConverter.hwpunitsToPoints(colLeft[c1]);
                double cellTop = tableTopPt + CoordinateConverter.hwpunitsToPoints(rowTop[r0]);
                double cellBottom = tableTopPt + CoordinateConverter.hwpunitsToPoints(rowTop[r1]);
                if (mostlyCoversCell(
                        rectLeft, rectTop, rectRight, rectBottom,
                        cellLeft, cellTop, cellRight, cellBottom)) {
                    matches.add(cell);
                }
            }
        }
        return matches;
    }

    private static boolean mostlyCoversCell(
            double rectLeft, double rectTop, double rectRight, double rectBottom,
            double cellLeft, double cellTop, double cellRight, double cellBottom) {
        double ix = Math.max(0, Math.min(rectRight, cellRight) - Math.max(rectLeft, cellLeft));
        double iy = Math.max(0, Math.min(rectBottom, cellBottom) - Math.max(rectTop, cellTop));
        double cellWidth = Math.max(0, cellRight - cellLeft);
        double cellHeight = Math.max(0, cellBottom - cellTop);
        if (ix <= 0 || iy <= 0 || cellWidth <= 0 || cellHeight <= 0) return false;
        double horizontalCoverage = ix / cellWidth;
        double verticalCoverage = iy / cellHeight;
        double areaCoverage = (ix * iy) / (cellWidth * cellHeight);
        return horizontalCoverage >= 0.82
                && verticalCoverage >= 0.72
                && areaCoverage >= 0.72;
    }

    private static ASTTableCell findCoveredCell(
            ASTTable table,
            long[] colLeft,
            long[] rowTop,
            double tableLeftPt,
            double tableTopPt,
            double rectLeft,
            double rectTop,
            double rectRight,
            double rectBottom) {
        ASTTableCell best = null;
        double bestScore = 0;
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                int c0 = cell.columnIndex();
                int c1 = Math.min(c0 + Math.max(1, cell.columnSpan()), colLeft.length - 1);
                int r0 = cell.rowIndex();
                int r1 = Math.min(r0 + Math.max(1, cell.rowSpan()), rowTop.length - 1);
                if (c0 < 0 || c0 >= colLeft.length || r0 < 0 || r0 >= rowTop.length) continue;

                double cellLeft = tableLeftPt + CoordinateConverter.hwpunitsToPoints(colLeft[c0]);
                double cellRight = tableLeftPt + CoordinateConverter.hwpunitsToPoints(colLeft[c1]);
                double cellTop = tableTopPt + CoordinateConverter.hwpunitsToPoints(rowTop[r0]);
                double cellBottom = tableTopPt + CoordinateConverter.hwpunitsToPoints(rowTop[r1]);
                double score = fullCellCoverScore(
                        rectLeft, rectTop, rectRight, rectBottom,
                        cellLeft, cellTop, cellRight, cellBottom);
                if (score > bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
        }
        return bestScore >= 0.82 ? best : null;
    }

    private static double fullCellCoverScore(
            double rectLeft, double rectTop, double rectRight, double rectBottom,
            double cellLeft, double cellTop, double cellRight, double cellBottom) {
        double ix = Math.max(0, Math.min(rectRight, cellRight) - Math.max(rectLeft, cellLeft));
        double iy = Math.max(0, Math.min(rectBottom, cellBottom) - Math.max(rectTop, cellTop));
        double intersection = ix * iy;
        double rectArea = Math.max(0, rectRight - rectLeft) * Math.max(0, rectBottom - rectTop);
        double cellArea = Math.max(0, cellRight - cellLeft) * Math.max(0, cellBottom - cellTop);
        if (intersection <= 0 || rectArea <= 0 || cellArea <= 0) return 0;

        double rectCoverage = intersection / rectArea;
        double cellCoverage = intersection / cellArea;
        double edgeTolerancePt = 4.0;
        boolean aligned = Math.abs(rectLeft - cellLeft) <= edgeTolerancePt
                && Math.abs(rectRight - cellRight) <= edgeTolerancePt
                && Math.abs(rectTop - cellTop) <= edgeTolerancePt
                && Math.abs(rectBottom - cellBottom) <= edgeTolerancePt;
        if (!aligned) return 0;
        return Math.min(rectCoverage, cellCoverage);
    }

    private static void markPageItemHandled(ResolvedBuildContext ctx, int itemId) {
        ctx.setRenderedDisposition(itemId, FrameDisposition.TEXT_BLOCK_PLACED);
        ctx.phase6PlacedIds.add(itemId);
    }

    private static void suppressRenderedGroupsCoveredByAbsorbedCellBackgrounds(
            ResolvedBuildContext ctx,
            Set<Integer> absorbedItemIds,
            Map<Integer, ResolvedPageItem> pageItemById) {
        if (ctx == null || ctx.resolvedData == null || absorbedItemIds == null || absorbedItemIds.isEmpty()) {
            return;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.sourceObjectIds() == null) continue;
            boolean containsAbsorbed = false;
            for (int sourceId : rg.sourceObjectIds()) {
                if (absorbedItemIds.contains(sourceId)) {
                    containsAbsorbed = true;
                    break;
                }
            }
            if (!containsAbsorbed) continue;

            if (shouldSuppressRenderedGroupAfterCellAbsorption(ctx, rg, absorbedItemIds, pageItemById)) {
                ctx.setRenderedDisposition(rg.id(), FrameDisposition.TEXT_BLOCK_PLACED);
                ctx.phase6PlacedIds.add(rg.id());
            }
        }
    }

    private static boolean shouldSuppressRenderedGroupAfterCellAbsorption(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            Set<Integer> absorbedItemIds,
            Map<Integer, ResolvedPageItem> pageItemById) {
        if (rg == null || rg.sourceObjectIds() == null) return false;
        String reason = rg.reason();
        if (reason != null
                && !(reason.contains("mixed_group")
                    || reason.contains("text_hidden")
                    || reason.contains("text_composite"))) {
            return false;
        }

        for (int sourceId : rg.sourceObjectIds()) {
            if (absorbedItemIds.contains(sourceId)) continue;
            ResolvedPageItem item = pageItemById.get(sourceId);
            if (item == null) continue;
            if (isNeutralSourceForCellBackgroundParent(ctx, item, pageItemById)) continue;
            return false;
        }
        return true;
    }

    private static boolean isNeutralSourceForCellBackgroundParent(
            ResolvedBuildContext ctx,
            ResolvedPageItem item,
            Map<Integer, ResolvedPageItem> pageItemById) {
        if (item == null) return true;
        String type = item.type();
        if ("Group".equals(type)) return true;
        int id;
        try {
            id = Integer.parseInt(item.id());
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (ctx.resolvedData.isInlineObjectId(id) || item.isInline()
                || isDescendantOfInlineObject(ctx, item, pageItemById)) {
            return true;
        }
        if ("TextFrame".equals(type)) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(item.id());
            return TableFrameOwnershipPolicy.isTableAnchorOnlyFrame(tf);
        }
        return !isVisiblePageItem(item);
    }

    private static boolean isDescendantOfInlineObject(
            ResolvedBuildContext ctx,
            ResolvedPageItem item,
            Map<Integer, ResolvedPageItem> pageItemById) {
        ResolvedPageItem cur = item;
        Set<Integer> visited = new HashSet<>();
        while (cur != null && cur.parentId() != null) {
            int parentId;
            try {
                parentId = Integer.parseInt(cur.parentId());
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (!visited.add(parentId)) return false;
            if (ctx.resolvedData.isInlineObjectId(parentId)) return true;
            ResolvedPageItem parent = pageItemById.get(parentId);
            if (parent == null) return false;
            if (parent.isInline()) return true;
            cur = parent;
        }
        return false;
    }

    private static boolean isVisiblePageItem(ResolvedPageItem item) {
        if (item == null) return false;
        String fill = item.fillColorName();
        if (fill != null && !fill.contains("None")) return true;
        String stroke = item.strokeColorName();
        if (stroke != null && !stroke.contains("None") && item.strokeWeight() > 0) return true;
        return "Image".equals(item.type());
    }

    private static void markPageObjectHandled(ResolvedBuildContext ctx, RenderedGroup rg) {
        ctx.setRenderedDisposition(rg.id(), FrameDisposition.TEXT_BLOCK_PLACED);
        ctx.phase6PlacedIds.add(rg.id());
        Set<Integer> ids = new HashSet<>();
        ids.add(rg.id());
        suppressAllDescendantsFromPhase7(ctx, ids);
    }

    private static String blendColorWithWhite(String hex, double fraction) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return hex;
        try {
            int rgb = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = (int) Math.round(255 + (r - 255) * fraction);
            g = (int) Math.round(255 + (g - 255) * fraction);
            b = (int) Math.round(255 + (b - 255) * fraction);
            return String.format("#%02X%02X%02X",
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            return hex;
        }
    }

    /**
     * 그룹 ID 집합과 그 모든 자손을 Phase 7에서 처리하지 않도록 등록.
     *
     * 처리 순서:
     * 1) groupIds 자체를 TEXT_BLOCK_PLACED로 등록
     * 2) groupIds가 사용하는 배지 PNG 파일 경로를 수집 →
     *    같은 파일을 공유하는 TF(형제 TF) 도 suppressed
     * 3) pageItems childIds BFS로 그룹 자손 전체 수집 → suppressed
     */
    private static void suppressAllDescendantsFromPhase7(
            ResolvedBuildContext ctx, Set<Integer> groupIds) {
        if (ctx.renderedItemDispositions == null || ctx.resolvedData == null) return;

        // Step 1: 부모 그룹 직접 등록
        for (int id : groupIds) ctx.setRenderedDisposition(id, FrameDisposition.TEXT_BLOCK_PLACED);

        // Step 2: 공유 PNG 파일로 렌더된 floating 항목 억제
        Set<String> sharedFiles = new HashSet<>();
        for (RenderedGroup rt : ctx.resolvedData.allRenderedFloatingItems()) {
            if (groupIds.contains(rt.id()) && rt.file() != null) {
                sharedFiles.add(rt.file());
            }
        }
        if (!sharedFiles.isEmpty()) {
            for (RenderedGroup rt : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rt.file() != null && sharedFiles.contains(rt.file())) {
                    ctx.setRenderedDisposition(rt.id(), FrameDisposition.TEXT_BLOCK_PLACED);
                }
            }
        }

        // Step 3: pageItems childIds BFS로 자손 전체 억제
        Map<Integer, int[]> childMap = new HashMap<>();
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || pi.childIds() == null || pi.childIds().length == 0) continue;
            int pid;
            try { pid = Integer.parseInt(pi.id()); } catch (NumberFormatException e) { continue; }
            childMap.put(pid, pi.childIds());
        }
        Queue<Integer> queue = new LinkedList<>(groupIds);
        Set<Integer> visited = new HashSet<>(groupIds);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            int[] children = childMap.get(cur);
            if (children == null) continue;
            for (int child : children) {
                if (visited.add(child)) {
                    ctx.setRenderedDisposition(child, FrameDisposition.TEXT_BLOCK_PLACED);
                    queue.add(child);
                }
            }
        }
    }

    /**
     * SPEC-017 Step D: ASTTable의 트리거 셀에서 인라인 객체를 floating ASTFigure로 추출.
     *
     * @return {@code [총 추출 인라인 수, 트리거 셀 수]}
     */
    private static int[] extractInlinesFromCells(ASTTable astTable, ASTSection section,
                                                   ConversionConfig.TableQualityGateConfig policy,
                                                   ResolvedBuildContext ctx) {
        int totalExtracted = 0;
        int triggerCells = 0;

        // 컬럼 X 누적 오프셋 (HWPUNIT)
        List<Long> colWidths = astTable.columnWidths();
        long[] colXOffset = new long[colWidths == null ? 1 : colWidths.size() + 1];
        for (int i = 1; i < colXOffset.length; i++) {
            colXOffset[i] = colXOffset[i - 1] + colWidths.get(i - 1);
        }

        long rowYAccum = 0;
        for (ASTTableRow row : astTable.rows()) {
            for (ASTTableCell cell : row.cells()) {
                if (!cellShouldExtract(cell, policy.maxTextLengthWithInline)) continue;
                int colIdx = cell.columnIndex();
                long cellX = astTable.x()
                        + (colIdx >= 0 && colIdx < colXOffset.length ? colXOffset[colIdx] : 0);
                long cellY = astTable.y() + rowYAccum;
                int extracted = extractCellInlines(cell, cellX, cellY, section, astTable.zOrder(), ctx);
                if (extracted > 0) {
                    triggerCells++;
                    totalExtracted += extracted;
                }
            }
            rowYAccum += row.rowHeight();
        }
        return new int[]{totalExtracted, triggerCells};
    }

    /** 셀이 게이트 조건(짧은 텍스트 + 인라인)을 만족하는지 — ASTTableCell 기반 검사. */
    private static boolean cellShouldExtract(ASTTableCell cell, int maxTextLen) {
        if (cell.paragraphs() == null) return false;
        boolean hasInline = false;
        int textLen = 0;
        for (ASTParagraph para : cell.paragraphs()) {
            if (para.items() == null) continue;
            for (ASTInlineItem item : para.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                    ASTInlineObject io = (ASTInlineObject) item;
                    if (io.imageData() != null) hasInline = true;
                } else if (item instanceof ASTTextRun) {
                    String t = ((ASTTextRun) item).text();
                    if (t != null) textLen += t.replace("\uFFFC", "").trim().length();
                }
            }
        }
        return hasInline && textLen < maxTextLen;
    }

    /**
     * 셀 안의 모든 imageData를 가진 인라인 객체를 floating ASTFigure로 추출.
     * 추출된 인라인은 cell.paragraphs.items에서 제거된다.
     */
    private static int extractCellInlines(ASTTableCell cell, long cellX, long cellY,
                                            ASTSection section, int tableZOrder, ResolvedBuildContext ctx) {
        if (cell.paragraphs() == null) return 0;
        int extracted = 0;
        for (ASTParagraph para : cell.paragraphs()) {
            if (para.items() == null) continue;
            Iterator<ASTInlineItem> it = para.items().iterator();
            while (it.hasNext()) {
                ASTInlineItem item = it.next();
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject inline = (ASTInlineObject) item;
                if (inline.imageData() == null) continue;
                if (inline.keepInline()) continue; // IDML inline_object — floating 추출 금지

                ASTFigure fig = inlineToFigure(inline, cellX, cellY, tableZOrder);
                if (fig == null) continue;
                if (ctx.debugAst) {
                    fig.debugOrNew().createdAt = "Phase4.cellInlineExtraction";
                    fig.debug().note("from cell r=" + cell.rowIndex() + " c=" + cell.columnIndex());
                }
                section.addBlock(fig);
                it.remove();
                extracted++;
            }
        }
        return extracted;
    }

    /** ASTInlineObject → ASTFigure (page-level floating image). */
    private static ASTFigure inlineToFigure(ASTInlineObject inline, long cellX, long cellY, int tableZOrder) {
        ASTFigure fig = new ASTFigure();
        // 위치: resolved 절대 좌표 우선, 없으면 셀 좌상단
        long figX = (inline.resolvedPageX() >= 0) ? inline.resolvedPageX() : cellX;
        long figY = (inline.resolvedPageY() >= 0) ? inline.resolvedPageY() : cellY;
        fig.x(figX);
        fig.y(figY);
        // 크기: resolved width/height 우선, 없으면 inline 자체 크기
        long figW = (inline.resolvedWidth() > 0) ? inline.resolvedWidth() : inline.width();
        long figH = (inline.resolvedHeight() > 0) ? inline.resolvedHeight() : inline.height();
        if (figW <= 0 || figH <= 0) return null;
        fig.width(figW);
        fig.height(figH);
        fig.zOrder(tableZOrder + 1); // 표보다 위
        fig.imageData(inline.imageData());
        fig.imageFormat(inline.imageFormat() != null ? inline.imageFormat() : "png");
        fig.pixelWidth(inline.pixelWidth());
        fig.pixelHeight(inline.pixelHeight());
        fig.sourceId(inline.sourceId());
        return fig;
    }

    /** 레거시(preferCellLevel=false) 경로용 — IDML 단계에서 표 전체 게이트 검사. */
    private static boolean idmlTableHasInlineWithShortText(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table, int threshold) {
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
                if (hasInline && textLen < threshold) return true;
            }
        }
        return false;
    }

    /**
     * 인라인 객체가 포함된 테이블 전체를 rendered PNG로 변환.
     * renderedFloatingItems에서 type="table_inline"인 항목을 찾아 사용.
     */
    private static ASTFigure renderTableAsImage(ResolvedBuildContext ctx,
                                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                                ResolvedTextFrame tf, long x, long y, int pageIdx) {
        if (ctx.basePath == null || ctx.resolvedData == null) return null;

        String tfDomId = tf.id();
        int domId;
        try { domId = Integer.parseInt(tfDomId); } catch (NumberFormatException e) { return null; }

        File pngFile = null;
        double[] rgBounds = null;
        boolean hasRenderedMetadata = false;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == domId && rg.file() != null) {
                hasRenderedMetadata = true;
                if (rg.shouldSkipByOwnership()) return null;
                File f = new File(ctx.basePath, rg.file());
                if (f.exists()) {
                    pngFile = f;
                    rgBounds = rg.bounds();
                    break;
                }
            }
        }
        if (pngFile == null && !hasRenderedMetadata) {
            File directFile = new File(ctx.basePath, "rendered_frames/table_" + domId + ".png");
            if (directFile.exists()) {
                pngFile = directFile;
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

            if (rgBounds != null && rgBounds.length >= 4) {
                double bw = Math.abs(rgBounds[3] - rgBounds[1]) * ctx.scaleFactor;
                double bh = Math.abs(rgBounds[2] - rgBounds[0]) * ctx.scaleFactor;
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
            } else {
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

    /**
     * SPEC-017 Step E: 페이지 배경 PNG에서 테이블 영역만 crop하여 ASTFigure 생성.
     * renderedFloatingItems에 단독 표 PNG가 없을 때 fallback.
     *
     * <p>좌표 변환: 표는 thisX/thisY/tw/th(HWPUNIT, 페이지 기준). 페이지 배경 PNG는
     * 픽셀 크기 + bounds(points). 픽셀 스케일 = pageImg.width / pageWidthHwpunit.</p>
     */
    private static ASTFigure cropTableFromPageBackground(ResolvedBuildContext ctx,
                                                          kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                                          long tableX, long tableY, int zOrder, int pageIdx) {
        if (ctx.basePath == null || ctx.resolvedData == null) return null;

        // 1. 표 크기 (HWPUNIT) — column widths 합 + row heights 합
        long tableW = 0, tableH = 0;
        for (double cw : table.columnWidths()) tableW += CoordinateConverter.pointsToHwpunits(cw);
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow r : table.rows())
            tableH += CoordinateConverter.pointsToHwpunits(r.rowHeight());
        if (tableW <= 0 || tableH <= 0) return null;

        // 2. 해당 페이지의 page_background RG 검색
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"page_background".equals(rg.itemType())) continue;
            int rgPageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (rgPageIdx != pageIdx) continue;
            if (rg.file() == null) continue;

            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists()) continue;

            try {
                java.awt.image.BufferedImage pageImg = javax.imageio.ImageIO.read(pngFile);
                if (pageImg == null) continue;

                double[] bounds = rg.bounds();
                if (bounds == null || bounds.length < 4) continue;

                long pageWHwp = CoordinateConverter.pointsToHwpunits((bounds[3] - bounds[1]) * ctx.scaleFactor);
                long pageHHwp = CoordinateConverter.pointsToHwpunits((bounds[2] - bounds[0]) * ctx.scaleFactor);
                if (pageWHwp <= 0 || pageHHwp <= 0) continue;

                double scaleX = (double) pageImg.getWidth() / pageWHwp;
                double scaleY = (double) pageImg.getHeight() / pageHHwp;

                int px = Math.max(0, (int) Math.round(tableX * scaleX));
                int py = Math.max(0, (int) Math.round(tableY * scaleY));
                int pw = (int) Math.round(tableW * scaleX);
                int ph = (int) Math.round(tableH * scaleY);

                if (px + pw > pageImg.getWidth()) pw = pageImg.getWidth() - px;
                if (py + ph > pageImg.getHeight()) ph = pageImg.getHeight() - py;
                if (pw <= 8 || ph <= 8) return null; // 너무 작으면 거부 (화질 우려)

                java.awt.image.BufferedImage cropped = pageImg.getSubimage(px, py, pw, ph);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(cropped, "png", baos);
                byte[] data = baos.toByteArray();

                ASTFigure fig = new ASTFigure();
                fig.sourceId("tbl_crop_" + table.selfId());
                fig.x(tableX);
                fig.y(tableY);
                fig.width(tableW);
                fig.height(tableH);
                fig.zOrder(zOrder);
                fig.imageData(data);
                fig.imageFormat("png");
                fig.pixelWidth(pw);
                fig.pixelHeight(ph);
                return fig;
            } catch (Exception e) {
                System.err.println("[Phase 4] background crop 실패: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static void printReport(Phase4Report r) {
        if (r.total == 0) return;
        System.err.println("[Phase 4] Tables: " + r.total + " total");
        if (r.asTableCleanCount > 0) {
            System.err.println("  · " + r.asTableCleanCount + " → ASTTable (clean)");
        }
        if (r.asTableWithExtraction > 0) {
            System.err.println("  · " + r.asTableWithExtraction + " → ASTTable + "
                    + r.totalInlinesExtracted + " inline figures extracted (cells: "
                    + r.totalCellsExtracted + ")");
        }
        if (r.wholeTablePngRendered > 0) {
            System.err.println("  · " + r.wholeTablePngRendered + " → whole-table PNG fallback (rendered)");
        }
        if (r.wholeTablePngForced > 0) {
            System.err.println("  · " + r.wholeTablePngForced + " → whole-table PNG fallback (nested forced)");
        }
        if (r.cellBackgroundsAbsorbed > 0) {
            System.err.println("  · " + r.cellBackgroundsAbsorbed
                    + " page_object backgrounds absorbed into table cells");
        }
        if (r.tableBordersAbsorbed > 0) {
            System.err.println("  · " + r.tableBordersAbsorbed
                    + " page_object borders absorbed into table cell borders");
        }
        if (r.detachedInlinePageLevel > 0) {
            System.err.println("  · " + r.detachedInlinePageLevel
                    + " detached inline table frames placed page-level");
        }
        if (r.tableOnlyPlansPlaced > 0) {
            System.err.println("  · " + r.tableOnlyPlansPlaced
                    + " table-only ownership plans placed as ASTTable");
        }
        if (r.duplicateInlineTablesRemoved > 0) {
            System.err.println("  · " + r.duplicateInlineTablesRemoved
                    + " duplicate inline tables removed by source ownership");
        }
        if (r.pngMissingFallback > 0) {
            System.err.println("  · WARNING: " + r.pngMissingFallback
                    + " → ASTTable forced (PNG missing, badge duplication risk!)");
        }
    }
}
