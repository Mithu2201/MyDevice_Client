package com.example.mydevice.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import org.koin.androidx.compose.koinViewModel

/**
 * Messages screen — displays messages pushed from the server via SignalR.
 *
 * DESIGN:
 * - Top bar with back arrow, title, "Mark all read" action
 * - List of message cards with sender, description, timestamp
 * - Unread messages have a colored left border indicator
 * - Tap a message to mark it as read
 * - Empty state when no messages
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBack: () -> Unit,
    viewModel: MessagesViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Messages", fontWeight = FontWeight.Bold)
                        if (uiState.unreadCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge { Text("${uiState.unreadCount}") }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.unreadCount > 0) {
                        TextButton(onClick = { viewModel.markAllAsRead() }) {
                            Text("Mark all read")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.MailOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Messages",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Messages from the server will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        MessageCard(
                            message = message,
                            onClick = { viewModel.markAsRead(message.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageCard(
    message: IncomingMessageEntity,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!message.isRead)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (!message.isRead) 2.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Unread indicator
            if (!message.isRead) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .offset(y = 6.dp)
                        .padding(end = 0.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {}
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = message.sendBy.ifEmpty { "System" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (!message.isRead) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = formatTimestamp(message.receivedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (!message.isRead) 0.9f else 0.6f
                    )
                )
            }
        }
    }
}

private fun formatTimestamp(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm")
        local.format(formatter)
    } catch (_: Exception) {
        isoString.take(16)
    }
}
