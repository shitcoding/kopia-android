# KopiaKt React UI

The React frontend for KopiaKt, rendered in an Android WebView and bridged to the Kotlin
backend through a JavaScript interface.

## Tech stack

- Vite
- TypeScript
- React
- Tailwind CSS
- shadcn/ui

## Local development

Requires Node.js & npm ([install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating)).

```sh
npm install
npm run dev
```

## Build

```sh
npm run build
```

The built assets are copied into the Android app's `assets/` folder during the Gradle build and
served from `an app-controlled https:// virtual origin (WebViewAssetLoader)`.
