package com.mediadash.android.ui.spotify

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Spotify brand colors
private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifyBlack = Color(0xFF191414)
private val SpotifyDarkGray = Color(0xFF282828)
private val SpotifyLightGray = Color(0xFFB3B3B3)

/**
 * Spotify authorization page for Janus.
 *
 * This page allows users to:
 * 1. Configure their Spotify Client ID
 * 2. Authenticate with their Spotify account using OAuth 2.0 PKCE
 * 3. View connection status and available features
 */
@Composable
fun SpotifyAuthPage(
    viewModel: SpotifyAuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Activity result launcher for OAuth flow
    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleAuthResult(result)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SpotifyBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Header
            SpotifyHeader()

            Spacer(modifier = Modifier.height(32.dp))

            // Client ID Configuration Card (always visible, expandable)
            ClientIdConfigCard(
                clientId = uiState.clientId,
                isConfigured = uiState.isClientIdConfigured,
                isExpanded = uiState.showClientIdInput || !uiState.isClientIdConfigured,
                onToggleExpand = { viewModel.onEvent(SpotifyAuthEvent.ToggleClientIdInput) },
                onSave = { viewModel.onEvent(SpotifyAuthEvent.SaveClientId(it)) },
                onClear = { viewModel.onEvent(SpotifyAuthEvent.ClearClientId) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.isLoggedIn) {
                // Logged in state
                LoggedInCard(
                    userName = uiState.userName,
                    userEmail = uiState.userEmail,
                    userImageUrl = uiState.userImageUrl,
                    userId = uiState.userId,
                    country = uiState.country,
                    product = uiState.product,
                    followerCount = uiState.followerCount,
                    onLogout = { viewModel.onEvent(SpotifyAuthEvent.Logout) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tab row for library navigation
                SpotifyTabRow(
                    selectedTab = uiState.selectedTab,
                    onTabSelected = { viewModel.onEvent(SpotifyAuthEvent.SelectTab(it)) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Content based on selected tab
                when (uiState.selectedTab) {
                    SpotifyTab.OVERVIEW -> {
                        // Original overview content
                        LibraryStatsCard(
                            isLoading = uiState.isLoadingStats,
                            savedTracks = uiState.savedTracksCount,
                            savedAlbums = uiState.savedAlbumsCount,
                            playlists = uiState.playlistsCount,
                            followedArtists = uiState.followedArtistsCount,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.RefreshLibraryStats) }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        ActivityCard(
                            currentlyPlaying = uiState.currentlyPlaying,
                            recentTrack = uiState.recentTrackName,
                            recentArtist = uiState.recentTrackArtist
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        PlaybackControlsCard(
                            shuffleState = uiState.shuffleState,
                            repeatState = uiState.repeatState,
                            isToggling = uiState.isTogglingPlayback,
                            onToggleShuffle = { viewModel.onEvent(SpotifyAuthEvent.ToggleShuffle) },
                            onToggleRepeat = { viewModel.onEvent(SpotifyAuthEvent.ToggleRepeat) },
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.RefreshPlaybackState) }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        QueueCard(
                            queueTracks = uiState.queueTracks,
                            isLoading = uiState.isLoadingQueue,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.RefreshQueue) },
                            onTrackClick = { position -> viewModel.onEvent(SpotifyAuthEvent.SkipToPosition(position)) }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        ConnectionStatusCard(isConnected = true)
                    }

                    SpotifyTab.RECENT -> {
                        RecentTracksSection(
                            tracks = uiState.recentTracks,
                            isLoading = uiState.isLoadingRecent,
                            savedTrackIds = uiState.savedTrackIds,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.LoadRecentTracks) },
                            onTrackClick = { uri -> viewModel.onEvent(SpotifyAuthEvent.PlayTrack(uri)) },
                            onToggleSave = { trackId, isSaved ->
                                viewModel.onEvent(SpotifyAuthEvent.ToggleSaveTrack(trackId, isSaved))
                            }
                        )
                    }

                    SpotifyTab.LIKED -> {
                        SavedTracksSection(
                            tracks = uiState.savedTracks,
                            isLoading = uiState.isLoadingSaved,
                            hasMore = uiState.hasMoreSaved,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.LoadSavedTracks) },
                            onLoadMore = { viewModel.onEvent(SpotifyAuthEvent.LoadMoreSavedTracks) },
                            onTrackClick = { uri -> viewModel.onEvent(SpotifyAuthEvent.PlayTrack(uri)) },
                            onRemoveTrack = { trackId -> viewModel.onEvent(SpotifyAuthEvent.RemoveSavedTrack(trackId)) }
                        )
                    }

                    SpotifyTab.ALBUMS -> {
                        SavedAlbumsSection(
                            albums = uiState.savedAlbums,
                            isLoading = uiState.isLoadingAlbums,
                            hasMore = uiState.hasMoreAlbums,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.LoadSavedAlbums) },
                            onLoadMore = { viewModel.onEvent(SpotifyAuthEvent.LoadMoreSavedAlbums) },
                            onAlbumClick = { uri -> viewModel.onEvent(SpotifyAuthEvent.PlayContext(uri)) }
                        )
                    }

                    SpotifyTab.ARTISTS -> {
                        FollowedArtistsSection(
                            artists = uiState.followedArtists,
                            isLoading = uiState.isLoadingArtists,
                            hasMore = uiState.hasMoreArtists,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.LoadFollowedArtists) },
                            onLoadMore = { viewModel.onEvent(SpotifyAuthEvent.LoadMoreFollowedArtists) },
                            onArtistClick = { uri -> viewModel.onEvent(SpotifyAuthEvent.PlayContext(uri)) }
                        )
                    }

                    SpotifyTab.PLAYLISTS -> {
                        PlaylistsSection(
                            playlists = uiState.playlists,
                            isLoading = uiState.isLoadingPlaylists,
                            hasMore = uiState.hasMorePlaylists,
                            onRefresh = { viewModel.onEvent(SpotifyAuthEvent.LoadPlaylists) },
                            onLoadMore = { viewModel.onEvent(SpotifyAuthEvent.LoadMorePlaylists) },
                            onPlaylistClick = { uri -> viewModel.onEvent(SpotifyAuthEvent.PlayContext(uri)) }
                        )
                    }
                }

            } else if (uiState.isClientIdConfigured) {
                // Client ID configured but not logged in
                LoginCard(
                    isLoading = uiState.isLoading,
                    errorMessage = uiState.errorMessage,
                    onLogin = {
                        val intent = viewModel.getAuthIntent()
                        if (intent != null) {
                            authLauncher.launch(intent)
                        }
                    },
                    onClearError = { viewModel.onEvent(SpotifyAuthEvent.ClearError) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Info card
                InfoCard()
            } else {
                // No client ID configured - show setup instructions
                SetupInstructionsCard()
            }

            Spacer(modifier = Modifier.height(80.dp)) // Space for page indicator
        }
    }
}

@Composable
private fun SpotifyHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Spotify-style music icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(SpotifyGreen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Spotify",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Text(
            text = "Connect your account",
            style = MaterialTheme.typography.bodyLarge,
            color = SpotifyLightGray
        )
    }
}

@Composable
private fun ClientIdConfigCard(
    clientId: String,
    isConfigured: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit
) {
    var inputValue by remember(clientId) { mutableStateOf(clientId) }
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConfigured) SpotifyDarkGray else Color(0xFF2D2D2D)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = if (isConfigured) SpotifyGreen else Color(0xFFFFB74D),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Spotify Client ID",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (isConfigured && !isExpanded) {
                        Text(
                            text = "${clientId.take(8)}...${clientId.takeLast(4)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (!isConfigured) {
                        Text(
                            text = "Not configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFFB74D)
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.Edit,
                    contentDescription = if (isExpanded) "Collapse" else "Edit",
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Expandable content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    // Input field
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Paste your Client ID here",
                                color = SpotifyLightGray.copy(alpha = 0.5f)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (inputValue.isNotBlank()) {
                                    onSave(inputValue)
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = SpotifyLightGray.copy(alpha = 0.3f),
                            cursorColor = SpotifyGreen
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    clipboardManager.getText()?.text?.let {
                                        inputValue = it.trim()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                    tint = SpotifyLightGray
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isConfigured) {
                            OutlinedButton(
                                onClick = {
                                    inputValue = ""
                                    onClear()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFE57373)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Clear")
                            }
                        }

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                onSave(inputValue)
                            },
                            enabled = inputValue.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpotifyGreen,
                                disabledContainerColor = SpotifyGreen.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Help text - clickable link
                    Text(
                        text = "Get your Client ID from developer.spotify.com/dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyGreen,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                uriHandler.openUri("https://developer.spotify.com/dashboard")
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupInstructionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Setup Instructions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupStep(1, "Go to developer.spotify.com/dashboard", url = "https://developer.spotify.com/dashboard")
            SetupStep(2, "Create a new application")
            SetupStep(3, "Copy your Client ID")
            SetupStep(4, "Add redirect URI: janus://spotify-callback")
            SetupStep(5, "Add test users to the allowlist")
            SetupStep(6, "Paste your Client ID above")

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A2D1A)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "Redirect URI:",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "janus://spotify-callback",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyGreen,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: Int, text: String, url: String? = null) {
    val uriHandler = LocalUriHandler.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(
                if (url != null) {
                    Modifier.clickable { uriHandler.openUri(url) }
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(SpotifyGreen.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = SpotifyGreen
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (url != null) SpotifyGreen else SpotifyLightGray,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun LoginCard(
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit,
    onClearError: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sign in with Spotify",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect your Spotify account to access your music data, playlists, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyLightGray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Error message
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2D1F1F)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE57373),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Login button
            Button(
                onClick = onLogin,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen,
                    disabledContainerColor = SpotifyGreen.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(26.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Log in with Spotify",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun LoggedInCard(
    userName: String?,
    userEmail: String?,
    userImageUrl: String?,
    userId: String?,
    country: String?,
    product: String?,
    followerCount: Int,
    onLogout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // User avatar
            if (userImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(userImageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile picture",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = userName ?: "Spotify User",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (!userEmail.isNullOrBlank()) {
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Account details row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                // Product badge (Premium/Free)
                if (!product.isNullOrBlank()) {
                    val isPremium = product.equals("premium", ignoreCase = true)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPremium) SpotifyGreen.copy(alpha = 0.2f)
                            else SpotifyLightGray.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = product.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPremium) SpotifyGreen else SpotifyLightGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Country
                if (!country.isNullOrBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = SpotifyLightGray.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = country,
                            style = MaterialTheme.typography.labelSmall,
                            color = SpotifyLightGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Followers
                if (followerCount > 0) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = SpotifyLightGray.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "$followerCount followers",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpotifyLightGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // User ID
            if (!userId.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "@$userId",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Logout button
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SpotifyLightGray
                ),
                shape = RoundedCornerShape(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log out")
            }
        }
    }
}

@Composable
private fun LibraryStatsCard(
    isLoading: Boolean,
    savedTracks: Int?,
    savedAlbums: Int?,
    playlists: Int?,
    followedArtists: Int?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your Library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SpotifyGreen,
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh library stats",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    value = savedTracks?.toString() ?: "-",
                    label = "Liked Songs"
                )
                StatItem(
                    value = savedAlbums?.toString() ?: "-",
                    label = "Albums"
                )
                StatItem(
                    value = playlists?.toString() ?: "-",
                    label = "Playlists"
                )
                StatItem(
                    value = followedArtists?.toString() ?: "-",
                    label = "Artists"
                )
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = SpotifyGreen
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SpotifyLightGray
        )
    }
}

@Composable
private fun ActivityCard(
    currentlyPlaying: String?,
    recentTrack: String?,
    recentArtist: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Currently playing
            if (!currentlyPlaying.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SpotifyGreen)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Now Playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpotifyGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentlyPlaying,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Recently played
            if (!recentTrack.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SpotifyLightGray.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Recently Played",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpotifyLightGray,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (!recentArtist.isNullOrBlank()) "$recentTrack - $recentArtist" else recentTrack,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Show placeholder if no activity
            if (currentlyPlaying.isNullOrBlank() && recentTrack.isNullOrBlank()) {
                Text(
                    text = "No recent activity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun PlaybackControlsCard(
    shuffleState: ShuffleState,
    repeatState: RepeatState,
    isToggling: Boolean,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !isToggling,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh playback state",
                        tint = SpotifyLightGray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Shuffle button
                PlaybackToggleButton(
                    icon = Icons.Default.Shuffle,
                    label = when (shuffleState) {
                        ShuffleState.OFF -> "Shuffle Off"
                        ShuffleState.ON -> "Shuffle On"
                        ShuffleState.SMART -> "Smart Shuffle"
                    },
                    isActive = shuffleState != ShuffleState.OFF,
                    isSmart = shuffleState == ShuffleState.SMART,
                    isLoading = isToggling,
                    onClick = onToggleShuffle
                )

                // Repeat button
                PlaybackToggleButton(
                    icon = when (repeatState) {
                        RepeatState.TRACK -> Icons.Default.RepeatOne
                        else -> Icons.Default.Repeat
                    },
                    label = when (repeatState) {
                        RepeatState.OFF -> "Repeat Off"
                        RepeatState.CONTEXT -> "Repeat All"
                        RepeatState.TRACK -> "Repeat One"
                    },
                    isActive = repeatState != RepeatState.OFF,
                    isSmart = false,
                    isLoading = isToggling,
                    onClick = onToggleRepeat
                )
            }
        }
    }
}

@Composable
private fun PlaybackToggleButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    isSmart: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onClick,
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isSmart -> Color(0xFF8B5CF6) // Purple for smart shuffle
                    isActive -> SpotifyGreen
                    else -> SpotifyDarkGray.copy(alpha = 0.8f)
                },
                contentColor = Color.White,
                disabledContainerColor = SpotifyDarkGray.copy(alpha = 0.5f),
                disabledContentColor = SpotifyLightGray
            ),
            shape = CircleShape,
            modifier = Modifier.size(56.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) SpotifyGreen else SpotifyLightGray,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun QueueCard(
    queueTracks: List<QueueTrack>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onTrackClick: (Int) -> Unit  // Position in queue (1-indexed)
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Up Next",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (queueTracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${queueTracks.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SpotifyLightGray
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SpotifyGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh queue",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (queueTracks.isEmpty()) {
                Text(
                    text = if (isLoading) "Loading queue..." else "Tap refresh to load your queue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            } else {
                // Show up to 10 tracks
                queueTracks.take(10).forEachIndexed { index, track ->
                    val position = index + 1  // 1-indexed position
                    QueueTrackRow(
                        position = position,
                        track = track,
                        onClick = { onTrackClick(position) },
                        enabled = !isLoading
                    )
                    if (index < minOf(queueTracks.size - 1, 9)) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Show "and X more" if there are more tracks
                if (queueTracks.size > 10) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "and ${queueTracks.size - 10} more...",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    position: Int,
    track: QueueTrack,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Position number
        Text(
            text = position.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = SpotifyLightGray,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Album art (if available)
        if (track.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(track.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Album art",
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        // Track info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duration
        Text(
            text = formatDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray
        )
    }
}

/**
 * Formats duration in milliseconds to MM:SS format.
 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ConnectionStatusCard(isConnected: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFF1A3D1A) else Color(0xFF3D1A1A)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isConnected) SpotifyGreen else Color(0xFFE57373),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isConnected) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = if (isConnected) "Your Spotify account is linked" else "Unable to connect to Spotify",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray
                )
            }
        }
    }
}

@Composable
private fun FeaturesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Available Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            FeatureItem("Recently played tracks")
            FeatureItem("Top artists and tracks")
            FeatureItem("Saved library")
            FeatureItem("Playlists")
            FeatureItem("Currently playing")
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(SpotifyGreen)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = SpotifyLightGray
        )
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "About Spotify Integration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connecting your Spotify account allows Janus to display your music data on your CarThing. " +
                        "Your credentials are stored securely on your device and are never shared.",
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Note: This feature is in development mode. Only users added to the Spotify app's allowlist can authenticate.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFB74D),
                lineHeight = 20.sp
            )
        }
    }
}

// =============================================================================
// Library Browser Components
// =============================================================================

@Composable
private fun SpotifyTabRow(
    selectedTab: SpotifyTab,
    onTabSelected: (SpotifyTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SpotifyTab.entries.forEach { tab ->
            TabChip(
                text = when (tab) {
                    SpotifyTab.OVERVIEW -> "Overview"
                    SpotifyTab.RECENT -> "Recent"
                    SpotifyTab.LIKED -> "Liked"
                    SpotifyTab.ALBUMS -> "Albums"
                    SpotifyTab.ARTISTS -> "Artists"
                    SpotifyTab.PLAYLISTS -> "Playlists"
                },
                icon = when (tab) {
                    SpotifyTab.OVERVIEW -> Icons.Default.Dashboard
                    SpotifyTab.RECENT -> Icons.Default.History
                    SpotifyTab.LIKED -> Icons.Default.Favorite
                    SpotifyTab.ALBUMS -> Icons.Default.Album
                    SpotifyTab.ARTISTS -> Icons.Default.Person
                    SpotifyTab.PLAYLISTS -> Icons.Default.PlaylistPlay
                },
                isSelected = tab == selectedTab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

@Composable
private fun TabChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) SpotifyGreen else SpotifyDarkGray,
        contentColor = if (isSelected) Color.White else SpotifyLightGray
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun RecentTracksSection(
    tracks: List<RecentTrackItem>,
    isLoading: Boolean,
    savedTrackIds: Set<String>,
    onRefresh: () -> Unit,
    onTrackClick: (String) -> Unit,
    onToggleSave: (String, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recently Played",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${tracks.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SpotifyLightGray
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SpotifyGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (tracks.isEmpty() && !isLoading) {
                Text(
                    text = "No recently played tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            } else {
                tracks.forEach { track ->
                    val isSaved = savedTrackIds.contains(track.id)
                    TrackListItem(
                        imageUrl = track.imageUrl,
                        name = track.name,
                        artist = track.artist,
                        subtitle = track.albumName,
                        duration = track.durationMs,
                        isSaved = isSaved,
                        onSaveToggle = { onToggleSave(track.id, isSaved) },
                        onClick = { onTrackClick(track.uri) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun SavedTracksSection(
    tracks: List<SavedTrackItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onTrackClick: (String) -> Unit,
    onRemoveTrack: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Liked Songs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (tracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${tracks.size}${if (hasMore) "+" else ""})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SpotifyLightGray
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isLoading && tracks.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SpotifyGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (tracks.isEmpty() && !isLoading) {
                Text(
                    text = "No liked songs yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            } else {
                tracks.forEach { track ->
                    TrackListItem(
                        imageUrl = track.imageUrl,
                        name = track.name,
                        artist = track.artist,
                        subtitle = track.albumName,
                        duration = track.durationMs,
                        isSaved = true,
                        onSaveToggle = { onRemoveTrack(track.id) },
                        onClick = { onTrackClick(track.uri) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (hasMore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onLoadMore,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen.copy(alpha = 0.2f),
                            contentColor = SpotifyGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = SpotifyGreen,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAlbumsSection(
    albums: List<SavedAlbumItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAlbumClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Saved Albums",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (albums.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${albums.size}${if (hasMore) "+" else ""})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SpotifyLightGray
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isLoading && albums.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SpotifyGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (albums.isEmpty() && !isLoading) {
                Text(
                    text = "No saved albums yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            } else {
                albums.forEach { album ->
                    AlbumListItem(
                        imageUrl = album.imageUrl,
                        name = album.name,
                        artist = album.artist,
                        trackCount = album.trackCount,
                        onClick = { onAlbumClick(album.uri) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (hasMore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onLoadMore,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen.copy(alpha = 0.2f),
                            contentColor = SpotifyGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = SpotifyGreen,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowedArtistsSection(
    artists: List<ArtistItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onArtistClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Followed Artists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (artists.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${artists.size}${if (hasMore) "+" else ""})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SpotifyLightGray
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isLoading && artists.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SpotifyGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (artists.isEmpty() && !isLoading) {
                Text(
                    text = "No followed artists yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            } else {
                artists.forEach { artist ->
                    ArtistListItem(
                        imageUrl = artist.imageUrl,
                        name = artist.name,
                        genres = artist.genres,
                        followerCount = artist.followerCount,
                        onClick = { onArtistClick(artist.uri) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (hasMore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onLoadMore,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen.copy(alpha = 0.2f),
                            contentColor = SpotifyGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = SpotifyGreen,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsSection(
    playlists: List<PlaylistItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPlaylistClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpotifyDarkGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlaylistPlay,
                        contentDescription = null,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Playlists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (playlists.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${playlists.size}${if (hasMore) "+" else ""})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SpotifyLightGray
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isLoading && playlists.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SpotifyGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SpotifyLightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (playlists.isEmpty() && !isLoading) {
                Text(
                    text = "No playlists yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray.copy(alpha = 0.6f)
                )
            } else {
                playlists.forEach { playlist ->
                    PlaylistListItem(
                        imageUrl = playlist.imageUrl,
                        name = playlist.name,
                        ownerName = playlist.ownerName,
                        trackCount = playlist.trackCount,
                        isPublic = playlist.isPublic,
                        onClick = { onPlaylistClick(playlist.uri) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (hasMore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onLoadMore,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen.copy(alpha = 0.2f),
                            contentColor = SpotifyGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = SpotifyGreen,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackListItem(
    imageUrl: String?,
    name: String,
    artist: String,
    subtitle: String?,
    duration: Long,
    isSaved: Boolean,
    onSaveToggle: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Album art",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SpotifyLightGray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Track info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (subtitle != null) "$artist • $subtitle" else artist,
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Save/like button
        IconButton(
            onClick = onSaveToggle,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isSaved) "Remove from library" else "Save to library",
                tint = if (isSaved) SpotifyGreen else SpotifyLightGray,
                modifier = Modifier.size(20.dp)
            )
        }

        // Duration
        Text(
            text = formatDuration(duration),
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyLightGray,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )

        // Play indicator
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = SpotifyLightGray.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AlbumListItem(
    imageUrl: String?,
    name: String,
    artist: String,
    trackCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Album art",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SpotifyLightGray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Album,
                    contentDescription = null,
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Album info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyLightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$trackCount tracks",
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray.copy(alpha = 0.7f)
            )
        }

        // Play indicator
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play album",
            tint = SpotifyLightGray.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ArtistListItem(
    imageUrl: String?,
    name: String,
    genres: List<String>,
    followerCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artist image (circular)
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Artist image",
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(SpotifyLightGray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Artist info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (genres.isNotEmpty()) {
                Text(
                    text = genres.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = formatFollowerCount(followerCount),
                style = MaterialTheme.typography.labelSmall,
                color = SpotifyLightGray.copy(alpha = 0.7f)
            )
        }

        // Play indicator
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play artist",
            tint = SpotifyLightGray.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Formats a follower count into a human-readable string.
 */
private fun formatFollowerCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM followers", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK followers", count / 1_000.0)
        else -> "$count followers"
    }
}

@Composable
private fun PlaylistListItem(
    imageUrl: String?,
    name: String,
    ownerName: String?,
    trackCount: Int,
    isPublic: Boolean?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Playlist image
        if (imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Playlist image",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SpotifyLightGray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlaylistPlay,
                    contentDescription = null,
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Playlist info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPublic != null) {
                    Icon(
                        imageVector = if (isPublic) Icons.Default.Public else Icons.Default.Lock,
                        contentDescription = if (isPublic) "Public" else "Private",
                        tint = SpotifyLightGray.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (ownerName != null) {
                    Text(
                        text = ownerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray
                    )
                }
                Text(
                    text = "$trackCount tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyLightGray.copy(alpha = 0.7f)
                )
            }
        }

        // Play indicator
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play playlist",
            tint = SpotifyLightGray.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}
