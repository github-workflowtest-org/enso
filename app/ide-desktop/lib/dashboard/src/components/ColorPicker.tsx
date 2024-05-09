/** @file A color picker to select from a predetermined list of colors. */
import * as React from 'react'

import * as focusHooks from '#/hooks/focusHooks'

import * as focusClassProvider from '#/providers/FocusClassProvider'
import * as focusDirectionProvider from '#/providers/FocusDirectionProvider'

import * as aria from '#/components/aria'
import FocusRing from '#/components/styled/FocusRing'
import RadioGroup from '#/components/styled/RadioGroup'

import * as backend from '#/services/Backend'

/** Props for a {@link ColorPickerItem}. */
export interface InternalColorPickerItemProps {
  readonly color: backend.LChColor
}

/** An input in a {@link ColorPicker}. */
function ColorPickerItem(props: InternalColorPickerItemProps) {
  const { color } = props
  const { focusChildClass } = focusClassProvider.useFocusClasses()
  const focusDirection = focusDirectionProvider.useFocusDirection()
  const handleFocusMove = focusHooks.useHandleFocusMove(focusDirection)
  const cssColor = backend.lChColorToCssColor(color)

  return (
    <FocusRing within>
      <aria.Radio
        ref={element => {
          element?.querySelector('input')?.classList.add(focusChildClass)
        }}
        value={cssColor}
        className="group flex size-radio-button cursor-pointer rounded-full p-radio-button-dot"
        style={{ backgroundColor: cssColor }}
        onKeyDown={handleFocusMove}
      >
        <div className="hidden size-radio-button-dot rounded-full bg-selected-frame group-selected:block" />
      </aria.Radio>
    </FocusRing>
  )
}

// ===================
// === ColorPicker ===
// ===================

/** Props for a {@link ColorPicker}. */
export interface ColorPickerProps extends Readonly<aria.RadioGroupProps> {
  readonly children?: React.ReactNode
  readonly pickerClassName?: string
  readonly setColor: (color: backend.LChColor) => void
}

/** A color picker to select from a predetermined list of colors. */
function ColorPicker(props: ColorPickerProps, ref: React.ForwardedRef<HTMLDivElement>) {
  const { pickerClassName = '', children, setColor, ...radioGroupProps } = props
  return (
    <RadioGroup
      ref={ref}
      {...radioGroupProps}
      orientation="horizontal"
      onChange={value => {
        const color = backend.COLOR_STRING_TO_COLOR.get(value)
        if (color != null) {
          setColor(color)
        }
      }}
    >
      {children}
      <div className={`flex items-center gap-colors ${pickerClassName}`}>
        {backend.COLORS.map((currentColor, i) => (
          <ColorPickerItem key={i} color={currentColor} />
        ))}
      </div>
    </RadioGroup>
  )
}

/** A color picker to select from a predetermined list of colors. */
export default React.forwardRef(ColorPicker)
