/** @file Switcher to choose the currently visible full-screen page. */
import * as React from 'react'

import * as reactQuery from '@tanstack/react-query'
import invariant from 'tiny-invariant'

import type * as text from 'enso-common/src/text'

import * as callbackHooks from '#/hooks/eventCallbackHooks'

import * as textProvider from '#/providers/TextProvider'

import * as dashboard from '#/pages/dashboard/Dashboard'

import * as aria from '#/components/aria'
import * as ariaComponents from '#/components/AriaComponents'
import StatelessSpinner, * as spinnerModule from '#/components/StatelessSpinner'
import FocusArea from '#/components/styled/FocusArea'
import SvgMask from '#/components/SvgMask'

import * as backend from '#/services/Backend'

import * as tailwindMerge from '#/utilities/tailwindMerge'

// =================
// === Constants ===
// =================

/** The corner radius of the tabs. */
const TAB_RADIUS_PX = 24

// =====================
// === TabBarContext ===
// =====================

const i = 0

/** Context for a {@link TabBarContext}. */
interface TabBarContextValue {
  readonly updateClipPath: (element: HTMLElement) => void
  readonly observeElement: (element: HTMLElement) => () => void
}

const TabBarContext = React.createContext<TabBarContextValue | null>(null)

/** Custom hook to get tab bar context. */
function useTabBarContext() {
  const context = React.useContext(TabBarContext)
  invariant(context, '`useTabBarContext` must be used inside a `<TabBar />`')
  return context
}

// ==============
// === TabBar ===
// ==============

/** Props for a {@link TabBar}. */
export interface TabBarProps extends Readonly<React.PropsWithChildren> {
  defaultSelectedKey?: string
  onSelectionChange?: (key: string) => void
  content: React.ReactNode
}

/** Switcher to choose the currently visible full-screen page. */
export default function TabBar(props: TabBarProps) {
  const { children, content, defaultSelectedKey = null, onSelectionChange } = props
  const cleanupResizeObserverRef = React.useRef(() => {})
  const tabsRef = React.useRef<HTMLDivElement | null>(null)
  const backgroundRef = React.useRef<HTMLDivElement | null>(null)
  const activeTabRef = React.useRef<string | null>(null)

  const [resizeObserver] = React.useState(
    () =>
      new ResizeObserver(() => {
        if (activeTabRef.current != null) {
          updateActiveTab(activeTabRef.current)
        }
      })
  )

  const updateClipPath = React.useCallback((element: HTMLElement | null) => {
    const backgroundElement = backgroundRef.current

    if (!backgroundElement || !element) {
      return
    }

    const bounds = element.getBoundingClientRect()
    const rootBounds = backgroundElement.getBoundingClientRect()
    const tabLeft = bounds.left - rootBounds.left
    const tabRight = bounds.right - rootBounds.left
    const segments = [
      'M 0 0',
      `L ${rootBounds.width} 0`,
      `L ${rootBounds.width} ${rootBounds.height}`,
      `L ${tabRight + TAB_RADIUS_PX} ${rootBounds.height}`,
      `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 1 ${tabRight} ${rootBounds.height - TAB_RADIUS_PX}`,
      `L ${tabRight} ${TAB_RADIUS_PX}`,
      `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 0 ${tabRight - TAB_RADIUS_PX} 0`,
      `L ${tabLeft + TAB_RADIUS_PX} 0`,
      `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 0 ${tabLeft} ${TAB_RADIUS_PX}`,
      `L ${tabLeft} ${rootBounds.height - TAB_RADIUS_PX}`,
      `A ${TAB_RADIUS_PX} ${TAB_RADIUS_PX} 0 0 1 ${tabLeft - TAB_RADIUS_PX} ${rootBounds.height}`,
      `L 0 ${rootBounds.height}`,
      'Z',
    ]

    backgroundElement.style.clipPath = `path("${segments.join(' ')}")`
  }, [])

  const updateResizeObserver = (element: HTMLElement | null) => {
    cleanupResizeObserverRef.current()

    if (element == null) {
      cleanupResizeObserverRef.current = () => {}
    } else {
      resizeObserver.observe(element)

      cleanupResizeObserverRef.current = () => {
        resizeObserver.unobserve(element)
      }
    }
  }

  /**
   *
   */
  function updateActiveTab(key: React.Key) {
    if (tabsRef.current) {
      const selectedTab = tabsRef.current.querySelector(`[data-key="${key}"]`)
      if (selectedTab && selectedTab instanceof HTMLElement) {
        updateClipPath(selectedTab)
      }
    }
  }

  React.useEffect(() => {
    console.log('defaultSelectedKey', defaultSelectedKey)
    if (defaultSelectedKey != null) {
      updateActiveTab(defaultSelectedKey)
    }
  }, [])

  return (
    <aria.Tabs
      ref={tabsRef}
      className="h-auto w-full"
      defaultSelectedKey={defaultSelectedKey}
      onSelectionChange={key => {
        activeTabRef.current = key
        updateActiveTab(key)
        onSelectionChange?.(key)
      }}
    >
      <TabBarContext.Provider
        value={{
          updateClipPath,
          observeElement: callbackHooks.useEventCallback(element => {
            resizeObserver.observe(element)
            return () => {
              resizeObserver.unobserve(element)
            }
          }),
        }}
      >
        <div className="relative flex w-full">
          <div
            ref={element => {
              backgroundRef.current = element
              updateResizeObserver(element)
            }}
            className="pointer-events-none absolute inset-0 bg-primary/5"
          />
          <TabList>{children}</TabList>
        </div>

        {content}
      </TabBarContext.Provider>
    </aria.Tabs>
  )
}

// ===============
// === TabList ===
// ===============

/** Props for a {@link TabList}. */
export interface TabListProps extends Readonly<React.PropsWithChildren> {}

/** A tab list in a {@link TabBar}. */
function TabList(props: TabListProps) {
  const { children } = props
  return (
    <FocusArea direction="horizontal">
      {innerProps => (
        <aria.TabList
          className="flex h-12 shrink-0 grow cursor-default items-center rounded-full"
          {...innerProps}
        >
          {children}
        </aria.TabList>
      )}
    </FocusArea>
  )
}

// ===========
// === Tab ===
// ===========

/** Props for a {@link Tab}. */
interface InternalTabProps extends Readonly<React.PropsWithChildren> {
  readonly id: string
  readonly project?: dashboard.Project
  readonly isActive: boolean
  readonly isHidden?: boolean
  readonly icon: string
  readonly labelId: text.TextId
  readonly onPress: () => void
  readonly onClose?: () => void
  readonly onLoadEnd?: () => void
}

/** A tab in a {@link TabBar}. */
export function Tab(props: InternalTabProps) {
  const {
    isActive,
    icon,
    labelId,
    children,
    onPress,
    onClose,
    project,
    onLoadEnd,
    id,
    isHidden = false,
  } = props
  const { observeElement } = useTabBarContext()
  const [ref, setRef] = React.useState<HTMLDivElement | null>(null)
  const isLoadingRef = React.useRef(true)

  const { getText } = textProvider.useText()

  React.useEffect(() => {
    if (ref) {
      console.log('observeElement', ref)
      return observeElement(ref)
    } else {
      return () => {}
    }
  }, [ref, observeElement])

  const { isLoading, data } = reactQuery.useQuery<backend.Project>(
    project?.id
      ? dashboard.createGetProjectDetailsQuery.createPassiveListener(project.id)
      : { queryKey: ['__IGNORE__'], queryFn: reactQuery.skipToken }
  )

  const isFetching =
    (isLoading || (data && data.state.type !== backend.ProjectState.opened)) ?? false

  React.useEffect(() => {
    if (!isFetching && isLoadingRef.current) {
      isLoadingRef.current = false
      onLoadEnd?.()
    }
  }, [isFetching, onLoadEnd])

  return (
    <aria.Tab
      ref={setRef}
      id={id}
      aria-label={getText(labelId)}
      className={tailwindMerge.twMerge(
        'group relative flex h-full items-center gap-3 rounded-t-2xl px-4',
        !isActive &&
          'cursor-pointer opacity-50 hover:bg-frame hover:opacity-75 disabled:cursor-not-allowed disabled:opacity-30 [&.disabled]:cursor-not-allowed [&.disabled]:opacity-30',
        isHidden && 'hidden'
      )}
    >
      {onClose && (
        <div className="mt-[1px] hidden h-4 w-4 items-center justify-center group-hover:flex focus-visible:flex">
          <ariaComponents.CloseButton onPress={onClose} />
        </div>
      )}
      {isLoading ? (
        <StatelessSpinner
          state={spinnerModule.SpinnerState.loadingMedium}
          size={16}
          className={tailwindMerge.twMerge(onClose && 'group-hover:hidden focus-visible:hidden')}
        />
      ) : (
        <SvgMask
          src={icon}
          className={tailwindMerge.twMerge(onClose && 'group-hover:hidden focus-visible:hidden')}
        />
      )}
      {children}
    </aria.Tab>
  )
}
