/** @file The container that launches the IDE. */
import * as React from 'react'

import * as reactQuery from '@tanstack/react-query'

import type * as types from 'enso-common/src/types'

import * as appUtils from '#/appUtils'

import * as gtagHooks from '#/hooks/gtagHooks'

import * as backendProvider from '#/providers/BackendProvider'
import * as textProvider from '#/providers/TextProvider'

import * as dashboard from '#/pages/dashboard/Dashboard'

import * as errorBoundary from '#/components/ErrorBoundary'
import * as suspense from '#/components/Suspense'

import * as backendModule from '#/services/Backend'

import * as twMerge from '#/utilities/tailwindMerge'

// =================
// === Constants ===
// =================

const IGNORE_PARAMS_REGEX = new RegExp(`^${appUtils.SEARCH_PARAMS_PREFIX}(.+)$`)

// ==============
// === Editor ===
// ==============

/** Props for an {@link Editor}. */
export interface EditorProps {
  readonly isOpening: boolean
  readonly isOpeningFailed: boolean
  readonly openingError: Error | null
  readonly startProject: (project: dashboard.Project) => void
  readonly project: dashboard.Project
  readonly hidden: boolean
  readonly ydocUrl: string | null
  readonly appRunner: types.EditorRunner | null
  readonly renameProject: (newName: string) => void
  readonly projectId: backendModule.ProjectAsset['id']
}

/** The container that launches the IDE. */
export default function Editor(props: EditorProps) {
  const { project, hidden, isOpening, startProject, isOpeningFailed, openingError } = props

  const remoteBackend = backendProvider.useRemoteBackendStrict()
  const localBackend = backendProvider.useLocalBackend()

  const projectStatusQuery = dashboard.createGetProjectDetailsQuery({
    type: project.type,
    assetId: project.id,
    parentId: project.parentId,
    title: project.title,
    remoteBackend,
    localBackend,
  })

  const projectQuery = reactQuery.useQuery({
    ...projectStatusQuery,
    networkMode: project.type === backendModule.BackendType.remote ? 'online' : 'always',
  })

  if (isOpeningFailed) {
    // eslint-disable-next-line no-restricted-syntax
    return (
      <errorBoundary.ErrorDisplay
        error={openingError}
        resetErrorBoundary={() => {
          startProject(project)
        }}
      />
    )
  }

  const isProjectClosed = projectQuery.data?.state.type === backendModule.ProjectState.closed
  const shouldRefetch = !(projectQuery.isError || projectQuery.isLoading)

  if (!isOpening && isProjectClosed && shouldRefetch) {
    startProject(project)
  }

  return (
    <div
      className={twMerge.twJoin('contents', hidden && 'hidden')}
      data-testid="gui-editor-root"
      data-testvalue={project.id}
    >
      {(() => {
        if (projectQuery.isError) {
          return (
            <errorBoundary.ErrorDisplay
              error={projectQuery.error}
              resetErrorBoundary={() => projectQuery.refetch()}
            />
          )
        } else if (
          projectQuery.isLoading ||
          projectQuery.data?.state.type !== backendModule.ProjectState.opened
        ) {
          return <suspense.Loader loaderProps={{ minHeight: 'full' }} />
        } else {
          return (
            <errorBoundary.ErrorBoundary>
              <suspense.Suspense>
                <EditorInternal
                  {...props}
                  openedProject={projectQuery.data}
                  backendType={project.type}
                />
              </suspense.Suspense>
            </errorBoundary.ErrorBoundary>
          )
        }
      })()}
    </div>
  )
}

// ======================
// === EditorInternal ===
// ======================

/** Props for an {@link EditorInternal}. */
interface EditorInternalProps extends Omit<EditorProps, 'project'> {
  readonly openedProject: backendModule.Project
  readonly backendType: backendModule.BackendType
}

/** An internal editor. */
function EditorInternal(props: EditorInternalProps) {
  const { hidden, ydocUrl, appRunner: AppRunner, renameProject, openedProject, backendType } = props

  const { getText } = textProvider.useText()
  const gtagEvent = gtagHooks.useGtagEvent()

  const localBackend = backendProvider.useLocalBackend()
  const remoteBackend = backendProvider.useRemoteBackend()

  const logEvent = React.useCallback(
    (message: string, projectId?: string | null, metadata?: object | null) => {
      if (remoteBackend) {
        void remoteBackend.logEvent(message, projectId, metadata)
      }
    },
    [remoteBackend]
  )

  React.useEffect(() => {
    if (hidden) {
      return
    } else {
      return gtagHooks.gtagOpenCloseCallback(gtagEvent, 'open_workflow', 'close_workflow')
    }
  }, [hidden, gtagEvent])

  const appProps: types.EditorProps | null = React.useMemo(() => {
    const jsonAddress = openedProject.jsonAddress
    const binaryAddress = openedProject.binaryAddress
    const ydocAddress = ydocUrl ?? ''
    const backend = backendType === backendModule.BackendType.remote ? remoteBackend : localBackend

    if (jsonAddress == null) {
      throw new Error(getText('noJSONEndpointError'))
    } else if (binaryAddress == null) {
      throw new Error(getText('noBinaryEndpointError'))
    } else {
      return {
        config: {
          engine: { rpcUrl: jsonAddress, dataUrl: binaryAddress, ydocUrl: ydocAddress },
          startup: { project: openedProject.packageName, displayedProjectName: openedProject.name },
          window: { topBarOffset: '0' },
        },
        projectId: openedProject.projectId,
        hidden,
        ignoreParamsRegex: IGNORE_PARAMS_REGEX,
        logEvent,
        renameProject,
        backend,
      }
    }
  }, [
    openedProject,
    ydocUrl,
    getText,
    hidden,
    logEvent,
    renameProject,
    backendType,
    localBackend,
    remoteBackend,
  ])

  // Currently the GUI component needs to be fully rerendered whenever the project is changed. Once
  // this is no longer necessary, the `key` could be removed.
  return AppRunner == null ? null : <AppRunner key={appProps.projectId} {...appProps} />
}
