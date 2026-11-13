package cz.ondra.absplayer.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import cz.ondra.absplayer.App
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Text field for the whole app. On the TV, moving onto it with the remote does not open the
 * keyboard – only OK on the field does (and moving away closes the editing again).
 */
@Composable
fun AbsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val tv = (LocalContext.current.applicationContext as App).isTv
    var editing by remember { mutableStateOf(!tv) }
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .onFocusChanged { if (tv && !it.isFocused) editing = false }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    !tv || editing || !ok -> false
                    e.type == KeyEventType.KeyUp -> { editing = true; keyboard?.show(); true }
                    else -> true // the key down too, so the field itself does not open the keyboard
                }
            },
    )
}

/**
 * Small field (40 dp, value centred) for the pairing form: IP, port, code. Same TV behaviour as
 * [AbsTextField]: the keyboard only after OK.
 */
@Composable
fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val tv = (LocalContext.current.applicationContext as App).isTv
    var editing by remember { mutableStateOf(!tv) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        textStyle = androidx.compose.ui.text.TextStyle(color = C.Text, fontSize = 14.sp, textAlign = TextAlign.Center),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(C.Accent),
        modifier = modifier
            .height(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (tv && !it.isFocused) editing = false
            }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    !tv || editing || !ok -> false
                    e.type == KeyEventType.KeyUp -> { editing = true; keyboard?.show(); true }
                    else -> true
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(if (focused && tv && !editing) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .border(if (focused) 2.dp else 1.dp, if (focused) C.Accent else C.Outline, shape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) Text(placeholder, color = C.Muted, fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.Center)
                inner()
            }
        },
    )
}
