<script setup lang="ts">
import MenuButton from '@/components/MenuButton.vue'
import SvgIcon from '@/components/SvgIcon.vue'
import type { URLString } from '@/util/data/urlString'
import type { Icon } from '@/util/iconName'

const _props = defineProps<{
  name: Icon | URLString
  label?: string
  disabled?: boolean
  title?: string
}>()
</script>

<template>
  <MenuButton :disabled="disabled" class="SvgButton" :title="title">
    <SvgIcon :name="name" />
    <div v-if="label" v-text="label" />
  </MenuButton>
</template>

<style scoped>
.SvgButton {
  margin: -4px;
  gap: 4px;

  &.disabled {
    opacity: 0.3;
  }
}
</style>
