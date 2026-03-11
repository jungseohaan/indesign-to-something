/**
 * ASTAdapter — SLA 코어와 AST JSON 사이의 인터페이스 경계.
 *
 * SLA 엔진은 이 인터페이스를 통해서만 AST 데이터에 접근한다.
 * AST JSON 구조가 변경되면 구현체(ASTJsonAdapter)만 수정.
 */

import type {
  PageInfo,
  BlockInfo,
  ParagraphInfo,
  StoryInfo,
  StyleInfo,
  ImageInfo,
  TableInfo,
  FontInfo,
  ColorMap,
} from './types.js'

export interface ASTAdapter {
  // 문서 구조
  getPages(): PageInfo[]
  getBlocks(pageNumber: number): BlockInfo[]
  getParagraphs(blockId: string): ParagraphInfo[]

  // 스토리
  getStories(): StoryInfo[]
  getStory(storyId: string): StoryInfo | null
  getFrameIdsForStory(storyId: string): string[]

  // 스타일
  getParagraphStyles(): StyleInfo[]
  getCharacterStyles(): StyleInfo[]
  getStyleByRef(ref: string): StyleInfo | null

  // 미디어
  getImageInfo(blockId: string): ImageInfo | null
  getTableInfo(blockId: string): TableInfo | null

  // 폰트
  getFonts(): FontInfo[]

  // 색상
  getColors(): ColorMap
  getColorHex(colorRef: string): string | null

  // 메타
  getSourceFile(): string | null
  getDocumentHash(): string
}
