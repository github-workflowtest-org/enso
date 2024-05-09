/** @file A modal opened when uploaded assets. */
import * as React from 'react'

import * as modalProvider from '#/providers/ModalProvider'
import * as textProvider from '#/providers/TextProvider'

import type * as assetEvent from '#/events/assetEvent'
import AssetEventType from '#/events/AssetEventType'
import type * as assetListEvent from '#/events/assetListEvent'
import AssetListEventType from '#/events/AssetListEventType'

import * as aria from '#/components/aria'
import AssetSummary from '#/components/dashboard/AssetSummary'
import Modal from '#/components/Modal'
import ButtonRow from '#/components/styled/ButtonRow'
import UnstyledButton from '#/components/UnstyledButton'

import * as backendModule from '#/services/Backend'

import * as fileInfo from '#/utilities/fileInfo'
import * as object from '#/utilities/object'

// =============
// === Types ===
// =============

/** An object containing the current asset, and the asset that is about to be uploaded,
 * that will conflict with the existing asset. */
export interface ConflictingAsset<
  Asset extends backendModule.FileAsset | backendModule.ProjectAsset =
    | backendModule.FileAsset
    | backendModule.ProjectAsset,
> {
  readonly current: backendModule.AnyAsset
  readonly new: Asset
  readonly file: File
}

// =================================
// === UpdateOrRenameAssetsModal ===
// =================================

/** Props for a {@link DuplicateAssetsModal}. */
export interface DuplicateAssetsModalProps {
  readonly parentKey: backendModule.DirectoryId
  readonly parentId: backendModule.DirectoryId
  readonly conflictingFiles: readonly ConflictingAsset<backendModule.FileAsset>[]
  readonly conflictingProjects: readonly ConflictingAsset<backendModule.ProjectAsset>[]
  readonly dispatchAssetEvent: (assetEvent: assetEvent.AssetEvent) => void
  readonly dispatchAssetListEvent: (assetListEvent: assetListEvent.AssetListEvent) => void
  readonly siblingFileNames: Iterable<string>
  readonly siblingProjectNames: Iterable<string>
  readonly nonConflictingFileCount: number
  readonly nonConflictingProjectCount: number
  readonly doUploadNonConflicting: () => void
}

/** A modal for creating a new label. */
export default function DuplicateAssetsModal(props: DuplicateAssetsModalProps) {
  const { parentKey, parentId, conflictingFiles: conflictingFilesRaw } = props
  const { conflictingProjects: conflictingProjectsRaw } = props
  const { dispatchAssetEvent, dispatchAssetListEvent } = props
  const { siblingFileNames: siblingFileNamesRaw } = props
  const { siblingProjectNames: siblingProjectNamesRaw } = props
  const { nonConflictingFileCount, nonConflictingProjectCount, doUploadNonConflicting } = props
  const { unsetModal } = modalProvider.useSetModal()
  const { getText } = textProvider.useText()
  const [conflictingFiles, setConflictingFiles] = React.useState(conflictingFilesRaw)
  const [conflictingProjects, setConflictingProjects] = React.useState(conflictingProjectsRaw)
  const [didUploadNonConflicting, setDidUploadNonConflicting] = React.useState(false)
  const siblingFileNames = React.useRef(new Set<string>())
  const siblingProjectNames = React.useRef(new Set<string>())
  const count = conflictingFiles.length + conflictingProjects.length
  const firstConflict = conflictingFiles[0] ?? conflictingProjects[0]
  const otherFilesCount = Math.max(0, conflictingFiles.length - 1)
  const otherProjectsCount = conflictingProjects.length - (conflictingFiles.length > 0 ? 0 : 1)

  React.useEffect(() => {
    for (const name of siblingFileNamesRaw) {
      siblingFileNames.current.add(name)
    }
    for (const name of siblingProjectNamesRaw) {
      siblingProjectNames.current.add(name)
    }
    // Note that because the props are `Iterable`s, they may be different each time
    // even if their contents are identical. However, as this component should never
    // be re-rendered with different props, the dependency list should not matter anyway.
  }, [siblingFileNamesRaw, siblingProjectNamesRaw])

  const findNewName = (conflict: ConflictingAsset, commit = true) => {
    let title = conflict.file.name
    switch (conflict.new.type) {
      case backendModule.AssetType.file: {
        const { basename, extension } = fileInfo.basenameAndExtension(title)
        let i = 1
        while (true) {
          i += 1
          const candidateTitle = `${basename} ${i}.${extension}`
          if (!siblingFileNames.current.has(candidateTitle)) {
            if (commit) {
              siblingFileNames.current.add(candidateTitle)
            }
            title = candidateTitle
            break
          }
        }
        break
      }
      case backendModule.AssetType.project: {
        const { basename, extension } = backendModule.extractProjectExtension(title)
        title = basename
        let i = 1
        while (true) {
          i += 1
          const candidateTitle = `${title} ${i}`
          if (!siblingProjectNames.current.has(candidateTitle)) {
            if (commit) {
              siblingProjectNames.current.add(candidateTitle)
            }
            title = `${candidateTitle}.${extension}`
            break
          }
        }
        break
      }
    }
    return title
  }

  const doUpdate = (toUpdate: ConflictingAsset[]) => {
    dispatchAssetEvent({
      type: AssetEventType.updateFiles,
      files: new Map(toUpdate.map(asset => [asset.current.id, asset.file])),
    })
  }

  const doRename = (toRename: ConflictingAsset[]) => {
    const clonedConflicts = structuredClone(toRename)
    for (const conflict of clonedConflicts) {
      // This is SAFE, as it is a shallow mutation of a freshly cloned object.
      object.unsafeMutable(conflict.new).title = findNewName(conflict)
    }
    dispatchAssetListEvent({
      type: AssetListEventType.insertAssets,
      parentKey,
      parentId,
      assets: clonedConflicts.map(conflict => conflict.new),
    })
    dispatchAssetEvent({
      type: AssetEventType.uploadFiles,
      files: new Map(clonedConflicts.map(conflict => [conflict.new.id, conflict.file])),
    })
  }

  return (
    <Modal centered className="absolute bg-dim">
      <form
        data-testid="new-label-modal"
        tabIndex={-1}
        className="pointer-events-auto relative flex w-duplicate-assets-modal flex-col gap-modal rounded-default p-modal-wide pt-modal before:absolute before:inset before:h-full before:w-full before:rounded-default before:bg-selected-frame before:backdrop-blur-default"
        onClick={event => {
          event.stopPropagation()
        }}
        onSubmit={event => {
          event.preventDefault()
        }}
      >
        <aria.Heading level={2} className="relative text-sm font-semibold">
          {conflictingFiles.length > 0
            ? conflictingProjects.length > 0
              ? getText('duplicateFilesAndProjectsFound')
              : getText('duplicateFilesFound')
            : getText('duplicateProjectsFound')}
        </aria.Heading>
        {nonConflictingFileCount > 0 ||
          (nonConflictingProjectCount > 0 && (
            <div className="relative flex flex-col">
              {nonConflictingFileCount > 0 && (
                <aria.Text className="text">
                  {nonConflictingFileCount === 1
                    ? getText('fileWithoutConflicts')
                    : getText('filesWithoutConflicts', nonConflictingFileCount)}
                </aria.Text>
              )}
              {nonConflictingProjectCount > 0 && (
                <aria.Text className="text">
                  {nonConflictingProjectCount === 1
                    ? getText('projectWithoutConflicts')
                    : getText('projectsWithoutConflicts', nonConflictingFileCount)}
                </aria.Text>
              )}
              <UnstyledButton
                isDisabled={didUploadNonConflicting}
                className="button relative self-start rounded-full bg-selected-frame selectable enabled:active"
                onPress={() => {
                  doUploadNonConflicting()
                  setDidUploadNonConflicting(true)
                }}
              >
                {didUploadNonConflicting ? getText('uploaded') : getText('upload')}
              </UnstyledButton>
            </div>
          ))}
        {firstConflict && (
          <>
            <div className="flex flex-col">
              <aria.Text className="relative">{getText('currentColon')}</aria.Text>
              <AssetSummary asset={firstConflict.current} className="relative" />
            </div>
            <div className="flex flex-col">
              <aria.Text className="relative">{getText('newColon')}</aria.Text>
              <AssetSummary
                new
                newName={backendModule.stripProjectExtension(findNewName(firstConflict, false))}
                asset={firstConflict.new}
                className="relative"
              />
            </div>
            {count > 1 && (
              <ButtonRow>
                <UnstyledButton
                  className="button bg-selected-frame active"
                  onPress={() => {
                    doUpdate([firstConflict])
                    switch (firstConflict.new.type) {
                      case backendModule.AssetType.file: {
                        setConflictingFiles(oldConflicts => oldConflicts.slice(1))
                        break
                      }
                      case backendModule.AssetType.project: {
                        setConflictingProjects(oldConflicts => oldConflicts.slice(1))
                        break
                      }
                    }
                  }}
                >
                  {getText('update')}
                </UnstyledButton>
                <UnstyledButton
                  className="button  bg-selected-frame active"
                  onPress={() => {
                    doRename([firstConflict])
                    switch (firstConflict.new.type) {
                      case backendModule.AssetType.file: {
                        setConflictingFiles(oldConflicts => oldConflicts.slice(1))
                        break
                      }
                      case backendModule.AssetType.project: {
                        setConflictingProjects(oldConflicts => oldConflicts.slice(1))
                        break
                      }
                    }
                  }}
                >
                  {firstConflict.new.type === backendModule.AssetType.file
                    ? getText('renameNewFile')
                    : getText('renameNewProject')}
                </UnstyledButton>
              </ButtonRow>
            )}
          </>
        )}
        {otherFilesCount > 0 && (
          <aria.Text className="relative">
            {otherFilesCount === 1
              ? getText('andOtherFile')
              : getText('andOtherFiles', otherFilesCount)}
          </aria.Text>
        )}
        {otherProjectsCount > 0 && (
          <aria.Text className="relative">
            {otherProjectsCount === 1
              ? getText('andOtherProject')
              : getText('andOtherProjects', otherProjectsCount)}
          </aria.Text>
        )}
        <ButtonRow>
          <UnstyledButton
            className="button bg-invite text-white active"
            onPress={() => {
              unsetModal()
              doUploadNonConflicting()
              doUpdate([...conflictingFiles, ...conflictingProjects])
            }}
          >
            {count === 1 ? getText('update') : getText('updateAll')}
          </UnstyledButton>
          <UnstyledButton
            className="button bg-invite text-white active"
            onPress={() => {
              unsetModal()
              doUploadNonConflicting()
              doRename([...conflictingFiles, ...conflictingProjects])
            }}
          >
            {count === 1
              ? firstConflict?.new.type === backendModule.AssetType.file
                ? getText('renameNewFile')
                : getText('renameNewProject')
              : firstConflict?.new.type === backendModule.AssetType.file
                ? getText('renameNewFiles')
                : getText('renameNewProjects')}
          </UnstyledButton>
          <UnstyledButton className="button bg-selected-frame active" onPress={unsetModal}>
            {getText('cancel')}
          </UnstyledButton>
        </ButtonRow>
      </form>
    </Modal>
  )
}
