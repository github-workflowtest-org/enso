import { Awareness } from '@/stores/awareness'
import { Vec2 } from '@/util/data/vec2'
import type { DataServer } from '@/util/net/dataServer'
import { Keccak, sha3_224 as SHA3 } from '@noble/hashes/sha3'
import type { Hash } from '@noble/hashes/utils'
import { bytesToHex } from '@noble/hashes/utils'
import { escapeTextLiteral } from 'shared/ast'
import type { LanguageServer } from 'shared/languageServer'
import { ErrorCode, RemoteRpcError } from 'shared/languageServer'
import type { ContentRoot, Path, StackItem, Uuid } from 'shared/languageServerTypes'
import { Err, Ok, withContext, type Result } from 'shared/util/data/result'
import { markRaw, toRaw } from 'vue'

// === Constants ===

const DATA_DIR_NAME = 'data'

export function uploadedExpression(result: UploadResult) {
  switch (result.source) {
    case 'Project': {
      return `enso_project.data/'${escapeTextLiteral(result.name)}' . read`
    }
    case 'FileSystemRoot': {
      return `Data.read '${escapeTextLiteral(result.name)}'`
    }
  }
}

// === Uploader ===

export interface UploadResult {
  source: 'FileSystemRoot' | 'Project'
  name: string
}

export class Uploader {
  private checksum: Hash<Keccak>
  private uploadedBytes: bigint
  private stackItem: StackItem

  private constructor(
    private rpc: LanguageServer,
    private binary: DataServer,
    private awareness: Awareness,
    private file: File,
    private projectRootId: Uuid,
    private position: Vec2,
    private isOnLocalBackend: boolean,
    private disableDirectRead: boolean,
    stackItem: StackItem,
  ) {
    this.checksum = SHA3.create()
    this.uploadedBytes = BigInt(0)
    this.stackItem = markRaw(toRaw(stackItem))
  }

  static async Create(
    rpc: LanguageServer,
    binary: DataServer,
    contentRoots: Promise<ContentRoot[]>,
    awareness: Awareness,
    file: File,
    position: Vec2,
    isOnLocalBackend: boolean,
    disableDirectRead: boolean,
    stackItem: StackItem,
  ): Promise<Uploader> {
    const roots = await contentRoots
    const projectRootId = roots.find((root) => root.type == 'Project')
    if (!projectRootId) throw new Error('Could not find project root, uploading not possible.')
    const instance = new Uploader(
      rpc,
      binary,
      awareness,
      file,
      projectRootId.id,
      position,
      isOnLocalBackend,
      disableDirectRead,
      stackItem,
    )
    return instance
  }

  async upload(): Promise<Result<UploadResult>> {
    // This non-standard property is defined in Electron.
    if (
      this.isOnLocalBackend &&
      !this.disableDirectRead &&
      'path' in this.file &&
      typeof this.file.path === 'string'
    ) {
      return Ok({ source: 'FileSystemRoot', name: this.file.path })
    }
    const dataDirExists = await this.ensureDataDirExists()
    if (!dataDirExists.ok) return dataDirExists
    const name = await this.pickUniqueName(this.file.name)
    if (!name.ok) return name
    this.awareness.addOrUpdateUpload(name.value, {
      sizePercentage: 0,
      position: this.position,
      stackItem: this.stackItem,
    })
    const remotePath: Path = { rootId: this.projectRootId, segments: [DATA_DIR_NAME, name.value] }
    const uploader = this
    const cleanup = this.cleanup.bind(this, name.value)
    const writableStream = new WritableStream<Uint8Array>({
      async write(chunk: Uint8Array) {
        await uploader.binary.writeBytes(remotePath, uploader.uploadedBytes, false, chunk)
        uploader.checksum.update(chunk)
        uploader.uploadedBytes += BigInt(chunk.length)
        const bytes = Number(uploader.uploadedBytes)
        const sizePercentage = Math.round((bytes / uploader.file.size) * 100)
        uploader.awareness.addOrUpdateUpload(name.value, {
          sizePercentage,
          position: uploader.position,
          stackItem: uploader.stackItem,
        })
      },
      async close() {
        cleanup()
      },
      async abort(reason: string) {
        cleanup()
        await uploader.rpc.deleteFile(remotePath)
        throw new Error(`Uploading process aborted. ${reason}`)
      },
    })
    // Disabled until https://github.com/enso-org/enso/issues/6691 is fixed.
    // Plus, handle the error here, as it should be displayed to the user.
    // uploader.assertChecksum(remotePath)
    await this.file.stream().pipeTo(writableStream)
    return Ok({ source: 'Project', name: name.value })
  }

  private cleanup(name: string) {
    this.awareness.removeUpload(name)
  }

  private async assertChecksum(path: Path): Promise<Result<void>> {
    const engineChecksum = await this.rpc.fileChecksum(path)
    if (!engineChecksum.ok) return engineChecksum
    const hexChecksum = bytesToHex(this.checksum.digest())
    if (hexChecksum != engineChecksum.value.checksum) {
      return Err(
        `Uploading file failed, checksum does not match. ${hexChecksum} != ${engineChecksum.value.checksum}`,
      )
    } else {
      return Ok()
    }
  }

  private dataDirPath(): Path {
    return { rootId: this.projectRootId, segments: [DATA_DIR_NAME] }
  }

  private async ensureDataDirExists() {
    const exists = await this.dataDirExists()
    if (!exists.ok) return exists
    if (exists.value) return Ok()
    return await withContext(
      () => 'When creating directory for uploaded file',
      async () => {
        return await this.rpc.createFile({
          type: 'Directory',
          name: DATA_DIR_NAME,
          path: { rootId: this.projectRootId, segments: [] },
        })
      },
    )
  }

  private async dataDirExists(): Promise<Result<boolean>> {
    const info = await this.rpc.fileInfo(this.dataDirPath())
    if (info.ok) return Ok(info.value.attributes.kind.type == 'Directory')
    else if (
      info.error.payload.cause instanceof RemoteRpcError &&
      (info.error.payload.cause.code === ErrorCode.FILE_NOT_FOUND ||
        info.error.payload.cause.code === ErrorCode.CONTENT_ROOT_NOT_FOUND)
    ) {
      return Ok(false)
    } else {
      return info
    }
  }

  private async pickUniqueName(suggestedName: string): Promise<Result<string>> {
    const files = await this.rpc.listFiles(this.dataDirPath())
    if (!files.ok) return files
    const existingNames = new Set(files.value.paths.map((path) => path.name))
    const { stem, extension = '' } = splitFilename(suggestedName)
    let candidate = suggestedName
    let num = 1
    while (existingNames.has(candidate)) {
      candidate = `${stem}_${num}.${extension}`
      num += 1
    }
    return Ok(candidate)
  }
}

/**
 * Split filename into stem and (optional) extension.
 */
function splitFilename(fileName: string): { stem: string; extension?: string } {
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex !== -1 && dotIndex !== 0) {
    const stem = fileName.substring(0, dotIndex)
    const extension = fileName.substring(dotIndex + 1)
    return { stem, extension }
  }
  return { stem: fileName }
}
