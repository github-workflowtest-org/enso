/** @file A LocalStorage data manager. */
import type * as z from 'zod'

import * as common from 'enso-common'

import * as object from '#/utilities/object'

// ====================
// === LocalStorage ===
// ====================

/** Metadata describing runtime behavior associated with a {@link LocalStorageKey}. */
export type LocalStorageKeyMetadata<K extends LocalStorageKey> =
  | LocalStorageKeyMetadataWithParseFunction<K>
  | LocalStorageKeyMetadataWithSchema<K>

/**
 * A {@link LocalStorageKeyMetadata} with a `tryParse` function.
 */
interface LocalStorageKeyMetadataWithParseFunction<K extends LocalStorageKey> {
  readonly isUserSpecific?: boolean
  /**
   * A function to parse a value from the stored data.
   * If this is provided, the value will be parsed using this function.
   * If this is not provided, the value will be parsed using the `schema`.
   */
  readonly tryParse: (value: unknown) => LocalStorageData[K] | null
  readonly schema?: never
}

/**
 * A {@link LocalStorageKeyMetadata} with a `schema`.
 */
interface LocalStorageKeyMetadataWithSchema<K extends LocalStorageKey> {
  readonly isUserSpecific?: boolean
  /**
   * The Zod schema to validate the value.
   * If this is provided, the value will be parsed using this schema.
   * If this is not provided, the value will be parsed using the `tryParse` function.
   */
  readonly schema: z.ZodType<LocalStorageData[K]>
  readonly tryParse?: never
}

/** The data that can be stored in a {@link LocalStorage}.
 * Declaration merge into this interface to add a new key. */
export interface LocalStorageData {}

/** All possible keys of a {@link LocalStorage}. */
type LocalStorageKey = keyof LocalStorageData

/** A LocalStorage data manager. */
export default class LocalStorage {
  // This is UNSAFE. It is assumed that `LocalStorage.register` is always called
  // when `LocalStorageData` is declaration merged into.
  // eslint-disable-next-line no-restricted-syntax
  static keyMetadata = {} as Record<LocalStorageKey, LocalStorageKeyMetadata<LocalStorageKey>>
  localStorageKey = common.PRODUCT_NAME.toLowerCase()
  protected values: Partial<LocalStorageData>

  /** Create a {@link LocalStorage}. */
  constructor(private readonly triggerRerender: () => void) {
    const savedValues: unknown = JSON.parse(localStorage.getItem(this.localStorageKey) ?? '{}')
    const newValues: Partial<Record<LocalStorageKey, LocalStorageData[LocalStorageKey]>> = {}
    if (typeof savedValues === 'object' && savedValues != null) {
      for (const [key, metadata] of object.unsafeEntries(LocalStorage.keyMetadata)) {
        if (key in savedValues) {
          // This is SAFE, as it is guarded by the `key in savedValues` check.
          // eslint-disable-next-line @typescript-eslint/no-unsafe-member-access, no-restricted-syntax, @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment
          const savedValue = (savedValues as any)[key]
          const value = metadata.schema
            ? metadata.schema.safeParse(savedValue).data
            : metadata.tryParse(savedValue)
          if (value != null) {
            newValues[key] = value
          }
        }
      }
    }
    // This is SAFE, as the `tryParse` function is required by definition to return a value of the
    // correct type for each corresponding key.
    // eslint-disable-next-line no-restricted-syntax, @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment
    this.values = newValues as any
  }

  /** Register runtime behavior associated with a {@link LocalStorageKey}. */
  static registerKey<K extends LocalStorageKey>(key: K, metadata: LocalStorageKeyMetadata<K>) {
    LocalStorage.keyMetadata[key] = metadata
  }

  /** Retrieve an entry from the stored data. */
  get<K extends LocalStorageKey>(key: K) {
    return this.values[key]
  }

  /** Write an entry to the stored data, and save. */
  set<K extends LocalStorageKey>(key: K, value: LocalStorageData[K]) {
    this.values[key] = value
    this.save()
  }

  /** Delete an entry from the stored data, and save. */
  delete<K extends LocalStorageKey>(key: K) {
    const oldValue = this.values[key]
    // The key being deleted is one of a statically known set of keys.
    // eslint-disable-next-line @typescript-eslint/no-dynamic-delete
    delete this.values[key]
    this.save()
    return oldValue
  }

  /** Delete user-specific entries from the stored data, and save. */
  clearUserSpecificEntries() {
    for (const [key, metadata] of object.unsafeEntries(LocalStorage.keyMetadata)) {
      if (metadata.isUserSpecific === true) {
        this.delete(key)
      }
    }
  }

  /** Save the current value of the stored data.. */
  protected save() {
    localStorage.setItem(this.localStorageKey, JSON.stringify(this.values))
    this.triggerRerender()
  }
}
