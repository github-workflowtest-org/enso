/** @file Actions for the user menu. */
import type * as test from 'playwright/test'

import type * as baseActions from './BaseActions'
import type BaseActions from './BaseActions'
import LoginPageActions from './LoginPageActions'

// =======================
// === UserMenuActions ===
// =======================

/** Actions for the user menu. */
export interface UserMenuActions<T extends BaseActions> {
  readonly downloadApp: (callback: (download: test.Download) => Promise<void> | void) => T
  readonly logout: () => LoginPageActions
  readonly goToLoginPage: () => LoginPageActions
}

// =======================
// === userMenuActions ===
// =======================

/** Generate actions for the user menu. */
export function userMenuActions<T extends BaseActions>(
  step: (name: string, callback: baseActions.PageCallback) => T
): UserMenuActions<T> {
  return {
    downloadApp: (callback: (download: test.Download) => Promise<void> | void) => {
      return step('Download app (user menu)', async page => {
        const downloadPromise = page.waitForEvent('download')
        await page.getByRole('button', { name: 'Download App' }).getByText('Download App').click()
        await callback(await downloadPromise)
      })
    },
    logout: () =>
      step('Logout (user menu)', page =>
        page.getByRole('button', { name: 'Logout' }).getByText('Logout').click()
      ).into(LoginPageActions),
    goToLoginPage: () =>
      step('Login (user menu)', page =>
        page.getByRole('button', { name: 'Login', exact: true }).getByText('Login').click()
      ).into(LoginPageActions),
  }
}
