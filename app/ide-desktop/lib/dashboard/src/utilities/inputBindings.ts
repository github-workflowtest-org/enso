/** @file Exports `defineKeybinds`, a function to define a namespace containing keyboard and mouse
 * shortcuts. */
import * as detect from 'enso-common/src/detect'

import * as eventModule from '#/utilities/event'
import * as newtype from '#/utilities/newtype'
import * as object from '#/utilities/object'
import * as string from '#/utilities/string'

// ================
// === Newtypes ===
// ================

/** A keyboard key obtained from `KeyboardEvent.key`. */
type KeyName = newtype.Newtype<string, 'keyboard key'>
// eslint-disable-next-line @typescript-eslint/no-redeclare, @typescript-eslint/naming-convention
const KeyName = newtype.newtypeConstructor<KeyName>()
/** A bitset of flags representing each keyboard modifier key. */
type ModifierFlags = newtype.Newtype<number, 'modifier flags'>
// eslint-disable-next-line @typescript-eslint/no-redeclare
const ModifierFlags = newtype.newtypeConstructor<ModifierFlags>()
/** A bitset of flags representing each mouse pointer. */
type PointerButtonFlags = newtype.Newtype<number, 'pointer button flags'>
// eslint-disable-next-line @typescript-eslint/no-redeclare
const PointerButtonFlags = newtype.newtypeConstructor<PointerButtonFlags>()

// =============
// === Types ===
// =============

/** All possible modifier keys. */
export type ModifierKey = keyof typeof RAW_MODIFIER_FLAG

/** The target of a {@link KeyboardEvent}, {@link MouseEvent}, or {@link PointerEvent}. */
export interface InputEventTarget<
  EventName extends string,
  Event extends
    | KeyboardEvent
    | MouseEvent
    | PointerEvent
    | React.KeyboardEvent
    | React.MouseEvent
    | React.PointerEvent,
> {
  readonly addEventListener: (eventName: EventName, handler: (event: Event) => void) => void
  readonly removeEventListener: (eventName: EventName, handler: (event: Event) => void) => void
}

/** An intermediate representation of a keybind, in which all segments have been tokenized but
 * before converting into either a {@link Keybind} or a {@link Mousebind}. */
interface ModifierStringDecomposition {
  readonly key: string
  readonly modifiers: Modifier[]
}

/** A keyboard shortcut. */
export interface Keybind {
  readonly type: 'keybind'
  readonly key: KeyName
  readonly modifierFlags: ModifierFlags
}

/** A mouse shortcut. */
export interface Mousebind {
  readonly type: 'mousebind'
  readonly key: PointerButtonFlags
  readonly modifierFlags: ModifierFlags
}

// ======================
// === Modifier flags ===
// ======================

/* eslint-disable @typescript-eslint/naming-convention */
const RAW_MODIFIER_FLAG = {
  Ctrl: ModifierFlags(1 << 0),
  Alt: ModifierFlags(1 << 1),
  Shift: ModifierFlags(1 << 2),
  Meta: ModifierFlags(1 << 3),
} as const

const MODIFIER_FLAG_NAME: Readonly<Record<Modifier, ModifierKey>> = {
  Mod: detect.isOnMacOS() ? 'Meta' : 'Ctrl',
  Ctrl: 'Ctrl',
  Alt: 'Alt',
  Shift: 'Shift',
  Meta: 'Meta',
}

const MODIFIER_FLAG: Readonly<Record<Modifier, ModifierFlags>> = {
  Mod: RAW_MODIFIER_FLAG[MODIFIER_FLAG_NAME.Mod],
  Ctrl: RAW_MODIFIER_FLAG[MODIFIER_FLAG_NAME.Ctrl],
  Alt: RAW_MODIFIER_FLAG[MODIFIER_FLAG_NAME.Alt],
  Shift: RAW_MODIFIER_FLAG[MODIFIER_FLAG_NAME.Shift],
  Meta: RAW_MODIFIER_FLAG[MODIFIER_FLAG_NAME.Meta],
}
/* eslint-enable @typescript-eslint/naming-convention */

/** A number representing the unique combination of modifier flags. */
function modifierFlagsForModifiers(modifiers: Modifier[]): ModifierFlags {
  let result = 0
  for (const modifier of modifiers) {
    result |= MODIFIER_FLAG[modifier]
  }
  return ModifierFlags(result)
}

/** The names of all {@link Modifier}s in this {@link ModifierFlags}, in the OS' preferred order. */
export function modifiersForModifierFlags(modifierFlags: ModifierFlags): Modifier[] {
  return ALL_MODIFIERS.filter(modifier => (MODIFIER_FLAG[modifier] & modifierFlags) !== 0)
}

/** Returns the raw modifier key equivalent of a modifier. */
export function toModifierKey(modifier: Modifier): ModifierKey {
  return MODIFIER_FLAG_NAME[modifier]
}

/** A comparison function that can be passed to {@link Array.sort}. */
export function compareModifiers(a: Modifier, b: Modifier): number {
  // This is INCORRECT, but SAFE when the modifiers are generated by the same system that is
  // currently reading them. This should always be the case unless a `Modifier` is saved in
  // `localStorage` and then synced to a browser on a different OS.
  // eslint-disable-next-line no-restricted-syntax
  return ALL_MODIFIERS.indexOf(a as never) - ALL_MODIFIERS.indexOf(b as never)
}

/** Any event that contains modifier keys. {@link KeyboardEvent}s and {@link MouseEvent}s fall into
 * this category. */
interface EventWithModifiers {
  readonly ctrlKey: boolean
  readonly altKey: boolean
  readonly shiftKey: boolean
  readonly metaKey: boolean
}

/** A number representing the unique combination of modifier flags for an event.. */
export function modifierFlagsForEvent(event: EventWithModifiers): ModifierFlags {
  // eslint-disable-next-line no-restricted-syntax
  return ModifierFlags(
    (event.ctrlKey ? RAW_MODIFIER_FLAG.Ctrl : 0) |
      (event.altKey ? RAW_MODIFIER_FLAG.Alt : 0) |
      (event.shiftKey ? RAW_MODIFIER_FLAG.Shift : 0) |
      (event.metaKey ? RAW_MODIFIER_FLAG.Meta : 0)
  )
}

/* eslint-disable @typescript-eslint/naming-convention */
/** These values MUST match the flags on `MouseEvent#button`.
 * See https://developer.mozilla.org/en-US/docs/Web/API/MouseEvent/buttons */
const POINTER_BUTTON_FLAG: Readonly<Record<Pointer, PointerButtonFlags>> = {
  PointerMain: PointerButtonFlags(1 << 0),
  PointerSecondary: PointerButtonFlags(1 << 1),
  PointerAux: PointerButtonFlags(1 << 2),
  PointerBack: PointerButtonFlags(1 << 3),
  PointerForward: PointerButtonFlags(1 << 4),
}
/* eslint-enable @typescript-eslint/naming-convention */

/** Return the equivalent {@link PointerButtonFlags} for the given mouse `button`. */
function buttonToPointerButtonFlags(button: number) {
  switch (button) {
    case 0: {
      return POINTER_BUTTON_FLAG.PointerMain
    }
    case 1: {
      return POINTER_BUTTON_FLAG.PointerAux
    }
    case 2: {
      return POINTER_BUTTON_FLAG.PointerSecondary
    }
    case 3: {
      return POINTER_BUTTON_FLAG.PointerBack
    }
    case 4: {
      return POINTER_BUTTON_FLAG.PointerForward
    }
    default: {
      return PointerButtonFlags(0)
    }
  }
}

// ==========================
// === Autocomplete types ===
// ==========================

const ALL_MODIFIERS = detect.isOnMacOS()
  ? (['Ctrl', 'Shift', 'Alt', 'Mod'] as const)
  : (['Mod', 'Shift', 'Alt', 'Meta'] as const)
/** All valid keyboard modifier keys. */
type Modifier = (typeof ALL_MODIFIERS)[number]
/** All valid keyboard modifier keys, normalized to lowercase for autocomplete purposes. */
type LowercaseModifier = Lowercase<Modifier>
const ALL_POINTERS = [
  'PointerMain',
  'PointerSecondary',
  'PointerAux',
  'PointerBack',
  'PointerForward',
] as const
/** All valid mouse pointer buttons. */
type Pointer = (typeof ALL_POINTERS)[number]
/** All valid mouse pointer buttons, normalized to lowercase for autocomplete purposes. */
type LowercasePointer = Lowercase<Pointer>
/** This list is non-exhaustive. It is intentionally limited to keys found on most keyboards. */
const ALL_KEYS = [
  'Escape',
  'Enter',
  'Backspace',
  'Delete',
  // The key labeled as `Delete` - `Backspace` on macOS, `Delete` on all other platforms.
  'OsDelete',
  'Tab',
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'Home',
  'End',
  'PageUp',
  'PageDown',
  'Insert',
  'Space',
  'A',
  'B',
  'C',
  'D',
  'E',
  'F',
  'G',
  'H',
  'I',
  'J',
  'K',
  'L',
  'M',
  'N',
  'O',
  'P',
  'Q',
  'R',
  'S',
  'T',
  'U',
  'V',
  'W',
  'X',
  'Y',
  'Z',
  '0',
  '1',
  '2',
  '3',
  '4',
  '5',
  '6',
  '7',
  '8',
  '9',
  '`',
  '-',
  '=',
  '~',
  '!',
  '@',
  '#',
  '$',
  '%',
  '^',
  '&',
  '*',
  '(',
  ')',
  '_',
  '+',
  '[',
  ']',
  '\\',
  '{',
  '}',
  '|',
  ';',
  "'",
  ':',
  '"',
  ',',
  '.',
  '/',
  '<',
  '>',
  '?',
  'F1',
  'F2',
  'F3',
  'F4',
  'F5',
  'F6',
  'F7',
  'F8',
  'F9',
  'F10',
  'F11',
  'F12',
] as const
/** Common keyboard keys. */
export type Key = (typeof ALL_KEYS)[number]
/** Common keyboard keys, normalized to lowercase for autocomplete purposes. */
type LowercaseKey = Lowercase<Key>
/** A segment of a keyboard shortcut. */
type KeybindSegment = Key | Modifier | Pointer
export const normalizedKeyboardSegmentLookup = Object.fromEntries<string>(
  [...ALL_MODIFIERS, ...ALL_POINTERS, ...ALL_KEYS].map(entry => [entry.toLowerCase(), entry])
)
normalizedKeyboardSegmentLookup[''] = '+'
normalizedKeyboardSegmentLookup['space'] = ' '
normalizedKeyboardSegmentLookup['osdelete'] = detect.isOnMacOS() ? 'Backspace' : 'Delete'
/** A mapping between the lowercased segment of a keyboard shortcut to its properly capitalized
 * normalized form. */
type NormalizeKeybindSegment = {
  [K in KeybindSegment as Lowercase<K>]: K
}
/** A segment suggestible by autocomplete. */
type SuggestedKeybindSegment = Key | Pointer | `${Modifier}+`
/** A helper type used to autocomplete and validate a single keyboard shortcut in the editor. */
type AutocompleteKeybind<T extends string, FoundKeyName extends string = never> = T extends '+'
  ? T
  : T extends `${infer First}+${infer Rest}`
    ? Lowercase<First> extends LowercaseModifier
      ? `${NormalizeKeybindSegment[Lowercase<First>] & string}+${AutocompleteKeybind<Rest>}`
      : Lowercase<First> extends LowercaseKey | LowercasePointer
        ? AutocompleteKeybind<Rest, NormalizeKeybindSegment[Lowercase<First>] & string>
        : `${Modifier}+${AutocompleteKeybind<Rest>}`
    : T extends ''
      ? SuggestedKeybindSegment
      : Lowercase<T> extends LowercaseKey | LowercasePointer
        ? NormalizeKeybindSegment[Lowercase<T>]
        : Lowercase<T> extends LowercaseModifier
          ? [FoundKeyName] extends [never]
            ? `${NormalizeKeybindSegment[Lowercase<T>] & string}+${SuggestedKeybindSegment}`
            : `${NormalizeKeybindSegment[Lowercase<T>] & string}+${FoundKeyName}`
          : [FoundKeyName] extends [never]
            ? SuggestedKeybindSegment
            : FoundKeyName

/** A helper type used to autocomplete and validate an array of keyboard shortcuts in the editor.
 */
type AutocompleteKeybinds<T extends readonly string[]> = {
  [K in keyof T]: AutocompleteKeybind<T[K]>
}

/** A list of keybinds, with metadata describing its purpose. */
export interface KeybindsWithMetadata {
  readonly name: string
  readonly bindings: readonly [] | readonly string[]
  readonly description?: string
  readonly icon?: string
  readonly color?: string
  /** Defaults to `true`. */
  readonly rebindable?: boolean
}

/** A helper type used to autocomplete and validate an array of keyboard shortcuts (and its
 * associated metadata) in the editor.
 *
 * This type SHOULD NOT be explicitly written - it is only exported to suppress TypeScript
 * errors. */
export interface AutocompleteKeybindsWithMetadata<T extends KeybindsWithMetadata> {
  readonly name: string
  readonly bindings: AutocompleteKeybinds<T['bindings']>
  readonly description?: string
  readonly icon?: string
  readonly color?: string
  /** Defaults to `true`. */
  readonly rebindable?: boolean
}

/** All the corresponding value for an arbitrary key of a {@link Keybinds}. */
type KeybindValue = KeybindsWithMetadata | readonly [] | readonly string[]

/** A helper type used to autocomplete and validate an object containing actions and their
 * corresponding keyboard shortcuts. */
// `never extends T ? Result : InferenceSource` is a trick to unify `T` with the actual type of the
// argument.
type Keybinds<T extends Record<keyof T, KeybindValue>> = never extends T
  ? {
      [K in keyof T]: T[K] extends readonly string[]
        ? AutocompleteKeybinds<T[K]>
        : T[K] extends KeybindsWithMetadata
          ? AutocompleteKeybindsWithMetadata<T[K]>
          : ['error...', T]
    }
  : T

const DEFINED_NAMESPACES = new Map<
  string,
  // This is SAFE, as the value is only being stored for bookkeeping purposes.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ReturnType<typeof defineBindingNamespace<Record<any, any>>>
>()

export const DEFAULT_HANDLER = Symbol('default handler')

/** Define key bindings for the given namespace.
 *
 * This function takes list of actions with default bindings, and returns an object which allows
 * making event handler which in turn may be added as an appropriate event listener. It may handle
 * both keyboard and mouse events.
 *
 * The event handler assigns functions to the corresponding action. The function may return false
 * if the event should be considered not handled (and thus propagated). Returning true or just
 * nothing from the function will cause propagation of event stop.
 * @param namespace - should be unique among other `defineKeybinds` calls.
 * @param originalBindings - an object defining actions and their key bindings. Each property name
 * is an action name, and the value is a list of default key bindings. See "Keybinds should be
 * parsed correctly" test for examples of valid strings.
 * @returns an object with defined `handler` function.
 * @example
 * Define bindings:
 * ```
 * const graphBindings = defineKeybinds('graph-editor', {
 *   undo: ['Mod+Z'],
 *   redo: ['Mod+Y', 'Mod+Shift+Z'],
 *   dragScene: ['PointerAux', 'Mod+PointerMain'],
 *   openComponentBrowser: ['Enter'],
 *   newNode: ['N'],
 * })
 * ```
 *
 * Then make a handler:
 * ```
 * const graphBindingsHandler = graphBindings.handler({
 *   undo() {
 *     projectStore.module?.undoManager.undo()
 *   },
 *   redo() {
 *     projectStore.module?.undoManager.redo()
 *   },
 *   openComponentBrowser() {
 *     if (keyboardBusy()) return false
 *     if (navigator.sceneMousePos != null && !componentBrowserVisible.value) {
 *       componentBrowserPosition.value = navigator.sceneMousePos
 *       componentBrowserVisible.value = true
 *     }
 *   },
 *   newNode() {
 *     if (keyboardBusy()) return false
 *     if (navigator.sceneMousePos != null) {
 *       graphStore.createNode(navigator.sceneMousePos, 'hello "world"! 123 + x')
 *     }
 *   },
 * })
 * ```
 *
 * And then pass the handler to the event listener:
 * ```
 * useEvent(window, 'keydown', graphBindingsHandler)
 * ```
 */
export function defineBindingNamespace<T extends Record<keyof T, KeybindValue>>(
  namespace: string,
  originalBindings: Keybinds<T>
) {
  /** The name of a binding in this set of keybinds. */
  type BindingKey = string & keyof T
  let keyboardShortcuts: Partial<Record<KeyName, Partial<Record<ModifierFlags, Set<BindingKey>>>>> =
    {}
  let mouseShortcuts: Partial<
    Record<PointerButtonFlags, Partial<Record<ModifierFlags, Set<BindingKey>>>>
  > = []

  const bindings = structuredClone(originalBindings)
  // This is SAFE, as it is a `readonly` upcast.
  const bindingsAsRecord =
    // eslint-disable-next-line no-restricted-syntax
    bindings as Readonly<Record<string, KeybindValue>>

  // This non-null assertion is SAFE, as it is immediately assigned by `rebuildMetadata()`.
  let metadata!: Record<BindingKey, KeybindsWithMetadata>
  const rebuildMetadata = () => {
    // This is SAFE, as this type is a direct mapping from `bindingsAsRecord`, which has `BindingKey`
    // as its keys.
    // eslint-disable-next-line no-restricted-syntax
    metadata = Object.fromEntries(
      Object.entries(bindingsAsRecord).map(kv => {
        const [name, info] = kv
        if (Array.isArray(info)) {
          return [
            name,
            { name: string.camelCaseToTitleCase(name), bindings: structuredClone(info) },
          ]
        } else {
          return [name, structuredClone(info)]
        }
      })
    ) as Record<BindingKey, KeybindsWithMetadata>
  }

  const rebuildLookups = () => {
    rebuildMetadata()
    keyboardShortcuts = {}
    mouseShortcuts = []
    for (const [nameRaw, value] of Object.entries(bindingsAsRecord)) {
      const keybindStrings = 'bindings' in value ? value.bindings : value
      // This is SAFE, as `Keybinds<T>` is a type derived from `T`.
      // eslint-disable-next-line no-restricted-syntax
      const name = nameRaw as BindingKey
      for (const keybindString of keybindStrings) {
        const keybind = parseKeybindString(keybindString)
        switch (keybind.type) {
          case 'keybind': {
            const shortcutsByKey = (keyboardShortcuts[keybind.key] ??= [])
            // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
            const shortcutsByModifier = (shortcutsByKey[keybind.modifierFlags] ??= new Set())
            shortcutsByModifier.add(name)
            break
          }
          case 'mousebind': {
            const shortcutsByKey = (mouseShortcuts[keybind.key] ??= [])
            const shortcutsByModifier = (shortcutsByKey[keybind.modifierFlags] ??= new Set())
            shortcutsByModifier.add(name)
            break
          }
        }
      }
    }
  }
  rebuildLookups()

  const handler = <
    Event extends
      | KeyboardEvent
      | MouseEvent
      | PointerEvent
      | React.KeyboardEvent
      | React.MouseEvent
      | React.PointerEvent,
  >(
    handlers: Partial<
      // This MUST be `void` to allow implicit returns.
      // eslint-disable-next-line @typescript-eslint/no-invalid-void-type
      Record<BindingKey | typeof DEFAULT_HANDLER, (event: Event) => boolean | void>
    >,
    stopAndPrevent = true
  ): ((event: Event, stopAndPrevent?: boolean) => boolean) => {
    return (event, innerStopAndPrevent = stopAndPrevent) => {
      const eventModifierFlags = modifierFlagsForEvent(event)
      const matchingBindings =
        'key' in event
          ? keyboardShortcuts[KeyName(event.key.toLowerCase())]?.[eventModifierFlags]
          : mouseShortcuts[
              event.buttons !== 0
                ? PointerButtonFlags(event.buttons)
                : buttonToPointerButtonFlags(event.button)
            ]?.[eventModifierFlags]
      let handle = handlers[DEFAULT_HANDLER]
      const isTextInputFocused = eventModule.isElementTextInput(document.activeElement)
      const isTextInputEvent = 'key' in event && eventModule.isTextInputEvent(event)
      const shouldIgnoreEvent = isTextInputFocused && isTextInputEvent
      if (matchingBindings != null && !shouldIgnoreEvent) {
        for (const bindingNameRaw in handlers) {
          // This is SAFE, because `handlers` is an object with identical keys to `T`,
          // which `BindingName` is also derived from.
          // eslint-disable-next-line no-restricted-syntax
          const bindingName = bindingNameRaw as BindingKey
          if (matchingBindings.has(bindingName)) {
            handle = handlers[bindingName]
            break
          }
        }
      }
      if (handle == null) {
        return false
      } else if (handle(event) === false) {
        return false
      } else {
        if (innerStopAndPrevent) {
          if ('stopImmediatePropagation' in event) {
            event.stopImmediatePropagation()
          } else {
            event.stopPropagation()
          }
          event.preventDefault()
        }
        return true
      }
    }
  }

  const attach = <
    EventName extends string,
    Event extends
      | KeyboardEvent
      | MouseEvent
      | PointerEvent
      | React.KeyboardEvent
      | React.MouseEvent
      | React.PointerEvent,
  >(
    target: InputEventTarget<EventName, Event>,
    eventName: EventName,
    handlers: Partial<
      // This MUST be `void` to allow implicit returns.
      // eslint-disable-next-line @typescript-eslint/no-invalid-void-type
      Record<BindingKey | typeof DEFAULT_HANDLER, (event: Event) => boolean | void>
    >,
    stopAndPrevent = true
  ) => {
    const newHandler = handler(handlers, stopAndPrevent)
    target.addEventListener(eventName, newHandler)
    return () => {
      target.removeEventListener(eventName, newHandler)
    }
  }

  const reset = (key: BindingKey) => {
    bindings[key] = structuredClone(originalBindings[key])
    rebuildLookups()
  }

  const deleteFunction = (key: BindingKey, binding: string) => {
    const bindingsOrInfo = bindingsAsRecord[key]
    const bindingsList =
      bindingsOrInfo != null && 'bindings' in bindingsOrInfo
        ? bindingsOrInfo.bindings
        : bindingsOrInfo
    if (bindingsList != null) {
      object.unsafeMutable(bindingsList).splice(bindingsList.indexOf(binding), 1)
      rebuildLookups()
    }
  }

  const add = (key: BindingKey, binding: string) => {
    const bindingsOrInfo = bindingsAsRecord[key]
    const bindingsList =
      bindingsOrInfo != null && 'bindings' in bindingsOrInfo
        ? bindingsOrInfo.bindings
        : bindingsOrInfo
    if (bindingsList != null) {
      object.unsafeMutable(bindingsList).push(binding)
      rebuildLookups()
    }
  }

  const result = {
    /** Return an event handler that handles a native keyboard, mouse or pointer event. */
    handler,
    /** Attach an event listener to an {@link EventTarget} and return a function to detach the
     * listener. */
    attach,
    /** Reset the entire list of bindings for a specific action to its default value. */
    reset,
    /** Delete one specific binding from the bindings for a specific action. */
    delete: deleteFunction,
    /** Add a new binding to the bindings for a specific action. */
    add,
    /** Metadata for every input binding. */
    get metadata() {
      return metadata
    },
    /** Add this namespace to the global lookup. */
    register: () => {
      if (DEFINED_NAMESPACES.has(namespace)) {
        // eslint-disable-next-line no-restricted-properties
        console.warn(
          `Overriding the keybind namespace '${namespace}', which has already been defined.`
        )
        // eslint-disable-next-line no-restricted-properties
        console.trace()
      }
      DEFINED_NAMESPACES.set(namespace, result)
    },
    /** Remove this namespace from the global lookup. */
    unregister: () => {
      const cached = DEFINED_NAMESPACES.get(namespace)
      if (cached !== result) {
        return false
      } else {
        DEFINED_NAMESPACES.delete(namespace)
        return true
      }
    },
  } as const
  return result
}

/** A function to define a bindings object that can be passed to {@link defineBindingNamespace}.
 * Useful when wanting to create reusable keybind definitions, or non-global keybind definitions. */
export function defineBindings<T extends Record<keyof T, KeybindValue>>(bindings: Keybinds<T>) {
  return bindings
}

/** A type predicate that narrows the potential child of the array. */
function includesPredicate<T extends U, U>(array: readonly T[]) {
  const wideArray: readonly unknown[] = array
  return (element: unknown): element is T => wideArray.includes(element)
}

// This is a function, even though it does not contain function syntax.
// eslint-disable-next-line no-restricted-syntax
const isModifier = includesPredicate(ALL_MODIFIERS)
// This is a function, even though it does not contain function syntax.
// eslint-disable-next-line no-restricted-syntax
const isPointer = includesPredicate(ALL_POINTERS)

/** Convert a keybind string to an intermediate form containing both the key and its modifiers
 * (if any).
 *
 * Although this is exported, it should ONLY be used for testing, as it is an implementation
 * detail. */
export function decomposeKeybindString(keybindString: string): ModifierStringDecomposition {
  const trimmed = keybindString.trim()
  const parts =
    trimmed === ''
      ? []
      : trimmed
          .split(/[\s+]+/)
          .map(part => normalizedKeyboardSegmentLookup[part.trim().toLowerCase()] ?? part)
  const modifiers = parts.filter(isModifier)
  const key = parts.find(part => !isModifier(part))
  return { key: key ?? '', modifiers }
}

/** Parse a keybind string into a {@link Mousebind} if the key name describes a mouse button,
 * otherwise parse it into a {@link Keybind}. */
export function parseKeybindString(keybindS: string): Keybind | Mousebind {
  const decomposed = decomposeKeybindString(keybindS)
  if (isPointer(decomposed.key)) {
    return {
      type: 'mousebind',
      key: POINTER_BUTTON_FLAG[decomposed.key],
      modifierFlags: modifierFlagsForModifiers(decomposed.modifiers),
    }
  } else {
    return {
      type: 'keybind',
      key: KeyName(decomposed.key.toLowerCase()),
      modifierFlags: modifierFlagsForModifiers(decomposed.modifiers),
    }
  }
}
