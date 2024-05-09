/** @file Constants related to the application root component. */

// =================
// === Constants ===
// =================

// =============
// === Paths ===
// =============

/** Path to the root of the app (i.e., the Cloud dashboard). */
export const DASHBOARD_PATH = '/'
/** Path to the login page. */
export const LOGIN_PATH = '/login'
/** Path to the registration page. */
export const REGISTRATION_PATH = '/registration'
/** Path to the confirm registration page. */
export const CONFIRM_REGISTRATION_PATH = '/confirmation'
/**
 * Path to the page in which a user can restore their account after it has been
 * marked for deletion.
 */
export const RESTORE_USER_PATH = '/restore-user'
/** Path to the forgot password page. */
export const FORGOT_PASSWORD_PATH = '/forgot-password'
/** Path to the reset password page. */
export const RESET_PASSWORD_PATH = '/password-reset'
/** Path to the set username page. */
export const SET_USERNAME_PATH = '/set-username'
/** Path to the offline mode entrypoint. */
export const ENTER_OFFLINE_MODE_PATH = '/offline'
/** Path to page in which the currently active payment plan can be managed. */
export const SUBSCRIBE_PATH = '/subscribe'
/** A {@link RegExp} matching all paths. */
export const ALL_PATHS_REGEX = new RegExp(
  `(?:${DASHBOARD_PATH}|${LOGIN_PATH}|${REGISTRATION_PATH}|${CONFIRM_REGISTRATION_PATH}|` +
    `${FORGOT_PASSWORD_PATH}|${RESET_PASSWORD_PATH}|${SET_USERNAME_PATH}|${RESTORE_USER_PATH}|` +
    `${ENTER_OFFLINE_MODE_PATH}|${SUBSCRIBE_PATH})$`
)

// ===========
// === URL ===
// ===========

export const SEARCH_PARAMS_PREFIX = 'cloud-ide_'
