package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * SemanticSchema — 라벨 정의 + 분류 룰 + 관계 룰 + 레이아웃 힌트.
 *
 * <p>TypeScript {@code SemanticSchema} 와 1:1. JSON 직렬화 시
 * 필드명/순서 동일.</p>
 */
public class SemanticSchema {
    public String schemaId = "";
    public String schemaName = "";
    public String version = "1.0.0";
    public String subject = "";
    public String documentType = "";
    /** 부모 스키마 ID. null 이면 단독 스키마. */
    public String extendsSchema; // JSON: "extends" — Gson에서 SerializedName 처리

    public List<LabelDef> labels = new ArrayList<>();
    public List<ClassificationRule> rules = new ArrayList<>();
    public List<RelationRule> relationRules = new ArrayList<>();
    public List<LayoutHint> layoutHints = new ArrayList<>();

    /**
     * LabelDef — 라벨 정의.
     */
    public static class LabelDef {
        public String id = "";
        public String name = "";
        public String description = "";
        public String color = "#888888";
        public String icon = "";
        public SemanticTypes.LabelCategory category = SemanticTypes.LabelCategory.content;
        public List<String> allowedChildren = new ArrayList<>();
    }

    /**
     * ClassificationRule — 단일 분류 룰.
     *
     * <p>{@code conditions} 는 모두 AND. 한 조건이라도 false면 매칭 실패.</p>
     */
    public static class ClassificationRule {
        public String id = "";
        public String label = "";
        public int priority = 999;
        public List<Condition> conditions = new ArrayList<>();
        public double confidence = 0.5;
    }

    /**
     * Condition — 단일 조건.
     *
     * <p>field 는 dot-notation 지원 (예: "spatial.overlappingNodeIds").</p>
     */
    public static class Condition {
        public String field = "";
        public SemanticTypes.Operator operator = SemanticTypes.Operator.eq;
        /** 임의 타입(string/number/boolean/array). Gson 동적 파싱. */
        public Object value;
    }

    /**
     * RelationRule — 라벨 → 라벨 관계 룰.
     */
    public static class RelationRule {
        public String id = "";
        public SemanticTypes.RelationType type;
        public String sourceLabel = "";
        public String targetLabel = "";
        public List<Condition> conditions = new ArrayList<>();
    }

    /**
     * LayoutHint — 라벨이 기대하는 페이지 영역 힌트.
     */
    public static class LayoutHint {
        public String label = "";
        public List<SemanticTypes.RegionTag> expectedRegions = new ArrayList<>();
    }
}
