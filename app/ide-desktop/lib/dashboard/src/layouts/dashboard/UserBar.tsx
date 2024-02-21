/** @file A toolbar containing chat and the user menu. */
import * as React from 'react'

import ChatIcon from 'enso-assets/chat.svg'
import DefaultUserIcon from 'enso-assets/default_user.svg'

import * as authProvider from '#/providers/AuthProvider'
import * as modalProvider from '#/providers/ModalProvider'

import InviteUsersModal from '#/layouts/dashboard/InviteUsersModal'
import ManagePermissionsModal from '#/layouts/dashboard/ManagePermissionsModal'
import * as pageSwitcher from '#/layouts/dashboard/PageSwitcher'
import UserMenu from '#/layouts/dashboard/UserMenu'

import Button from '#/components/Button'

import type * as backendModule from '#/services/Backend'

// ===============
// === UserBar ===
// ===============

/** Props for a {@link UserBar}. */
export interface UserBarProps {
  readonly supportsLocalBackend: boolean
  readonly isCloud: boolean
  readonly page: pageSwitcher.Page
  readonly setPage: (page: pageSwitcher.Page) => void
  readonly isHelpChatOpen: boolean
  readonly setIsHelpChatOpen: (isHelpChatOpen: boolean) => void
  readonly projectAsset: backendModule.SmartProject | null
  readonly setProjectAsset: React.Dispatch<React.SetStateAction<backendModule.ProjectAsset>> | null
  readonly doRemoveSelf: () => void
  readonly onSignOut: () => void
}

/** A toolbar containing chat and the user menu. */
export default function UserBar(props: UserBarProps) {
  const { supportsLocalBackend, isCloud, page, setPage, isHelpChatOpen, setIsHelpChatOpen } = props
  const { projectAsset, setProjectAsset, doRemoveSelf, onSignOut } = props
  const { type: sessionType, user } = authProvider.useNonPartialUserSession()
  const { setModal, updateModal } = modalProvider.useSetModal()
  const self =
    user != null
      ? projectAsset?.value.permissions?.find(
          permissions => permissions.user.user_email === user.value.email
        ) ?? null
      : null
  const shouldShowShareButton =
    isCloud &&
    page === pageSwitcher.Page.editor &&
    projectAsset != null &&
    setProjectAsset != null &&
    self != null
  const shouldShowInviteButton =
    sessionType === authProvider.UserSessionType.full && !shouldShowShareButton
  const shouldMakeSpaceForExtendedEditorMenu = page === pageSwitcher.Page.editor
  return (
    <div
      className={
        'flex shrink-0 items-center bg-frame backdrop-blur-3xl rounded-full gap-3 h-8 pl-2 pr-0.75 cursor-default pointer-events-auto' +
        (shouldMakeSpaceForExtendedEditorMenu ? ' mr-10' : '')
      }
    >
      <Button
        active={isHelpChatOpen}
        image={ChatIcon}
        onClick={() => {
          setIsHelpChatOpen(!isHelpChatOpen)
        }}
      />
      {shouldShowInviteButton && (
        <button
          className="text-inversed bg-share rounded-full leading-5 h-6 px-2 py-px"
          onClick={event => {
            event.stopPropagation()
            setModal(<InviteUsersModal eventTarget={null} />)
          }}
        >
          Invite
        </button>
      )}
      {shouldShowShareButton && (
        <button
          className="text-inversed bg-share rounded-full leading-5 h-6 px-2 py-px"
          onClick={event => {
            event.stopPropagation()
            setModal(
              <ManagePermissionsModal
                item={projectAsset}
                setItem={setProjectAsset}
                self={self}
                doRemoveSelf={doRemoveSelf}
                eventTarget={null}
              />
            )
          }}
        >
          Share
        </button>
      )}
      <button
        className="flex items-center select-none rounded-full overflow-clip w-7.25 h-7.25"
        onClick={event => {
          event.stopPropagation()
          updateModal(oldModal =>
            oldModal?.type === UserMenu ? null : (
              <UserMenu
                setPage={setPage}
                supportsLocalBackend={supportsLocalBackend}
                onSignOut={onSignOut}
              />
            )
          )
        }}
      >
        <img
          src={user?.value.profilePicture ?? DefaultUserIcon}
          alt="Open user menu"
          height={28}
          width={28}
        />
      </button>
      {/* Required for shortcuts to work. */}
      <div className="hidden">
        <UserMenu
          hidden
          setPage={setPage}
          supportsLocalBackend={supportsLocalBackend}
          onSignOut={onSignOut}
        />
      </div>
    </div>
  )
}
