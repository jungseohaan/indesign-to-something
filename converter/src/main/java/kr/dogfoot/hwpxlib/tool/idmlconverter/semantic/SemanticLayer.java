package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * SemanticLayer — 시멘틱 레이어 출력 (캐노니컬 JSON 포맷).
 *
 * <p>TypeScript {@code SemanticLayer} 와 1:1. {@code docs/semantic-format.md}
 * 참조.</p>
 */
public class SemanticLayer {
    public String version = "1.0.0";
    public String schemaId = "";
    public String sourceAstHash = "";
    public String previousAstHash;
    public String createdAt;
    public String modifiedAt;

    public List<MergeHistoryEntry> mergeHistory = new ArrayList<>();
    public List<SemanticNode> nodes = new ArrayList<>();
    public List<SemanticRelation> relations = new ArrayList<>();
    public List<DeletedNode> deletedNodes = new ArrayList<>();

    /**
     * MergeHistoryEntry — AST 재추출 시 머지 기록.
     */
    public static class MergeHistoryEntry {
        public String timestamp;
        public String previousHash;
        public MergeStats stats = new MergeStats();
    }

    public static class MergeStats {
        public int matched;
        public int manualPreserved;
        public int reclassified;
        public int added;
        public int deleted;
        public int symmetryMatched;
    }

    /**
     * DeletedNode — 머지에서 사라진 노드의 흔적.
     */
    public static class DeletedNode {
        public String id;
        public String label;
        public boolean manualOverride;
        public String deletedAt;
        public NodeFingerprint fingerprint;
    }

    public static class NodeFingerprint {
        public String sourceId;
        public String storyId;
        public int frameIndexInStory;
        public String textFingerprint;
        public int pageNumber;
    }
}
