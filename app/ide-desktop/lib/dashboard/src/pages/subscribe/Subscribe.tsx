/** @file A page in which the currently active payment plan can be changed. */
import * as React from 'react'

import * as stripeReact from '@stripe/react-stripe-js'
import type * as stripeTypes from '@stripe/stripe-js'
import * as stripe from '@stripe/stripe-js/pure'
import * as toast from 'react-toastify'

import * as load from 'enso-common/src/load'

import * as appUtils from '#/appUtils'
import type * as text from '#/text'

import * as navigateHooks from '#/hooks/navigateHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as backendProvider from '#/providers/BackendProvider'
import * as textProvider from '#/providers/TextProvider'

import Modal from '#/components/Modal'
import UnstyledButton from '#/components/UnstyledButton'

import * as backendModule from '#/services/Backend'

import * as string from '#/utilities/string'

// =================
// === Constants ===
// =================

let stripePromise: Promise<stripeTypes.Stripe | null> | null = null

/** The delay in milliseconds before redirecting back to the main page. */
const REDIRECT_DELAY_MS = 1_500

const PLAN_TO_TEXT_ID: Readonly<Record<backendModule.Plan, text.TextId>> = {
  [backendModule.Plan.solo]: 'soloPlanName',
  [backendModule.Plan.team]: 'teamPlanName',
} satisfies { [Plan in backendModule.Plan]: `${Plan}PlanName` }

// =================
// === Subscribe ===
// =================

/** A page in which the currently active payment plan can be changed.
 *
 * This page can be in one of several states:
 *
 * 1. Initial (i.e. `plan = null, clientSecret = '', sessionStatus = null`),
 * 2. Plan selected (e.g. `plan = 'solo', clientSecret = '', sessionStatus = null`),
 * 3. Session created (e.g. `plan = 'solo', clientSecret = 'cs_foo',
 * sessionStatus.status = { status: 'open' || 'complete' || 'expired',
 * paymentStatus: 'no_payment_required' || 'paid' || 'unpaid' }`),
 * 4. Session complete (e.g. `plan = 'solo', clientSecret = 'cs_foo',
 * sessionStatus.status = { status: 'complete',
 * paymentStatus: 'no_payment_required' || 'paid' || 'unpaid' }`). */
export default function Subscribe() {
  const { getText } = textProvider.useText()
  const navigate = navigateHooks.useNavigate()
  // Plan that the user has currently selected, if any (e.g., 'solo', 'team', etc.).
  const [plan, setPlan] = React.useState(() => {
    const initialPlan = new URLSearchParams(location.search).get('plan')
    return backendModule.isPlan(initialPlan) ? initialPlan : backendModule.Plan.solo
  })
  // A client secret used to access details about a Checkout Session on the Stripe API. A Checkout
  // Session represents a customer's session as they are in the process of paying for a
  // subscription. The client secret is provided by Stripe when the Checkout Session is created.
  const [clientSecret, setClientSecret] = React.useState('')
  // The ID of a Checkout Session on the Stripe API. This is the same as the client secret, minus
  // the secret part. Without the secret part, the session ID can be safely stored in the URL
  // query.
  const [sessionId, setSessionId] = React.useState<backendModule.CheckoutSessionId | null>(null)
  // The status of a Checkout Session on the Stripe API. This stores whether or not the Checkout
  // Session is complete (i.e., the user has provided payment information), and if so, whether
  // payment has been confirmed.
  const [sessionStatus, setSessionStatus] =
    React.useState<backendModule.CheckoutSessionStatus | null>(null)
  const { backend } = backendProvider.useBackend()
  const toastAndLog = toastAndLogHooks.useToastAndLog()

  if (stripePromise == null && process.env.ENSO_CLOUD_STRIPE_KEY != null) {
    const stripeKey = process.env.ENSO_CLOUD_STRIPE_KEY
    stripePromise = load.loadScript('https://js.stripe.com/v3/').then(async script => {
      const innerStripe = await stripe.loadStripe(stripeKey)
      script.remove()
      return innerStripe
    })
  }

  React.useEffect(() => {
    void (async () => {
      try {
        const checkoutSession = await backend.createCheckoutSession(plan)
        setClientSecret(checkoutSession.clientSecret)
        setSessionId(checkoutSession.id)
      } catch (error) {
        toastAndLog(null, error)
      }
    })()
  }, [backend, plan, /* should never change */ toastAndLog])

  React.useEffect(() => {
    if (sessionStatus?.status === 'complete') {
      toast.toast.success('Your plan has successfully been upgraded!')
      window.setTimeout(() => {
        navigate(appUtils.DASHBOARD_PATH)
      }, REDIRECT_DELAY_MS)
    }
  }, [sessionStatus?.status, navigate])

  const onComplete = React.useCallback(() => {
    if (sessionId != null) {
      void (async () => {
        try {
          setSessionStatus(await backend.getCheckoutSession(sessionId))
        } catch (error) {
          toastAndLog(null, error)
        }
      })()
    }
    // Stripe does not allow this callback to change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  return (
    <Modal centered className="bg-hover-bg text-xs text-primary">
      <div
        data-testid="subscribe-modal"
        className="flex max-h-screen w-full max-w-md flex-col gap-modal rounded-default bg-selected-frame p-auth backdrop-blur-default"
        onClick={event => {
          event.stopPropagation()
        }}
      >
        <div className="self-center text-xl">
          {getText('upgradeTo', string.capitalizeFirst(plan))}
        </div>
        <div className="flex h-row items-stretch rounded-full bg-gray-500/30 text-base">
          {backendModule.PLANS.map(newPlan => (
            <UnstyledButton
              key={newPlan}
              isDisabled={plan === newPlan}
              className="flex-1 grow rounded-full disabled:bg-frame"
              onPress={() => {
                setPlan(newPlan)
              }}
            >
              {PLAN_TO_TEXT_ID[newPlan]}
            </UnstyledButton>
          ))}
        </div>
        {sessionId && clientSecret ? (
          <div className="overflow-auto">
            <stripeReact.EmbeddedCheckoutProvider
              key={sessionId}
              stripe={stripePromise}
              // Above, `sessionId` is updated when the `checkoutSession` is created.
              // This triggers a fetch of the session's `status`.
              // The `status` is not going to be `complete` at that point
              // (unless the user completes the checkout process before the fetch is complete).
              // So the `status` needs to be fetched again when the `checkoutSession` is updated.
              // This is done by passing a function to `onComplete`.
              options={{ clientSecret, onComplete }}
            >
              <stripeReact.EmbeddedCheckout />
            </stripeReact.EmbeddedCheckoutProvider>
          </div>
        ) : (
          <div className="h-payment-form transition-all" />
        )}
      </div>
    </Modal>
  )
}
