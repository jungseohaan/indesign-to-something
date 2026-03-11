import { describe, it, expect } from 'vitest'
import { ASTJsonAdapter } from '../src/adapter/ast-json-adapter.js'
import { extractFeatures } from '../src/core/feature-extractor.js'

const SAMPLE_AST = {
  sourceFile: 'test.idml',
  stories: [
    {
      storyId: 'story_1',
      linkedFrameIds: ['u001'],
      pages: [1],
      paragraphCount: 2,
      tableCount: 0,
    },
    {
      storyId: 'story_2',
      linkedFrameIds: ['u002', 'u003'],
      pages: [1],
      paragraphCount: 4,
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
          verticalJustification: 'CenterAlign',
          paragraphs: [
            {
              paragraphStyleRef: 'ps_header',
              alignment: 'CenterJustify',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: 'Ⅱ. 방정식과 부등식',
                  fontFamily: '고딕',
                  fontSizeHwpunits: 800,
                },
              ],
            },
          ],
        },
        {
          blockType: 'TEXT_FRAME_BLOCK',
          sourceId: 'u002',
          storyId: 'story_2',
          x: 3600,
          y: 35000,
          width: 25000,
          height: 20000,
          zOrder: 2,
          columnCount: 1,
          fillColor: '#F5F5DC',
          strokeColor: '#333333',
          paragraphs: [
            {
              paragraphStyleRef: 'ps_body',
              alignment: 'LeftAlign',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: '1. 다음 이차방정식을 풀어라.\n',
                  fontFamily: '바탕',
                  fontStyle: 'Bold',
                  fontSizeHwpunits: 1200,
                },
              ],
            },
            {
              paragraphStyleRef: 'ps_body',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: '(1) x² + 2x + 1 = 0  ',
                  fontFamily: '바탕',
                  fontSizeHwpunits: 1100,
                },
                {
                  itemType: 'EQUATION',
                  hwpScript: 'x^2+2x+1=0',
                },
              ],
            },
            {
              paragraphStyleRef: 'ps_body',
              items: [
                {
                  itemType: 'INLINE_OBJECT',
                  kind: 'IMAGE',
                  sourceId: 'uimg1',
                  width: 5000,
                  height: 3000,
                },
              ],
            },
          ],
        },
        {
          blockType: 'TEXT_FRAME_BLOCK',
          sourceId: 'u003',
          storyId: 'story_2',
          x: 30000,
          y: 35000,
          width: 25000,
          height: 20000,
          zOrder: 3,
          columnCount: 1,
          paragraphs: [
            {
              paragraphStyleRef: 'ps_body',
              alignment: 'LeftAlign',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: '(2) 2x² - 3x + 1 = 0',
                  fontFamily: '바탕',
                  fontSizeHwpunits: 1100,
                },
              ],
            },
          ],
        },
        // 배경 전용 프레임
        {
          blockType: 'TEXT_FRAME_BLOCK',
          sourceId: 'ubg',
          x: 0,
          y: 0,
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
  paragraphStyles: [
    { styleId: 'ps_header', styleName: '머리글' },
    { styleId: 'ps_body', styleName: '본문' },
  ],
  characterStyles: [],
  colors: {},
}

describe('extractFeatures', () => {
  const adapter = new ASTJsonAdapter(SAMPLE_AST)
  const nodes = extractFeatures(adapter)

  it('모든 블록에 대해 노드 생성', () => {
    expect(nodes).toHaveLength(4)
  })

  it('노드 ID 형식: sn-{sourceId}', () => {
    expect(nodes[0].id).toBe('sn-u001')
    expect(nodes[1].id).toBe('sn-u002')
  })

  it('기본 레이블은 UNKNOWN', () => {
    for (const node of nodes) {
      expect(node.label).toBe('UNKNOWN')
    }
  })

  describe('위치 & 레이아웃 features', () => {
    it('좌표와 크기', () => {
      const f = nodes[0].features
      expect(f.pageNumber).toBe(1)
      expect(f.x).toBe(3600)
      expect(f.y).toBe(5040)
      expect(f.width).toBe(52328)
    })

    it('regionTag 계산 — 전체 너비 상단 프레임은 FULL_WIDTH', () => {
      // u001 너비가 페이지 대비 87% → FULL_WIDTH
      expect(nodes[0].features.regionTag).toBe('FULL_WIDTH')
    })

    it('regionTag 계산 — 중간 프레임은 MIDDLE', () => {
      expect(nodes[1].features.regionTag).toBe('MIDDLE')
    })

    it('regionTag 계산 — 전체 너비 프레임은 FULL_WIDTH', () => {
      // ubg는 전체 너비
      expect(nodes[3].features.regionTag).toBe('FULL_WIDTH')
    })

    it('relativeYInPage 계산', () => {
      const f = nodes[0].features
      expect(f.relativeYInPage).toBeCloseTo(5040 / 84189, 4)
    })
  })

  describe('스토리 features', () => {
    it('스토리 프레임 수', () => {
      // u001은 story_1 (1프레임)
      expect(nodes[0].features.storyFrameCount).toBe(1)
      // u002는 story_2 (2프레임)
      expect(nodes[1].features.storyFrameCount).toBe(2)
    })

    it('스토리 시작/끝 판별', () => {
      // u002: story_2의 첫 프레임
      expect(nodes[1].features.isStoryStart).toBe(true)
      expect(nodes[1].features.isStoryEnd).toBe(false)
      // u003: story_2의 마지막 프레임
      expect(nodes[2].features.isStoryStart).toBe(false)
      expect(nodes[2].features.isStoryEnd).toBe(true)
    })

    it('프레임 순서', () => {
      expect(nodes[1].features.frameIndexInStory).toBe(0)
      expect(nodes[2].features.frameIndexInStory).toBe(1)
    })
  })

  describe('텍스트 features', () => {
    it('텍스트 길이', () => {
      expect(nodes[0].features.textLength).toBeGreaterThan(0)
      expect(nodes[0].features.textContent).toContain('방정식과 부등식')
    })

    it('최빈 폰트 크기', () => {
      expect(nodes[0].features.dominantFontSize).toBe(800)
    })

    it('최대 폰트 크기', () => {
      // u002: 1200과 1100 → max 1200
      expect(nodes[1].features.maxFontSize).toBe(1200)
    })

    it('Bold 텍스트 감지', () => {
      // u002 첫 문단이 Bold
      expect(nodes[1].features.hasBoldText).toBe(true)
      // u001은 Bold 없음
      expect(nodes[0].features.hasBoldText).toBe(false)
    })

    it('번호 접두사 감지 — arabic_dot', () => {
      expect(nodes[1].features.hasNumberPrefix).toBe(true)
      expect(nodes[1].features.numberPrefixPattern).toBe('arabic_dot')
    })

    it('번호 접두사 — 로마 숫자 (Ⅱ.)', () => {
      // "Ⅱ. 방정식과 부등식" → roman 패턴 매칭
      expect(nodes[0].features.hasNumberPrefix).toBe(true)
      expect(nodes[0].features.numberPrefixPattern).toBe('roman')
    })

    it('firstLineText', () => {
      expect(nodes[1].features.firstLineText).toBe('1. 다음 이차방정식을 풀어라.')
    })
  })

  describe('스타일 features', () => {
    it('문단 스타일명 추출', () => {
      expect(nodes[0].features.paragraphStyleNames).toContain('머리글')
      expect(nodes[1].features.dominantParagraphStyle).toBe('본문')
    })
  })

  describe('프레임 속성 features', () => {
    it('배경색/테두리 감지', () => {
      expect(nodes[1].features.hasFill).toBe(true)
      expect(nodes[1].features.fillColor).toBe('#F5F5DC')
      expect(nodes[1].features.hasStroke).toBe(true)
    })

    it('isBackgroundOnly 감지', () => {
      // ubg: 빈 문단 + fillColor
      expect(nodes[3].features.isBackgroundOnly).toBe(true)
      expect(nodes[1].features.isBackgroundOnly).toBe(false)
    })
  })

  describe('콘텐츠 구성 features', () => {
    it('수식 포함', () => {
      expect(nodes[1].features.hasEquation).toBe(true)
      expect(nodes[0].features.hasEquation).toBe(false)
    })

    it('이미지 포함', () => {
      expect(nodes[1].features.hasImage).toBe(true)
    })

    it('인라인 객체 수', () => {
      expect(nodes[1].features.inlineObjectCount).toBe(1)
    })
  })

  describe('공간 근접도 features', () => {
    it('overlappingNodeIds — 배경 프레임과 겹침', () => {
      // ubg(전체 배경)는 모든 프레임과 겹침
      const bgNode = nodes[3]
      expect(bgNode.features.spatial.overlappingNodeIds.length).toBeGreaterThan(0)
    })

    it('nearestContentNodeId 설정', () => {
      // u002와 u003은 서로 가까움
      const n1 = nodes[1]
      expect(n1.features.spatial.nearestContentNodeId).not.toBeNull()
    })
  })
})
