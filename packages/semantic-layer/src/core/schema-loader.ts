/**
 * SchemaLoader — 스키마 JSON 로드 + 상속(extends) 해석.
 */

import type { SemanticSchema, ClassificationRule, LabelDef, RelationRule, LayoutHint } from '../types.js'

/** 스키마 저장소 */
export class SchemaLoader {
  private schemas = new Map<string, SemanticSchema>()

  /** 스키마 등록 */
  register(schema: SemanticSchema): void {
    this.schemas.set(schema.schemaId, schema)
  }

  /** JSON에서 스키마 로드 & 등록 */
  loadFromJson(json: string | object): SemanticSchema {
    const raw = typeof json === 'string' ? JSON.parse(json) : json
    const schema = validateSchema(raw)
    this.register(schema)
    return schema
  }

  /** 스키마 가져오기 (상속 해석 포함) */
  get(schemaId: string): SemanticSchema | null {
    const schema = this.schemas.get(schemaId)
    if (!schema) return null
    return this.resolve(schema)
  }

  /** 등록된 모든 스키마 ID */
  listIds(): string[] {
    return [...this.schemas.keys()]
  }

  /** 상속 해석: extends된 부모 스키마와 합치기 */
  private resolve(schema: SemanticSchema): SemanticSchema {
    if (!schema.extends) return schema

    const parent = this.schemas.get(schema.extends)
    if (!parent) return schema

    const resolvedParent = this.resolve(parent)
    return mergeSchemas(resolvedParent, schema)
  }
}

/** 부모 + 자식 스키마 합치기 */
function mergeSchemas(parent: SemanticSchema, child: SemanticSchema): SemanticSchema {
  // 자식 레이블이 부모를 덮어씀 (같은 id)
  const labelMap = new Map<string, LabelDef>()
  for (const l of parent.labels) labelMap.set(l.id, l)
  for (const l of child.labels) labelMap.set(l.id, l)

  // 규칙: 부모 + 자식 합쳐서 priority로 정렬
  const allRules: ClassificationRule[] = [...parent.rules, ...child.rules]
  allRules.sort((a, b) => a.priority - b.priority)

  // 관계 규칙: 합치기
  const relationMap = new Map<string, RelationRule>()
  for (const r of parent.relationRules) relationMap.set(r.id, r)
  for (const r of child.relationRules) relationMap.set(r.id, r)

  // 레이아웃 힌트: 합치기
  const hintMap = new Map<string, LayoutHint>()
  for (const h of parent.layoutHints) hintMap.set(h.label, h)
  for (const h of child.layoutHints) hintMap.set(h.label, h)

  return {
    schemaId: child.schemaId,
    schemaName: child.schemaName,
    version: child.version,
    subject: child.subject,
    documentType: child.documentType,
    // extends는 제거 (이미 해석됨)
    labels: [...labelMap.values()],
    rules: allRules,
    relationRules: [...relationMap.values()],
    layoutHints: [...hintMap.values()],
  }
}

/** 스키마 검증 + 기본값 보장 */
function validateSchema(raw: any): SemanticSchema {
  return {
    schemaId: raw.schemaId ?? '',
    schemaName: raw.schemaName ?? '',
    version: raw.version ?? '1.0.0',
    subject: raw.subject ?? '',
    documentType: raw.documentType ?? '',
    extends: raw.extends ?? undefined,
    labels: (raw.labels ?? []).map(validateLabel),
    rules: (raw.rules ?? []).map(validateRule),
    relationRules: raw.relationRules ?? [],
    layoutHints: raw.layoutHints ?? [],
  }
}

function validateLabel(raw: any): LabelDef {
  return {
    id: raw.id ?? '',
    name: raw.name ?? '',
    description: raw.description ?? '',
    color: raw.color ?? '#888888',
    icon: raw.icon ?? '',
    category: raw.category ?? 'content',
    allowedChildren: raw.allowedChildren ?? [],
  }
}

function validateRule(raw: any): ClassificationRule {
  return {
    id: raw.id ?? '',
    label: raw.label ?? '',
    priority: raw.priority ?? 999,
    conditions: raw.conditions ?? [],
    confidence: raw.confidence ?? 0.5,
  }
}
