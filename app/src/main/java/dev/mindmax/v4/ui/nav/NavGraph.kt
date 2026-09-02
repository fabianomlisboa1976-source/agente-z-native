package dev.mindmax.v4.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mindmax.v4.ui.agents.AgentsScreen
import dev.mindmax.v4.ui.audit.AuditScreen
import dev.mindmax.v4.ui.chat.ChatScreen
import dev.mindmax.v4.ui.settings.SettingsScreen

sealed class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Chat : Destination("chat", "Chat", Icons.Filled.Chat)
    data object Agents : Destination("agents", "Agentes", Icons.Filled.SmartToy)
    data object Audit : Destination("audit", "Auditoria", Icons.Filled.Storage)
    data object Settings : Destination("settings", "Config", Icons.Filled.Settings)
}

private val destinations = listOf(
    Destination.Chat,
    Destination.Agents,
    Destination.Audit,
    Destination.Settings,
)

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destination.Chat.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentRoute == destination.route ||
                        backStackEntry?.destination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Chat.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Chat.route) { ChatScreen() }
            composable(Destination.Agents.route) { AgentsScreen() }
            composable(Destination.Audit.route) { AuditScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
