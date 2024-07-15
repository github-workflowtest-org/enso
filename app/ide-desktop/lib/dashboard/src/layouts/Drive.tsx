/** @file The directory header bar and directory item listing. */
import * as React from 'react'

import * as appUtils from '#/appUtils'

import * as offlineHooks from '#/hooks/offlineHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as authProvider from '#/providers/AuthProvider'
import * as backendProvider from '#/providers/BackendProvider'
import * as localStorageProvider from '#/providers/LocalStorageProvider'
import * as textProvider from '#/providers/TextProvider'

import AssetListEventType from '#/events/AssetListEventType'

import type * as dashboard from '#/pages/dashboard/Dashboard'

import type * as assetPanel from '#/layouts/AssetPanel'
import AssetPanel from '#/layouts/AssetPanel'
import type * as assetSearchBar from '#/layouts/AssetSearchBar'
import type * as assetsTable from '#/layouts/AssetsTable'
import AssetsTable from '#/layouts/AssetsTable'
import * as eventListProvider from '#/layouts/AssetsTable/EventListProvider'
import CategorySwitcher from '#/layouts/CategorySwitcher'
import Category, * as categoryModule from '#/layouts/CategorySwitcher/Category'
import DriveBar from '#/layouts/DriveBar'
import Labels from '#/layouts/Labels'

import * as ariaComponents from '#/components/AriaComponents'
import * as result from '#/components/Result'

import * as backendModule from '#/services/Backend'
import * as projectManager from '#/services/ProjectManager'

import AssetQuery from '#/utilities/AssetQuery'
import type AssetTreeNode from '#/utilities/AssetTreeNode'
import * as download from '#/utilities/download'
import * as github from '#/utilities/github'
import * as tailwindMerge from '#/utilities/tailwindMerge'

// ===================
// === DriveStatus ===
// ===================

/** The predicted status of project listing. This is used to avoid sending requests to the backend
 * if it is already known that the request will fail. */
enum DriveStatus {
  /** No errors predicted. The request may still error because of an issue in the backend. */
  ok = 'ok',
  /** Trying to use the remote backend when offline. The network request will fail. */
  offline = 'offline',
  /** The user does not have an active plan, and therefore has no access to the remote backend. */
  notEnabled = 'not-enabled',
  /** The connection to the Project Manager timed out. This may happen if the Project Manager
   * crashed, or was never run in the first place. */
  noProjectManager = 'no-project-manager',
}

// =============
// === Drive ===
// =============

/** Props for a {@link Drive}. */
export interface DriveProps {
  readonly openedProjects: dashboard.Project[]
  readonly category: Category
  readonly setCategory: (category: Category) => void
  readonly hidden: boolean
  readonly initialProjectName: string | null
  readonly doOpenEditor: (id: dashboard.ProjectId) => void
  readonly doOpenProject: (project: dashboard.Project) => void
  readonly doCloseProject: (project: dashboard.Project) => void
  readonly assetsManagementApiRef: React.Ref<assetsTable.AssetManagementApi>
}

/** Contains directory path and directory contents (projects, folders, secrets and files). */
export default function Drive(props: DriveProps) {
  const {
    openedProjects,
    doOpenEditor,
    doCloseProject,
    category,
    setCategory,
    hidden,
    initialProjectName,
    doOpenProject,
    assetsManagementApiRef,
  } = props

  const { isOffline } = offlineHooks.useOffline()
  const { localStorage } = localStorageProvider.useLocalStorage()
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const { user } = authProvider.useNonPartialUserSession()
  const localBackend = backendProvider.useLocalBackend()
  const backend = backendProvider.useBackend(category)
  const { getText } = textProvider.useText()
  const dispatchAssetListEvent = eventListProvider.useDispatchAssetListEvent()
  const [query, setQuery] = React.useState(() => AssetQuery.fromString(''))
  const [suggestions, setSuggestions] = React.useState<readonly assetSearchBar.Suggestion[]>([])
  const [canDownload, setCanDownload] = React.useState(false)
  const [didLoadingProjectManagerFail, setDidLoadingProjectManagerFail] = React.useState(false)
  const [assetPanelProps, setAssetPanelProps] =
    React.useState<assetPanel.AssetPanelRequiredProps | null>(null)
  const [isAssetPanelEnabled, setIsAssetPanelEnabled] = React.useState(
    () => localStorage.get('isAssetPanelVisible') ?? false
  )
  const [isAssetPanelTemporarilyVisible, setIsAssetPanelTemporarilyVisible] = React.useState(false)
  const rootDirectoryId = React.useMemo(
    () => backend.rootDirectoryId(user) ?? backendModule.DirectoryId(''),
    [backend, user]
  )
  const targetDirectoryNodeRef = React.useRef<AssetTreeNode<backendModule.DirectoryAsset> | null>(
    null
  )
  const isCloud = categoryModule.isCloud(category)
  const supportLocalBackend = localBackend != null

  const status =
    !isCloud && didLoadingProjectManagerFail
      ? DriveStatus.noProjectManager
      : isCloud && isOffline
        ? DriveStatus.offline
        : isCloud && !user.isEnabled
          ? DriveStatus.notEnabled
          : DriveStatus.ok

  const isAssetPanelVisible = isAssetPanelEnabled || isAssetPanelTemporarilyVisible

  React.useEffect(() => {
    localStorage.set('isAssetPanelVisible', isAssetPanelEnabled)
  }, [isAssetPanelEnabled, /* should never change */ localStorage])

  React.useEffect(() => {
    const onProjectManagerLoadingFailed = () => {
      setDidLoadingProjectManagerFail(true)
    }
    document.addEventListener(
      projectManager.ProjectManagerEvents.loadingFailed,
      onProjectManagerLoadingFailed
    )
    return () => {
      document.removeEventListener(
        projectManager.ProjectManagerEvents.loadingFailed,
        onProjectManagerLoadingFailed
      )
    }
  }, [])

  const doUploadFiles = React.useCallback(
    (files: File[]) => {
      if (isCloud && isOffline) {
        // This should never happen, however display a nice error message in case it does.
        toastAndLog('offlineUploadFilesError')
      } else {
        dispatchAssetListEvent({
          type: AssetListEventType.uploadFiles,
          parentKey: targetDirectoryNodeRef.current?.key ?? rootDirectoryId,
          parentId: targetDirectoryNodeRef.current?.item.id ?? rootDirectoryId,
          files,
        })
      }
    },
    [isCloud, rootDirectoryId, toastAndLog, isOffline, dispatchAssetListEvent]
  )

  const doEmptyTrash = React.useCallback(() => {
    dispatchAssetListEvent({ type: AssetListEventType.emptyTrash })
  }, [dispatchAssetListEvent])

  const doCreateProject = React.useCallback(
    (templateId: string | null = null, templateName: string | null = null) => {
      dispatchAssetListEvent({
        type: AssetListEventType.newProject,
        parentKey: targetDirectoryNodeRef.current?.key ?? rootDirectoryId,
        parentId: targetDirectoryNodeRef.current?.item.id ?? rootDirectoryId,
        templateId,
        datalinkId: null,
        preferredName: templateName,
      })
    },
    [rootDirectoryId, dispatchAssetListEvent]
  )

  const doCreateDirectory = React.useCallback(() => {
    dispatchAssetListEvent({
      type: AssetListEventType.newFolder,
      parentKey: targetDirectoryNodeRef.current?.key ?? rootDirectoryId,
      parentId: targetDirectoryNodeRef.current?.item.id ?? rootDirectoryId,
    })
  }, [rootDirectoryId, dispatchAssetListEvent])

  const doCreateSecret = React.useCallback(
    (name: string, value: string) => {
      dispatchAssetListEvent({
        type: AssetListEventType.newSecret,
        parentKey: targetDirectoryNodeRef.current?.key ?? rootDirectoryId,
        parentId: targetDirectoryNodeRef.current?.item.id ?? rootDirectoryId,
        name,
        value,
      })
    },
    [rootDirectoryId, dispatchAssetListEvent]
  )

  const doCreateDatalink = React.useCallback(
    (name: string, value: unknown) => {
      dispatchAssetListEvent({
        type: AssetListEventType.newDatalink,
        parentKey: targetDirectoryNodeRef.current?.key ?? rootDirectoryId,
        parentId: targetDirectoryNodeRef.current?.item.id ?? rootDirectoryId,
        name,
        value,
      })
    },
    [rootDirectoryId, dispatchAssetListEvent]
  )

  switch (status) {
    case DriveStatus.noProjectManager: {
      return (
        <div className={tailwindMerge.twMerge('grid grow place-items-center', hidden && 'hidden')}>
          <div className="flex flex-col gap-status-page text-center text-base">
            {getText('couldNotConnectToPM')}
          </div>
        </div>
      )
    }
    case DriveStatus.notEnabled: {
      return (
        <result.Result
          status="error"
          title={getText('notEnabledTitle')}
          testId="not-enabled-stub"
          subtitle={`${getText('notEnabledSubtitle')}${localBackend == null ? ' ' + getText('downloadFreeEditionMessage') : ''}`}
        >
          <ariaComponents.ButtonGroup align="center">
            <ariaComponents.Button variant="tertiary" size="medium" href={appUtils.SUBSCRIBE_PATH}>
              {getText('upgrade')}
            </ariaComponents.Button>

            {!supportLocalBackend && (
              <ariaComponents.Button
                data-testid="download-free-edition"
                size="medium"
                onPress={async () => {
                  const downloadUrl = await github.getDownloadUrl()
                  if (downloadUrl == null) {
                    toastAndLog('noAppDownloadError')
                  } else {
                    download.download(downloadUrl)
                  }
                }}
              >
                {getText('downloadFreeEdition')}
              </ariaComponents.Button>
            )}
          </ariaComponents.ButtonGroup>
        </result.Result>
      )
    }
    case DriveStatus.offline:
    case DriveStatus.ok: {
      return (
        <div className={tailwindMerge.twMerge('relative flex grow', hidden && 'hidden')}>
          <div
            data-testid="drive-view"
            className="mt-4 flex flex-1 flex-col gap-4 overflow-visible px-page-x"
          >
            <DriveBar
              backend={backend}
              query={query}
              setQuery={setQuery}
              suggestions={suggestions}
              category={category}
              canDownload={canDownload}
              isAssetPanelOpen={isAssetPanelVisible}
              setIsAssetPanelOpen={valueOrUpdater => {
                const newValue =
                  typeof valueOrUpdater === 'function'
                    ? valueOrUpdater(isAssetPanelVisible)
                    : valueOrUpdater
                setIsAssetPanelTemporarilyVisible(false)
                setIsAssetPanelEnabled(newValue)
              }}
              doEmptyTrash={doEmptyTrash}
              doCreateProject={doCreateProject}
              doUploadFiles={doUploadFiles}
              doCreateDirectory={doCreateDirectory}
              doCreateSecret={doCreateSecret}
              doCreateDatalink={doCreateDatalink}
            />

            <div className="flex flex-1 gap-drive overflow-hidden">
              <div className="flex w-drive-sidebar flex-col gap-drive-sidebar py-drive-sidebar-y">
                <CategorySwitcher category={category} setCategory={setCategory} />
                {isCloud && (
                  <Labels
                    backend={backend}
                    draggable={category !== Category.trash}
                    query={query}
                    setQuery={setQuery}
                  />
                )}
              </div>
              {status === DriveStatus.offline ? (
                <result.Result
                  status="info"
                  className="my-12"
                  centered="horizontal"
                  title={getText('cloudUnavailableOffline')}
                  subtitle={`${getText('cloudUnavailableOfflineDescription')} ${supportLocalBackend ? getText('cloudUnavailableOfflineDescriptionOfferLocal') : ''}`}
                >
                  {supportLocalBackend && (
                    <ariaComponents.Button
                      variant="primary"
                      size="small"
                      className="mx-auto"
                      onPress={() => {
                        setCategory(Category.local)
                      }}
                    >
                      {getText('switchToLocal')}
                    </ariaComponents.Button>
                  )}
                </result.Result>
              ) : (
                <AssetsTable
                  assetManagementApiRef={assetsManagementApiRef}
                  openedProjects={openedProjects}
                  hidden={hidden}
                  query={query}
                  setQuery={setQuery}
                  setCanDownload={setCanDownload}
                  category={category}
                  setSuggestions={setSuggestions}
                  initialProjectName={initialProjectName}
                  setAssetPanelProps={setAssetPanelProps}
                  setIsAssetPanelTemporarilyVisible={setIsAssetPanelTemporarilyVisible}
                  targetDirectoryNodeRef={targetDirectoryNodeRef}
                  doOpenEditor={doOpenEditor}
                  doOpenProject={doOpenProject}
                  doCloseProject={doCloseProject}
                />
              )}
            </div>
          </div>
          <div
            className={tailwindMerge.twMerge(
              'flex flex-col overflow-hidden transition-min-width duration-side-panel ease-in-out',
              isAssetPanelVisible ? 'min-w-side-panel' : 'min-w'
            )}
          >
            <AssetPanel
              isVisible={isAssetPanelVisible}
              key={assetPanelProps?.item?.item.id}
              backend={assetPanelProps?.backend ?? null}
              item={assetPanelProps?.item ?? null}
              setItem={assetPanelProps?.setItem ?? null}
              category={category}
              isReadonly={category === Category.trash}
            />
          </div>
        </div>
      )
    }
  }
}
