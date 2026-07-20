import { defineConfig } from 'vite'
import legacy from '@vitejs/plugin-legacy'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    legacy({
      // Android 7 can still ship a Chrome 51 era system WebView.
      targets: ["Chrome >= 51"],
      modernPolyfills: true,
    }),
  ],
  build: {
    cssTarget: "chrome51",
    minify: "terser",
  },
})
