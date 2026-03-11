/**
 * PPTTemplate — 레이블 조합 → 슬라이드 템플릿 매칭.
 */

import type { SlideType } from './slide-breaker.js'

export interface PPTTemplate {
  templateId: string
  name: string
  matchType: SlideType
  /** 레이블 조합 매칭 (옵션) */
  matchLabels?: string[]
  /** 슬라이드 레이아웃 규칙 */
  layout: Record<string, LayoutRule>
  /** 슬라이드 크기 (인치) */
  slideSize: { width: number; height: number }
}

export interface LayoutRule {
  x: string     // "5%" | "0.5in"
  y: string
  w: string
  h: string
}

/** 내장 템플릿 */
export const BUILT_IN_TEMPLATES: PPTTemplate[] = [
  {
    templateId: 'title-only',
    name: '제목 슬라이드',
    matchType: 'TITLE_SLIDE',
    layout: {
      TITLE: { x: '10%', y: '30%', w: '80%', h: '40%' },
    },
    slideSize: { width: 10, height: 7.5 },
  },
  {
    templateId: 'problem-with-choices',
    name: '문제 + 보기',
    matchType: 'PROBLEM_SLIDE',
    matchLabels: ['PROBLEM', 'CHOICES'],
    layout: {
      PROBLEM: { x: '5%', y: '10%', w: '90%', h: '40%' },
      CHOICES: { x: '5%', y: '55%', w: '90%', h: '40%' },
    },
    slideSize: { width: 10, height: 7.5 },
  },
  {
    templateId: 'figure-with-caption',
    name: '그림 + 캡션',
    matchType: 'FIGURE_SLIDE',
    matchLabels: ['FIGURE', 'CAPTION'],
    layout: {
      FIGURE: { x: '10%', y: '8%', w: '80%', h: '65%' },
      CAPTION: { x: '10%', y: '78%', w: '80%', h: '10%' },
    },
    slideSize: { width: 10, height: 7.5 },
  },
  {
    templateId: 'content-basic',
    name: '기본 콘텐츠',
    matchType: 'CONTENT_SLIDE',
    layout: {
      DEFAULT: { x: '5%', y: '10%', w: '90%', h: '85%' },
    },
    slideSize: { width: 10, height: 7.5 },
  },
  {
    templateId: 'concept-box',
    name: '개념 박스',
    matchType: 'CONCEPT_SLIDE',
    layout: {
      CONCEPT_BOX: { x: '8%', y: '10%', w: '84%', h: '80%' },
    },
    slideSize: { width: 10, height: 7.5 },
  },
  {
    templateId: 'table-slide',
    name: '표',
    matchType: 'TABLE_SLIDE',
    layout: {
      TABLE: { x: '5%', y: '10%', w: '90%', h: '80%' },
      CAPTION: { x: '5%', y: '92%', w: '90%', h: '6%' },
    },
    slideSize: { width: 10, height: 7.5 },
  },
]

/**
 * SlideType + 레이블 조합으로 최적 템플릿 찾기.
 */
export function matchTemplate(
  slideType: SlideType,
  labels: string[],
  customTemplates: PPTTemplate[] = [],
): PPTTemplate {
  const allTemplates = [...customTemplates, ...BUILT_IN_TEMPLATES]

  // 1. matchLabels가 정확히 일치하는 템플릿
  for (const t of allTemplates) {
    if (t.matchType !== slideType) continue
    if (t.matchLabels) {
      const labelSet = new Set(labels)
      if (t.matchLabels.every(l => labelSet.has(l))) {
        return t
      }
    }
  }

  // 2. matchType만 일치하는 템플릿
  for (const t of allTemplates) {
    if (t.matchType === slideType && !t.matchLabels) {
      return t
    }
  }

  // 3. 기본 콘텐츠 템플릿
  return BUILT_IN_TEMPLATES.find(t => t.templateId === 'content-basic')!
}
