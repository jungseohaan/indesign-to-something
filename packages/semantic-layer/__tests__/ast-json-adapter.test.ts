import { describe, it, expect } from 'vitest'
import { ASTJsonAdapter } from '../src/adapter/ast-json-adapter.js'

/** 최소한의 AST JSON 샘플 */
const SAMPLE_AST = {
  sourceFile: 'test.idml',
  sourceFormat: 'IDML',
  stories: [
    {
      storyId: 'story_1',
      orientation: 'Horizontal',
      paragraphCount: 3,
      tableCount: 0,
      linkedFrameIds: ['u1a2b', 'u3c4d'],
      pages: [1, 2],
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
        columnCount: 2,
        columnGutter: 1440,
      },
      blocks: [
        {
          blockType: 'TEXT_FRAME_BLOCK',
          sourceId: 'u1a2b',
          storyId: 'story_1',
          x: 3600,
          y: 5040,
          width: 25204,
          height: 10000,
          zOrder: 1,
          columnCount: 1,
          verticalJustification: 'TopAlign',
          fillColor: '#F0F0F0',
          paragraphs: [
            {
              paragraphStyleRef: 'ps_body',
              alignment: 'LeftAlign',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: '1. 다음 이차방정식을 풀어라.',
                  fontFamily: '바탕',
                  fontSizeHwpunits: 1200,
                  textColor: '#000000',
                },
              ],
            },
            {
              paragraphStyleRef: 'ps_body',
              alignment: 'LeftAlign',
              items: [
                {
                  itemType: 'TEXT_RUN',
                  text: '① x² + 2x + 1 = 0',
                  fontFamily: '바탕',
                  fontStyle: 'Bold',
                  fontSizeHwpunits: 1100,
                },
                {
                  itemType: 'EQUATION',
                  hwpScript: 'x^2+2x+1=0',
                  sourceType: 'BT',
                },
              ],
            },
          ],
        },
        {
          blockType: 'TABLE',
          sourceId: 'u5e6f',
          x: 30000,
          y: 20000,
          width: 25000,
          height: 15000,
          zOrder: 2,
          rowCount: 2,
          colCount: 3,
          columnWidths: [8000, 8000, 9000],
          rows: [
            {
              rowIndex: 0,
              rowHeight: 7500,
              cells: [
                {
                  rowIndex: 0,
                  columnIndex: 0,
                  width: 8000,
                  height: 7500,
                  fillColor: '#EEEEEE',
                  paragraphs: [
                    {
                      items: [
                        { itemType: 'TEXT_RUN', text: '셀 A' },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        },
        {
          blockType: 'FIGURE',
          sourceId: 'u7g8h',
          kind: 'IMAGE',
          x: 3600,
          y: 60000,
          width: 20000,
          height: 15000,
          zOrder: 3,
          imageFormat: 'PNG',
          imagePath: 'Links/image1.png',
          pixelWidth: 800,
          pixelHeight: 600,
          hasImageData: true,
          bundlePath: 'images/image1.png',
        },
      ],
    },
  ],
  paragraphStyles: [
    {
      styleId: 'ps_body',
      styleName: '본문',
      fontFamily: '바탕',
      fontSizeHwpunits: 1200,
      alignment: 'LeftAlign',
    },
    {
      styleId: 'ps_heading',
      styleName: '제목',
      fontFamily: '고딕',
      fontSizeHwpunits: 2000,
      bold: true,
    },
  ],
  characterStyles: [
    {
      styleId: 'cs_bold',
      styleName: '강조',
      fontStyle: 'Bold',
    },
  ],
  colors: {
    'swatch_black': '#000000',
    'swatch_red': '#FF0000',
  },
}

describe('ASTJsonAdapter', () => {
  const adapter = new ASTJsonAdapter(SAMPLE_AST)

  describe('getPages', () => {
    it('페이지 정보를 정확히 반환', () => {
      const pages = adapter.getPages()
      expect(pages).toHaveLength(1)
      expect(pages[0]).toEqual({
        pageNumber: 1,
        width: 59528,
        height: 84189,
        marginTop: 5040,
        marginBottom: 5040,
        marginLeft: 3600,
        marginRight: 3600,
        columnCount: 2,
        columnGutter: 1440,
      })
    })
  })

  describe('getBlocks', () => {
    it('페이지의 블록 목록 반환', () => {
      const blocks = adapter.getBlocks(1)
      expect(blocks).toHaveLength(3)
    })

    it('TEXT_FRAME_BLOCK → TEXT_FRAME 매핑', () => {
      const blocks = adapter.getBlocks(1)
      const tf = blocks[0]
      expect(tf.id).toBe('u1a2b')
      expect(tf.blockType).toBe('TEXT_FRAME')
      expect(tf.storyId).toBe('story_1')
      expect(tf.hasFill).toBe(true)
      expect(tf.fillColor).toBe('#F0F0F0')
    })

    it('TABLE 블록 매핑', () => {
      const blocks = adapter.getBlocks(1)
      const table = blocks[1]
      expect(table.blockType).toBe('TABLE')
      expect(table.id).toBe('u5e6f')
      expect(table.storyId).toBeNull()
    })

    it('FIGURE 블록 매핑', () => {
      const blocks = adapter.getBlocks(1)
      const fig = blocks[2]
      expect(fig.blockType).toBe('FIGURE')
      expect(fig.id).toBe('u7g8h')
    })

    it('존재하지 않는 페이지는 빈 배열', () => {
      expect(adapter.getBlocks(99)).toEqual([])
    })
  })

  describe('getParagraphs', () => {
    it('블록의 문단 반환', () => {
      const paras = adapter.getParagraphs('u1a2b')
      expect(paras).toHaveLength(2)
      expect(paras[0].alignment).toBe('LeftAlign')
      expect(paras[0].paragraphStyleRef).toBe('ps_body')
    })

    it('TEXT_RUN 아이템 매핑', () => {
      const paras = adapter.getParagraphs('u1a2b')
      const item = paras[0].items[0]
      expect(item.itemType).toBe('TEXT_RUN')
      expect(item.text).toBe('1. 다음 이차방정식을 풀어라.')
      expect(item.fontSize).toBe(1200)
      expect(item.fontFamily).toBe('바탕')
    })

    it('EQUATION 아이템 매핑', () => {
      const paras = adapter.getParagraphs('u1a2b')
      const eq = paras[1].items[1]
      expect(eq.itemType).toBe('EQUATION')
      expect(eq.equationScript).toBe('x^2+2x+1=0')
      expect(eq.equationSourceType).toBe('BT')
    })

    it('존재하지 않는 블록은 빈 배열', () => {
      expect(adapter.getParagraphs('nonexistent')).toEqual([])
    })
  })

  describe('getStories', () => {
    it('스토리 목록 반환', () => {
      const stories = adapter.getStories()
      expect(stories).toHaveLength(1)
      expect(stories[0].storyId).toBe('story_1')
      expect(stories[0].linkedFrameIds).toEqual(['u1a2b', 'u3c4d'])
      expect(stories[0].pages).toEqual([1, 2])
    })
  })

  describe('getStory', () => {
    it('ID로 스토리 조회', () => {
      const story = adapter.getStory('story_1')
      expect(story).not.toBeNull()
      expect(story!.paragraphCount).toBe(3)
    })

    it('없는 스토리는 null', () => {
      expect(adapter.getStory('nonexistent')).toBeNull()
    })
  })

  describe('getFrameIdsForStory', () => {
    it('스토리의 프레임 ID 체인', () => {
      expect(adapter.getFrameIdsForStory('story_1')).toEqual(['u1a2b', 'u3c4d'])
    })
  })

  describe('styles', () => {
    it('문단 스타일 목록', () => {
      const styles = adapter.getParagraphStyles()
      expect(styles).toHaveLength(2)
      expect(styles[0].styleId).toBe('ps_body')
      expect(styles[0].styleName).toBe('본문')
    })

    it('문자 스타일 목록', () => {
      const styles = adapter.getCharacterStyles()
      expect(styles).toHaveLength(1)
      expect(styles[0].styleId).toBe('cs_bold')
    })

    it('ref로 스타일 조회', () => {
      const style = adapter.getStyleByRef('ps_heading')
      expect(style).not.toBeNull()
      expect(style!.styleName).toBe('제목')
      expect(style!.bold).toBe(true)
    })
  })

  describe('getImageInfo', () => {
    it('FIGURE 블록의 이미지 정보', () => {
      const img = adapter.getImageInfo('u7g8h')
      expect(img).not.toBeNull()
      expect(img!.format).toBe('PNG')
      expect(img!.pixelWidth).toBe(800)
      expect(img!.bundlePath).toBe('images/image1.png')
    })

    it('TEXT_FRAME 블록은 null', () => {
      expect(adapter.getImageInfo('u1a2b')).toBeNull()
    })
  })

  describe('getTableInfo', () => {
    it('TABLE 블록의 테이블 정보', () => {
      const table = adapter.getTableInfo('u5e6f')
      expect(table).not.toBeNull()
      expect(table!.rowCount).toBe(2)
      expect(table!.colCount).toBe(3)
      expect(table!.columnWidths).toEqual([8000, 8000, 9000])
      expect(table!.rows).toHaveLength(1)
      expect(table!.rows[0].cells[0].fillColor).toBe('#EEEEEE')
    })
  })

  describe('colors', () => {
    it('색상 맵 반환', () => {
      const colors = adapter.getColors()
      expect(colors['swatch_black']).toBe('#000000')
    })

    it('색상 ref로 hex 조회', () => {
      expect(adapter.getColorHex('swatch_red')).toBe('#FF0000')
      expect(adapter.getColorHex('nonexistent')).toBeNull()
    })
  })

  describe('meta', () => {
    it('소스 파일명', () => {
      expect(adapter.getSourceFile()).toBe('test.idml')
    })

    it('문서 해시 반환', () => {
      const hash = adapter.getDocumentHash()
      expect(typeof hash).toBe('string')
      expect(hash.length).toBeGreaterThan(0)
    })

    it('같은 데이터면 같은 해시', () => {
      const adapter2 = new ASTJsonAdapter(SAMPLE_AST)
      expect(adapter2.getDocumentHash()).toBe(adapter.getDocumentHash())
    })
  })

  describe('문자열 입력', () => {
    it('JSON 문자열로도 생성 가능', () => {
      const adapter2 = new ASTJsonAdapter(JSON.stringify(SAMPLE_AST))
      expect(adapter2.getPages()).toHaveLength(1)
    })
  })
})
