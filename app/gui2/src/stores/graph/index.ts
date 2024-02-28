import { nonDictatedPlacement } from '@/components/ComponentBrowser/placement'
import type { PortId } from '@/providers/portInfo'
import type { WidgetUpdate } from '@/providers/widgetRegistry'
import { GraphDb, asNodeId, type NodeId } from '@/stores/graph/graphDatabase'
import {
  addImports,
  filterOutRedundantImports,
  readImports,
  type RequiredImport,
} from '@/stores/graph/imports'
import { useProjectStore } from '@/stores/project'
import { useSuggestionDbStore } from '@/stores/suggestionDatabase'
import { assert, bail } from '@/util/assert'
import { Ast, RawAst, visitRecursive } from '@/util/ast'
import type {
  AstId,
  Module,
  ModuleUpdate,
  NodeMetadata,
  NodeMetadataFields,
} from '@/util/ast/abstract'
import { MutableModule, isIdentifier } from '@/util/ast/abstract'
import { partition } from '@/util/data/array'
import type { Opt } from '@/util/data/opt'
import { Rect } from '@/util/data/rect'
import { Vec2 } from '@/util/data/vec2'
import { map, set } from 'lib0'
import { iteratorFilter } from 'lib0/iterator'
import { defineStore } from 'pinia'
import { SourceDocument } from 'shared/ast/sourceDocument'
import type { ExpressionUpdate, StackItem } from 'shared/languageServerTypes'
import type {
  LocalOrigin,
  SourceRangeKey,
  VisualizationIdentifier,
  VisualizationMetadata,
} from 'shared/yjsModel'
import { defaultLocalOrigin, sourceRangeKey, visMetadataEquals } from 'shared/yjsModel'
import { computed, markRaw, reactive, ref, toRef, watch, type ShallowRef } from 'vue'

export { type Node, type NodeId } from '@/stores/graph/graphDatabase'

export interface NodeEditInfo {
  id: NodeId
  initialCursorPos: number
}

export class PortViewInstance {
  constructor(
    public rect: ShallowRef<Rect | undefined>,
    public nodeId: NodeId,
    public onUpdate: (update: WidgetUpdate) => void,
  ) {}
}

export const useGraphStore = defineStore('graph', () => {
  const proj = useProjectStore()
  const suggestionDb = useSuggestionDbStore()

  proj.setObservedFileName('Main.enso')

  const syncModule = computed(() => proj.module && new MutableModule(proj.module.doc.ydoc))

  const nodeRects = reactive(new Map<NodeId, Rect>())
  const vizRects = reactive(new Map<NodeId, Rect>())
  // The currently visible nodes' areas (including visualization).
  const visibleNodeAreas = computed(() => {
    const existing = iteratorFilter(nodeRects.entries(), ([id]) => db.nodeIdToNode.has(id))
    return Array.from(existing, ([id, rect]) => vizRects.get(id) ?? rect)
  })

  const db = new GraphDb(
    suggestionDb.entries,
    toRef(suggestionDb, 'groups'),
    proj.computedValueRegistry,
  )
  const portInstances = reactive(new Map<PortId, Set<PortViewInstance>>())
  const editedNodeInfo = ref<NodeEditInfo>()
  const methodAst = ref<Ast.Function>()

  const unconnectedEdge = ref<UnconnectedEdge>()

  const moduleSource = reactive(SourceDocument.Empty())
  const moduleRoot = ref<Ast.Ast>()
  const topLevel = ref<Ast.BodyBlock>()

  let disconnectSyncModule: undefined | (() => void)
  watch(syncModule, (syncModule) => {
    if (!syncModule) return
    let moduleChanged = true
    disconnectSyncModule?.()
    const handle = syncModule.observe((update) => {
      moduleSource.applyUpdate(syncModule, update)
      handleModuleUpdate(syncModule, moduleChanged, update)
      moduleChanged = false
    })
    disconnectSyncModule = () => {
      syncModule.unobserve(handle)
      moduleSource.clear()
    }
  })

  let toRaw = new Map<SourceRangeKey, RawAst.Tree.Function>()
  function handleModuleUpdate(module: Module, moduleChanged: boolean, update: ModuleUpdate) {
    const root = module.root()
    if (!root) return
    moduleRoot.value = root
    if (root instanceof Ast.BodyBlock) topLevel.value = root
    // We can cast maps of unknown metadata fields to `NodeMetadata` because all `NodeMetadata` fields are optional.
    const nodeMetadataUpdates = update.metadataUpdated as any as {
      id: AstId
      changes: NodeMetadata
    }[]
    const dirtyNodeSet = new Set(update.nodesUpdated)
    if (moduleChanged || dirtyNodeSet.size !== 0) {
      db.updateExternalIds(root)
      toRaw = new Map()
      visitRecursive(Ast.parseEnso(moduleSource.text), (node) => {
        if (node.type === RawAst.Tree.Type.Function) {
          const start = node.whitespaceStartInCodeParsed + node.whitespaceLengthInCodeParsed
          const end = start + node.childrenLengthInCodeParsed
          toRaw.set(sourceRangeKey([start, end]), node)
          return false
        }
        return true
      })
      updateState(dirtyNodeSet)
    }
    if (nodeMetadataUpdates.length !== 0) {
      for (const { id, changes } of nodeMetadataUpdates) db.updateMetadata(id, changes)
    }
  }

  function updateState(dirtyNodes?: Set<AstId>) {
    const module = proj.module
    if (!module) return
    const textContentLocal = moduleSource.text
    if (!textContentLocal) return
    if (!syncModule.value) return
    methodAst.value = methodAstInModule(syncModule.value)
    if (methodAst.value) {
      const methodSpan = moduleSource.getSpan(methodAst.value.id)
      assert(methodSpan != null)
      const rawFunc = toRaw.get(sourceRangeKey(methodSpan))
      assert(rawFunc != null)
      db.readFunctionAst(
        methodAst.value,
        rawFunc,
        textContentLocal,
        (id) => moduleSource.getSpan(id),
        dirtyNodes ?? new Set(),
      )
    }
  }

  function methodAstInModule(mod: Module) {
    const topLevel = mod.root()
    if (!topLevel) return
    assert(topLevel instanceof Ast.BodyBlock)
    return getExecutedMethodAst(topLevel, proj.executionContext.getStackTop(), db)
  }

  function generateUniqueIdent() {
    for (;;) {
      const ident = randomIdent()
      if (!db.identifierUsed(ident)) return ident
    }
  }

  const edges = computed(() => {
    const disconnectedEdgeTarget = unconnectedEdge.value?.disconnectedEdgeTarget
    const edges = []
    for (const [target, sources] of db.connections.allReverse()) {
      if ((target as string as PortId) === disconnectedEdgeTarget) continue
      for (const source of sources) {
        edges.push({ source, target })
      }
    }
    if (unconnectedEdge.value) {
      edges.push({
        source: unconnectedEdge.value.source,
        target: unconnectedEdge.value.target,
      })
    }
    return edges
  })

  const connectedEdges = computed(() => {
    return edges.value.filter<ConnectedEdge>(isConnected)
  })

  function createEdgeFromOutput(source: Ast.AstId) {
    unconnectedEdge.value = { source, target: undefined }
  }

  function disconnectSource(edge: Edge) {
    if (!edge.target) return
    unconnectedEdge.value = {
      source: undefined,
      target: edge.target,
      disconnectedEdgeTarget: edge.target,
    }
  }

  function disconnectTarget(edge: Edge) {
    if (!edge.source || !edge.target) return
    unconnectedEdge.value = {
      source: edge.source,
      target: undefined,
      disconnectedEdgeTarget: edge.target,
    }
  }

  function clearUnconnected() {
    unconnectedEdge.value = undefined
  }

  function createNode(
    position: Vec2,
    expression: string,
    metadata: NodeMetadataFields = {},
    withImports: RequiredImport[] | undefined = undefined,
  ): Opt<NodeId> {
    const method = syncModule.value ? methodAstInModule(syncModule.value) : undefined
    if (!method) {
      console.error(`BUG: Cannot add node: No current function.`)
      return
    }
    const ident = generateUniqueIdent()
    metadata.position = { x: position.x, y: position.y }
    return edit((edit) => {
      if (withImports) addMissingImports(edit, withImports)
      const rhs = Ast.parse(expression, edit)
      rhs.setNodeMetadata(metadata)
      const assignment = Ast.Assignment.new(edit, ident, rhs)
      edit.getVersion(method).bodyAsBlock().push(assignment)
      return asNodeId(rhs.id)
    })
  }

  function addMissingImports(edit: MutableModule, newImports: RequiredImport[]) {
    if (!newImports.length) return
    const topLevel = edit.getVersion(moduleRoot.value!)
    if (!(topLevel instanceof Ast.MutableBodyBlock)) {
      console.error(`BUG: Cannot add required imports: No BodyBlock module root.`)
      return
    }
    const existingImports = readImports(topLevel)
    const importsToAdd = filterOutRedundantImports(existingImports, newImports)
    if (!importsToAdd.length) return
    addImports(edit.getVersion(topLevel), importsToAdd)
  }

  function deleteNodes(ids: NodeId[]) {
    edit(
      (edit) => {
        for (const id of ids) {
          const node = db.nodeIdToNode.get(id)
          if (!node) continue
          const outerExpr = edit.tryGet(node.outerExprId)
          if (outerExpr) Ast.deleteFromParentBlock(outerExpr)
          nodeRects.delete(id)
        }
      },
      true,
      true,
    )
  }

  function setNodeContent(id: NodeId, content: string) {
    const node = db.nodeIdToNode.get(id)
    if (!node) return
    edit((edit) => {
      edit.getVersion(node.rootSpan).syncToCode(content)
    })
  }

  function transact(fn: () => void) {
    syncModule.value!.transact(fn)
  }

  function stopCapturingUndo() {
    proj.stopCapturingUndo()
  }

  function setNodePosition(nodeId: NodeId, position: Vec2) {
    const nodeAst = syncModule.value?.tryGet(nodeId)
    if (!nodeAst) return
    const oldPos = nodeAst.nodeMetadata.get('position')
    if (oldPos?.x !== position.x || oldPos?.y !== position.y) {
      editNodeMetadata(nodeAst, (metadata) =>
        metadata.set('position', { x: position.x, y: position.y }),
      )
    }
  }

  function normalizeVisMetadata(
    id: Opt<VisualizationIdentifier>,
    visible: boolean | undefined,
  ): VisualizationMetadata | undefined {
    const vis: VisualizationMetadata = { identifier: id ?? null, visible: visible ?? false }
    if (visMetadataEquals(vis, { identifier: null, visible: false })) return undefined
    else return vis
  }

  function setNodeVisualizationId(nodeId: NodeId, vis: Opt<VisualizationIdentifier>) {
    const nodeAst = syncModule.value?.tryGet(nodeId)
    if (!nodeAst) return
    editNodeMetadata(nodeAst, (metadata) =>
      metadata.set(
        'visualization',
        normalizeVisMetadata(vis, metadata.get('visualization')?.visible),
      ),
    )
  }

  function setNodeVisualizationVisible(nodeId: NodeId, visible: boolean) {
    const nodeAst = syncModule.value?.tryGet(nodeId)
    if (!nodeAst) return
    editNodeMetadata(nodeAst, (metadata) =>
      metadata.set(
        'visualization',
        normalizeVisMetadata(metadata.get('visualization')?.identifier, visible),
      ),
    )
  }

  function updateNodeRect(nodeId: NodeId, rect: Rect) {
    const nodeAst = syncModule.value?.tryGet(nodeId)
    if (!nodeAst) return
    if (rect.pos.equals(Vec2.Zero) && !nodeAst.nodeMetadata.get('position')) {
      const { position } = nonDictatedPlacement(rect.size, {
        nodeRects: visibleNodeAreas.value,
        // The rest of the properties should not matter.
        selectedNodeRects: [],
        screenBounds: Rect.Zero,
        mousePosition: Vec2.Zero,
      })
      editNodeMetadata(nodeAst, (metadata) =>
        metadata.set('position', { x: position.x, y: position.y }),
      )
      nodeRects.set(nodeId, new Rect(position, rect.size))
    } else {
      nodeRects.set(nodeId, rect)
    }
  }

  function updateVizRect(id: NodeId, rect: Rect | undefined) {
    if (rect) vizRects.set(id, rect)
    else vizRects.delete(id)
  }

  function unregisterNodeRect(id: NodeId) {
    nodeRects.delete(id)
    vizRects.delete(id)
  }

  function addPortInstance(id: PortId, instance: PortViewInstance) {
    map.setIfUndefined(portInstances, id, set.create).add(instance)
  }

  function removePortInstance(id: PortId, instance: PortViewInstance) {
    const instances = portInstances.get(id)
    if (!instances) return
    instances.delete(instance)
    if (instances.size === 0) portInstances.delete(id)
  }

  function setEditedNode(id: NodeId | null, cursorPosition: number | null) {
    if (!id) {
      editedNodeInfo.value = undefined
      return
    }
    if (cursorPosition == null) {
      console.warn('setEditedNode: cursorPosition is null')
      return
    }
    editedNodeInfo.value = { id, initialCursorPos: cursorPosition }
  }

  function getPortPrimaryInstance(id: PortId): PortViewInstance | undefined {
    const instances = portInstances.get(id)
    return instances && set.first(instances)
  }

  /**
   * Get the bounding rectangle of a port view, within the coordinate system of the node it belongs
   * to. If the port is currently not connected or interacted with, `undefined` may be returned.
   */
  function getPortRelativeRect(id: PortId): Rect | undefined {
    return getPortPrimaryInstance(id)?.rect.value
  }

  function getPortNodeId(id: PortId): NodeId | undefined {
    return db.getExpressionNodeId(id as string as Ast.AstId) ?? getPortPrimaryInstance(id)?.nodeId
  }

  /**
   * Emit a value update to a port view under specific ID. Returns `true` if the port view is
   * registered and the update was emitted, or `false` otherwise.
   *
   * NOTE: If this returns `true,` The update handlers called `graph.commitEdit` on their own.
   * Therefore, the passed in `edit` should not be modified afterward, as it is already committed.
   */
  function updatePortValue(edit: MutableModule, id: PortId, value: Ast.Owned | undefined): boolean {
    const update = getPortPrimaryInstance(id)?.onUpdate
    if (!update) return false
    update({ edit, portUpdate: { value, origin: id } })
    return true
  }

  function startEdit(): MutableModule {
    return syncModule.value!.edit()
  }

  /** Apply the given `edit` to the state.
   *
   *  @param skipTreeRepair - If the edit is known not to require any parenthesis insertion, this may be set to `true`
   *  for better performance.
   */
  function commitEdit(
    edit: MutableModule,
    skipTreeRepair?: boolean,
    origin: LocalOrigin = defaultLocalOrigin,
  ) {
    const root = edit.root()
    if (!(root instanceof Ast.BodyBlock)) {
      console.error(`BUG: Cannot commit edit: No module root block.`)
      return
    }
    if (!skipTreeRepair) Ast.repair(root, edit)
    syncModule.value!.applyEdit(edit, origin)
  }

  /** Edit the AST module.
   *
   *  Optimization options: These are safe to use for metadata-only edits; otherwise, they require extreme caution.
   *
   *  @param skipTreeRepair - If the edit is certain not to produce incorrect or non-canonical syntax, this may be set
   *  to `true` for better performance.
   *  @param direct - Apply all changes directly to the synchronized module; they will be committed even if the callback
   *  exits by throwing an exception.
   */
  function edit<T>(f: (edit: MutableModule) => T, skipTreeRepair?: boolean, direct?: boolean): T {
    const edit = direct ? syncModule.value : syncModule.value?.edit()
    assert(edit != null)
    let result
    edit.transact(() => {
      result = f(edit)
      if (!skipTreeRepair) {
        const root = edit.root()
        assert(root instanceof Ast.BodyBlock)
        Ast.repair(root, edit)
      }
      if (!direct) syncModule.value!.applyEdit(edit)
    })
    return result!
  }

  function editNodeMetadata(ast: Ast.Ast, f: (metadata: Ast.MutableNodeMetadata) => void) {
    edit((edit) => f(edit.getVersion(ast).mutableNodeMetadata()), true, true)
  }

  function viewModule(): Module {
    return syncModule.value!
  }

  function mockExpressionUpdate(
    locator: string | { binding: string; expr: string },
    update: Partial<ExpressionUpdate>,
  ) {
    const { binding, expr } =
      typeof locator === 'string' ? { binding: locator, expr: undefined } : locator
    const nodeId = db.getIdentDefiningNode(binding)
    if (nodeId == null) bail(`The node with identifier '${binding}' was not found.`)
    let exprId: AstId | undefined
    if (expr) {
      const node = db.nodeIdToNode.get(nodeId)
      node?.rootSpan.visitRecursive((ast) => {
        if (ast instanceof Ast.Ast && ast.code() == expr) {
          exprId = ast.id
        }
      })
    } else {
      exprId = nodeId
    }

    if (exprId == null) bail(`Cannot find expression located by ${locator}`)

    const update_: ExpressionUpdate = {
      expressionId: db.idToExternal(exprId)!,
      profilingInfo: update.profilingInfo ?? [],
      fromCache: update.fromCache ?? false,
      payload: update.payload ?? { type: 'Value' },
      ...(update.type ? { type: update.type } : {}),
      ...(update.methodCall ? { methodCall: update.methodCall } : {}),
    }
    proj.computedValueRegistry.processUpdates([update_])
  }

  /**
   * Reorders nodes so the `targetNodeId` node is placed after `sourceNodeId`. Does nothing if the
   * relative order is already correct.
   *
   * Additionally, all nodes dependent on the `targetNodeId` that end up being before its new line
   * are also moved after it, keeping their relative order.
   */
  function ensureCorrectNodeOrder(edit: MutableModule, sourceNodeId: NodeId, targetNodeId: NodeId) {
    const sourceExpr = db.nodeIdToNode.get(sourceNodeId)?.outerExprId
    const targetExpr = db.nodeIdToNode.get(targetNodeId)?.outerExprId
    const body = edit.getVersion(methodAstInModule(edit)!).bodyAsBlock()
    assert(sourceExpr != null)
    assert(targetExpr != null)
    const lines = body.lines
    const sourceIdx = lines.findIndex((line) => line.expression?.node.id === sourceExpr)
    const targetIdx = lines.findIndex((line) => line.expression?.node.id === targetExpr)
    assert(sourceIdx != null)
    assert(targetIdx != null)

    // If source is placed after its new target, the nodes needs to be reordered.
    if (sourceIdx > targetIdx) {
      // Find all transitive dependencies of the moved target node.
      const deps = db.dependantNodes(targetNodeId)

      const dependantLines = new Set(Array.from(deps, (id) => db.nodeIdToNode.get(id)?.outerExprId))
      // Include the new target itself in the set of lines that must be placed after source node.
      dependantLines.add(targetExpr)

      // Check if the source depends on target. If that's the case, the edge we are trying to make
      // creates a circular dependency. Reordering doesn't make any sense in that case.
      if (dependantLines.has(sourceExpr)) {
        return 'circular'
      }

      body.updateLines((lines) => {
        // Pick subset of lines to reorder, i.e. lines between and including target and source.
        const linesToSort = lines.splice(targetIdx, sourceIdx - targetIdx + 1)

        // Split those lines into two buckets, whether or not they depend on the target.
        const [linesAfter, linesBefore] = partition(linesToSort, (line) =>
          dependantLines.has(line.expression?.node.id),
        )

        // Recombine all lines after splitting, keeping existing dependants below the target.
        lines.splice(targetIdx, 0, ...linesBefore, ...linesAfter)

        return lines
      })
    } else {
      return false
    }
  }

  function isConnectedTarget(portId: PortId): boolean {
    return db.connections.reverseLookup(portId as AstId).size > 0
  }

  return {
    transact,
    db: markRaw(db),
    mockExpressionUpdate,
    editedNodeInfo,
    unconnectedEdge,
    edges,
    connectedEdges,
    moduleSource,
    nodeRects,
    vizRects,
    visibleNodeAreas,
    unregisterNodeRect,
    methodAst,
    createEdgeFromOutput,
    disconnectSource,
    disconnectTarget,
    clearUnconnected,
    moduleRoot,
    createNode,
    deleteNodes,
    ensureCorrectNodeOrder,
    setNodeContent,
    setNodePosition,
    setNodeVisualizationId,
    setNodeVisualizationVisible,
    stopCapturingUndo,
    topLevel,
    updateNodeRect,
    updateVizRect,
    addPortInstance,
    removePortInstance,
    getPortRelativeRect,
    getPortNodeId,
    updatePortValue,
    setEditedNode,
    updateState,
    startEdit,
    commitEdit,
    edit,
    viewModule,
    addMissingImports,
    isConnectedTarget,
  }
})

function randomIdent() {
  const ident = 'operator' + Math.round(Math.random() * 100000)
  assert(isIdentifier(ident))
  return ident
}

/** An edge, which may be connected or unconnected. */
export interface Edge {
  source: AstId | undefined
  target: PortId | undefined
}

export interface ConnectedEdge extends Edge {
  source: AstId
  target: PortId
}

export function isConnected(edge: Edge): edge is ConnectedEdge {
  return edge.source != null && edge.target != null
}

interface UnconnectedEdge extends Edge {
  /** If this edge represents an in-progress edit of a connected edge, it is identified by its target expression. */
  disconnectedEdgeTarget?: PortId
}

function getExecutedMethodAst(
  topLevel: Ast.BodyBlock,
  executionStackTop: StackItem,
  db: GraphDb,
): Ast.Function | undefined {
  switch (executionStackTop.type) {
    case 'ExplicitCall': {
      // Assume that the provided AST matches the module in the method pointer. There is no way to
      // actually verify this assumption at this point.
      const ptr = executionStackTop.methodPointer
      return Ast.findModuleMethod(topLevel, ptr.name) ?? undefined
    }
    case 'LocalCall': {
      const exprId = executionStackTop.expressionId
      const info = db.getExpressionInfo(exprId)
      if (!info) return undefined
      const ptr = info.methodCall?.methodPointer
      if (!ptr) return undefined
      return Ast.findModuleMethod(topLevel, ptr.name) ?? undefined
    }
  }
}
