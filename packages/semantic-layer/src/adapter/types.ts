/**
 * AST Adapter 데이터 타입 — SLA가 필요로 하는 최소한의 AST 데이터 구조.
 * AST JSON 필드명과 무관하게 정의된 인터페이스.
 */

/** 페이지 정보 */
export interface PageInfo {
  pageNumber: number
  width: number    // HWPUNIT
  height: number
  marginTop: number
  marginBottom: number
  marginLeft: number
  marginRight: number
  columnCount: number
  columnGutter: number
}

/** 블록 타입 */
export type BlockType = 'TEXT_FRAME' | 'TABLE' | 'FIGURE'

/** 블록 정보 */
export interface BlockInfo {
  id: string              // sourceId
  blockType: BlockType
  pageNumber: number
  x: number
  y: number
  width: number
  height: number
  zOrder: number
  rotation: number
  // 텍스트 프레임 전용
  storyId: string | null
  columnCount: number
  fillColor: string | null
  strokeColor: string | null
  hasFill: boolean
  hasStroke: boolean
  isBackgroundOnly: boolean
  verticalJustification: string | null
}

/** 문단 정보 */
export interface ParagraphInfo {
  index: number
  alignment: string | null
  paragraphStyleRef: string | null
  firstLineIndent: number | null
  spaceBefore: number | null
  spaceAfter: number | null
  items: InlineItemInfo[]
}

/** 인라인 아이템 타입 */
export type InlineItemType = 'TEXT_RUN' | 'INLINE_OBJECT' | 'BREAK' | 'EQUATION'

/** 인라인 객체 종류 */
export type InlineObjectKind = 'IMAGE' | 'RENDERED_GROUP' | 'INLINE_TEXT_FRAME' | 'SPACER_RECT'

/** 인라인 아이템 정보 */
export interface InlineItemInfo {
  itemType: InlineItemType
  // TEXT_RUN
  text?: string
  fontFamily?: string
  fontStyle?: string
  fontSize?: number       // HWPUNIT (fontSizeHwpunits)
  textColor?: string
  bold?: boolean
  underline?: boolean
  strikeThrough?: boolean
  subscript?: boolean
  superscript?: boolean
  characterStyleRef?: string
  // INLINE_OBJECT
  objectKind?: InlineObjectKind
  objectSourceId?: string
  objectWidth?: number
  objectHeight?: number
  // EQUATION
  equationScript?: string
  equationSourceType?: string
  equationColor?: string
  // BREAK
  breakType?: string
}

/** 스토리 정보 */
export interface StoryInfo {
  storyId: string
  orientation: string | null
  linkedFrameIds: string[]
  pages: number[]
  paragraphCount: number
  tableCount: number
}

/** 스타일 타입 */
export type StyleType = 'paragraph' | 'character'

/** 스타일 정보 */
export interface StyleInfo {
  styleId: string
  styleName: string | null
  type: StyleType
  basedOnStyleRef: string | null
  fontFamily?: string
  fontStyle?: string
  fontSize?: number       // HWPUNIT
  bold?: boolean
  italic?: boolean
  alignment?: string
  textColor?: string
}

/** 이미지 정보 */
export interface ImageInfo {
  format: string | null
  imagePath: string | null
  bundlePath: string | null
  pixelWidth: number
  pixelHeight: number
  hasImageData: boolean
}

/** 테이블 정보 */
export interface TableInfo {
  id: string
  rowCount: number
  colCount: number
  columnWidths: number[]
  borderColor: string | null
  borderWidth: number
  rows: TableRowInfo[]
}

export interface TableRowInfo {
  rowIndex: number
  rowHeight: number
  cells: TableCellInfo[]
}

export interface TableCellInfo {
  rowIndex: number
  columnIndex: number
  rowSpan: number
  columnSpan: number
  fillColor: string | null
  paragraphs: ParagraphInfo[]
}

/** 폰트 정보 */
export interface FontInfo {
  fontId: string
  fontFamily: string | null
  fontType: string | null
}

/** 색상 맵 */
export type ColorMap = Record<string, string>
