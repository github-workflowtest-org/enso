import { test } from '@playwright/test'
import assert from 'assert'
import * as actions from './actions'
import { expect } from './customExpect'
import * as locate from './locate'

test('Selecting nodes by click', async ({ page }) => {
  await actions.goToGraph(page)
  const node1 = locate.graphNodeByBinding(page, 'five')
  const node2 = locate.graphNodeByBinding(page, 'final')
  const selectionMenu = page.locator('.SelectionMenu')
  await expect(node1).not.toBeSelected()
  await expect(node2).not.toBeSelected()
  await expect(selectionMenu).not.toBeVisible()

  await locate.graphNodeIcon(node1).click()
  await expect(node1).toBeSelected()
  await expect(node2).not.toBeSelected()
  await expect(selectionMenu).not.toBeVisible()

  // Check that clicking an unselected node deselects replaces the previous selection.
  await locate.graphNodeIcon(node2).click()
  await expect(node1).not.toBeSelected()
  await expect(node2).toBeSelected()
  await expect(selectionMenu).not.toBeVisible()

  await page.waitForTimeout(300) // Avoid double clicks
  await locate.graphNodeIcon(node1).click({ modifiers: ['Shift'] })
  await expect(node1).toBeSelected()
  await expect(node2).toBeSelected()
  await expect(selectionMenu).toBeVisible()

  // Check that when two nodes are selected, clicking a selected node replaces the previous selection.
  await locate.graphNodeIcon(node2).click()
  await expect(node1).not.toBeSelected()
  await expect(node2).toBeSelected()
  await expect(selectionMenu).not.toBeVisible()

  // Check that clicking the background deselects all nodes.
  await locate.graphEditor(page).click({ position: { x: 600, y: 200 } })
  await expect(node1).not.toBeSelected()
  await expect(node2).not.toBeSelected()
  await expect(selectionMenu).not.toBeVisible()
})

test('Selecting nodes by area drag', async ({ page }) => {
  await actions.goToGraph(page)
  const node1 = locate.graphNodeByBinding(page, 'five')
  const node2 = locate.graphNodeByBinding(page, 'ten')
  await expect(node1).not.toBeSelected()
  await expect(node2).not.toBeSelected()

  const node1Id = await node1.getAttribute('data-node-id')
  const node1Selection = page.locator(`.GraphNodeSelection[data-node-id="${node1Id}"]`)
  const node1BBox = await node1Selection.boundingBox()
  const node2BBox = await node2.boundingBox()
  assert(node1BBox)
  assert(node2BBox)
  await page.mouse.move(node1BBox.x - 50, node1BBox.y - 50)
  await page.mouse.down()
  await page.mouse.move(node1BBox.x - 49, node1BBox.y - 49)
  await expect(page.locator('.SelectionBrush')).toBeVisible()
  await page.mouse.move(node2BBox.x + node2BBox.width, node2BBox.y + node2BBox.height)
  await expect(node1).toBeSelected()
  await expect(node2).toBeSelected()
  await page.mouse.up()
  await expect(node1).toBeSelected()
  await expect(node2).toBeSelected()
})

test('Deleting selected node with backspace key', async ({ page }) => {
  await actions.goToGraph(page)

  const nodesCount = await locate.graphNode(page).count()
  const deletedNode = locate.graphNodeByBinding(page, 'final')
  await deletedNode.click()
  await page.keyboard.press('Backspace')
  await expect(locate.graphNode(page)).toHaveCount(nodesCount - 1)
})

test('Deleting selected node with delete key', async ({ page }) => {
  await actions.goToGraph(page)

  const nodesCount = await locate.graphNode(page).count()
  const deletedNode = locate.graphNodeByBinding(page, 'final')
  await deletedNode.click()
  await page.keyboard.press('Delete')
  await expect(locate.graphNode(page)).toHaveCount(nodesCount - 1)
})
