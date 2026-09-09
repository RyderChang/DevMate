import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: env.VITE_DEV_PROXY_TARGET
      ? {
          proxy: {
            '/api': {
              target: env.VITE_DEV_PROXY_TARGET,
              changeOrigin: true,
            },
          },
        }
      : undefined,
  }
})
