package frb.axeron.manager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import frb.axeron.manager.R
import frb.axeron.manager.data.AxshLauncher
import frb.axeron.manager.ui.theme.cardContainerColor
import frb.axeron.manager.ui.viewmodel.AppsViewModel

@Composable
fun TerminalAppDialog(
    app: AppsViewModel.AppInfo,
    showRootless: Boolean,
    initialRootless: Boolean,
    onRootlessChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    // Aplikasi dengan libtermux.so: tampilkan perintah axsh + tombol Launch.
    // Aplikasi lain: hanya opsi Rootless debugging (tanpa perintah/Launch).
    showTerminal: Boolean = true,
    onLaunch: () -> Unit = {}
) {
    var rootless by remember(app.packageName) { mutableStateOf(initialRootless) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = cardContainerColor(),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.privilege_app_settings),
                    style = MaterialTheme.typography.titleLarge
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AppIcon(
                        packageInfo = app.packageInfo,
                        contentDescription = app.label,
                        modifier = Modifier.size(56.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (showTerminal) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = AxshLauncher.RUN_COMMAND,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (showRootless) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.privilege_rootless_debugging),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.privilege_rootless_debugging_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rootless,
                            onCheckedChange = {
                                rootless = it
                                onRootlessChange(it)
                            }
                        )
                    }
                }

                // Explanation goes below the controls, right above the buttons
                // (same layout as the WebUI shortcut dialog).
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showTerminal) {
                        Text(
                            text = stringResource(R.string.privilege_terminal_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!showRootless) {
                        Text(
                            text = stringResource(R.string.privilege_terminal_mode_adb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.close))
                    }
                    if (showTerminal) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onLaunch) {
                            Text(stringResource(R.string.privilege_launch))
                        }
                    }
                }
            }
        }
    }
}
