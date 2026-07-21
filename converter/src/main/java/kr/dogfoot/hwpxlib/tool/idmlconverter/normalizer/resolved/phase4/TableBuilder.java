package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBreak;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
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
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryFlowAssembler;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.GroupedFlowStackPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TableFrameOwnershipPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.StoryConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * SPEC-013 Phase 4 + SPEC-017 v2: 테이블 포함 TextFrame → ASTTable / ASTFigure 변환.
 *
 * <p>분기 정책 (SPEC-017 migration bridge):</p>
 * <ul>
 *   <li><b>cell-level 모드</b>(기본) → ASTTable로 변환 후 트리거 셀의 인라인 객체만
 *       개별 floating ASTFigure로 추출. 본문 셀은 ASTTable로 유지되어 검색/편집 가능</li>
 *   <li><b>preferCellLevel = false</b>(레거시) → Stage 1 whole-table PNG plan이 있을 때만 실행</li>
 *   <li>planned PNG material이 없으면 새 visible owner를 합성하지 않고 ASTTable을 유지</li>
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
        int detachedInlinePageLevel;    // table-only inline TF → page-level table
        int tableOnlyPlansPlaced;       // ObjectPlan(text_frame:table_only) → ASTTable
        int tableOnlyPlansSkippedAnchored;
        int tableOnlyPlansSkippedInlineFlow;
        int duplicateInlineTablesRemoved;
        int nestedTableBlocksAbsorbed;
        int total;
        int textFramesScanned;
        int textFramesSkippedBeforeStoryLoad;
        int storyLoadAttempts;
        int storyWithTables;
        int storyTablesAlreadyProcessed;
        long tableOnlyPlansNanos;
        long storyLoadNanos;
        long storyTableSetupNanos;
        long buildAstTableNanos;
        long splitTableNanos;
        long suppressVisualNanos;
        long extractCellInlineNanos;
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

        long tableOnlyStart = System.nanoTime();
        placeTableOnlyPlans(ctx, sections, processedTableIds, report);
        report.tableOnlyPlansNanos += System.nanoTime() - tableOnlyStart;

        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            report.textFramesScanned++;
            String storyId = tf.storyId();
            if (storyId == null) continue;
            if (tf.sourceHidden()) {
                report.textFramesSkippedBeforeStoryLoad++;
                continue;
            }
            int tfDomId = parseId(tf.id());
            if (tfDomId >= 0 && ctx.isTextFrameOwnedByTextShellPlan(tfDomId)) {
                report.textFramesSkippedBeforeStoryLoad++;
                continue;
            }
            boolean editableTextFrame = ctx.resolvedData.isEditableTextFrame(tf.id());
            boolean tableAnchorOnlyFrame = TableFrameOwnershipPolicy.isTableAnchorOnlyFrame(tf);
            if (tf.isInline() && !tableAnchorOnlyFrame) {
                report.textFramesSkippedBeforeStoryLoad++;
                continue;
            }
            if (!tf.isInline() && !editableTextFrame && !tableAnchorOnlyFrame) {
                report.textFramesSkippedBeforeStoryLoad++;
                continue;
            }
            report.storyLoadAttempts++;
            long storyLoadStart = System.nanoTime();
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
            report.storyLoadNanos += System.nanoTime() - storyLoadStart;
            if (idmlStory == null || !idmlStory.hasTables()) continue;
            report.storyWithTables++;
            if (allTablesConsumedByAnchoredPlan(ctx, idmlStory)) continue;
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
            if (allStoryTablesProcessedOrAnchored(ctx, allTables, processedTableIds)) {
                report.storyTablesAlreadyProcessed++;
                continue;
            }
            long tableSetupStart = System.nanoTime();
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
            report.storyTableSetupNanos += System.nanoTime() - tableSetupStart;

            long tableYOffset = 0;
            Map<Integer, Long> markerTableYOffsetByPage = new HashMap<>();
            MarkerTableFlow markerTableFlow = markerTableFlowForFrame(ctx, idmlStory, tf);
            boolean markerOnlyTableFlow = markerTableFlow.active;
            boolean markerTableSequence = topLevelStoryTables(idmlStory).size() > 1;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable : allTables) {
                if (ctx.isAnchoredTableSource(idmlTable.selfId())) continue;
                if (isNestedTableInSameStory(idmlStory, idmlTable)) continue;
                if (!markerTableFlow.owns(idmlStory, idmlTable)) continue;
                // 같은 Table이 TF 연결 체인에서 중복 배치되는 것을 방지
                if (!processedTableIds.add(idmlTable.selfId())) continue;

                int tablePageIdx = tablePlacementPageIndex(ctx, idmlTable, pageIdx, markerOnlyTableFlow);
                if (tablePageIdx < 0 || tablePageIdx >= sections.size()) continue;
                long markerTableYOffset = markerTableSequence
                        ? markerTableYOffsetByPage.getOrDefault(tablePageIdx, 0L)
                        : 0L;

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
                    if (markerTableSequence) {
                        thisY += markerTableYOffset;
                    } else {
                        // InDesign DOM already gave the table's page-relative bounds.
                        // Prefer that over story-flow estimates so tables stay below their preceding text.
                    }
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

                // 분기 1: 레거시 모드 (preferCellLevel=false) — 표 단위 게이트 + planned 표 전체 PNG
                if (!policy.preferCellLevel) {
                    if (idmlTableHasInlineWithShortText(idmlTable, policy.maxTextLengthWithInline)) {
                        ASTFigure fig = renderTableAsImage(ctx, idmlTable, tf, thisX, thisY, tablePageIdx);
                        if (fig != null) {
                            section.addBlock(fig);
                            report.wholeTablePngRendered++;
                            if (markerTableSequence) {
                                addMarkerTableYOffset(markerTableYOffsetByPage,
                                        tablePageIdx, tableHeightHwpunits(idmlTable, null));
                            }
                            continue;
                        }
                        report.pngMissingFallback++;
                        System.err.println("[Phase 4] (legacy) 인라인 감지했지만 PNG 없음 → ASTTable, tf="
                                + tf.id() + " — 배지 중복 위험");
                    }
                }

                // 분기 2 (기본): ASTTable로 변환
                long buildStart = System.nanoTime();
                ASTTable astTable = buildPreparedAstTable(ctx, idmlTable, thisX, thisY, tf.zOrder());
                report.buildAstTableNanos += System.nanoTime() - buildStart;
                if (markerTableSequence && markerTableYOffset > 0) {
                    astTable.y(thisY);
                }
                if (ctx.isAnchoredNestedTableSource(idmlTable.selfId())) {
                    astTable.flowWithText(true);
                }
                if (GroupedFlowStackPolicy.isFlowStackTableTextFrame(ctx, tf)
                        || hasFlowStackTitleAboveTableBounds(ctx, tf, resolvedTableBounds, thisX, thisY, astTable)) {
                    astTable.anchoredFlowWithText(true);
                }
                applySourcePageTablePlacementPolicy(ctx, tf, idmlTable, astTable);

                long splitStart = System.nanoTime();
                List<TablePlacement> tablePlacements = splitSpreadWideTable(
                        ctx, astTable, tablePageIdx, resolvedTableBounds, sections.size());
                report.splitTableNanos += System.nanoTime() - splitStart;
                for (TablePlacement placement : tablePlacements) {
                    if (placement.pageIdx < 0 || placement.pageIdx >= sections.size()) continue;
                    sections.get(placement.pageIdx).addBlock(placement.table);
                }
                long suppressStart = System.nanoTime();
                suppressRenderedVisualsOwnedByTable(ctx, tf, astTable);
                report.suppressVisualNanos += System.nanoTime() - suppressStart;

                // 분기 2a: cell-level 모드 — 트리거 셀의 인라인 객체를 floating으로 추출
                int extractedInThisTable = 0;
                int triggerCells = 0;
                if (policy.preferCellLevel) {
                    for (TablePlacement placement : tablePlacements) {
                        if (placement.pageIdx < 0 || placement.pageIdx >= sections.size()) continue;
                        long extractStart = System.nanoTime();
                        int[] result = extractInlinesFromCells(
                                placement.table, sections.get(placement.pageIdx), policy, ctx);
                        report.extractCellInlineNanos += System.nanoTime() - extractStart;
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
                if (markerTableSequence) {
                    addMarkerTableYOffset(markerTableYOffsetByPage,
                            tablePageIdx, tableHeightHwpunits(idmlTable, astTable));
                }
            }
        }

        report.duplicateInlineTablesRemoved =
                removeInlineTablesBySourceId(sections, pageLevelTableSourceIds);
        report.nestedTableBlocksAbsorbed = absorbNestedTableBlocksIntoEmptyCells(ctx, sections);
        recordTiming(report);
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
            if (plan == null || plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (!isTableOnlyStylePlan(plan)) continue;
            if (isInlineStoryFlowTableStylePlan(plan)) {
                report.tableOnlyPlansSkippedInlineFlow++;
                continue;
            }

            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(plan.domId));
            if (tf == null || tf.storyId() == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(tf.storyId());
            if (!TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, idmlStory)) continue;
            if (isConsumedByAnchoredTablePlan(ctx, plan, tf, idmlStory)) {
                report.tableOnlyPlansSkippedAnchored++;
                continue;
            }

            int pageIdx = ctx.toSectionIndex.applyAsInt(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;
            long[] origin = tableOwnerOrigin(ctx, tf, pageIdx);
            long hx = origin[0];
            long hy = origin[1];
            Map<Integer, Long> markerTableYOffsetByPage = new HashMap<>();
            MarkerTableFlow markerTableFlow = markerTableFlowForFrame(ctx, idmlStory, tf);
            boolean markerOnlyTableFlow = markerTableFlow.active;
            boolean markerTableSequence = topLevelStoryTables(idmlStory).size() > 1;

            for (IDMLTable idmlTable : idmlStory.tables()) {
                if (idmlTable == null || idmlTable.selfId() == null) continue;
                if (isNestedTableInSameStory(idmlStory, idmlTable)) {
                    continue;
                }
                if (ctx.isAnchoredWrapperTableSource(idmlTable.selfId())) {
                    continue;
                }
                if (!markerTableFlow.owns(idmlStory, idmlTable)) continue;
                if (!processedTableIds.add(idmlTable.selfId())) continue;

                int tablePageIdx = tablePlacementPageIndex(ctx, idmlTable, pageIdx, markerOnlyTableFlow);
                if (tablePageIdx < 0 || tablePageIdx >= sections.size()) continue;
                long markerTableYOffset = markerTableSequence
                        ? markerTableYOffsetByPage.getOrDefault(tablePageIdx, 0L)
                        : 0L;

                long thisX = hx;
                long thisY = hy;
                double[] resolvedTableBounds = ctx.resolvedData.getTablePlacementBounds(idmlTable.selfId());
                if (resolvedTableBounds != null && resolvedTableBounds.length >= 4) {
                    double scale = ctx.resolvedData.scaleFactor();
                    thisX = CoordinateConverter.pointsToHwpunits(resolvedTableBounds[1] * scale);
                    thisY = CoordinateConverter.pointsToHwpunits(resolvedTableBounds[0] * scale);
                    if (markerTableSequence) {
                        thisY += markerTableYOffset;
                    }
                }

                long buildStart = System.nanoTime();
                ASTTable astTable = buildPreparedAstTable(ctx, idmlTable, thisX, thisY, tf.zOrder());
                report.buildAstTableNanos += System.nanoTime() - buildStart;
                if (markerTableSequence && markerTableYOffset > 0) {
                    astTable.y(thisY);
                }
                if (ctx.isAnchoredNestedTableSource(idmlTable.selfId())) {
                    astTable.flowWithText(true);
                }
                if (GroupedFlowStackPolicy.isFlowStackTableTextFrame(ctx, tf)
                        || hasFlowStackTitleAboveTableBounds(ctx, tf, resolvedTableBounds, thisX, thisY, astTable)) {
                    astTable.anchoredFlowWithText(true);
                }
                applySourcePageTablePlacementPolicy(ctx, tf, idmlTable, astTable);

                long splitStart = System.nanoTime();
                List<TablePlacement> tablePlacements = splitSpreadWideTable(
                        ctx, astTable, tablePageIdx, resolvedTableBounds, sections.size());
                report.splitTableNanos += System.nanoTime() - splitStart;
                for (TablePlacement placement : tablePlacements) {
                    if (placement.pageIdx < 0 || placement.pageIdx >= sections.size()) continue;
                    sections.get(placement.pageIdx).addBlock(placement.table);
                }
                long suppressStart = System.nanoTime();
                suppressRenderedVisualsOwnedByTable(ctx, tf, astTable);
                report.suppressVisualNanos += System.nanoTime() - suppressStart;
                report.asTableCleanCount++;
                report.tableOnlyPlansPlaced++;
                report.total++;
                if (markerTableSequence) {
                    addMarkerTableYOffset(markerTableYOffsetByPage,
                            tablePageIdx, tableHeightHwpunits(idmlTable, astTable));
                }
            }
        }
    }

    private static boolean isInlineStoryFlowTableStylePlan(ObjectPlan plan) {
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.coordinateSpace == CoordinateSpace.STORY_FLOW
                && (plan.materialization == Materialization.HWPX_TABLE_STYLE
                || plan.visualAction == VisualAction.PLACE_TABLE_STYLE);
    }

    private static boolean isConsumedByAnchoredTablePlan(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            ResolvedTextFrame tf,
            IDMLStory idmlStory) {
        if (ctx == null) return false;
        int tfDomId = tf != null ? parseId(tf.id()) : -1;
        if (tfDomId < 0 && plan != null) tfDomId = plan.domId;
        if (tfDomId >= 0) {
            for (AnchoredTablePlan anchoredPlan : ctx.anchoredTablePlans()) {
                if (anchoredPlan != null && anchoredPlan.anchoredTextFrameDomId == tfDomId) {
                    return true;
                }
            }
        }
        if (idmlStory == null || idmlStory.tables() == null) return false;
        for (IDMLTable table : idmlStory.tables()) {
            if (table != null && ctx.isAnchoredNestedTableSource(table.selfId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNestedTableInSameStory(IDMLStory story, IDMLTable table) {
        if (story == null || table == null || table.selfId() == null) return false;
        String tableId = table.selfId();
        List<IDMLTable> tables = story.tables();
        if (tables == null || tables.size() < 2) return false;
        for (IDMLTable other : tables) {
            if (other == null || other == table || other.selfId() == null) continue;
            String parentId = other.selfId();
            if (tableId.length() > parentId.length()
                    && tableId.startsWith(parentId)
                    && tableId.charAt(parentId.length()) == 'i') {
                return true;
            }
        }
        return false;
    }

    private static void applySourcePageTablePlacementPolicy(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            IDMLTable idmlTable,
            ASTTable astTable) {
        if (!isSourcePageLocalTable(ctx, tf, idmlTable, astTable)) return;
        astTable.fixedOuterBounds(true);
        astTable.flowWithText(false);
        astTable.anchoredFlowWithText(false);
        if (ctx != null && ctx.debugAst) {
            astTable.debugOrNew().note("source-page-local table: fixed outer bounds, no row page split");
        }
    }

    private static boolean isSourcePageLocalTable(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            IDMLTable idmlTable,
            ASTTable astTable) {
        if (ctx == null || tf == null || idmlTable == null || astTable == null) return false;
        if (tf.sourceHidden() || tf.pageIndex() < 0) return false;
        if (idmlTable.selfId() != null && ctx.isAnchoredTableSource(idmlTable.selfId())) return false;
        return !tf.isInline() || TableFrameOwnershipPolicy.isTableAnchorOnlyFrame(tf);
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
            int fallbackPageIdx,
            boolean markerOnlyTableFlow) {
        if (ctx == null || ctx.resolvedData == null || idmlTable == null) return fallbackPageIdx;
        if (markerOnlyTableFlow) {
            return fallbackPageIdx;
        }
        Integer placementPageIndex = ctx.resolvedData.getTablePlacementPageIndex(idmlTable.selfId());
        if (placementPageIndex == null) return fallbackPageIdx;
        return ctx.toSectionIndex.applyAsInt(placementPageIndex);
    }

    private static final class MarkerTableFlow {
        static final MarkerTableFlow INACTIVE = new MarkerTableFlow(false, 0, 0);

        final boolean active;
        final int startOrdinal;
        final int endOrdinal;

        MarkerTableFlow(boolean active, int startOrdinal, int endOrdinal) {
            this.active = active;
            this.startOrdinal = startOrdinal;
            this.endOrdinal = endOrdinal;
        }

        boolean owns(IDMLStory story, IDMLTable table) {
            if (!active) return true;
            int ordinal = topLevelStoryTableIndex(story, table);
            return ordinal >= startOrdinal && ordinal < endOrdinal;
        }
    }

    private static MarkerTableFlow markerTableFlowForFrame(
            ResolvedBuildContext ctx,
            IDMLStory idmlStory,
            ResolvedTextFrame ownerFrame) {
        if (ctx == null || ctx.resolvedData == null || idmlStory == null || ownerFrame == null) {
            return MarkerTableFlow.INACTIVE;
        }
        List<IDMLTable> sourceTables = topLevelStoryTables(idmlStory);
        int ownerMarkerCount = countTableMarkers(ownerFrame.frameVisibleText());
        if (sourceTables.size() < 2 || ownerMarkerCount <= 0) {
            return MarkerTableFlow.INACTIVE;
        }
        List<ResolvedTextFrame> frames = markerFlowFramesForStory(ctx, ownerFrame.storyId());
        if (frames.isEmpty()) {
            return MarkerTableFlow.INACTIVE;
        }
        int totalMarkers = 0;
        for (ResolvedTextFrame frame : frames) {
            totalMarkers += countTableMarkers(frame.frameVisibleText());
        }
        if (totalMarkers < sourceTables.size()) {
            return MarkerTableFlow.INACTIVE;
        }

        int startOrdinal = 0;
        for (ResolvedTextFrame frame : frames) {
            int markerCount = countTableMarkers(frame.frameVisibleText());
            if (sameTextFrame(frame, ownerFrame)) {
                return new MarkerTableFlow(true, startOrdinal, startOrdinal + markerCount);
            }
            startOrdinal += markerCount;
        }
        return MarkerTableFlow.INACTIVE;
    }

    private static List<IDMLTable> topLevelStoryTables(IDMLStory story) {
        List<IDMLTable> result = new ArrayList<>();
        if (story == null || story.tables() == null) return result;
        for (IDMLTable table : story.tables()) {
            if (table == null || isNestedTableInSameStory(story, table)) continue;
            result.add(table);
        }
        return result;
    }

    private static int topLevelStoryTableIndex(IDMLStory story, IDMLTable target) {
        if (story == null || target == null || target.selfId() == null || story.tables() == null) return -1;
        int index = 0;
        for (IDMLTable table : story.tables()) {
            if (table == null || isNestedTableInSameStory(story, table)) continue;
            if (target.selfId().equals(table.selfId())) return index;
            index++;
        }
        return -1;
    }

    private static List<ResolvedTextFrame> markerFlowFramesForStory(
            ResolvedBuildContext ctx,
            String storyId) {
        List<ResolvedTextFrame> markerFrames = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || storyId == null) return markerFrames;
        for (ResolvedTextFrame frame : ctx.resolvedData.textFrames()) {
            if (frame == null || !storyId.equals(frame.storyId())) continue;
            if (frame != null && countTableMarkers(frame.frameVisibleText()) > 0) {
                markerFrames.add(frame);
            }
        }
        markerFrames.sort(new Comparator<ResolvedTextFrame>() {
            @Override
            public int compare(ResolvedTextFrame a, ResolvedTextFrame b) {
                int byPara = Integer.compare(a.paragraphStart(), b.paragraphStart());
                if (byPara != 0) return byPara;
                int byPage = Integer.compare(a.pageIndex(), b.pageIndex());
                if (byPage != 0) return byPage;
                String aid = a.id();
                String bid = b.id();
                if (aid == null && bid == null) return 0;
                if (aid == null) return -1;
                if (bid == null) return 1;
                return aid.compareTo(bid);
            }
        });
        return markerFrames;
    }

    private static boolean sameTextFrame(ResolvedTextFrame a, ResolvedTextFrame b) {
        if (a == b) return true;
        if (a == null || b == null || a.id() == null || b.id() == null) return false;
        return a.id().equals(b.id());
    }

    private static int countTableMarkers(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0016' || c == '\uFFFC') count++;
        }
        return count;
    }

    private static long tableHeightHwpunits(IDMLTable sourceTable, ASTTable astTable) {
        if (astTable != null && astTable.height() > 0) {
            return astTable.height();
        }
        if (sourceTable == null) return 0;
        double height = sourceTable.totalHeight();
        height += Math.max(0, sourceTable.spaceBefore());
        height += Math.max(0, sourceTable.spaceAfter());
        return CoordinateConverter.pointsToHwpunits(height);
    }

    private static void addMarkerTableYOffset(Map<Integer, Long> offsets, int pageIdx, long height) {
        if (offsets == null || pageIdx < 0 || height <= 0) return;
        offsets.put(pageIdx, offsets.getOrDefault(pageIdx, 0L) + height);
    }

    private static boolean allStoryTablesProcessedOrAnchored(
            ResolvedBuildContext ctx,
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> tables,
            Set<String> processedTableIds) {
        if (tables == null || tables.isEmpty()) return false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table : tables) {
            if (table == null || table.selfId() == null) return false;
            if (ctx != null && ctx.isAnchoredTableSource(table.selfId())) continue;
            if (processedTableIds == null || !processedTableIds.contains(table.selfId())) return false;
        }
        return true;
    }

    private static boolean allTablesConsumedByAnchoredPlan(ResolvedBuildContext ctx, IDMLStory idmlStory) {
        if (ctx == null || idmlStory == null || idmlStory.tables() == null || idmlStory.tables().isEmpty()) return false;
        for (IDMLTable table : idmlStory.tables()) {
            if (table == null || !ctx.isAnchoredTableSource(table.selfId())) return false;
        }
        return true;
    }

    private static void recordTiming(Phase4Report report) {
        if (report == null) return;
        String prefix = "stage2.textBuilder.tableBuilder.";
        ConversionTiming.metric(prefix + "textFramesScanned", report.textFramesScanned);
        ConversionTiming.metric(prefix + "textFramesSkippedBeforeStoryLoad", report.textFramesSkippedBeforeStoryLoad);
        ConversionTiming.metric(prefix + "storyLoadAttempts", report.storyLoadAttempts);
        ConversionTiming.metric(prefix + "storyWithTables", report.storyWithTables);
        ConversionTiming.metric(prefix + "storyTablesAlreadyProcessed", report.storyTablesAlreadyProcessed);
        ConversionTiming.metric(prefix + "tablesPlaced", report.total);
        ConversionTiming.metric(prefix + "tableOnlyPlansPlaced", report.tableOnlyPlansPlaced);
        ConversionTiming.metric(prefix + "tableOnlyPlansSkippedAnchored", report.tableOnlyPlansSkippedAnchored);
        ConversionTiming.metric(prefix + "tableOnlyPlansSkippedInlineFlow", report.tableOnlyPlansSkippedInlineFlow);
        ConversionTiming.metric(prefix + "nestedTableBlocksAbsorbed", report.nestedTableBlocksAbsorbed);
        ConversionTiming.metric(prefix + "tableOnlyPlansMs", millis(report.tableOnlyPlansNanos));
        ConversionTiming.metric(prefix + "storyLoadMs", millis(report.storyLoadNanos));
        ConversionTiming.metric(prefix + "storyTableSetupMs", millis(report.storyTableSetupNanos));
        ConversionTiming.metric(prefix + "buildAstTableMs", millis(report.buildAstTableNanos));
        ConversionTiming.metric(prefix + "splitTableMs", millis(report.splitTableNanos));
        ConversionTiming.metric(prefix + "suppressVisualMs", millis(report.suppressVisualNanos));
        ConversionTiming.metric(prefix + "extractCellInlineMs", millis(report.extractCellInlineNanos));
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10000.0) / 100.0;
    }

    public static ASTTable buildPreparedAstTable(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            long x,
            long y,
            int zOrder) {
        return buildPreparedAstTable(ctx, idmlTable, x, y, zOrder, false);
    }

    private static ASTTable buildPreparedAstTable(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            long x,
            long y,
            int zOrder,
            boolean preserveNestedTableStyleSlot) {
        final ResolvedBuildContext cellCtx = ctx;
        ASTTable astTable = ASTTableConverter.convertTableSimple(
                idmlTable, x, y, zOrder,
                null, null, null,
                ctx != null ? ctx.resolvedData : null,
                ctx != null ? ctx.styleResolver : null,
                cellCtx == null ? null : ((table, idmlCell) -> StoryFlowAssembler.buildCellFlow(cellCtx, table, idmlCell)));
        traceTableCells(ctx, "TableBuilder.afterConvertTableSimple", idmlTable, astTable);
        restoreNestedTextFrameTables(ctx, astTable, idmlTable);
        inlineNestedTextFrameParagraphsInCells(ctx, astTable);
        applyTableCellLineWrapHints(astTable);
        applyOverflowingTableOnlyVisibleRowBudget(ctx, idmlTable, astTable);
        traceTableCells(ctx, "TableBuilder.afterNestedContentRestore", idmlTable, astTable);

        ASTTable result = astTable;
        if (shouldPreserveTableStyleSlot(ctx, idmlTable, preserveNestedTableStyleSlot)) {
            applyDeclaredTableStyleSources(ctx, idmlTable, result);
            ResolvedTextFrame owner = anchoredNestedTableTextFrame(ctx, idmlTable);
            absorbTextFrameOutlineIntoTable(ctx, owner, result);
            absorbInlineTextShellStylesIntoTableCells(ctx, result);
            suppressRenderedVisualsOwnedByAnchoredNestedTable(ctx, owner, idmlTable, result);
        } else {
            stripTableCellDecoration(result);
        }
        applyPlannedTableCarrierCellVisuals(ctx, idmlTable, result);
        traceTableCells(ctx, "TableBuilder.afterStyleSlotPolicy", idmlTable, result);
        return result;
    }

    private static void applyPlannedTableCarrierCellVisuals(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || idmlTable == null || table == null) return;
        String carrierParentId = tableCarrierParentId(ctx, idmlTable);
        if (carrierParentId == null || carrierParentId.isEmpty()) return;
        List<RenderedGroup> rendered = ctx.resolvedData.allRenderedFloatingItems();
        if (rendered == null || rendered.isEmpty()) return;
        if (ctx.ownershipPlans == null || ctx.ownershipPlans.isEmpty()) return;

        int applied = 0;
        Set<String> seenPlans = new HashSet<>();
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!isPlannedTableCarrierCellVisual(plan)) continue;
            if (!planSourcesBelongToCarrier(ctx, plan, carrierParentId)) continue;
            String seenKey = plan.objectPlanId != null ? plan.objectPlanId : plan.candidateId;
            if (seenKey != null && !seenPlans.add(seenKey)) continue;
            RenderedGroup rg = renderedGroupForPlan(rendered, plan);
            if (rg == null) continue;
            double[] cellBounds = normalizeSourceBoundsForTable(ctx, table,
                    validBounds(plan.bounds) ? plan.bounds : rg.bounds());
            ASTTableCell cell = targetCellForRenderedTableVisual(table, cellBounds);
            if (cell == null) continue;
            ASTInlineObject object = tableCellVisualInlineObject(ctx, plan, rg, cellBounds);
            if (object == null) continue;
            addVisualObjectToCell(cell, object);
            markTableCellVisualHandled(ctx, plan, rg);
            applied++;
        }
        if (applied > 0 && ctx.debugAst) {
            table.debugOrNew().note("planned table carrier cell visuals applied: " + applied);
        }
    }

    private static RenderedGroup renderedGroupForPlan(List<RenderedGroup> rendered, ObjectPlan plan) {
        if (rendered == null || plan == null) return null;
        RenderedGroup fallback = null;
        for (RenderedGroup rg : rendered) {
            if (rg == null) continue;
            if (plan.renderId != null && rg.id() == plan.renderId) {
                if (sameText(rg.file(), plan.file)) return rg;
                if (fallback == null) fallback = rg;
            }
            if (plan.candidateId != null && plan.candidateId.equals(rg.candidateId())) {
                if (sameText(rg.file(), plan.file)) return rg;
                if (fallback == null) fallback = rg;
            }
            if (plan.file != null && !plan.file.isEmpty() && plan.file.equals(rg.file())
                    && sameIntSet(plan.exportSourceObjectIds, rg.exportSourceObjectIds())) {
                return rg;
            }
        }
        return fallback;
    }

    private static boolean sameText(String a, String b) {
        if (a == null || a.isEmpty()) return b == null || b.isEmpty();
        return a.equals(b);
    }

    private static boolean sameIntSet(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        Set<Integer> seen = new HashSet<>();
        for (int v : a) seen.add(v);
        for (int v : b) {
            if (!seen.remove(v)) return false;
        }
        return seen.isEmpty();
    }

    private static boolean isPlannedTableCarrierCellVisual(ObjectPlan plan) {
        if (plan == null || !plan.hasVisibleVisual()) return false;
        if (!"pass.decoration_groups".equals(plan.planPassId)) return false;
        if (plan.slotRole == null) return false;
        return "table_cell_content_visual_slot".equals(plan.slotRole)
                || "table_cell_shell_slot".equals(plan.slotRole);
    }

    private static String tableCarrierParentId(ResolvedBuildContext ctx, IDMLTable idmlTable) {
        String storyId = tableStoryDomId(idmlTable);
        if (storyId == null || storyId.isEmpty()) return null;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || !storyId.equals(tf.storyId())) continue;
            ResolvedPageItem item = ctx.resolvedData.getPageItem(tf.id());
            if (item != null && item.parentId() != null && !item.parentId().isEmpty()) {
                return item.parentId();
            }
        }
        return null;
    }

    private static String tableStoryDomId(IDMLTable idmlTable) {
        if (idmlTable == null || idmlTable.selfId() == null) return null;
        String selfId = idmlTable.selfId();
        int i = selfId.indexOf('i');
        if (!selfId.startsWith("u") || i <= 1) return null;
        try {
            return String.valueOf(Integer.parseInt(selfId.substring(1, i), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean planSourcesBelongToCarrier(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            String carrierParentId) {
        if (ctx == null || ctx.resolvedData == null || plan == null || carrierParentId == null) return false;
        int[] ids = plan.exportSourceObjectIds != null && plan.exportSourceObjectIds.length > 0
                ? plan.exportSourceObjectIds
                : plan.sourceObjectIds;
        for (int id : ids) {
            if (sourceHasAncestor(ctx, id, carrierParentId)) return true;
        }
        return false;
    }

    private static boolean sourceHasAncestor(
            ResolvedBuildContext ctx,
            int sourceId,
            String ancestorId) {
        if (sourceId < 0 || ancestorId == null || ancestorId.isEmpty()) return false;
        Set<String> visited = new HashSet<>();
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        while (item != null) {
            String parentId = item.parentId();
            if (parentId == null || parentId.isEmpty()) return false;
            if (ancestorId.equals(parentId)) return true;
            if (!visited.add(parentId)) return false;
            item = ctx.resolvedData.getPageItem(parentId);
        }
        return false;
    }

    private static ASTTableCell targetCellForRenderedTableVisual(
            ASTTable table,
            double[] bounds) {
        ASTTableCell centered = cellContainingSourceCenter(table, bounds);
        if (centered != null) return centered;
        List<ASTTableCell> covered = cellsCoveredBySource(table, bounds);
        return covered.isEmpty() ? null : covered.get(0);
    }

    private static ASTTableCell cellContainingSourceCenter(ASTTable table, double[] sourceBounds) {
        if (!validBounds(sourceBounds)) return null;
        double y = (sourceBounds[0] + sourceBounds[2]) / 2.0;
        double x = (sourceBounds[1] + sourceBounds[3]) / 2.0;
        ASTTableCell best = null;
        double bestArea = Double.MAX_VALUE;
        for (CellPlacement placement : cellPlacements(table)) {
            if (placement == null || placement.cell == null) continue;
            if (y < placement.top - 0.75 || y > placement.bottom + 0.75
                    || x < placement.left - 0.75 || x > placement.right + 0.75) {
                continue;
            }
            double area = Math.max(0.0, placement.bottom - placement.top)
                    * Math.max(0.0, placement.right - placement.left);
            if (best == null || area < bestArea) {
                best = placement.cell;
                bestArea = area;
            }
        }
        return best;
    }

    private static ASTInlineObject tableCellVisualInlineObject(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup rg,
            double[] bounds) {
        if (ctx == null || rg == null || rg.file() == null || rg.file().isEmpty()) return null;
        File file = new File(ctx.resolvedData.basePath(), rg.file());
        if (!file.isFile()) return null;
        byte[] data;
        BufferedImage image = null;
        try {
            data = Files.readAllBytes(file.toPath());
            image = ImageIO.read(file);
        } catch (Exception e) {
            return null;
        }
        if (!validBounds(bounds)) return null;
        long width = CoordinateConverter.pointsToHwpunits(Math.max(0.1, bounds[3] - bounds[1]));
        long height = CoordinateConverter.pointsToHwpunits(Math.max(0.1, bounds[2] - bounds[0]));
        if (width <= 0 || height <= 0) return null;
        ASTInlineObject object = new ASTInlineObject();
        object.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        object.sourceId(plan != null && plan.objectPlanId != null ? plan.objectPlanId : String.valueOf(rg.id()));
        object.imageFormat("png");
        object.imageData(data);
        object.imagePath(rg.file());
        object.bundlePath(rg.file());
        object.width(width);
        object.height(height);
        object.resolvedWidth(width);
        object.resolvedHeight(height);
        object.resolvedPageX(CoordinateConverter.pointsToHwpunits(bounds[1]));
        object.resolvedPageY(CoordinateConverter.pointsToHwpunits(bounds[0]));
        object.keepInline(true);
        object.affectsLineSpacing(true);
        object.plannedZOrder(plan != null ? plan.zOrder : rg.zOrder());
        object.plannedVisualLayer(plan != null && plan.visualLayer != null
                ? plan.visualLayer.name()
                : rg.visualLayer());
        if (image != null) {
            object.pixelWidth(image.getWidth());
            object.pixelHeight(image.getHeight());
        }
        return object;
    }

    private static void addVisualObjectToCell(ASTTableCell cell, ASTInlineObject object) {
        if (cell == null || object == null) return;
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.alignment("center");
        paragraph.items().add(object);
        if (cell.paragraphs() == null || cell.paragraphs().isEmpty()) {
            cell.addParagraph(paragraph);
        } else {
            cell.paragraphs().add(paragraph);
        }
    }

    private static void markTableCellVisualHandled(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            RenderedGroup rg) {
        if (ctx == null) return;
        if (rg != null) ctx.markRenderedVisualHandled(rg.id());
        markIdsHandled(ctx, plan != null ? plan.sourceObjectIds : null);
        markIdsHandled(ctx, plan != null ? plan.visualSourceObjectIds : null);
        markIdsHandled(ctx, plan != null ? plan.exportSourceObjectIds : null);
        markIdsHandled(ctx, rg != null ? rg.sourceObjectIds() : null);
        markIdsHandled(ctx, rg != null ? rg.exportSourceObjectIds() : null);
    }

    private static void markIdsHandled(ResolvedBuildContext ctx, int[] ids) {
        if (ctx == null || ids == null) return;
        for (int id : ids) {
            if (id >= 0) ctx.markRenderedVisualHandled(id);
        }
    }

    private static void traceTableCells(
            ResolvedBuildContext ctx,
            String producer,
            IDMLTable idmlTable,
            ASTTable table) {
        if (ctx == null || ctx.astProductionLines == null || table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                JsonObject out = new JsonObject();
                out.addProperty("producer", producer);
                out.addProperty("blockType", "TABLE_CELL");
                out.addProperty("tableSourceId", table.sourceId());
                out.addProperty("idmlTableId", idmlTable != null ? idmlTable.selfId() : null);
                out.addProperty("rowIndex", cell.rowIndex());
                out.addProperty("columnIndex", cell.columnIndex());
                out.addProperty("rowSpan", cell.rowSpan());
                out.addProperty("columnSpan", cell.columnSpan());
                out.addProperty("width", cell.width());
                out.addProperty("height", cell.height());
                out.addProperty("fillColor", cell.fillColor());
                out.addProperty("squeezeLineWrap", cell.squeezeLineWrap());
                out.addProperty("hasTopBorder", cell.topBorder() != null);
                out.addProperty("hasBottomBorder", cell.bottomBorder() != null);
                out.addProperty("hasLeftBorder", cell.leftBorder() != null);
                out.addProperty("hasRightBorder", cell.rightBorder() != null);
                out.add("topBorder", traceCellBorder(cell.topBorder()));
                out.add("bottomBorder", traceCellBorder(cell.bottomBorder()));
                out.add("leftBorder", traceCellBorder(cell.leftBorder()));
                out.add("rightBorder", traceCellBorder(cell.rightBorder()));
                JsonArray inlineObjects = new JsonArray();
                collectCellInlineObjects(cell, inlineObjects);
                out.add("inlineObjects", inlineObjects);
                out.addProperty("preview", previewCell(cell));
                ctx.astProductionLines.add(out.toString());
            }
        }
    }

    private static JsonObject traceCellBorder(ASTTableCell.CellBorder border) {
        JsonObject out = new JsonObject();
        if (border == null) return out;
        out.addProperty("color", border.color());
        out.addProperty("weight", border.weight());
        out.addProperty("strokeType", border.strokeType());
        out.addProperty("tint", border.tint());
        return out;
    }

    private static void collectCellInlineObjects(ASTTableCell cell, JsonArray out) {
        if (cell == null || cell.paragraphs() == null) return;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject obj = (ASTInlineObject) item;
                JsonObject row = new JsonObject();
                row.addProperty("sourceId", obj.sourceId());
                row.addProperty("kind", obj.kind() != null ? obj.kind().name() : null);
                row.addProperty("width", obj.width());
                row.addProperty("height", obj.height());
                row.addProperty("hasImageData", obj.imageData() != null);
                row.addProperty("imagePath", obj.imagePath());
                row.addProperty("bundlePath", obj.bundlePath());
                row.addProperty("keepInline", obj.keepInline());
                row.addProperty("layoutOnlyInlineSlot", obj.layoutOnlyInlineSlot());
                row.addProperty("plannedVisualLayer", obj.plannedVisualLayer());
                row.addProperty("plannedZOrder", obj.plannedZOrder());
                row.addProperty("paragraphPreview", previewParagraphs(obj.paragraphs()));
                out.add(row);
            }
        }
    }

    private static String previewCell(ASTTableCell cell) {
        return cell == null ? "" : previewParagraphs(cell.paragraphs());
    }

    private static String previewParagraphs(List<ASTParagraph> paragraphs) {
        if (paragraphs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph paragraph : paragraphs) {
            appendParagraphPreview(sb, paragraph);
            sb.append(' ');
            if (sb.length() > 260) break;
        }
        String preview = sb.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return preview.length() > 240 ? preview.substring(0, 240) : preview;
    }

    private static void appendParagraphPreview(StringBuilder sb, ASTParagraph paragraph) {
        if (paragraph == null) return;
        if (paragraph.items() != null) {
            for (ASTInlineItem item : paragraph.items()) {
                appendInlinePreview(sb, item);
                if (sb.length() > 260) return;
            }
        }
        if (paragraph.inlineTable() != null) {
            sb.append("[inline-table]");
        }
    }

    private static void appendInlinePreview(StringBuilder sb, ASTInlineItem item) {
        if (item == null) return;
        if (item instanceof ASTTextRun) {
            String text = ((ASTTextRun) item).text();
            if (text != null) sb.append(text);
        } else if (item instanceof ASTEquation) {
            sb.append("[eq:").append(safePreview(((ASTEquation) item).hwpScript())).append(']');
        } else if (item instanceof ASTInlineObject) {
            ASTInlineObject obj = (ASTInlineObject) item;
            sb.append("[inline:").append(safePreview(obj.sourceId())).append(']');
            String nested = previewParagraphs(obj.paragraphs());
            if (!nested.isEmpty()) sb.append(nested);
        } else if (item instanceof ASTBreak) {
            sb.append(' ');
        }
    }

    private static String safePreview(String text) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    private static boolean shouldPreserveTableStyleSlot(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            boolean preserveNestedTableStyleSlot) {
        if (preserveNestedTableStyleSlot) return true;
        if (idmlTable == null) return false;
        return ctx != null
                && idmlTable.selfId() != null
                && (ctx.isAnchoredNestedTableSource(idmlTable.selfId())
                || ctx.isTableStyleOwnedByObjectPlan(idmlTable.selfId())
                || hasDeclaredTableStylePlanForTable(ctx, idmlTable));
    }

    private static boolean hasDeclaredTableStylePlanForTable(ResolvedBuildContext ctx, IDMLTable idmlTable) {
        if (ctx == null || idmlTable == null || idmlTable.selfId() == null) return false;
        int tableId = parseFlexibleId(idmlTable.selfId());
        if (tableId < 0) return false;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectId(tableId)) {
            if (isDeclaredTableStylePlan(plan)) return true;
        }
        return false;
    }

    private static ResolvedTextFrame anchoredNestedTableTextFrame(ResolvedBuildContext ctx, IDMLTable idmlTable) {
        if (ctx == null || ctx.resolvedData == null || idmlTable == null || idmlTable.selfId() == null) return null;
        for (AnchoredTablePlan plan : ctx.anchoredTablePlans()) {
            if (plan == null || !idmlTable.selfId().equals(plan.nestedTableId)) continue;
            return ctx.resolvedData.getTextFrame(String.valueOf(plan.anchoredTextFrameDomId));
        }
        return null;
    }

    private static void applyOverflowingTableOnlyVisibleRowBudget(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            ASTTable table) {
        ResolvedTextFrame owner = tableOnlyStyleOwnerTextFrame(ctx, idmlTable);
        if (!shouldApplyVisibleLineBudget(owner)) return;
        ResolvedTable resolvedTable = ctx != null && ctx.resolvedData != null && idmlTable != null
                ? ctx.resolvedData.getTableByIdOrSourceId(idmlTable.selfId())
                : null;
        int visibleRows = fullyVisibleRowCount(resolvedTable);
        if (visibleRows < 0) return;
        int removed = clearTextInRowsAtOrBelow(table, visibleRows);
        if (removed > 0 && ctx != null && ctx.debugAst) {
            table.debugOrNew().note("overflowing table-only carrier visible row budget: ownerTf="
                    + owner.id() + " visibleRows=" + visibleRows + " removedParagraphs=" + removed);
        }
    }

    private static ResolvedTextFrame tableOnlyStyleOwnerTextFrame(ResolvedBuildContext ctx, IDMLTable idmlTable) {
        if (ctx == null || ctx.resolvedData == null || idmlTable == null || idmlTable.selfId() == null) return null;
        int tableId = parseFlexibleId(idmlTable.selfId());
        if (tableId < 0) return null;
        for (ObjectPlan plan : ctx.ownershipPlansForObjectId(tableId)) {
            if (!isDeclaredTableStylePlan(plan)) continue;
            if (!isTableOnlyStylePlan(plan)) continue;
            if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) continue;
            for (int textFrameId : plan.ownedTextFrameIds) {
                ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
                if (tf != null) return tf;
            }
        }
        return null;
    }

    private static boolean isTableOnlyStylePlan(ObjectPlan plan) {
        if (plan == null) return false;
        boolean tableStyle = plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                || plan.materialization == Materialization.HWPX_TABLE_STYLE;
        if (!tableStyle) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return true;
        if (plan.sourceObjectIds != null && containsSourceId(plan.sourceObjectIds, plan.domId)) return true;
        if (plan.kind != null && plan.kind.contains("table_only_text_frame")) return true;
        return plan.reason != null && plan.reason.contains("table_only_text_frame");
    }

    private static boolean shouldApplyVisibleLineBudget(ResolvedTextFrame owner) {
        return owner != null
                && owner.overflows()
                && isObjectControlOnly(owner.frameVisibleText())
                && composedLinesAreObjectControlOnly(owner);
    }

    private static boolean composedLinesAreObjectControlOnly(ResolvedTextFrame owner) {
        if (owner == null || owner.composedLines() == null || owner.composedLines().isEmpty()) return true;
        for (ResolvedTextFrame.ComposedLine line : owner.composedLines()) {
            if (line == null) continue;
            if (!isObjectControlOnly(line.text())) return false;
        }
        return true;
    }

    private static boolean isObjectControlOnly(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch) || ch == '\uFFFC' || ch == '\uFEFF') {
                continue;
            }
            return false;
        }
        return true;
    }

    private static int fullyVisibleRowCount(ResolvedTable table) {
        if (table == null || table.rowHeights() == null || table.bounds() == null) return -1;
        double[] bounds = table.bounds();
        if (bounds.length < 4 || bounds[2] <= bounds[0]) return -1;
        double visibleHeight = bounds[2] - bounds[0];
        double y = 0.0;
        int visibleRows = 0;
        for (double rowHeight : table.rowHeights()) {
            if (rowHeight <= 0) return -1;
            y += rowHeight;
            if (y <= visibleHeight + 0.35) {
                visibleRows++;
            } else {
                break;
            }
        }
        return visibleRows;
    }

    private static int clearTextInRowsAtOrBelow(ASTTable table, int firstHiddenRow) {
        if (table == null || table.rows() == null) return 0;
        int removed = 0;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null) continue;
                if (cell.rowIndex() >= firstHiddenRow) {
                    removed += cell.paragraphs().size();
                    cell.paragraphs().clear();
                }
            }
        }
        return removed;
    }

    private static void stripTableCellDecoration(ASTTable table) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                cell.fillColor(null);
                cell.topBorder(null);
                cell.bottomBorder(null);
                cell.leftBorder(null);
                cell.rightBorder(null);
                cell.topLeftDiagonalLine(false);
                cell.topRightDiagonalLine(false);
                cell.diagonalBorder(null);
            }
        }
    }

    private static void applyDeclaredTableStyleSources(
            ResolvedBuildContext ctx,
            IDMLTable idmlTable,
            ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || idmlTable == null || table == null) return;
        int tableId = parseFlexibleId(idmlTable.selfId());
        if (tableId < 0) return;
        LinkedHashSet<Integer> styleSourceIds = new LinkedHashSet<>();
        for (ObjectPlan plan : ctx.ownershipPlansForObjectId(tableId)) {
            if (!isDeclaredTableStylePlan(plan)) continue;
            addDeclaredStyleSourceIds(styleSourceIds, plan.styleSourceObjectIds);
        }
        if (styleSourceIds.isEmpty()) return;
        int applied = 0;
        for (Integer sourceId : styleSourceIds) {
            if (sourceId == null || sourceId < 0) continue;
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
            if (applyDeclaredTableStyleSource(ctx, table, item)) {
                markPageItemHandled(ctx, sourceId);
                applied++;
            }
        }
        if (ctx.debugAst && applied > 0) {
            table.debugOrNew().note("declared table style sources applied: " + applied);
        }
    }

    private static boolean isDeclaredTableStylePlan(ObjectPlan plan) {
        return plan != null
                && (plan.visualAction == VisualAction.PLACE_TABLE_STYLE
                || plan.materialization == Materialization.HWPX_TABLE_STYLE);
    }

    private static void addDeclaredStyleSourceIds(Set<Integer> target, int[] sourceIds) {
        if (target == null || sourceIds == null) return;
        for (int sourceId : sourceIds) {
            if (sourceId >= 0) target.add(sourceId);
        }
    }

    private static boolean applyDeclaredTableStyleSource(
            ResolvedBuildContext ctx,
            ASTTable table,
            ResolvedPageItem item) {
        if (ctx == null || table == null || item == null) return false;
        if (!isTableStyleMaterialSource(item)) return false;
        boolean applied = false;
        String fill = resolvedFillHex(ctx, item);
        ASTTableCell.CellBorder border = pageItemStrokeBorder(ctx, item);
        if (fill != null && shouldApplyFillFromStyleSource(ctx, table, item)) {
            applied |= applyFillFromStyleSource(ctx, table, item, fill);
        }
        if (border != null) {
            if ("GraphicLine".equals(item.type())) {
                applied |= applyLineBorderFromStyleSource(ctx, table, item, border);
            } else {
                applied |= applyRectangleBorderFromStyleSource(ctx, table, item, border);
            }
        }
        return applied;
    }

    private static boolean isTableStyleMaterialSource(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"GraphicLine".equals(type)) return false;
        if ("GraphicLine".equals(type)) {
            return hasVisibleStroke(item);
        }
        if (Math.abs(item.absoluteRotationAngle()) > 0.1) return false;
        if (Math.abs(item.absoluteShearAngle()) > 0.1) return false;
        if (item.hasDropShadow() || item.gradientFeatherApplied()) return false;
        return resolvedHasFill(item) || hasVisibleStroke(item);
    }

    private static boolean applyFillFromStyleSource(
            ResolvedBuildContext ctx,
            ASTTable table,
            ResolvedPageItem item,
            String fill) {
        double[] sourceBounds = normalizeSourceBoundsForTable(ctx, table, boundsOf(ctx, item));
        List<ASTTableCell> cells = cellsCoveredBySource(table, sourceBounds);
        if (cells.isEmpty() && singleCell(table) != null) {
            cells.add(singleCell(table));
        }
        boolean applied = false;
        for (ASTTableCell cell : cells) {
            if (cell == null) continue;
            cell.fillColor(fill);
            applied = true;
        }
        return applied;
    }

    private static boolean shouldApplyFillFromStyleSource(
            ResolvedBuildContext ctx,
            ASTTable table,
            ResolvedPageItem item) {
        if (table == null || item == null) return false;
        if (singleCell(table) != null) return true;
        double[] sourceBounds = normalizeSourceBoundsForTable(ctx, table, boundsOf(ctx, item));
        if ("Rectangle".equals(item.type())
                && boundsCover(sourceBounds, tableBoundsPoints(table), 0.75)) {
            return false;
        }
        return true;
    }

    private static boolean applyRectangleBorderFromStyleSource(
            ResolvedBuildContext ctx,
            ASTTable table,
            ResolvedPageItem item,
            ASTTableCell.CellBorder border) {
        double[] sourceBounds = normalizeSourceBoundsForTable(ctx, table, boundsOf(ctx, item));
        if (boundsCover(tableBoundsPoints(table), sourceBounds, 0.75)) {
            applyOuterBorder(table, border);
            return true;
        }
        List<ASTTableCell> cells = cellsCoveredBySource(table, sourceBounds);
        if (cells.isEmpty() && singleCell(table) != null) {
            cells.add(singleCell(table));
        }
        boolean applied = false;
        for (ASTTableCell cell : cells) {
            if (cell == null) continue;
            applyCellBoxBorder(cell, border);
            applied = true;
        }
        return applied;
    }

    private static boolean applyLineBorderFromStyleSource(
            ResolvedBuildContext ctx,
            ASTTable table,
            ResolvedPageItem item,
            ASTTableCell.CellBorder border) {
        double[] line = normalizeSourceBoundsForTable(ctx, table, boundsOf(ctx, item));
        if (line == null || line.length < 4) return false;
        double height = Math.abs(line[2] - line[0]);
        double width = Math.abs(line[3] - line[1]);
        boolean horizontal = width >= height;
        boolean applied = false;
        for (CellPlacement placement : cellPlacements(table)) {
            if (placement == null || placement.cell == null) continue;
            if (horizontal && overlapsRange(line[1], line[3], placement.left, placement.right, 0.75)) {
                double mid = (line[0] + line[2]) / 2.0;
                if (Math.abs(mid - placement.top) <= 1.0) {
                    placement.cell.topBorder(preferOuterBorder(placement.cell.topBorder(), border));
                    applied = true;
                } else if (Math.abs(mid - placement.bottom) <= 1.0) {
                    placement.cell.bottomBorder(preferOuterBorder(placement.cell.bottomBorder(), border));
                    applied = true;
                }
            } else if (!horizontal && overlapsRange(line[0], line[2], placement.top, placement.bottom, 0.75)) {
                double mid = (line[1] + line[3]) / 2.0;
                if (Math.abs(mid - placement.left) <= 1.0) {
                    placement.cell.leftBorder(preferOuterBorder(placement.cell.leftBorder(), border));
                    applied = true;
                } else if (Math.abs(mid - placement.right) <= 1.0) {
                    placement.cell.rightBorder(preferOuterBorder(placement.cell.rightBorder(), border));
                    applied = true;
                }
            }
        }
        return applied;
    }

    private static void applyCellBoxBorder(ASTTableCell cell, ASTTableCell.CellBorder border) {
        cell.topBorder(preferOuterBorder(cell.topBorder(), border));
        cell.bottomBorder(preferOuterBorder(cell.bottomBorder(), border));
        cell.leftBorder(preferOuterBorder(cell.leftBorder(), border));
        cell.rightBorder(preferOuterBorder(cell.rightBorder(), border));
    }

    private static List<ASTTableCell> cellsCoveredBySource(ASTTable table, double[] sourceBounds) {
        List<ASTTableCell> cells = new ArrayList<>();
        if (sourceBounds == null || sourceBounds.length < 4) return cells;
        for (CellPlacement placement : cellPlacements(table)) {
            if (placement == null || placement.cell == null) continue;
            double[] cellBounds = new double[] { placement.top, placement.left, placement.bottom, placement.right };
            if (boundsCover(sourceBounds, cellBounds, 0.75)
                    || boundsOverlapRatio(sourceBounds, cellBounds) >= 0.65) {
                cells.add(placement.cell);
            }
        }
        return cells;
    }

    private static double[] normalizeSourceBoundsForTable(
            ResolvedBuildContext ctx,
            ASTTable table,
            double[] sourceBounds) {
        if (!validStyleSourceBounds(sourceBounds) || table == null) return sourceBounds;
        double[] tableBounds = tableBoundsPoints(table);
        if (!validBounds(tableBounds)) return sourceBounds;
        double originalScore = tableOverlapScore(tableBounds, sourceBounds);
        double scale = ctx != null && ctx.resolvedData != null && ctx.resolvedData.scaleFactor() > 0.0
                ? ctx.resolvedData.scaleFactor()
                : 1.0;
        if (Math.abs(scale - 1.0) < 0.001) return sourceBounds;
        double[] scaled = new double[] {
                sourceBounds[0] * scale,
                sourceBounds[1] * scale,
                sourceBounds[2] * scale,
                sourceBounds[3] * scale
        };
        double scaledScore = tableOverlapScore(tableBounds, scaled);
        return scaledScore > originalScore ? scaled : sourceBounds;
    }

    private static double tableOverlapScore(double[] tableBounds, double[] sourceBounds) {
        if (!validBounds(tableBounds) || !validStyleSourceBounds(sourceBounds)) return 0.0;
        double score = boundsOverlapRatio(tableBounds, sourceBounds);
        double centerY = (sourceBounds[0] + sourceBounds[2]) / 2.0;
        double centerX = (sourceBounds[1] + sourceBounds[3]) / 2.0;
        if (centerY >= tableBounds[0] - 0.75 && centerY <= tableBounds[2] + 0.75
                && centerX >= tableBounds[1] - 0.75 && centerX <= tableBounds[3] + 0.75) {
            score += 1.0;
        }
        if (boundsCover(tableBounds, sourceBounds, 0.75)
                || boundsCover(sourceBounds, tableBounds, 0.75)) {
            score += 0.5;
        }
        return score;
    }

    private static final class CellPlacement {
        final ASTTableCell cell;
        final double top;
        final double left;
        final double bottom;
        final double right;

        CellPlacement(ASTTableCell cell, double top, double left, double bottom, double right) {
            this.cell = cell;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
        }
    }

    private static List<CellPlacement> cellPlacements(ASTTable table) {
        List<CellPlacement> out = new ArrayList<>();
        if (table == null || table.rows() == null) return out;
        double tableTop = CoordinateConverter.hwpunitsToPoints(table.y());
        double tableLeft = CoordinateConverter.hwpunitsToPoints(table.x());
        double rowTop = tableTop;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            double rowHeight = CoordinateConverter.hwpunitsToPoints(row.rowHeight());
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                double left = tableLeft + widthBeforeColumn(table, cell.columnIndex());
                double width = spannedColumnWidth(table, cell.columnIndex(), Math.max(1, cell.columnSpan()));
                double height = spannedRowHeight(table, cell.rowIndex(), Math.max(1, cell.rowSpan()));
                if (height <= 0) height = rowHeight;
                out.add(new CellPlacement(cell, rowTop, left, rowTop + height, left + width));
            }
            rowTop += rowHeight;
        }
        return out;
    }

    private static double widthBeforeColumn(ASTTable table, int columnIndex) {
        if (table == null || table.columnWidths() == null) return 0.0;
        double sum = 0.0;
        int end = Math.max(0, Math.min(columnIndex, table.columnWidths().size()));
        for (int i = 0; i < end; i++) sum += CoordinateConverter.hwpunitsToPoints(table.columnWidths().get(i));
        return sum;
    }

    private static double spannedColumnWidth(ASTTable table, int start, int span) {
        if (table == null || table.columnWidths() == null) return 0.0;
        double sum = 0.0;
        int end = Math.min(table.columnWidths().size(), Math.max(0, start) + Math.max(1, span));
        for (int i = Math.max(0, start); i < end; i++) {
            sum += CoordinateConverter.hwpunitsToPoints(table.columnWidths().get(i));
        }
        return sum;
    }

    private static double spannedRowHeight(ASTTable table, int start, int span) {
        if (table == null || table.rows() == null) return 0.0;
        double sum = 0.0;
        int end = Math.min(table.rows().size(), Math.max(0, start) + Math.max(1, span));
        for (int i = Math.max(0, start); i < end; i++) {
            ASTTableRow row = table.rows().get(i);
            if (row != null) sum += CoordinateConverter.hwpunitsToPoints(row.rowHeight());
        }
        return sum;
    }

    private static double[] tableBoundsPoints(ASTTable table) {
        if (table == null) return null;
        double top = CoordinateConverter.hwpunitsToPoints(table.y());
        double left = CoordinateConverter.hwpunitsToPoints(table.x());
        double bottom = top + CoordinateConverter.hwpunitsToPoints(table.height());
        double right = left + CoordinateConverter.hwpunitsToPoints(table.width());
        return new double[] { top, left, bottom, right };
    }

    private static boolean boundsCover(double[] outer, double[] inner, double tolerance) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        double t = Math.max(0.0, tolerance);
        return inner[0] >= outer[0] - t
                && inner[1] >= outer[1] - t
                && inner[2] <= outer[2] + t
                && inner[3] <= outer[3] + t;
    }

    private static double boundsOverlapRatio(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double top = Math.max(a[0], b[0]);
        double left = Math.max(a[1], b[1]);
        double bottom = Math.min(a[2], b[2]);
        double right = Math.min(a[3], b[3]);
        double intersection = Math.max(0.0, bottom - top) * Math.max(0.0, right - left);
        double bArea = Math.max(0.0, b[2] - b[0]) * Math.max(0.0, b[3] - b[1]);
        return bArea <= 0.0 ? 0.0 : intersection / bArea;
    }

    private static boolean overlapsRange(double a0, double a1, double b0, double b1, double tolerance) {
        double minA = Math.min(a0, a1);
        double maxA = Math.max(a0, a1);
        double minB = Math.min(b0, b1);
        double maxB = Math.max(b0, b1);
        return Math.max(minA, minB) <= Math.min(maxA, maxB) + Math.max(0.0, tolerance);
    }

    private static ASTTableCell singleCell(ASTTable table) {
        if (table == null || table.rows() == null || table.rows().size() != 1) return null;
        ASTTableRow row = table.rows().get(0);
        if (row == null || row.cells() == null || row.cells().size() != 1) return null;
        return row.cells().get(0);
    }

    private static String resolvedFillHex(ResolvedBuildContext ctx, ResolvedPageItem item) {
        if (ctx == null || ctx.resolvedData == null || item == null || !resolvedHasFill(item)) return null;
        String color = ctx.resolvedData.resolveTintedColorHex(item.fillColorName(), pageItemTint(item.fillTint()));
        return color != null && color.startsWith("#") ? color : null;
    }

    private static boolean resolvedHasFill(ResolvedPageItem item) {
        if (item == null) return false;
        String color = item.fillColorName();
        return color != null && !isNoneLike(color);
    }

    private static double[] boundsOf(ResolvedBuildContext ctx, ResolvedPageItem item) {
        if (item == null) return null;
        if (validStyleSourceBounds(item.pageRelativeBounds())) return item.pageRelativeBounds();
        if (validStyleSourceBounds(item.visibleBounds())) return normalizePageItemBounds(ctx, item, item.visibleBounds());
        if (validStyleSourceBounds(item.geometricBounds())) return normalizePageItemBounds(ctx, item, item.geometricBounds());
        return null;
    }

    private static double[] normalizePageItemBounds(
            ResolvedBuildContext ctx,
            ResolvedPageItem item,
            double[] bounds) {
        if (!validStyleSourceBounds(bounds)) return bounds;
        if (ctx == null || ctx.resolvedData == null || item == null || item.pageIndex() < 0) {
            return bounds;
        }
        int pageIndex = item.pageIndex();
        ResolvedPage page = resolvedPageByIndex(ctx, pageIndex);
        double[] pageBounds = page != null ? page.bounds() : null;
        if (!validBounds(pageBounds)) return bounds;
        double pageTop = pageBounds[0];
        double pageLeft = pageBounds[1];
        double pageRight = pageBounds[3];
        boolean spreadX = pageLeft != 0.0 && (bounds[1] >= pageLeft || bounds[3] > pageRight);
        boolean spreadY = pageTop != 0.0 && bounds[0] >= pageTop;
        if (!spreadX && !spreadY) return bounds;
        return new double[] {
                bounds[0] - (spreadY ? pageTop : 0.0),
                bounds[1] - (spreadX ? pageLeft : 0.0),
                bounds[2] - (spreadY ? pageTop : 0.0),
                bounds[3] - (spreadX ? pageLeft : 0.0)
        };
    }

    private static ResolvedPage resolvedPageByIndex(ResolvedBuildContext ctx, int pageIndex) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return null;
        for (ResolvedPage page : ctx.resolvedData.pages()) {
            if (page != null && page.index() == pageIndex) return page;
        }
        if (pageIndex >= 0 && pageIndex < ctx.resolvedData.pages().size()) {
            return ctx.resolvedData.pages().get(pageIndex);
        }
        return null;
    }

    private static boolean validBounds(double[] bounds) {
        if (bounds == null || bounds.length < 4) return false;
        return bounds[2] > bounds[0] && bounds[3] > bounds[1];
    }

    private static boolean validStyleSourceBounds(double[] bounds) {
        if (bounds == null || bounds.length < 4) return false;
        return bounds[2] >= bounds[0]
                && bounds[3] >= bounds[1]
                && (bounds[2] > bounds[0] || bounds[3] > bounds[1]);
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
        out.fixedOuterBounds(src.fixedOuterBounds());
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
            if (!isTableCompositeVisual(ctx, rg)) continue;
            ctx.markRenderedVisualHandled(rg.id());
            if (ctx.debugAst && table != null) {
                table.debugOrNew().note("table composite visual suppressed: rendered " + rg.id());
            }
        }
    }

    private static boolean isTableCompositeVisual(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || rg == null) return false;
        ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
        if (plan == null) return false;
        return plan.visualAction == VisualAction.DROP_VISUAL
                || plan.visualAction == VisualAction.PLACE_TABLE_STYLE;
    }

    private static void suppressRenderedVisualsOwnedByAnchoredNestedTable(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            IDMLTable idmlTable,
            ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || tf == null || idmlTable == null) return;
        int tfId = parseId(tf.id());
        int tableId = parseFlexibleId(idmlTable.selfId());
        if (tfId < 0 && tableId < 0) return;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
            if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!planOwnsAnchoredNestedTableShell(plan, rg, tfId, tableId)) continue;
            ctx.markRenderedVisualHandled(rg.id());
            if (ctx.debugAst && table != null) {
                table.debugOrNew().note("anchored nested table shell suppressed: rendered " + rg.id());
            }
        }
    }

    private static boolean planOwnsAnchoredNestedTableShell(
            ObjectPlan plan,
            RenderedGroup rg,
            int tfId,
            int tableId) {
        return containsSourceId(plan.ownedTextFrameIds, tfId)
                || containsSourceId(plan.sourceObjectIds, tfId)
                || containsSourceId(plan.sourceObjectIds, tableId)
                || containsSourceId(plan.visualSourceObjectIds, tfId)
                || containsSourceId(plan.styleSourceObjectIds, tfId)
                || containsSourceId(plan.styleSourceObjectIds, tableId)
                || containsSourceId(rg != null ? rg.sourceObjectIds() : null, tfId)
                || containsSourceId(rg != null ? rg.sourceObjectIds() : null, tableId);
    }

    private static boolean containsSourceId(int[] sourceIds, int id) {
        if (id < 0) return false;
        if (sourceIds == null) return false;
        for (int sourceId : sourceIds) {
            if (sourceId == id) return true;
        }
        return false;
    }

    public static void absorbTextFrameOutlineIntoTable(
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

    private static void absorbInlineTextShellStylesIntoTableCells(
            ResolvedBuildContext ctx,
            ASTTable table) {
        if (ctx == null || ctx.resolvedData == null || table == null || table.rows() == null) return;
        boolean singleCellTable = singleCell(table) != null;
        int applied = 0;
        int removed = 0;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                InlineTextShellStyleSources sources = inlineTextShellStyleSourcesInCell(ctx, cell);
                if (sources.isEmpty()) continue;
                boolean cellApplied = false;
                for (Integer sourceId : sources.styleSourceIds) {
                    if (sourceId == null || sourceId < 0) continue;
                    ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
                    if (!isTableStyleMaterialSource(item)) continue;
                    String fill = resolvedFillHex(ctx, item);
                    if (fill != null) {
                        cell.fillColor(fill);
                        cellApplied = true;
                    }
                    ASTTableCell.CellBorder border = pageItemStrokeBorder(ctx, item);
                    if (border != null) {
                        if (singleCellTable) {
                            applyOuterBorder(table, border);
                        } else {
                            applyCellBoxBorder(cell, border);
                        }
                        cellApplied = true;
                    }
                    markPageItemHandled(ctx, sourceId);
                }
                if (cellApplied) {
                    applied++;
                    if (singleCellTable) {
                        applyInlineTextShellPlacementToTable(ctx, table, sources);
                    }
                    applyOwnedTextFrameInsetsToCell(ctx, cell, sources.ownedTextFrameIds);
                    removed += replaceInlineTextShellCarriersWithOwnedText(
                            ctx, cell.paragraphs(), sources.ownerSourceIds, sources.ownedTextFrameIds);
                    removed += removeEmptyInlineTextShellObjects(cell.paragraphs(), sources.ownerSourceIds);
                }
            }
        }
        if (ctx.debugAst && applied > 0) {
            table.debugOrNew().note("inline text shell style absorbed into table cells: cells=" + applied
                    + ", carriersRemoved=" + removed);
        }
    }

    private static InlineTextShellStyleSources inlineTextShellStyleSourcesInCell(
            ResolvedBuildContext ctx,
            ASTTableCell cell) {
        InlineTextShellStyleSources sources = new InlineTextShellStyleSources();
        if (ctx == null || cell == null) return sources;
        collectInlineTextShellStyleSources(ctx, cell.paragraphs(), sources);
        return sources;
    }

    private static void collectInlineTextShellStyleSources(
            ResolvedBuildContext ctx,
            List<ASTParagraph> paragraphs,
            InlineTextShellStyleSources sources) {
        if (ctx == null || paragraphs == null || sources == null) return;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject obj = (ASTInlineObject) item;
                Integer domId = parseSourceObjectId(obj.sourceId());
                if (domId != null) {
                    for (ObjectPlan plan : ctx.ownershipPlansForObjectId(domId)) {
                        if (!isAbsorbableInlineTextShellPlan(plan)) continue;
                        sources.ownerSourceIds.add(domId);
                        sources.ownerSourceIds.add(plan.domId);
                        addAll(sources.ownerSourceIds, plan.sourceObjectIds);
                        sources.shellPlans.add(plan);
                        addAll(sources.styleSourceIds, plan.visualSourceObjectIds);
                        addAll(sources.ownedTextFrameIds, plan.ownedTextFrameIds);
                        if (sources.styleSourceIds.isEmpty()) {
                            addAll(sources.styleSourceIds, plan.sourceObjectIds);
                        }
                    }
                }
                collectInlineTextShellStyleSources(ctx, obj.paragraphs(), sources);
            }
        }
    }

    private static boolean isAbsorbableInlineTextShellPlan(ObjectPlan plan) {
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0
                && ShellRole.isTextShell(plan);
    }

    private static void applyInlineTextShellPlacementToTable(
            ResolvedBuildContext ctx,
            ASTTable table,
            InlineTextShellStyleSources sources) {
        if (ctx == null || ctx.resolvedData == null || table == null || sources == null
                || sources.shellPlans == null || sources.shellPlans.isEmpty()) {
            return;
        }
        ObjectPlan shellPlan = firstPlanWithBounds(sources.shellPlans);
        if (shellPlan == null || !validStyleSourceBounds(shellPlan.bounds)) return;
        ResolvedTextFrame owner = ctx.resolvedData.getTableOwnerTextFrame(table.sourceId());
        double[] ownerBounds = owner != null ? owner.pageRelativeBounds() : null;
        if (!validStyleSourceBounds(ownerBounds)) return;
        double[] inset = owner.insetSpacing();
        double ownerLeft = ownerBounds[1]
                + (inset != null && inset.length >= 4 ? Math.max(0.0, inset[1]) : 0.0);
        double scale = ctx.resolvedData.scaleFactor();
        if (scale <= 0.0) scale = 1.0;
        long offsetX = CoordinateConverter.pointsToHwpunits(
                Math.max(0.0, shellPlan.bounds[1] - ownerLeft) * scale);
        if (offsetX <= 0) return;
        table.inlineOffsetX(offsetX);
        double ownerRight = ownerBounds[3]
                - (inset != null && inset.length >= 4 ? Math.max(0.0, inset[3]) : 0.0);
        long availableWidth = CoordinateConverter.pointsToHwpunits(
                Math.max(0.0, ownerRight - shellPlan.bounds[1]) * scale);
        long fitWidth = availableWidth - inlineTextShellTableClipGuard(table);
        if (fitWidth > 0 && fitWidth < table.width()) {
            fitSingleCellTableWidth(table, fitWidth);
        }
        if (ctx.debugAst) {
            table.debugOrNew().note("inline text shell table x offset preserved from source shell: "
                    + offsetX + " hwp");
        }
    }

    private static long inlineTextShellTableClipGuard(ASTTable table) {
        long fallback = CoordinateConverter.pointsToHwpunits(0.5);
        if (table == null) return fallback;
        long borderGuard = table.borderWidth() > 0
                ? CoordinateConverter.pointsToHwpunits(table.borderWidth())
                : 0L;
        return Math.max(fallback, borderGuard);
    }

    private static void fitSingleCellTableWidth(ASTTable table, long width) {
        if (table == null || width <= 0 || singleCell(table) == null) return;
        table.width(width);
        if (table.columnWidths() != null && !table.columnWidths().isEmpty()) {
            table.columnWidths().clear();
            table.addColumnWidth(width);
        }
        ASTTableCell cell = singleCell(table);
        if (cell != null) {
            cell.width(width);
        }
    }

    private static ObjectPlan firstPlanWithBounds(List<ObjectPlan> plans) {
        if (plans == null) return null;
        for (ObjectPlan plan : plans) {
            if (plan != null && validStyleSourceBounds(plan.bounds)) return plan;
        }
        return null;
    }

    private static int replaceInlineTextShellCarriersWithOwnedText(
            ResolvedBuildContext ctx,
            List<ASTParagraph> paragraphs,
            Set<Integer> ownerSourceIds,
            Set<Integer> ownedTextFrameIds) {
        if (ctx == null || paragraphs == null || ownerSourceIds == null || ownerSourceIds.isEmpty()
                || ownedTextFrameIds == null || ownedTextFrameIds.isEmpty()) {
            return 0;
        }
        List<ASTParagraph> ownedParagraphs = ownedTextFrameParagraphs(ctx, ownedTextFrameIds);
        if (ownedParagraphs.isEmpty()) return 0;

        int replaced = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            ASTParagraph paragraph = paragraphs.get(i);
            if (!isOnlyInlineTextShellCarrierParagraph(paragraph, ownerSourceIds)) continue;
            paragraphs.remove(i);
            paragraphs.addAll(i, ownedParagraphs);
            replaced++;
            i += ownedParagraphs.size() - 1;
        }
        return replaced;
    }

    private static List<ASTParagraph> ownedTextFrameParagraphs(
            ResolvedBuildContext ctx,
            Set<Integer> ownedTextFrameIds) {
        List<ASTParagraph> out = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || ownedTextFrameIds == null) return out;
        Set<String> seenStories = new LinkedHashSet<>();
        for (Integer textFrameId : ownedTextFrameIds) {
            if (textFrameId == null || textFrameId < 0) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
            if (tf == null || tf.storyId() == null || tf.storyId().isEmpty()) continue;
            if (!seenStories.add(tf.storyId())) continue;

            List<ASTParagraph> paragraphs = StoryLoader.convertStoryFromIDML(ctx, tf.storyId());
            if (paragraphs == null || paragraphs.isEmpty()) {
                ResolvedStory story = ctx.resolvedData.getStory(tf.storyId());
                if (story != null) {
                    paragraphs = StoryConverter.convertStoryParagraphs(ctx, story);
                }
            }
            if (paragraphs != null) {
                applyOwnedTextFrameRunColor(ctx, tf, paragraphs);
                out.addAll(paragraphs);
            }
        }
        return out;
    }

    private static void applyOwnedTextFrameInsetsToCell(
            ResolvedBuildContext ctx,
            ASTTableCell cell,
            Set<Integer> ownedTextFrameIds) {
        if (ctx == null || ctx.resolvedData == null || cell == null
                || ownedTextFrameIds == null || ownedTextFrameIds.isEmpty()) {
            return;
        }
        for (Integer textFrameId : ownedTextFrameIds) {
            if (textFrameId == null || textFrameId < 0) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(textFrameId));
            double[] inset = tf != null ? tf.insetSpacing() : null;
            if (inset == null || inset.length < 4) continue;
            cell.marginTop(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[0])));
            cell.marginLeft(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[1])));
            cell.marginBottom(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[2])));
            cell.marginRight(CoordinateConverter.pointsToHwpunits(Math.max(0.0, inset[3])));
            return;
        }
    }

    private static void applyOwnedTextFrameRunColor(
            ResolvedBuildContext ctx,
            ResolvedTextFrame tf,
            List<ASTParagraph> paragraphs) {
        if (ctx == null || ctx.resolvedData == null || tf == null || paragraphs == null || paragraphs.isEmpty()) {
            return;
        }
        ResolvedStory story = tf.storyId() != null ? ctx.resolvedData.getStory(tf.storyId()) : null;
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return;
        int paraCount = Math.min(paragraphs.size(), story.paragraphs().size());
        for (int p = 0; p < paraCount; p++) {
            ASTParagraph paragraph = paragraphs.get(p);
            ResolvedParagraph resolvedParagraph = story.paragraphs().get(p);
            if (paragraph == null || paragraph.items() == null
                    || resolvedParagraph == null || resolvedParagraph.runs() == null) {
                continue;
            }
            List<String> colors = resolvedParagraphRunColors(ctx, resolvedParagraph.runs());
            if (colors.isEmpty()) continue;
            int textRunIndex = 0;
            for (ASTInlineItem item : paragraph.items()) {
                if (!(item instanceof ASTTextRun)) continue;
                ASTTextRun run = (ASTTextRun) item;
                String color = colors.get(Math.min(textRunIndex, colors.size() - 1));
                if (color != null && !color.isEmpty()) {
                    run.textColor(color);
                }
                textRunIndex++;
            }
        }
    }

    private static List<String> resolvedParagraphRunColors(
            ResolvedBuildContext ctx,
            List<ResolvedRun> runs) {
        List<String> colors = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || runs == null) return colors;
        for (ResolvedRun run : runs) {
            if (run == null || run.fillColor() == null || run.fillColor().isEmpty()) continue;
            String color = ctx.resolvedData.resolveColorHex(run.fillColor());
            if (color != null && !color.isEmpty()) {
                colors.add(color);
            }
        }
        return colors;
    }

    private static boolean isOnlyInlineTextShellCarrierParagraph(
            ASTParagraph paragraph,
            Set<Integer> ownerSourceIds) {
        if (paragraph == null || paragraph.items() == null || paragraph.items().isEmpty()) return false;
        boolean sawCarrier = false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !isMarkerOnlyInlineShellText(text)) return false;
                continue;
            }
            if (!(item instanceof ASTInlineObject)) return false;
            ASTInlineObject obj = (ASTInlineObject) item;
            Integer domId = parseSourceObjectId(obj.sourceId());
            if (domId == null || !ownerSourceIds.contains(domId)) return false;
            sawCarrier = true;
        }
        return sawCarrier;
    }

    private static boolean isMarkerOnlyInlineShellText(String text) {
        if (text == null || text.isEmpty()) return true;
        String cleaned = text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return cleaned.isEmpty();
    }

    private static int removeEmptyInlineTextShellObjects(
            List<ASTParagraph> paragraphs,
            Set<Integer> ownerSourceIds) {
        if (paragraphs == null || ownerSourceIds == null || ownerSourceIds.isEmpty()) return 0;
        int removed = 0;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null || paragraph.items() == null) continue;
            Iterator<ASTInlineItem> it = paragraph.items().iterator();
            while (it.hasNext()) {
                ASTInlineItem item = it.next();
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject obj = (ASTInlineObject) item;
                removed += removeEmptyInlineTextShellObjects(obj.paragraphs(), ownerSourceIds);
                Integer domId = parseSourceObjectId(obj.sourceId());
                if (domId != null
                        && ownerSourceIds.contains(domId)
                        && isStyleOnlyInlineTextShellCarrier(obj)) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private static boolean isStyleOnlyInlineTextShellCarrier(ASTInlineObject obj) {
        if (obj == null) return true;
        if (obj.paragraphs() != null && !obj.paragraphs().isEmpty()) return false;
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return false;
        if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) return false;
        return true;
    }

    private static final class InlineTextShellStyleSources {
        final Set<Integer> ownerSourceIds = new LinkedHashSet<>();
        final Set<Integer> styleSourceIds = new LinkedHashSet<>();
        final Set<Integer> ownedTextFrameIds = new LinkedHashSet<>();
        final List<ObjectPlan> shellPlans = new ArrayList<>();

        boolean isEmpty() {
            return ownerSourceIds.isEmpty() || styleSourceIds.isEmpty();
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
        border.tint(pageItemTint(item.strokeTint()));
        String color = ctx.resolvedData.resolveTintedColorHex(item.strokeColorName(), pageItemTint(item.strokeTint()));
        if (color == null || color.isEmpty() || !color.startsWith("#")) color = "#000000";
        border.color(color);
        return border;
    }

    private static double pageItemTint(double tint) {
        return tint <= 0.0 ? 100.0 : ColorResolver.normalizeTint(tint);
    }

    private static void applyOuterBorder(ASTTable table, ASTTableCell.CellBorder border) {
        if (table.rows() == null || table.rows().isEmpty()) return;
        int physicalRowCount = table.rows().size();
        int lastRow = Math.max(0, Math.max(table.rowCount(), physicalRowCount) - 1);
        int physicalRowIndex = 0;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            int physicalColCount = row.cells().size();
            int lastCol = Math.max(0, Math.max(table.colCount(), physicalColCount) - 1);
            int physicalColIndex = 0;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                int rowStart = cell.rowIndex();
                int rowEnd = rowStart + Math.max(1, cell.rowSpan()) - 1;
                int colStart = cell.columnIndex();
                int colEnd = colStart + Math.max(1, cell.columnSpan()) - 1;
                int physicalRowEnd = physicalRowIndex + Math.max(1, cell.rowSpan()) - 1;
                int physicalColEnd = physicalColIndex + Math.max(1, cell.columnSpan()) - 1;
                if (rowStart <= 0 || physicalRowIndex <= 0) {
                    cell.topBorder(preferOuterBorder(cell.topBorder(), border));
                }
                if (rowEnd >= lastRow || physicalRowEnd >= physicalRowCount - 1) {
                    cell.bottomBorder(preferOuterBorder(cell.bottomBorder(), border));
                }
                if (colStart <= 0 || physicalColIndex <= 0) {
                    cell.leftBorder(preferOuterBorder(cell.leftBorder(), border));
                }
                if (colEnd >= lastCol || physicalColEnd >= physicalColCount - 1) {
                    cell.rightBorder(preferOuterBorder(cell.rightBorder(), border));
                }
                physicalColIndex++;
            }
            physicalRowIndex++;
        }
    }

    private static ASTTableCell.CellBorder preferOuterBorder(
            ASTTableCell.CellBorder existing,
            ASTTableCell.CellBorder outer) {
        if (!isVisibleCellBorder(outer)) return existing;
        if (!isVisibleCellBorder(existing)) return cloneBorder(outer);
        if (outer.weight() >= existing.weight()) return cloneBorder(outer);
        return existing;
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

    private static final class EmptyCellPlacement {
        final ASTTable ownerTable;
        final ASTTableCell cell;
        final long left;
        final long top;
        final long right;
        final long bottom;

        EmptyCellPlacement(ASTTable ownerTable, ASTTableCell cell,
                           long left, long top, long right, long bottom) {
            this.ownerTable = ownerTable;
            this.cell = cell;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /**
     * Some IDML table-in-table structures arrive as two native HWPX table blocks:
     * an outer carrier table with an intentionally empty cell, and the real nested
     * table placed in that cell's page-space bounds. Stage 1 already decided both
     * sides as HWPX table/style owners; this pass only restores the parent/child
     * AST structure so the nested table flows inside the carrier cell.
     */
    private static int absorbNestedTableBlocksIntoEmptyCells(
            ResolvedBuildContext ctx,
            List<ASTSection> sections) {
        if (ctx == null || sections == null) return 0;
        int absorbed = 0;
        for (ASTSection section : sections) {
            if (section == null || section.blocks() == null || section.blocks().isEmpty()) continue;
            List<ASTBlock> blocks = section.blocks();
            List<ASTTable> tables = new ArrayList<>();
            for (ASTBlock block : blocks) {
                if (block instanceof ASTTable) tables.add((ASTTable) block);
            }
            if (tables.size() < 2) continue;

            List<EmptyCellPlacement> emptyCells = emptyCellPlacements(tables);
            if (emptyCells.isEmpty()) continue;

            Iterator<ASTBlock> it = blocks.iterator();
            while (it.hasNext()) {
                ASTBlock block = it.next();
                if (!(block instanceof ASTTable)) continue;
                ASTTable child = (ASTTable) block;
                if (!isNestedTableBlockCandidate(ctx, child)) continue;
                EmptyCellPlacement target = findContainingEmptyCell(child, emptyCells);
                if (target == null || target.ownerTable == child) continue;

                ASTParagraph paragraph = new ASTParagraph();
                normalizeNestedInlineTable(child);
                paragraph.inlineTable(child);
                removeEmptyPlaceholderParagraphs(target.cell);
                target.cell.addParagraph(paragraph);
                it.remove();
                absorbed++;
                if (ctx.debugAst) {
                    target.ownerTable.debugOrNew().note("nested table block absorbed into empty cell: "
                            + child.sourceId());
                    child.debugOrNew().note("absorbed as inline table into carrier table cell");
                }
            }
        }
        return absorbed;
    }

    private static void removeEmptyPlaceholderParagraphs(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) return;
        cell.paragraphs().removeIf(TableBuilder::isEmptyPlaceholderParagraph);
    }

    private static boolean isEmptyPlaceholderParagraph(ASTParagraph paragraph) {
        if (paragraph == null) return true;
        if (paragraph.inlineTable() != null) return false;
        if (paragraph.items() == null || paragraph.items().isEmpty()) return true;
        for (ASTInlineItem item : paragraph.items()) {
            if (item == null) continue;
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.replace("\r", "").replace("\n", "").trim().isEmpty()) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean isNestedTableBlockCandidate(ResolvedBuildContext ctx, ASTTable table) {
        if (table == null) return false;
        String sourceId = table.sourceId();
        if (sourceId != null && (ctx.isAnchoredNestedTableSource(sourceId)
                || ctx.isTableStyleOwnedByObjectPlan(sourceId))) {
            return true;
        }
        return table.flowWithText() || table.anchoredFlowWithText();
    }

    private static List<EmptyCellPlacement> emptyCellPlacements(List<ASTTable> tables) {
        List<EmptyCellPlacement> result = new ArrayList<>();
        if (tables == null) return result;
        for (ASTTable table : tables) {
            if (table == null || table.rows() == null || table.columnWidths() == null) continue;
            long rowTop = table.y();
            for (ASTTableRow row : table.rows()) {
                if (row == null || row.cells() == null) continue;
                long rowHeight = Math.max(0L, row.rowHeight());
                for (ASTTableCell cell : row.cells()) {
                    if (cell == null || cellHasVisibleContent(cell)) continue;
                    int startCol = Math.max(0, cell.columnIndex());
                    int endCol = startCol + Math.max(1, cell.columnSpan());
                    long left = table.x() + columnWidthSum(table.columnWidths(), 0, startCol);
                    long width = cell.width() > 0
                            ? cell.width()
                            : columnWidthSum(table.columnWidths(), startCol, endCol);
                    long height = cell.height() > 0
                            ? cell.height()
                            : rowHeight * Math.max(1, cell.rowSpan());
                    if (width <= 0 || height <= 0) continue;
                    result.add(new EmptyCellPlacement(
                            table, cell, left, rowTop, left + width, rowTop + height));
                }
                rowTop += rowHeight;
            }
        }
        return result;
    }

    private static EmptyCellPlacement findContainingEmptyCell(
            ASTTable child,
            List<EmptyCellPlacement> emptyCells) {
        if (child == null || emptyCells == null || emptyCells.isEmpty()) return null;
        long left = child.x();
        long top = child.y();
        long width = child.width() > 0 ? child.width() : tableWidth(child);
        long height = child.height() > 0 ? child.height() : tableHeight(child);
        long right = left + Math.max(0L, width);
        long bottom = top + Math.max(0L, height);
        long tolerance = CoordinateConverter.pointsToHwpunits(1.5);
        EmptyCellPlacement best = null;
        long bestArea = Long.MAX_VALUE;
        for (EmptyCellPlacement cell : emptyCells) {
            if (cell == null || cell.ownerTable == child) continue;
            if (left < cell.left - tolerance) continue;
            if (top < cell.top - tolerance) continue;
            if (right > cell.right + tolerance) continue;
            if (bottom > cell.bottom + tolerance) continue;
            long area = Math.max(1L, cell.right - cell.left) * Math.max(1L, cell.bottom - cell.top);
            if (area < bestArea) {
                best = cell;
                bestArea = area;
            }
        }
        return best;
    }

    private static void normalizeNestedInlineTable(ASTTable table) {
        if (table == null) return;
        table.x(0);
        table.y(0);
        table.flowWithText(true);
        table.anchoredFlowWithText(true);
        table.fixedOuterBounds(false);
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
                if (isEmptyInlineObjectAfterTableRemoval(obj)) {
                    it.remove();
                }
            }
        }
        return removed;
    }

    private static boolean isEmptyInlineObjectAfterTableRemoval(ASTInlineObject obj) {
        if (obj == null) return true;
        if (obj.paragraphs() != null && !obj.paragraphs().isEmpty()) return false;
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return false;
        if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) return false;
        if (obj.imageData() != null || obj.imagePath() != null || obj.imageFillData() != null) return false;
        return true;
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
                    if (isStoryOwnedByPlacedTextFrame(ctx, storyRef)) continue;
                    kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory nestedStory =
                            ctx.loadIDMLStory.apply(storyRef);
                    if (nestedStory == null) continue;
                    if (!nestedStory.hasTables()) continue;
                    ResolvedTextFrame nestedOwner = nestedTextFrameOwnerForStory(ctx, storyRef);
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable nestedTable : nestedStory.tables()) {
                        ASTTable nestedAst = buildPreparedAstTable(ctx, nestedTable, 0, 0, 0, true);
                        if (nestedAst == null) continue;
                        absorbTextFrameOutlineIntoTable(ctx, nestedOwner, nestedAst);
                        ASTParagraph paragraph = new ASTParagraph();
                        paragraph.inlineTable(nestedAst);
                        astCell.addParagraph(paragraph);
                    }
                }
            }
        }
    }

    private static ResolvedTextFrame nestedTextFrameOwnerForStory(
            ResolvedBuildContext ctx,
            String storyRef) {
        if (ctx == null || ctx.resolvedData == null || storyRef == null) return null;
        String decimalStoryId = toDecimalStoryId(storyRef);
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(decimalStoryId);
        if (frames == null || frames.isEmpty()) {
            frames = ctx.resolvedData.getTextFramesForStory(storyRef);
        }
        if (frames == null || frames.isEmpty()) return null;
        for (ResolvedTextFrame tf : frames) {
            if (tf != null && tf.isInline()) return tf;
        }
        return frames.get(0);
    }

    private static boolean isStoryOwnedByPlacedTextFrame(ResolvedBuildContext ctx, String storyRef) {
        return StoryFlowAssembler.isStoryOwnedByPlacedTextFrame(ctx, storyRef);
    }

    private static boolean hasAuthoritativeResolvedStructure(ResolvedStory story) {
        return StoryFlowAssembler.hasAuthoritativeResolvedStructure(story);
    }

    private static void inlineNestedTextFrameParagraphsInCells(ResolvedBuildContext ctx, ASTTable table) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) continue;
                List<ASTParagraph> paragraphs = cell.paragraphs();
                for (int i = 0; i < paragraphs.size(); i++) {
                    ASTInlineObject nested = firstNestedTextFrame(paragraphs.get(i));
                    if (nested == null || nested.paragraphs() == null || nested.paragraphs().isEmpty()) continue;
                    if (isPlannedInlineTextShellObject(ctx, nested)) continue;
                    if (hasDrawableNestedTextFrameShell(nested)) continue;
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

    private static boolean hasDrawableNestedTextFrameShell(ASTInlineObject obj) {
        if (obj == null) return false;
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) return true;
        String fill = obj.fillColor();
        if (fill != null && fill.startsWith("#")) return true;
        String stroke = obj.strokeColor();
        if (stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0) return true;
        return obj.cornerRadius() > 0.01;
    }

    private static void applyTableCellLineWrapHints(ASTTable table) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                if (isSourceSingleLineCell(cell)) {
                    cell.squeezeLineWrap(true);
                    markCellParagraphsSqueeze(cell);
                }
            }
        }
    }

    private static boolean cellHasSqueezeCarrier(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraphHasSqueezeCarrier(paragraph)) return true;
        }
        return false;
    }

    private static boolean paragraphHasSqueezeCarrier(ASTParagraph paragraph) {
        if (paragraph == null) return false;
        if (paragraph.squeezeLineWrap()) return true;
        if (paragraph.inlineTable() != null && tableHasSqueezeCarrier(paragraph.inlineTable())) return true;
        if (paragraph.items() == null) return false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTInlineObject
                    && inlineObjectHasSqueezeCarrier((ASTInlineObject) item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inlineObjectHasSqueezeCarrier(ASTInlineObject obj) {
        if (obj == null) return false;
        if (obj.paragraphs() != null) {
            for (ASTParagraph paragraph : obj.paragraphs()) {
                if (paragraphHasSqueezeCarrier(paragraph)) return true;
            }
        }
        if (obj.inlineTables() != null) {
            for (ASTTable table : obj.inlineTables()) {
                if (tableHasSqueezeCarrier(table)) return true;
            }
        }
        if (obj.overlayFrames() != null) {
            for (ASTInlineObject overlay : obj.overlayFrames()) {
                if (inlineObjectHasSqueezeCarrier(overlay)) return true;
            }
        }
        return false;
    }

    private static boolean tableHasSqueezeCarrier(ASTTable table) {
        if (table == null || table.rows() == null) return false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                if (cell.squeezeLineWrap() || cellHasSqueezeCarrier(cell)) return true;
            }
        }
        return false;
    }

    private static void markCellParagraphsSqueeze(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph != null && !paragraph.squeezeLineWrap()) {
                paragraph.squeezeLineWrap(true);
            }
        }
    }

    private static boolean isSourceSingleLineCell(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        ASTParagraph paragraph = singleTextParagraph(cell);
        if (paragraph == null) return false;
        int maxFont = maxFontSizeHwpunits(paragraph);
        if (maxFont <= 0 || cell.height() <= 0) return false;
        long contentHeight = cell.height()
                - Math.max(0, cell.marginTop())
                - Math.max(0, cell.marginBottom());
        if (contentHeight <= 0) contentHeight = cell.height();
        return contentHeight <= Math.round(maxFont * 2.1d);
    }

    private static ASTParagraph singleTextParagraph(ASTTableCell cell) {
        ASTParagraph found = null;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null) return null;
            if (!paragraphHasVisibleText(paragraph)) continue;
            if (found != null) return null;
            if (!isSingleLinePlainTextParagraph(paragraph)) return null;
            found = paragraph;
        }
        return found;
    }

    private static boolean paragraphHasVisibleText(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return true;
            } else if (item != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingleLinePlainTextParagraph(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null || paragraph.items().isEmpty()) return false;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) return false;
            String text = ((ASTTextRun) item).text();
            if (text == null || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                    || text.indexOf('\u2028') >= 0 || text.indexOf('\u000b') >= 0) {
                return false;
            }
        }
        return true;
    }

    private static int maxFontSizeHwpunits(ASTParagraph paragraph) {
        int max = 0;
        if (paragraph == null || paragraph.items() == null) return max;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            Integer fontSize = ((ASTTextRun) item).fontSizeHwpunits();
            if (fontSize != null && fontSize > max) max = fontSize;
        }
        return max;
    }

    private static boolean isPlannedInlineTextShellObject(ResolvedBuildContext ctx, ASTInlineObject obj) {
        if (ctx == null || obj == null || obj.sourceId() == null) return false;
        Integer domId = parseSourceObjectId(obj.sourceId());
        if (domId == null) return false;
        if (ctx.ownershipPlans == null) return false;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (!ShellRole.isTextShell(plan)) continue;
            if (plan.domId == domId
                    || containsInt(plan.sourceObjectIds, domId)
                    || containsInt(plan.visualSourceObjectIds, domId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInt(int[] values, int target) {
        if (values == null) return false;
        for (int value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static void addAll(Set<Integer> target, int[] values) {
        if (target == null || values == null) return;
        for (int value : values) target.add(value);
    }

    private static Integer parseSourceObjectId(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        String value = sourceId;
        int suffix = value.indexOf('#');
        if (suffix >= 0) value = value.substring(0, suffix);
        if (value.startsWith("child_")) value = value.substring("child_".length());
        if (value.startsWith("page_obj_")) value = value.substring("page_obj_".length());
        try {
            if (value.startsWith("u") || value.startsWith("U")) {
                return Integer.parseInt(value.substring(1), 16);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
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
        List<ASTParagraph> paragraphs = StoryConverter.convertStoryParagraphs(ctx, story);
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
        cell.firstBaselineOffset(src.firstBaselineOffset());
        cell.minimumFirstBaselineOffset(src.minimumFirstBaselineOffset());
        cell.squeezeLineWrap(src.squeezeLineWrap());
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

    private static void markPageItemHandled(ResolvedBuildContext ctx, int itemId) {
        ctx.markRenderedVisualHandled(itemId);
    }

    /**
     * 그룹 ID 집합과 그 모든 자손을 Stage 3 visual executor에서 중복 처리하지 않도록 등록.
     *
     * 처리 순서:
     * 1) groupIds 자체를 TEXT_BLOCK_PLACED로 등록
     * 2) pageItems childIds BFS로 그룹 자손 전체 수집 → suppressed
     */
    private static void markAllDescendantsVisualHandled(
            ResolvedBuildContext ctx, Set<Integer> groupIds) {
        if (ctx.renderedItemDispositions == null || ctx.resolvedData == null) return;

        // Step 1: 부모 그룹 직접 등록
        for (int id : groupIds) ctx.markRenderedVisualHandled(id);

        // Step 2: pageItems childIds BFS로 자손 전체 억제
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
                    ctx.markRenderedVisualHandled(child);
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
     * 인라인 객체가 포함된 테이블 전체 PNG를 Stage 1 ObjectPlan으로만 실행한다.
     */
    private static ASTFigure renderTableAsImage(ResolvedBuildContext ctx,
                                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                                ResolvedTextFrame tf, long x, long y, int pageIdx) {
        if (ctx.basePath == null || ctx.resolvedData == null) return null;

        String tfDomId = tf.id();
        int domId;
        try { domId = Integer.parseInt(tfDomId); } catch (NumberFormatException e) { return null; }

        File pngFile = null;
        ObjectPlan tablePngPlan = null;
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg.id() == domId) {
                ObjectPlan plan = ctx.findOwnershipPlanForRendered(rg);
                if (!isPlannedWholeTablePng(plan)) return null;
                File f = new File(ctx.basePath, plan.file);
                if (f.exists()) {
                    pngFile = f;
                    tablePngPlan = plan;
                    break;
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

            double[] planBounds = tablePngPlan.bounds;
            double bw = Math.abs(planBounds[3] - planBounds[1]) * ctx.scaleFactor;
            double bh = Math.abs(planBounds[2] - planBounds[0]) * ctx.scaleFactor;
            fig.width(CoordinateConverter.pointsToHwpunits(bw));
            fig.height(CoordinateConverter.pointsToHwpunits(bh));
            return fig;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPlannedWholeTablePng(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (plan.coordinateSpace != CoordinateSpace.PAGE) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) return false;
        if (plan.materialization != Materialization.COMPLETE_PNG
                && plan.materialization != Materialization.EXTRACTED_PNG_VECTOR) {
            return false;
        }
        return plan.file != null
                && !plan.file.isEmpty()
                && plan.bounds != null
                && plan.bounds.length >= 4
                && Math.abs(plan.bounds[3] - plan.bounds[1]) > 0
                && Math.abs(plan.bounds[2] - plan.bounds[0]) > 0;
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
        if (r.detachedInlinePageLevel > 0) {
            System.err.println("  · " + r.detachedInlinePageLevel
                    + " detached inline table frames placed page-level");
        }
        if (r.tableOnlyPlansPlaced > 0) {
            System.err.println("  · " + r.tableOnlyPlansPlaced
                    + " table-only ownership plans placed as ASTTable");
        }
        if (r.tableOnlyPlansSkippedAnchored > 0) {
            System.err.println("  · " + r.tableOnlyPlansSkippedAnchored
                    + " table-only ownership plans skipped because anchored table plans own them");
        }
        if (r.duplicateInlineTablesRemoved > 0) {
            System.err.println("  · " + r.duplicateInlineTablesRemoved
                    + " duplicate inline tables removed by source ownership");
        }
        if (r.nestedTableBlocksAbsorbed > 0) {
            System.err.println("  · " + r.nestedTableBlocksAbsorbed
                    + " nested table blocks absorbed into empty carrier cells");
        }
        if (r.pngMissingFallback > 0) {
            System.err.println("  · WARNING: " + r.pngMissingFallback
                    + " → ASTTable forced (PNG missing, badge duplication risk!)");
        }
    }
}
