<script setup lang="ts">
import {
  codeEditorBindings,
  documentationEditorBindings,
  graphBindings,
  interactionBindings,
  undoBindings,
} from '@/bindings'
import BottomPanel from '@/components/BottomPanel.vue'
import CodeEditor from '@/components/CodeEditor.vue'
import ComponentBrowser from '@/components/ComponentBrowser.vue'
import { type Usage } from '@/components/ComponentBrowser/input'
import { usePlacement } from '@/components/ComponentBrowser/placement'
import DockPanel from '@/components/DockPanel.vue'
import DocumentationEditor from '@/components/DocumentationEditor.vue'
import GraphEdges from '@/components/GraphEditor/GraphEdges.vue'
import GraphNodes from '@/components/GraphEditor/GraphNodes.vue'
import { useGraphEditorClipboard } from '@/components/GraphEditor/clipboard'
import { performCollapse, prepareCollapsedInfo } from '@/components/GraphEditor/collapsing'
import type { NodeCreationOptions } from '@/components/GraphEditor/nodeCreation'
import { useGraphEditorToasts } from '@/components/GraphEditor/toasts'
import { Uploader, uploadedExpression } from '@/components/GraphEditor/upload'
import GraphMouse from '@/components/GraphMouse.vue'
import PlusButton from '@/components/PlusButton.vue'
import SceneScroller from '@/components/SceneScroller.vue'
import TopBar from '@/components/TopBar.vue'
import { useAstDocumentation } from '@/composables/astDocumentation'
import { useDoubleClick } from '@/composables/doubleClick'
import { keyboardBusy, keyboardBusyExceptIn, unrefElement, useEvent } from '@/composables/events'
import { groupColorVar } from '@/composables/nodeColors'
import type { PlacementStrategy } from '@/composables/nodeCreation'
import { useSyncLocalStorage } from '@/composables/syncLocalStorage'
import { provideGraphNavigator, type GraphNavigator } from '@/providers/graphNavigator'
import { provideNodeColors } from '@/providers/graphNodeColors'
import { provideNodeCreation } from '@/providers/graphNodeCreation'
import { provideGraphSelection } from '@/providers/graphSelection'
import { provideStackNavigator } from '@/providers/graphStackNavigator'
import { provideInteractionHandler } from '@/providers/interactionHandler'
import { provideKeyboard } from '@/providers/keyboard'
import { provideWidgetRegistry } from '@/providers/widgetRegistry'
import { provideGraphStore, type NodeId } from '@/stores/graph'
import type { RequiredImport } from '@/stores/graph/imports'
import { useProjectStore } from '@/stores/project'
import { provideSuggestionDbStore } from '@/stores/suggestionDatabase'
import { suggestionDocumentationUrl, type Typename } from '@/stores/suggestionDatabase/entry'
import { provideVisualizationStore } from '@/stores/visualization'
import { bail } from '@/util/assert'
import type { AstId } from '@/util/ast/abstract'
import { colorFromString } from '@/util/colors'
import { partition } from '@/util/data/array'
import { every, filterDefined } from '@/util/data/iterable'
import { Rect } from '@/util/data/rect'
import { unwrapOr } from '@/util/data/result'
import { Vec2 } from '@/util/data/vec2'
import { computedFallback } from '@/util/reactivity'
import { until } from '@vueuse/core'
import { encoding, set } from 'lib0'
import { encodeMethodPointer } from 'shared/languageServerTypes'
import { isDevMode } from 'shared/util/detect'
import {
  computed,
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  toRaw,
  toRef,
  watch,
  type ComponentInstance,
} from 'vue'

import { builtinWidgets } from '@/components/widgets'

const keyboard = provideKeyboard()
const projectStore = useProjectStore()
const suggestionDb = provideSuggestionDbStore(projectStore)
const graphStore = provideGraphStore(projectStore, suggestionDb)
const widgetRegistry = provideWidgetRegistry(graphStore.db)
const _visualizationStore = provideVisualizationStore(projectStore)

onMounted(() => {
  widgetRegistry.loadWidgets(Object.entries(builtinWidgets))
  if (isDevMode) {
    ;(window as any).suggestionDb = toRaw(suggestionDb.entries)
  }
})
onUnmounted(() => {
  projectStore.disposeYDocsProvider()
})

// === Navigator ===

const viewportNode = ref<HTMLElement>()
onMounted(() => viewportNode.value?.focus())
const graphNavigator: GraphNavigator = provideGraphNavigator(viewportNode, keyboard, {
  predicate: (e) => (e instanceof KeyboardEvent ? nodeSelection.selected.size === 0 : true),
})

// === Client saved state ===

const storedShowDocumentationEditor = ref()
const rightDockWidth = ref<number>()

/**
 * JSON serializable representation of graph state saved in localStorage. The names of fields here
 * are kept relatively short, because it will be common to store hundreds of them within one big
 * JSON object, and serialize it quite often whenever the state is modified. Shorter keys end up
 * costing less localStorage space and slightly reduce serialization overhead.
 */
interface GraphStoredState {
  /** Navigator position X */
  x: number
  /** Navigator position Y */
  y: number
  /** Navigator scale */
  s: number
  /** Whether or not the documentation panel is open. */
  doc: boolean
  /** Width of the right dock. */
  rwidth: number | null
}

const visibleAreasReady = computed(() => {
  const nodesCount = graphStore.db.nodeIdToNode.size
  const visibleNodeAreas = graphStore.visibleNodeAreas
  return nodesCount > 0 && visibleNodeAreas.length == nodesCount
})

useSyncLocalStorage<GraphStoredState>({
  storageKey: 'enso-graph-state',
  mapKeyEncoder: (enc) => {
    // Client graph state needs to be stored separately for:
    // - each project
    // - each function within the project
    encoding.writeVarString(enc, projectStore.name)
    const methodPtr = graphStore.currentMethodPointer()
    if (methodPtr != null) encodeMethodPointer(enc, methodPtr)
  },
  debounce: 200,
  captureState() {
    return {
      x: graphNavigator.targetCenter.x,
      y: graphNavigator.targetCenter.y,
      s: graphNavigator.targetScale,
      doc: storedShowDocumentationEditor.value,
      rwidth: rightDockWidth.value ?? null,
    }
  },
  async restoreState(restored, abort) {
    if (restored) {
      const pos = new Vec2(restored.x ?? 0, restored.y ?? 0)
      const scale = restored.s ?? 1
      graphNavigator.setCenterAndScale(pos, scale)
      storedShowDocumentationEditor.value = restored.doc ?? undefined
      rightDockWidth.value = restored.rwidth ?? undefined
    } else {
      await until(visibleAreasReady).toBe(true)
      if (!abort.aborted) zoomToAll(true)
    }
  },
})

function nodesBounds(nodeIds: Iterable<NodeId>) {
  let bounds = Rect.Bounding()
  for (const id of nodeIds) {
    const rect = graphStore.visibleArea(id)
    if (rect) bounds = Rect.Bounding(bounds, rect)
  }
  if (bounds.isFinite()) return bounds
}

function selectionBounds() {
  const selected = nodeSelection.selected
  const nodesToCenter = selected.size === 0 ? graphStore.db.nodeIdToNode.keys() : selected
  return nodesBounds(nodesToCenter)
}

function zoomToSelected(skipAnimation: boolean = false) {
  const bounds = selectionBounds()
  if (bounds)
    graphNavigator.panAndZoomTo(bounds, 0.1, Math.max(1, graphNavigator.targetScale), skipAnimation)
}

function zoomToAll(skipAnimation: boolean = false) {
  const bounds = nodesBounds(graphStore.db.nodeIdToNode.keys())
  if (bounds)
    graphNavigator.panAndZoomTo(bounds, 0.1, Math.max(1, graphNavigator.targetScale), skipAnimation)
}

function panToSelected() {
  const bounds = selectionBounds()
  if (bounds)
    graphNavigator.panTo([new Vec2(bounds.left, bounds.top), new Vec2(bounds.right, bounds.bottom)])
}

// == Breadcrumbs ==

const stackNavigator = provideStackNavigator(projectStore, graphStore)

// === Toasts ===

const toasts = useGraphEditorToasts(projectStore)

// === Selection ===

const graphNodeSelections = shallowRef<HTMLElement>()
const nodeSelection = provideGraphSelection(
  graphNavigator,
  graphStore.nodeRects,
  graphStore.isPortEnabled,
  {
    isValid: (id) => graphStore.db.nodeIdToNode.has(id),
    onSelected: (id) => graphStore.db.moveNodeToTop(id),
  },
)

// Clear selection whenever the graph view is switched.
watch(
  () => projectStore.executionContext.getStackTop(),
  () => nodeSelection.deselectAll(),
)

// === Node creation ===

const { place: nodePlacement, collapse: collapsedNodePlacement } = usePlacement(
  toRef(graphStore, 'visibleNodeAreas'),
  toRef(graphNavigator, 'viewport'),
)

const { createNode, createNodes, placeNode } = provideNodeCreation(
  graphStore,
  toRef(graphNavigator, 'viewport'),
  toRef(graphNavigator, 'sceneMousePos'),
  (nodes) => {
    clearFocus()
    nodeSelection.setSelection(nodes)
    panToSelected()
  },
)

// === Clipboard Copy/Paste ===

const { copySelectionToClipboard, createNodesFromClipboard } = useGraphEditorClipboard(
  graphStore,
  toRef(nodeSelection, 'selected'),
  createNodes,
)

// === Interactions ===

const interaction = provideInteractionHandler()
const interactionBindingsHandler = interactionBindings.handler({
  cancel: () => interaction.handleCancel(),
})

useEvent(window, 'keydown', (event) => {
  interactionBindingsHandler(event) ||
    (!keyboardBusyExceptIn(documentationEditorArea.value) && undoBindingsHandler(event)) ||
    (!keyboardBusy() && graphBindingsHandler(event)) ||
    (!keyboardBusyExceptIn(codeEditorArea.value) && codeEditorHandler(event)) ||
    (!keyboardBusyExceptIn(documentationEditorArea.value) && documentationEditorHandler(event)) ||
    (!keyboardBusy() && graphNavigator.keyboardEvents.keydown(event))
})

useEvent(
  window,
  'pointerdown',
  (e) => interaction.handlePointerEvent(e, 'pointerdown', graphNavigator),
  {
    capture: true,
  },
)

useEvent(
  window,
  'pointerup',
  (e) => interaction.handlePointerEvent(e, 'pointerup', graphNavigator),
  {
    capture: true,
  },
)

// === Keyboard/Mouse bindings ===

const undoBindingsHandler = undoBindings.handler({
  undo() {
    graphStore.undoManager.undo()
  },
  redo() {
    graphStore.undoManager.redo()
  },
})

const graphBindingsHandler = graphBindings.handler({
  startProfiling() {
    projectStore.lsRpcConnection.profilingStart(true)
  },
  stopProfiling() {
    projectStore.lsRpcConnection.profilingStop()
  },
  openComponentBrowser() {
    if (graphNavigator.sceneMousePos != null && !componentBrowserVisible.value) {
      createWithComponentBrowser(fromSelection() ?? { placement: { type: 'mouse' } })
    }
  },
  deleteSelected,
  zoomToSelected() {
    zoomToSelected()
  },
  selectAll() {
    nodeSelection.selectAll()
  },
  deselectAll() {
    nodeSelection.deselectAll()
    clearFocus()
    graphStore.undoManager.undoStackBoundary()
  },
  toggleVisualization() {
    const selected = nodeSelection.selected
    const allVisible = every(
      selected,
      (id) => graphStore.db.nodeIdToNode.get(id)?.vis?.visible === true,
    )
    graphStore.transact(() => {
      for (const nodeId of selected) {
        graphStore.setNodeVisualization(nodeId, { visible: !allVisible })
      }
    })
  },
  copyNode() {
    copySelectionToClipboard()
  },
  pasteNode() {
    createNodesFromClipboard()
  },
  collapse() {
    collapseNodes()
  },
  enterNode() {
    const selectedNode = set.first(nodeSelection.selected)
    if (selectedNode) {
      stackNavigator.enterNode(selectedNode)
    }
  },
  exitNode() {
    stackNavigator.exitNode()
  },
  changeColorSelectedNodes() {
    showColorPicker.value = true
  },
  openDocumentation() {
    const failure = 'Unable to show node documentation'
    const selected = getSoleSelectionOrToast(failure)
    if (selected == null) return
    const suggestion = graphStore.db.nodeMainSuggestion.lookup(selected)
    const documentation = suggestion && suggestionDocumentationUrl(suggestion)
    if (documentation) {
      window.open(documentation, '_blank')
    } else {
      toasts.userActionFailed.show(`${failure}: no documentation available for selected node.`)
    }
  },
})

function getSoleSelectionOrToast(context: string) {
  if (nodeSelection.selected.size === 0) {
    toasts.userActionFailed.show(`${context}: no node selected.`)
  } else if (nodeSelection.selected.size > 1) {
    toasts.userActionFailed.show(`${context}: multiple nodes selected.`)
  } else {
    return set.first(nodeSelection.selected)
  }
}

const { handleClick } = useDoubleClick(
  (e: MouseEvent) => {
    if (e.target !== e.currentTarget) return false
    clearFocus()
  },
  (e: MouseEvent) => {
    if (e.target !== e.currentTarget) return false
    stackNavigator.exitNode()
  },
)

function deleteSelected() {
  graphStore.deleteNodes(nodeSelection.selected)
  nodeSelection.deselectAll()
}

// === Code Editor ===

const codeEditor = shallowRef<ComponentInstance<typeof CodeEditor>>()
const codeEditorArea = computed(() => unrefElement(codeEditor))
const showCodeEditor = ref(false)
const codeEditorHandler = codeEditorBindings.handler({
  toggle() {
    showCodeEditor.value = !showCodeEditor.value
  },
})

// === Documentation Editor ===

const docEditor = shallowRef<ComponentInstance<typeof DocumentationEditor>>()
const documentationEditorArea = computed(() => unrefElement(docEditor))
const showDocumentationEditor = computedFallback(
  storedShowDocumentationEditor,
  // Show documentation editor when documentation exists on first graph visit.
  () => !!documentation.state.value,
)

const documentationEditorHandler = documentationEditorBindings.handler({
  toggle() {
    showDocumentationEditor.value = !showDocumentationEditor.value
  },
})

const { documentation } = useAstDocumentation(graphStore, () =>
  unwrapOr(graphStore.methodAst, undefined),
)

// === Component Browser ===

const componentBrowserVisible = ref(false)
const componentBrowserNodePosition = ref<Vec2>(Vec2.Zero)
const componentBrowserUsage = ref<Usage>({ type: 'newNode' })

function openComponentBrowser(usage: Usage, position: Vec2) {
  componentBrowserUsage.value = usage
  componentBrowserNodePosition.value = position
  componentBrowserVisible.value = true
}

function hideComponentBrowser() {
  graphStore.editedNodeInfo = undefined
  componentBrowserVisible.value = false
}

function editWithComponentBrowser(node: NodeId, cursorPos: number) {
  openComponentBrowser(
    { type: 'editNode', node, cursorPos },
    graphStore.db.nodeIdToNode.get(node)?.position ?? Vec2.Zero,
  )
}

function createWithComponentBrowser(options: NewNodeOptions) {
  openComponentBrowser(
    { type: 'newNode', sourcePort: options.sourcePort },
    placeNode(options.placement, nodePlacement),
  )
}

function commitComponentBrowser(
  content: string,
  requiredImports: RequiredImport[],
  type: Typename | undefined,
) {
  if (graphStore.editedNodeInfo) {
    // We finish editing a node.
    graphStore.setNodeContent(graphStore.editedNodeInfo.id, content, requiredImports)
  } else if (content != '') {
    // We finish creating a new node.
    createNode({
      placement: { type: 'fixed', position: componentBrowserNodePosition.value },
      expression: content,
      type,
      requiredImports,
    })
  }
  hideComponentBrowser()
}

// Watch the `editedNode` in the graph store and synchronize component browser display with it.
watch(
  () => graphStore.editedNodeInfo,
  (editedInfo) => {
    if (editedInfo) {
      editWithComponentBrowser(editedInfo.id, editedInfo.initialCursorPos)
    } else {
      hideComponentBrowser()
    }
  },
)

// === Node Creation ===

interface NewNodeOptions {
  placement: PlacementStrategy
  sourcePort?: AstId | undefined
}

function addNodeDisconnected() {
  nodeSelection.deselectAll()
  createWithComponentBrowser({ placement: { type: 'viewport' } })
}

function fromSelection(): NewNodeOptions | undefined {
  if (graphStore.editedNodeInfo != null) return undefined
  const firstSelectedNode = set.first(nodeSelection.selected)
  if (firstSelectedNode == null) return undefined
  return {
    placement: { type: 'source', node: firstSelectedNode },
    sourcePort: graphStore.db.getNodeFirstOutputPort(firstSelectedNode),
  }
}

function clearFocus() {
  if (
    document.activeElement instanceof HTMLElement ||
    document.activeElement instanceof SVGElement
  ) {
    document.activeElement.blur()
  }
}

function createNodesFromSource(sourceNode: NodeId, options: NodeCreationOptions[]) {
  const sourcePort = graphStore.db.getNodeFirstOutputPort(sourceNode)
  if (sourcePort == null) return
  const sourcePortAst = graphStore.viewModule.get(sourcePort)
  const [toCommit, toEdit] = partition(options, (opts) => opts.commit)
  createNodes(
    toCommit.map((options: NodeCreationOptions) => ({
      placement: { type: 'source', node: sourceNode },
      expression: options.content!.instantiateCopied([sourcePortAst]).code(),
    })),
  )
  if (toEdit.length)
    createWithComponentBrowser({ placement: { type: 'source', node: sourceNode }, sourcePort })
}

function handleNodeOutputPortDoubleClick(id: AstId) {
  const srcNode = graphStore.db.getPatternExpressionNodeId(id)
  if (srcNode == null) {
    console.error('Impossible happened: Double click on port not belonging to any node: ', id)
    return
  }
  createWithComponentBrowser({ placement: { type: 'source', node: srcNode }, sourcePort: id })
}

function handleEdgeDrop(source: AstId, position: Vec2) {
  createWithComponentBrowser({ placement: { type: 'fixed', position }, sourcePort: source })
}

// === Node Collapsing ===

function collapseNodes() {
  const selected = nodeSelection.selected
  if (selected.size == 0) return
  try {
    const info = prepareCollapsedInfo(selected, graphStore.db)
    if (!info.ok) {
      toasts.userActionFailed.show(`Unable to group nodes: ${info.error.payload}.`)
      return
    }
    const currentMethod = projectStore.executionContext.getStackTop()
    const currentMethodName = graphStore.db.stackItemToMethodName(currentMethod)
    if (currentMethodName == null) {
      bail(`Cannot get the method name for the current execution stack item. ${currentMethod}`)
    }
    const topLevel = graphStore.topLevel
    if (!topLevel) {
      bail('BUG: no top level, collapsing not possible.')
    }
    const selectedNodeRects = filterDefined(Array.from(selected, graphStore.visibleArea))
    graphStore.edit((edit) => {
      const { refactoredExpressionAstId, collapsedNodeIds, outputNodeId } = performCollapse(
        info.value,
        edit.getVersion(topLevel),
        graphStore.db,
        currentMethodName,
      )
      const position = collapsedNodePlacement(selectedNodeRects)
      edit.get(refactoredExpressionAstId).mutableNodeMetadata().set('position', position.xy())
      if (outputNodeId != null) {
        const collapsedNodeRects = filterDefined(
          Array.from(collapsedNodeIds, graphStore.visibleArea),
        )
        const { place } = usePlacement(collapsedNodeRects, graphNavigator.viewport)
        const position = place(collapsedNodeRects)
        edit.get(refactoredExpressionAstId).mutableNodeMetadata().set('position', position.xy())
      }
    })
  } catch (err) {
    console.error('Error while collapsing, this is not normal.', err)
  }
}

// === Drag and drop ===

async function handleFileDrop(event: DragEvent) {
  // A vertical gap between created nodes when multiple files were dropped together.
  const MULTIPLE_FILES_GAP = 50

  if (!event.dataTransfer?.items) return
  const projectRootId = await projectStore.projectRootId
  if (projectRootId == null) {
    toasts.userActionFailed.show(`Unable to upload file(s): Could not identify project root.`)
    return
  }
  ;[...event.dataTransfer.items].forEach(async (item, index) => {
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (!file) return
      const clientPos = new Vec2(event.clientX, event.clientY)
      const offset = new Vec2(0, index * -MULTIPLE_FILES_GAP)
      const pos = graphNavigator.clientToScenePos(clientPos).add(offset)
      const uploader = Uploader.Create(
        projectStore.lsRpcConnection,
        projectStore.dataConnection,
        projectRootId,
        projectStore.awareness,
        file,
        pos,
        projectStore.isOnLocalBackend,
        event.shiftKey,
        projectStore.executionContext.getStackTop(),
      )
      const uploadResult = await uploader.upload()
      if (uploadResult.ok) {
        createNode({
          placement: { type: 'mouseEvent', position: pos },
          expression: uploadedExpression(uploadResult.value),
        })
      } else {
        uploadResult.error.log(`Uploading file failed`)
      }
    }
  })
}

// === Color Picker ===

provideNodeColors(graphStore, (variable) =>
  viewportNode.value ? getComputedStyle(viewportNode.value).getPropertyValue(variable) : '',
)

const showColorPicker = ref(false)

function setSelectedNodesColor(color: string | undefined) {
  graphStore.transact(() =>
    nodeSelection.selected.forEach((id) => graphStore.overrideNodeColor(id, color)),
  )
}

const groupColors = computed(() => {
  const styles: { [key: string]: string } = {}
  for (let group of suggestionDb.groups) {
    styles[groupColorVar(group)] = group.color ?? colorFromString(group.name)
  }
  return styles
})
</script>

<template>
  <div class="GraphEditor" @dragover.prevent @drop.prevent="handleFileDrop($event)">
    <div class="vertical">
      <div
        ref="viewportNode"
        class="viewport"
        :class="{ draggingEdge: graphStore.mouseEditedEdge != null }"
        :style="groupColors"
        v-on.="graphNavigator.pointerEvents"
        v-on..="nodeSelection.events"
        @click="handleClick"
        @pointermove.capture="graphNavigator.pointerEventsCapture.pointermove"
        @wheel.capture="graphNavigator.pointerEventsCapture.wheel"
      >
        <div class="layer" :style="{ transform: graphNavigator.transform }">
          <GraphNodes
            :graphNodeSelections="graphNodeSelections"
            @nodeOutputPortDoubleClick="handleNodeOutputPortDoubleClick"
            @nodeDoubleClick="(id) => stackNavigator.enterNode(id)"
            @createNodes="createNodesFromSource"
            @setNodeColor="setSelectedNodesColor"
          />
        </div>
        <div
          ref="graphNodeSelections"
          class="layer"
          :style="{ transform: graphNavigator.transform, 'z-index': -1 }"
        />
        <GraphEdges :navigator="graphNavigator" @createNodeFromEdge="handleEdgeDrop" />
        <ComponentBrowser
          v-if="componentBrowserVisible"
          ref="componentBrowser"
          :navigator="graphNavigator"
          :nodePosition="componentBrowserNodePosition"
          :usage="componentBrowserUsage"
          @accepted="commitComponentBrowser"
          @canceled="hideComponentBrowser"
        />
        <TopBar
          v-model:recordMode="projectStore.recordMode"
          v-model:showColorPicker="showColorPicker"
          v-model:showCodeEditor="showCodeEditor"
          v-model:showDocumentationEditor="showDocumentationEditor"
          :zoomLevel="100.0 * graphNavigator.targetScale"
          :componentsSelected="nodeSelection.selected.size"
          :class="{ extraRightSpace: !showDocumentationEditor }"
          @fitToAllClicked="zoomToSelected"
          @zoomIn="graphNavigator.stepZoom(+1)"
          @zoomOut="graphNavigator.stepZoom(-1)"
          @collapseNodes="collapseNodes"
          @removeNodes="deleteSelected"
        />
        <PlusButton title="Add Component" @click.stop="addNodeDisconnected()" />
        <SceneScroller
          :navigator="graphNavigator"
          :scrollableArea="Rect.Bounding(...graphStore.visibleNodeAreas)"
        />
        <GraphMouse />
      </div>
      <BottomPanel v-model:show="showCodeEditor">
        <Suspense>
          <CodeEditor ref="codeEditor" />
        </Suspense>
      </BottomPanel>
    </div>
    <DockPanel v-model:show="showDocumentationEditor" v-model:size="rightDockWidth">
      <template #default="{ toolbar }">
        <DocumentationEditor
          ref="docEditor"
          :modelValue="documentation.state.value"
          :toolbarContainer="toolbar"
          @update:modelValue="documentation.set"
        />
      </template>
    </DockPanel>
  </div>
</template>

<style scoped>
.GraphEditor {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  right: 0;
  contain: layout;
  user-select: none;
  /* Prevent touchpad back gesture, which can be triggered while panning. */
  overscroll-behavior-x: none;

  display: flex;
  flex-direction: row;
  & :deep(.DockPanel) {
    flex: none;
  }
  & .vertical {
    flex: auto;
    min-width: 0;
  }
}

.vertical {
  display: flex;
  flex-direction: column;
  & :deep(.BottomPanel) {
    flex: none;
  }
  & .viewport {
    flex: auto;
    min-height: 0;
  }
}

.viewport {
  contain: layout;
  overflow: clip;
  --group-color-fallback: #006b8a;
  --node-color-no-type: #596b81;
}

.layer {
  position: absolute;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
  contain: layout size style;
  will-change: transform;
}

.layer.nodes:deep(::selection) {
  background-color: rgba(255, 255, 255, 20%);
}
</style>
