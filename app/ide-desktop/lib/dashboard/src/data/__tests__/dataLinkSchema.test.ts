/** @file Tests ensuring consistency of example data-link files with the schema. */

import * as fs from 'node:fs'
import * as path from 'node:path'
import * as url from 'node:url'

import * as v from 'vitest'

import * as dataLinkValidator from '#/data/dataLinkValidator'

v.test('correctly rejects invalid values as not matching the schema', () => {
  v.expect(dataLinkValidator.validateDataLink({})).toBe(false)
  v.expect(dataLinkValidator.validateDataLink('foobar')).toBe(false)
  v.expect(dataLinkValidator.validateDataLink({ foo: 'BAR' })).toBe(false)
})

/** Load and parse a data-link description. */
function loadDataLinkFile(dataLinkPath: string): unknown {
  const text: string = fs.readFileSync(dataLinkPath, { encoding: 'utf-8' })
  return JSON.parse(text)
}

/** Check if the given data-link description matches the schema, reporting any errors. */
function testSchema(json: unknown, fileName: string): void {
  const validate = dataLinkValidator.validateDataLink
  if (!validate(json)) {
    v.assert.fail(`Failed to validate ${fileName}:\n${JSON.stringify(validate.errors, null, 2)}`)
  }
}

// We need to go up from `app/ide-desktop/lib/dashboard/` to the root of the repo
const DIR_DEPTH = 7
const REPO_ROOT = url.fileURLToPath(new URL('../'.repeat(DIR_DEPTH), import.meta.url))
const BASE_DATA_LINKS_ROOT = path.resolve(REPO_ROOT, 'test/Base_Tests/data/datalinks/')
const S3_DATA_LINKS_ROOT = path.resolve(REPO_ROOT, 'test/AWS_Tests/data/')
const TABLE_DATA_LINKS_ROOT = path.resolve(REPO_ROOT, 'test/Table_Tests/data/datalinks/')

v.test('correctly validates example HTTP .datalink files with the schema', () => {
  const schemas = [
    'example-http.datalink',
    'example-http-format-explicit-default.datalink',
    'example-http-format-delimited.datalink',
    'example-http-format-json.datalink',
  ]
  for (const schema of schemas) {
    const json = loadDataLinkFile(path.resolve(BASE_DATA_LINKS_ROOT, schema))
    testSchema(json, schema)
  }
})

v.test('correctly validates example Enso_File .datalink files with the schema', () => {
  const schemas = ['example-enso-file.datalink']
  for (const schema of schemas) {
    const json = loadDataLinkFile(path.resolve(BASE_DATA_LINKS_ROOT, schema))
    testSchema(json, schema)
  }
})

v.test('rejects invalid schemas (Base)', () => {
  const invalidSchemas = ['example-http-format-invalid.datalink']
  for (const schema of invalidSchemas) {
    const json = loadDataLinkFile(path.resolve(BASE_DATA_LINKS_ROOT, schema))
    v.expect(dataLinkValidator.validateDataLink(json)).toBe(false)
  }
})

v.test('correctly validates example S3 .datalink files with the schema', () => {
  const schemas = [
    'simple.datalink',
    'credentials-with-secrets.datalink',
    'format-delimited.datalink',
  ]
  for (const schema of schemas) {
    const json = loadDataLinkFile(path.resolve(S3_DATA_LINKS_ROOT, schema))
    testSchema(json, schema)
  }
})

v.test('correctly validates example Table .datalink files with the schema', () => {
  const schemas = [
    'example-http-format-excel-workbook.datalink',
    'example-http-format-excel-sheet.datalink',
    'example-http-format-excel-range.datalink',
    'example-http-format-delimited-custom-quote.datalink',
    'example-http-format-delimited-ignore-quote.datalink',
  ]
  for (const schema of schemas) {
    const json = loadDataLinkFile(path.resolve(TABLE_DATA_LINKS_ROOT, schema))
    testSchema(json, schema)
  }
})

v.test('correctly validates example Database .datalink files with the schema', () => {
  const schemas = ['postgres-db.datalink', 'postgres-table.datalink']
  for (const schema of schemas) {
    const json = loadDataLinkFile(path.resolve(TABLE_DATA_LINKS_ROOT, schema))
    testSchema(json, schema)
  }
})
