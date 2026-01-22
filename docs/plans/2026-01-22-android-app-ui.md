# KopiaKt Android App UI Implementation Plan



**Goal:** Build a native Android app with Jetpack Compose UI to browse and restore Kopia backups from cloud/local storage.

**Architecture:** MVVM with clean architecture layers. UI Layer (Compose + Material 3), ViewModel Layer (StateFlow), Domain Layer (Use Cases + Repository interfaces), Data Layer (Repository implementations using existing KopiaKt backend). Hilt for DI, Navigation Compose for routing.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Hilt, Navigation Compose, Coroutines/Flow, KopiaKt modules (:core, :snapshot, :storage, :android)

---

## Phase 1: Project Setup & Foundation

### Task 1.1: Create Android App Module

**Files:**
- Create: `kopiaKt/app-android/build.gradle.kts`
- Modify: `kopiaKt/settings.gradle.kts` (add `:app-android` module)

**Step 1: Create app-android directory**

```bash
mkdir -p kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app
mkdir -p kopiaKt/app-android/src/main/res/values
mkdir -p kopiaKt/app-android/src/test/kotlin/org/kopiaKt/app
mkdir -p kopiaKt/app-android/src/androidTest/kotlin/org/kopiaKt/app
```

**Step 2: Create build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.kopiaKt.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.kopiaKt.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    // KopiaKt modules
    implementation(project(":core"))
    implementation(project(":snapshot"))
    implementation(project(":storage"))
    implementation(project(":android"))

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing.unit)
    androidTestImplementation(libs.bundles.testing.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
```

**Step 3: Add module to settings.gradle.kts**

Add `include(":app-android")` to the includes.

**Step 4: Verify build compiles**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add kopiaKt/app-android kopiaKt/settings.gradle.kts
git commit -m "feat(android): add app-android module with Compose setup"
```

---

### Task 1.2: Add Missing Gradle Dependencies

**Files:**
- Modify: `kopiaKt/gradle/libs.versions.toml`

**Step 1: Add Compose and Hilt dependencies to version catalog**

Add to `[versions]`:
```toml
composeBom = "2024.12.01"
hilt = "2.51.1"
navigationCompose = "2.8.5"
activityCompose = "1.9.3"
```

Add to `[libraries]`:
```toml
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
```

Add to `[plugins]`:
```toml
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt-android = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.21-1.0.27" }
```

**Step 2: Verify build**

Run: `cd kopiaKt && ./gradlew :app-android:dependencies`
Expected: All dependencies resolve

**Step 3: Commit**

```bash
git add kopiaKt/gradle/libs.versions.toml
git commit -m "build: add Compose, Hilt, Navigation dependencies"
```

---

### Task 1.3: Create Application Class with Hilt

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/KopiaApp.kt`
- Create: `kopiaKt/app-android/src/main/AndroidManifest.xml`

**Step 1: Create KopiaApp.kt**

```kotlin
package org.kopiaKt.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KopiaApp : Application()
```

**Step 2: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <application
        android:name=".KopiaApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.KopiaKt">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.KopiaKt">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 3: Create string resources**

Create `kopiaKt/app-android/src/main/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">KopiaKt</string>
</resources>
```

**Step 4: Create theme**

Create `kopiaKt/app-android/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.KopiaKt" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

**Step 5: Verify build**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add kopiaKt/app-android/src/main
git commit -m "feat(android): add Application class and manifest"
```

---

### Task 1.4: Create MainActivity with Compose

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/MainActivity.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/theme/Theme.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/theme/Color.kt`

**Step 1: Create Theme.kt**

```kotlin
package org.kopiaKt.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun KopiaKtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
```

**Step 2: Create Color.kt**

```kotlin
package org.kopiaKt.app.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
```

**Step 3: Create MainActivity.kt**

```kotlin
package org.kopiaKt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.kopiaKt.app.ui.theme.KopiaKtTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KopiaKtTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KopiaNavHost()
                }
            }
        }
    }
}
```

**Step 4: Verify build**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: Fails (KopiaNavHost not yet created - expected)

**Step 5: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app
git commit -m "feat(android): add MainActivity with Compose theme"
```

---

## Phase 2: Navigation & Core UI Structure

### Task 2.1: Create Navigation Graph

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/navigation/KopiaNavHost.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/navigation/Destinations.kt`

**Step 1: Create Destinations.kt**

```kotlin
package org.kopiaKt.app.navigation

import kotlinx.serialization.Serializable

sealed interface Destination {
    @Serializable
    data object Welcome : Destination

    @Serializable
    data object RepositoryConnect : Destination

    @Serializable
    data object SnapshotList : Destination

    @Serializable
    data class FileBrowser(
        val snapshotId: String,
        val path: String = ""
    ) : Destination

    @Serializable
    data class Restore(
        val snapshotId: String,
        val path: String
    ) : Destination

    @Serializable
    data object Settings : Destination
}
```

**Step 2: Create KopiaNavHost.kt**

```kotlin
package org.kopiaKt.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.kopiaKt.app.ui.screens.filebrowser.FileBrowserScreen
import org.kopiaKt.app.ui.screens.repositoryconnect.RepositoryConnectScreen
import org.kopiaKt.app.ui.screens.restore.RestoreScreen
import org.kopiaKt.app.ui.screens.settings.SettingsScreen
import org.kopiaKt.app.ui.screens.snapshotlist.SnapshotListScreen
import org.kopiaKt.app.ui.screens.welcome.WelcomeScreen

@Composable
fun KopiaNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Welcome,
        modifier = modifier
    ) {
        composable<Destination.Welcome> {
            WelcomeScreen(
                onConnectRepository = {
                    navController.navigate(Destination.RepositoryConnect)
                }
            )
        }

        composable<Destination.RepositoryConnect> {
            RepositoryConnectScreen(
                onConnected = {
                    navController.navigate(Destination.SnapshotList) {
                        popUpTo(Destination.Welcome) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<Destination.SnapshotList> {
            SnapshotListScreen(
                onSnapshotSelected = { snapshotId ->
                    navController.navigate(Destination.FileBrowser(snapshotId))
                },
                onSettings = {
                    navController.navigate(Destination.Settings)
                }
            )
        }

        composable<Destination.FileBrowser> { backStackEntry ->
            val args = backStackEntry.toRoute<Destination.FileBrowser>()
            FileBrowserScreen(
                snapshotId = args.snapshotId,
                initialPath = args.path,
                onNavigateToPath = { path ->
                    navController.navigate(Destination.FileBrowser(args.snapshotId, path))
                },
                onRestore = { path ->
                    navController.navigate(Destination.Restore(args.snapshotId, path))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<Destination.Restore> { backStackEntry ->
            val args = backStackEntry.toRoute<Destination.Restore>()
            RestoreScreen(
                snapshotId = args.snapshotId,
                sourcePath = args.path,
                onComplete = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }

        composable<Destination.Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onDisconnect = {
                    navController.navigate(Destination.Welcome) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
```

**Step 3: Verify files created**

Run: `ls -la kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/navigation/`
Expected: Destinations.kt, KopiaNavHost.kt

**Step 4: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/navigation
git commit -m "feat(android): add navigation graph with type-safe routes"
```

---

### Task 2.2: Create Placeholder Screen Composables

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/welcome/WelcomeScreen.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/repositoryconnect/RepositoryConnectScreen.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/snapshotlist/SnapshotListScreen.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/filebrowser/FileBrowserScreen.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/restore/RestoreScreen.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/settings/SettingsScreen.kt`

**Step 1: Create WelcomeScreen.kt**

```kotlin
package org.kopiaKt.app.ui.screens.welcome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    onConnectRepository: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "KopiaKt",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Browse and restore your Kopia backups",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onConnectRepository,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text("Connect to Repository")
        }
    }
}
```

**Step 2: Create RepositoryConnectScreen.kt**

```kotlin
package org.kopiaKt.app.ui.screens.repositoryconnect

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryConnectScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect Repository") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Repository connection UI - TODO")

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onConnected) {
                Text("Connect (placeholder)")
            }
        }
    }
}
```

**Step 3: Create SnapshotListScreen.kt**

```kotlin
package org.kopiaKt.app.ui.screens.snapshotlist

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotListScreen(
    onSnapshotSelected: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snapshots") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Snapshot list UI - TODO")

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { onSnapshotSelected("test-snapshot-id") }) {
                Text("Browse snapshot (placeholder)")
            }
        }
    }
}
```

**Step 4: Create FileBrowserScreen.kt**

```kotlin
package org.kopiaKt.app.ui.screens.filebrowser

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    snapshotId: String,
    initialPath: String,
    onNavigateToPath: (String) -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialPath.isEmpty()) "/" else initialPath) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onRestore(initialPath) }) {
                        Icon(Icons.Default.Download, "Restore")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("File browser UI - TODO")
            Text("Snapshot: $snapshotId")
            Text("Path: ${initialPath.ifEmpty { "/" }}")

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { onNavigateToPath("$initialPath/subfolder") }) {
                Text("Navigate to subfolder (placeholder)")
            }
        }
    }
}
```

**Step 5: Create RestoreScreen.kt**

```kotlin
package org.kopiaKt.app.ui.screens.restore

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RestoreScreen(
    snapshotId: String,
    sourcePath: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Restore", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Text("From: $sourcePath")

        Spacer(modifier = Modifier.height(32.dp))

        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))

        Spacer(modifier = Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
            Button(onClick = onComplete) {
                Text("Done (placeholder)")
            }
        }
    }
}
```

**Step 6: Create SettingsScreen.kt**

```kotlin
package org.kopiaKt.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Settings UI - TODO")

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Disconnect Repository")
            }
        }
    }
}
```

**Step 7: Verify build compiles**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens
git commit -m "feat(android): add placeholder screen composables"
```

---

## Phase 3: Domain Layer - Use Cases & Repository Interfaces

### Task 3.1: Create Domain Models

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/model/RepositoryConnection.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/model/SnapshotInfo.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/model/FileEntry.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/model/RestoreProgress.kt`

**Step 1: Create RepositoryConnection.kt**

```kotlin
package org.kopiaKt.app.domain.model

import java.time.Instant

data class RepositoryConnection(
    val id: String,
    val displayName: String,
    val storageType: StorageType,
    val connectionConfig: ConnectionConfig,
    val lastConnected: Instant? = null,
    val isConnected: Boolean = false
)

enum class StorageType {
    LOCAL_FILESYSTEM,
    S3,
    WEBDAV,
    SFTP,
    SAF
}

sealed interface ConnectionConfig {
    data class LocalFilesystem(val path: String) : ConnectionConfig

    data class S3(
        val bucket: String,
        val endpoint: String,
        val region: String,
        val accessKeyId: String
        // password not stored in domain model
    ) : ConnectionConfig

    data class WebDAV(
        val url: String,
        val username: String
    ) : ConnectionConfig

    data class SFTP(
        val host: String,
        val port: Int,
        val username: String,
        val path: String
    ) : ConnectionConfig

    data class SAF(
        val treeUri: String,
        val displayPath: String
    ) : ConnectionConfig
}
```

**Step 2: Create SnapshotInfo.kt**

```kotlin
package org.kopiaKt.app.domain.model

import java.time.Instant

data class SnapshotInfo(
    val id: String,
    val source: SourceInfo,
    val startTime: Instant,
    val endTime: Instant?,
    val description: String,
    val stats: SnapshotStats?,
    val isIncomplete: Boolean,
    val tags: Map<String, String>
)

data class SourceInfo(
    val host: String,
    val userName: String,
    val path: String
) {
    override fun toString(): String {
        return "$userName@$host:$path"
    }
}

data class SnapshotStats(
    val totalFileSize: Long,
    val totalFileCount: Int,
    val totalDirectoryCount: Int
)
```

**Step 3: Create FileEntry.kt**

```kotlin
package org.kopiaKt.app.domain.model

import java.time.Instant

data class FileEntry(
    val name: String,
    val type: FileEntryType,
    val size: Long,
    val modTime: Instant?,
    val permissions: Int,
    val objectId: String?
)

enum class FileEntryType {
    FILE,
    DIRECTORY,
    SYMLINK,
    UNKNOWN
}

data class DirectorySummary(
    val totalSize: Long,
    val fileCount: Long,
    val dirCount: Long
)
```

**Step 4: Create RestoreProgress.kt**

```kotlin
package org.kopiaKt.app.domain.model

data class RestoreProgress(
    val state: RestoreState,
    val totalFiles: Long,
    val restoredFiles: Long,
    val totalBytes: Long,
    val restoredBytes: Long,
    val currentFile: String?,
    val errorMessage: String?
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((restoredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

enum class RestoreState {
    IDLE,
    PREPARING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

**Step 5: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/model
git commit -m "feat(android): add domain models"
```

---

### Task 3.2: Create Repository Interfaces

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/repository/KopiaRepositoryManager.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/repository/SnapshotRepository.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/repository/CredentialRepository.kt`

**Step 1: Create KopiaRepositoryManager.kt**

```kotlin
package org.kopiaKt.app.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection

interface KopiaRepositoryManager {
    val connectionState: StateFlow<ConnectionState>

    suspend fun connect(config: ConnectionConfig, password: String): Result<RepositoryConnection>

    suspend fun disconnect()

    suspend fun getStoredConnections(): List<RepositoryConnection>

    suspend fun deleteStoredConnection(id: String)
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val connection: RepositoryConnection) : ConnectionState
    data class Error(val message: String) : ConnectionState
}
```

**Step 2: Create SnapshotRepository.kt**

```kotlin
package org.kopiaKt.app.domain.repository

import kotlinx.coroutines.flow.Flow
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SourceInfo

interface SnapshotRepository {
    suspend fun listSources(): List<SourceInfo>

    suspend fun listSnapshots(source: SourceInfo? = null): List<SnapshotInfo>

    suspend fun getSnapshot(snapshotId: String): SnapshotInfo?

    suspend fun browseDirectory(snapshotId: String, path: String): List<FileEntry>

    fun restore(
        snapshotId: String,
        sourcePath: String,
        destinationUri: String,
        options: RestoreOptions = RestoreOptions()
    ): Flow<RestoreProgress>

    fun cancelRestore()
}

data class RestoreOptions(
    val parallel: Int = 0,
    val incremental: Boolean = false,
    val overwriteExisting: Boolean = true
)
```

**Step 3: Create CredentialRepository.kt**

```kotlin
package org.kopiaKt.app.domain.repository

interface CredentialRepository {
    suspend fun storePassword(connectionId: String, password: String)

    suspend fun getPassword(connectionId: String): String?

    suspend fun deletePassword(connectionId: String)

    suspend fun hasPassword(connectionId: String): Boolean
}
```

**Step 4: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/repository
git commit -m "feat(android): add repository interfaces"
```

---

### Task 3.3: Create Use Cases

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/usecase/ConnectRepositoryUseCase.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/usecase/ListSnapshotsUseCase.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/usecase/BrowseSnapshotUseCase.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/usecase/RestoreFilesUseCase.kt`

**Step 1: Create ConnectRepositoryUseCase.kt**

```kotlin
package org.kopiaKt.app.domain.usecase

import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.app.domain.repository.CredentialRepository
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import javax.inject.Inject

class ConnectRepositoryUseCase @Inject constructor(
    private val repositoryManager: KopiaRepositoryManager,
    private val credentialRepository: CredentialRepository
) {
    suspend operator fun invoke(
        config: ConnectionConfig,
        password: String,
        savePassword: Boolean
    ): Result<RepositoryConnection> {
        val result = repositoryManager.connect(config, password)

        if (result.isSuccess && savePassword) {
            val connection = result.getOrThrow()
            credentialRepository.storePassword(connection.id, password)
        }

        return result
    }
}
```

**Step 2: Create ListSnapshotsUseCase.kt**

```kotlin
package org.kopiaKt.app.domain.usecase

import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Inject

class ListSnapshotsUseCase @Inject constructor(
    private val snapshotRepository: SnapshotRepository
) {
    suspend operator fun invoke(source: SourceInfo? = null): List<SnapshotInfo> {
        return snapshotRepository.listSnapshots(source)
            .sortedByDescending { it.startTime }
    }
}
```

**Step 3: Create BrowseSnapshotUseCase.kt**

```kotlin
package org.kopiaKt.app.domain.usecase

import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Inject

class BrowseSnapshotUseCase @Inject constructor(
    private val snapshotRepository: SnapshotRepository
) {
    suspend operator fun invoke(snapshotId: String, path: String): List<FileEntry> {
        return snapshotRepository.browseDirectory(snapshotId, path)
            .sortedWith(
                compareBy<FileEntry> { it.type != FileEntryType.DIRECTORY }
                    .thenBy { it.name.lowercase() }
            )
    }
}
```

**Step 4: Create RestoreFilesUseCase.kt**

```kotlin
package org.kopiaKt.app.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.repository.RestoreOptions
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Inject

class RestoreFilesUseCase @Inject constructor(
    private val snapshotRepository: SnapshotRepository
) {
    operator fun invoke(
        snapshotId: String,
        sourcePath: String,
        destinationUri: String,
        options: RestoreOptions = RestoreOptions()
    ): Flow<RestoreProgress> {
        return snapshotRepository.restore(snapshotId, sourcePath, destinationUri, options)
    }

    fun cancel() {
        snapshotRepository.cancelRestore()
    }
}
```

**Step 5: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/domain/usecase
git commit -m "feat(android): add use cases"
```

---

## Phase 4: Data Layer - Repository Implementations

### Task 4.1: Implement Credential Repository with Encrypted SharedPreferences

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/data/repository/EncryptedCredentialRepository.kt`

**Step 1: Add security dependency**

Add to `kopiaKt/app-android/build.gradle.kts` dependencies:
```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

**Step 2: Create EncryptedCredentialRepository.kt**

```kotlin
package org.kopiaKt.app.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.kopiaKt.app.domain.repository.CredentialRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptedCredentialRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialRepository {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "kopia_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun storePassword(connectionId: String, password: String) {
        sharedPreferences.edit()
            .putString(keyForConnection(connectionId), password)
            .apply()
    }

    override suspend fun getPassword(connectionId: String): String? {
        return sharedPreferences.getString(keyForConnection(connectionId), null)
    }

    override suspend fun deletePassword(connectionId: String) {
        sharedPreferences.edit()
            .remove(keyForConnection(connectionId))
            .apply()
    }

    override suspend fun hasPassword(connectionId: String): Boolean {
        return sharedPreferences.contains(keyForConnection(connectionId))
    }

    private fun keyForConnection(connectionId: String): String = "password_$connectionId"
}
```

**Step 3: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/data/repository/EncryptedCredentialRepository.kt
git add kopiaKt/app-android/build.gradle.kts
git commit -m "feat(android): implement encrypted credential storage"
```

---

### Task 4.2: Implement KopiaRepositoryManager

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/data/repository/KopiaRepositoryManagerImpl.kt`

**Step 1: Create KopiaRepositoryManagerImpl.kt**

```kotlin
package org.kopiaKt.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.kopiaKt.android.storage.SafBlobStorage
import org.kopiaKt.android.storage.SafOptions
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.app.domain.model.StorageType
import org.kopiaKt.app.domain.repository.ConnectionState
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.format.FormatBlobManager
import org.kopiaKt.core.repository.ClientOptions
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import org.kopiaKt.storage.s3.S3BlobStorage
import org.kopiaKt.storage.s3.S3Options
import org.kopiaKt.storage.sftp.SftpBlobStorage
import org.kopiaKt.storage.sftp.SftpOptions
import org.kopiaKt.storage.webdav.WebDavBlobStorage
import org.kopiaKt.storage.webdav.WebDavOptions
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.Path

@Singleton
class KopiaRepositoryManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : KopiaRepositoryManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var currentRepository: DirectRepository? = null

    override suspend fun connect(
        config: ConnectionConfig,
        password: String
    ): Result<RepositoryConnection> = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting

        try {
            val storage = createBlobStorage(config, password)
            val formatManager = FormatBlobManager(storage)
            val formatBlob = formatManager.getFormatBlob()
                ?: throw IllegalStateException("Repository format blob not found. Is this a valid Kopia repository?")

            val repository = DirectRepositoryImpl.open(
                storage = storage,
                formatBlob = formatBlob,
                password = password,
                clientOptions = ClientOptions.withDefaults(
                    description = "KopiaKt Android"
                )
            )

            currentRepository = repository

            val connection = RepositoryConnection(
                id = UUID.randomUUID().toString(),
                displayName = getDisplayName(config),
                storageType = getStorageType(config),
                connectionConfig = config,
                lastConnected = Instant.now(),
                isConnected = true
            )

            _connectionState.value = ConnectionState.Connected(connection)
            Result.success(connection)

        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        currentRepository?.close()
        currentRepository = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun getStoredConnections(): List<RepositoryConnection> {
        // TODO: Implement persistent storage of connections
        return emptyList()
    }

    override suspend fun deleteStoredConnection(id: String) {
        // TODO: Implement
    }

    fun getRepository(): DirectRepository? = currentRepository

    private suspend fun createBlobStorage(
        config: ConnectionConfig,
        password: String
    ): BlobStorage = when (config) {
        is ConnectionConfig.LocalFilesystem -> {
            FilesystemBlobStorage.open(Path(config.path))
        }

        is ConnectionConfig.S3 -> {
            S3BlobStorage.open(
                S3Options(
                    bucket = config.bucket,
                    endpoint = config.endpoint,
                    region = config.region,
                    accessKeyId = config.accessKeyId,
                    secretAccessKey = password
                )
            )
        }

        is ConnectionConfig.WebDAV -> {
            WebDavBlobStorage.open(
                WebDavOptions(
                    url = config.url,
                    username = config.username,
                    password = password
                )
            )
        }

        is ConnectionConfig.SFTP -> {
            SftpBlobStorage.open(
                SftpOptions(
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    password = password,
                    path = config.path
                )
            )
        }

        is ConnectionConfig.SAF -> {
            SafBlobStorage.create(
                context = context,
                treeUri = Uri.parse(config.treeUri),
                options = SafOptions(
                    treeUri = Uri.parse(config.treeUri),
                    readOnly = false
                )
            )
        }
    }

    private fun getDisplayName(config: ConnectionConfig): String = when (config) {
        is ConnectionConfig.LocalFilesystem -> config.path
        is ConnectionConfig.S3 -> "${config.bucket} (S3)"
        is ConnectionConfig.WebDAV -> config.url
        is ConnectionConfig.SFTP -> "${config.username}@${config.host}:${config.path}"
        is ConnectionConfig.SAF -> config.displayPath
    }

    private fun getStorageType(config: ConnectionConfig): StorageType = when (config) {
        is ConnectionConfig.LocalFilesystem -> StorageType.LOCAL_FILESYSTEM
        is ConnectionConfig.S3 -> StorageType.S3
        is ConnectionConfig.WebDAV -> StorageType.WEBDAV
        is ConnectionConfig.SFTP -> StorageType.SFTP
        is ConnectionConfig.SAF -> StorageType.SAF
    }
}
```

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/data/repository/KopiaRepositoryManagerImpl.kt
git commit -m "feat(android): implement KopiaRepositoryManager"
```

---

### Task 4.3: Implement SnapshotRepository

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/data/repository/SnapshotRepositoryImpl.kt`

**Step 1: Create SnapshotRepositoryImpl.kt**

```kotlin
package org.kopiaKt.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.repository.RestoreOptions
import org.kopiaKt.app.domain.repository.SnapshotRepository
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.snapshotfs.entryFromDirEntry
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.model.EntryType as SnapshotEntryType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotRepositoryImpl @Inject constructor(
    private val repositoryManager: KopiaRepositoryManagerImpl
) : SnapshotRepository {

    @Volatile
    private var restoreCancelled = false

    override suspend fun listSources(): List<SourceInfo> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        val manifests = repo.findManifests(mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT))

        manifests
            .mapNotNull { metadata ->
                val host = metadata.labels[ManifestLabels.HOST] ?: return@mapNotNull null
                val userName = metadata.labels[ManifestLabels.USERNAME] ?: return@mapNotNull null
                val path = metadata.labels[ManifestLabels.PATH] ?: return@mapNotNull null
                SourceInfo(host, userName, path)
            }
            .distinct()
    }

    override suspend fun listSnapshots(source: SourceInfo?): List<SnapshotInfo> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        val labels = mutableMapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        source?.let {
            labels[ManifestLabels.HOST] = it.host
            labels[ManifestLabels.USERNAME] = it.userName
            labels[ManifestLabels.PATH] = it.path
        }

        val manifests = repo.findManifests(labels)

        manifests.mapNotNull { metadata ->
            try {
                val (manifest, _) = repo.getManifest(
                    ManifestId(metadata.id),
                    SnapshotManifest.serializer()
                )
                manifest.toSnapshotInfo()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun getSnapshot(snapshotId: String): SnapshotInfo? = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        try {
            val (manifest, _) = repo.getManifest(
                ManifestId(snapshotId),
                SnapshotManifest.serializer()
            )
            manifest.toSnapshotInfo()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun browseDirectory(snapshotId: String, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        val (manifest, _) = repo.getManifest(
            ManifestId(snapshotId),
            SnapshotManifest.serializer()
        )

        var currentEntry = snapshotRoot(repo, manifest)

        if (path.isNotEmpty()) {
            val pathParts = path.trim('/').split('/')
            for (part in pathParts) {
                if (part.isEmpty()) continue
                val dir = currentEntry as? Directory
                    ?: throw IllegalArgumentException("$part is not a directory")
                currentEntry = dir.child(part)
                    ?: throw IllegalArgumentException("$part not found")
            }
        }

        val dir = currentEntry as? Directory
            ?: throw IllegalArgumentException("Path is not a directory")

        val entries = mutableListOf<FileEntry>()
        val iterator = dir.iterate()
        try {
            var entry = iterator.next()
            while (entry != null) {
                entries.add(entry.toFileEntry())
                entry = iterator.next()
            }
        } finally {
            iterator.close()
        }

        entries
    }

    override fun restore(
        snapshotId: String,
        sourcePath: String,
        destinationUri: String,
        options: RestoreOptions
    ): Flow<RestoreProgress> = callbackFlow {
        restoreCancelled = false

        send(RestoreProgress(
            state = RestoreState.PREPARING,
            totalFiles = 0,
            restoredFiles = 0,
            totalBytes = 0,
            restoredBytes = 0,
            currentFile = null,
            errorMessage = null
        ))

        try {
            // TODO: Implement actual restore using SnapshotRestorer
            // This is a placeholder that needs to be connected to the existing restore infrastructure

            send(RestoreProgress(
                state = RestoreState.COMPLETED,
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                currentFile = null,
                errorMessage = null
            ))
        } catch (e: Exception) {
            send(RestoreProgress(
                state = RestoreState.FAILED,
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                currentFile = null,
                errorMessage = e.message
            ))
        }

        awaitClose { restoreCancelled = true }
    }

    override fun cancelRestore() {
        restoreCancelled = true
    }

    private fun SnapshotManifest.toSnapshotInfo(): SnapshotInfo {
        return SnapshotInfo(
            id = id,
            source = SourceInfo(source.host, source.userName, source.path),
            startTime = startTime,
            endTime = endTime,
            description = description,
            stats = stats?.let {
                SnapshotStats(
                    totalFileSize = it.totalFileSize,
                    totalFileCount = it.totalFileCount,
                    totalDirectoryCount = it.totalDirectoryCount
                )
            },
            isIncomplete = incompleteReason != null,
            tags = tags
        )
    }

    private fun org.kopiaKt.snapshot.fs.Entry.toFileEntry(): FileEntry {
        return FileEntry(
            name = name,
            type = when (type) {
                org.kopiaKt.snapshot.fs.EntryType.FILE -> FileEntryType.FILE
                org.kopiaKt.snapshot.fs.EntryType.DIRECTORY -> FileEntryType.DIRECTORY
                org.kopiaKt.snapshot.fs.EntryType.SYMLINK -> FileEntryType.SYMLINK
                else -> FileEntryType.UNKNOWN
            },
            size = size,
            modTime = modTime,
            permissions = mode,
            objectId = null // Not exposed directly
        )
    }
}
```

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/data/repository/SnapshotRepositoryImpl.kt
git commit -m "feat(android): implement SnapshotRepository"
```

---

### Task 4.4: Create Hilt DI Module

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/di/AppModule.kt`

**Step 1: Create AppModule.kt**

```kotlin
package org.kopiaKt.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.kopiaKt.app.data.repository.EncryptedCredentialRepository
import org.kopiaKt.app.data.repository.KopiaRepositoryManagerImpl
import org.kopiaKt.app.data.repository.SnapshotRepositoryImpl
import org.kopiaKt.app.domain.repository.CredentialRepository
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindKopiaRepositoryManager(
        impl: KopiaRepositoryManagerImpl
    ): KopiaRepositoryManager

    @Binds
    @Singleton
    abstract fun bindSnapshotRepository(
        impl: SnapshotRepositoryImpl
    ): SnapshotRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        impl: EncryptedCredentialRepository
    ): CredentialRepository
}
```

**Step 2: Verify build**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/di
git commit -m "feat(android): add Hilt DI module"
```

---

## Phase 5: ViewModels

### Task 5.1: Create SnapshotListViewModel

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/snapshotlist/SnapshotListViewModel.kt`

**Step 1: Create SnapshotListViewModel.kt**

```kotlin
package org.kopiaKt.app.ui.screens.snapshotlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.usecase.ListSnapshotsUseCase
import javax.inject.Inject

@HiltViewModel
class SnapshotListViewModel @Inject constructor(
    private val listSnapshotsUseCase: ListSnapshotsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SnapshotListUiState())
    val uiState: StateFlow<SnapshotListUiState> = _uiState.asStateFlow()

    init {
        loadSnapshots()
    }

    fun loadSnapshots(source: SourceInfo? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val snapshots = listSnapshotsUseCase(source)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        snapshots = snapshots,
                        selectedSource = source
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load snapshots"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadSnapshots(_uiState.value.selectedSource)
    }
}

data class SnapshotListUiState(
    val isLoading: Boolean = false,
    val snapshots: List<SnapshotInfo> = emptyList(),
    val selectedSource: SourceInfo? = null,
    val error: String? = null
)
```

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/snapshotlist/SnapshotListViewModel.kt
git commit -m "feat(android): add SnapshotListViewModel"
```

---

### Task 5.2: Create FileBrowserViewModel

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/filebrowser/FileBrowserViewModel.kt`

**Step 1: Create FileBrowserViewModel.kt**

```kotlin
package org.kopiaKt.app.ui.screens.filebrowser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.usecase.BrowseSnapshotUseCase
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val browseSnapshotUseCase: BrowseSnapshotUseCase
) : ViewModel() {

    private val snapshotId: String = savedStateHandle["snapshotId"] ?: ""

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    fun loadDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val entries = browseSnapshotUseCase(snapshotId, path)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentPath = path,
                        entries = entries,
                        pathHistory = buildPathHistory(path)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load directory"
                    )
                }
            }
        }
    }

    private fun buildPathHistory(path: String): List<PathSegment> {
        if (path.isEmpty()) {
            return listOf(PathSegment("/", ""))
        }

        val segments = mutableListOf(PathSegment("/", ""))
        var currentPath = ""

        for (part in path.trim('/').split('/')) {
            if (part.isEmpty()) continue
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            segments.add(PathSegment(part, currentPath))
        }

        return segments
    }
}

data class FileBrowserUiState(
    val isLoading: Boolean = false,
    val currentPath: String = "",
    val entries: List<FileEntry> = emptyList(),
    val pathHistory: List<PathSegment> = listOf(PathSegment("/", "")),
    val error: String? = null
)

data class PathSegment(
    val name: String,
    val fullPath: String
)
```

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/filebrowser/FileBrowserViewModel.kt
git commit -m "feat(android): add FileBrowserViewModel"
```

---

### Task 5.3: Create RestoreViewModel

**Files:**
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/restore/RestoreViewModel.kt`

**Step 1: Create RestoreViewModel.kt**

```kotlin
package org.kopiaKt.app.ui.screens.restore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.usecase.RestoreFilesUseCase
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val restoreFilesUseCase: RestoreFilesUseCase
) : ViewModel() {

    private val snapshotId: String = savedStateHandle["snapshotId"] ?: ""
    private val sourcePath: String = savedStateHandle["sourcePath"] ?: ""

    private val _uiState = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    fun startRestore(destinationUri: String) {
        viewModelScope.launch {
            restoreFilesUseCase(snapshotId, sourcePath, destinationUri)
                .collect { progress ->
                    _uiState.update {
                        it.copy(progress = progress)
                    }
                }
        }
    }

    fun cancelRestore() {
        restoreFilesUseCase.cancel()
    }
}

data class RestoreUiState(
    val progress: RestoreProgress = RestoreProgress(
        state = RestoreState.IDLE,
        totalFiles = 0,
        restoredFiles = 0,
        totalBytes = 0,
        restoredBytes = 0,
        currentFile = null,
        errorMessage = null
    )
)
```

**Step 2: Verify build**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/restore/RestoreViewModel.kt
git commit -m "feat(android): add RestoreViewModel"
```

---

## Phase 6: Complete UI Screens

### Task 6.1: Implement SnapshotListScreen with ViewModel

**Files:**
- Modify: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/snapshotlist/SnapshotListScreen.kt`

**Step 1: Update SnapshotListScreen.kt with full implementation**

Replace placeholder with full implementation using the ViewModel, LazyColumn for snapshot list, pull-to-refresh, loading states, and proper formatting.

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/snapshotlist/SnapshotListScreen.kt
git commit -m "feat(android): implement SnapshotListScreen with ViewModel"
```

---

### Task 6.2: Implement FileBrowserScreen with ViewModel

**Files:**
- Modify: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/filebrowser/FileBrowserScreen.kt`

**Step 1: Update FileBrowserScreen.kt with full implementation**

Implement file list with icons, breadcrumb navigation, size formatting, date formatting, folder navigation, and restore button.

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/filebrowser/FileBrowserScreen.kt
git commit -m "feat(android): implement FileBrowserScreen with ViewModel"
```

---

### Task 6.3: Implement RepositoryConnectScreen

**Files:**
- Modify: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/repositoryconnect/RepositoryConnectScreen.kt`
- Create: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/repositoryconnect/RepositoryConnectViewModel.kt`

**Step 1: Create RepositoryConnectViewModel.kt**

Implement ViewModel with storage type selection, form state for each type (S3, WebDAV, SFTP, SAF), validation, and connection logic.

**Step 2: Update RepositoryConnectScreen.kt**

Implement tabbed interface for storage types, form fields for each type, password field, "remember password" checkbox, and connect button.

**Step 3: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/repositoryconnect
git commit -m "feat(android): implement RepositoryConnectScreen"
```

---

### Task 6.4: Implement RestoreScreen with Progress

**Files:**
- Modify: `kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/restore/RestoreScreen.kt`

**Step 1: Update RestoreScreen.kt**

Implement destination picker using SAF, progress display with file count and byte count, cancel button, and completion/error states.

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/kotlin/org/kopiaKt/app/ui/screens/restore/RestoreScreen.kt
git commit -m "feat(android): implement RestoreScreen with progress"
```

---

## Phase 7: Testing & Polish

### Task 7.1: Add Unit Tests for ViewModels

**Files:**
- Create: `kopiaKt/app-android/src/test/kotlin/org/kopiaKt/app/ui/screens/snapshotlist/SnapshotListViewModelTest.kt`
- Create: `kopiaKt/app-android/src/test/kotlin/org/kopiaKt/app/ui/screens/filebrowser/FileBrowserViewModelTest.kt`

**Step 1: Create SnapshotListViewModelTest.kt**

Test loading states, error handling, refresh functionality.

**Step 2: Create FileBrowserViewModelTest.kt**

Test directory loading, path history building, error states.

**Step 3: Verify tests pass**

Run: `cd kopiaKt && ./gradlew :app-android:testDebugUnitTest`
Expected: All tests pass

**Step 4: Commit**

```bash
git add kopiaKt/app-android/src/test
git commit -m "test(android): add ViewModel unit tests"
```

---

### Task 7.2: Add Launcher Icons

**Files:**
- Create: `kopiaKt/app-android/src/main/res/mipmap-*/ic_launcher.webp`
- Create: `kopiaKt/app-android/src/main/res/mipmap-*/ic_launcher_round.webp`

**Step 1: Create placeholder icons**

Use Android Studio Image Asset Studio or generate basic icons.

**Step 2: Commit**

```bash
git add kopiaKt/app-android/src/main/res/mipmap-*
git commit -m "feat(android): add launcher icons"
```

---

### Task 7.3: Add ProGuard Rules

**Files:**
- Create: `kopiaKt/app-android/proguard-rules.pro`

**Step 1: Create proguard-rules.pro**

```proguard
# Keep KopiaKt model classes for serialization
-keep class org.kopiaKt.snapshot.model.** { *; }
-keep class org.kopiaKt.core.format.** { *; }
-keep class org.kopiaKt.core.manifest.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Bouncycastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
```

**Step 2: Commit**

```bash
git add kopiaKt/app-android/proguard-rules.pro
git commit -m "build(android): add ProGuard rules"
```

---

### Task 7.4: Final Build and Install Test

**Step 1: Build release APK**

Run: `cd kopiaKt && ./gradlew :app-android:assembleRelease`
Expected: BUILD SUCCESSFUL

**Step 2: Build debug APK**

Run: `cd kopiaKt && ./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Run all tests**

Run: `cd kopiaKt && ./gradlew :app-android:test`
Expected: All tests pass

**Step 4: Create final commit**

```bash
git add -A
git commit -m "feat(android): complete initial Android app implementation"
```

---

## Summary

This plan implements a fully functional Android app with:

1. **Phase 1**: Project setup with Compose, Hilt, and Navigation
2. **Phase 2**: Navigation graph and placeholder screens
3. **Phase 3**: Domain layer with models, interfaces, and use cases
4. **Phase 4**: Data layer with repository implementations
5. **Phase 5**: ViewModels with StateFlow for reactive UI
6. **Phase 6**: Complete UI screens with full functionality
7. **Phase 7**: Testing and polish

The app uses the existing KopiaKt backend modules (`:core`, `:snapshot`, `:storage`, `:android`) and adds a native Compose UI layer on top.

**Key Features:**
- Connect to repositories (S3, WebDAV, SFTP, SAF)
- Browse snapshots by source
- Navigate directory trees
- Restore files to device storage
- Encrypted credential storage
- Material 3 design with dynamic colors
