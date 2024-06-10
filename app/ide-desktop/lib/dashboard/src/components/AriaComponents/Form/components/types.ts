/**
 * @file
 * Types for the Form component.
 */
import type * as React from 'react'

import type * as reactHookForm from 'react-hook-form'
import type * as z from 'zod'

import type * as aria from '#/components/aria'

/**
 * Field values type.
 */
// eslint-disable-next-line no-restricted-syntax
export type FieldValues<Schema extends TSchema | undefined> =
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  Schema extends z.ZodObject<any> ? z.infer<Schema> : reactHookForm.FieldValues

/**
 * Field path type.
 * @alias reactHookForm.FieldPath
 */
export type FieldPath<
  Schema extends TSchema,
  TFieldValues extends FieldValues<Schema>,
> = reactHookForm.FieldPath<TFieldValues>

/**
 * Schema type
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type TSchema = z.ZodObject<any>

/**
 * Props for the useForm hook.
 */
export interface UseFormProps<Schema extends TSchema, TFieldValues extends FieldValues<Schema>>
  extends Omit<
    reactHookForm.UseFormProps<TFieldValues>,
    'handleSubmit' | 'resetOptions' | 'resolver'
  > {
  readonly schema: Schema
}

/**
 * Return type of the useForm hook.
 * @alias reactHookForm.UseFormReturn
 */
export type UseFormReturn<
  Schema extends TSchema,
  TFieldValues extends FieldValues<Schema>,
  // eslint-disable-next-line no-restricted-syntax
  TTransformedValues extends Record<string, unknown> | undefined = undefined,
> = reactHookForm.UseFormReturn<TFieldValues, unknown, TTransformedValues>

/**
 * Form state type.
 * @alias reactHookForm.FormState
 */
export type FormState<
  Schema extends TSchema,
  TFieldValues extends FieldValues<Schema>,
> = reactHookForm.FormState<TFieldValues>

/**
 * Form instance type
 * @alias UseFormReturn
 */
export type FormInstance<
  Schema extends TSchema,
  TFieldValues extends FieldValues<Schema>,
  // eslint-disable-next-line no-restricted-syntax
  TTransformedValues extends Record<string, unknown> | undefined = undefined,
> = UseFormReturn<Schema, TFieldValues, TTransformedValues>

/**
 * Form type interface that check if FieldValues type is compatible with the value type from component
 */
export interface FormWithValueValidation<
  BaseValueType,
  Schema extends TSchema,
  TFieldValues extends FieldValues<Schema>,
  TFieldName extends FieldPath<Schema, TFieldValues>,
  // eslint-disable-next-line no-restricted-syntax
  TTransformedValues extends FieldValues<Schema> | undefined = undefined,
> {
  readonly form?:
    | (BaseValueType extends TFieldValues[TFieldName]
        ? FormInstance<Schema, TFieldValues, TTransformedValues>
        : 'Type mismatch: Field with this name has a different type than the value of the component.')
    // eslint-disable-next-line no-restricted-syntax
    | undefined
}

/**
 * Props for the Field component.
 */
export interface FieldProps extends aria.AriaLabelingProps {
  readonly isRequired?: boolean
  readonly label?: React.ReactNode
  readonly description?: React.ReactNode
  readonly error?: React.ReactNode
}
