/** @file The mock API. */
import * as test from '@playwright/test'

import * as backend from '#/services/Backend'
import type * as remoteBackend from '#/services/RemoteBackend'
import * as remoteBackendPaths from '#/services/remoteBackendPaths'

import * as dateTime from '#/utilities/dateTime'
import * as object from '#/utilities/object'
import * as permissions from '#/utilities/permissions'
import * as uniqueString from '#/utilities/uniqueString'

import * as actions from './actions'

// =================
// === Constants ===
// =================

/** The HTTP status code representing a response with an empty body. */
const HTTP_STATUS_NO_CONTENT = 204
/** The HTTP status code representing a bad request. */
const HTTP_STATUS_BAD_REQUEST = 400
/** The HTTP status code representing a URL that does not exist. */
const HTTP_STATUS_NOT_FOUND = 404
/** An asset ID that is a path glob. */
const GLOB_ASSET_ID: backend.AssetId = backend.DirectoryId('*')
/** A directory ID that is a path glob. */
const GLOB_DIRECTORY_ID = backend.DirectoryId('*')
/** A project ID that is a path glob. */
const GLOB_PROJECT_ID = backend.ProjectId('*')
/** A tag ID that is a path glob. */
const GLOB_TAG_ID = backend.TagId('*')
/* eslint-enable no-restricted-syntax */
const BASE_URL = 'https://mock/'

// ===============
// === mockApi ===
// ===============

/** Parameters for {@link mockApi}. */
interface MockParams {
  readonly page: test.Page
  readonly setupAPI?: SetupAPI | null | undefined
}

/**
 * Setup function for the mock API.
 * use it to setup the mock API with custom handlers.
 */
export interface SetupAPI {
  (api: Awaited<ReturnType<typeof mockApi>>): Promise<void> | void
}

/** Add route handlers for the mock API to a page. */
// This syntax is required for Playwright to work properly.
// eslint-disable-next-line no-restricted-syntax
export async function mockApi({ page, setupAPI }: MockParams) {
  // eslint-disable-next-line no-restricted-syntax
  const defaultEmail = 'email@example.com' as backend.EmailAddress
  const defaultUsername = 'user name'
  const defaultPassword = actions.VALID_PASSWORD
  const defaultOrganizationId = backend.OrganizationId('organization-placeholder id')
  const defaultOrganizationName = 'organization name'
  const defaultUserId = backend.UserId('user-placeholder id')
  const defaultDirectoryId = backend.DirectoryId('directory-placeholder id')
  const defaultUser: backend.User = {
    email: defaultEmail,
    name: defaultUsername,
    organizationId: defaultOrganizationId,
    userId: defaultUserId,
    isEnabled: true,
    rootDirectoryId: defaultDirectoryId,
    userGroups: null,
    plan: backend.Plan.enterprise,
  }
  const defaultOrganization: backend.OrganizationInfo = {
    id: defaultOrganizationId,
    name: defaultOrganizationName,
    address: null,
    email: null,
    picture: null,
    website: null,
    subscription: {},
  }

  let isOnline = true
  let currentUser: backend.User | null = defaultUser
  let currentProfilePicture: string | null = null
  let currentPassword = defaultPassword
  let currentOrganization: backend.OrganizationInfo | null = defaultOrganization
  let currentOrganizationProfilePicture: string | null = null

  const assetMap = new Map<backend.AssetId, backend.AnyAsset>()
  const deletedAssets = new Set<backend.AssetId>()
  const assets: backend.AnyAsset[] = []
  const labels: backend.Label[] = []
  const labelsByValue = new Map<backend.LabelName, backend.Label>()
  const labelMap = new Map<backend.TagId, backend.Label>()
  const users: backend.User[] = [defaultUser]
  const usersMap = new Map<backend.UserId, backend.User>()
  const userGroups: backend.UserGroupInfo[] = []

  usersMap.set(defaultUser.userId, defaultUser)

  const addAsset = <T extends backend.AnyAsset>(asset: T) => {
    assets.push(asset)
    assetMap.set(asset.id, asset)
    return asset
  }

  const deleteAsset = (assetId: backend.AssetId) => {
    const alreadyDeleted = deletedAssets.has(assetId)
    deletedAssets.add(assetId)
    return !alreadyDeleted
  }

  const undeleteAsset = (assetId: backend.AssetId) => {
    const wasDeleted = deletedAssets.has(assetId)
    deletedAssets.delete(assetId)
    return wasDeleted
  }

  const createDirectory = (
    title: string,
    rest: Partial<backend.DirectoryAsset> = {}
  ): backend.DirectoryAsset =>
    object.merge(
      {
        type: backend.AssetType.directory,
        id: backend.DirectoryId('directory-' + uniqueString.uniqueString()),
        projectState: null,
        title,
        modifiedAt: dateTime.toRfc3339(new Date()),
        description: null,
        labels: [],
        parentId: defaultDirectoryId,
        permissions: [],
      },
      rest
    )

  const createProject = (
    title: string,
    rest: Partial<backend.ProjectAsset> = {}
  ): backend.ProjectAsset =>
    object.merge(
      {
        type: backend.AssetType.project,
        id: backend.ProjectId('project-' + uniqueString.uniqueString()),
        projectState: {
          type: backend.ProjectState.opened,
          volumeId: '',
        },
        title,
        modifiedAt: dateTime.toRfc3339(new Date()),
        description: null,
        labels: [],
        parentId: defaultDirectoryId,
        permissions: [],
      },
      rest
    )

  const createFile = (title: string, rest: Partial<backend.FileAsset> = {}): backend.FileAsset =>
    object.merge(
      {
        type: backend.AssetType.file,
        id: backend.FileId('file-' + uniqueString.uniqueString()),
        projectState: null,
        title,
        modifiedAt: dateTime.toRfc3339(new Date()),
        description: null,
        labels: [],
        parentId: defaultDirectoryId,
        permissions: [],
      },
      rest
    )

  const createSecret = (
    title: string,
    rest: Partial<backend.SecretAsset> = {}
  ): backend.SecretAsset =>
    object.merge(
      {
        type: backend.AssetType.secret,
        id: backend.SecretId('secret-' + uniqueString.uniqueString()),
        projectState: null,
        title,
        modifiedAt: dateTime.toRfc3339(new Date()),
        description: null,
        labels: [],
        parentId: defaultDirectoryId,
        permissions: [],
      },
      rest
    )

  const createLabel = (value: string, color: backend.LChColor): backend.Label => ({
    id: backend.TagId('tag-' + uniqueString.uniqueString()),
    value: backend.LabelName(value),
    color,
  })

  const addDirectory = (title: string, rest?: Partial<backend.DirectoryAsset>) => {
    return addAsset(createDirectory(title, rest))
  }

  const addProject = (title: string, rest?: Partial<backend.ProjectAsset>) => {
    return addAsset(createProject(title, rest))
  }

  const addFile = (title: string, rest?: Partial<backend.FileAsset>) => {
    return addAsset(createFile(title, rest))
  }

  const addSecret = (title: string, rest?: Partial<backend.SecretAsset>) => {
    return addAsset(createSecret(title, rest))
  }

  const addLabel = (value: string, color: backend.LChColor) => {
    const label = createLabel(value, color)
    labels.push(label)
    labelsByValue.set(label.value, label)
    labelMap.set(label.id, label)
    return label
  }

  const setLabels = (id: backend.AssetId, newLabels: backend.LabelName[]) => {
    const ids = new Set<backend.AssetId>([id])
    for (const [innerId, asset] of assetMap) {
      if (ids.has(asset.parentId)) {
        ids.add(innerId)
      }
    }
    for (const innerId of ids) {
      const asset = assetMap.get(innerId)
      if (asset != null) {
        object.unsafeMutable(asset).labels = newLabels
      }
    }
  }

  const addUser = (name: string, rest: Partial<backend.User> = {}) => {
    const organizationId = currentOrganization?.id ?? defaultOrganizationId
    const user: backend.User = {
      userId: backend.UserId(`user-${uniqueString.uniqueString()}`),
      name,
      email: backend.EmailAddress(`${name}@example.org`),
      organizationId,
      rootDirectoryId: backend.DirectoryId(organizationId.replace(/^organization-/, 'directory-')),
      isEnabled: true,
      userGroups: null,
      plan: backend.Plan.enterprise,
      ...rest,
    }
    users.push(user)
    usersMap.set(user.userId, user)
    return user
  }

  const deleteUser = (userId: backend.UserId) => {
    usersMap.delete(userId)
    const index = users.findIndex(user => user.userId === userId)
    if (index === -1) {
      return false
    } else {
      users.splice(index, 1)
      return true
    }
  }

  const addUserGroup = (name: string, rest: Partial<backend.UserGroupInfo>) => {
    const userGroup: backend.UserGroupInfo = {
      id: backend.UserGroupId(`usergroup-${uniqueString.uniqueString()}`),
      groupName: name,
      organizationId: currentOrganization?.id ?? defaultOrganizationId,
      ...rest,
    }
    userGroups.push(userGroup)
    return userGroup
  }

  const deleteUserGroup = (userGroupId: backend.UserGroupId) => {
    const index = userGroups.findIndex(userGroup => userGroup.id === userGroupId)
    if (index === -1) {
      return false
    } else {
      users.splice(index, 1)
      return true
    }
  }

  // addPermission,
  // deletePermission,
  // addUserGroupToUser,
  // deleteUserGroupFromUser,
  const addUserGroupToUser = (userId: backend.UserId, userGroupId: backend.UserGroupId) => {
    const user = usersMap.get(userId)
    if (user == null || user.userGroups?.includes(userGroupId) === true) {
      // The user does not exist, or they are already in this group.
      return false
    } else {
      const newUserGroups = object.unsafeMutable(user.userGroups ?? [])
      newUserGroups.push(userGroupId)
      object.unsafeMutable(user).userGroups = newUserGroups
      return true
    }
  }

  const removeUserGroupFromUser = (userId: backend.UserId, userGroupId: backend.UserGroupId) => {
    const user = usersMap.get(userId)
    if (user?.userGroups?.includes(userGroupId) !== true) {
      // The user does not exist, or they are already not in this group.
      return false
    } else {
      object.unsafeMutable(user.userGroups).splice(user.userGroups.indexOf(userGroupId), 1)
      return true
    }
  }

  await test.test.step('Mock API', async () => {
    const method =
      (theMethod: string) =>
      async (url: string, callback: (route: test.Route, request: test.Request) => unknown) => {
        await page.route(BASE_URL + url, async (route, request) => {
          if (request.method() !== theMethod) {
            await route.fallback()
          } else {
            const result = await callback(route, request)
            // `null` counts as a JSON value that we will want to return.
            // eslint-disable-next-line no-restricted-syntax
            if (result !== undefined) {
              await route.fulfill({ json: result })
            }
          }
        })
      }
    const get = method('GET')
    const put = method('PUT')
    const post = method('POST')
    const patch = method('PATCH')
    // eslint-disable-next-line @typescript-eslint/naming-convention
    const delete_ = method('DELETE')

    await page.route('https://cdn.enso.org/**', route => route.fulfill())
    await page.route('https://www.google-analytics.com/**', route => route.fulfill())
    await page.route('https://www.googletagmanager.com/gtag/js*', route =>
      route.fulfill({ contentType: 'text/javascript', body: 'export {};' })
    )
    const isActuallyOnline = await page.evaluate(() => navigator.onLine)
    if (!isActuallyOnline) {
      await page.route('https://fonts.googleapis.com/*', route => route.abort())
    }

    await page.route(BASE_URL + '**', (_route, request) => {
      throw new Error(`Missing route handler for '${request.url().replace(BASE_URL, '')}'.`)
    })

    // === Mock Cognito endpoints ===

    await page.route('https://mock-cognito.com/change-password', async (route, request) => {
      if (request.method() !== 'POST') {
        await route.fallback()
      } else {
        /** The type for the JSON request payload for this endpoint. */
        interface Body {
          readonly oldPassword: string
          readonly newPassword: string
        }
        // The type of the body sent by this app is statically known.
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
        const body: Body = await request.postDataJSON()
        if (body.oldPassword === currentPassword) {
          currentPassword = body.newPassword
          await route.fulfill({ status: HTTP_STATUS_NO_CONTENT })
        } else {
          await route.fulfill({ status: HTTP_STATUS_BAD_REQUEST })
        }
      }
    })

    // === Endpoints returning arrays ===

    await get(remoteBackendPaths.LIST_DIRECTORY_PATH + '*', (_route, request) => {
      /** The type for the search query for this endpoint. */
      interface Query {
        /* eslint-disable @typescript-eslint/naming-convention */
        readonly parent_id?: string
        readonly filter_by?: backend.FilterBy
        readonly labels?: backend.LabelName[]
        readonly recent_projects?: boolean
        /* eslint-enable @typescript-eslint/naming-convention */
      }
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line no-restricted-syntax
      const body = Object.fromEntries(
        new URL(request.url()).searchParams.entries()
      ) as unknown as Query
      const parentId = body.parent_id ?? defaultDirectoryId
      let filteredAssets = assets.filter(asset => asset.parentId === parentId)
      // This lint rule is broken; there is clearly a case for `undefined` below.
      // eslint-disable-next-line @typescript-eslint/switch-exhaustiveness-check
      switch (body.filter_by) {
        case backend.FilterBy.active: {
          filteredAssets = filteredAssets.filter(asset => !deletedAssets.has(asset.id))
          break
        }
        case backend.FilterBy.trashed: {
          filteredAssets = filteredAssets.filter(asset => deletedAssets.has(asset.id))
          break
        }
        case backend.FilterBy.recent: {
          filteredAssets = assets
            .filter(asset => !deletedAssets.has(asset.id))
            // eslint-disable-next-line @typescript-eslint/no-magic-numbers
            .slice(0, 10)
          break
        }
        case backend.FilterBy.all:
        case null: {
          // do nothing
          break
        }
        // eslint-disable-next-line no-restricted-syntax
        case undefined: {
          // do nothing
          break
        }
      }
      filteredAssets.sort(
        (a, b) => backend.ASSET_TYPE_ORDER[a.type] - backend.ASSET_TYPE_ORDER[b.type]
      )
      const json: remoteBackend.ListDirectoryResponseBody = { assets: filteredAssets }
      return json
    })
    await get(
      remoteBackendPaths.LIST_FILES_PATH + '*',
      () => ({ files: [] }) satisfies remoteBackend.ListFilesResponseBody
    )
    await get(
      remoteBackendPaths.LIST_PROJECTS_PATH + '*',
      () => ({ projects: [] }) satisfies remoteBackend.ListProjectsResponseBody
    )
    await get(
      remoteBackendPaths.LIST_SECRETS_PATH + '*',
      () => ({ secrets: [] }) satisfies remoteBackend.ListSecretsResponseBody
    )
    await get(
      remoteBackendPaths.LIST_TAGS_PATH + '*',
      () => ({ tags: labels }) satisfies remoteBackend.ListTagsResponseBody
    )
    await get(remoteBackendPaths.LIST_USERS_PATH + '*', async route => {
      if (currentUser != null) {
        return { users } satisfies remoteBackend.ListUsersResponseBody
      } else {
        await route.fulfill({ status: HTTP_STATUS_BAD_REQUEST })
        return
      }
    })
    await get(remoteBackendPaths.LIST_VERSIONS_PATH + '*', (_route, request) => ({
      versions: [
        {
          ami: null,
          created: dateTime.toRfc3339(new Date()),
          number: {
            lifecycle:
              // eslint-disable-next-line no-restricted-syntax
              'Development' satisfies `${backend.VersionLifecycle.development}` as backend.VersionLifecycle.development,
            value: '2023.2.1-dev',
          },
          // eslint-disable-next-line @typescript-eslint/naming-convention, no-restricted-syntax
          version_type: (new URL(request.url()).searchParams.get('version_type') ??
            '') as backend.VersionType,
        } satisfies backend.Version,
      ],
    }))

    // === Endpoints with dummy implementations ===

    await get(remoteBackendPaths.getProjectDetailsPath(GLOB_PROJECT_ID), (_route, request) => {
      const projectId = request.url().match(/[/]projects[/](.+?)[/]copy/)?.[1] ?? ''
      return {
        organizationId: defaultOrganizationId,
        projectId: backend.ProjectId(projectId),
        name: 'example project name',
        state: {
          type: backend.ProjectState.opened,
          volumeId: '',
          openedBy: defaultEmail,
        },
        packageName: 'Project_root',
        // eslint-disable-next-line @typescript-eslint/naming-convention
        ide_version: null,
        // eslint-disable-next-line @typescript-eslint/naming-convention
        engine_version: {
          value: '2023.2.1-nightly.2023.9.29',
          lifecycle: backend.VersionLifecycle.development,
        },
        address: backend.Address('ws://example.com/'),
      } satisfies backend.ProjectRaw
    })

    // === Endpoints returning `void` ===

    await post(remoteBackendPaths.copyAssetPath(GLOB_ASSET_ID), async (route, request) => {
      /** The type for the JSON request payload for this endpoint. */
      interface Body {
        readonly parentDirectoryId: backend.DirectoryId
      }
      const assetId = request.url().match(/[/]assets[/](.+?)[/]copy/)?.[1]
      // eslint-disable-next-line no-restricted-syntax
      const asset = assetId != null ? assetMap.get(assetId as backend.AssetId) : null
      if (asset == null) {
        if (assetId == null) {
          await route.fulfill({
            status: HTTP_STATUS_BAD_REQUEST,
            json: { message: 'Invalid Asset ID' },
          })
        } else {
          await route.fulfill({
            status: HTTP_STATUS_NOT_FOUND,
            json: { message: 'Asset does not exist' },
          })
        }
      } else {
        // The type of the body sent by this app is statically known.
        // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
        const body: Body = await request.postDataJSON()
        const parentId = body.parentDirectoryId
        // Can be any asset ID.
        const id = backend.DirectoryId(uniqueString.uniqueString())
        const json: backend.CopyAssetResponse = {
          asset: {
            id,
            parentId,
            title: asset.title + ' (copy)',
          },
        }
        const newAsset = { ...asset }
        newAsset.id = id
        newAsset.parentId = parentId
        newAsset.title += ' (copy)'
        addAsset(newAsset)
        await route.fulfill({ json })
      }
    })
    await get(remoteBackendPaths.INVITATION_PATH + '*', async route => {
      await route.fulfill({
        json: { invitations: [] } satisfies backend.ListInvitationsResponseBody,
      })
    })
    await post(remoteBackendPaths.INVITE_USER_PATH + '*', async route => {
      await route.fulfill()
    })
    await post(remoteBackendPaths.CREATE_PERMISSION_PATH + '*', async route => {
      await route.fulfill()
    })
    await delete_(remoteBackendPaths.deleteAssetPath(GLOB_ASSET_ID), async route => {
      await route.fulfill()
    })
    await post(remoteBackendPaths.closeProjectPath(GLOB_PROJECT_ID), async route => {
      await route.fulfill()
    })
    await post(remoteBackendPaths.openProjectPath(GLOB_PROJECT_ID), async route => {
      await route.fulfill()
    })
    await delete_(remoteBackendPaths.deleteTagPath(GLOB_TAG_ID), async route => {
      await route.fulfill()
    })
    await post(remoteBackendPaths.POST_LOG_EVENT_PATH, async route => {
      await route.fulfill()
    })

    // === Entity creation endpoints ===

    await put(remoteBackendPaths.UPLOAD_USER_PICTURE_PATH + '*', async (route, request) => {
      const content = request.postData()
      if (content != null) {
        currentProfilePicture = content
        return null
      } else {
        await route.fallback()
        return
      }
    })
    await put(remoteBackendPaths.UPLOAD_ORGANIZATION_PICTURE_PATH + '*', async (route, request) => {
      const content = request.postData()
      if (content != null) {
        currentOrganizationProfilePicture = content
        return null
      } else {
        await route.fallback()
        return
      }
    })
    await post(remoteBackendPaths.UPLOAD_FILE_PATH + '*', (_route, request) => {
      /** The type for the JSON request payload for this endpoint. */
      interface SearchParams {
        // eslint-disable-next-line @typescript-eslint/naming-convention
        readonly file_name: string
        // eslint-disable-next-line @typescript-eslint/naming-convention
        readonly file_id?: backend.FileId
        // eslint-disable-next-line @typescript-eslint/naming-convention
        readonly parent_directory_id?: backend.DirectoryId
      }
      // The type of the search params sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment, no-restricted-syntax
      const searchParams: SearchParams = Object.fromEntries(
        new URL(request.url()).searchParams.entries()
      ) as never
      const file = createFile(searchParams.file_name)
      return { path: '', id: file.id, project: null } satisfies backend.FileInfo
    })

    await post(remoteBackendPaths.CREATE_SECRET_PATH + '*', async (_route, request) => {
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.CreateSecretRequestBody = await request.postDataJSON()
      const secret = createSecret(body.name)
      return secret.id
    })

    // === Other endpoints ===

    await patch(remoteBackendPaths.updateAssetPath(GLOB_ASSET_ID), (_route, request) => {
      const assetId = request.url().match(/[/]assets[/]([^?]+)/)?.[1] ?? ''
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.UpdateAssetRequestBody = request.postDataJSON()
      // This could be an id for an arbitrary asset, but pretend it's a
      // `DirectoryId` to make TypeScript happy.
      const asset = assetMap.get(backend.DirectoryId(assetId))
      if (asset != null) {
        if (body.description != null) {
          object.unsafeMutable(asset).description = body.description
        }
      }
    })
    await patch(remoteBackendPaths.associateTagPath(GLOB_ASSET_ID), async (_route, request) => {
      const assetId = request.url().match(/[/]assets[/]([^/?]+)/)?.[1] ?? ''
      /** The type for the JSON request payload for this endpoint. */
      interface Body {
        readonly labels: backend.LabelName[]
      }
      /** The type for the JSON response payload for this endpoint. */
      interface Response {
        readonly tags: backend.Label[]
      }
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: Body = await request.postDataJSON()
      // This could be an id for an arbitrary asset, but pretend it's a
      // `DirectoryId` to make TypeScript happy.
      setLabels(backend.DirectoryId(assetId), body.labels)
      const json: Response = {
        tags: body.labels.flatMap(value => {
          const label = labelsByValue.get(value)
          return label != null ? [label] : []
        }),
      }
      return json
    })
    await put(remoteBackendPaths.updateDirectoryPath(GLOB_DIRECTORY_ID), async (route, request) => {
      const directoryId = request.url().match(/[/]directories[/]([^?]+)/)?.[1] ?? ''
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.UpdateDirectoryRequestBody = request.postDataJSON()
      const asset = assetMap.get(backend.DirectoryId(directoryId))
      if (asset == null) {
        await route.abort()
      } else {
        object.unsafeMutable(asset).title = body.title
        await route.fulfill({
          json: {
            id: backend.DirectoryId(directoryId),
            parentId: asset.parentId,
            title: body.title,
          } satisfies backend.UpdatedDirectory,
        })
      }
    })
    await delete_(remoteBackendPaths.deleteAssetPath(GLOB_ASSET_ID), async (route, request) => {
      const assetId = request.url().match(/[/]assets[/]([^?]+)/)?.[1] ?? ''
      // This could be an id for an arbitrary asset, but pretend it's a
      // `DirectoryId` to make TypeScript happy.
      deleteAsset(backend.DirectoryId(assetId))
      await route.fulfill({ status: HTTP_STATUS_NO_CONTENT })
    })
    await patch(remoteBackendPaths.UNDO_DELETE_ASSET_PATH, async (route, request) => {
      /** The type for the JSON request payload for this endpoint. */
      interface Body {
        readonly assetId: backend.AssetId
      }
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: Body = await request.postDataJSON()
      undeleteAsset(body.assetId)
      await route.fulfill({ status: HTTP_STATUS_NO_CONTENT })
    })
    await post(remoteBackendPaths.CREATE_USER_PATH + '*', async (route, request) => {
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.CreateUserRequestBody = await request.postDataJSON()
      const organizationId = body.organizationId ?? defaultUser.organizationId
      const rootDirectoryId = backend.DirectoryId(
        organizationId.replace(/^organization-/, 'directory-')
      )
      currentUser = {
        email: body.userEmail,
        name: body.userName,
        organizationId,
        userId: backend.UserId(`user-${uniqueString.uniqueString()}`),
        isEnabled: false,
        rootDirectoryId,
        userGroups: null,
        plan: backend.Plan.enterprise,
      }
      await route.fulfill({ json: currentUser })
    })
    await put(remoteBackendPaths.UPDATE_CURRENT_USER_PATH + '*', async (_route, request) => {
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.UpdateUserRequestBody = await request.postDataJSON()
      if (currentUser && body.username != null) {
        currentUser = { ...currentUser, name: body.username }
      }
    })
    await get(remoteBackendPaths.USERS_ME_PATH + '*', () => currentUser)
    await patch(remoteBackendPaths.UPDATE_ORGANIZATION_PATH + '*', async (route, request) => {
      // The type of the body sent by this app is statically known.
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.UpdateOrganizationRequestBody = await request.postDataJSON()
      if (body.name === '') {
        await route.fulfill({
          status: HTTP_STATUS_BAD_REQUEST,
          json: { message: 'Organization name must not be empty' },
        })
        return
      } else if (currentOrganization) {
        currentOrganization = { ...currentOrganization, ...body }
        return currentOrganization satisfies backend.OrganizationInfo
      } else {
        await route.fulfill({ status: HTTP_STATUS_NOT_FOUND })
        return
      }
    })
    await get(remoteBackendPaths.GET_ORGANIZATION_PATH + '*', async route => {
      await route.fulfill({
        json: currentOrganization,
        // eslint-disable-next-line @typescript-eslint/no-magic-numbers
        status: currentOrganization == null ? 404 : 200,
      })
    })
    await post(remoteBackendPaths.CREATE_TAG_PATH + '*', route => {
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.CreateTagRequestBody = route.request().postDataJSON()
      const json: backend.Label = {
        id: backend.TagId(`tag-${uniqueString.uniqueString()}`),
        value: backend.LabelName(body.value),
        color: body.color,
      }
      return json
    })
    await post(remoteBackendPaths.CREATE_PROJECT_PATH + '*', (_route, request) => {
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.CreateProjectRequestBody = request.postDataJSON()
      const title = body.projectName
      const id = backend.ProjectId(`project-${uniqueString.uniqueString()}`)
      const parentId =
        body.parentDirectoryId ?? backend.DirectoryId(`directory-${uniqueString.uniqueString()}`)
      const json: backend.CreatedProject = {
        name: title,
        organizationId: defaultOrganizationId,
        packageName: 'Project_root',
        projectId: id,
        state: { type: backend.ProjectState.opened, volumeId: '' },
      }
      addProject(title, {
        description: null,
        id,
        labels: [],
        modifiedAt: dateTime.toRfc3339(new Date()),
        parentId,
        permissions: [
          {
            user: {
              organizationId: defaultOrganizationId,
              userId: defaultUserId,
              name: defaultUsername,
              email: defaultEmail,
            },
            permission: permissions.PermissionAction.own,
          },
        ],
        projectState: json.state,
      })
      return json
    })
    await post(remoteBackendPaths.CREATE_DIRECTORY_PATH + '*', (_route, request) => {
      // eslint-disable-next-line @typescript-eslint/no-unsafe-assignment
      const body: backend.CreateDirectoryRequestBody = request.postDataJSON()
      const title = body.title
      const id = backend.DirectoryId(`directory-${uniqueString.uniqueString()}`)
      const parentId =
        body.parentId ?? backend.DirectoryId(`directory-${uniqueString.uniqueString()}`)
      const json: backend.CreatedDirectory = { title, id, parentId }
      addDirectory(title, {
        description: null,
        id,
        labels: [],
        modifiedAt: dateTime.toRfc3339(new Date()),
        parentId,
        permissions: [
          {
            user: {
              organizationId: defaultOrganizationId,
              userId: defaultUserId,
              name: defaultUsername,
              email: defaultEmail,
            },
            permission: permissions.PermissionAction.own,
          },
        ],
        projectState: null,
      })
      return json
    })

    await page.route('*', async route => {
      if (!isOnline) {
        await route.abort('connectionfailed')
      }
    })
  })

  const api = {
    defaultEmail,
    defaultName: defaultUsername,
    defaultOrganization,
    defaultOrganizationId,
    defaultOrganizationName,
    defaultUser,
    defaultUserId,
    rootDirectoryId: defaultDirectoryId,
    goOffline: () => {
      isOnline = false
    },
    goOnline: () => {
      isOnline = true
    },
    currentUser: () => currentUser,
    setCurrentUser: (user: backend.User | null) => {
      currentUser = user
    },
    currentPassword: () => currentPassword,
    currentProfilePicture: () => currentProfilePicture,
    currentOrganization: () => currentOrganization,
    setCurrentOrganization: (organization: backend.OrganizationInfo | null) => {
      currentOrganization = organization
    },
    currentOrganizationProfilePicture: () => currentOrganizationProfilePicture,
    addAsset,
    deleteAsset,
    undeleteAsset,
    createDirectory,
    createProject,
    createFile,
    createSecret,
    addDirectory,
    addProject,
    addFile,
    addSecret,
    createLabel,
    addLabel,
    setLabels,
    addUser,
    deleteUser,
    addUserGroup,
    deleteUserGroup,
    // TODO:
    // addPermission,
    // deletePermission,
    addUserGroupToUser,
    removeUserGroupFromUser,
  } as const

  if (setupAPI) {
    await setupAPI(api)
  }

  return api
}
