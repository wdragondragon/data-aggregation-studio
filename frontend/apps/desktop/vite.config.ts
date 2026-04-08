import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  base: "./",
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@web': path.resolve(__dirname, '../web/src')
    }
  },
  server: {
    host: "localhost",
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:18180',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist/renderer',
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
  }
});
