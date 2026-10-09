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
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
  font-size: 13px;
}
.evidence-note {
  color: #606266;
}
ol {
  display: grid;
  gap: 10px;
  margin: 10px 0;
  padding-left: 20px;
}
li {
  overflow-wrap: anywhere;
}
li > span {
  display: block;
  margin-bottom: 4px;
}
.citation-name {
  font-weight: 600;
}
summary {
  cursor: pointer;
}
dl {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 3px 8px;
}
dt {
  color: #606266;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
small {
  color: #606266;
}
</style>
