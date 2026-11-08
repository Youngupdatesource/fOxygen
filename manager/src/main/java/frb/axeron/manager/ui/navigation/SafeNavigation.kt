package frb.axeron.manager.ui.navigation

import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.spec.Direction

/**
 * Opens [direction] at most once on top of the back stack.
 *
 * A plain navigate() pushes a new entry for every tap, so tapping an item twice before the
 * transition has finished opened the same screen twice (Appearance, Settings Editor, ...).
 * With launchSingleTop the second tap finds the screen already on top and reuses it.
 */
fun DestinationsNavigator.navigateOnce(direction: Direction) {
    navigate(direction) {
        launchSingleTop = true
    }
}
