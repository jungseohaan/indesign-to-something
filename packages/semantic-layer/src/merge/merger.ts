/**
 * Merger — 이전 SemanticLayer + 새 AST → 업데이트된 SemanticLayer.
 * manualOverride 보존, Symmetry Match 상속.
 */

import type {
  SemanticLayer,
  SemanticNode,
  SemanticRelation,
  DeletedNode,
  MergeHistoryEntry,
  ClassificationRule,
  RelationRule,
} from '../types.js'
import type { ASTAdapter } from '../adapter/ast-adapter.js'
import { extractFeatures } from '../core/feature-extractor.js'
import { classifyNodes } from '../core/rule-classifier.js'
import { buildRelations } from '../core/relation-builder.js'
import { matchNodes, createFingerprint } from './node-matcher.js'

export interface MergeOptions {
  rules: ClassificationRule[]
  relationRules: RelationRule[]
  schemaId: string
  symmetryThreshold?: number
}

/**
 * 새 AST에 기반하여 기존 SemanticLayer를 업데이트.
 * 수동 레이블, confidence, 메타데이터 보존.
 */
export function mergeLayer(
  previous: SemanticLayer | null,
  adapter: ASTAdapter,
  options: MergeOptions,
): SemanticLayer {
  const newNodes = extractFeatures(adapter)
  const astHash = adapter.getDocumentHash()
  const now = new Date().toISOString()

  // 이전 레이어가 없으면 새로 생성
  if (!previous) {
    const classified = classifyNodes(newNodes, options.rules)
    const relations = buildRelations(classified, options.relationRules)
    return {
      version: '1.0.0',
      schemaId: options.schemaId,
      sourceAstHash: astHash,
      createdAt: now,
      modifiedAt: now,
      mergeHistory: [],
      nodes: classified,
      relations,
      deletedNodes: [],
    }
  }

  // 매칭
  const threshold = options.symmetryThreshold ?? 0.8
  const matchResult = matchNodes(previous.nodes, newNodes, threshold)

  // 매칭된 노드: 이전 레이블 보존
  const mergedNodes: SemanticNode[] = []

  for (const [oldId, newId] of matchResult.matched) {
    const oldNode = previous.nodes.find(n => n.id === oldId)!
    const newNode = newNodes.find(n => n.id === newId)!

    if (oldNode.manualOverride) {
      // 수동 레이블 보존 (features만 업데이트)
      mergedNodes.push({
        ...newNode,
        label: oldNode.label,
        confidence: oldNode.confidence,
        appliedRule: oldNode.appliedRule,
        manualOverride: true,
        children: oldNode.children,
        metadata: { ...oldNode.metadata, ...newNode.metadata },
      })
    } else {
      // Symmetry Match인 경우 이전 레이블 상속
      const isSymmetry = matchResult.symmetryMatched.some(
        ([oId, nId]) => oId === oldId && nId === newId,
      )
      if (isSymmetry && oldNode.label !== 'UNKNOWN') {
        mergedNodes.push({
          ...newNode,
          label: oldNode.label,
          confidence: oldNode.confidence * 0.9, // 약간 감소
          appliedRule: oldNode.appliedRule,
          manualOverride: false,
          metadata: { ...newNode.metadata, symmetryInherited: true },
        })
      } else {
        mergedNodes.push(newNode)
      }
    }
  }

  // 새로 추가된 노드
  for (const newId of matchResult.unmatchedNew) {
    const newNode = newNodes.find(n => n.id === newId)!
    mergedNodes.push(newNode)
  }

  // 삭제된 노드 기록
  const newDeletedNodes: DeletedNode[] = [...previous.deletedNodes]
  for (const oldId of matchResult.unmatchedOld) {
    const oldNode = previous.nodes.find(n => n.id === oldId)!
    newDeletedNodes.push({
      id: oldNode.id,
      label: oldNode.label,
      manualOverride: oldNode.manualOverride,
      deletedAt: now,
      fingerprint: createFingerprint(oldNode),
    })
  }

  // 규칙 분류 (manualOverride와 symmetryInherited 제외)
  const classified = classifyNodes(mergedNodes, options.rules)

  // 관계 재빌드
  const relations = buildRelations(classified, options.relationRules)

  // merge history
  const historyEntry: MergeHistoryEntry = {
    timestamp: now,
    previousHash: previous.sourceAstHash,
    stats: {
      matched: matchResult.matched.length,
      manualPreserved: matchResult.matched.filter(([oldId]) =>
        previous.nodes.find(n => n.id === oldId)?.manualOverride,
      ).length,
      reclassified: classified.filter(n =>
        !n.manualOverride && n.label !== 'UNKNOWN' && !(n.metadata as any)?.symmetryInherited,
      ).length,
      added: matchResult.unmatchedNew.length,
      deleted: matchResult.unmatchedOld.length,
      symmetryMatched: matchResult.symmetryMatched.length,
    },
  }

  return {
    version: '1.0.0',
    schemaId: options.schemaId,
    sourceAstHash: astHash,
    previousAstHash: previous.sourceAstHash,
    createdAt: previous.createdAt,
    modifiedAt: now,
    mergeHistory: [...previous.mergeHistory, historyEntry],
    nodes: classified,
    relations,
    deletedNodes: newDeletedNodes,
  }
}
