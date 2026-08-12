import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  // Use relative paths for file:// protocol in Android WebView
  base: "./",

  build: {
    outDir: "dist",
    assetsDir: "assets",
    // Pinned, not inherited. This bundle runs in an Android System WebView on a phone with minSdk
    // 26, and vite's default floor moves with vite: 5 shipped chrome87, 7 raised it to chrome107.
    // A dependency that starts emitting newer syntax would then white-screen the app on a device
    // whose WebView has never been updated, with nothing failing at build time to say so. chrome87
    // is what the app was built and tested against before the vite 7 upgrade; raise it deliberately
    // and with a device to try it on, or not at all.
    target: "chrome87",
    // Generate sourcemaps for debugging
    sourcemap: mode === "development",
    // Ensure single CSS file for simpler loading
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        // Predictable file names for easier debugging
        entryFileNames: "assets/[name].js",
        chunkFileNames: "assets/[name].js",
        assetFileNames: "assets/[name].[ext]",
      },
    },
  },

  server: {
    // For development with emulator
    host: "::",
    port: 8080,
    hmr: {
      overlay: false,
    },
  },

  plugins: [react()],

  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
}));
