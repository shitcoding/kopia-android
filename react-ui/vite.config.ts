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
