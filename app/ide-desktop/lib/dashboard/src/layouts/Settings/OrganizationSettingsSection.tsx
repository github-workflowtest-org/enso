/** @file Settings tab for viewing and editing account information. */
import * as React from 'react'

import isEmail from 'validator/lib/isEmail'

import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as backendProvider from '#/providers/BackendProvider'
import * as textProvider from '#/providers/TextProvider'

import * as aria from '#/components/aria'
import SettingsInput from '#/components/styled/settings/SettingsInput'
import SettingsSection from '#/components/styled/settings/SettingsSection'

import * as backendModule from '#/services/Backend'

import * as object from '#/utilities/object'

// ===================================
// === OrganizationSettingsSection ===
// ===================================

/** Props for a {@link OrganizationSettingsSection}. */
export interface OrganizationSettingsSectionProps {
  readonly organization: backendModule.OrganizationInfo
  readonly setOrganization: React.Dispatch<React.SetStateAction<backendModule.OrganizationInfo>>
}

/** Settings tab for viewing and editing organization information. */
export default function OrganizationSettingsSection(props: OrganizationSettingsSectionProps) {
  const { organization, setOrganization } = props
  const toastAndLog = toastAndLogHooks.useToastAndLog()
  const { backend } = backendProvider.useBackend()
  const { getText } = textProvider.useText()
  const nameRef = React.useRef<HTMLInputElement | null>(null)
  const emailRef = React.useRef<HTMLInputElement | null>(null)
  const websiteRef = React.useRef<HTMLInputElement | null>(null)
  const locationRef = React.useRef<HTMLInputElement | null>(null)

  const doUpdateName = async () => {
    const oldName = organization.name ?? null
    const name = nameRef.current?.value ?? ''
    if (oldName !== name) {
      try {
        // eslint-disable-next-line @typescript-eslint/naming-convention
        setOrganization(object.merger({ name: name }))
        const newOrganization = await backend.updateOrganization({ name })
        if (newOrganization != null) {
          setOrganization(newOrganization)
        }
      } catch (error) {
        // eslint-disable-next-line @typescript-eslint/naming-convention
        setOrganization(object.merger({ name: oldName }))
        toastAndLog(null, error)
        const ref = nameRef.current
        if (ref) {
          ref.value = oldName ?? ''
        }
      }
    }
  }

  const doUpdateEmail = async () => {
    const oldEmail = organization.email ?? null
    const email = backendModule.EmailAddress(emailRef.current?.value ?? '')
    if (oldEmail !== email) {
      try {
        setOrganization(object.merger({ email }))
        const newOrganization = await backend.updateOrganization({ email })
        if (newOrganization != null) {
          setOrganization(newOrganization)
        }
      } catch (error) {
        setOrganization(object.merger({ email: oldEmail }))
        toastAndLog(null, error)
        const ref = emailRef.current
        if (ref) {
          ref.value = oldEmail ?? ''
        }
      }
    }
  }

  const doUpdateWebsite = async () => {
    const oldWebsite = organization.website ?? null
    const website = backendModule.HttpsUrl(websiteRef.current?.value ?? '')
    if (oldWebsite !== website) {
      try {
        setOrganization(object.merger({ website }))
        await backend.updateOrganization({ website })
      } catch (error) {
        setOrganization(object.merger({ website: oldWebsite }))
        toastAndLog(null, error)
        const ref = websiteRef.current
        if (ref) {
          ref.value = oldWebsite ?? ''
        }
      }
    }
  }

  const doUpdateLocation = async () => {
    const oldLocation = organization.address ?? null
    const location = locationRef.current?.value ?? ''
    if (oldLocation !== location) {
      try {
        setOrganization(object.merger({ address: location }))
        const newOrganization = await backend.updateOrganization({ address: location })
        if (newOrganization != null) {
          setOrganization(newOrganization)
        }
      } catch (error) {
        setOrganization(object.merger({ address: oldLocation }))
        toastAndLog(null, error)
        const ref = locationRef.current
        if (ref) {
          ref.value = oldLocation ?? ''
        }
      }
    }
  }

  return (
    <SettingsSection title={getText('organization')}>
      <div key={JSON.stringify(organization)} className="flex flex-col">
        <aria.TextField
          key={organization.name}
          defaultValue={organization.name ?? ''}
          className="flex h-row gap-settings-entry"
        >
          <aria.Label className="text my-auto w-organization-settings-label">
            {getText('organizationDisplayName')}
          </aria.Label>
          <SettingsInput
            key={organization.name}
            ref={nameRef}
            type="text"
            onSubmit={doUpdateName}
          />
        </aria.TextField>
        <aria.TextField
          key={organization.email}
          defaultValue={organization.email ?? ''}
          className="flex h-row gap-settings-entry"
        >
          <aria.Label className="text my-auto w-organization-settings-label">
            {getText('email')}
          </aria.Label>
          <SettingsInput
            key={organization.email}
            ref={emailRef}
            type="text"
            onSubmit={value => {
              if (isEmail(value)) {
                void doUpdateEmail()
              } else {
                emailRef.current?.focus()
              }
            }}
            onChange={() => {
              emailRef.current?.setCustomValidity(
                isEmail(emailRef.current.value) ? '' : 'Invalid email.'
              )
            }}
          />
        </aria.TextField>
        <aria.TextField
          key={organization.website}
          defaultValue={organization.website ?? ''}
          className="flex h-row gap-settings-entry"
        >
          <aria.Label className="text my-auto w-organization-settings-label">
            {getText('website')}
          </aria.Label>
          <SettingsInput
            key={organization.website}
            ref={websiteRef}
            type="text"
            onSubmit={doUpdateWebsite}
          />
        </aria.TextField>
        <aria.TextField
          key={organization.address}
          defaultValue={organization.address ?? ''}
          className="flex h-row gap-settings-entry"
        >
          <aria.Label className="text my-auto w-organization-settings-label">
            {getText('location')}
          </aria.Label>
          <SettingsInput
            ref={locationRef}
            key={organization.address}
            type="text"
            onSubmit={doUpdateLocation}
          />
        </aria.TextField>
      </div>
    </SettingsSection>
  )
}
