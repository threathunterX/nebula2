import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发时把 /api 与 /checkRisk 代理到控制面,避免前端自己处理跨域。
// 生产构建产物是纯静态文件,由控制面或任意静态服务器托管 —— 前端不引入自己的运行时。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.NEBULA_API ?? 'http://127.0.0.1:8080', changeOrigin: true },
      '/actuator': { target: process.env.NEBULA_API ?? 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
})
