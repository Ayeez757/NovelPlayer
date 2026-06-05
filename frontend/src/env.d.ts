declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // 让 TypeScript 能识别 .vue 单文件组件导入。
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
