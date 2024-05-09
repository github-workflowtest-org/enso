import { isMacLike, useEvent } from '@/composables/events'
import { proxyRefs, ref } from 'vue'

export type KeyboardComposable = ReturnType<typeof useKeyboard>
export function useKeyboard() {
  const state = {
    alt: ref(false),
    shift: ref(false),
    meta: ref(false),
    ctrl: ref(false),
  }

  const updateState = (e: MouseEvent | KeyboardEvent) => {
    state.alt.value = e.altKey
    state.shift.value = e.shiftKey
    state.meta.value = e.metaKey
    state.ctrl.value = e.ctrlKey
    return false
  }
  useEvent(window, 'keydown', updateState, { capture: true })
  useEvent(window, 'keyup', updateState, { capture: true })
  useEvent(window, 'pointerenter', updateState, { capture: true })

  return proxyRefs({
    alt: state.alt,
    shift: state.shift,
    meta: state.meta,
    ctrl: state.ctrl,
    mod: isMacLike ? state.meta : state.ctrl,
  })
}
