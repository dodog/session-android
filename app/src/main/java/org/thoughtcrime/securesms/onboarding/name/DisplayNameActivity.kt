package org.thoughtcrime.securesms.onboarding.name

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ContentAlpha
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivityDisplayNameBinding
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.onboarding.PNModeActivity
import org.thoughtcrime.securesms.ui.AppTheme
import org.thoughtcrime.securesms.ui.OutlineButton
import org.thoughtcrime.securesms.ui.PreviewTheme
import org.thoughtcrime.securesms.ui.base
import org.thoughtcrime.securesms.ui.baseBold
import org.thoughtcrime.securesms.ui.colorDestructive
import org.thoughtcrime.securesms.util.push
import org.thoughtcrime.securesms.util.setUpActionBarSessionLogo

@AndroidEntryPoint
class DisplayNameActivity : BaseActionBarActivity() {
    private val viewModel: DisplayNameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpActionBarSessionLogo()

        ComposeView(this)
            .apply { setContent { DisplayNameScreen(viewModel) } }
            .let(::setContentView)

        lifecycleScope.launch {
            viewModel.eventFlow.collect {
//                val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
//                inputMethodManager.hideSoftInputFromWindow(content.displayNameEditText.windowToken, 0)

                Intent(this@DisplayNameActivity, PNModeActivity::class.java).also(::push)
            }
        }
    }

    @Composable
    private fun DisplayNameScreen(viewModel: DisplayNameViewModel) {
        val state = viewModel.stateFlow.collectAsState()

        AppTheme {
            DisplayName(state.value, viewModel::onChange, viewModel::onContinue)
        }
    }

    @Preview
    @Composable
    fun PreviewDisplayName() {
        PreviewTheme(R.style.Classic_Dark) {
            DisplayName(State())
        }
    }

    @Composable
    fun DisplayName(state: State, onChange: (String) -> Unit = {}, onContinue: () -> Unit = {}) {
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .padding(horizontal = 50.dp)
                .padding(bottom = 12.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(stringResource(state.title), style = MaterialTheme.typography.h4)
            Text(
                stringResource(state.description),
                style = MaterialTheme.typography.base,
                modifier = Modifier.padding(bottom = 12.dp))

            OutlinedTextField(
                value = state.displayName,
                onValueChange = { onChange(it) },
                placeholder = { Text(stringResource(R.string.activity_display_name_edit_text_hint)) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = state.error?.let { colorDestructive } ?:
                        LocalContentColor.current.copy(LocalContentAlpha.current),
                    focusedBorderColor = Color(0xff414141),
                    unfocusedBorderColor = Color(0xff414141),
                    cursorColor = LocalContentColor.current,
                    placeholderColor = state.error?.let { colorDestructive }
                        ?: MaterialTheme.colors.onSurface.copy(ContentAlpha.medium)
                ),
                singleLine = true,
                keyboardActions = KeyboardActions(
                    onDone = { viewModel.onContinue() },
                    onGo = { viewModel.onContinue() },
                    onSearch = { viewModel.onContinue() },
                    onSend = { viewModel.onContinue() },
                ),
                isError = state.error != null,
                shape = RoundedCornerShape(12.dp)
            )

            state.error?.let {
                Text(stringResource(it), style = MaterialTheme.typography.baseBold, color = MaterialTheme.colors.error)
            }

            Spacer(modifier = Modifier.weight(2f))

            OutlineButton(
                stringResource(R.string.continue_2),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(262.dp)
            ) { onContinue() }
        }
    }
}