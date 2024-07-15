/** @file Display and modify the properties of an asset. */
import * as React from 'react'

import PenIcon from 'enso-assets/pen.svg'

import * as datalinkValidator from '#/data/datalinkValidator'

import * as backendHooks from '#/hooks/backendHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as authProvider from '#/providers/AuthProvider'
import * as textProvider from '#/providers/TextProvider'

import type Category from '#/layouts/CategorySwitcher/Category'

import * as aria from '#/components/aria'
import * as ariaComponents from '#/components/AriaComponents'
import SharedWithColumn from '#/components/dashboard/column/SharedWithColumn'
import DatalinkInput from '#/components/dashboard/DatalinkInput'
import Label from '#/components/dashboard/Label'
import StatelessSpinner, * as statelessSpinner from '#/components/StatelessSpinner'

import * as backendModule from '#/services/Backend'
import type Backend from '#/services/Backend'
import * as localBackend from '#/services/LocalBackend'

import type * as assetTreeNode from '#/utilities/AssetTreeNode'
import * as object from '#/utilities/object'
import * as permissions from '#/utilities/permissions'

// =======================
// === AssetProperties ===
// =======================

/** Props for an {@link AssetPropertiesProps}. */
export interface AssetPropertiesProps {
  readonly backend: Backend
  readonly item: assetTreeNode.AnyAssetTreeNode
  readonly setItem: React.Dispatch<React.SetStateAction<assetTreeNode.AnyAssetTreeNode>>
  readonly category: Category
  readonly isReadonly?: boolean
}

/** Display and modify the properties of an asset. */
export default function AssetProperties(props: AssetPropertiesProps) {
  const { backend, item: itemRaw, setItem: setItemRaw, category } = props
  const { isReadonly = false } = props

  const { user } = authProvider.useNonPartialUserSession()
  const { getText } = textProvider.useText()
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const [item, setItemInner] = React.useState(itemRaw)
  const [isEditingDescription, setIsEditingDescription] = React.useState(false)
  const [queuedDescription, setQueuedDescripion] = React.useState<string | null>(null)
  const [description, setDescription] = React.useState('')
  const [datalinkValue, setDatalinkValue] = React.useState<NonNullable<unknown> | null>(null)
  const [editedDatalinkValue, setEditedDatalinkValue] = React.useState<NonNullable<unknown> | null>(
    datalinkValue
  )
  const [isDatalinkFetched, setIsDatalinkFetched] = React.useState(false)
  const isDatalinkSubmittable = React.useMemo(
    () => datalinkValidator.validateDatalink(datalinkValue),
    [datalinkValue]
  )
  const setItem = React.useCallback(
    (valueOrUpdater: React.SetStateAction<assetTreeNode.AnyAssetTreeNode>) => {
      setItemInner(valueOrUpdater)
      setItemRaw(valueOrUpdater)
    },
    [setItemRaw]
  )
  const labels = backendHooks.useBackendListTags(backend) ?? []
  const self = item.item.permissions?.find(
    backendModule.isUserPermissionAnd(permission => permission.user.userId === user.userId)
  )
  const ownsThisAsset = self?.permission === permissions.PermissionAction.own
  const canEditThisAsset =
    ownsThisAsset ||
    self?.permission === permissions.PermissionAction.admin ||
    self?.permission === permissions.PermissionAction.edit
  const isDatalink = item.item.type === backendModule.AssetType.datalink
  const isDatalinkDisabled = datalinkValue === editedDatalinkValue || !isDatalinkSubmittable
  const isCloud = backend.type === backendModule.BackendType.remote
  const path = isCloud
    ? null
    : item.item.type === backendModule.AssetType.project
      ? item.item.projectState.path ?? null
      : localBackend.extractTypeAndId(item.item.id).id

  const createDatalinkMutation = backendHooks.useBackendMutation(backend, 'createDatalink')
  const getDatalinkMutation = backendHooks.useBackendMutation(backend, 'getDatalink')
  const updateAssetMutation = backendHooks.useBackendMutation(backend, 'updateAsset')
  const getDatalinkMutate = getDatalinkMutation.mutateAsync

  React.useEffect(() => {
    setDescription(item.item.description ?? '')
  }, [item.item.description])

  React.useEffect(() => {
    void (async () => {
      if (item.item.type === backendModule.AssetType.datalink) {
        const value = await getDatalinkMutate([item.item.id, item.item.title])
        setDatalinkValue(value)
        setEditedDatalinkValue(value)
        setIsDatalinkFetched(true)
      }
    })()
  }, [backend, item.item, getDatalinkMutate])

  const doEditDescription = async () => {
    setIsEditingDescription(false)
    if (description !== item.item.description) {
      const oldDescription = item.item.description
      setItem(oldItem => oldItem.with({ item: object.merge(oldItem.item, { description }) }))
      try {
        const projectPath = item.item.projectState?.path
        await updateAssetMutation.mutateAsync([
          item.item.id,
          {
            parentDirectoryId: null,
            description,
            ...(projectPath == null ? {} : { projectPath }),
          },
          item.item.title,
        ])
      } catch (error) {
        toastAndLog('editDescriptionError')
        setItem(oldItem =>
          oldItem.with({ item: object.merge(oldItem.item, { description: oldDescription }) })
        )
      }
    }
  }

  return (
    <>
      <div className="pointer-events-auto flex flex-col items-start gap-side-panel">
        <aria.Heading
          level={2}
          className="flex h-side-panel-heading items-center gap-side-panel-section py-side-panel-heading-y text-lg leading-snug"
        >
          {getText('description')}
          {!isReadonly && ownsThisAsset && !isEditingDescription && (
            <ariaComponents.Button
              size="icon"
              variant="icon"
              icon={PenIcon}
              onPress={() => {
                setIsEditingDescription(true)
                setQueuedDescripion(item.item.description)
              }}
            />
          )}
        </aria.Heading>
        <div
          data-testid="asset-panel-description"
          className="self-stretch py-side-panel-description-y"
        >
          {!isEditingDescription ? (
            <aria.Text className="text">{item.item.description}</aria.Text>
          ) : (
            <form className="flex flex-col gap-modal pr-4" onSubmit={doEditDescription}>
              <textarea
                ref={element => {
                  if (element != null && queuedDescription != null) {
                    element.value = queuedDescription
                    setQueuedDescripion(null)
                  }
                }}
                value={description}
                className="w-full resize-none rounded-default border-0.5 border-primary/20 p-2"
                onBlur={doEditDescription}
                onChange={event => {
                  setDescription(event.currentTarget.value)
                }}
                onKeyDown={event => {
                  event.stopPropagation()
                  switch (event.key) {
                    case 'Escape': {
                      setIsEditingDescription(false)
                      break
                    }
                    case 'Enter': {
                      if (event.ctrlKey) {
                        void doEditDescription()
                        break
                      }
                    }
                  }
                }}
              />
              <ariaComponents.ButtonGroup>
                <ariaComponents.Button size="medium" variant="bar" onPress={doEditDescription}>
                  {getText('update')}
                </ariaComponents.Button>
              </ariaComponents.ButtonGroup>
            </form>
          )}
        </div>
      </div>
      {!isCloud && (
        <div className="pointer-events-auto flex flex-col items-start gap-side-panel-section">
          <aria.Heading
            level={2}
            className="h-side-panel-heading py-side-panel-heading-y text-lg leading-snug"
          >
            {getText('metadata')}
          </aria.Heading>
          <table>
            <tbody>
              <tr data-testid="asset-panel-permissions" className="h-row">
                <td className="text my-auto min-w-side-panel-label p-0">
                  <aria.Label className="text inline-block">{getText('path')}</aria.Label>
                </td>
                <td className="w-full p-0">
                  <div className="flex gap-2">
                    <span className="grow">{path}</span>
                    <ariaComponents.CopyButton copyText={path ?? ''} />
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      )}
      {isCloud && (
        <div className="pointer-events-auto flex flex-col items-start gap-side-panel-section">
          <aria.Heading
            level={2}
            className="h-side-panel-heading py-side-panel-heading-y text-lg leading-snug"
          >
            {getText('settings')}
          </aria.Heading>
          <table>
            <tbody>
              <tr data-testid="asset-panel-permissions" className="h-row">
                <td className="text my-auto min-w-side-panel-label p">
                  <aria.Label className="text inline-block">{getText('sharedWith')}</aria.Label>
                </td>
                <td className="w-full p">
                  <SharedWithColumn
                    isReadonly={isReadonly}
                    item={item}
                    setItem={setItem}
                    state={{ category, setQuery: () => {} }}
                  />
                </td>
              </tr>
              <tr data-testid="asset-panel-labels" className="h-row">
                <td className="text my-auto min-w-side-panel-label p">
                  <aria.Label className="text inline-block">{getText('labels')}</aria.Label>
                </td>
                <td className="w-full p">
                  {item.item.labels?.map(value => {
                    const label = labels.find(otherLabel => otherLabel.value === value)
                    return label == null ? null : (
                      <Label key={value} active isDisabled color={label.color} onPress={() => {}}>
                        {value}
                      </Label>
                    )
                  })}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      )}
      {isDatalink && (
        <div className="pointer-events-auto flex flex-col items-start gap-side-panel-section">
          <aria.Heading
            level={2}
            className="h-side-panel-heading py-side-panel-heading-y text-lg leading-snug"
          >
            {getText('datalink')}
          </aria.Heading>
          {!isDatalinkFetched ? (
            <div className="grid place-items-center self-stretch">
              <StatelessSpinner size={48} state={statelessSpinner.SpinnerState.loadingMedium} />
            </div>
          ) : (
            <>
              <DatalinkInput
                readOnly={!canEditThisAsset}
                dropdownTitle="Type"
                value={editedDatalinkValue}
                setValue={setEditedDatalinkValue}
              />
              {canEditThisAsset && (
                <ariaComponents.ButtonGroup>
                  <ariaComponents.Button
                    size="medium"
                    variant="submit"
                    isDisabled={isDatalinkDisabled}
                    {...(isDatalinkDisabled
                      ? { title: 'Edit the Datalink before updating it.' }
                      : {})}
                    onPress={() => {
                      void (async () => {
                        if (item.item.type === backendModule.AssetType.datalink) {
                          const oldDatalinkValue = datalinkValue
                          try {
                            setDatalinkValue(editedDatalinkValue)
                            await createDatalinkMutation.mutateAsync([
                              {
                                datalinkId: item.item.id,
                                name: item.item.title,
                                parentDirectoryId: null,
                                value: editedDatalinkValue,
                              },
                            ])
                          } catch (error) {
                            toastAndLog(null, error)
                            setDatalinkValue(oldDatalinkValue)
                            setEditedDatalinkValue(oldDatalinkValue)
                          }
                        }
                      })()
                    }}
                  >
                    {getText('update')}
                  </ariaComponents.Button>
                  <ariaComponents.Button
                    size="medium"
                    variant="bar"
                    isDisabled={isDatalinkDisabled}
                    onPress={() => {
                      setEditedDatalinkValue(datalinkValue)
                    }}
                  >
                    {getText('cancel')}
                  </ariaComponents.Button>
                </ariaComponents.ButtonGroup>
              )}
            </>
          )}
        </div>
      )}
    </>
  )
}
