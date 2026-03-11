/**
 * NodeMatcher — 이전 SLA와 새 AST 노드 간 4단계 매칭.
 *
 * Stage 1: sourceId 일치
 * Stage 2: storyId + frameIndexInStory
 * Stage 3: textFingerprint (정규화된 텍스트 해시)
 * Stage 4: Symmetry Check (LCS 텍스트 유사도 + 스타일 + 위치)
 */

import type { SemanticNode, NodeFingerprint } from '../types.js'

export interface MatchResult {
  /** 매칭된 쌍: [이전 노드 ID, 새 노드 ID] */
  matched: Array<[string, string]>
  /** 매칭 안 된 이전 노드 ID (삭제됨) */
  unmatchedOld: string[]
  /** 매칭 안 된 새 노드 ID (추가됨) */
  unmatchedNew: string[]
  /** Symmetry Match로 매칭된 쌍 (별도 추적) */
  symmetryMatched: Array<[string, string, number]>
}

/**
 * 이전 노드 + 새 노드를 4단계로 매칭.
 * @param symmetryThreshold Symmetry Match 자동 수락 최소 점수 (기본 0.8)
 */
export function matchNodes(
  oldNodes: SemanticNode[],
  newNodes: SemanticNode[],
  symmetryThreshold: number = 0.8,
): MatchResult {
  const matched: Array<[string, string]> = []
  const symmetryMatched: Array<[string, string, number]> = []
  const matchedOldIds = new Set<string>()
  const matchedNewIds = new Set<string>()

  const oldFingerprints = new Map<string, SemanticNode>()
  for (const n of oldNodes) {
    oldFingerprints.set(n.id, n)
  }

  // Stage 1: sourceId 일치
  const oldBySourceId = indexBy(oldNodes, n => extractSourceId(n.id))
  for (const newNode of newNodes) {
    const sourceId = extractSourceId(newNode.id)
    const oldNode = oldBySourceId.get(sourceId)
    if (oldNode && !matchedOldIds.has(oldNode.id)) {
      matched.push([oldNode.id, newNode.id])
      matchedOldIds.add(oldNode.id)
      matchedNewIds.add(newNode.id)
    }
  }

  // Stage 2: storyId + frameIndexInStory
  const remainingOld = oldNodes.filter(n => !matchedOldIds.has(n.id))
  const remainingNew = newNodes.filter(n => !matchedNewIds.has(n.id))
  const oldByStoryFrame = indexBy(remainingOld, n =>
    n.storyId ? `${n.storyId}:${n.features.frameIndexInStory}` : '',
  )
  for (const newNode of remainingNew) {
    if (!newNode.storyId) continue
    const key = `${newNode.storyId}:${newNode.features.frameIndexInStory}`
    const oldNode = oldByStoryFrame.get(key)
    if (oldNode && !matchedOldIds.has(oldNode.id)) {
      matched.push([oldNode.id, newNode.id])
      matchedOldIds.add(oldNode.id)
      matchedNewIds.add(newNode.id)
    }
  }

  // Stage 3: textFingerprint
  const remainingOld2 = oldNodes.filter(n => !matchedOldIds.has(n.id))
  const remainingNew2 = newNodes.filter(n => !matchedNewIds.has(n.id))
  const oldByTextFp = indexBy(remainingOld2, n => computeTextFingerprint(n))
  for (const newNode of remainingNew2) {
    const fp = computeTextFingerprint(newNode)
    if (!fp) continue
    const oldNode = oldByTextFp.get(fp)
    if (oldNode && !matchedOldIds.has(oldNode.id)) {
      matched.push([oldNode.id, newNode.id])
      matchedOldIds.add(oldNode.id)
      matchedNewIds.add(newNode.id)
    }
  }

  // Stage 4: Symmetry Check
  const remainingOld3 = oldNodes.filter(n => !matchedOldIds.has(n.id))
  const remainingNew3 = newNodes.filter(n => !matchedNewIds.has(n.id))

  for (const oldNode of remainingOld3) {
    let bestNewNode: SemanticNode | null = null
    let bestScore = 0

    for (const newNode of remainingNew3) {
      if (matchedNewIds.has(newNode.id)) continue
      const score = computeSymmetryScore(oldNode, newNode)
      if (score > bestScore) {
        bestScore = score
        bestNewNode = newNode
      }
    }

    if (bestNewNode && bestScore >= symmetryThreshold) {
      matched.push([oldNode.id, bestNewNode.id])
      symmetryMatched.push([oldNode.id, bestNewNode.id, bestScore])
      matchedOldIds.add(oldNode.id)
      matchedNewIds.add(bestNewNode.id)
    }
  }

  return {
    matched,
    unmatchedOld: oldNodes.filter(n => !matchedOldIds.has(n.id)).map(n => n.id),
    unmatchedNew: newNodes.filter(n => !matchedNewIds.has(n.id)).map(n => n.id),
    symmetryMatched,
  }
}

/** 노드 fingerprint 생성 */
export function createFingerprint(node: SemanticNode): NodeFingerprint {
  return {
    sourceId: extractSourceId(node.id),
    storyId: node.storyId,
    frameIndexInStory: node.features.frameIndexInStory,
    textFingerprint: computeTextFingerprint(node),
    pageNumber: node.features.pageNumber,
  }
}

// ─── Symmetry Check ───────────────────────────────────────

/**
 * Symmetry Score = 텍스트 유사도(60%) + 스타일 일치(20%) + 위치 근접(20%)
 */
export function computeSymmetryScore(a: SemanticNode, b: SemanticNode): number {
  const textSim = lcsTextSimilarity(a.features.textContent, b.features.textContent)
  const styleSim = computeStyleMatch(a, b)
  const posSim = computePositionProximity(a, b)

  return textSim * 0.6 + styleSim * 0.2 + posSim * 0.2
}

/** LCS 기반 텍스트 유사도 (0~1) */
function lcsTextSimilarity(a: string, b: string): number {
  if (!a && !b) return 1
  if (!a || !b) return 0

  // 긴 텍스트는 앞뒤 200자만 비교
  const maxLen = 200
  const sa = a.length > maxLen * 2 ? a.slice(0, maxLen) + a.slice(-maxLen) : a
  const sb = b.length > maxLen * 2 ? b.slice(0, maxLen) + b.slice(-maxLen) : b

  const lcsLen = lcsLength(sa, sb)
  const maxPossible = Math.max(sa.length, sb.length)
  return maxPossible > 0 ? lcsLen / maxPossible : 1
}

/** LCS 길이 (O(nm) → 공간 O(min(n,m))) */
function lcsLength(a: string, b: string): number {
  if (a.length > b.length) return lcsLength(b, a)
  const m = a.length
  const n = b.length
  const prev = new Uint16Array(m + 1)
  const curr = new Uint16Array(m + 1)

  for (let j = 1; j <= n; j++) {
    for (let i = 1; i <= m; i++) {
      if (a[i - 1] === b[j - 1]) {
        curr[i] = prev[i - 1] + 1
      } else {
        curr[i] = Math.max(prev[i], curr[i - 1])
      }
    }
    prev.set(curr)
    curr.fill(0)
  }

  return prev[m]
}

/** 스타일 일치도 (0~1) */
function computeStyleMatch(a: SemanticNode, b: SemanticNode): number {
  let score = 0
  let count = 0

  // 문단 스타일
  if (a.features.dominantParagraphStyle || b.features.dominantParagraphStyle) {
    score += a.features.dominantParagraphStyle === b.features.dominantParagraphStyle ? 1 : 0
    count++
  }

  // 폰트 크기
  if (a.features.dominantFontSize > 0 || b.features.dominantFontSize > 0) {
    score += a.features.dominantFontSize === b.features.dominantFontSize ? 1 : 0
    count++
  }

  // 폰트 패밀리
  if (a.features.dominantFontFamily || b.features.dominantFontFamily) {
    score += a.features.dominantFontFamily === b.features.dominantFontFamily ? 1 : 0
    count++
  }

  // Bold
  score += a.features.hasBoldText === b.features.hasBoldText ? 1 : 0
  count++

  return count > 0 ? score / count : 0
}

/** 위치 근접도 (0~1) */
function computePositionProximity(a: SemanticNode, b: SemanticNode): number {
  // 같은 페이지 여부
  const samePage = a.features.pageNumber === b.features.pageNumber ? 1 : 0

  // 같은 regionTag
  const sameRegion = a.features.regionTag === b.features.regionTag ? 1 : 0

  // 상대 위치 유사도
  const yDiff = Math.abs(a.features.relativeYInPage - b.features.relativeYInPage)
  const ySim = Math.max(0, 1 - yDiff * 2)

  return (samePage * 0.4 + sameRegion * 0.3 + ySim * 0.3)
}

// ─── 유틸 ─────────────────────────────────────────────────

/** sn-{sourceId} 에서 sourceId 추출 */
function extractSourceId(nodeId: string): string {
  return nodeId.startsWith('sn-') ? nodeId.slice(3) : nodeId
}

/** 텍스트 기반 fingerprint: 정규화 후 간단한 해시 */
function computeTextFingerprint(node: SemanticNode): string {
  const text = node.features.textContent
  if (!text || text.length === 0) return ''

  // 공백 정규화 + 앞뒤 100자
  const normalized = text.replace(/\s+/g, ' ').trim()
  const key = normalized.length > 200
    ? normalized.slice(0, 100) + '|' + normalized.slice(-100)
    : normalized

  return simpleHash(key)
}

/** djb2 해시 */
function simpleHash(str: string): string {
  let hash = 5381
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash + str.charCodeAt(i)) & 0x7fffffff
  }
  return hash.toString(36)
}

/** 배열을 키 함수로 인덱싱 (첫 번째 매칭만) */
function indexBy<T>(items: T[], keyFn: (item: T) => string): Map<string, T> {
  const map = new Map<string, T>()
  for (const item of items) {
    const key = keyFn(item)
    if (key && !map.has(key)) {
      map.set(key, item)
    }
  }
  return map
}
