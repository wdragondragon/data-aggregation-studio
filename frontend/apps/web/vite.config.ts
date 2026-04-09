import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@web': path.resolve(__dirname, './src')
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('monaco-editor')) {
              return 'vendor-monaco';
            }
            if (id.includes('@antv/x6')) {
              return 'vendor-x6';
            }
            if (id.includes('vue-router') || id.includes('pinia') || id.includes('/vue/')) {
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
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true
      }
    }
  }
});
