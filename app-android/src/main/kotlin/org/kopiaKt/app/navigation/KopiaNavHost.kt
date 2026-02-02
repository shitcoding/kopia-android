package org.kopiaKt.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.kopiaKt.app.bridge.FlutterScreen
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
                },
                onFlutterDemo = {
                    navController.navigate(Destination.FlutterHome)
                }
            )
        }

        composable<Destination.FlutterHome> {
            FlutterScreen(modifier = Modifier.fillMaxSize())
        }
    }
}
