<script setup lang="ts">
import { DEFAULT_ALT_TEXT } from '@/components/MarkdownEditor/ImagePlugin'
import {
  injectLexicalImageUrlTransformer,
  type TransformUrlResult,
} from '@/components/MarkdownEditor/imageUrlTransformer'
import { computedAsync } from '@vueuse/core'
import { Ok } from 'shared/util/data/result'
import { computed, onUnmounted, type Ref } from 'vue'

const props = defineProps<{
  src: string
  alt: string
}>()

const urlTransformer = injectLexicalImageUrlTransformer(true)

// NOTE: Garbage-collecting image data when the `src` changes is not implemented. Current users of `LexicalImage` don't
// change the `src` after creating an image.
const data: Ref<TransformUrlResult | undefined> =
  urlTransformer ?
    computedAsync(() => urlTransformer.transformUrl(props.src), undefined)
  : computed(() => Ok({ url: props.src }))

const title = computed(() =>
  data.value == null ? 'Loading'
  : !data.value.ok ? data.value.error.message()
  : props.alt !== DEFAULT_ALT_TEXT ? props.alt
  : '',
)

onUnmounted(() => {
  if (data.value?.ok) data.value.value.dispose?.()
})
</script>

<template>
  <img :src="data?.ok ? data.value.url : ''" :alt="alt" :title="title" />
</template>
