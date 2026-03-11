/**
 * PPTRenderer — pptxgenjs를 사용하여 .pptx 파일 생성.
 */

import PptxGenJS from 'pptxgenjs'
import type { LayoutedSlide, SlideElement } from './slide-layout.js'
import type { StyleCluster, PPTStyleSlot } from './ppt-style-mapper.js'

export interface RenderOptions {
  /** 슬라이드 크기 (인치) */
  slideSize?: { width: number; height: number }
  /** 작성자 */
  author?: string
  /** 제목 */
  title?: string
  /** 주제 */
  subject?: string
}

/**
 * 레이아웃된 슬라이드를 pptx 바이너리로 렌더링.
 * @returns ArrayBuffer (브라우저) 또는 Buffer (Node.js)
 */
export async function renderPptx(
  slides: LayoutedSlide[],
  styleClusters: StyleCluster[],
  options: RenderOptions = {},
): Promise<ArrayBuffer> {
  const pptx = new PptxGenJS()

  // 문서 메타데이터
  pptx.author = options.author ?? 'Semantic Layer'
  pptx.title = options.title ?? '시멘틱 레이어 내보내기'
  pptx.subject = options.subject ?? ''

  // 슬라이드 크기
  const size = options.slideSize ?? { width: 10, height: 7.5 }
  pptx.defineLayout({ name: 'CUSTOM', width: size.width, height: size.height })
  pptx.layout = 'CUSTOM'

  // 스타일 인덱스
  const styleMap = new Map<PPTStyleSlot, StyleCluster>()
  for (const c of styleClusters) {
    styleMap.set(c.slot, c)
  }

  // 슬라이드 생성
  for (const layoutedSlide of slides) {
    const slide = pptx.addSlide()

    // 슬라이드별 배경 (타이틀은 약간 다른 배경)
    if (layoutedSlide.type === 'TITLE_SLIDE') {
      slide.background = { color: 'F5F5F5' }
    }

    for (const element of layoutedSlide.elements) {
      renderElement(slide, element, styleMap)
    }
  }

  // 렌더링
  const output = await pptx.write({ outputType: 'arraybuffer' })
  return output as ArrayBuffer
}

/** 요소를 슬라이드에 렌더링 */
function renderElement(
  slide: PptxGenJS.Slide,
  element: SlideElement,
  styleMap: Map<PPTStyleSlot, StyleCluster>,
): void {
  const style = styleMap.get(element.styleSlot) ?? getDefaultStyle(element.styleSlot)

  switch (element.elementType) {
    case 'text':
      renderTextElement(slide, element, style)
      break
    case 'image':
      renderImagePlaceholder(slide, element)
      break
    case 'table':
      renderTablePlaceholder(slide, element, style)
      break
    case 'shape':
      renderShape(slide, element, style)
      break
  }
}

/** 텍스트 요소 렌더링 */
function renderTextElement(
  slide: PptxGenJS.Slide,
  element: SlideElement,
  style: StyleCluster,
): void {
  // 텍스트를 줄바꿈으로 분할
  const text = element.text || element.label

  slide.addText(text, {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fontSize: style.pptFontSize,
    fontFace: style.pptFontFamily,
    bold: style.pptBold,
    italic: style.pptItalic,
    color: style.pptColor.replace('#', ''),
    align: style.pptAlignment as 'left' | 'center' | 'right',
    valign: element.styleSlot === 'TITLE' ? 'middle' : 'top',
    wrap: true,
    shrinkText: true,
  })
}

/** 이미지 플레이스홀더 (실제 이미지 없이 박스 표시) */
function renderImagePlaceholder(
  slide: PptxGenJS.Slide,
  element: SlideElement,
): void {
  slide.addShape('rect', {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fill: { color: 'F0F0F0' },
    line: { color: 'CCCCCC', width: 1 },
  })
  slide.addText('[Image]', {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fontSize: 10,
    color: '999999',
    align: 'center',
    valign: 'middle',
  })
}

/** 표 플레이스홀더 */
function renderTablePlaceholder(
  slide: PptxGenJS.Slide,
  element: SlideElement,
  style: StyleCluster,
): void {
  slide.addText(element.text || '[Table]', {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fontSize: style.pptFontSize,
    fontFace: style.pptFontFamily,
    color: style.pptColor.replace('#', ''),
    fill: { color: 'FAFAFA' },
    line: { color: 'DDDDDD', width: 0.5 },
    wrap: true,
  })
}

/** 도형 렌더링 */
function renderShape(
  slide: PptxGenJS.Slide,
  element: SlideElement,
  style: StyleCluster,
): void {
  slide.addShape('rect', {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fill: { color: 'E8EAF6' },
    line: { color: '7986CB', width: 1 },
  })
  if (element.text) {
    slide.addText(element.text, {
      x: element.x,
      y: element.y,
      w: element.w,
      h: element.h,
      fontSize: style.pptFontSize,
      fontFace: style.pptFontFamily,
      wrap: true,
    })
  }
}

/** 기본 스타일 */
function getDefaultStyle(slot: PPTStyleSlot): StyleCluster {
  return {
    slot,
    pptFontSize: 12,
    pptFontFamily: '맑은 고딕',
    pptBold: false,
    pptItalic: false,
    pptColor: '#000000',
    pptAlignment: 'left',
    sourceStyles: [],
  }
}
