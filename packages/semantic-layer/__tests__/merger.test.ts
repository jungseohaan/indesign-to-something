import { describe, it, expect } from 'vitest'
import { mergeLayer } from '../src/merge/merger.js'
import { ASTJsonAdapter } from '../src/adapter/ast-json-adapter.js'
import type { SemanticLayer, ClassificationRule } from '../src/types.js'

const SAMPLE_AST = {
  sourceFile: 'test.idml',
  stories: [
    {
      storyId: 'story_1',
      linkedFrameIds: ['u001'],
      pages: [1],
      paragraphCount: 1,
      tableCount: 0,
    },
  ],
  sections: [
    {
      pageNumber: 1,
      layout: {
        pageWidth: 59528,
        pageHeight: 84189,
        marginTop: 5040,
        marginBottom: 5040,
        marginLeft: 3600,
        marginRight: 3600,
        columnCount: 1,
        columnGutter: 0,
      },
      blocks: [
        {
          blockType: 'TEXT_FRAME_BLOCK',
          sourceId: 'u001',
          storyId: 'story_1',
          x: 3600,
          y: 5040,
          width: 52328,
          height: 3000,
          zOrder: 1,
          columnCount: 1,
          paragraphs: [
            {
              paragraphStyleRef: 'ps_body',
              alignment: 'LeftAlign',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: '1. 다음 문제를 풀어라.',
                  fontFamily: '바탕',
                  fontSizeHwpunits: 1200,
                },
              ],
            },
          ],
        },
        {
          blockType: 'TEXT_FRAME_BLOCK',
          sourceId: 'ubg',
          x: 0, y: 0,
          width: 59528,
          height: 84189,
          zOrder: 0,
          columnCount: 1,
          fillColor: '#FFFFFF',
          paragraphs: [],
        },
      ],
    },
  ],
  paragraphStyles: [{ styleId: 'ps_body', styleName: '본문' }],
  characterStyles: [],
  colors: {},
}

const RULES: ClassificationRule[] = [
  {
    id: 'rule-bg',
    label: 'BACKGROUND',
    priority: 5,
    conditions: [{ field: 'isBackgroundOnly', operator: 'eq', value: true }],
    confidence: 0.95,
  },
]

const OPTIONS = {
  rules: RULES,
  relationRules: [],
  schemaId: 'test-schema',
}

describe('mergeLayer', () => {
  it('이전 레이어 없으면 새로 생성', () => {
    const adapter = new ASTJsonAdapter(SAMPLE_AST)
    const layer = mergeLayer(null, adapter, OPTIONS)

    expect(layer.version).toBe('1.0.0')
    expect(layer.schemaId).toBe('test-schema')
    expect(layer.nodes).toHaveLength(2)
    expect(layer.mergeHistory).toHaveLength(0)

    // BACKGROUND 규칙 적용
    const bgNode = layer.nodes.find(n => n.label === 'BACKGROUND')
    expect(bgNode).toBeDefined()
  })

  it('manualOverride 보존', () => {
    const adapter = new ASTJsonAdapter(SAMPLE_AST)
    const initial = mergeLayer(null, adapter, OPTIONS)

    // 수동 레이블 설정
    const target = initial.nodes.find(n => n.id === 'sn-u001')!
    target.label = 'PROBLEM'
    target.manualOverride = true
    target.confidence = 1

    // 다시 merge
    const updated = mergeLayer(initial, adapter, OPTIONS)
    const preserved = updated.nodes.find(n => n.id === 'sn-u001')!
    expect(preserved.label).toBe('PROBLEM')
    expect(preserved.manualOverride).toBe(true)
  })

  it('삭제된 노드 기록', () => {
    const adapter = new ASTJsonAdapter(SAMPLE_AST)
    const initial = mergeLayer(null, adapter, OPTIONS)

    // 블록 하나 제거한 새 AST
    const reducedAst = {
      ...SAMPLE_AST,
      sections: [{
        ...SAMPLE_AST.sections[0],
        blocks: [SAMPLE_AST.sections[0].blocks[0]], // ubg 제거
      }],
    }
    const adapter2 = new ASTJsonAdapter(reducedAst)
    const updated = mergeLayer(initial, adapter2, OPTIONS)

    expect(updated.deletedNodes).toHaveLength(1)
    expect(updated.deletedNodes[0].id).toBe('sn-ubg')
  })

  it('merge history 기록', () => {
    const adapter = new ASTJsonAdapter(SAMPLE_AST)
    const initial = mergeLayer(null, adapter, OPTIONS)
    const updated = mergeLayer(initial, adapter, OPTIONS)

    expect(updated.mergeHistory).toHaveLength(1)
    expect(updated.mergeHistory[0].stats.matched).toBe(2)
    expect(updated.mergeHistory[0].previousHash).toBe(initial.sourceAstHash)
  })
})
