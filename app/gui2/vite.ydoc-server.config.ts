/// <reference types="histoire" />

import { getDefines, readEnvironmentFromFile } from 'enso-common/src/appConfig'
import * as fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig, type Plugin } from 'vite'

const localServerPort = 8080
const projectManagerUrl = 'ws://127.0.0.1:30535'

const IS_CLOUD_BUILD = process.env.CLOUD_BUILD === 'true'

await readEnvironmentFromFile()

// https://vitejs.dev/config/
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  cacheDir: fileURLToPath(new URL('../../node_modules/.cache/vite', import.meta.url)),
  publicDir: fileURLToPath(new URL('./public', import.meta.url)),
  envDir: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [useJavaFfi()],
  resolve: {
    alias: {
      shared: fileURLToPath(new URL('./shared', import.meta.url)),
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      //ws: fileURLToPath(new URL('../../node_modules/ws/index.js', import.meta.url)),
    },
  },
  define: {
    ...getDefines(localServerPort),
    IS_CLOUD_BUILD: JSON.stringify(IS_CLOUD_BUILD),
    PROJECT_MANAGER_URL: JSON.stringify(projectManagerUrl),
    RUNNING_VITEST: false,
    'import.meta.vitest': false,
    // Single hardcoded usage of `global` in aws-amplify.
    'global.TYPED_ARRAY_SUPPORT': true,
    // One of the libraries refers to self in `self.fetch.bind(self)`
    self: 'globalThis',
  },
  build: {
    minify: false, // For debugging
    emptyOutDir: true,
    outDir: '../../lib/java/ydoc-server/target/classes/dist',
    /*lib: {
      name: 'ydocServer',
      entry: fileURLToPath(new URL('ydoc-server/server.ts', import.meta.url)),
      fileName: () => 'ydocServer.js',
    },*/
    rollupOptions: {
      input: {
        ydocServer: fileURLToPath(new URL('ydoc-server/server.ts', import.meta.url)),
      },
      output: {
        entryFileNames: `assets/[name].js`,
      },
    },
  },
})

/**
 * Use `ffiJava` module as `ffi` interface during the build.
 */
function useJavaFfi(): Plugin {
  const ffiJava = fileURLToPath(new URL('./shared/ast/ffiJava.ts', import.meta.url))
  const ffiWasm = fileURLToPath(new URL('./shared/ast/ffiWasm.ts', import.meta.url))
  const ffi = fileURLToPath(new URL('./shared/ast/ffi.ts', import.meta.url))

  return {
    name: 'use-java-ffi',
    options: () => {
      fs.renameSync(ffi, ffiWasm)
      fs.copyFileSync(ffiJava, ffi)
    },
    buildEnd: () => {
      fs.renameSync(ffiWasm, ffi)
    },
  }
}
