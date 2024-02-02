/** @file A panel containing the description and settings for an asset. */
import * as React from 'react'

import PenIcon from 'enso-assets/pen.svg'

import type * as assetEvent from '#/events/assetEvent'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'
import type Category from '#/layouts/dashboard/CategorySwitcher/Category'
import type * as pageSwitcher from '#/layouts/dashboard/PageSwitcher'
import UserBar from '#/layouts/dashboard/UserBar'
import * as authProvider from '#/providers/AuthProvider'
import * as backendModule from '#/services/backend'
import * as assetTreeNode from '#/utilities/assetTreeNode'
import * as object from '#/utilities/object'
import * as permissions from '#/utilities/permissions'

import Button from '#/components/Button'
import AssetInfoBar from '#/components/dashboard/AssetInfoBar'
import SharedWithColumn from '#/components/dashboard/column/SharedWithColumn'

// ==========================
// === AssetSettingsPanel ===
// ==========================

/** The subset of {@link AssetSettingsPanelProps} that are required to be supplied by the row. */
export interface AssetSettingsPanelRequiredProps {
  item: assetTreeNode.AssetTreeNode
  setItem: React.Dispatch<React.SetStateAction<assetTreeNode.AssetTreeNode>>
}

/** Props for a {@link AssetSettingsPanel}. */
export interface AssetSettingsPanelProps extends AssetSettingsPanelRequiredProps {
  supportsLocalBackend: boolean
  backend: backendModule.Backend
  page: pageSwitcher.Page
  setPage: (page: pageSwitcher.Page) => void
  category: Category
  isHelpChatOpen: boolean
  setIsHelpChatOpen: React.Dispatch<React.SetStateAction<boolean>>
  setIsSettingsPanelVisible: React.Dispatch<React.SetStateAction<boolean>>
  dispatchAssetEvent: (event: assetEvent.AssetEvent) => void
  projectAsset: backendModule.SmartProject | null
  setProjectAsset: React.Dispatch<React.SetStateAction<backendModule.ProjectAsset>> | null
  doRemoveSelf: () => void
  onSignOut: () => void
}

/** A panel containing the description and settings for an asset. */
export default function AssetSettingsPanel(props: AssetSettingsPanelProps) {
  const { item: rawItem, setItem: rawSetItem, backend, supportsLocalBackend, page, setPage } = props
  const { category, isHelpChatOpen, setIsHelpChatOpen, setIsSettingsPanelVisible } = props
  const { dispatchAssetEvent, projectAsset, setProjectAsset, doRemoveSelf, onSignOut } = props
  const [item, innerSetItem] = React.useState(rawItem)
  const [isEditingDescription, setIsEditingDescription] = React.useState(false)
  const [queuedDescription, setQueuedDescripion] = React.useState<string | null>(null)
  const [description, setDescription] = React.useState('')
  const { organization } = authProvider.useNonPartialUserSession()
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const setItem = React.useCallback(
    (valueOrUpdater: React.SetStateAction<assetTreeNode.AssetTreeNode>) => {
      innerSetItem(valueOrUpdater)
      rawSetItem(valueOrUpdater)
    },
    [/* should never change */ rawSetItem]
  )
  const smartAsset = item.item
  const asset = smartAsset.value
  const setAsset = assetTreeNode.useSetAsset(asset, setItem)
  const self = asset.permissions?.find(
    permission => permission.user.user_email === organization?.value.email
  )
  const ownsThisAsset = self?.permission === permissions.PermissionAction.own
  const isCloud = backend.type === backendModule.BackendType.remote
  React.useEffect(() => {
    setDescription(asset.description ?? '')
  }, [asset.description])

  const doEditDescription = async () => {
    setIsEditingDescription(false)
    if (description !== asset.description) {
      const oldDescription = asset.description
      setAsset(object.merger({ description }))
      try {
        await smartAsset.update({ description })
      } catch (error) {
        toastAndLog('Could not edit asset description')
        setAsset(object.merger({ description: oldDescription }))
      }
    }
  }

  return (
    <div
      className="absolute flex flex-col h-full border-black/[0.12] border-l-2 gap-8 w-120 pl-3 pr-4 py-2.25"
      onClick={event => {
        event.stopPropagation()
      }}
    >
      <div className="flex">
        {/* Spacing. */}
        <div className="grow" />
        <div className="flex gap-2">
          <AssetInfoBar
            isCloud={isCloud}
            canToggleSettingsPanel={true}
            isSettingsPanelVisible={true}
            setIsSettingsPanelVisible={setIsSettingsPanelVisible}
          />
          <UserBar
            backend={backend}
            supportsLocalBackend={supportsLocalBackend}
            isHelpChatOpen={isHelpChatOpen}
            setIsHelpChatOpen={setIsHelpChatOpen}
            onSignOut={onSignOut}
            page={page}
            setPage={setPage}
            projectAsset={projectAsset}
            setProjectAsset={setProjectAsset}
            doRemoveSelf={doRemoveSelf}
          />
        </div>
      </div>
      <div className="flex flex-col items-start gap-1">
        <span className="flex items-center gap-2 text-lg leading-144.5 h-7 py-px">
          Description
          {ownsThisAsset && !isEditingDescription && (
            <Button
              image={PenIcon}
              onClick={() => {
                setIsEditingDescription(true)
                setQueuedDescripion(asset.description)
              }}
            />
          )}
        </span>
        <div className="py-1 self-stretch">
          {!isEditingDescription ? (
            <span className="leading-170 py-px">{asset.description}</span>
          ) : (
            <form className="flex flex-col gap-2" onSubmit={doEditDescription}>
              <textarea
                ref={element => {
                  if (element != null && queuedDescription != null) {
                    element.value = queuedDescription
                    setQueuedDescripion(null)
                  }
                }}
                onBlur={doEditDescription}
                value={description}
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
                onChange={event => {
                  setDescription(event.currentTarget.value)
                }}
                className="bg-frame resize-none rounded-lg w-full p-2"
              ></textarea>
              <button type="submit" className="self-start bg-frame-selected rounded-full px-4 py-1">
                Update
              </button>
            </form>
          )}
        </div>
      </div>
      <div className="flex flex-col items-start gap-2">
        <span className="text-lg leading-144.5 h-7 py-px">Settings</span>
        <table>
          <tbody>
            <tr>
              <td className="min-w-32 px-0 py-1">
                <span className="inline-block leading-170 h-6 py-px">Shared with</span>
              </td>
              <td className="p-0 w-full">
                <SharedWithColumn
                  item={item}
                  setItem={setItem}
                  state={{ backend, category, dispatchAssetEvent }}
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  )
}
