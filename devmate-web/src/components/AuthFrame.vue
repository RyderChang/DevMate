<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
</script>

<template>
  <main class="auth-page">
    <aside class="auth-intro" aria-label="DevMate 工作空间">
      <div class="auth-wordmark">
        <span class="brand-mark"><AppIcon name="code" /></span> DevMate
      </div>
      <div class="auth-story">
        <p class="auth-kicker">你的项目，你的上下文</p>
        <h2>专注构建。<br />让想法成为交付。</h2>
        <p class="auth-description">
          把项目、对话与文档线索放在一起，<br />为每一次思考保留清晰的上下文。
        </p>
        <div class="auth-flow" aria-hidden="true">
          <div><AppIcon name="folder" /><span>项目空间</span><span class="flow-line"></span></div>
          <div><AppIcon name="chat" /><span>专注对话</span><span class="flow-line"></span></div>
          <div><AppIcon name="code" /><span>持续构建</span></div>
        </div>
      </div>
      <p class="auth-footnote">为软件研发而设计的 AI 工作空间</p>
    </aside>
    <section class="auth-main"><slot /></section>
  </main>
</template>

<style scoped>
.auth-intro {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px clamp(32px, 5vw, 80px);
  color: #fff;
  background: #171e2d;
  position: relative;
  overflow: hidden;
}
.auth-intro::after {
  content: '';
  position: absolute;
  width: 380px;
  height: 380px;
  right: -210px;
  top: 28%;
  border: 1px solid #33405a;
  border-radius: 50%;
  box-shadow:
    0 0 0 64px #ffffff03,
    0 0 0 128px #ffffff02;
  pointer-events: none;
}
.auth-wordmark {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.6px;
}
.auth-story {
  padding: 72px 0;
  position: relative;
  z-index: 1;
}
.auth-kicker {
  color: #a4b8eb;
  font-size: 13px;
  letter-spacing: 0.12em;
}
h2 {
  margin: 24px 0;
  font-size: clamp(30px, 3.2vw, 46px);
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.04em;
}
.auth-description {
  color: #a6afc1;
  font-size: 14px;
  line-height: 1.9;
}
.auth-flow {
  display: grid;
  gap: 19px;
  margin-top: 44px;
  max-width: 300px;
}
.auth-flow > div {
  display: flex;
  align-items: center;
  gap: 14px;
  color: #c5cee0;
  font-size: 13px;
}
.auth-flow .app-icon {
  color: #94aef3;
}
.flow-line {
  height: 1px;
  flex: 1;
  margin-left: 10px;
  background: #33405a;
}
.auth-footnote {
  color: #909bb0;
  font-size: 12px;
}
@media (max-width: 800px) {
  .auth-intro {
    padding: 28px;
  }
  .auth-story,
  .auth-footnote {
    display: none;
  }
}
</style>
