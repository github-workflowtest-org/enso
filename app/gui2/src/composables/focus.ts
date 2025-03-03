import { syncRef, useFocus, type MaybeElement } from '@vueuse/core'
import { effectScope, nextTick, ref, watch, type EffectScope, type WatchSource } from 'vue'

/** Maintain bidirectional synchronization between an element's focus state and a model value, which is returned.
 *
 * This is similar to `syncRef(model, useFocus(element).focused)`, but correctly handles
 * the `element` being updated immediately before it is made visible in the DOM by delaying
 * any attempt to focus the new element by one tick.
 */
export function useFocusDelayed(element: WatchSource<MaybeElement>) {
  const focused = ref(false)
  let focusScope: EffectScope | undefined = undefined
  watch(element, (element) => {
    focusScope?.stop()
    if (element) {
      const scope = effectScope()
      focusScope = scope
      nextTick(() =>
        scope.run(() => {
          const elementFocused = useFocus(element, { initialValue: focused.value }).focused
          syncRef(focused, elementFocused, { immediate: false })
        }),
      ).catch()
    }
  })
  return { focused }
}
