/** @file A column displaying the time at which the asset was last modified. */
import * as React from 'react'

import type * as column from '#/components/dashboard/column'

import * as dateTime from '#/utilities/dateTime'

// ======================
// === ModifiedColumn ===
// ======================

/** A column displaying the time at which the asset was last modified. */
export default function ModifiedColumn(props: column.AssetColumnProps) {
  const { item } = props

  return <>{dateTime.formatDateTime(new Date(item.modifiedAt))}</>
}
