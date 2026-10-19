<script setup lang="ts">
import type { RagEvidence } from '@/api/types'

defineProps<{ evidence: RagEvidence }>()
</script>

<template>
  <section class="rag-evidence" aria-label="文档引用">
    <strong>文档引用</strong>
    <p class="evidence-note">引用定位不代表回答事实已经人工确认。</p>
    <ol>
      <li v-for="citation in evidence.citations" :key="citation.citationId">
        <span class="citation-name"
          >[{{ citation.citationId }}] {{ citation.source.filename }}</span
        >
        <span
          >片段 {{ citation.source.ordinal }} · 第 {{ citation.source.startLine }}–{{
            citation.source.endLine
          }}
          行</span
        >
        <el-tag :type="citation.available ? 'success' : 'warning'" size="small">
          {{ citation.available ? '当前可用' : '来源当前不可用' }}
        </el-tag>
        <details>
          <summary>核验定位</summary>
          <dl>
            <dt>文档 / 处理 / 索引</dt>
            <dd>
              {{ citation.source.documentId }} / {{ citation.source.processingId }} /
              {{ citation.source.indexId }}
            </dd>
            <dt>代次</dt>
            <dd>
              {{ citation.source.processingGeneration }} / {{ citation.source.indexGeneration }}
            </dd>
            <dt>解析 / 分块版本</dt>
            <dd>{{ citation.source.parserVersion }} / {{ citation.source.strategyVersion }}</dd>
            <dt>来源 SHA-256</dt>
            <dd>{{ citation.source.sourceSha256 }}</dd>
            <dt>片段 SHA-256</dt>
            <dd>{{ citation.source.chunkSha256 }}</dd>
            <dt>规范化字符区间</dt>
            <dd>[{{ citation.source.start }}, {{ citation.source.end }})</dd>
          </dl>
        </details>
      </li>
    </ol>
    <small
      >发布时来源检查时间：{{ evidence.rag.checkedAt }} · {{ evidence.rag.templateVersion }}</small
    >
  </section>
</template>

<style scoped>
.rag-evidence {
  margin-top: 18px;
  padding: 18px;
  border: 1px solid var(--dm-border);
  border-radius: 9px;
  background: var(--dm-surface);
  font-size: 12px;
}
.rag-evidence > strong {
  font-size: 12px;
  font-weight: 600;
}
.evidence-note {
  color: var(--dm-secondary);
  font-size: 11px;
  line-height: 1.7;
}
ol {
  display: grid;
  gap: 18px;
  margin: 16px 0;
  padding-left: 18px;
}
li {
  overflow-wrap: anywhere;
}
li > span {
  display: block;
  margin-bottom: 6px;
  color: var(--dm-secondary);
}
li .citation-name {
  color: var(--dm-text);
  font-weight: 550;
}
.el-tag {
  display: inline-flex;
  width: fit-content;
}
summary {
  margin-top: 10px;
  color: var(--dm-accent);
  cursor: pointer;
  font-size: 11px;
}
dl {
  display: grid;
  grid-template-columns: minmax(0, max-content) minmax(0, 1fr);
  gap: 7px 14px;
  padding: 14px;
  border-radius: 6px;
  background: var(--dm-canvas);
  font-size: 11px;
}
dt {
  color: var(--dm-secondary);
}
dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}
small {
  display: block;
  color: var(--dm-muted);
  font-size: 10px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
@media (max-width: 480px) {
  dl {
    grid-template-columns: 1fr;
    gap: 4px;
  }
  dd {
    margin-bottom: 8px;
  }
  .rag-evidence {
    padding: 14px;
  }
}
</style>
