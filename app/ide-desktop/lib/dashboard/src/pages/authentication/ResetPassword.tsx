/** @file Container responsible for rendering and interactions in second half of forgot password
 * flow. */
import * as React from 'react'

import * as router from 'react-router-dom'

import ArrowRightIcon from 'enso-assets/arrow_right.svg'
import GoBackIcon from 'enso-assets/go_back.svg'
import LockIcon from 'enso-assets/lock.svg'

import * as appUtils from '#/appUtils'

import * as navigateHooks from '#/hooks/navigateHooks'
import * as toastAndLogHooks from '#/hooks/toastAndLogHooks'

import * as authProvider from '#/providers/AuthProvider'
import * as textProvider from '#/providers/TextProvider'

import AuthenticationPage from '#/pages/authentication/AuthenticationPage'

import * as aria from '#/components/aria'
import Input from '#/components/Input'
import Link from '#/components/Link'
import SubmitButton from '#/components/SubmitButton'

import * as eventModule from '#/utilities/event'
import * as string from '#/utilities/string'
import * as validation from '#/utilities/validation'

// =====================
// === ResetPassword ===
// =====================

/** A form for users to reset their password. */
export default function ResetPassword() {
  const { resetPassword } = authProvider.useAuth()
  const { getText } = textProvider.useText()
  const location = router.useLocation()
  const navigate = navigateHooks.useNavigate()
  const toastAndLog = toastAndLogHooks.useToastAndLog()

  const query = new URLSearchParams(location.search)
  const email = query.get('email')
  const verificationCode = query.get('verification_code')

  const [newPassword, setNewPassword] = React.useState('')
  const [newPasswordConfirm, setNewPasswordConfirm] = React.useState('')

  React.useEffect(() => {
    if (email == null) {
      toastAndLog('missingEmailError')
      navigate(appUtils.LOGIN_PATH)
    } else if (verificationCode == null) {
      toastAndLog('missingVerificationCodeError')
      navigate(appUtils.LOGIN_PATH)
    }
  }, [email, navigate, verificationCode, getText, /* should never change */ toastAndLog])

  const doSubmit = () => {
    if (newPassword !== newPasswordConfirm) {
      toastAndLog('passwordMismatchError')
      return Promise.resolve()
    } else {
      // These should never be nullish, as the effect should immediately navigate away.
      return resetPassword(email ?? '', verificationCode ?? '', newPassword)
    }
  }

  return (
    <AuthenticationPage
      title={getText('resetYourPassword')}
      footer={<Link to={appUtils.LOGIN_PATH} icon={GoBackIcon} text={getText('goBackToLogin')} />}
      onSubmit={async event => {
        event.preventDefault()
        await doSubmit()
      }}
    >
      <aria.Input
        required
        readOnly
        hidden
        type="email"
        autoComplete="email"
        placeholder={getText('emailPlaceholder')}
        value={email ?? ''}
      />
      <aria.Input
        required
        readOnly
        hidden
        type="text"
        autoComplete="one-time-code"
        placeholder={getText('confirmationCodePlaceholder')}
        value={verificationCode ?? ''}
      />
      <Input
        autoFocus
        required
        validate
        allowShowingPassword
        type="password"
        autoComplete="new-password"
        icon={LockIcon}
        placeholder={getText('newPasswordPlaceholder')}
        pattern={validation.PASSWORD_PATTERN}
        error={getText('passwordValidationError')}
        value={newPassword}
        setValue={setNewPassword}
      />
      <Input
        required
        validate
        allowShowingPassword
        type="password"
        autoComplete="new-password"
        icon={LockIcon}
        placeholder={getText('confirmNewPasswordPlaceholder')}
        pattern={string.regexEscape(newPassword)}
        error={getText('passwordMismatchError')}
        value={newPasswordConfirm}
        setValue={setNewPasswordConfirm}
      />
      <SubmitButton
        text={getText('reset')}
        icon={ArrowRightIcon}
        onPress={eventModule.submitForm}
      />
    </AuthenticationPage>
  )
}
