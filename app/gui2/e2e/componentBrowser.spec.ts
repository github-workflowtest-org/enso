import { test, type Page } from '@playwright/test'
import assert from 'assert'
import os from 'os'
import * as actions from './actions'
import { expect } from './customExpect'
import * as locate from './locate'

const CONTROL_KEY = os.platform() === 'darwin' ? 'Meta' : 'Control'
const ACCEPT_SUGGESTION_SHORTCUT = `${CONTROL_KEY}+Enter`

async function deselectAllNodes(page: Page) {
  await page.keyboard.press('Escape')
  await expect(page.locator('.GraphNode.selected')).toHaveCount(0)
}

test('Different ways of opening Component Browser', async ({ page }) => {
  await actions.goToGraph(page)
  const nodeCount = await locate.graphNode(page).count()

  async function expectAndCancelBrowser(expectedInput: string) {
    await expect(locate.componentBrowser(page)).toExist()
    await expect(locate.componentBrowserEntry(page)).toExist()
    await expect(locate.componentBrowserInput(page).locator('input')).toHaveValue(expectedInput)
    await page.keyboard.press('Escape')
    await expect(locate.componentBrowser(page)).not.toBeVisible()
    await expect(locate.graphNode(page)).toHaveCount(nodeCount)
  }

  // Without source node

  // (+) button
  await locate.addNewNodeButton(page).click()
  await expectAndCancelBrowser('')
  // Enter key
  await locate.graphEditor(page).press('Enter')
  await expectAndCancelBrowser('')

  // With source node

  // (+) button
  await locate.graphNodeByBinding(page, 'final').click()
  await locate.addNewNodeButton(page).click()
  await expectAndCancelBrowser('final.')
  // Enter key
  await locate.graphNodeByBinding(page, 'final').click()
  await locate.graphEditor(page).press('Enter')
  await expectAndCancelBrowser('final.')
  // Dragging out an edge
  // `click` method of locator could be simpler, but `position` option doesn't work.
  const outputPortArea = await locate
    .graphNodeByBinding(page, 'final')
    .locator('.outputPortHoverArea')
    .boundingBox()
  assert(outputPortArea)
  const outputPortX = outputPortArea.x + outputPortArea.width / 2.0
  const outputPortY = outputPortArea.y + outputPortArea.height - 2.0
  await page.mouse.click(outputPortX, outputPortY)
  await page.mouse.click(40, 300)
  await expectAndCancelBrowser('final.')
  // Double-clicking port
  // TODO[ao] Without timeout, even the first click would be treated as double due to previous
  // event. Probably we need a better way to simulate double clicks.
  await page.waitForTimeout(600)
  await page.mouse.click(outputPortX, outputPortY)
  await page.mouse.click(outputPortX, outputPortY)
  await expectAndCancelBrowser('final.')
})

test('Accepting suggestion', async ({ page }) => {
  // Clicking enry
  await actions.goToGraph(page)
  await locate.addNewNodeButton(page).click()
  let nodeCount = await locate.graphNode(page).count()
  await locate.componentBrowserEntry(page).nth(1).click()
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(locate.graphNode(page)).toHaveCount(nodeCount + 1)
  await expect(locate.graphNode(page).last().locator('.WidgetToken')).toHaveText([
    'Data',
    '.',
    'read_text',
  ])
  await expect(locate.graphNode(page).last()).toBeSelected()

  // Clicking at highlighted entry
  nodeCount = await locate.graphNode(page).count()
  await deselectAllNodes(page)
  await locate.addNewNodeButton(page).click()
  await locate.componentBrowserSelectedEntry(page).first().click()
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(locate.graphNode(page)).toHaveCount(nodeCount + 1)
  await expect(locate.graphNode(page).last().locator('.WidgetToken')).toHaveText([
    'Data',
    '.',
    'read',
  ])
  await expect(locate.graphNode(page).last()).toBeSelected()

  // Accepting with Enter
  nodeCount = await locate.graphNode(page).count()
  await deselectAllNodes(page)
  await locate.addNewNodeButton(page).click()
  await page.keyboard.press('Enter')
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(locate.graphNode(page)).toHaveCount(nodeCount + 1)
  await expect(locate.graphNode(page).last().locator('.WidgetToken')).toHaveText([
    'Data',
    '.',
    'read',
  ])
  await expect(locate.graphNode(page).last()).toBeSelected()
})

test('Accepting any written input', async ({ page }) => {
  await actions.goToGraph(page)
  await locate.addNewNodeButton(page).click()
  const nodeCount = await locate.graphNode(page).count()
  await locate.componentBrowserInput(page).locator('input').fill('re')
  await page.keyboard.press(ACCEPT_SUGGESTION_SHORTCUT)
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(locate.graphNode(page)).toHaveCount(nodeCount + 1)
  await expect(locate.graphNode(page).last().locator('.WidgetToken')).toHaveText('re')
})

test('Filling input with suggestions', async ({ page }) => {
  await actions.goToGraph(page)
  await locate.addNewNodeButton(page).click()

  // Entering module
  await locate.componentBrowserEntryByLabel(page, 'Standard.Base.Data').click()
  await expect(locate.componentBrowser(page)).toExist()
  await expect(locate.componentBrowserInput(page).locator('input')).toHaveValue(
    'Standard.Base.Data.',
  )

  // Applying suggestion
  await page.keyboard.press('Tab')
  await expect(locate.componentBrowser(page)).toExist()
  await expect(locate.componentBrowserInput(page).locator('input')).toHaveValue(
    'Standard.Base.Data.read ',
  )
})

test('Filtering list', async ({ page }) => {
  await actions.goToGraph(page)
  await locate.addNewNodeButton(page).click()
  await locate.componentBrowserInput(page).locator('input').fill('re_te')
  const segments = locate.componentBrowserEntry(page).locator('.component-label-segment')
  await expect(segments).toHaveText(['Data.', 're', 'ad', '_te', 'xt'])
  const highlighted = locate.componentBrowserEntry(page).locator('.component-label-segment.match')
  await expect(highlighted).toHaveText(['re', '_te'])
})

test('Editing existing nodes', async ({ page }) => {
  await actions.goToGraph(page)
  const node = locate.graphNodeByBinding(page, 'data')
  const ADDED_PATH = '"/home/enso/Input.txt"'

  // Start node editing
  await locate.graphNodeIcon(node).click({ modifiers: [CONTROL_KEY] })
  await expect(locate.componentBrowser(page)).toBeVisible()
  const input = locate.componentBrowserInput(page).locator('input')
  await expect(input).toHaveValue('Data.read')

  // Add argument and accept
  await page.keyboard.press('End')
  await input.pressSequentially(` ${ADDED_PATH}`)
  await expect(input).toHaveValue(`Data.read ${ADDED_PATH}`)
  await page.keyboard.press('Enter')
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(node.locator('.WidgetToken')).toHaveText(['Data', '.', 'read', '"', '"'])
  await expect(node.locator('.WidgetText input')).toHaveValue(ADDED_PATH.replaceAll('"', ''))

  // Edit again, using "edit" button
  await locate.graphNodeIcon(node).click()
  await node.getByTestId('edit-button').click()
  await expect(locate.componentBrowser(page)).toBeVisible()
  await expect(input).toHaveValue(`Data.read ${ADDED_PATH}`)
  for (let i = 0; i < ADDED_PATH.length; ++i) await page.keyboard.press('Backspace')
  await expect(input).toHaveValue('Data.read ')
  await page.keyboard.press('Enter')
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(node.locator('.WidgetToken')).toHaveText(['Data', '.', 'read'])
  await expect(node.locator('.WidgetText')).not.toBeVisible()
})

test('Visualization preview: type-based visualization selection', async ({ page }) => {
  await actions.goToGraph(page)
  const nodeCount = await locate.graphNode(page).count()
  await locate.addNewNodeButton(page).click()
  await expect(locate.componentBrowser(page)).toExist()
  await expect(locate.componentBrowserEntry(page)).toExist()
  const input = locate.componentBrowserInput(page).locator('input')
  await input.fill('4')
  await expect(input).toHaveValue('4')
  await expect(locate.jsonVisualization(page)).toExist()
  await input.fill('Table.ne')
  await expect(input).toHaveValue('Table.ne')
  // The table visualization is not currently working with `executeExpression` (#9194), but we can test that the JSON
  // visualization is no longer selected.
  await expect(locate.jsonVisualization(page)).not.toBeVisible()
  await page.keyboard.press('Escape')
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(locate.graphNode(page)).toHaveCount(nodeCount)
})

test('Visualization preview: user visualization selection', async ({ page }) => {
  await actions.goToGraph(page)
  const nodeCount = await locate.graphNode(page).count()
  await locate.addNewNodeButton(page).click()
  await expect(locate.componentBrowser(page)).toExist()
  await expect(locate.componentBrowserEntry(page)).toExist()
  const input = locate.componentBrowserInput(page).locator('input')
  await input.fill('4')
  await expect(input).toHaveValue('4')
  await expect(locate.jsonVisualization(page)).toExist()
  await locate.showVisualizationSelectorButton(page).click()
  await page.getByRole('button', { name: 'Table' }).click()
  // The table visualization is not currently working with `executeExpression` (#9194), but we can test that the JSON
  // visualization is no longer selected.
  await expect(locate.jsonVisualization(page)).not.toBeVisible()
  await page.keyboard.press('Escape')
  await expect(locate.componentBrowser(page)).not.toBeVisible()
  await expect(locate.graphNode(page)).toHaveCount(nodeCount)
})
