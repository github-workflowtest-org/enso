/** @file Vue composables for listening to DOM events. */

import type { Opt } from '@/util/data/opt'
import { Vec2 } from '@/util/data/vec2'
import { type VueInstance } from '@vueuse/core'
import {
  computed,
  onScopeDispose,
  proxyRefs,
  ref,
  shallowRef,
  toValue,
  watch,
  watchEffect,
  type Ref,
  type ShallowRef,
  type WatchSource,
} from 'vue'

export function isTriggeredByKeyboard(e: MouseEvent | PointerEvent) {
  if (e instanceof PointerEvent) return e.pointerType !== 'mouse'
  else return false
}

/**
 * Add an event listener for the duration of the component's lifetime.
 * @param target element on which to register the event
 * @param event name of event to register
 * @param handler event handler
 */
export function useEvent<K extends keyof DocumentEventMap>(
  target: Document,
  event: K,
  handler: (e: DocumentEventMap[K]) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEvent<K extends keyof WindowEventMap>(
  target: Window,
  event: K,
  handler: (e: WindowEventMap[K]) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEvent<K extends keyof ElementEventMap>(
  target: Element,
  event: K,
  handler: (event: ElementEventMap[K]) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEvent(
  target: EventTarget,
  event: string,
  handler: EventListenerOrEventListenerObject,
  options?: boolean | AddEventListenerOptions,
): void
export function useEvent(
  target: EventTarget,
  event: string,
  handler: EventListenerOrEventListenerObject,
  options?: boolean | AddEventListenerOptions,
): void {
  target.addEventListener(event, handler, options)
  onScopeDispose(() => target.removeEventListener(event, handler, options))
}

/**
 * Add an event listener for the duration of condition being true.
 * @param target element on which to register the event
 * @param condition the condition that determines if event is bound
 * @param event name of event to register
 * @param handler event handler
 */
export function useEventConditional<K extends keyof DocumentEventMap>(
  target: Document,
  event: K,
  condition: WatchSource<boolean>,
  handler: (e: DocumentEventMap[K]) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEventConditional<K extends keyof WindowEventMap>(
  target: Window,
  event: K,
  condition: WatchSource<boolean>,
  handler: (e: WindowEventMap[K]) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEventConditional<K extends keyof ElementEventMap>(
  target: Element,
  event: K,
  condition: WatchSource<boolean>,
  handler: (event: ElementEventMap[K]) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEventConditional(
  target: EventTarget,
  event: string,
  condition: WatchSource<boolean>,
  handler: (event: unknown) => void,
  options?: boolean | AddEventListenerOptions,
): void
export function useEventConditional(
  target: EventTarget,
  event: string,
  condition: WatchSource<boolean>,
  handler: (event: unknown) => void,
  options?: boolean | AddEventListenerOptions,
): void {
  watch(condition, (conditionMet, _, onCleanup) => {
    if (conditionMet) {
      target.addEventListener(event, handler, options)
      onCleanup(() => target.removeEventListener(event, handler, options))
    }
  })
}

/** Whether any element currently has keyboard focus. */
export function keyboardBusy() {
  return document.activeElement != document.body
}

/** Whether focused element is within given element's subtree. */
export function focusIsIn(el: Element | undefined | null) {
  return el && el.contains(document.activeElement)
}

/**
 * Whether any element currently has keyboard focus, except for elements within given subtree.
 * When `el` is `null` or `undefined`, the function behaves as `keyboardBusy()`.
 */
export function keyboardBusyExceptIn(el: Opt<Element>) {
  return keyboardBusy() && (el == null || !focusIsIn(el))
}

const hasWindow = typeof window !== 'undefined'
const platform = hasWindow ? window.navigator?.platform ?? '' : ''
export const isMacLike = /(Mac|iPhone|iPod|iPad)/i.test(platform)

export function modKey(e: KeyboardEvent | MouseEvent): boolean {
  return isMacLike ? e.metaKey : e.ctrlKey
}

/** A helper for getting Element out of VueInstance, it allows using `useResizeObserver` with Vue components. */
export function unrefElement(
  element: Ref<Element | undefined | null | VueInstance>,
): Element | undefined | null {
  const plain = toValue(element)
  return (plain as VueInstance)?.$el ?? plain
}

interface ResizeObserverData {
  refCount: number
  boundRectUsers: number
  contentRect: ShallowRef<Vec2>
  boundRect: ShallowRef<Vec2>
}

const resizeObserverData = new WeakMap<Element, ResizeObserverData>()
function getOrCreateObserverData(element: Element): ResizeObserverData {
  const existingData = resizeObserverData.get(element)
  if (existingData) return existingData
  const data: ResizeObserverData = {
    refCount: 0,
    boundRectUsers: 0,
    contentRect: shallowRef<Vec2>(Vec2.Zero),
    boundRect: shallowRef<Vec2>(Vec2.Zero),
  }
  resizeObserverData.set(element, data)
  return data
}

const sharedResizeObserver: ResizeObserver | undefined =
  typeof ResizeObserver === 'undefined' ? undefined : (
    new ResizeObserver((entries) => {
      for (const entry of entries) {
        const data = resizeObserverData.get(entry.target)
        if (data != null) {
          if (entry.contentRect != null) {
            data.contentRect.value = new Vec2(entry.contentRect.width, entry.contentRect.height)
          }
          if (data.boundRectUsers > 0) {
            const rect = entry.target.getBoundingClientRect()
            data.boundRect.value = new Vec2(rect.width, rect.height)
          }
        }
      }
    })
  )

/**
 * Get DOM node size and keep it up to date.
 *
 * # Warning:
 * Updating DOM node layout based on values derived from their size can introduce unwanted feedback
 * loops across the script and layout reflow. Avoid doing that.
 *
 * @param elementRef DOM node to observe.
 * @returns Reactive value with the DOM node size.
 */
export function useResizeObserver(
  elementRef: Ref<Element | undefined | null | VueInstance>,
  useContentRect = true,
): Ref<Vec2> {
  if (!sharedResizeObserver) {
    const sizeRef = shallowRef<Vec2>(Vec2.Zero)
    // Fallback implementation for browsers/test environment that do not support ResizeObserver:
    // Grab the size of the element every time the ref is assigned, or when the page is resized.
    function refreshSize() {
      const element = unrefElement(elementRef)
      if (element != null) {
        const rect = element.getBoundingClientRect()
        sizeRef.value = new Vec2(rect.width, rect.height)
      }
    }
    watchEffect(refreshSize)
    useEvent(window, 'resize', refreshSize)
    return sizeRef
  }
  const observer = sharedResizeObserver
  watchEffect((onCleanup) => {
    const element = unrefElement(elementRef)
    if (element != null) {
      const data = getOrCreateObserverData(element)
      if (data.refCount === 0) observer.observe(element)
      data.refCount += 1
      if (!useContentRect) {
        if (data.boundRectUsers === 0) {
          const rect = element.getBoundingClientRect()
          data.boundRect.value = new Vec2(rect.width, rect.height)
        }
        data.boundRectUsers += 1
      }
      onCleanup(() => {
        if (elementRef.value != null) {
          data.refCount -= 1
          if (!useContentRect) data.boundRectUsers -= 1
          if (data.refCount === 0) observer.unobserve(element)
        }
      })
    }
  })

  return computed(() => {
    const element = unrefElement(elementRef)
    if (element == null) return Vec2.Zero
    const data = getOrCreateObserverData(element)
    return useContentRect ? data.contentRect.value : data.boundRect.value
  })
}

export interface EventPosition {
  /** The event position at the initialization of the drag. */
  initial: Vec2
  /** Absolute event position, equivalent to clientX/Y. */
  absolute: Vec2
  /** Event position relative to the initial position. Total movement of the drag so far. */
  relative: Vec2
  /** Difference of the event position since last event. */
  delta: Vec2
}

type PointerEventType = 'start' | 'move' | 'stop'

/**
 * A mask of all available pointer buttons. The values are compatible with DOM's `PointerEvent.buttons` value. The mask values
 * can be ORed together to create a mask of multiple buttons.
 */
export const enum PointerButtonMask {
  /** No buttons are pressed. */
  Empty = 0,
  /** Main mouse button, usually left. */
  Main = 1,
  /** Secondary mouse button, usually right. */
  Secondary = 2,
  /** Auxiliary mouse button, usually middle or wheel press. */
  Auxiliary = 4,
  /** Additional fourth mouse button, usually assigned to "browser back" action. */
  ExtBack = 8,
  /** Additional fifth mouse button, usually assigned to "browser forward" action. */
  ExtForward = 16,
}

/**
 * Register for a pointer dragging events.
 *
 * @param handler callback on any pointer event.
 * If `false` is returned from the callback, `preventDefault` will NOT be called for the event.
 * @param requiredButtonMask declare which buttons to look for. The value represents a `PointerEvent.buttons` mask.
 * @returns
 */
export function usePointer(
  handler: (pos: EventPosition, event: PointerEvent, eventType: PointerEventType) => void | boolean,
  requiredButtonMask: number = PointerButtonMask.Main,
  predicate?: (e: PointerEvent) => boolean,
) {
  const trackedPointer: Ref<number | null> = ref(null)
  let trackedElement: (Element & GlobalEventHandlers) | null = null
  let initialGrabPos: Vec2 | null = null
  let lastPos: Vec2 | null = null

  const dragging = computed(() => trackedPointer.value != null)

  function doStop(e: PointerEvent) {
    if (trackedPointer.value != null) {
      trackedElement?.releasePointerCapture(trackedPointer.value)
    }

    if (trackedElement != null && initialGrabPos != null && lastPos != null) {
      if (handler(computePosition(e, initialGrabPos, lastPos), e, 'stop') !== false) {
        e.stopImmediatePropagation()
      }

      lastPos = null
      trackedElement = null
    }
    trackedPointer.value = null
  }

  function doMove(e: PointerEvent) {
    if (trackedElement != null && initialGrabPos != null && lastPos != null) {
      if (handler(computePosition(e, initialGrabPos, lastPos), e, 'move') !== false) {
        e.stopImmediatePropagation()
      }
      lastPos = new Vec2(e.clientX, e.clientY)
    }
  }

  const events = {
    pointerdown(e: PointerEvent) {
      // pointers should not respond to unmasked mouse buttons
      if ((e.buttons & requiredButtonMask) === 0 || (predicate && !predicate(e))) {
        return
      }

      if (trackedPointer.value == null && e.currentTarget instanceof Element) {
        trackedPointer.value = e.pointerId
        // This is mostly SAFE, as virtually all `Element`s also extend `GlobalEventHandlers`.
        trackedElement = e.currentTarget as Element & GlobalEventHandlers
        // `setPointerCapture` is not defined in tests.
        trackedElement.setPointerCapture?.(e.pointerId)
        initialGrabPos = new Vec2(e.clientX, e.clientY)
        lastPos = initialGrabPos
        if (handler(computePosition(e, initialGrabPos, lastPos), e, 'start') !== false) {
          e.stopImmediatePropagation()
        }
      }
    },
    pointerup(e: PointerEvent) {
      if (trackedPointer.value !== e.pointerId) {
        return
      }
      doStop(e)
    },
    pointermove(e: PointerEvent) {
      if (trackedPointer.value !== e.pointerId) {
        return
      }
      // handle release of all masked buttons as stop
      if ((e.buttons & requiredButtonMask) !== 0) {
        doMove(e)
      } else {
        doStop(e)
      }
    },
  }

  return proxyRefs({
    events,
    dragging,
  })
}

function computePosition(event: PointerEvent, initial: Vec2, last: Vec2): EventPosition {
  return {
    initial,
    absolute: new Vec2(event.clientX, event.clientY),
    relative: new Vec2(event.clientX - initial.x, event.clientY - initial.y),
    delta: new Vec2(event.clientX - last.x, event.clientY - last.y),
  }
}
