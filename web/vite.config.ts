import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [vue()],
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  resolve: { dedupe: ['vue'] },
  build: { target: 'es2022', outDir: '../build/panel', emptyOutDir: true, assetsInlineLimit: 2 * 1024 * 1024,
    cssCodeSplit: false, lib: { entry: fileURLToPath(new URL('./main.ts', import.meta.url)), name: 'DevLineSteamDeck', formats: ['iife'], fileName: () => 'panel.js', cssFileName: 'panel' } },
})
