package kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic Block Discovery JSON root.
 *
 * <p>This is intentionally separate from the legacy semantic-layer model. The
 * first contract is for visual review and downstream structure detection.</p>
 */
public class SemanticBlockDocument {
    public String version = "sbd-1";
    public Source source = new Source();
    public Summary summary = new Summary();
    public List<SemanticBlock> blocks = new ArrayList<SemanticBlock>();

    public static class Source {
        public String document_name;
        public String ast_source = "ASTDocument";
        public String semantic_context = "ASTDocument";
        public String coordinate_unit = "HWPUNIT";
        public String generated_at;
    }

    public static class Summary {
        public int pages;
        public int blocks;
        public int members;
        public int anchors;
        public int unattached_visuals;
    }
}
