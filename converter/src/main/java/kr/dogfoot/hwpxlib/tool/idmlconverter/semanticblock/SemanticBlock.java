package kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of AST object ids that form one semantic unit.
 */
public class SemanticBlock {
    public String id;
    public List<String> member_ids = new ArrayList<String>();
    public String anchor_id;
    public String display_name;
    public int page_start;
    public int page_end;
    public long[] bbox;
    public List<MemberBox> member_boxes = new ArrayList<MemberBox>();
    public int reading_order;
    public String block_type = "unknown";
    public double confidence;
    public Signals signals = new Signals();
    public Debug debug = new Debug();

    public static class MemberBox {
        public String id;
        public int page;
        public long[] bbox;
        public String kind;
        public String role;
        public String parent_id;
        public String visual_layer;
        public String group_id;
    }

    public static class Signals {
        public double anchor_score;
        public double container_score;
        public double image_score;
        public double resolved_relation_score;
    }

    public static class Debug {
        public String anchor_type;
        public String anchor_reason;
        public int text_members;
        public int visual_members;
        public int table_members;
    }
}
