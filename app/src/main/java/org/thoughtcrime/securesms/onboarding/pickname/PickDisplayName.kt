package org.thoughtcrime.securesms.onboarding.pickname

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.PreviewTheme
import org.thoughtcrime.securesms.ui.base
import org.thoughtcrime.securesms.ui.components.OutlineButton
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.contentDescription
import org.thoughtcrime.securesms.ui.h4

@Preview
@Composable
fun PreviewDisplayName() {
    PreviewTheme {
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
        Text(stringResource(state.title), style = h4)
        Text(
            stringResource(state.description),
            style = base,
            modifier = Modifier.padding(bottom = 12.dp))

        SessionOutlinedTextField(
            text = state.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .contentDescription(R.string.AccessibilityId_enter_display_name),
            placeholder = stringResource(R.string.displayNameEnter),
            onChange = onChange,
            onContinue = onContinue,
            error = state.error?.let { stringResource(it) }
        )

        Spacer(modifier = Modifier.weight(2f))

        OutlineButton(
            stringResource(R.string.continue_2),
            modifier = Modifier
                .contentDescription(R.string.AccessibilityId_continue)
                .align(Alignment.CenterHorizontally)
                .width(262.dp),
            onClick = onContinue,
        )
    }
}
