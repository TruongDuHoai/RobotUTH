package com.robotuth.v3.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Smooth auto-scroll logic
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    ChatLayout(
        uiState = uiState,
        listState = listState,
        onInputChange = { viewModel.onInputTextChange(it) },
        onSend = { viewModel.sendMessage() },
        onClearChat = { viewModel.clearChat() },
        onBack = onBack
    )
}

@Composable
private fun ChatLayout(
    uiState: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearChat: () -> Unit,
    onBack: () -> Unit
) {
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    // Keep the latest message in view: when a new message arrives AND when the
    // soft keyboard opens, so the last message is never hidden behind the IME.
    LaunchedEffect(uiState.messages.size, isKeyboardVisible) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White) // top band now blends with the card instead of leaving a light-blue strip under the status bar
            // No system-bar insets: MainActivityNew runs in IMERSIVE_STICKY full-screen and the robot has
            // no status/nav bars, so the chat fills the entire screen edge-to-edge. (The input bar still
            // lifts above the soft keyboard via its own WindowInsets.ime padding.)
            .padding(0.dp)
    ) {
        // Messages panel (.messages) — fills the space ABOVE the input bar so the
        // latest message stays visible above the soft keyboard. The input bar lives
        // at the BOTTOM (below), so it rises with the IME and the conversation
        // scrolls up to reveal new messages instead of being hidden behind the keyboard.
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(0.dp), // full-bleed: no rounded corners so the screen is used edge-to-edge (no corner gaps)
            color = Color.White,
            border = null
        ) {
            ChatMessageList(
                messages = uiState.messages,
                listState = listState,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Input bar (.input-bar) — now at the BOTTOM, directly above the keyboard.
        // Back arrow is the first child (left of ⌨).
        ChatInputBar(
            onBack = onBack,
            inputText = uiState.inputText,
            onInputChange = onInputChange,
            onSend = onSend,
            onClearChat = onClearChat
        )
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom), // Flow messages from the bottom to prevent layout cut-off
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp) // .messages { padding: 16px; }
    ) {
        items(messages) { message ->
            ChatMessageRow(message = message)
        }
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage) {
    val isUser = message.isUser
    val bubbleColor = if (isUser) Color(0xFF008689) else Color(0xFFEEF7F7) // .user { background: #008689; } .bot { background: #eef7f7; }
    val textColor = if (isUser) Color.White else Color(0xFF183536) // .bot { color: #183536; }
    val shape = RoundedCornerShape(15.dp) // .msg { border-radius: 15px; }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp), // .msg { margin-bottom: 10px; }
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
            Box(
                modifier = Modifier.fillMaxWidth(0.90f), // Use more horizontal space (90%) so the right gutter is trimmed without crowding
            contentAlignment = if (isUser) Alignment.TopEnd else Alignment.TopStart
        ) {
            Surface(
                color = bubbleColor,
                shape = shape
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 14.sp, // .msg { font-size: 14px; }
                    lineHeight = 19.6.sp, // .msg { line-height: 1.4; } -> 14 * 1.4 = 19.6sp
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp) // .msg { padding: 11px 13px; }
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    onBack: () -> Unit,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearChat: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime) // lift the input bar above the soft keyboard when it opens (works even if adjustResize doesn't shrink the window)
    ) {
        // border-top: 1px solid #e5eeee
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFE5EEEE))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 8.dp), // .input-bar { padding: 8px 12px; gap: 8px; }
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back arrow — moved from the screen-level header into the input bar (left of keyboard toggle).
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(42.dp) // width: 42px; height: 42px;
                    .background(Color(0xFFEEF7F7), RoundedCornerShape(10.dp)) // same container look as the other icon buttons
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF0F172A)
                )
            }

            // Toggle Keyboard Button (.toggle-btn)
            IconButton(
                onClick = {
                    if (isFocused) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    } else {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                },
                modifier = Modifier
                    .size(42.dp) // width: 42px; height: 42px;
                    .background(Color(0xFFEEF7F7), RoundedCornerShape(10.dp)) // background: #eef7f7; border-radius: 10px;
            ) {
                Text(
                    text = "⌨",
                    fontSize = 18.sp, // font-size: 18px;
                    color = Color(0xFF204243) // color: #204243;
                )
            }

            // Clear Chat Button (.clear-btn)
            IconButton(
                onClick = onClearChat,
                modifier = Modifier
                    .size(42.dp) // width: 42px; height: 42px;
                    .background(Color(0xFFEEF7F7), RoundedCornerShape(10.dp)) // same container look as keyboard toggle button
            ) {
                Text(
                    text = "🗑",
                    fontSize = 18.sp,
                    color = Color(0xFF991B1B) // red color indicating deletion action
                )
            }

            // Editable text input field matching .input-bar input
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp) // height: 42px
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused }
                    .border(1.dp, Color(0xFFD4E3E3), RoundedCornerShape(12.dp)) // border: 1px solid #d4e3e3; border-radius: 12px;
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .onKeyEvent { keyEvent ->
                        // Handle physical keyboard Enter send action
                        if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                textStyle = TextStyle(
                    color = Color(0xFF183536), // color: #183536;
                    fontSize = 14.sp // font-size: 14px;
                ),
                singleLine = true,
                cursorBrush = SolidColor(Color(0xFF008689)),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp), // padding: 0 16px;
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = "Nhập tin nhắn...",
                                color = Color(0xFF94A3B8),
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Send Button matching .input-bar .send
            Button(
                onClick = { onSend() },
                modifier = Modifier
                    .width(78.dp) // width: 78px;
                    .height(42.dp), // height: 42px;
                shape = RoundedCornerShape(12.dp), // border-radius: 12px;
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF008689), // background: #008689;
                    contentColor = Color.White // color: white;
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text(
                    text = "Gửi", // .send content
                    fontWeight = FontWeight.Bold, // font-weight: bold;
                    fontSize = 14.sp // font-size: 14px;
                )
            }
        }
    }
}
