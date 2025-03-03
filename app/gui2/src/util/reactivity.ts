/** @file Functions for manipulating Vue reactive objects. */

import { defaultEquality } from '@/util/equals'
import { debouncedWatch } from '@vueuse/core'
import { nop } from 'lib0/function'
import type { ComputedRef, MaybeRefOrGetter, Ref, WatchSource, WritableComputedRef } from 'vue'
import {
  callWithErrorHandling,
  computed,
  effect,
  effectScope,
  isRef,
  queuePostFlushCb,
  shallowRef,
  toValue,
  watch,
} from 'vue'

/** Cast watch source to an observable ref. */
export function watchSourceToRef<T>(src: WatchSource<T>): Ref<T> {
  return isRef(src) ? src : computed(src)
}

/** Get the value behind a watch source at the current time. */
export function evalWatchSource<T>(src: WatchSource<T>): T {
  return isRef(src) ? src.value : src()
}

export type OnCleanup = (fn: () => void) => void
export type StopEffect = () => void

/**
 * A set of effects that will defer their re-execution until an explicit flush. This is useful for
 * implementing incremental updates to an external data structure, while delaying the update logic
 * until next time that data structure is queried.
 */
export class LazySyncEffectSet {
  _dirtyRunners = new Set<() => void>()
  _scope = effectScope()
  _boundFlush = this.flush.bind(this)

  /**
   * Add an effect to the lazy set. The effect will run once immediately, and any subsequent runs
   * will be delayed until the next flush. Only effects that were notified about a dependency change
   * will be re-run on flush.
   *
   * Returns a function that can be used to manually stop the effect.
   */
  lazyEffect(fn: (onCleanup: OnCleanup) => void): StopEffect {
    return (
      this._scope.run(() => {
        let cleanup: (() => void) | null = null
        const callCleanup = () => {
          if (cleanup != null) {
            const tmpCleanup = cleanup
            cleanup = null
            callWithErrorHandling(tmpCleanup, null, 4 /* ErrorCodes.WATCH_CLEANUP */)
          }
        }
        function onCleanup(fn: () => void) {
          cleanup = fn
        }

        const runner = effect(
          () => {
            callCleanup()
            fn(onCleanup)
          },
          {
            lazy: true,
            scheduler: () => {
              if (this._dirtyRunners.size === 0) queuePostFlushCb(this._boundFlush)
              this._dirtyRunners.add(runner)
            },
            onStop: () => {
              this._dirtyRunners.delete(runner)
              callCleanup()
            },
          },
        )
        runner.effect.scheduler?.()
        return () => runner.effect.stop()
      }) ?? nop
    )
  }

  /**
   * Run all effects that are currently dirty. In case any effect causes other effects to become
   * dirty, they will be rerun during the same flush.
   */
  flush() {
    while (this._dirtyRunners.size !== 0) {
      const runners = [...this._dirtyRunners]
      this._dirtyRunners.clear()
      for (let i = 0; i < runners.length; ++i) runners[i]!()
    }
  }

  // Immediately stops all effects and clears the dirty set.
  stop() {
    this._scope.stop()
  }
}

/**
 * Create a ref that is updated whenever the given function's return value changes. Similar to
 * `computed`, but differs in significant ways:
 * - The dependencies of the `getter` will not be propagated to any effects that access the result.
 * - The `getter` will run even if the ref is not actively observed by any effects (i.e. it is
 * effectively an **always-active watcher**),
 * - The `ref` will only be updated only when the derived value actually changed (as determined by
 *   the `equalFn` equality test).
 *
 * This is most useful for derived values that are accessed often and recompute frequently, but the
 * actual derived result changes very rarely. When in doubt, use `computed` instead.
 */
export function cachedGetter<T>(
  getter: () => T,
  equalFn: (a: T, b: T) => boolean = defaultEquality,
): Ref<T> {
  const valueRef = shallowRef<T>(getter())
  watch(
    getter,
    (newValue) => {
      const oldValue = valueRef.value
      if (!equalFn(oldValue, newValue)) valueRef.value = newValue
    },
    { flush: 'sync' },
  )

  return valueRef
}

/**
 * Same as `cachedGetter`, except that any changes will be not applied immediately, but only after
 * the timer set for `debounce` milliseconds will expire. If any further update arrives in that
 * time, the timer is restarted.
 */
export function debouncedGetter<T>(
  getter: WatchSource<T>,
  debounce: number,
  equalFn: (a: T, b: T) => boolean = defaultEquality,
): Ref<T> {
  const valueRef = shallowRef<T>(toValue(getter))
  debouncedWatch(
    getter,
    (newValue) => {
      const oldValue = valueRef.value
      if (!equalFn(oldValue, newValue)) valueRef.value = newValue
    },
    { debounce },
  )
  return valueRef
}

/** Update `target` to have the same entries as `newState`. */
export function syncSet<T>(target: Set<T>, newState: Set<T>) {
  for (const oldKey of target) if (!newState.has(oldKey)) target.delete(oldKey)
  for (const newKey of newState) if (!target.has(newKey)) target.add(newKey)
}

/** Type of the parameter of `toValue`. */
export type ToValue<T> = MaybeRefOrGetter<T> | ComputedRef<T>

/**
 * A writable proxy computed value that reads a fallback value in case the base is `undefined`.
 * Useful for cases where we have a user-overridable behavior with a computed default.
 */
export function computedFallback<T>(
  base: Ref<T | undefined>,
  fallback: () => T,
): WritableComputedRef<T> {
  return computed({
    get: () => base.value ?? fallback(),
    set: (val: T) => (base.value = val),
  })
}

/** Given a "raw" getter and setter, returns a writable-computed that buffers `set` operations.
 *
 * When the setter of the returned ref is invoked, the raw setter will be called during the next callback flush if and
 * only if the most recently set value does not compare strictly-equal to the current value (read from the raw getter).
 *
 * The getter of the returned ref immediately reflects the value of any pending write.
 */
export function useBufferedWritable<T>(raw: {
  get: ToValue<T>
  set: (value: T) => void
}): WritableComputedRef<T> {
  const pendingWrite = shallowRef<{ pending: T }>()
  watch(pendingWrite, () => {
    if (pendingWrite.value) {
      if (pendingWrite.value.pending !== toValue(raw.get)) {
        raw.set(pendingWrite.value.pending)
      }
      pendingWrite.value = undefined
    }
  })
  return computed({
    get: () => (pendingWrite.value ? pendingWrite.value.pending : toValue(raw.get)),
    set: (value: T) => (pendingWrite.value = { pending: value }),
  })
}
