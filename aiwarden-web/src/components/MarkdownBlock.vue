<script setup lang="ts">
/**
 * Markdown 渲染 + 消毒（FR-APP-01「Markdown 渲染」）。
 * marked 产出 HTML，DOMPurify 消毒后才 v-html——不直接信任模型输出。
 * 引用序号 [n]：渲染后把与引用列表对得上的 [n] 变成可点击上标（FR-APP-02）。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = withDefaults(
  defineProps<{
    source: string
    /** 已有引用条数；>0 时把 [1..n] 变成可点击引用 */
    citationCount?: number
  }>(),
  { citationCount: 0 },
)

const emit = defineEmits<{ (event: 'pick-citation', index: number): void }>()

const container = ref<HTMLElement | null>(null)

const html = computed(() => {
  const raw = marked.parse(props.source ?? '', { async: false, gfm: true, breaks: true }) as string
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } })
})

/** 把 [n]（n 在引用范围内）替换成引用锚点，其余文本保持原样。 */
function decorateCitations(): void {
  const root = container.value
  if (!root) {
    return
  }
  const limit = props.citationCount
  if (limit <= 0) {
    return
  }
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const targets: Text[] = []
  let node = walker.nextNode()
  while (node) {
    const text = node.nodeValue ?? ''
    if (/\[\d+\]/.test(text) && node.parentElement?.tagName !== 'A') {
      targets.push(node as Text)
    }
    node = walker.nextNode()
  }

  for (const textNode of targets) {
    const text = textNode.nodeValue ?? ''
    const fragment = document.createDocumentFragment()
    let lastIndex = 0
    const pattern = /\[(\d+)\]/g
    let match = pattern.exec(text)
    while (match) {
      const index = Number(match[1])
      if (index >= 1 && index <= limit) {
        if (match.index > lastIndex) {
          fragment.appendChild(document.createTextNode(text.slice(lastIndex, match.index)))
        }
        const anchor = document.createElement('a')
        anchor.className = 'aw-citation-ref'
        anchor.textContent = `[${index}]`
        anchor.dataset.citationIndex = String(index)
        anchor.title = `查看引用 ${index}`
        fragment.appendChild(anchor)
        lastIndex = match.index + match[0].length
      }
      match = pattern.exec(text)
    }
    if (lastIndex === 0) {
      continue
    }
    if (lastIndex < text.length) {
      fragment.appendChild(document.createTextNode(text.slice(lastIndex)))
    }
    textNode.parentNode?.replaceChild(fragment, textNode)
  }
}

function handleClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  const anchor = target?.closest('a.aw-citation-ref') as HTMLElement | null
  if (!anchor) {
    return
  }
  event.preventDefault()
  const index = Number(anchor.dataset.citationIndex)
  if (Number.isFinite(index)) {
    emit('pick-citation', index)
  }
}

async function render(): Promise<void> {
  await nextTick()
  decorateCitations()
}

onMounted(render)
watch(() => [props.source, props.citationCount], render)
</script>

<template>
  <div ref="container" class="aw-markdown" @click="handleClick" v-html="html" />
</template>
