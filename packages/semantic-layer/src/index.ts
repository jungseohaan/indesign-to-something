/**
 * @its/semantic-layer — 공개 API
 */

// Adapter
export type { ASTAdapter } from './adapter/ast-adapter.js'
export { ASTJsonAdapter } from './adapter/ast-json-adapter.js'
export type {
  PageInfo,
  BlockInfo,
  BlockType,
  ParagraphInfo,
  InlineItemInfo,
  InlineItemType,
  InlineObjectKind,
  StoryInfo,
  StyleInfo,
  StyleType,
  ImageInfo,
  TableInfo,
  TableRowInfo,
  TableCellInfo,
  FontInfo,
  ColorMap,
} from './adapter/types.js'

// Core
export { extractFeatures } from './core/feature-extractor.js'
export { SchemaLoader } from './core/schema-loader.js'
export { classifyNodes, classifyNode, evaluateConditions } from './core/rule-classifier.js'
export type { ClassificationResult } from './core/rule-classifier.js'
export { buildRelations } from './core/relation-builder.js'
export { suggestRules } from './core/rule-suggester.js'
export type { SuggestedRule } from './core/rule-suggester.js'
export { generateSchema } from './core/schema-generator.js'
export type { SchemaGeneratorOptions } from './core/schema-generator.js'
export { validateRules } from './core/rule-validator.js'
export type { ValidationResult, RuleMetric, LabelMetric } from './core/rule-validator.js'

// Merge
export { matchNodes, createFingerprint, computeSymmetryScore } from './merge/node-matcher.js'
export type { MatchResult } from './merge/node-matcher.js'
export { mergeLayer } from './merge/merger.js'
export type { MergeOptions } from './merge/merger.js'

// PPT Export
export { clusterStyles } from './ppt/ppt-style-mapper.js'
export type { PPTStyleSlot, StyleCluster } from './ppt/ppt-style-mapper.js'
export { breakIntoSlides } from './ppt/slide-breaker.js'
export type { SlideGroup, SlideType } from './ppt/slide-breaker.js'
export { layoutSlides } from './ppt/slide-layout.js'
export type { SlideSize, SlideElement, LayoutedSlide } from './ppt/slide-layout.js'
export { matchTemplate, BUILT_IN_TEMPLATES } from './ppt/ppt-template.js'
export type { PPTTemplate, LayoutRule } from './ppt/ppt-template.js'
export { renderPptx } from './ppt/ppt-renderer.js'
export type { RenderOptions } from './ppt/ppt-renderer.js'

// Types
export type {
  NodeType,
  RegionTag,
  StructuralFeatures,
  SpatialProximityFeatures,
  SemanticNode,
  SemanticRelation,
  RelationType,
  SemanticSchema,
  LabelDef,
  ClassificationRule,
  Condition,
  RelationRule,
  LayoutHint,
  SemanticLayer,
  MergeHistoryEntry,
  DeletedNode,
  NodeFingerprint,
} from './types.js'
