package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stage 0 table source fact index. */
public final class TableSourceIndex {
    private final List<TableSourceRecord> records;
    private final List<String> warnings;

    public TableSourceIndex(List<TableSourceRecord> records, List<String> warnings) {
        this.records = records != null ? new ArrayList<>(records) : new ArrayList<>();
        this.warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
    }

    public List<TableSourceRecord> records() {
        return Collections.unmodifiableList(records);
    }

    public List<TableSourceRecord> executableRecords() {
        List<TableSourceRecord> out = new ArrayList<>();
        for (TableSourceRecord record : records) {
            if (record != null && record.executable()) out.add(record);
        }
        return out;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
