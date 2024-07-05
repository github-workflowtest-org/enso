/** @file A styled button representing a tab on a sidebar. */
import * as React from 'react'

import OpenInNewIcon from 'enso-assets/open.svg'

import type * as aria from '#/components/aria'
import * as ariaComponent from '#/components/AriaComponents'

// ========================
// === SidebarTabButton ===
// ========================

/** Props for a {@link SidebarTabButton}. */
export interface SidebarTabButtonProps {
  readonly id: string
  readonly isDisabled?: boolean
  readonly autoFocus?: boolean
  /** When `true`, the button is not faded out even when not hovered. */
  readonly active?: boolean
  readonly icon: string
  readonly label: string
  readonly onPress: (event: aria.PressEvent) => void
  readonly href?: string | undefined
}

/** A styled button representing a tab on a sidebar. */
export default function SidebarTabButton(props: SidebarTabButtonProps) {
  const { isDisabled = false, active = false, icon, label, onPress, href } = props

  const isLink = href != null

  return (
    <ariaComponent.Button
      {...(isLink
        ? { href, icon: ({ isHovered }) => (isHovered ? OpenInNewIcon : icon), target: '_blank' }
        : { onPress, icon })}
      variant="ghost"
      size="medium"
      isDisabled={isDisabled}
      rounded="full"
      className={active ? 'bg-white opacity-100' : ''}
    >
      {label}
    </ariaComponent.Button>
  )
}
