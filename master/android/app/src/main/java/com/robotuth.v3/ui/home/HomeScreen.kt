package com.robotuth.v3.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToChat: () -> Unit,
    onNavigateToConversation: () -> Unit,
    onNavigateToNavigation: () -> Unit,
    onExitApp: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE6F4F3)),
        contentAlignment = Alignment.Center
    ) {
        Container(
            onGreetingTapped = viewModel::onGreetingTapped,
            onNavigateToChat = onNavigateToChat,
            onNavigateToConversation = onNavigateToConversation,
            onNavigateToNavigation = onNavigateToNavigation,
            onIssue = { viewModel.showIssueDialog(true) }
        )

        if (uiState.showConfigDialog) {
            ServerConfigDialog(
                step = uiState.configStep,
                serverUrl = uiState.serverUrlInput,
                passwordError = uiState.passwordError,
                onPasswordSubmit = viewModel::onPasswordSubmit,
                onServerUrlChanged = viewModel::onServerUrlChanged,
                onServerUrlSave = viewModel::onServerUrlSave,
                onDismiss = viewModel::dismissConfigDialog
            )
        }

        if (uiState.showIssueDialog) {
            IssueDialog(
                submitting = uiState.issueSubmitting,
                error = uiState.issueError,
                onDismiss = { viewModel.showIssueDialog(false) },
                onSubmit = viewModel::submitIssue
            )
        }

        uiState.saveMessage?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = viewModel::clearSaveMessage) {
                        Text("OK")
                    }
                }
            ) {
                Text(message)
            }
        }
    }
}

@Composable
fun Container(
    onGreetingTapped: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToConversation: () -> Unit,
    onNavigateToNavigation: () -> Unit,
    onIssue: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    
    // Detect device type
    val isTablet = screenWidth >= 600.dp
    val isLargeTablet = screenWidth >= 1000.dp
    
    // Calculate responsive sizes based on screen
    val padding = when {
        isLargeTablet -> 64.dp
        isTablet -> 32.dp
        else -> 16.dp
    }
    
    val fontSize = when {
        isLargeTablet -> 36.sp
        isTablet -> 28.sp
        else -> 20.sp
    }
    
    val buttonSpacing = when {
        isLargeTablet -> 24.dp
        isTablet -> 20.dp
        else -> 8.dp
    }
    
    val cardPadding = when {
        isLargeTablet -> 32.dp
        isTablet -> 24.dp
        else -> 10.dp
    }
    
    // The "Điểm danh" (attendance) card is intentionally not exposed in the
    // current robot UI, so the remaining 3 cards must be laid out as a balanced
    // 3-cell grid (row on tablets, column on phones) instead of a broken 2x2.
    val useSingleRow = isTablet
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(padding)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Question — tap 7 times to open hidden server config
        Text(
            text = "Xin chào, tôi có thể giúp gì cho bạn?",
            fontSize = fontSize,
            lineHeight = (fontSize * 1.4),
            color = Color(0xFF0F172A),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(onClick = onGreetingTapped)
                .padding(bottom = buttonSpacing)
        )

        // Attendance is intentionally not exposed in the current robot UI.
        if (useSingleRow) {
            // Single row of 3 cards for tablets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
            ) {
                OptionButton(
                    text = "Nhắn tin với tôi",
                    onClick = onNavigateToChat,
                    cardPadding = cardPadding,
                    fontSize = fontSize,
                    modifier = Modifier.weight(1f)
                )
                OptionButton(
                    text = "Trò chuyện với tôi",
                    onClick = onNavigateToConversation,
                    cardPadding = cardPadding,
                    fontSize = fontSize,
                    modifier = Modifier.weight(1f)
                )
                OptionButton(
                    text = "Bản đồ 3D",
                    onClick = onNavigateToNavigation,
                    cardPadding = cardPadding,
                    fontSize = fontSize,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // Phones: centered column of 3 cards (Điểm danh intentionally hidden)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(buttonSpacing),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OptionButton(
                    text = "Nhắn tin với tôi",
                    onClick = onNavigateToChat,
                    cardPadding = cardPadding,
                    fontSize = fontSize
                )
                OptionButton(
                    text = "Trò chuyện với tôi",
                    onClick = onNavigateToConversation,
                    cardPadding = cardPadding,
                    fontSize = fontSize
                )
                OptionButton(
                    text = "Bản đồ 3D",
                    onClick = onNavigateToNavigation,
                    cardPadding = cardPadding,
                    fontSize = fontSize
                )
            }
        }
        TextButton(onClick = onIssue, modifier = Modifier.padding(top = buttonSpacing)) {
            Text("Gửi phiếu hỗ trợ", color = Color(0xFF006F72))
        }
    }
}

@Composable
private fun IssueDialog(
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var identity by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(1) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(step) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("Gửi phiếu hỗ trợ ($step/3)") },
        text = {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (step) {
                    1 -> OutlinedTextField(identity, { identity = it; validationError = null }, Modifier.focusRequester(focusRequester), label = { Text("MSSV hoặc CCCD") }, singleLine = true)
                    2 -> OutlinedTextField(detail, { detail = it; validationError = null }, Modifier.focusRequester(focusRequester), label = { Text("Chi tiết cần hỗ trợ") }, minLines = 3)
                    else -> OutlinedTextField(notes, { notes = it }, Modifier.focusRequester(focusRequester), label = { Text("Ghi chú khác (không bắt buộc)") }, minLines = 3)
                }
                (validationError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !submitting, onClick = {
                validationError = when (step) {
                    1 -> if (identity.trim().length < 4) "Vui lòng nhập MSSV hoặc CCCD." else null
                    2 -> if (detail.trim().length < 5) "Vui lòng mô tả chi tiết cần hỗ trợ." else null
                    else -> null
                }
                if (validationError == null) {
                    if (step < 3) step++ else onSubmit(identity.trim(), detail.trim(), notes.trim())
                }
            }) {
                Text(if (submitting) "Đang gửi..." else if (step < 3) "Tiếp tục" else "Gửi phiếu")
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = { if (step > 1) step-- else onDismiss() }) {
                Text(if (step > 1) "Quay lại" else "Hủy")
            }
        }
    )
}

@Composable
fun OptionButton(
    text: String,
    onClick: () -> Unit,
    cardPadding: Dp = 28.dp,
    fontSize: TextUnit = 28.sp,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8FAFC),
            contentColor = Color(0xFF0F172A)
        ),
        border = null,
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = text,
                fontSize = (fontSize * 0.8),
                color = Color(0xFF0F172A)
            )
        }
    }
}
