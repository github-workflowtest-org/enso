import { computeNodeColor } from '@/composables/nodeColors'
import { ComputedValueRegistry, type ExpressionInfo } from '@/stores/project/computedValueRegistry'
import { SuggestionDb, type Group } from '@/stores/suggestionDatabase'
import type { SuggestionEntry } from '@/stores/suggestionDatabase/entry'
import { assert } from '@/util/assert'
import { Ast, RawAst } from '@/util/ast'
import type { AstId, NodeMetadata } from '@/util/ast/abstract'
import { MutableModule, autospaced, subtrees } from '@/util/ast/abstract'
import { AliasAnalyzer } from '@/util/ast/aliasAnalysis'
import { nodeFromAst } from '@/util/ast/node'
import { MappedKeyMap, MappedSet } from '@/util/containers'
import { arrayEquals, tryGetIndex } from '@/util/data/array'
import { Vec2 } from '@/util/data/vec2'
import { ReactiveDb, ReactiveIndex, ReactiveMapping } from '@/util/database/reactiveDb'
import { syncSet } from '@/util/reactivity'
import * as set from 'lib0/set'
import { methodPointerEquals, type MethodCall, type StackItem } from 'shared/languageServerTypes'
import type { Opt } from 'shared/util/data/opt'
import type { ExternalId, SourceRange, VisualizationMetadata } from 'shared/yjsModel'
import { isUuid, sourceRangeKey, visMetadataEquals } from 'shared/yjsModel'
import { reactive, ref, type Ref } from 'vue'

export interface MethodCallInfo {
  methodCall: MethodCall
  methodCallSource: Ast.AstId
  suggestion: SuggestionEntry
}

export interface BindingInfo {
  identifier: string
  usages: Set<AstId>
}

export class BindingsDb {
  bindings = new ReactiveDb<AstId, BindingInfo>()
  identifierToBindingId = new ReactiveIndex(this.bindings, (id, info) => [[info.identifier, id]])

  readFunctionAst(
    func: Ast.Function,
    rawFunc: RawAst.Tree.Function,
    moduleCode: string,
    getSpan: (id: AstId) => SourceRange | undefined,
  ) {
    // TODO[ao]: Rename 'alias' to 'binding' in AliasAnalyzer and it's more accurate term.
    const analyzer = new AliasAnalyzer(moduleCode, rawFunc)
    analyzer.process()

    const [bindingRangeToTree, bindingIdToRange] = BindingsDb.rangeMappings(func, analyzer, getSpan)

    // Remove old keys.
    for (const key of this.bindings.keys()) {
      const range = bindingIdToRange.get(key)
      if (range == null || !analyzer.aliases.has(range)) {
        this.bindings.delete(key)
      }
    }

    // Add or update bindings.
    for (const [bindingRange, usagesRanges] of analyzer.aliases) {
      const aliasAst = bindingRangeToTree.get(bindingRange)
      assert(aliasAst != null)
      if (aliasAst == null) continue
      const aliasAstId = aliasAst.id
      const info = this.bindings.get(aliasAstId)
      if (info == null) {
        function* usageIds() {
          for (const usageRange of usagesRanges) {
            const usageAst = bindingRangeToTree.get(usageRange)
            assert(usageAst != null)
            if (usageAst != null) yield usageAst.id
          }
        }
        this.bindings.set(aliasAstId, {
          identifier: aliasAst.code(),
          usages: new Set(usageIds()),
        })
      } else {
        const newIdentifier = aliasAst.code()
        if (info.identifier != newIdentifier) info.identifier = newIdentifier
        // Remove old usages.
        for (const usage of info.usages) {
          const range = bindingIdToRange.get(usage)
          if (range == null || !usagesRanges.has(range)) info.usages.delete(usage)
        }
        // Add or update usages.
        for (const usageRange of usagesRanges) {
          const usageAst = bindingRangeToTree.get(usageRange)
          if (usageAst != null && !info.usages.has(usageAst.id)) {
            info.usages.add(usageAst.id)
          }
        }
      }
    }
  }

  /** Create mappings between bindings' ranges and AST
   *
   * The AliasAnalyzer is general and returns ranges, but we're interested in AST nodes. This
   * method creates mappings in both ways. For given range, only the shallowest AST node will be
   * assigned (RawAst.Tree.Identifier, not RawAst.Token.Identifier).
   */
  private static rangeMappings(
    ast: Ast.Ast,
    analyzer: AliasAnalyzer,
    getSpan: (id: AstId) => SourceRange | undefined,
  ): [MappedKeyMap<SourceRange, Ast.Ast>, Map<AstId, SourceRange>] {
    const bindingRangeToTree = new MappedKeyMap<SourceRange, Ast.Ast>(sourceRangeKey)
    const bindingIdToRange = new Map<AstId, SourceRange>()
    const bindingRanges = new MappedSet(sourceRangeKey)
    for (const [binding, usages] of analyzer.aliases) {
      bindingRanges.add(binding)
      for (const usage of usages) bindingRanges.add(usage)
    }
    ast.visitRecursiveAst((ast) => {
      const span = getSpan(ast.id)
      assert(span != null)
      if (bindingRanges.has(span)) {
        bindingRangeToTree.set(span, ast)
        bindingIdToRange.set(ast.id, span)
        return false
      }
      return true
    })
    return [bindingRangeToTree, bindingIdToRange]
  }
}

export class GraphDb {
  nodeIdToNode = new ReactiveDb<NodeId, Node>()
  private highestZIndex = 0
  private readonly idToExternalMap = reactive(new Map<Ast.AstId, ExternalId>())
  private readonly idFromExternalMap = reactive(new Map<ExternalId, Ast.AstId>())
  private bindings = new BindingsDb()
  private currentFunction: AstId | undefined = undefined

  constructor(
    private suggestionDb: SuggestionDb,
    private groups: Ref<Group[]>,
    private valuesRegistry: ComputedValueRegistry,
  ) {}

  private nodeIdToOuterExprIds = new ReactiveIndex(this.nodeIdToNode, (id, entry) => {
    return [[id, entry.outerExpr.id]]
  })

  private nodeIdToPatternExprIds = new ReactiveIndex(this.nodeIdToNode, (id, entry) => {
    const exprs: AstId[] = []
    if (entry.pattern) entry.pattern.visitRecursiveAst((ast) => void exprs.push(ast.id))
    return Array.from(exprs, (expr) => [id, expr])
  })

  private nodeIdToExprIds = new ReactiveIndex(this.nodeIdToNode, (id, entry) => {
    const exprs: AstId[] = []
    entry.innerExpr.visitRecursiveAst((ast) => void exprs.push(ast.id))
    return Array.from(exprs, (expr) => [id, expr])
  })

  connections = new ReactiveIndex(this.bindings.bindings, (alias, info) => {
    const srcNode = this.getPatternExpressionNodeId(alias)
    // Display connection starting from existing node.
    //TODO[ao]: When implementing input nodes, they should be taken into account here.
    if (srcNode == null) return []
    return Array.from(this.connectionsFromBindings(info, alias, srcNode))
  })

  /** Same as {@link GraphDb.connections}, but also includes connections without source node,
   * e.g. input arguments of the collapsed function.
   */
  allConnections = new ReactiveIndex(this.bindings.bindings, (alias, info) => {
    const srcNode = this.getPatternExpressionNodeId(alias)
    return Array.from(this.connectionsFromBindings(info, alias, srcNode))
  })

  nodeDependents = new ReactiveIndex(this.nodeIdToNode, (id) => {
    const result = new Set<NodeId>()
    for (const port of this.getNodeUsages(id)) {
      const portNode = this.getExpressionNodeId(port)
      if (portNode != null) result.add(portNode)
    }
    return Array.from(result, (target) => [id, target])
  })

  private *connectionsFromBindings(
    info: BindingInfo,
    alias: AstId,
    srcNode: NodeId | undefined,
  ): Generator<[AstId, AstId]> {
    for (const usage of info.usages) {
      const targetNode = this.getExpressionNodeId(usage)
      // Display only connections to existing targets and different than source node.
      if (targetNode == null || targetNode === srcNode) continue
      yield [alias, usage]
    }
  }

  /** Output port bindings of the node. Lists all bindings that can be dragged out from a node. */
  nodeOutputPorts = new ReactiveIndex(this.nodeIdToNode, (id, entry) => {
    if (entry.pattern == null) return []
    const ports = new Set<AstId>()
    entry.pattern.visitRecursiveAst((ast) => {
      if (this.bindings.bindings.has(ast.id)) {
        ports.add(ast.id)
        return false
      }
      return true
    })
    return Array.from(ports, (port) => [id, port])
  })

  nodeMainSuggestion = new ReactiveMapping(this.nodeIdToNode, (_id, entry) => {
    const expressionInfo = this.getExpressionInfo(entry.innerExpr.id)
    const method = expressionInfo?.methodCall?.methodPointer
    if (method == null) return
    const suggestionId = this.suggestionDb.findByMethodPointer(method)
    if (suggestionId == null) return
    return this.suggestionDb.get(suggestionId)
  })

  nodeColor = new ReactiveMapping(this.nodeIdToNode, (id, entry) => {
    if (entry.colorOverride != null) return entry.colorOverride
    return computeNodeColor(
      () => tryGetIndex(this.groups.value, this.nodeMainSuggestion.lookup(id)?.groupIndex),
      () => this.getExpressionInfo(id)?.typename,
    )
  })

  getNodeFirstOutputPort(id: NodeId | undefined): AstId | undefined {
    return id ? set.first(this.nodeOutputPorts.lookup(id)) ?? this.idFromExternal(id) : undefined
  }

  *getNodeUsages(id: NodeId): IterableIterator<AstId> {
    const outputPorts = this.nodeOutputPorts.lookup(id)
    for (const outputPort of outputPorts) {
      yield* this.connections.lookup(outputPort)
    }
  }

  getOuterExpressionNodeId(exprId: AstId | undefined): NodeId | undefined {
    return exprId && set.first(this.nodeIdToOuterExprIds.reverseLookup(exprId))
  }

  getExpressionNodeId(exprId: AstId | undefined): NodeId | undefined {
    return exprId && set.first(this.nodeIdToExprIds.reverseLookup(exprId))
  }

  getPatternExpressionNodeId(exprId: AstId | undefined): NodeId | undefined {
    return exprId && set.first(this.nodeIdToPatternExprIds.reverseLookup(exprId))
  }

  getIdentDefiningNode(ident: string): NodeId | undefined {
    const binding = set.first(this.bindings.identifierToBindingId.lookup(ident))
    return this.getPatternExpressionNodeId(binding)
  }

  getExpressionInfo(id: AstId | ExternalId | undefined): ExpressionInfo | undefined {
    const externalId = isUuid(id) ? id : this.idToExternal(id)
    return externalId && this.valuesRegistry.getExpressionInfo(externalId)
  }

  getOutputPortIdentifier(source: AstId | undefined): string | undefined {
    return source ? this.bindings.bindings.get(source)?.identifier : undefined
  }

  allIdentifiers(): string[] {
    return [...this.bindings.identifierToBindingId.allForward()].map(([ident, _]) => ident)
  }

  identifierUsed(ident: string): boolean {
    return this.bindings.identifierToBindingId.hasKey(ident)
  }

  nodeIds(): IterableIterator<NodeId> {
    return this.nodeIdToNode.keys()
  }

  isNodeId(externalId: ExternalId): boolean {
    return this.nodeIdToNode.has(asNodeId(externalId))
  }

  isKnownFunctionCall(id: AstId): boolean {
    return this.getMethodCallInfo(id) != null
  }

  getMethodCall(id: AstId): MethodCall | undefined {
    const info = this.getExpressionInfo(id)
    if (info == null) return
    return (
      info.methodCall ?? (info.payload.type === 'Value' ? info.payload.functionSchema : undefined)
    )
  }

  getMethodCallInfo(id: AstId): MethodCallInfo | undefined {
    const info = this.getExpressionInfo(id)
    if (info == null) return
    const payloadFuncSchema =
      info.payload.type === 'Value' ? info.payload.functionSchema : undefined
    const methodCall = info.methodCall ?? payloadFuncSchema
    if (methodCall == null) return
    const suggestionId = this.suggestionDb.findByMethodPointer(methodCall.methodPointer)
    if (suggestionId == null) return
    const suggestion = this.suggestionDb.get(suggestionId)
    if (suggestion == null) return
    return { methodCall, methodCallSource: id, suggestion }
  }

  getNodeColorStyle(id: NodeId): string {
    return this.nodeColor.lookup(id) ?? 'var(--node-color-no-type)'
  }

  moveNodeToTop(id: NodeId) {
    const node = this.nodeIdToNode.get(id)
    if (!node) return
    node.zIndex = this.highestZIndex + 1
    this.highestZIndex++
  }

  /** Get the method name from the stack item. */
  stackItemToMethodName(item: StackItem): string | undefined {
    switch (item.type) {
      case 'ExplicitCall': {
        return item.methodPointer.name
      }
      case 'LocalCall': {
        const exprId = item.expressionId
        const info = this.valuesRegistry.getExpressionInfo(exprId)
        return info?.methodCall?.methodPointer.name
      }
    }
  }

  /**
   * Note that the `dirtyNodes` are visited and updated in the order that they appear in the module AST, irrespective of
   * the iteration order of the `dirtyNodes` set.
   **/
  readFunctionAst(
    functionAst_: Ast.Function,
    rawFunction: RawAst.Tree.Function,
    moduleCode: string,
    getSpan: (id: AstId) => SourceRange | undefined,
    dirtyNodes: Set<AstId>,
  ) {
    const functionChanged = functionAst_.id !== this.currentFunction
    // Note: `subtrees` returns a set that has the iteration order of all `Ast.ID`s in the order they appear in the
    // module AST. This is important to ensure that nodes are updated in the correct order.
    const knownDirtySubtrees = functionChanged ? null : subtrees(functionAst_.module, dirtyNodes)
    const subtreeDirty = (id: AstId) => !knownDirtySubtrees || knownDirtySubtrees.has(id)
    this.currentFunction = functionAst_.id
    const currentNodeIds = new Set<NodeId>()
    for (const nodeAst of functionAst_.bodyExpressions()) {
      const newNode = nodeFromAst(nodeAst)
      if (!newNode) continue
      const nodeId = asNodeId(newNode.rootExpr.externalId)
      const node = this.nodeIdToNode.get(nodeId)
      currentNodeIds.add(nodeId)
      if (node == null) {
        const nodeMeta = newNode.rootExpr.nodeMetadata
        const pos = nodeMeta.get('position') ?? { x: 0, y: 0 }
        const metadataFields = {
          position: new Vec2(pos.x, pos.y),
          vis: nodeMeta.get('visualization'),
          colorOverride: nodeMeta.get('colorOverride'),
        }
        this.nodeIdToNode.set(nodeId, { ...newNode, ...metadataFields, zIndex: this.highestZIndex })
      } else {
        const {
          outerExpr,
          pattern,
          rootExpr,
          innerExpr,
          primarySubject,
          prefixes,
          documentation,
          conditionalPorts,
        } = newNode
        const differentOrDirty = (a: Ast.Ast | undefined, b: Ast.Ast | undefined) =>
          a?.id !== b?.id || (a && subtreeDirty(a.id))
        if (differentOrDirty(node.outerExpr, outerExpr)) node.outerExpr = outerExpr
        if (differentOrDirty(node.pattern, pattern)) node.pattern = pattern
        if (differentOrDirty(node.rootExpr, rootExpr)) node.rootExpr = rootExpr
        if (differentOrDirty(node.innerExpr, innerExpr)) node.innerExpr = innerExpr
        if (node.primarySubject !== primarySubject) node.primarySubject = primarySubject
        if (node.documentation !== documentation) node.documentation = documentation
        if (
          Object.entries(node.prefixes).some(
            ([k, v]) => prefixes[k as keyof typeof node.prefixes] !== v,
          )
        )
          node.prefixes = prefixes
        syncSet(node.conditionalPorts, conditionalPorts)
        // Ensure new fields can't be added to `NodeAstData` without this code being updated.
        const _allFieldsHandled = {
          outerExpr,
          pattern,
          rootExpr,
          innerExpr,
          primarySubject,
          prefixes,
          documentation,
          conditionalPorts,
        } satisfies NodeDataFromAst
      }
    }
    for (const nodeId of this.nodeIdToNode.keys()) {
      if (!currentNodeIds.has(nodeId)) {
        this.nodeIdToNode.delete(nodeId)
      }
    }
    this.updateExternalIds(functionAst_)
    this.bindings.readFunctionAst(functionAst_, rawFunction, moduleCode, getSpan)
    return currentNodeIds
  }

  updateExternalIds(topLevel: Ast.Ast) {
    const idToExternalNew = new Map()
    const idFromExternalNew = new Map()
    topLevel.visitRecursiveAst((ast) => {
      idToExternalNew.set(ast.id, ast.externalId)
      idFromExternalNew.set(ast.externalId, ast.id)
    })
    const updateMap = <K, V>(map: Map<K, V>, newMap: Map<K, V>) => {
      for (const key of map.keys()) if (!newMap.has(key)) map.delete(key)
      for (const [key, value] of newMap) map.set(key, value)
    }
    updateMap(this.idToExternalMap, idToExternalNew)
    updateMap(this.idFromExternalMap, idFromExternalNew)
  }

  updateMetadata(astId: Ast.AstId, changes: NodeMetadata) {
    const node = this.nodeByRootAstId(astId)
    if (!node) return
    const newPos = changes.get('position')
    const newPosVec = newPos && new Vec2(newPos.x, newPos.y)
    if (newPosVec && !newPosVec.equals(node.position)) node.position = newPosVec
    if (changes.has('visualization')) {
      const newVis = changes.get('visualization')
      if (!visMetadataEquals(newVis, node.vis)) node.vis = newVis
    }
    if (changes.has('colorOverride')) {
      node.colorOverride = changes.get('colorOverride')
    }
  }

  nodeByRootAstId(astId: Ast.AstId): Node | undefined {
    const nodeId = asNodeId(this.idToExternal(astId))
    return nodeId != null ? this.nodeIdToNode.get(nodeId) : undefined
  }

  /** Get the ID of the `Ast` corresponding to the given `ExternalId` as of the last synchronization. */
  idFromExternal(id: ExternalId | undefined): AstId | undefined {
    return id ? this.idFromExternalMap.get(id) : id
  }
  /** Get the external ID corresponding to the given `AstId` as of the last synchronization.
   *
   *  Note that if there is an edit in progress (i.e. a `MutableModule` containing changes that haven't been committed
   *  and observed), this may be different from the value return by calling `toExternal` on the edited `Ast` object.
   *
   *  When performing an edit and obtaining an ID to be sent to the engine, always use `Ast.toExternal`, which gives the
   *  ID the node will have once it is committed.
   *
   *  When looking up a node in data previously obtained from the engine, the choice depends on the situation:
   *  - If the data being looked up should be inherited from the previous holder of the `ExternalId`, use the current
   *    `toExternal`.
   *  - If the data should be associated with the `Ast` that the engine was referring to, use `idToExternal`.
   *  Either choice is an approximation that will be used until the engine provides an update after processing the edit.
   */
  idToExternal(id: AstId | undefined): ExternalId | undefined {
    return id ? this.idToExternalMap.get(id) : undefined
  }

  static Mock(registry = ComputedValueRegistry.Mock(), db = new SuggestionDb()): GraphDb {
    return new GraphDb(db, ref([]), registry)
  }

  mockNode(binding: string, id: NodeId, code?: string): Node {
    const edit = MutableModule.Transient()
    const pattern = Ast.parse(binding, edit)
    const expression = Ast.parse(code ?? '0', edit)
    const outerExpr = Ast.Assignment.concrete(
      edit,
      autospaced(pattern),
      { node: Ast.Token.new('='), whitespace: ' ' },
      { node: expression, whitespace: ' ' },
    )

    const node: Node = {
      ...baseMockNode,
      outerExpr,
      pattern,
      rootExpr: Ast.parse(code ?? '0'),
      innerExpr: Ast.parse(code ?? '0'),
      zIndex: this.highestZIndex,
    }
    const bindingId = pattern.id
    this.nodeIdToNode.set(id, node)
    this.bindings.bindings.set(bindingId, { identifier: binding, usages: new Set() })
    return node
  }
}

declare const brandNodeId: unique symbol

/** An unique node identifier, shared across all clients. It is the ExternalId of node's root expression. */
export type NodeId = string & ExternalId & { [brandNodeId]: never }
export function asNodeId(id: ExternalId): NodeId
export function asNodeId(id: ExternalId | undefined): NodeId | undefined
export function asNodeId(id: ExternalId | undefined): NodeId | undefined {
  return id != null ? (id as NodeId) : undefined
}

export interface NodeDataFromAst {
  /** The outer expression, usually an assignment expression (`a = b`). */
  outerExpr: Ast.Ast
  /** The left side of the assignment experssion, if `outerExpr` is an assignment expression. */
  pattern: Ast.Ast | undefined
  /** The value of the node. The right side of the assignment, if `outerExpr` is an assignment
   * expression, else the entire `outerExpr`. */
  rootExpr: Ast.Ast
  /** The expression displayed by the node. This is `rootExpr`, minus the prefixes, which are in
   * `prefixes`. */
  innerExpr: Ast.Ast
  /** Prefixes that are present in `rootExpr` but omitted in `innerExpr` to ensure a clean output.
   */
  prefixes: Record<'enableRecording', Ast.AstId[] | undefined>
  /** A child AST in a syntactic position to be a self-argument input to the node. */
  primarySubject: Ast.AstId | undefined
  documentation: string | undefined
  /** Ports that are not targetable by default; they can be targeted while holding the modifier key. */
  conditionalPorts: Set<Ast.AstId>
}

export interface NodeDataFromMetadata {
  position: Vec2
  vis: Opt<VisualizationMetadata>
  colorOverride: Opt<string>
}

export interface Node extends NodeDataFromAst, NodeDataFromMetadata {
  zIndex: number
}

const baseMockNode = {
  position: Vec2.Zero,
  vis: undefined,
  prefixes: { enableRecording: undefined },
  primarySubject: undefined,
  documentation: undefined,
  colorOverride: undefined,
  conditionalPorts: new Set(),
} satisfies Partial<Node>

export function mathodCallEquals(a: MethodCall | undefined, b: MethodCall | undefined): boolean {
  return (
    a === b ||
    (a != null &&
      b != null &&
      methodPointerEquals(a.methodPointer, b.methodPointer) &&
      arrayEquals(a.notAppliedArguments, b.notAppliedArguments))
  )
}
