package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.musicbrain.MusicBrainSettings
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicBrainSettings(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(MusicBrainSettings.Settings()) }

    LaunchedEffect(context) {
        settings = MusicBrainSettings.read(context)
    }

    fun update(transform: (MusicBrainSettings.Settings) -> MusicBrainSettings.Settings) {
        val next = transform(settings)
        settings = next
        scope.launch { MusicBrainSettings.write(context, next) }
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        Material3SettingsGroup(
            title = stringResource(R.string.musicbrain_options),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.musicbrain_enabled)) },
                    description = { Text(stringResource(R.string.musicbrain_enabled_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { update { it.copy(enabled = it.enabled.not()) } },
                            thumbContent = { Icon(painterResource(if (settings.enabled) R.drawable.check else R.drawable.close), null) },
                            colors = SwitchDefaults.colors()
                        )
                    }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.radio),
                    title = { Text(stringResource(R.string.musicbrain_endless_radio)) },
                    description = { Text(stringResource(R.string.musicbrain_endless_radio_desc)) },
                    enabled = settings.enabled,
                    trailingContent = {
                        Switch(
                            checked = settings.endlessRadio,
                            onCheckedChange = { update { it.copy(endlessRadio = it.endlessRadio.not()) } },
                            thumbContent = { Icon(painterResource(if (settings.endlessRadio) R.drawable.check else R.drawable.close), null) },
                            enabled = settings.enabled
                        )
                    }
                )
            )
        )
        Spacer(Modifier.height(16.dp))
        Material3SettingsGroup(
            title = stringResource(R.string.musicbrain_about),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text(stringResource(R.string.musicbrain_about_title)) },
                    description = { Text(stringResource(R.string.musicbrain_about_desc)) }
                )
            )
        )
    }

    androidx.compose.material3.TopAppBar(
        title = { Text(stringResource(R.string.musicbrain_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        }
    )
}
