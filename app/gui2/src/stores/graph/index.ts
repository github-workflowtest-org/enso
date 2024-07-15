import { usePlacement } from '@/components/ComponentBrowser/placement'
import { createContextStore } from '@/providers'
import type { PortId } from '@/providers/portInfo'
import type { WidgetUpdate } from '@/providers/widgetRegistry'
import { GraphDb, type NodeId } from '@/stores/graph/graphDatabase'
import {
  addImports,
  detectImportConflicts,
  filterOutRedundantImports,
  readImports,
  type DetectedConflict,
  type Import,
  type RequiredImport,
} from '@/stores/graph/imports'
import { useUnconnectedEdges, type UnconnectedEdge } from '@/stores/graph/unconnectedEdges'
import { type ProjectStore } from '@/stores/project'
import { type SuggestionDbStore } from '@/stores/suggestionDatabase'
import { assert, bail } from '@/util/assert'
import { Ast } from '@/util/ast'
import type { AstId } from '@/util/ast/abstract'
import { MutableModule, isAstId, isIdentifier, type Identifier } from '@/util/ast/abstract'
import { RawAst, visitRecursive } from '@/util/ast/raw'
import { partition } from '@/util/data/array'
import { Rect } from '@/util/data/rect'
import { Err, Ok, mapOk, unwrap, type Result } from '@/util/data/result'
import { Vec2 } from '@/util/data/vec2'
import { normalizeQualifiedName, tryQualifiedName } from '@/util/qualifiedName'
import { computedAsync } from '@vueuse/core'
import { map, set } from 'lib0'
import { iteratorFilter } from 'lib0/iterator'
import { SourceDocument } from 'shared/ast/sourceDocument'
import type { ExpressionUpdate, Path as LsPath, MethodPointer } from 'shared/languageServerTypes'
import { reachable } from 'shared/util/data/graph'
import type {
  LocalUserActionOrigin,
  Origin,
  SourceRangeKey,
  VisualizationMetadata,
} from 'shared/yjsModel'
import { defaultLocalOrigin, sourceRangeKey, visMetadataEquals } from 'shared/yjsModel'
import {
  computed,
  markRaw,
  proxyRefs,
  reactive,
  ref,
  shallowReactive,
  toRef,
  watch,
  type Ref,
  type ShallowRef,
} from 'vue'

const FALLBACK_BINDING_PREFIX = 'node'

export type {
  Node,
  NodeDataFromAst,
  NodeDataFromMetadata,
  NodeId,
} from '@/stores/graph/graphDatabase'

export interface NodeEditInfo {
  id: NodeId
  initialCursorPos: number
}

export class PortViewInstance {
  constructor(
    public rect: ShallowRef<Rect | undefined>,
    public nodeId: NodeId,
    public onUpdate: (update: WidgetUpdate) => void,
  ) {
    markRaw(this)
  }
}

export type GraphStore = ReturnType<typeof useGraphStore>
export const { injectFn: useGraphStore, provideFn: provideGraphStore } = createContextStore(
  'graph',
  (proj: ProjectStore, suggestionDb: SuggestionDbStore) => {
    proj.setObservedFileName('Main.enso')

    const syncModule = computed(
      () => proj.module && markRaw(new MutableModule(proj.module.doc.ydoc)),
    )

    const nodeRects = reactive(new Map<NodeId, Rect>())
    const nodeHoverAnimations = reactive(new Map<NodeId, number>())
    const vizRects = reactive(new Map<NodeId, Rect>())
    // The currently visible nodes' areas (including visualization).
    const visibleNodeAreas = computed(() => {
      const existing = iteratorFilter(nodeRects.entries(), ([id]) => db.nodeIdToNode.has(id))
      return Array.from(existing, ([id, rect]) => vizRects.get(id) ?? rect)
    })
    function visibleArea(nodeId: NodeId): Rect | undefined {
      if (!db.nodeIdToNode.has(nodeId)) return
      return vizRects.get(nodeId) ?? nodeRects.get(nodeId)
    }

    const db = new GraphDb(
      suggestionDb.entries,
      toRef(suggestionDb, 'groups'),
      proj.computedValueRegistry,
    )
    const portInstances = shallowReactive(new Map<PortId, Set<PortViewInstance>>())
    const editedNodeInfo = ref<NodeEditInfo>()
    const methodAst = ref<Result<Ast.Function>>(Err('AST not yet initialized'))

    const moduleSource = reactive(SourceDocument.Empty())
    const moduleRoot = ref<Ast.Ast>()
    const topLevel = ref<Ast.BodyBlock>()

    watch(syncModule, (syncModule, _, onCleanup) => {
      if (!syncModule) return
      let moduleChanged = true
      const handle = syncModule.observe((update) => {
        moduleSource.applyUpdate(syncModule, update)
        handleModuleUpdate(syncModule, moduleChanged, update)
        moduleChanged = false
      })
      onCleanup(() => {
        syncModule.unobserve(handle)
        moduleSource.clear()
      })
    })

    let toRaw = new Map<SourceRangeKey, RawAst.Tree.Function>()
    function handleModuleUpdate(
      module: Ast.Module,
      moduleChanged: boolean,
      update: Ast.ModuleUpdate,
    ) {
      const root = module.root()
      if (!root) return
      if (moduleRoot.value != root) {
        moduleRoot.value = root
      }
      if (root instanceof Ast.BodyBlock && topLevel.value != root) {
        topLevel.value = root
      }
      // We can cast maps of unknown metadata fields to `NodeMetadata` because all `NodeMetadata` fields are optional.
      const nodeMetadataUpdates = update.metadataUpdated as any as {
        id: AstId
        changes: Ast.NodeMetadata
      }[]
      const dirtyNodeSet = new Set(
        (function* () {
          yield* update.nodesUpdated
          yield* update.nodesAdded
        })(),
      )
      if (moduleChanged || dirtyNodeSet.size !== 0 || update.nodesDeleted.size !== 0) {
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
      methodAst.value = getExecutedMethodAst(syncModule.value)
      if (methodAst.value.ok) {
        const methodSpan = moduleSource.getSpan(methodAst.value.value.id)
        assert(methodSpan != null)
        const rawFunc = toRaw.get(sourceRangeKey(methodSpan))
        assert(rawFunc != null)
        db.readFunctionAst(
          methodAst.value.value,
          rawFunc,
          textContentLocal,
          (id) => moduleSource.getSpan(id),
          dirtyNodes ?? new Set(),
        )
      }
    }

    function getExecutedMethodAst(module?: Ast.Module): Result<Ast.Function> {
      const executionStackTop = proj.executionContext.getStackTop()
      switch (executionStackTop.type) {
        case 'ExplicitCall': {
          return getMethodAst(executionStackTop.methodPointer, module)
        }
        case 'LocalCall': {
          const exprId = executionStackTop.expressionId
          const info = db.getExpressionInfo(exprId)
          const ptr = info?.methodCall?.methodPointer
          if (!ptr) return Err("Unknown method pointer of execution stack's top frame")
          return getMethodAst(ptr, module)
        }
      }
    }

    function getMethodAst(ptr: MethodPointer, edit?: Ast.Module): Result<Ast.Function> {
      const topLevel = (edit ?? syncModule.value)?.root()
      if (!topLevel) return Err('Module unavailable')
      assert(topLevel instanceof Ast.BodyBlock)
      const modulePath =
        proj.modulePath ?
          mapOk(proj.modulePath, normalizeQualifiedName)
        : Err('Unknown current module name')
      if (!modulePath?.ok) return modulePath
      const ptrModule = mapOk(tryQualifiedName(ptr.module), normalizeQualifiedName)
      const ptrDefinedOnType = mapOk(tryQualifiedName(ptr.definedOnType), normalizeQualifiedName)
      if (!ptrModule.ok) return ptrModule
      if (!ptrDefinedOnType.ok) return ptrDefinedOnType
      if (ptrModule.value !== modulePath.value)
        return Err('Cannot read method from different module')
      if (ptrModule.value !== ptrDefinedOnType.value)
        return Err('Method pointer is not a module method')
      const method = Ast.findModuleMethod(topLevel, ptr.name)
      if (!method) return Err(`No method with name ${ptr.name} in ${modulePath.value}`)
      return Ok(method)
    }

    /** Generate unique identifier from `prefix` and some numeric suffix.
     * @param prefix - of the identifier
     * @param ignore - a list of identifiers to consider as unavailable. Useful when creating multiple identifiers in a batch.
     * */
    function generateLocallyUniqueIdent(
      prefix?: string | undefined,
      ignore: Set<Identifier> = new Set(),
    ): Identifier {
      // FIXME: This implementation is not robust in the context of a synchronized document,
      // as the same name can likely be assigned by multiple clients.
      // Consider implementing a mechanism to repair the document in case of name clashes.
      const identPrefix = prefix && isIdentifier(prefix + 1) ? prefix : FALLBACK_BINDING_PREFIX
      for (let i = 1; ; i++) {
        const ident = identPrefix + i
        assert(isIdentifier(ident))
        if (!db.identifierUsed(ident) && !ignore.has(ident)) return ident
      }
    }

    const unconnectedEdges = useUnconnectedEdges()

    const editedNodeDisconnectedTarget = computed(() =>
      editedNodeInfo.value ?
        db.nodeIdToNode.get(editedNodeInfo.value.id)?.primarySubject
      : undefined,
    )

    const connectedEdges = computed(() => {
      const edges = new Array<ConnectedEdge>()
      for (const [target, sources] of db.connections.allReverse()) {
        if (target === editedNodeDisconnectedTarget.value) continue
        for (const source of sources) {
          const edge = { source, target }
          if (!unconnectedEdges.isDisconnected(edge)) {
            edges.push(edge)
          }
        }
      }
      return edges
    })

    /* Try adding imports. Does nothing if conflict is detected, and returns `DectedConflict` in such case. */
    function addMissingImports(
      edit: MutableModule,
      newImports: RequiredImport[],
    ): DetectedConflict[] | undefined {
      const topLevel = edit.getVersion(moduleRoot.value!)
      if (!(topLevel instanceof Ast.MutableBodyBlock)) {
        console.error(`BUG: Cannot add required imports: No BodyBlock module root.`)
        return
      }
      const existingImports = readImports(topLevel)

      const conflicts = []
      const nonConflictingImports = []
      for (const newImport of newImports) {
        const conflictInfo = detectImportConflicts(suggestionDb.entries, existingImports, newImport)
        if (conflictInfo?.detected) {
          conflicts.push(conflictInfo)
        } else {
          nonConflictingImports.push(newImport)
        }
      }
      addMissingImportsDisregardConflicts(edit, nonConflictingImports, existingImports)

      if (conflicts.length > 0) return conflicts
    }

    /* Adds imports, ignores any possible conflicts.
     * `existingImports` are optional and will be used instead of `readImports(topLevel)` if provided. */
    function addMissingImportsDisregardConflicts(
      edit: MutableModule,
      imports: RequiredImport[],
      existingImports?: Import[] | undefined,
    ) {
      if (!imports.length) return
      const topLevel = edit.getVersion(moduleRoot.value!)
      if (!(topLevel instanceof Ast.MutableBodyBlock)) {
        console.error(`BUG: Cannot add required imports: No BodyBlock module root.`)
        return
      }
      const existingImports_ = existingImports ?? readImports(topLevel)

      const importsToAdd = filterOutRedundantImports(existingImports_, imports)
      if (!importsToAdd.length) return
      addImports(edit.getVersion(topLevel), importsToAdd)
    }

    function deleteNodes(ids: Iterable<NodeId>) {
      edit(
        (edit) => {
          for (const id of ids) {
            const node = db.nodeIdToNode.get(id)
            if (!node) continue
            const usages = db.getNodeUsages(id)
            for (const usage of usages) updatePortValue(edit, usage, undefined)
            const outerExpr = edit.getVersion(node.outerExpr)
            if (outerExpr) Ast.deleteFromParentBlock(outerExpr)
            nodeRects.delete(id)
            nodeHoverAnimations.delete(id)
          }
        },
        true,
        true,
      )
    }

    function setNodeContent(
      id: NodeId,
      content: string,
      withImports?: RequiredImport[] | undefined,
    ) {
      const node = db.nodeIdToNode.get(id)
      if (!node) return
      edit((edit) => {
        const editExpr = edit.getVersion(node.innerExpr)
        editExpr.syncToCode(content)
        if (withImports) {
          const conflicts = addMissingImports(edit, withImports)
          if (conflicts == null) return
          const wholeAssignment = editExpr.mutableParent()
          if (wholeAssignment == null) {
            console.error('Cannot find parent of the node expression. Conflict resolution failed.')
            return
          }
          for (const _conflict of conflicts) {
            // TODO: Substitution does not work, because we interpret imports wrongly. To be fixed in
            // https://github.com/enso-org/enso/issues/9356
            // substituteQualifiedName(wholeAssignment, conflict.pattern, conflict.fullyQualified)
          }
        }
      })
    }

    function transact(fn: () => void) {
      syncModule.value!.transact(fn)
    }

    const undoManager = {
      undo() {
        proj.module?.undoManager.undo()
      },
      redo() {
        proj.module?.undoManager.redo()
      },
      undoStackBoundary() {
        proj.module?.undoManager.stopCapturing()
      },
    }

    function setNodePosition(nodeId: NodeId, position: Vec2) {
      const nodeAst = syncModule.value?.tryGet(db.idFromExternal(nodeId))
      if (!nodeAst) return
      const oldPos = nodeAst.nodeMetadata.get('position')
      if (oldPos?.x !== position.x || oldPos?.y !== position.y) {
        editNodeMetadata(nodeAst, (metadata) =>
          metadata.set('position', { x: position.x, y: position.y }),
        )
      }
    }

    function overrideNodeColor(nodeId: NodeId, color: string | undefined) {
      const nodeAst = syncModule.value?.tryGet(db.idFromExternal(nodeId))
      if (!nodeAst) return
      editNodeMetadata(nodeAst, (metadata) => {
        metadata.set('colorOverride', color)
      })
    }

    function getNodeColorOverride(node: NodeId) {
      return db.nodeIdToNode.get(node)?.colorOverride ?? undefined
    }

    function normalizeVisMetadata(
      partial: Partial<VisualizationMetadata>,
    ): VisualizationMetadata | undefined {
      const empty: VisualizationMetadata = {
        identifier: null,
        visible: false,
        fullscreen: false,
        width: null,
        height: null,
      }
      const vis: VisualizationMetadata = { ...empty, ...partial }
      if (visMetadataEquals(vis, empty)) return undefined
      else return vis
    }

    function setNodeVisualization(nodeId: NodeId, vis: Partial<VisualizationMetadata>) {
      const nodeAst = syncModule.value?.tryGet(db.idFromExternal(nodeId))
      if (!nodeAst) return
      editNodeMetadata(nodeAst, (metadata) => {
        const data: Partial<VisualizationMetadata> = {
          identifier: vis.identifier ?? metadata.get('visualization')?.identifier ?? null,
          visible: vis.visible ?? metadata.get('visualization')?.visible ?? false,
          fullscreen: vis.fullscreen ?? metadata.get('visualization')?.fullscreen ?? false,
          width: vis.width ?? metadata.get('visualization')?.width ?? null,
          height: vis.height ?? metadata.get('visualization')?.height ?? null,
        }
        metadata.set('visualization', normalizeVisMetadata(data))
      })
    }

    function updateNodeRect(nodeId: NodeId, rect: Rect) {
      nodeRects.set(nodeId, rect)
      if (rect.pos.equals(Vec2.Zero)) {
        nodesToPlace.push(nodeId)
      }
    }

    function updateNodeHoverAnim(nodeId: NodeId, progress: number) {
      nodeHoverAnimations.set(nodeId, progress)
    }

    const nodesToPlace = reactive<NodeId[]>([])
    const { place: placeNode } = usePlacement(visibleNodeAreas, Rect.Zero)

    watch(nodesToPlace, (nodeIds) => {
      if (nodeIds.length === 0) return
      const nodesToProcess = [...nodeIds]
      nodesToPlace.length = 0
      batchEdits(() => {
        for (const nodeId of nodesToProcess) {
          const nodeAst = syncModule.value?.get(db.idFromExternal(nodeId))
          const rect = nodeRects.get(nodeId)
          if (!rect || !nodeAst || nodeAst.nodeMetadata.get('position') != null) continue
          const position = placeNode([], rect.size)
          editNodeMetadata(nodeAst, (metadata) =>
            metadata.set('position', { x: position.x, y: position.y }),
          )
          nodeRects.set(nodeId, new Rect(position, rect.size))
        }
      }, 'local:autoLayout')
    })

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

    function isPortEnabled(id: PortId): boolean {
      return getPortRelativeRect(id) != null
    }

    function getPortNodeId(id: PortId): NodeId | undefined {
      return (isAstId(id) && db.getExpressionNodeId(id)) || getPortPrimaryInstance(id)?.nodeId
    }

    /**
     * Emit a value update to a port view under specific ID. Returns `true` if the port view is
     * registered and the update was emitted, or `false` otherwise.
     *
     * NOTE: If this returns `true,` The update handlers called `graph.commitEdit` on their own.
     * Therefore, the passed in `edit` should not be modified afterward, as it is already committed.
     */
    function updatePortValue(
      edit: MutableModule,
      id: PortId,
      value: Ast.Owned | undefined,
    ): boolean {
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
      origin: LocalUserActionOrigin = defaultLocalOrigin,
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

    function batchEdits(f: () => void, origin: Origin = defaultLocalOrigin) {
      assert(syncModule.value != null)
      syncModule.value.transact(f, origin)
    }

    function editNodeMetadata(ast: Ast.Ast, f: (metadata: Ast.MutableNodeMetadata) => void) {
      edit((edit) => f(edit.getVersion(ast).mutableNodeMetadata()), true, true)
    }

    const viewModule = computed(() => syncModule.value!)

    // expose testing hook
    ;(window as any)._mockExpressionUpdate = mockExpressionUpdate

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
        node?.innerExpr.visitRecursive((ast) => {
          if (ast instanceof Ast.Ast && ast.code() == expr) {
            exprId = ast.id
          }
        })
      } else {
        exprId = db.idFromExternal(nodeId)
      }

      if (exprId == null) {
        const locatorStr =
          typeof locator === 'string' ? locator : `${locator.binding}/${locator.expr}`
        bail(`Cannot find expression located by ${locatorStr}`)
      }

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

    /** Iterate over code lines, return node IDs from `ids` set in the order of code positions. */
    function pickInCodeOrder(ids: Set<NodeId>): NodeId[] {
      assert(syncModule.value != null)
      const func = unwrap(getExecutedMethodAst(syncModule.value))
      const body = func.bodyExpressions()
      const result: NodeId[] = []
      for (const expr of body) {
        const id = expr?.id
        const nodeId = db.getOuterExpressionNodeId(id)
        if (nodeId && ids.has(nodeId)) result.push(nodeId)
      }
      return result
    }

    /**
     * Reorders nodes so the `targetNodeId` node is placed after `sourceNodeId`. Does nothing if the
     * relative order is already correct.
     *
     * Additionally, all nodes dependent on the `targetNodeId` that end up being before its new line
     * are also moved after it, keeping their relative order.
     */
    function ensureCorrectNodeOrder(
      edit: MutableModule,
      sourceNodeId: NodeId,
      targetNodeId: NodeId,
    ) {
      const sourceExpr = db.nodeIdToNode.get(sourceNodeId)?.outerExpr.id
      const targetExpr = db.nodeIdToNode.get(targetNodeId)?.outerExpr.id
      const body = edit.getVersion(unwrap(getExecutedMethodAst(edit))).bodyAsBlock()
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
        const deps = reachable([targetNodeId], (node) => db.nodeDependents.lookup(node))

        const dependantLines = new Set(
          Array.from(deps, (id) => db.nodeIdToNode.get(id)?.outerExpr.id),
        )
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
      return isAstId(portId) && db.connections.reverseLookup(portId).size > 0
    }

    const modulePath: Ref<LsPath | undefined> = computedAsync(async () => {
      const rootId = await proj.projectRootId
      const segments = ['src', 'Main.enso']
      return rootId ? { rootId, segments } : undefined
    })

    return proxyRefs({
      transact,
      db: markRaw(db),
      mockExpressionUpdate,
      editedNodeInfo,
      moduleSource,
      nodeRects,
      nodeHoverAnimations,
      vizRects,
      visibleNodeAreas,
      visibleArea,
      unregisterNodeRect,
      methodAst,
      getMethodAst,
      generateLocallyUniqueIdent,
      moduleRoot,
      deleteNodes,
      pickInCodeOrder,
      ensureCorrectNodeOrder,
      batchEdits,
      overrideNodeColor,
      getNodeColorOverride,
      setNodeContent,
      setNodePosition,
      setNodeVisualization,
      undoManager,
      topLevel,
      updateNodeRect,
      updateNodeHoverAnim,
      updateVizRect,
      addPortInstance,
      removePortInstance,
      getPortRelativeRect,
      getPortNodeId,
      isPortEnabled,
      updatePortValue,
      setEditedNode,
      updateState,
      startEdit,
      commitEdit,
      edit,
      viewModule,
      addMissingImports,
      addMissingImportsDisregardConflicts,
      isConnectedTarget,
      currentMethodPointer() {
        const currentMethod = proj.executionContext.getStackTop()
        if (currentMethod.type === 'ExplicitCall') return currentMethod.methodPointer
        return db.getExpressionInfo(currentMethod.expressionId)?.methodCall?.methodPointer
      },
      modulePath,
      connectedEdges,
      ...unconnectedEdges,
    })
  },
)

/** An edge, which may be connected or unconnected. */
export type Edge = ConnectedEdge | UnconnectedEdge

export interface ConnectedEdge {
  source: AstId
  target: PortId
}

export function isConnected(edge: Edge): edge is ConnectedEdge {
  return edge.source != null && edge.target != null
}
