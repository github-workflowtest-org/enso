/** @file The icon and name of a {@link backendModule.ProjectAsset}. */
import * as React from 'react'

import NetworkIcon from 'enso-assets/network.svg'

import * as eventHooks from '#/hooks/eventHooks'
import * as setAssetHooks from '#/hooks/setAssetHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as authProvider from '#/providers/AuthProvider'
import * as backendProvider from '#/providers/BackendProvider'
import * as inputBindingsProvider from '#/providers/InputBindingsProvider'
import * as textProvider from '#/providers/TextProvider'

import AssetEventType from '#/events/AssetEventType'
import AssetListEventType from '#/events/AssetListEventType'

import type * as column from '#/components/dashboard/column'
import ProjectIcon from '#/components/dashboard/ProjectIcon'
import EditableSpan from '#/components/EditableSpan'
import SvgMask from '#/components/SvgMask'

import * as backendModule from '#/services/Backend'
import * as localBackend from '#/services/LocalBackend'
import * as projectManager from '#/services/ProjectManager'

import * as eventModule from '#/utilities/event'
import * as indent from '#/utilities/indent'
import * as object from '#/utilities/object'
import * as permissions from '#/utilities/permissions'
import * as string from '#/utilities/string'
import * as validation from '#/utilities/validation'
import Visibility from '#/utilities/Visibility'

// ===================
// === ProjectName ===
// ===================

/** Props for a {@link ProjectNameColumn}. */
export interface ProjectNameColumnProps extends column.AssetColumnProps {}

/** The icon and name of a {@link backendModule.ProjectAsset}.
 * @throws {Error} when the asset is not a {@link backendModule.ProjectAsset}.
 * This should never happen. */
export default function ProjectNameColumn(props: ProjectNameColumnProps) {
  const { item, setItem, selected, rowState, setRowState, state, isEditable } = props
  const { selectedKeys, assetEvents, dispatchAssetEvent, dispatchAssetListEvent } = state
  const { nodeMap, doOpenManually, doOpenEditor, doCloseEditor } = state
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const { backend } = backendProvider.useBackend()
  const { user } = authProvider.useNonPartialUserSession()
  const { getText } = textProvider.useText()
  const inputBindings = inputBindingsProvider.useInputBindings()
  if (item.type !== backendModule.AssetType.project) {
    // eslint-disable-next-line no-restricted-syntax
    throw new Error('`ProjectNameColumn` can only display projects.')
  }
  const asset = item.item
  const setAsset = setAssetHooks.useSetAsset(asset, setItem)
  const ownPermission =
    asset.permissions?.find(permission => permission.user.userId === user?.userId) ?? null
  // This is a workaround for a temporary bad state in the backend causing the `projectState` key
  // to be absent.
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
  const projectState = asset.projectState ?? {
    type: backendModule.ProjectState.closed,
  }
  const isRunning = backendModule.IS_OPENING_OR_OPENED[projectState.type]
  const canExecute =
    isEditable &&
    (backend.type === backendModule.BackendType.local ||
      (ownPermission != null &&
        permissions.PERMISSION_ACTION_CAN_EXECUTE[ownPermission.permission]))
  const isOtherUserUsingProject =
    backend.type !== backendModule.BackendType.local &&
    projectState.openedBy != null &&
    projectState.openedBy !== user?.email

  const setIsEditing = (isEditingName: boolean) => {
    if (isEditable) {
      setRowState(object.merger({ isEditingName }))
    }
  }

  const doRename = async (newTitle: string) => {
    setIsEditing(false)

    if (string.isWhitespaceOnly(newTitle)) {
      // Do nothing.
    } else if (newTitle !== asset.title) {
      const oldTitle = asset.title
      setAsset(object.merger({ title: newTitle }))
      try {
        await backend.updateProject(
          asset.id,
          { ami: null, ideVersion: null, projectName: newTitle, parentId: asset.parentId },
          asset.title
        )
      } catch (error) {
        toastAndLog('renameProjectError', error)
        setAsset(object.merger({ title: oldTitle }))
      }
    }
  }

  eventHooks.useEventHandler(
    assetEvents,
    async event => {
      switch (event.type) {
        case AssetEventType.newFolder:
        case AssetEventType.newDataLink:
        case AssetEventType.newSecret:
        case AssetEventType.openProject:
        case AssetEventType.closeProject:
        case AssetEventType.copy:
        case AssetEventType.cut:
        case AssetEventType.cancelCut:
        case AssetEventType.move:
        case AssetEventType.delete:
        case AssetEventType.deleteForever:
        case AssetEventType.restore:
        case AssetEventType.download:
        case AssetEventType.downloadSelected:
        case AssetEventType.removeSelf:
        case AssetEventType.temporarilyAddLabels:
        case AssetEventType.temporarilyRemoveLabels:
        case AssetEventType.addLabels:
        case AssetEventType.removeLabels:
        case AssetEventType.deleteLabel: {
          // Ignored. Any missing project-related events should be handled by `ProjectIcon`.
          // `delete`, `deleteForever`, `restore`, `download`, and `downloadSelected`
          // are handled by`AssetRow`.
          break
        }
        case AssetEventType.newProject: {
          // This should only run before this project gets replaced with the actual project
          // by this event handler. In both cases `key` will match, so using `key` here
          // is a mistake.
          if (asset.id === event.placeholderId) {
            rowState.setVisibility(Visibility.faded)
            try {
              const createdProject = await backend.createProject({
                parentDirectoryId: asset.parentId,
                projectName: asset.title,
                ...(event.templateId == null ? {} : { projectTemplateName: event.templateId }),
                ...(event.datalinkId == null ? {} : { datalinkId: event.datalinkId }),
              })
              rowState.setVisibility(Visibility.visible)
              setAsset(
                object.merge(asset, {
                  id: createdProject.projectId,
                  projectState: object.merge(projectState, {
                    type: backendModule.ProjectState.placeholder,
                    ...(backend.type === backendModule.BackendType.remote
                      ? {}
                      : { path: createdProject.state.path }),
                  }),
                })
              )
              dispatchAssetEvent({
                type: AssetEventType.openProject,
                id: createdProject.projectId,
                shouldAutomaticallySwitchPage: true,
                runInBackground: false,
              })
            } catch (error) {
              dispatchAssetListEvent({ type: AssetListEventType.delete, key: item.key })
              toastAndLog('createProjectError', error)
            }
          }
          break
        }
        case AssetEventType.updateFiles:
        case AssetEventType.uploadFiles: {
          const file = event.files.get(item.key)
          if (file != null) {
            const fileId = event.type !== AssetEventType.updateFiles ? null : asset.id
            rowState.setVisibility(Visibility.faded)
            const { extension } = backendModule.extractProjectExtension(file.name)
            const title = backendModule.stripProjectExtension(asset.title)
            setAsset(object.merge(asset, { title }))
            try {
              if (backend.type === backendModule.BackendType.local) {
                const directory = localBackend.extractTypeAndId(item.directoryId).id
                let id: string
                if (
                  'backendApi' in window &&
                  // This non-standard property is defined in Electron.
                  'path' in file &&
                  typeof file.path === 'string'
                ) {
                  id = await window.backendApi.importProjectFromPath(file.path, directory, title)
                } else {
                  const searchParams = new URLSearchParams({ directory, name: title }).toString()
                  // Ideally this would use `file.stream()`, to minimize RAM
                  // requirements. for uploading large projects. Unfortunately,
                  // this requires HTTP/2, which is HTTPS-only, so it will not
                  // work on `http://localhost`.
                  const body =
                    window.location.protocol === 'https:' ? file.stream() : await file.arrayBuffer()
                  const path = `./api/upload-project?${searchParams}`
                  const response = await fetch(path, { method: 'POST', body })
                  id = await response.text()
                }
                const projectId = localBackend.newProjectId(projectManager.UUID(id))
                const listedProject = await backend.getProjectDetails(
                  projectId,
                  asset.parentId,
                  file.name
                )
                rowState.setVisibility(Visibility.visible)
                setAsset(object.merge(asset, { title: listedProject.packageName, id: projectId }))
              } else {
                const createdFile = await backend.uploadFile(
                  { fileId, fileName: `${title}.${extension}`, parentDirectoryId: asset.parentId },
                  file
                )
                const project = createdFile.project
                if (project == null) {
                  throw new Error('The uploaded file was not a project.')
                } else {
                  rowState.setVisibility(Visibility.visible)
                  setAsset(
                    object.merge(asset, {
                      title,
                      id: project.projectId,
                      projectState: project.state,
                    })
                  )
                  return
                }
              }
            } catch (error) {
              switch (event.type) {
                case AssetEventType.uploadFiles: {
                  dispatchAssetListEvent({ type: AssetListEventType.delete, key: item.key })
                  toastAndLog('uploadProjectError', error)
                  break
                }
                case AssetEventType.updateFiles: {
                  toastAndLog('updateProjectError', error)
                  break
                }
              }
            }
          }
          break
        }
      }
    },
    { isDisabled: !isEditable }
  )

  const handleClick = inputBindings.handler({
    open: () => {
      dispatchAssetEvent({
        type: AssetEventType.openProject,
        id: asset.id,
        shouldAutomaticallySwitchPage: true,
        runInBackground: false,
      })
    },
    run: () => {
      dispatchAssetEvent({
        type: AssetEventType.openProject,
        id: asset.id,
        shouldAutomaticallySwitchPage: false,
        runInBackground: true,
      })
    },
    editName: () => {
      setIsEditing(true)
    },
  })

  return (
    <div
      className={`flex h-full min-w-max items-center gap-name-column-icon whitespace-nowrap rounded-l-full px-name-column-x py-name-column-y ${indent.indentClass(
        item.depth
      )}`}
      onKeyDown={event => {
        if (rowState.isEditingName && event.key === 'Enter') {
          event.stopPropagation()
        }
      }}
      onClick={event => {
        if (rowState.isEditingName || isOtherUserUsingProject) {
          // The project should neither be edited nor opened in these cases.
        } else if (handleClick(event)) {
          // Already handled.
        } else if (
          !isRunning &&
          eventModule.isSingleClick(event) &&
          selected &&
          selectedKeys.current.size === 1
        ) {
          setIsEditing(true)
        }
      }}
    >
      {!canExecute ? (
        <SvgMask src={NetworkIcon} className="m-name-column-icon size-icon" />
      ) : (
        <ProjectIcon
          keyProp={item.key}
          // This is a workaround for a temporary bad state in the backend causing the
          // `projectState` key to be absent.
          item={object.merge(asset, { projectState })}
          setItem={setAsset}
          assetEvents={assetEvents}
          doOpenManually={doOpenManually}
          doOpenEditor={switchPage => {
            doOpenEditor(asset, setAsset, switchPage)
          }}
          doCloseEditor={() => {
            doCloseEditor(asset)
          }}
        />
      )}
      <EditableSpan
        data-testid="asset-row-name"
        editable={rowState.isEditingName}
        className={`text grow bg-transparent ${
          rowState.isEditingName
            ? 'cursor-text'
            : canExecute && !isOtherUserUsingProject
              ? 'cursor-pointer'
              : ''
        }`}
        checkSubmittable={newTitle =>
          newTitle !== item.item.title &&
          (nodeMap.current.get(item.directoryKey)?.children ?? []).every(
            child =>
              // All siblings,
              child.key === item.key ||
              // that are not directories,
              backendModule.assetIsDirectory(child.item) ||
              // must have a different name.
              child.item.title !== newTitle
          )
        }
        onSubmit={doRename}
        onCancel={() => {
          setIsEditing(false)
        }}
        {...(backend.type === backendModule.BackendType.local
          ? {
              inputPattern: validation.LOCAL_PROJECT_NAME_PATTERN,
              inputTitle: getText('projectNameCannotBeEmpty'),
            }
          : {})}
      >
        {asset.title}
      </EditableSpan>
    </div>
  )
}
