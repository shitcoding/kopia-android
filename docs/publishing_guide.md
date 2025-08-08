# Kopia Android App Publishing Guide

This guide provides instructions for publishing the Kopia Android app to various distribution channels.

## Prerequisites

1. Completed app development and testing
2. Configured signing keys in `keystore.properties`
3. Prepared app store assets (icons, screenshots, descriptions)

## Building a Release APK

To build a signed release APK:

```bash
./gradlew assembleRelease
```

The signed APK will be located at:
`app/build/outputs/apk/release/app-release.apk`

## Publishing to Google Play Store

1. Create a Google Play Developer account if you don't have one
2. Create a new application in the Google Play Console
3. Complete the store listing:
   - App name: Kopia Android Backup
   - Short description: Secure backup app for Android using Kopia
   - Full description: Kopia Android Backup is a mobile client for the Kopia backup tool, allowing you to create, manage, and restore backups directly from your Android device.
   - Screenshots: Include screenshots from various Android devices
   - Feature graphic: Use the Kopia logo with appropriate branding
4. Set up content rating by completing the questionnaire
5. Set pricing and distribution (free/paid)
6. Upload the signed APK or create an Android App Bundle:
   ```bash
   ./gradlew bundleRelease
   ```
7. Submit for review

## Publishing to F-Droid

1. Create a metadata file in the F-Droid format
2. Submit your repository to F-Droid's inclusion process
3. Ensure your app meets F-Droid's free software requirements
4. Provide build instructions in the required format

## Publishing to GitHub Releases

1. Create a new release on GitHub
2. Upload the signed APK
3. Write release notes detailing features and changes
4. Publish the release

## Direct APK Distribution

For direct distribution:

1. Host the APK on your website or file hosting service
2. Provide clear installation instructions
3. Ensure users enable "Install from Unknown Sources" in their device settings
4. Consider using a QR code for easy download

## Updating the App

When releasing updates:

1. Increment `versionCode` and `versionName` in `build.gradle`
2. Build a new signed APK
3. Submit the update to all distribution channels
4. Provide clear release notes detailing changes

## Security Considerations

- Keep signing keys secure and never commit them to version control
- Use the keystore.properties file approach to separate sensitive information
- Consider enabling Play App Signing for additional security
- Implement proper certificate pinning for network communications
