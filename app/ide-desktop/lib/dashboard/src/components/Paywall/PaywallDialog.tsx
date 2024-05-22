/**
 * @file
 *
 * A dialog that prompts the user to upgrade to a paid plan.
 */

import * as React from 'react'

import * as billingHooks from '#/hooks/billing'

import * as textProvider from '#/providers/TextProvider'

import * as ariaComponents from '#/components/AriaComponents'

import * as components from './components'
import * as upgradeButton from './UpgradeButton'

/**
 * Props for a {@link PaywallDialog}.
 */
export interface PaywallDialogProps extends ariaComponents.DialogProps {
  readonly feature: billingHooks.PaywallFeatureName
}

/**
 * A dialog that prompts the user to upgrade to a paid plan.
 */
export function PaywallDialog(props: PaywallDialogProps) {
  const { feature, type = 'modal', title, ...dialogProps } = props

  const { getText } = textProvider.useText()
  const { getFeature } = billingHooks.usePaywallFeatures()

  const { bulletPointsTextId, label, descriptionTextId } = getFeature(feature)

  return (
    <ariaComponents.Dialog type={type} title={title ?? getText(label)} {...dialogProps}>
      <div className="flex flex-col">
        <components.PaywallLock feature={feature} className="mb-2" />

        <p className="text-base text-primary/60">{getText(descriptionTextId)}</p>

        <components.PaywallBulletPoints bulletPointsTextId={bulletPointsTextId} className="my-4" />

        <upgradeButton.UpgradeButton feature={feature} rounding="xlarge" className="mt-2" />
      </div>
    </ariaComponents.Dialog>
  )
}
