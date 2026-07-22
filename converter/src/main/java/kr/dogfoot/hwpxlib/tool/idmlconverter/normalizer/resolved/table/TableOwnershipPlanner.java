package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.table;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;

import java.util.LinkedHashSet;

/** Stage 1 planner for table source contracts. */
public final class TableOwnershipPlanner {
    private TableOwnershipPlanner() {
    }

    public static void plan(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.tableSourceIndex == null) return;
        int planned = 0;
        for (TableSourceRecord record : ctx.tableSourceIndex.records()) {
            if (record == null) continue;
            if (!record.executable()) {
                ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_TABLE_SOURCE_CONTRACT_NOT_EXECUTABLE\""
                        + ",\"stage\":\"stage1.tableOwnershipPlanner\",\"detail\":\"carrier="
                        + escape(record.carrierTextFrameId)
                        + " table=" + escape(record.tableId)
                        + " issue=" + escape(record.issue) + "\"}");
                continue;
            }
            if (!record.tableOnlyStory) {
                ctx.ownershipWarningLines.add("{\"code\":\"STAGE1_TABLE_SOURCE_CONTRACT_SKIPPED_NON_TABLE_ONLY_STORY\""
                        + ",\"stage\":\"stage1.tableOwnershipPlanner\",\"detail\":\"carrier="
                        + escape(record.carrierTextFrameId)
                        + " table=" + escape(record.tableId) + "\"}");
                continue;
            }
            ObjectPlan plan = new ObjectPlan(
                    record.carrierDomId,
                    "table_source_contract",
                    record.pageIndex,
                    TextAction.DROP_TEXT,
                    VisualAction.PLACE_TABLE_STYLE,
                    VisualLayer.CONTENT_VISUAL,
                    Placement.FLOATING,
                    null,
                    new int[] { record.carrierDomId, record.tableDomId },
                    new int[0],
                    styleSourceIds(record),
                    new int[] { record.carrierDomId },
                    new int[0],
                    "table:" + record.storyId + ":" + record.tableId,
                    Materialization.HWPX_TABLE_STYLE,
                    CoordinateSpace.PAGE,
                    null,
                    0,
                    "table_source_contract",
                    null,
                    record.bounds,
                    null,
                    null,
                    null,
                    -1)
                    .withObjectPlanId("source.table_contract."
                            + record.carrierDomId + "." + record.tableDomId);
            ctx.addOwnershipPlan(plan);
            planned++;
        }
        if (planned > 0) {
            System.err.println("[TableOwnershipPlanner] table source contracts=" + planned);
        }
    }

    private static int[] styleSourceIds(TableSourceRecord record) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (record.styleSourceIds != null) {
            for (int sourceId : record.styleSourceIds) {
                add(ids, sourceId);
            }
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        return out;
    }

    private static void add(LinkedHashSet<Integer> ids, int id) {
        if (id >= 0) ids.add(id);
    }

    private static String escape(String s) {
        return TableSourceRecord.escape(s);
    }
}
