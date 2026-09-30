<template>
  <aside class="demo-notice" role="note" aria-label="演示数据声明" data-testid="demo-notice">
    <strong>演示数据，未接入真实接口</strong>
    <span
      >景点、路线、天气、时长、票价、政策与社区互动数字均为 Mock / 示例 / 估算或待核验内容。</span
    >
  </aside>
</template>

<style scoped>
.demo-notice {
  position: sticky;
  z-index: 20;
  top: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 12px clamp(16px, 4vw, 48px);
  border-bottom: 1px solid #d97706;
  color: #5a2c00;
  background: #fff4cf;
  box-shadow: 0 4px 18px rgb(90 44 0 / 10%);
  overflow-wrap: anywhere;
}

.demo-notice strong {
  font-size: 16px;
}

.demo-notice span {
  font-size: 14px;
  line-height: 1.5;
}
</style>
