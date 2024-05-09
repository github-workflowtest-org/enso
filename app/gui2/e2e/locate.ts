import { expect, type Locator, type Page } from '@playwright/test'
import assert from 'assert'
import cssEscape from 'css.escape'

// ==============
// === Filter ===
// ==============

class Filter {
  constructor(public selector = '') {}

  visible<T extends { selector: string }>(this: T): Omit<T, 'visible'> {
    return new Filter(this.selector + ':visible') as any
  }

  first<T extends { selector: string }>(this: T): Omit<T, 'first' | 'last'> {
    return new Filter(this.selector + ':first') as any
  }

  last<T extends { selector: string }>(this: T): Omit<T, 'first' | 'last'> {
    return new Filter(this.selector + ':last') as any
  }

  id<T extends { selector: string }>(this: T, id: string): Omit<T, 'id'> {
    return new Filter(this.selector + '#' + cssEscape(id)) as any
  }

  class(...classes: string[]) {
    return new Filter(this.selector + '.' + classes.map(cssEscape).join('.'))
  }

  toString() {
    return this.selector
  }
}

// ================
// === Locators ===
// ================

// === Button locators ===

function or(a: (page: Locator | Page) => Locator, b: (page: Locator | Page) => Locator) {
  return (page: Locator | Page) => a(page).or(b(page))
}

export function playOrOpenProjectButton(page: Locator | Page) {
  return page.getByAltText('Open in editor')
}

// === Auto-evaluation ===

export function enableAutoEvaluationButton(page: Locator | Page) {
  return page.getByAltText('Enable auto-evaluation')
}

export function disableAutoEvaluationButton(page: Locator | Page) {
  return page.getByAltText('Disable auto-evaluation')
}

export const toggleAutoEvaluationButton = or(
  enableAutoEvaluationButton,
  disableAutoEvaluationButton,
)

// === Documentation ===

export function showDocumentationButton(page: Locator | Page) {
  return page.getByAltText('Show documentation')
}

export function hideDocumentationButton(page: Locator | Page) {
  return page.getByAltText('Hide documentation')
}

export const toggleDocumentationButton = or(showDocumentationButton, hideDocumentationButton)

// === Visualization ===

export function showVisualizationButton(page: Locator | Page) {
  return page.getByAltText('Show visualization')
}

export function hideVisualizationButton(page: Locator | Page) {
  return page.getByAltText('Hide visualization')
}

export const toggleVisualizationButton = or(showVisualizationButton, hideVisualizationButton)

// === Visualization selector ===

export function showVisualizationSelectorButton(page: Locator | Page) {
  return page.getByAltText('Show visualization selector')
}

export function hideVisualizationSelectorButton(page: Locator | Page) {
  return page.getByAltText('Hide visualization selector')
}

export const toggleVisualizationSelectorButton = or(
  showVisualizationSelectorButton,
  hideVisualizationSelectorButton,
)

// === Fullscreen ===

export function enterFullscreenButton(page: Locator | Page) {
  return page.getByAltText('Enter fullscreen')
}

export function exitFullscreenButton(page: Locator | Page) {
  return page.getByAltText('Exit fullscreen')
}

export const toggleFullscreenButton = or(enterFullscreenButton, exitFullscreenButton)

// === Nodes ===

declare const nodeLocatorBrand: unique symbol
export type Node = Locator & { [nodeLocatorBrand]: never }

export function graphNode(page: Page | Locator): Node {
  return page.locator('.GraphNode') as Node
}
export function graphNodeByBinding(page: Locator | Page, binding: string): Node {
  return graphNode(page).filter({ has: page.locator('.binding', { hasText: binding }) }) as Node
}
export function graphNodeIcon(node: Node) {
  return node.locator('.nodeCategoryIcon')
}
export function selectedNodes(page: Page | Locator): Node {
  return page.locator('.GraphNode.selected') as Node
}

// === Data locators ===

type SanitizeClassName<T extends string> =
  T extends `${infer A}.${infer B}` ? SanitizeClassName<`${A}${B}`>
  : T extends `${infer A} ${infer B}` ? SanitizeClassName<`${A}${B}`>
  : T

function componentLocator<T extends string>(className: SanitizeClassName<T>) {
  return (page: Locator | Page, filter?: (f: Filter) => { selector: string }) => {
    return page.locator(`.${className}${filter?.(new Filter()) ?? ''}`)
  }
}

export const graphEditor = componentLocator('GraphEditor')
// @ts-expect-error
export const anyVisualization = componentLocator('GraphVisualization > *')
export const loadingVisualization = componentLocator('LoadingVisualization')
export const circularMenu = componentLocator('CircularMenu')
export const addNewNodeButton = componentLocator('PlusButton')
export const componentBrowser = componentLocator('ComponentBrowser')
export const nodeOutputPort = componentLocator('outputPortHoverArea')
export const smallPlusButton = componentLocator('SmallPlusButton')

export function componentBrowserEntry(
  page: Locator | Page,
  filter?: (f: Filter) => { selector: string },
) {
  return page.locator(
    `.ComponentBrowser .list-variant:not(.selected) .component${filter?.(new Filter()) ?? ''}`,
  )
}

export function componentBrowserSelectedEntry(
  page: Locator | Page,
  filter?: (f: Filter) => { selector: string },
) {
  return page.locator(
    `.ComponentBrowser .list-variant.selected .component${filter?.(new Filter()) ?? ''}`,
  )
}

export function componentBrowserEntryByLabel(page: Locator | Page, label: string) {
  return componentBrowserEntry(page).filter({ has: page.getByText(label) })
}

export const navBreadcrumb = componentLocator('NavBreadcrumb')
export const componentBrowserInput = componentLocator('CBInput')
export const jsonVisualization = componentLocator('JSONVisualization')
export const tableVisualization = componentLocator('TableVisualization')
export const scatterplotVisualization = componentLocator('ScatterplotVisualization')
export const histogramVisualization = componentLocator('HistogramVisualization')
export const heatmapVisualization = componentLocator('HeatmapVisualization')
export const sqlVisualization = componentLocator('SqlVisualization')
export const geoMapVisualization = componentLocator('GeoMapVisualization')
export const imageBase64Visualization = componentLocator('ImageBase64Visualization')
export const warningsVisualization = componentLocator('WarningsVisualization')

// === Edge locators ===

export async function edgesFromNodeWithBinding(page: Page, binding: string) {
  const node = graphNodeByBinding(page, binding).first()
  const nodeId = await node.getAttribute('data-node-id')
  return page.locator(`[data-source-node-id="${nodeId}"]`)
}

export async function edgesToNodeWithBinding(page: Page, binding: string) {
  const node = graphNodeByBinding(page, binding).first()
  const nodeId = await node.getAttribute('data-node-id')
  return page.locator(`[data-target-node-id="${nodeId}"]`)
}

// === Output ports ===

/** Returns a location that can be clicked to activate an output port.
 *  Using a `Locator` would be better, but `position` option of `click` doesn't work.
 */
export async function outputPortCoordinates(node: Locator) {
  const outputPortArea = await node.locator('.outputPortHoverArea').boundingBox()
  expect(outputPortArea).not.toBeNull()
  assert(outputPortArea)
  const centerX = outputPortArea.x + outputPortArea.width / 2
  const bottom = outputPortArea.y + outputPortArea.height
  return { x: centerX, y: bottom - 2.0 }
}
