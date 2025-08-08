# Kopia Android App Development Plan

## Overview
An Android application that embeds Kopia server, provides a WebView interface to the Kopia Web UI, and allows access to the local phone filesystem for backup and restore operations.

## Development Phases

### Phase 1: Project Setup and Environment Configuration
- [x] Create Android project structure
- [ ] Configure build.gradle for NDK support
- [ ] Set up necessary permissions in AndroidManifest.xml
- [ ] Create basic activity layouts

### Phase 2: Kopia Integration
- [ ] Download and prepare Kopia binary for ARM64
- [ ] Implement binary extraction to app's internal storage
- [ ] Create service for running Kopia server
- [ ] Implement WebView to display Kopia Web UI

### Phase 3: Storage Access Implementation
- [ ] Implement Storage Access Framework (SAF) for repository selection
- [ ] Create utilities for copying between SAF and internal storage
- [ ] Implement repository connection functionality
- [ ] Add snapshot restore capabilities

### Phase 4: User Interface and Experience
- [ ] Design main activity layout
- [ ] Create settings screen for configuration options
- [ ] Implement progress indicators for operations
- [ ] Add error handling and user notifications

### Phase 5: Testing and Optimization
- [ ] Test on various Android versions (8-13)
- [ ] Optimize performance and reduce resource usage
- [ ] Handle edge cases and error conditions
- [ ] Validate all user workflows

### Phase 6: Packaging and Distribution
- [ ] Prepare release build configuration
- [ ] Sign APK with appropriate keystore
- [ ] Create documentation for users
- [ ] Publish to desired distribution channels

## Technical Notes
- Kopia binary must be statically linked for Android compatibility
- WebView requires cleartext traffic permissions for localhost
- SAF is required for accessing user files on modern Android
- Internal app storage will be used for temporary files and repository cache
