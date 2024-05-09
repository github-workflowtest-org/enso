/** @file A styled horizontal button row. Does not have padding; does not have a background. */
import * as React from 'react'

import FocusArea from '#/components/styled/FocusArea'

// =================
// === ButtonRow ===
// =================

/** The flex `align-self` of a {@link ButtonRow}. */
export type ButtonRowPosition = 'center' | 'end' | 'start'

/** Props for a {@link ButtonRow}. */
export interface ButtonRowProps extends Readonly<React.PropsWithChildren> {
  /** The flex `align-self` of this element. Defaults to `start`. */
  readonly position?: ButtonRowPosition
}

/** A styled horizontal button row. Does not have padding; does not have a background. */
export default function ButtonRow(props: ButtonRowProps) {
  const { children, position = 'start' } = props
  const positionClass =
    position === 'start' ? 'self-start' : position === 'center' ? 'self-center' : 'self-end'

  return (
    <FocusArea direction="horizontal">
      {innerProps => (
        <div className={`relative flex gap-buttons self-start ${positionClass}`} {...innerProps}>
          {children}
        </div>
      )}
    </FocusArea>
  )
}
