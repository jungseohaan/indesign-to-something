package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

/**
 * SPEC-018 M2: 시멘틱 레이어 enum/상수.
 *
 * <p>TypeScript {@code packages/semantic-layer/src/types.ts} 와 1:1로 일치한다.
 * 같은 AST + 같은 스키마 → 같은 SemanticLayer.</p>
 */
public final class SemanticTypes {
    private SemanticTypes() {}

    /** SemanticNode.nodeType — 노드의 구조적 분류. */
    public enum NodeType {
        FRAME, PARAGRAPH, TABLE, FIGURE, INLINE_OBJECT, EQUATION
    }

    /** 페이지 영역 분류. */
    public enum RegionTag {
        TOP, MIDDLE, BOTTOM, LEFT, RIGHT, FULL_WIDTH
    }

    /** 시멘틱 관계 타입. */
    public enum RelationType {
        PARENT_OF, CAPTION_FOR, ANSWER_FOR, SOLUTION_FOR, CONTINUES_FROM, REFERENCES
    }

    /** 라벨 카테고리. */
    public enum LabelCategory {
        content, structure, media, decoration
    }

    /** 룰 조건 연산자. TS Condition.operator 와 1:1. */
    public enum Operator {
        eq, ne, gt, lt, gte, lte,
        contains, startsWith, matches,
        in, notIn
    }

    /** "UNKNOWN" 라벨 상수 — 미분류 노드의 기본 라벨. */
    public static final String LABEL_UNKNOWN = "UNKNOWN";
}
