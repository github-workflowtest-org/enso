import { fc, test } from '@fast-check/vitest'
import { __TEST, isAstId } from 'shared/ast/mutableModule'
import { expect } from 'vitest'

const { newAstId } = __TEST

test.prop({ str: fc.stringMatching(/^[A-Za-z]+$/) })('isAstId matches created IDs', ({ str }) => {
  expect(newAstId(str)).toSatisfy(isAstId)
})

test.prop({ str: fc.uuid() })('isAstId does not match uuids', ({ str: uuid }) => {
  expect(uuid).not.toSatisfy(isAstId)
})
