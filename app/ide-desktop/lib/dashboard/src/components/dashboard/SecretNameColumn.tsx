/** @file The icon and name of a {@link backendModule.SecretAsset}. */
import * as React from 'react'

import * as tailwindMerge from 'tailwind-merge'

import KeyIcon from 'enso-assets/key.svg'

import * as backendHooks from '#/hooks/backendHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as inputBindingsProvider from '#/providers/InputBindingsProvider'
import * as modalProvider from '#/providers/ModalProvider'

import * as aria from '#/components/aria'
import type * as column from '#/components/dashboard/column'
import SvgMask from '#/components/SvgMask'

import UpsertSecretModal from '#/modals/UpsertSecretModal'

import * as backendModule from '#/services/Backend'

import * as eventModule from '#/utilities/event'
import * as indent from '#/utilities/indent'
import * as object from '#/utilities/object'

// =====================
// === ConnectorName ===
// =====================

/** Props for a {@link SecretNameColumn}. */
export interface SecretNameColumnProps extends column.AssetColumnProps {}

/** The icon and name of a {@link backendModule.SecretAsset}.
 * @throws {Error} when the asset is not a {@link backendModule.SecretAsset}.
 * This should never happen. */
export default function SecretNameColumn(props: SecretNameColumnProps) {
  const { item, depth, selected, state, rowState, setRowState, isEditable } = props
  const { backend } = state
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const { setModal } = modalProvider.useSetModal()
  const inputBindings = inputBindingsProvider.useInputBindings()
  if (item.type !== backendModule.AssetType.secret) {
    // eslint-disable-next-line no-restricted-syntax
    throw new Error('`SecretNameColumn` can only display secrets.')
  }

  const updateSecretMutation = backendHooks.useBackendMutation(backend, 'updateSecret')

  const setIsEditing = (isEditingName: boolean) => {
    if (isEditable) {
      setRowState(object.merger({ isEditingName }))
    }
  }

  const handleClick = inputBindings.handler({
    editName: () => {
      setIsEditing(true)
    },
  })

  return (
    <div
      className={tailwindMerge.twMerge(
        'flex h-full min-w-max items-center gap-name-column-icon whitespace-nowrap rounded-l-full px-name-column-x py-name-column-y',
        indent.indentClass(depth)
      )}
      onKeyDown={event => {
        if (rowState.isEditingName && event.key === 'Enter') {
          event.stopPropagation()
        }
      }}
      onClick={event => {
        if (handleClick(event)) {
          // Already handled.
        } else if (eventModule.isSingleClick(event) && selected) {
          setIsEditing(true)
        } else if (eventModule.isDoubleClick(event) && isEditable) {
          event.stopPropagation()
          setModal(
            <UpsertSecretModal
              id={item.id}
              name={item.title}
              doCreate={async (_name, value) => {
                try {
                  await updateSecretMutation.mutateAsync([item.id, { value }, item.title])
                } catch (error) {
                  toastAndLog(null, error)
                }
              }}
            />
          )
        }
      }}
    >
      <SvgMask src={KeyIcon} className="m-name-column-icon size-icon" />
      {/* Secrets cannot be renamed. */}
      <aria.Text data-testid="asset-row-name" className="text grow bg-transparent">
        {item.title}
      </aria.Text>
    </div>
  )
}
