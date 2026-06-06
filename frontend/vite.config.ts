import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 开发环境下前端直连 Vite，API 请求代理到本地 Spring Boot。
    proxy: {
      '/api': 'http://localhost:18080'
    }
  },
  build: {
    outDir: 'dist'
  }
})
