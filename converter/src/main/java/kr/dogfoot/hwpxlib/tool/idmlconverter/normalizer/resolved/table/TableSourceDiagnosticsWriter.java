package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.table;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes Stage 0 table source facts without influencing ownership. */
public final class TableSourceDiagnosticsWriter {
    private TableSourceDiagnosticsWriter() {
    }

    public static void write(String basePath, TableSourceIndex index) {
        if (basePath == null || basePath.isEmpty() || index == null) return;
        try {
            List<String> records = new ArrayList<>();
            for (TableSourceRecord record : index.records()) {
                records.add(record.toJson());
            }
            Files.write(Path.of(basePath, "table-source-index.jsonl"), records, StandardCharsets.UTF_8);
            if (!index.warnings().isEmpty()) {
                Files.write(Path.of(basePath, "table-source-warnings.jsonl"),
                        index.warnings(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[TableSourceDiagnosticsWriter] write failed: " + e.getMessage());
        }
    }
}
