import { defineConfig } from "vite";
export default defineConfig({
    cacheDir: ".vite",
    server: {
        port: 5173,
        strictPort: true,
        proxy: {
            "/api": { target: process.env.API_URL || "http://localhost:8080" },
        },
    },
    preview: { port: 5173 },
});
