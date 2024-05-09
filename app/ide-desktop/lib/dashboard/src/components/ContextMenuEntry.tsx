/** @file An entry in a context menu. */
import * as React from 'react'

import type * as menuEntry from '#/components/MenuEntry'
import MenuEntry from '#/components/MenuEntry'

// ========================
// === ContextMenuEntry ===
// ========================

/** Props for a {@link ContextMenuEntry}. */
export interface ContextMenuEntryProps
  extends Omit<menuEntry.MenuEntryProps, 'isContextMenuEntry'> {}

/** An item in a menu. */
export default function ContextMenuEntry(props: ContextMenuEntryProps) {
  return <MenuEntry isContextMenuEntry {...props} />
}
