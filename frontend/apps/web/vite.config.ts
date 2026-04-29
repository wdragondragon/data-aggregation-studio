import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  base: '/dfs/data-aggregation-studio/',
  plugins: [vue()],
  resolve: {
    dedupe: ['@antv/x6'],
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@web': path.resolve(__dirname, './src')
    }
  },
  optimizeDeps: {
    exclude: ['@antv/x6-vue-shape']
  },
  build: {
    chunkSizeWarningLimit: 5000,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('monaco-editor')) {
              return 'vendor-monaco';
            }
            if (id.includes('echarts')) {
              return 'vendor-charts';
            }
            if (
              id.includes('@antv/x6') ||
              id.includes('vue-router') ||
              id.includes('pinia') ||
              id.includes('/vue/')
            ) {
              return 'vendor-vue';
            }
            if (id.includes('axios')) {
              return 'vendor-http';
            }
          }
          return undefined;
        }
      }
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/dfs/data-aggregation-studio': {
        target: 'http://127.0.0.1:31649',
        changeOrigin: true,
        rewrite: (proxyPath) => proxyPath.replace(/^\/dfs/, '')
      },
      '/data-aggregation-studio': {
        target: 'http://127.0.0.1:31649',
        changeOrigin: true
      },
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true
      },
      '/openapi': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true
      }
    }
  }
});
