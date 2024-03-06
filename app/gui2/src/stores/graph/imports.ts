import { SuggestionDb } from '@/stores/suggestionDatabase'
import {
  SuggestionKind,
  makeCon,
  makeMethod,
  makeModule,
  makeStaticMethod,
  makeType,
  type SuggestionEntry,
} from '@/stores/suggestionDatabase/entry'
import { Ast } from '@/util/ast'
import { MutableModule, parseIdent, parseIdents, parseQualifiedName } from '@/util/ast/abstract'
import { unwrap } from '@/util/data/result'
import {
  qnLastSegment,
  qnSegments,
  qnSplit,
  tryIdentifier,
  tryQualifiedName,
  type IdentifierOrOperatorIdentifier,
  type QualifiedName,
} from '@/util/qualifiedName'

// ========================
// === Imports analysis ===
// ========================

/** Parse import statement. */
export function recognizeImport(ast: Ast.Import): Import | null {
  const from = ast.from
  const as = ast.as
  const import_ = ast.import_
  const all = ast.all
  const hiding = ast.hiding
  const moduleAst = from ?? import_
  const module = moduleAst ? parseQualifiedName(moduleAst) : null
  if (!module) return null
  if (all) {
    const except = (hiding != null ? parseIdents(hiding) : []) ?? []
    return {
      from: module,
      imported: { kind: 'All', except },
    }
  } else if (from && import_) {
    const names = parseIdents(import_) ?? []
    return {
      from: module,
      imported: { kind: 'List', names },
    }
  } else if (import_) {
    const alias = as ? parseIdent(as) : null
    return {
      from: module,
      imported: alias ? { kind: 'Module', alias } : { kind: 'Module' },
    }
  } else {
    console.error('Unrecognized import', ast.code())
    return null
  }
}

export type ModuleName = QualifiedName

/** Information about parsed import statement. */
export interface Import {
  from: ModuleName
  imported: ImportedNames
}

export type ImportedNames = Module | List | All

/** import Module.Path (as Alias)? */
export interface Module {
  kind: 'Module'
  alias?: IdentifierOrOperatorIdentifier
}

/** from Module.Path import (Ident),+ */
export interface List {
  kind: 'List'
  names: IdentifierOrOperatorIdentifier[]
}

/** from Module.Path import all (hiding (Ident),*)? */
export interface All {
  kind: 'All'
  except: IdentifierOrOperatorIdentifier[]
}

// ========================
// === Required imports ===
// ========================

/** Import required for the suggestion entry. */
export type RequiredImport = QualifiedImport | UnqualifiedImport

/** import Module.Path */
export interface QualifiedImport {
  kind: 'Qualified'
  module: QualifiedName
}

/** from Module.Path import SomeIdentifier */
export interface UnqualifiedImport {
  kind: 'Unqualified'
  from: QualifiedName
  import: IdentifierOrOperatorIdentifier
}

/** Read imports from given module block */
export function readImports(ast: Ast.Ast): Import[] {
  const imports: Import[] = []
  ast.visitRecursiveAst((node) => {
    if (node instanceof Ast.Import) {
      const recognized = recognizeImport(node)
      if (recognized) {
        imports.push(recognized)
      }
      return false
    }
    return true
  })
  return imports
}

/** Insert the given imports into the given block at an appropriate location. */
export function addImports(scope: Ast.MutableBodyBlock, importsToAdd: RequiredImport[]) {
  const imports = importsToAdd.map((info) => requiredImportToAst(info, scope.module))
  const position = newImportsLocation(scope)
  scope.insert(position, ...imports)
}

/** Return a suitable location in the given block to insert an import statement.
 *
 *  The location chosen will be before the first non-import line, and after all preexisting imports.
 *  If there are any blank lines in that range, it will be before them.
 */
function newImportsLocation(scope: Ast.BodyBlock): number {
  let lastImport
  const lines = scope.lines
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!
    if (line.expression) {
      if (line.expression.node?.innerExpression() instanceof Ast.Import) {
        lastImport = i
      } else {
        break
      }
    }
  }
  return lastImport === undefined ? 0 : lastImport + 1
}

/** Create an AST representing the required import statement. */
function requiredImportToAst(value: RequiredImport, module?: MutableModule) {
  const module_ = module ?? MutableModule.Transient()
  switch (value.kind) {
    case 'Qualified':
      return Ast.Import.Qualified(qnSegments(value.module), module_)!
    case 'Unqualified':
      return Ast.Import.Unqualified(qnSegments(value.from), value.import, module_)!
  }
}

/** A list of required imports for specific suggestion entry */
export function requiredImports(
  db: SuggestionDb,
  entry: SuggestionEntry,
  directConImport: boolean = false,
): RequiredImport[] {
  const unqualifiedImport = (from: QualifiedName): UnqualifiedImport[] => [
    {
      kind: 'Unqualified',
      from,
      import: entry.name,
    },
  ]
  switch (entry.kind) {
    case SuggestionKind.Module:
      return entry.reexportedIn
        ? unqualifiedImport(entry.reexportedIn)
        : [
            {
              kind: 'Qualified',
              module: entry.definedIn,
            },
          ]
    case SuggestionKind.Type:
      return unqualifiedImport(entry.reexportedIn ? entry.reexportedIn : entry.definedIn)
    case SuggestionKind.Constructor:
      if (directConImport) {
        return entry.reexportedIn
          ? unqualifiedImport(entry.reexportedIn)
          : entry.memberOf
          ? unqualifiedImport(entry.memberOf)
          : []
      } else {
        const selfType = selfTypeEntry(db, entry)
        return selfType ? requiredImports(db, selfType) : []
      }
    case SuggestionKind.Method: {
      const isStatic = entry.selfType == null
      const selfType = selfTypeEntry(db, entry)
      const isExtension = selfType && selfType.definedIn !== entry.definedIn
      const definedIn = definedInEntry(db, entry)
      const extensionImports = isExtension && definedIn ? requiredImports(db, definedIn) : []
      const selfTypeImports = isStatic && selfType ? requiredImports(db, selfType) : []
      if (isStatic) {
        return [...extensionImports, ...selfTypeImports]
      } else {
        return [...extensionImports]
      }
    }
    case SuggestionKind.Function:
    case SuggestionKind.Local:
    default:
      return []
  }
}

export function requiredImportsByFQN(
  db: SuggestionDb,
  fqn: QualifiedName,
  directConImport: boolean = false,
) {
  const entry = db.getEntryByQualifiedName(fqn)
  if (!entry) return []
  return requiredImports(db, entry, directConImport)
}

function selfTypeEntry(db: SuggestionDb, entry: SuggestionEntry): SuggestionEntry | undefined {
  if (entry.memberOf) {
    return db.getEntryByQualifiedName(entry.memberOf)
  }
}

function definedInEntry(db: SuggestionDb, entry: SuggestionEntry): SuggestionEntry | undefined {
  return db.getEntryByQualifiedName(entry.definedIn)
}

function entryFQNFromRequiredImport(importStatement: RequiredImport): QualifiedName {
  if (importStatement.kind === 'Qualified') {
    return importStatement.module
  } else {
    return unwrap(tryQualifiedName(`${importStatement.from}.${importStatement.import}`))
  }
}

export function requiredImportEquals(left: RequiredImport, right: RequiredImport): boolean {
  if (left.kind != right.kind) return false
  switch (left.kind) {
    case 'Qualified':
      return left.module === (right as QualifiedImport).module
    case 'Unqualified':
      return (
        left.from === (right as UnqualifiedImport).from &&
        left.import === (right as UnqualifiedImport).import
      )
  }
}

/** Check if `existing` import statement covers `required`. */
export function covers(existing: Import, required: RequiredImport): boolean {
  const [parent, name] =
    required.kind === 'Qualified'
      ? qnSplit(required.module)
      : required.kind === 'Unqualified'
      ? [required.from, required.import]
      : [undefined, '']
  const directlyImported =
    required.kind === 'Qualified' &&
    existing.imported.kind === 'Module' &&
    existing.from === required.module
  const importedInList =
    existing.imported.kind === 'List' &&
    parent != null &&
    existing.from === parent &&
    existing.imported.names.includes(name)
  const importedWithAll =
    existing.imported.kind === 'All' &&
    parent != null &&
    existing.from === parent &&
    !existing.imported.except.includes(name)
  return directlyImported || importedInList || importedWithAll
}

export function filterOutRedundantImports(
  existing: Import[],
  required: RequiredImport[],
): RequiredImport[] {
  return required.filter((info) => !existing.some((existing) => covers(existing, info)))
}

/* Information about detected conflict import, and advisory on resolution. */
export interface DetectedConflict {
  /* Always true, for more expressive API usage. */
  detected: boolean
  /* Advisory to replace the following name (qualified name or single ident)… */
  pattern: QualifiedName | IdentifierOrOperatorIdentifier
  /* … with this fully qualified name. */
  fullyQualified: QualifiedName
}

export type ConflictInfo = DetectedConflict | undefined

/* Detect possible name clash when adding `importsForEntry` with `existingImports` present. */
export function detectImportConflicts(
  suggestionDb: SuggestionDb,
  existingImports: Import[],
  importToCheck: RequiredImport,
): ConflictInfo {
  const entryFQN = entryFQNFromRequiredImport(importToCheck)
  const [entryId] = suggestionDb.nameToId.lookup(entryFQN)
  if (entryId == null) return
  const name = qnLastSegment(entryFQN)
  const conflictingIds = suggestionDb.conflictingNames.lookup(name)
  // Obviously, the entry doesn’t conflict with itself.
  conflictingIds.delete(entryId)

  for (const id of conflictingIds) {
    const e = suggestionDb.get(id)
    const required = e ? requiredImports(suggestionDb, e) : []
    for (const req of required) {
      if (existingImports.some((existing) => covers(existing, req))) {
        return {
          detected: true,
          pattern: name,
          fullyQualified: entryFQN,
        }
      }
    }
  }
}

if (import.meta.vitest) {
  const { test, expect } = import.meta.vitest
  const { initializeFFI } = await import('shared/ast/ffi')

  await initializeFFI()

  test.each([
    {
      description: 'Direct import of a module',
      existing: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: { kind: 'Module' },
      } as Import,
      required: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('Standard.Base')),
      } as RequiredImport,
      expected: true,
    },
    {
      description: 'Module imported by alias',
      existing: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: { kind: 'Module', alias: unwrap(tryIdentifier('MyBase')) },
      } as Import,
      required: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('Standard.Base')),
      } as RequiredImport,
      expected: true,
    },
    {
      description: 'Module imported from parent',
      existing: {
        from: unwrap(tryQualifiedName('Standard')),
        imported: { kind: 'List', names: [unwrap(tryIdentifier('Base'))] },
      } as Import,
      required: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('Standard.Base')),
      } as RequiredImport,
      expected: true,
    },
    {
      description: 'Module imported from parent with all',
      existing: {
        from: unwrap(tryQualifiedName('Standard')),
        imported: { kind: 'All', except: [] },
      } as Import,
      required: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('Standard.Base')),
      } as RequiredImport,
      expected: true,
    },
    {
      description: 'Module hidden when importing all from parent',
      existing: {
        from: unwrap(tryQualifiedName('Standard')),
        imported: { kind: 'All', except: [unwrap(tryIdentifier('Base'))] },
      } as Import,
      required: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('Standard.Base')),
      } as RequiredImport,
      expected: false,
    },
    {
      description: 'Type imported from module by name',
      existing: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: { kind: 'List', names: [unwrap(tryIdentifier('Table'))] },
      } as Import,
      required: {
        kind: 'Unqualified',
        from: unwrap(tryQualifiedName('Standard.Base')),
        import: unwrap(tryIdentifier('Table')),
      } as RequiredImport,
      expected: true,
    },
    {
      description: 'Type imported from module by all',
      existing: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: { kind: 'All', except: [] },
      } as Import,
      required: {
        kind: 'Unqualified',
        from: unwrap(tryQualifiedName('Standard.Base')),
        import: unwrap(tryIdentifier('Table')),
      } as RequiredImport,
      expected: true,
    },
    {
      description: 'Type hidden when importing all',
      existing: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: { kind: 'All', except: [unwrap(tryIdentifier('Table'))] },
      } as Import,
      required: {
        kind: 'Unqualified',
        from: unwrap(tryQualifiedName('Standard.Base')),
        import: unwrap(tryIdentifier('Table')),
      } as RequiredImport,
      expected: false,
    },
  ])('Existing imports cover required, $description', ({ existing, required, expected }) => {
    expect(covers(existing, required)).toStrictEqual(expected)
  })

  const mockDb = () => {
    const db = new SuggestionDb()
    const reexportedModule = makeModule('Standard.AWS.Connections')
    reexportedModule.reexportedIn = unwrap(tryQualifiedName('Standard.Base'))
    const reexportedType = makeModule('Standard.Database.DB_Table.DB_Table')
    reexportedType.reexportedIn = unwrap(tryQualifiedName('Standard.Base'))
    const extensionMethod = makeMethod('Standard.Network.URI.fetch')
    extensionMethod.definedIn = unwrap(tryQualifiedName('Standard.Base'))
    db.set(1, makeModule('Standard.Base'))
    db.set(2, makeType('Standard.Base.Type'))
    db.set(3, reexportedModule)
    db.set(4, reexportedType)
    db.set(5, makeCon('Standard.Base.Type.Constructor'))
    db.set(6, makeStaticMethod('Standard.Base.Type.staticMethod'))
    db.set(7, makeMethod('Standard.Base.Type.method'))
    db.set(8, makeType('Standard.Network.URI'))
    db.set(9, extensionMethod)
    db.set(10, makeType('Standard.Base.Vector'))
    db.set(11, makeStaticMethod('Standard.Base.Vector.new'))
    db.set(12, makeModule('Project.Foo'))
    db.set(13, makeType('Project.Foo.Vector'))
    db.set(14, makeStaticMethod('Project.Foo.Vector.new'))
    db.set(15, makeModule('Project.Foo.Base'))

    return db
  }

  const qn = (s: string) => unwrap(tryQualifiedName(s))
  test.each([
    {
      description: 'Conflicting Vector',
      importing: {
        kind: 'Unqualified',
        from: qn('Project.Foo'),
        import: 'Vector',
      } as RequiredImport,
      alreadyImported: [
        { from: qn('Standard.Base'), imported: { kind: 'List', names: ['Vector'] } } as Import,
      ],
      expected: { name: 'Vector', fullyQualified: 'Project.Foo.Vector' },
    },
    {
      description: 'Conflicting Vector (2)',
      importing: {
        kind: 'Unqualified',
        from: qn('Project.Foo'),
        import: 'Vector',
      } as RequiredImport,
      alreadyImported: [
        { from: qn('Standard.Base'), imported: { kind: 'All', except: [] } } as Import,
      ],
      expected: { name: 'Vector', fullyQualified: 'Project.Foo.Vector' },
    },
  ])('Conflicting imports: $description', ({ importing, alreadyImported, expected }) => {
    const db = mockDb()

    const existingImports: Import[] = alreadyImported
    const conflicts = detectImportConflicts(db, existingImports, importing)
    expect(conflicts).toEqual({
      detected: true,
      pattern: expected.name,
      fullyQualified: expected.fullyQualified,
    } as ConflictInfo)
  })

  test.each([
    {
      id: 1,
      expected: [
        {
          kind: 'Qualified',
          module: unwrap(tryQualifiedName('Standard.Base')),
        },
      ],
    },
    {
      id: 2,
      expected: [
        {
          kind: 'Unqualified',
          from: unwrap(tryQualifiedName('Standard.Base')),
          import: unwrap(tryIdentifier('Type')),
        },
      ],
    },
    {
      id: 3,
      expected: [
        {
          kind: 'Unqualified',
          from: unwrap(tryQualifiedName('Standard.Base')),
          import: unwrap(tryIdentifier('Connections')),
        },
      ],
    },
    {
      id: 4,
      expected: [
        {
          kind: 'Unqualified',
          from: unwrap(tryQualifiedName('Standard.Base')),
          import: unwrap(tryIdentifier('DB_Table')),
        },
      ],
    },
    {
      id: 5,
      expected: [
        {
          kind: 'Unqualified',
          from: unwrap(tryQualifiedName('Standard.Base')),
          import: unwrap(tryIdentifier('Type')),
        },
      ],
    },
    {
      id: 6,
      expected: [
        {
          kind: 'Unqualified',
          from: unwrap(tryQualifiedName('Standard.Base')),
          import: unwrap(tryIdentifier('Type')),
        },
      ],
    },
    {
      id: 7,
      expected: [],
    },
    {
      id: 9,
      expected: [
        {
          kind: 'Qualified',
          module: unwrap(tryQualifiedName('Standard.Base')),
        },
      ],
    },
  ])('Required imports $id', ({ id, expected }) => {
    const db = mockDb()
    expect(requiredImports(db, db.get(id)!)).toStrictEqual(expected)
  })

  const parseImport = (code: string): Import | null => {
    const ast = Ast.Import.tryParse(code)
    return ast ? recognizeImport(ast) : null
  }

  test.each([
    { code: '1 + 1', expected: null },
    { code: 'import Standard.(2+2).Base', expected: null },
    {
      code: 'from Standard.Base import all',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: { kind: 'All', except: [] },
      },
    },
    {
      code: 'from Standard.Base.Table import Table',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base.Table')),
        imported: { kind: 'List', names: [unwrap(tryIdentifier('Table'))] },
      },
    },
    {
      code: 'import AWS.Connection as Backend',
      expected: {
        from: unwrap(tryQualifiedName('AWS.Connection')),
        imported: { kind: 'Module', alias: 'Backend' },
      },
    },
    {
      code: 'import Standard.Base.Data',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base.Data')),
        imported: { kind: 'Module' },
      },
    },
    {
      code: 'import local',
      expected: {
        from: unwrap(tryQualifiedName('local')),
        imported: { kind: 'Module' },
      },
    },
    {
      code: 'from Standard.Base import Foo, Bar',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: {
          kind: 'List',
          names: [unwrap(tryIdentifier('Foo')), unwrap(tryIdentifier('Bar'))],
        },
      },
    },
    {
      code: 'from   Standard  . Base import  Foo ,  Bar ,Buz',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: {
          kind: 'List',
          names: [
            unwrap(tryIdentifier('Foo')),
            unwrap(tryIdentifier('Bar')),
            unwrap(tryIdentifier('Buz')),
          ],
        },
      },
    },
    {
      code: 'from Standard.Base import all hiding Foo, Bar',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: {
          kind: 'All',
          except: [unwrap(tryIdentifier('Foo')), unwrap(tryIdentifier('Bar'))],
        },
      },
    },
    {
      code: 'from   Standard  . Base import  all  hiding  Foo ,  Bar ,Buz',
      expected: {
        from: unwrap(tryQualifiedName('Standard.Base')),
        imported: {
          kind: 'All',
          except: [
            unwrap(tryIdentifier('Foo')),
            unwrap(tryIdentifier('Bar')),
            unwrap(tryIdentifier('Buz')),
          ],
        },
      },
    },
  ])('Recognizing import $code', ({ code, expected }) => {
    expect(parseImport(code)).toStrictEqual(expected)
  })

  test.each([
    {
      import: {
        kind: 'Unqualified',
        from: unwrap(tryQualifiedName('Standard.Base.Table')),
        import: unwrap(tryIdentifier('Table')),
      } satisfies RequiredImport,
      expected: 'from Standard.Base.Table import Table',
    },
    {
      import: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('Standard.Base.Data')),
      } satisfies RequiredImport,
      expected: 'import Standard.Base.Data',
    },
    {
      import: {
        kind: 'Qualified',
        module: unwrap(tryQualifiedName('local')),
      } satisfies RequiredImport,
      expected: 'import local',
    },
  ])('Generating import $expected', ({ import: import_, expected }) => {
    expect(requiredImportToAst(import_).code()).toStrictEqual(expected)
  })

  test('Insert after other imports in module', () => {
    const module_ = Ast.parseBlock('from Standard.Base import all\n\nmain = 42\n')
    const edit = module_.module.edit()
    addImports(edit.getVersion(module_), [
      { kind: 'Qualified', module: unwrap(tryQualifiedName('Standard.Visualization')) },
    ])
    expect(edit.getVersion(module_).code()).toBe(
      'from Standard.Base import all\nimport Standard.Visualization\n\nmain = 42\n',
    )
  })

  test('Insert import in module with no other imports', () => {
    const module_ = Ast.parseBlock('main = 42\n')
    const edit = module_.module.edit()
    addImports(edit.getVersion(module_), [
      { kind: 'Qualified', module: unwrap(tryQualifiedName('Standard.Visualization')) },
    ])
    expect(edit.getVersion(module_).code()).toBe('import Standard.Visualization\nmain = 42\n')
  })
}
