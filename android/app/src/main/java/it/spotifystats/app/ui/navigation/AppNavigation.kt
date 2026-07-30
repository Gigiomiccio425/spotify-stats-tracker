package it.spotifystats.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.spotifystats.app.ui.detail.ArtistDetailScreen
import it.spotifystats.app.ui.detail.TrackDetailScreen
import it.spotifystats.app.ui.history.HistoryScreen
import it.spotifystats.app.ui.home.HomeScreen
import it.spotifystats.app.ui.recap.RecapDetailScreen
import it.spotifystats.app.ui.recap.RecapListScreen
import it.spotifystats.app.ui.settings.SettingsScreen
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.top.TopScreen

private sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Destination("home", "Home", Icons.Filled.Home)
    data object Top : Destination("top", "Classifiche", Icons.Filled.BarChart)
    data object History : Destination("history", "Storico", Icons.Filled.History)
    data object Recaps : Destination("recaps", "Recap", Icons.Filled.CalendarMonth)
    data object Settings : Destination("settings", "Profilo", Icons.Filled.Person)
}

private val BOTTOM_ITEMS = listOf(
    Destination.Home,
    Destination.Top,
    Destination.History,
    Destination.Recaps,
    Destination.Settings,
)

@Composable
fun AppNavigation(onLoggedOut: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Le schermate di dettaglio occupano tutto lo schermo: la barra sparisce
    // per non rubare spazio a una pagina in cui non serve.
    val showBottomBar = currentRoute in BOTTOM_ITEMS.map { it.route }

    Scaffold(
        containerColor = Background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Background) {
                    BOTTOM_ITEMS.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = { navController.navigateToTab(item.route) },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Background,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    onTrackClick = { navController.navigate("track/$it") },
                    onArtistClick = { navController.navigate("artist/$it") },
                )
            }
            composable(Destination.Top.route) {
                TopScreen(
                    onTrackClick = { navController.navigate("track/$it") },
                    onArtistClick = { navController.navigate("artist/$it") },
                )
            }
            composable(Destination.History.route) {
                HistoryScreen(onTrackClick = { navController.navigate("track/$it") })
            }
            composable(Destination.Recaps.route) {
                RecapListScreen(onOpenRecap = { type, key -> navController.navigate("recap/$type/$key") })
            }
            composable(Destination.Settings.route) {
                SettingsScreen(onLoggedOut = onLoggedOut)
            }

            composable("track/{trackId}") { entry ->
                TrackDetailScreen(
                    trackId = entry.arguments?.getString("trackId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("artist/{artistId}") { entry ->
                ArtistDetailScreen(
                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onTrackClick = { navController.navigate("track/$it") },
                )
            }
            composable("recap/{type}/{key}") { entry ->
                RecapDetailScreen(
                    type = entry.arguments?.getString("type").orEmpty(),
                    periodKey = entry.arguments?.getString("key").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Cambio scheda che non accumula cronologia: senza `popUpTo`, saltare fra le
 * cinque sezioni riempirebbe il back stack e il tasto indietro obbligherebbe a
 * ripercorrere ogni passaggio.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
