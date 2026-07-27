import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

const studioProxyTarget = process.env.STUDIO_PROXY_TARGET
  || process.env.VITE_STUDIO_PROXY_TARGET
  || 'http://127.0.0.1:18080';

function patchNoVue3CronDeprecatedButton() {
  return {
    name: 'studio-patch-no-vue3-cron-deprecated-button',
    enforce: 'pre' as const,
    transform(code: string, id: string) {
      const normalizedId = id.replace(/\\/g, '/');
      if (!normalizedId.includes('/no-vue3-cron/lib/noVue3Cron.')) {
        return null;
      }

      const patchedCode = code.replace(
        /class:\s*["']language["'],\s*type:\s*["']text["']/g,
        'class: "language", link: true',
      );
      return patchedCode === code ? null : { code: patchedCode, map: null };
    },
  };
}

export default defineConfig({
  base: '/dfs/data-aggregation-studio/',
  plugins: [patchNoVue3CronDeprecatedButton(), vue()],
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
      '^/dfs/data-aggregation-studio/(api|openapi|v3/api-docs|swagger-ui|doc\.html)(/.*)?$': {
        target: studioProxyTarget,
        changeOrigin: true,
        rewrite: (proxyPath) => proxyPath.replace(/^\/dfs\/data-aggregation-studio/, '')
      },
      '/data-aggregation-studio': {
        target: studioProxyTarget,
        changeOrigin: true
      },
      '/api': {
        target: studioProxyTarget,
        changeOrigin: true
      },
      '/openapi': {
        target: studioProxyTarget,
        changeOrigin: true
      }
    }
  }
});
