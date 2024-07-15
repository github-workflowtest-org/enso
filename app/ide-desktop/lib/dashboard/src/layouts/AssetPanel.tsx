/** @file A panel containing the description and settings for an asset. */
import * as React from 'react'

import * as localStorageProvider from '#/providers/LocalStorageProvider'
import * as textProvider from '#/providers/TextProvider'

import AssetProjectSessions from '#/layouts/AssetProjectSessions'
import AssetProperties from '#/layouts/AssetProperties'
import AssetVersions from '#/layouts/AssetVersions/AssetVersions'
import type Category from '#/layouts/CategorySwitcher/Category'

import * as ariaComponents from '#/components/AriaComponents'

import * as backendModule from '#/services/Backend'
import type Backend from '#/services/Backend'

import * as array from '#/utilities/array'
import type * as assetTreeNode from '#/utilities/AssetTreeNode'
import LocalStorage from '#/utilities/LocalStorage'
import * as tailwindMerge from '#/utilities/tailwindMerge'

// =====================
// === AssetPanelTab ===
// =====================

/** Determines the content of the {@link AssetPanel}. */
enum AssetPanelTab {
  properties = 'properties',
  versions = 'versions',
  projectSessions = 'projectSessions',
}

// ============================
// === Global configuration ===
// ============================

declare module '#/utilities/LocalStorage' {
  /** */
  interface LocalStorageData {
    readonly assetPanelTab: AssetPanelTab
  }
}

const TABS = Object.values(AssetPanelTab)
LocalStorage.registerKey('assetPanelTab', {
  tryParse: value => (array.includes(TABS, value) ? value : null),
})

// ==================
// === AssetPanel ===
// ==================

/** The subset of {@link AssetPanelProps} that are required to be supplied by the row. */
export interface AssetPanelRequiredProps {
  readonly backend: Backend | null
  readonly item: assetTreeNode.AnyAssetTreeNode | null
  readonly setItem: React.Dispatch<React.SetStateAction<assetTreeNode.AnyAssetTreeNode>> | null
}

/** Props for an {@link AssetPanel}. */
export interface AssetPanelProps extends AssetPanelRequiredProps {
  readonly isVisible: boolean
  readonly isReadonly?: boolean
  readonly category: Category
}

/** A panel containing the description and settings for an asset. */
export default function AssetPanel(props: AssetPanelProps) {
  const { isVisible, backend, isReadonly = false, item, setItem, category } = props
  const isCloud = backend?.type === backendModule.BackendType.remote

  const { getText } = textProvider.useText()
  const { localStorage } = localStorageProvider.useLocalStorage()
  const [initialized, setInitialized] = React.useState(false)
  const initializedRef = React.useRef(initialized)
  initializedRef.current = initialized
  const [tabRaw, setTab] = React.useState(
    () => localStorage.get('assetPanelTab') ?? AssetPanelTab.properties
  )
  const tab = (() => {
    if (!isCloud) {
      return AssetPanelTab.properties
    } else if (
      (item?.item.type === backendModule.AssetType.secret ||
        item?.item.type === backendModule.AssetType.directory) &&
      tabRaw === AssetPanelTab.versions
    ) {
      return AssetPanelTab.properties
    } else if (
      item?.item.type !== backendModule.AssetType.project &&
      tabRaw === AssetPanelTab.projectSessions
    ) {
      return AssetPanelTab.properties
    } else {
      return tabRaw
    }
  })()

  React.useEffect(() => {
    // This prevents secrets and directories always setting the tab to `properties`
    // (because they do not support the `versions` tab).
    if (initializedRef.current) {
      localStorage.set('assetPanelTab', tabRaw)
    }
  }, [tabRaw, localStorage])

  React.useEffect(() => {
    setInitialized(true)
  }, [])

  return (
    <div
      data-testid="asset-panel"
      className={tailwindMerge.twMerge(
        'pointer-events-none absolute flex h-full w-asset-panel flex-col gap-asset-panel bg-white p-4 pl-asset-panel-l transition-[box-shadow] clip-path-left-shadow',
        isVisible ? 'shadow-softer' : ''
      )}
      onClick={event => {
        event.stopPropagation()
      }}
    >
      <ariaComponents.ButtonGroup className="mt-0.5 grow-0 basis-8">
        {isCloud &&
          item != null &&
          item.item.type !== backendModule.AssetType.secret &&
          item.item.type !== backendModule.AssetType.directory && (
            <ariaComponents.Button
              size="medium"
              variant="bar"
              className={tailwindMerge.twMerge(
                'pointer-events-auto disabled:opacity-100',
                tab === AssetPanelTab.versions && 'bg-primary/[8%] opacity-100'
              )}
              onPress={() => {
                setTab(oldTab =>
                  oldTab === AssetPanelTab.versions
                    ? AssetPanelTab.properties
                    : AssetPanelTab.versions
                )
              }}
            >
              {getText('versions')}
            </ariaComponents.Button>
          )}
        {isCloud && item != null && item.item.type === backendModule.AssetType.project && (
          <ariaComponents.Button
            size="medium"
            variant="bar"
            isDisabled={tab === AssetPanelTab.projectSessions}
            className={tailwindMerge.twMerge(
              'pointer-events-auto disabled:opacity-100',
              tab === AssetPanelTab.projectSessions && 'bg-primary/[8%] opacity-100'
            )}
            onPress={() => {
              setTab(oldTab =>
                oldTab === AssetPanelTab.projectSessions
                  ? AssetPanelTab.properties
                  : AssetPanelTab.projectSessions
              )
            }}
          >
            {getText('projectSessions')}
          </ariaComponents.Button>
        )}
        {/* Spacing. The top right asset and user bars overlap this area. */}
        <div className="grow" />
      </ariaComponents.ButtonGroup>
      {item == null || setItem == null || backend == null ? (
        <div className="grid grow place-items-center text-lg">
          {getText('selectExactlyOneAssetToViewItsDetails')}
        </div>
      ) : (
        <>
          {tab === AssetPanelTab.properties && (
            <AssetProperties
              backend={backend}
              isReadonly={isReadonly}
              item={item}
              setItem={setItem}
              category={category}
            />
          )}
          {tab === AssetPanelTab.versions && <AssetVersions backend={backend} item={item} />}
          {tab === AssetPanelTab.projectSessions &&
            item.type === backendModule.AssetType.project && (
              <AssetProjectSessions backend={backend} item={item} />
            )}
        </>
      )}
    </div>
  )
}
