import { provideGuiConfig, type GuiConfig } from '@/providers/guiConfig'
import { provideWidgetRegistry } from '@/providers/widgetRegistry'
import { useGraphStore } from '@/stores/graph'
import { GraphDb } from '@/stores/graph/graphDatabase'
import { useProjectStore } from '@/stores/project'
import { ComputedValueRegistry } from '@/stores/project/computedValueRegistry'
import { Ast } from '@/util/ast'
import { MockTransport, MockWebSocket } from '@/util/net'
import { getActivePinia } from 'pinia'
import { ref, type App } from 'vue'
import { mockDataHandler, mockLSHandler } from './engine'
export * as vue from './vue'

export function languageServer() {
  MockTransport.addMock('engine', mockLSHandler)
}

export function dataServer() {
  MockWebSocket.addMock('data', mockDataHandler)
}

export function guiConfig(app: App) {
  return provideGuiConfig._mock(
    ref<GuiConfig>({
      startup: {
        project: 'Mock Project',
        displayedProjectName: 'Mock Project',
      },
      engine: {
        rpcUrl: 'mock://engine',
        dataUrl: 'mock://data',
        ydocUrl: '',
        namespace: 'local',
        projectManagerUrl: '',
      },
      window: {
        topBarOffset: 96,
        vibrancy: false,
      },
      authentication: {
        enabled: true,
        email: '',
      },
    }),
    app,
  )
}

export const computedValueRegistry = ComputedValueRegistry.Mock
export const graphDb = GraphDb.Mock

export function widgetRegistry(app: App) {
  return widgetRegistry.withGraphDb(graphDb())(app)
}

widgetRegistry.withGraphDb = function widgetRegistryWithGraphDb(graphDb: GraphDb) {
  return (app: App) => provideWidgetRegistry._mock([graphDb], app)
}

export function graphStore(): ReturnType<typeof useGraphStore> {
  return useGraphStore(getActivePinia())
}

export function projectStore(): ReturnType<typeof useProjectStore> {
  const projectStore = useProjectStore(getActivePinia())
  const mod = projectStore.projectModel.createNewModule('Main.enso')
  mod.doc.ydoc.emit('load', [])
  const syncModule = new Ast.MutableModule(mod.doc.ydoc)
  syncModule.transact(() => {
    const root = Ast.parseBlock('main =\n', syncModule)
    syncModule.replaceRoot(root)
  })
  return projectStore
}

/** The stores should be initialized in this order, as `graphStore` depends on `projectStore`. */
export function projectStoreAndGraphStore(): readonly [
  ReturnType<typeof useProjectStore>,
  ReturnType<typeof useGraphStore>,
] {
  return [projectStore(), graphStore()] as const
}

export function waitForMainModule(projectStore?: ReturnType<typeof useProjectStore>) {
  const definedProjectStore = projectStore ?? useProjectStore(getActivePinia())
  return new Promise((resolve, reject) => {
    const handle1 = window.setInterval(() => {
      if (definedProjectStore.module != null) {
        window.clearInterval(handle1)
        window.clearTimeout(handle2)
        resolve(definedProjectStore.module)
      }
    }, 10)
    const handle2 = window.setTimeout(() => {
      window.clearInterval(handle1)
      reject()
    }, 5_000)
  })
}
