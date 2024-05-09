/** @file AJV instance configured for data links. */
import type * as ajv from 'ajv/dist/2020'
import Ajv from 'ajv/dist/2020'

import SCHEMA from '#/data/dataLinkSchema.json' assert { type: 'json' }

import * as error from '#/utilities/error'

// eslint-disable-next-line @typescript-eslint/naming-convention
export const AJV = new Ajv({ formats: { 'enso-secret': true, 'enso-file': true } })
AJV.addSchema(SCHEMA)

// This is a function, even though it does not contain function syntax.
// eslint-disable-next-line no-restricted-syntax
export const validateDataLink = error.assert<ajv.ValidateFunction>(() =>
  AJV.getSchema('#/$defs/DataLink')
)
