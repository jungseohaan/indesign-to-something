import { describe, it, expect } from 'vitest'
import { SchemaLoader } from '../src/core/schema-loader.js'
import type { SemanticSchema } from '../src/types.js'

const COMMON_SCHEMA: SemanticSchema = {
  schemaId: 'common',
  schemaName: '공통',
  version: '1.0.0',
  subject: '',
  documentType: '',
  labels: [
    {
      id: 'PAGE_HEADER',
      name: '쪽 머리',
      description: '',
      color: '#999999',
      icon: '',
      category: 'structure',
      allowedChildren: [],
    },
    {
      id: 'BODY_TEXT',
      name: '본문',
      description: '',
      color: '#333333',
      icon: '',
      category: 'content',
      allowedChildren: [],
    },
  ],
  rules: [
    {
      id: 'rule-page-header',
      label: 'PAGE_HEADER',
      priority: 10,
      conditions: [
        { field: 'regionTag', operator: 'eq', value: 'TOP' },
      ],
      confidence: 0.9,
    },
  ],
  relationRules: [],
  layoutHints: [],
}

const MATH_SCHEMA: SemanticSchema = {
  schemaId: 'math-reference-v1',
  schemaName: '수학 참고서',
  version: '1.0.0',
  subject: '수학',
  documentType: '참고서',
  extends: 'common',
  labels: [
    {
      id: 'PROBLEM',
      name: '문제',
      description: '',
      color: '#FF6B6B',
      icon: '📝',
      category: 'content',
      allowedChildren: ['SUB_PROBLEM', 'CHOICES'],
    },
  ],
  rules: [
    {
      id: 'rule-problem',
      label: 'PROBLEM',
      priority: 50,
      conditions: [
        { field: 'hasNumberPrefix', operator: 'eq', value: true },
      ],
      confidence: 0.85,
    },
  ],
  relationRules: [],
  layoutHints: [],
}

describe('SchemaLoader', () => {
  it('스키마 등록 및 조회', () => {
    const loader = new SchemaLoader()
    loader.register(COMMON_SCHEMA)
    const schema = loader.get('common')
    expect(schema).not.toBeNull()
    expect(schema!.schemaId).toBe('common')
    expect(schema!.labels).toHaveLength(2)
  })

  it('없는 스키마는 null', () => {
    const loader = new SchemaLoader()
    expect(loader.get('nonexistent')).toBeNull()
  })

  it('스키마 상속 — 레이블 합치기', () => {
    const loader = new SchemaLoader()
    loader.register(COMMON_SCHEMA)
    loader.register(MATH_SCHEMA)

    const resolved = loader.get('math-reference-v1')!
    // 공통 2개 + 수학 1개 = 3개
    expect(resolved.labels).toHaveLength(3)
    expect(resolved.labels.map(l => l.id)).toContain('PAGE_HEADER')
    expect(resolved.labels.map(l => l.id)).toContain('PROBLEM')
  })

  it('스키마 상속 — 규칙 합치기 (priority 정렬)', () => {
    const loader = new SchemaLoader()
    loader.register(COMMON_SCHEMA)
    loader.register(MATH_SCHEMA)

    const resolved = loader.get('math-reference-v1')!
    // 공통 1개 + 수학 1개 = 2개
    expect(resolved.rules).toHaveLength(2)
    // priority 10이 50보다 먼저
    expect(resolved.rules[0].id).toBe('rule-page-header')
    expect(resolved.rules[1].id).toBe('rule-problem')
  })

  it('스키마 상속 — 자식이 부모 레이블 덮어쓰기', () => {
    const loader = new SchemaLoader()
    loader.register(COMMON_SCHEMA)
    // BODY_TEXT를 수학 버전으로 덮어쓰는 스키마
    loader.register({
      ...MATH_SCHEMA,
      labels: [
        ...MATH_SCHEMA.labels,
        {
          id: 'BODY_TEXT',
          name: '문제 본문',
          description: '수학 문제 본문',
          color: '#444444',
          icon: '',
          category: 'content',
          allowedChildren: [],
        },
      ],
    })

    const resolved = loader.get('math-reference-v1')!
    const bodyText = resolved.labels.find(l => l.id === 'BODY_TEXT')
    expect(bodyText!.name).toBe('문제 본문') // 자식이 덮어씀
  })

  it('extends 없으면 부모 없이 해석됨', () => {
    const loader = new SchemaLoader()
    loader.register(COMMON_SCHEMA)
    expect(loader.get('common')!.extends).toBeUndefined()
  })

  it('loadFromJson — 문자열 입력', () => {
    const loader = new SchemaLoader()
    const schema = loader.loadFromJson(JSON.stringify(COMMON_SCHEMA))
    expect(schema.schemaId).toBe('common')
    expect(loader.get('common')).not.toBeNull()
  })

  it('listIds', () => {
    const loader = new SchemaLoader()
    loader.register(COMMON_SCHEMA)
    loader.register(MATH_SCHEMA)
    expect(loader.listIds()).toEqual(['common', 'math-reference-v1'])
  })
})
