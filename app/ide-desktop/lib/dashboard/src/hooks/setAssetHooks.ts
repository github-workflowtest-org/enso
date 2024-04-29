/** @file A hook that turns a `set` function for an {@link AssetTreeNode} to a `set` function
 * on its item, for a specific type of item. */
import * as React from 'react'

import type * as backend from '#/services/Backend'

import type * as assetTreeNode from '#/utilities/AssetTreeNode'
import AssetTreeNode from '#/utilities/AssetTreeNode'

// ===================
// === useSetAsset ===
// ===================

/** Converts a React set state action for an {@link AssetTreeNode} to a set state action for any
 * subset of {@link backend.AnyAsset}. This is unsafe when `T` does not match the type of the
 * item contained in the `AssetTreeNode`, so this MUST be guarded by checking that the item is of
 * the correct type. A value of type `T` must be provided as the first parameter to ensure that this
 * has been done. */
export function useSetAsset<T extends backend.AnyAsset>(
  _value: T,
  setNode: React.Dispatch<React.SetStateAction<assetTreeNode.AnyAssetTreeNode>>
) {
  return React.useCallback(
    (valueOrUpdater: React.SetStateAction<T>) => {
      setNode(oldNode => {
        const value =
          typeof valueOrUpdater === 'function'
            ? // This is SAFE, because it is a mistake for an item to change type.
              // eslint-disable-next-line no-restricted-syntax
              valueOrUpdater(oldNode.item.value as T)
            : valueOrUpdater
        // This is SAFE, because it is a mistake for an item to change type.
        // eslint-disable-next-line @typescript-eslint/no-unsafe-argument, no-restricted-syntax, @typescript-eslint/no-explicit-any
        const result = oldNode.with({ item: oldNode.item.withValue(value as any) })
        return result
      })
    },
    [/* should never change */ setNode]
  )
}
