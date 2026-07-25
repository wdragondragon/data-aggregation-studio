import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

function patchLegacyDynamicGlobalFallback() {
  return {
    name: 'studio-patch-legacy-dynamic-global-fallback',
    enforce: 'pre' as const,
    transform(code: string, id: string) {
      const normalizedId = id.replace(/\\/g, '/');
      if (!normalizedId.includes('/node_modules/')) {
        return null;
      }
      if (
        !normalizedId.includes('/lodash/')
        && !normalizedId.includes('/lodash-es/')
        && !normalizedId.includes('/globalthis/')
        && !normalizedId.includes('/core-js/')
      ) {
        return null;
      }
      const patchedCode = code.replace(/Function\((['"])return this\1\)\(\)/g, 'globalThis');
      return patchedCode === code ? null : { code: patchedCode, map: null };
    }
  };
}

export default defineConfig({
  base: "./",
  define: {
    __INTLIFY_JIT_COMPILATION__: true,
    __INTLIFY_DROP_MESSAGE_COMPILER__: false,
    __INTLIFY_PROD_DEVTOOLS__: false
  },
  plugins: [patchLegacyDynamicGlobalFallback(), vue()],
  resolve: {
    dedupe: ['@antv/x6'],
    alias: [
      { find: 'vue', replacement: path.resolve(__dirname, '../../node_modules/vue/dist/vue.runtime.esm-bundler.js') },
      { find: '@/api/studio', replacement: path.resolve(__dirname, './src/api/studio.ts') },
      { find: '@/stores/auth', replacement: path.resolve(__dirname, './src/stores/auth.ts') },
      { find: '@desktop', replacement: path.resolve(__dirname, './src') },
      { find: '@web', replacement: path.resolve(__dirname, '../web/src') },
      { find: '@', replacement: path.resolve(__dirname, '../web/src') }
    ]
  },
  optimizeDeps: {
    exclude: ['@antv/x6-vue-shape']
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
  }
});
