/**
 * AST Adapter 데이터 타입 — SLA가 필요로 하는 최소한의 AST 데이터 구조.
 * AST JSON 필드명과 무관하게 정의된 인터페이스.
 */
/** 페이지 정보 */
interface PageInfo {
    pageNumber: number;
    width: number;
    height: number;
    marginTop: number;
    marginBottom: number;
    marginLeft: number;
    marginRight: number;
    columnCount: number;
    columnGutter: number;
}
/** 블록 타입 */
type BlockType = 'TEXT_FRAME' | 'TABLE' | 'FIGURE';
/** 블록 정보 */
interface BlockInfo {
    id: string;
    blockType: BlockType;
    pageNumber: number;
    x: number;
    y: number;
    width: number;
    height: number;
    zOrder: number;
    rotation: number;
    storyId: string | null;
    columnCount: number;
    fillColor: string | null;
    strokeColor: string | null;
    hasFill: boolean;
    hasStroke: boolean;
    isBackgroundOnly: boolean;
    verticalJustification: string | null;
}
/** 문단 정보 */
interface ParagraphInfo {
    index: number;
    alignment: string | null;
    paragraphStyleRef: string | null;
    firstLineIndent: number | null;
    spaceBefore: number | null;
    spaceAfter: number | null;
    items: InlineItemInfo[];
}
/** 인라인 아이템 타입 */
type InlineItemType = 'TEXT_RUN' | 'INLINE_OBJECT' | 'BREAK' | 'EQUATION';
/** 인라인 객체 종류 */
type InlineObjectKind = 'IMAGE' | 'RENDERED_GROUP' | 'INLINE_TEXT_FRAME' | 'SPACER_RECT';
/** 인라인 아이템 정보 */
interface InlineItemInfo {
    itemType: InlineItemType;
    text?: string;
    fontFamily?: string;
    fontStyle?: string;
    fontSize?: number;
    textColor?: string;
    bold?: boolean;
    underline?: boolean;
    strikeThrough?: boolean;
    subscript?: boolean;
    superscript?: boolean;
    characterStyleRef?: string;
    objectKind?: InlineObjectKind;
    objectSourceId?: string;
    objectWidth?: number;
    objectHeight?: number;
    equationScript?: string;
    equationSourceType?: string;
    equationColor?: string;
    breakType?: string;
}
/** 스토리 정보 */
interface StoryInfo {
    storyId: string;
    orientation: string | null;
    linkedFrameIds: string[];
    pages: number[];
    paragraphCount: number;
    tableCount: number;
}
/** 스타일 타입 */
type StyleType = 'paragraph' | 'character';
/** 스타일 정보 */
interface StyleInfo {
    styleId: string;
    styleName: string | null;
    type: StyleType;
    basedOnStyleRef: string | null;
    fontFamily?: string;
    fontStyle?: string;
    fontSize?: number;
    bold?: boolean;
    italic?: boolean;
    alignment?: string;
    textColor?: string;
}
/** 이미지 정보 */
interface ImageInfo {
    format: string | null;
    imagePath: string | null;
    bundlePath: string | null;
    pixelWidth: number;
    pixelHeight: number;
    hasImageData: boolean;
}
/** 테이블 정보 */
interface TableInfo {
    id: string;
    rowCount: number;
    colCount: number;
    columnWidths: number[];
    borderColor: string | null;
    borderWidth: number;
    rows: TableRowInfo[];
}
interface TableRowInfo {
    rowIndex: number;
    rowHeight: number;
    cells: TableCellInfo[];
}
interface TableCellInfo {
    rowIndex: number;
    columnIndex: number;
    rowSpan: number;
    columnSpan: number;
    fillColor: string | null;
    paragraphs: ParagraphInfo[];
}
/** 폰트 정보 */
interface FontInfo {
    fontId: string;
    fontFamily: string | null;
    fontType: string | null;
}
/** 색상 맵 */
type ColorMap = Record<string, string>;

/**
 * ASTAdapter — SLA 코어와 AST JSON 사이의 인터페이스 경계.
 *
 * SLA 엔진은 이 인터페이스를 통해서만 AST 데이터에 접근한다.
 * AST JSON 구조가 변경되면 구현체(ASTJsonAdapter)만 수정.
 */

interface ASTAdapter {
    getPages(): PageInfo[];
    getBlocks(pageNumber: number): BlockInfo[];
    getParagraphs(blockId: string): ParagraphInfo[];
    getStories(): StoryInfo[];
    getStory(storyId: string): StoryInfo | null;
    getFrameIdsForStory(storyId: string): string[];
    getParagraphStyles(): StyleInfo[];
    getCharacterStyles(): StyleInfo[];
    getStyleByRef(ref: string): StyleInfo | null;
    getImageInfo(blockId: string): ImageInfo | null;
    getTableInfo(blockId: string): TableInfo | null;
    getFonts(): FontInfo[];
    getColors(): ColorMap;
    getColorHex(colorRef: string): string | null;
    getSourceFile(): string | null;
    getDocumentHash(): string;
}

/**
 * ASTJsonAdapter — AST JSON 구조를 아는 유일한 파일.
 *
 * Java ASTSerializer.toJson() 출력을 ASTAdapter 인터페이스로 변환.
 * AST 필드명/구조가 변경되면 이 파일만 수정한다.
 */

declare class ASTJsonAdapter implements ASTAdapter {
    private readonly json;
    private readonly blockIndex;
    private readonly storyIndex;
    private readonly styleIndex;
    private _hash;
    constructor(astJson: string | object);
    private buildIndices;
    getPages(): PageInfo[];
    getBlocks(pageNumber: number): BlockInfo[];
    getParagraphs(blockId: string): ParagraphInfo[];
    getStories(): StoryInfo[];
    getStory(storyId: string): StoryInfo | null;
    getFrameIdsForStory(storyId: string): string[];
    getParagraphStyles(): StyleInfo[];
    getCharacterStyles(): StyleInfo[];
    getStyleByRef(ref: string): StyleInfo | null;
    getImageInfo(blockId: string): ImageInfo | null;
    getTableInfo(blockId: string): TableInfo | null;
    getFonts(): FontInfo[];
    getColors(): ColorMap;
    getColorHex(colorRef: string): string | null;
    getSourceFile(): string | null;
    getDocumentHash(): string;
    private mapBlock;
    private mapBlockType;
    private detectBackgroundOnly;
    private mapParagraph;
    private mapInlineItem;
    private mapStory;
    private mapStyle;
    private mapTableRow;
    private mapTableCell;
}

/**
 * SLA 핵심 타입 정의 — SemanticNode, Schema, Rule, Relation.
 */
type NodeType = 'FRAME' | 'PARAGRAPH' | 'TABLE' | 'FIGURE' | 'INLINE_OBJECT' | 'EQUATION';
interface SpatialProximityFeatures {
    nearestContentNodeId: string | null;
    nearestContentDistance: number;
    overlappingNodeIds: string[];
    isVisuallyContainedBy: string | null;
    visualContainmentRatio: number;
}
interface StructuralFeatures {
    pageNumber: number;
    x: number;
    y: number;
    width: number;
    height: number;
    zOrder: number;
    regionTag: RegionTag;
    columnIndex: number;
    relativeYInPage: number;
    storyId: string | null;
    storyFrameCount: number;
    storyPageSpan: number;
    frameIndexInStory: number;
    isStoryStart: boolean;
    isStoryEnd: boolean;
    textContent: string;
    textLength: number;
    paragraphCount: number;
    dominantFontSize: number;
    maxFontSize: number;
    dominantFontFamily: string;
    hasBoldText: boolean;
    dominantAlignment: string | null;
    hasNumberPrefix: boolean;
    numberPrefixPattern: string | null;
    firstLineText: string;
    paragraphStyleNames: string[];
    characterStyleNames: string[];
    dominantParagraphStyle: string | null;
    hasFill: boolean;
    fillColor: string | null;
    hasStroke: boolean;
    isBackgroundOnly: boolean;
    columnCount: number;
    rotationAngle: number;
    hasTable: boolean;
    hasImage: boolean;
    hasEquation: boolean;
    hasInlineFrame: boolean;
    inlineObjectCount: number;
    blockType: string;
    spatial: SpatialProximityFeatures;
}
type RegionTag = 'TOP' | 'MIDDLE' | 'BOTTOM' | 'LEFT' | 'RIGHT' | 'FULL_WIDTH';
interface SemanticNode {
    id: string;
    astPath: string;
    nodeType: NodeType;
    features: StructuralFeatures;
    label: string;
    confidence: number;
    appliedRule: string | null;
    manualOverride: boolean;
    children: string[];
    storyId: string | null;
    metadata: Record<string, unknown>;
}
type RelationType = 'PARENT_OF' | 'CAPTION_FOR' | 'ANSWER_FOR' | 'SOLUTION_FOR' | 'CONTINUES_FROM' | 'REFERENCES';
interface SemanticRelation {
    type: RelationType;
    sourceId: string;
    targetId: string;
    confidence?: number;
}
interface SemanticSchema {
    schemaId: string;
    schemaName: string;
    version: string;
    subject: string;
    documentType: string;
    extends?: string;
    labels: LabelDef[];
    rules: ClassificationRule[];
    relationRules: RelationRule[];
    layoutHints: LayoutHint[];
}
interface LabelDef {
    id: string;
    name: string;
    description: string;
    color: string;
    icon: string;
    category: 'content' | 'structure' | 'media' | 'decoration';
    allowedChildren: string[];
}
interface ClassificationRule {
    id: string;
    label: string;
    priority: number;
    conditions: Condition[];
    confidence: number;
}
interface Condition {
    field: string;
    operator: 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte' | 'contains' | 'startsWith' | 'matches' | 'in' | 'notIn';
    value: unknown;
}
interface RelationRule {
    id: string;
    type: RelationType;
    sourceLabel: string;
    targetLabel: string;
    conditions?: Condition[];
}
interface LayoutHint {
    label: string;
    expectedRegions: RegionTag[];
}
interface SemanticLayer {
    version: string;
    schemaId: string;
    sourceAstHash: string;
    previousAstHash?: string;
    createdAt: string;
    modifiedAt: string;
    mergeHistory: MergeHistoryEntry[];
    nodes: SemanticNode[];
    relations: SemanticRelation[];
    deletedNodes: DeletedNode[];
}
interface MergeHistoryEntry {
    timestamp: string;
    previousHash: string;
    stats: {
        matched: number;
        manualPreserved: number;
        reclassified: number;
        added: number;
        deleted: number;
        symmetryMatched: number;
    };
}
interface DeletedNode {
    id: string;
    label: string;
    manualOverride: boolean;
    deletedAt: string;
    fingerprint: NodeFingerprint;
}
interface NodeFingerprint {
    sourceId: string;
    storyId: string | null;
    frameIndexInStory: number;
    textFingerprint: string;
    pageNumber: number;
}

/**
 * FeatureExtractor — ASTAdapter를 통해 구조적 특징을 추출하고 SemanticNode[]를 생성.
 */

/** FeatureExtractor 메인 함수 */
declare function extractFeatures(adapter: ASTAdapter): SemanticNode[];

/**
 * SchemaLoader — 스키마 JSON 로드 + 상속(extends) 해석.
 */

/** 스키마 저장소 */
declare class SchemaLoader {
    private schemas;
    /** 스키마 등록 */
    register(schema: SemanticSchema): void;
    /** JSON에서 스키마 로드 & 등록 */
    loadFromJson(json: string | object): SemanticSchema;
    /** 스키마 가져오기 (상속 해석 포함) */
    get(schemaId: string): SemanticSchema | null;
    /** 등록된 모든 스키마 ID */
    listIds(): string[];
    /** 상속 해석: extends된 부모 스키마와 합치기 */
    private resolve;
}

/**
 * RuleClassifier — ClassificationRule[] 평가 엔진.
 * 노드의 StructuralFeatures에 대해 조건을 평가하고 최적 레이블을 결정.
 */

interface ClassificationResult {
    label: string;
    confidence: number;
    ruleId: string;
}
/**
 * 노드 배열에 규칙을 적용하여 레이블을 부여.
 * manualOverride된 노드는 건너뜀.
 */
declare function classifyNodes(nodes: SemanticNode[], rules: ClassificationRule[]): SemanticNode[];
/** 단일 노드에 대해 최적 규칙 선택 */
declare function classifyNode(node: SemanticNode, rules: ClassificationRule[]): ClassificationResult | null;
/** 모든 조건이 만족되는지 평가 (AND) */
declare function evaluateConditions(conditions: Condition[], features: StructuralFeatures): boolean;

/**
 * RelationBuilder — 시멘틱 관계(SemanticRelation) 생성.
 * 스토리 기반 CONTINUES_FROM + 규칙 기반 + 공간 근접도.
 */

/**
 * 노드 배열에서 관계를 추출.
 */
declare function buildRelations(nodes: SemanticNode[], relationRules: RelationRule[]): SemanticRelation[];

/**
 * RuleSuggester — 수동 레이블링된 노드에서 규칙을 역추출.
 * 같은 레이블의 노드들에서 공통 feature 패턴을 찾아 규칙 초안 생성.
 */

interface SuggestedRule extends ClassificationRule {
    /** 이 규칙이 매칭하는 노드 수 */
    matchCount: number;
    /** 전체 해당 레이블 노드 대비 비율 */
    coverage: number;
}
/**
 * 수동 레이블링된 노드에서 규칙 초안 생성.
 * @param labeledNodes manualOverride가 true인 노드들
 * @param minCoverage 최소 커버리지 (기본 0.6 = 60%)
 */
declare function suggestRules(labeledNodes: SemanticNode[], minCoverage?: number): SuggestedRule[];

/**
 * SchemaGenerator — AST 노드 features를 통계 분석하여 스키마를 자동 생성.
 *
 * 접근법:
 * 1. 노드를 blockType / regionTag / 스타일(paragraphStyle + fontSize) 기준으로 그룹화
 * 2. 각 그룹의 특징을 분석하여 레이블 + 분류 규칙 생성
 * 3. 공간 근접도 기반 관계 규칙 추론
 */

interface SchemaGeneratorOptions {
    /** 스키마 ID (기본: "auto-generated") */
    schemaId?: string;
    /** 스키마 이름 */
    schemaName?: string;
    /** 과목 */
    subject?: string;
    /** 문서 유형 */
    documentType?: string;
    /** 최소 그룹 크기 (이보다 작은 그룹은 무시) */
    minGroupSize?: number;
}
/**
 * SemanticNode[] → SemanticSchema 자동 생성.
 * extractFeatures()로 생성된 노드 배열을 입력받아 스키마를 만듦.
 */
declare function generateSchema(nodes: SemanticNode[], options?: SchemaGeneratorOptions): SemanticSchema;

/**
 * RuleValidator — 규칙의 정밀도(precision) / 재현율(recall) 계산.
 * 수동 레이블 데이터와 규칙 분류 결과를 비교.
 */

interface ValidationResult {
    /** 규칙별 성능 */
    ruleMetrics: RuleMetric[];
    /** 레이블별 성능 */
    labelMetrics: LabelMetric[];
    /** 전체 정확도 */
    accuracy: number;
    /** 분류된 노드 수 */
    classifiedCount: number;
    /** 전체 노드 수 */
    totalCount: number;
}
interface RuleMetric {
    ruleId: string;
    label: string;
    /** True Positive */
    tp: number;
    /** False Positive */
    fp: number;
    /** False Negative (이 레이블인데 다른 규칙에 매칭) */
    fn: number;
    precision: number;
    recall: number;
    f1: number;
}
interface LabelMetric {
    label: string;
    tp: number;
    fp: number;
    fn: number;
    precision: number;
    recall: number;
    f1: number;
    support: number;
}
/**
 * 수동 레이블 노드와 규칙 분류 결과 비교.
 * @param groundTruth 정답 레이블이 있는 노드 (manualOverride=true)
 * @param rules 검증할 규칙 세트
 */
declare function validateRules(groundTruth: SemanticNode[], rules: ClassificationRule[]): ValidationResult;

/**
 * NodeMatcher — 이전 SLA와 새 AST 노드 간 4단계 매칭.
 *
 * Stage 1: sourceId 일치
 * Stage 2: storyId + frameIndexInStory
 * Stage 3: textFingerprint (정규화된 텍스트 해시)
 * Stage 4: Symmetry Check (LCS 텍스트 유사도 + 스타일 + 위치)
 */

interface MatchResult {
    /** 매칭된 쌍: [이전 노드 ID, 새 노드 ID] */
    matched: Array<[string, string]>;
    /** 매칭 안 된 이전 노드 ID (삭제됨) */
    unmatchedOld: string[];
    /** 매칭 안 된 새 노드 ID (추가됨) */
    unmatchedNew: string[];
    /** Symmetry Match로 매칭된 쌍 (별도 추적) */
    symmetryMatched: Array<[string, string, number]>;
}
/**
 * 이전 노드 + 새 노드를 4단계로 매칭.
 * @param symmetryThreshold Symmetry Match 자동 수락 최소 점수 (기본 0.8)
 */
declare function matchNodes(oldNodes: SemanticNode[], newNodes: SemanticNode[], symmetryThreshold?: number): MatchResult;
/** 노드 fingerprint 생성 */
declare function createFingerprint(node: SemanticNode): NodeFingerprint;
/**
 * Symmetry Score = 텍스트 유사도(60%) + 스타일 일치(20%) + 위치 근접(20%)
 */
declare function computeSymmetryScore(a: SemanticNode, b: SemanticNode): number;

/**
 * Merger — 이전 SemanticLayer + 새 AST → 업데이트된 SemanticLayer.
 * manualOverride 보존, Symmetry Match 상속.
 */

interface MergeOptions {
    rules: ClassificationRule[];
    relationRules: RelationRule[];
    schemaId: string;
    symmetryThreshold?: number;
}
/**
 * 새 AST에 기반하여 기존 SemanticLayer를 업데이트.
 * 수동 레이블, confidence, 메타데이터 보존.
 */
declare function mergeLayer(previous: SemanticLayer | null, adapter: ASTAdapter, options: MergeOptions): SemanticLayer;

/**
 * PPTStyleMapper — AST 스타일 1,000개+ → PPT 표준 슬롯 10개 클러스터링.
 *
 * 알고리즘:
 * 1. 시멘틱 레이블 기반 1차 매핑
 * 2. 폰트 크기 기반 2차 분류
 * 3. 속성 기반 3차 보정 (Bold → EMPHASIS, Monospace → CODE)
 * 4. 빈도 기반 대표값 선정
 */

/** PPT 표준 스타일 슬롯 */
type PPTStyleSlot = 'TITLE' | 'SUBTITLE' | 'HEADING1' | 'HEADING2' | 'BODY' | 'BODY_SMALL' | 'LIST_ITEM' | 'CAPTION' | 'CODE' | 'EMPHASIS';
interface StyleCluster {
    slot: PPTStyleSlot;
    pptFontSize: number;
    pptFontFamily: string;
    pptBold: boolean;
    pptItalic: boolean;
    pptColor: string;
    pptAlignment: string;
    /** 이 슬롯에 매핑된 원본 스타일명 목록 */
    sourceStyles: string[];
}
/**
 * 노드에서 PPT 스타일 클러스터를 생성.
 */
declare function clusterStyles(nodes: SemanticNode[]): StyleCluster[];

/**
 * SlideBreaker — 시멘틱 노드를 슬라이드 단위로 분할.
 *
 * 분할 규칙:
 * - SECTION_TITLE, CHAPTER_TITLE → 새 슬라이드 시작 (표지)
 * - PROBLEM + SUB_PROBLEM + CHOICES → 1 슬라이드로 그루핑
 * - CONCEPT_BOX, FORMULA → 독립 슬라이드
 * - FIGURE + CAPTION → 한 슬라이드에 묶음
 * - 콘텐츠 초과 시 자동 분할
 */

interface SlideGroup {
    /** 슬라이드 인덱스 (0-based) */
    index: number;
    /** 슬라이드 타입 */
    type: SlideType;
    /** 포함된 노드 */
    nodes: SemanticNode[];
    /** 대표 제목 */
    title: string;
}
type SlideType = 'TITLE_SLIDE' | 'CONTENT_SLIDE' | 'PROBLEM_SLIDE' | 'FIGURE_SLIDE' | 'TABLE_SLIDE' | 'CONCEPT_SLIDE';
/**
 * 시멘틱 노드를 슬라이드 그룹으로 분할.
 */
declare function breakIntoSlides(nodes: SemanticNode[], relations: SemanticRelation[]): SlideGroup[];

/**
 * SlideLayout — 슬라이드 내 요소 배치.
 * SlideGroup의 노드를 PPT 좌표계로 배치.
 */

/** PPT 슬라이드 크기 (인치) */
interface SlideSize {
    width: number;
    height: number;
}
/** 배치된 슬라이드 요소 */
interface SlideElement {
    nodeId: string;
    label: string;
    /** PPT 좌표 (인치) */
    x: number;
    y: number;
    w: number;
    h: number;
    /** 텍스트 콘텐츠 */
    text: string;
    /** 적용할 스타일 슬롯 */
    styleSlot: PPTStyleSlot;
    /** 요소 타입 */
    elementType: 'text' | 'image' | 'table' | 'shape';
}
/** 레이아웃된 슬라이드 */
interface LayoutedSlide {
    index: number;
    type: SlideType;
    title: string;
    elements: SlideElement[];
}
/**
 * 슬라이드 그룹을 PPT 레이아웃으로 변환.
 */
declare function layoutSlides(slides: SlideGroup[], slideSize?: SlideSize): LayoutedSlide[];

/**
 * PPTTemplate — 레이블 조합 → 슬라이드 템플릿 매칭.
 */

interface PPTTemplate {
    templateId: string;
    name: string;
    matchType: SlideType;
    /** 레이블 조합 매칭 (옵션) */
    matchLabels?: string[];
    /** 슬라이드 레이아웃 규칙 */
    layout: Record<string, LayoutRule>;
    /** 슬라이드 크기 (인치) */
    slideSize: {
        width: number;
        height: number;
    };
}
interface LayoutRule {
    x: string;
    y: string;
    w: string;
    h: string;
}
/** 내장 템플릿 */
declare const BUILT_IN_TEMPLATES: PPTTemplate[];
/**
 * SlideType + 레이블 조합으로 최적 템플릿 찾기.
 */
declare function matchTemplate(slideType: SlideType, labels: string[], customTemplates?: PPTTemplate[]): PPTTemplate;

/**
 * PPTRenderer — pptxgenjs를 사용하여 .pptx 파일 생성.
 */

interface RenderOptions {
    /** 슬라이드 크기 (인치) */
    slideSize?: {
        width: number;
        height: number;
    };
    /** 작성자 */
    author?: string;
    /** 제목 */
    title?: string;
    /** 주제 */
    subject?: string;
}
/**
 * 레이아웃된 슬라이드를 pptx 바이너리로 렌더링.
 * @returns ArrayBuffer (브라우저) 또는 Buffer (Node.js)
 */
declare function renderPptx(slides: LayoutedSlide[], styleClusters: StyleCluster[], options?: RenderOptions): Promise<ArrayBuffer>;

export { type ASTAdapter, ASTJsonAdapter, BUILT_IN_TEMPLATES, type BlockInfo, type BlockType, type ClassificationResult, type ClassificationRule, type ColorMap, type Condition, type DeletedNode, type FontInfo, type ImageInfo, type InlineItemInfo, type InlineItemType, type InlineObjectKind, type LabelDef, type LabelMetric, type LayoutHint, type LayoutRule, type LayoutedSlide, type MatchResult, type MergeHistoryEntry, type MergeOptions, type NodeFingerprint, type NodeType, type PPTStyleSlot, type PPTTemplate, type PageInfo, type ParagraphInfo, type RegionTag, type RelationRule, type RelationType, type RenderOptions, type RuleMetric, type SchemaGeneratorOptions, SchemaLoader, type SemanticLayer, type SemanticNode, type SemanticRelation, type SemanticSchema, type SlideElement, type SlideGroup, type SlideSize, type SlideType, type SpatialProximityFeatures, type StoryInfo, type StructuralFeatures, type StyleCluster, type StyleInfo, type StyleType, type SuggestedRule, type TableCellInfo, type TableInfo, type TableRowInfo, type ValidationResult, breakIntoSlides, buildRelations, classifyNode, classifyNodes, clusterStyles, computeSymmetryScore, createFingerprint, evaluateConditions, extractFeatures, generateSchema, layoutSlides, matchNodes, matchTemplate, mergeLayer, renderPptx, suggestRules, validateRules };
