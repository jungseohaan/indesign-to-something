package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

/**
 * 카운터 기반 ID 생성기 — Flat AST 노드에 고유 ID 부여.
 */
public class FlatIdGenerator {
    private int pageCounter = 0;
    private int nodeCounter = 0;
    private int componentCounter = 0;

    public String nextPageId() { return "pg_" + String.format("%04d", ++pageCounter); }
    public String nextNodeId() { return "ln_" + String.format("%04d", ++nodeCounter); }
    public String nextComponentId() { return "cp_" + String.format("%04d", ++componentCounter); }
}
