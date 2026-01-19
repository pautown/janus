package com.mediadash.android.data.remote

import android.util.Log
import com.mediadash.android.domain.model.LyricsLine
import com.mediadash.android.domain.model.LyricsState
import com.mediadash.android.util.CRC32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for fetching lyrics from multiple sources.
 *
 * Search strategy (prioritizes synced lyrics):
 * 1. LRCLIB exact match
 * 2. LRCLIB search endpoint (fuzzy match)
 * 3. LRCLIB with cleaned track name variations
 * 4. NetEase Music (Chinese service with good synced coverage)
 *
 * Falls back to plain lyrics only if no synced lyrics found anywhere.
 */
@Singleton
class LyricsApiService @Inject constructor() {
    companion object {
        private const val TAG = "LyricsApiService"
        private const val LRCLIB_BASE_URL = "https://lrclib.net/api"
        private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
        private const val NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric"
        private const val REQUEST_TIMEOUT = 10000 // 10 seconds
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Fetches lyrics for a given artist and track.
     * Tries multiple sources and strategies to find synced lyrics.
     * Returns null if no lyrics found anywhere.
     */
    suspend fun fetchLyrics(artist: String, track: String): LyricsState? = withContext(Dispatchers.IO) {
        Log.d("LYRICS", "═══════════════════════════════════════════════")
        Log.d("LYRICS", "🌐 LYRICS FETCH - Multi-source search")
        Log.d("LYRICS", "   Artist: $artist")
        Log.d("LYRICS", "   Track: $track")

        val cleanArtist = cleanString(artist)
        val cleanTrack = cleanString(track)

        if (cleanArtist.isBlank() || cleanTrack.isBlank()) {
            Log.w("LYRICS", "   ⚠️ Empty artist or track after cleaning")
            return@withContext null
        }

        val startTime = System.currentTimeMillis()
        var bestResult: LyricsState? = null
        var unsyncedFallback: LyricsState? = null

        // Strategy 1: LRCLIB exact match
        Log.d("LYRICS", "   📍 Strategy 1: LRCLIB exact match")
        val exactResult = tryLrcLibExact(cleanArtist, cleanTrack)
        if (exactResult != null) {
            if (exactResult.synced) {
                Log.i("LYRICS", "   ✅ Found synced lyrics via LRCLIB exact match")
                bestResult = exactResult
            } else {
                Log.d("LYRICS", "   ⚠️ Found unsynced lyrics, continuing search for synced...")
                unsyncedFallback = exactResult
            }
        }

        // Strategy 2: LRCLIB search endpoint (fuzzy match)
        if (bestResult == null) {
            Log.d("LYRICS", "   📍 Strategy 2: LRCLIB search (fuzzy)")
            val searchResult = tryLrcLibSearch(cleanArtist, cleanTrack)
            if (searchResult != null && searchResult.synced) {
                Log.i("LYRICS", "   ✅ Found synced lyrics via LRCLIB search")
                bestResult = searchResult
            } else if (searchResult != null && unsyncedFallback == null) {
                unsyncedFallback = searchResult
            }
        }

        // Strategy 3: Try track name variations
        if (bestResult == null) {
            Log.d("LYRICS", "   📍 Strategy 3: Track name variations")
            val variations = generateTrackVariations(cleanTrack)
            for (variation in variations) {
                if (variation == cleanTrack) continue
                Log.d("LYRICS", "      Trying: '$variation'")
                val varResult = tryLrcLibExact(cleanArtist, variation)
                if (varResult != null && varResult.synced) {
                    Log.i("LYRICS", "   ✅ Found synced lyrics with variation: '$variation'")
                    bestResult = varResult
                    break
                } else if (varResult != null && unsyncedFallback == null) {
                    unsyncedFallback = varResult
                }
            }
        }

        // Strategy 4: NetEase Music (good for popular songs)
        if (bestResult == null) {
            Log.d("LYRICS", "   📍 Strategy 4: NetEase Music")
            val netEaseResult = tryNetEase(cleanArtist, cleanTrack)
            if (netEaseResult != null && netEaseResult.synced) {
                Log.i("LYRICS", "   ✅ Found synced lyrics via NetEase")
                bestResult = netEaseResult
            } else if (netEaseResult != null && unsyncedFallback == null) {
                unsyncedFallback = netEaseResult
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Use synced result or fall back to unsynced
        val finalResult = bestResult ?: unsyncedFallback

        if (finalResult != null) {
            Log.i("LYRICS", "═══════════════════════════════════════════════")
            Log.i("LYRICS", "   ✅ LYRICS FOUND")
            Log.i("LYRICS", "      Source: ${finalResult.source}")
            Log.i("LYRICS", "      Lines: ${finalResult.lines.size}")
            Log.i("LYRICS", "      Synced: ${finalResult.synced}")
            Log.i("LYRICS", "      Time: ${elapsed}ms")
            Log.i("LYRICS", "═══════════════════════════════════════════════")
        } else {
            Log.w("LYRICS", "   ❌ No lyrics found from any source (${elapsed}ms)")
        }

        finalResult
    }

    // =========================================================================
    // LRCLIB Methods
    // =========================================================================

    private suspend fun tryLrcLibExact(artist: String, track: String): LyricsState? {
        return try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTrack = URLEncoder.encode(track, "UTF-8")
            val url = "$LRCLIB_BASE_URL/get?artist_name=$encodedArtist&track_name=$encodedTrack"

            val response = makeRequest(url) ?: return null
            val lrcLibResponse = json.decodeFromString<LrcLibResponse>(response)
            parseLrcLibResponse(lrcLibResponse, artist, track, "lrclib")
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB exact match failed: ${e.message}")
            null
        }
    }

    private suspend fun tryLrcLibSearch(artist: String, track: String): LyricsState? {
        return try {
            val query = "$artist $track"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$LRCLIB_BASE_URL/search?q=$encodedQuery"

            val response = makeRequest(url) ?: return null
            val results = json.decodeFromString<List<LrcLibResponse>>(response)

            // Find best match - prefer synced lyrics
            val syncedResult = results.firstOrNull { it.syncedLyrics != null }
            val anyResult = results.firstOrNull()

            val bestMatch = syncedResult ?: anyResult
            if (bestMatch != null) {
                parseLrcLibResponse(bestMatch, artist, track, "lrclib-search")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB search failed: ${e.message}")
            null
        }
    }

    private fun parseLrcLibResponse(
        response: LrcLibResponse,
        artist: String,
        track: String,
        source: String
    ): LyricsState {
        val hash = generateLyricsHash(artist, track)
        val lyricsText = response.syncedLyrics ?: response.plainLyrics ?: ""
        val isSynced = response.syncedLyrics != null

        val lines = if (isSynced && lyricsText.isNotBlank()) {
            parseLrcFormat(lyricsText)
        } else {
            parsePlainLyrics(lyricsText)
        }

        return LyricsState(
            hash = hash,
            trackTitle = response.trackName ?: track,
            artist = response.artistName ?: artist,
            synced = isSynced,
            lines = lines,
            source = source
        )
    }

    // =========================================================================
    // NetEase Music Methods
    // =========================================================================

    private suspend fun tryNetEase(artist: String, track: String): LyricsState? {
        return try {
            // Step 1: Search for the song
            val songId = searchNetEase(artist, track) ?: return null
            Log.d(TAG, "NetEase: Found song ID: $songId")

            // Step 2: Get lyrics for the song
            val lyrics = getNetEaseLyrics(songId) ?: return null

            val hash = generateLyricsHash(artist, track)
            val lines = parseLrcFormat(lyrics)

            if (lines.isEmpty()) return null

            LyricsState(
                hash = hash,
                trackTitle = track,
                artist = artist,
                synced = true,
                lines = lines,
                source = "netease"
            )
        } catch (e: Exception) {
            Log.w(TAG, "NetEase fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun searchNetEase(artist: String, track: String): Long? {
        return try {
            val query = "$artist $track"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$NETEASE_SEARCH_URL?s=$encodedQuery&type=1&limit=5"

            val response = makeRequest(url, mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )) ?: return null

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val result = jsonResponse["result"]?.jsonObject ?: return null
            val songs = result["songs"]?.jsonArray ?: return null

            if (songs.isEmpty()) return null

            // Get the first result's ID
            val firstSong = songs[0].jsonObject
            firstSong["id"]?.jsonPrimitive?.longOrNull
        } catch (e: Exception) {
            Log.w(TAG, "NetEase search failed: ${e.message}")
            null
        }
    }

    private suspend fun getNetEaseLyrics(songId: Long): String? {
        return try {
            val url = "$NETEASE_LYRIC_URL?id=$songId&lv=1"

            val response = makeRequest(url, mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )) ?: return null

            val jsonResponse = json.parseToJsonElement(response).jsonObject
            val lrc = jsonResponse["lrc"]?.jsonObject ?: return null
            lrc["lyric"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.w(TAG, "NetEase lyrics fetch failed: ${e.message}")
            null
        }
    }

    // =========================================================================
    // HTTP & Parsing Utilities
    // =========================================================================

    private suspend fun makeRequest(
        urlString: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var lastException: Exception? = null

        repeat(3) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = REQUEST_TIMEOUT
                connection.readTimeout = REQUEST_TIMEOUT
                connection.setRequestProperty("Connection", "close")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate")

                // Default User-Agent
                if (!extraHeaders.containsKey("User-Agent")) {
                    connection.setRequestProperty("User-Agent", "Janus/1.0 (Android)")
                }

                // Apply extra headers
                extraHeaders.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                val responseCode = connection.responseCode

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val inputStream = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                            GZIPInputStream(connection.inputStream)
                        } else {
                            connection.inputStream
                        }
                        return inputStream.bufferedReader().use { it.readText() }
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> {
                        return null
                    }
                    in 500..599 -> {
                        Log.w(TAG, "Server error $responseCode, attempt ${attempt + 1}/3")
                        lastException = Exception("HTTP $responseCode")
                    }
                    else -> {
                        Log.w(TAG, "HTTP error: $responseCode")
                        return null
                    }
                }
            } catch (e: java.io.EOFException) {
                Log.w(TAG, "EOF error on attempt ${attempt + 1}/3: ${e.message}")
                lastException = e
            } catch (e: java.io.IOException) {
                Log.w(TAG, "IO error on attempt ${attempt + 1}/3: ${e.message}")
                lastException = e
            } catch (e: Exception) {
                Log.e(TAG, "Request failed on attempt ${attempt + 1}/3", e)
                lastException = e
            } finally {
                try {
                    connection?.inputStream?.close()
                } catch (_: Exception) {}
                connection?.disconnect()
            }

            if (attempt < 2) {
                val delayMs = 200L * (attempt + 1)
                delay(delayMs)
            }
        }

        Log.e(TAG, "All retry attempts failed", lastException)
        return null
    }

    /**
     * Parses LRC format lyrics with timestamps.
     * Supports formats: [mm:ss.xx], [mm:ss.xxx], [mm:ss]
     */
    private fun parseLrcFormat(lrc: String): List<LyricsLine> {
        val lines = mutableListOf<LyricsLine>()
        // Support multiple timestamp formats
        val pattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")

        for (line in lrc.split("\n")) {
            val trimmedLine = line.trim()

            // Skip metadata lines like [ar:Artist], [ti:Title], etc.
            if (trimmedLine.matches(Regex("""\[[a-z]+:.*\]"""))) continue

            val match = pattern.find(trimmedLine)
            if (match != null) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0
                val seconds = match.groupValues[2].toLongOrNull() ?: 0
                val subseconds = match.groupValues[3].let {
                    if (it.isEmpty()) 0L
                    else {
                        val value = it.toLongOrNull() ?: 0
                        if (it.length == 2) value * 10 else value
                    }
                }
                val text = match.groupValues[4].trim()

                val timestampMs = (minutes * 60 + seconds) * 1000 + subseconds
                if (text.isNotBlank()) {
                    lines.add(LyricsLine(t = timestampMs, l = text))
                }
            }
        }

        return lines.sortedBy { it.t }
    }

    private fun parsePlainLyrics(text: String): List<LyricsLine> {
        return text.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                LyricsLine(t = index.toLong(), l = line)
            }
    }

    private fun generateLyricsHash(artist: String, track: String): String {
        val input = "${artist.lowercase()}|${track.lowercase()}"
        return CRC32Util.calculateCRC32(input.toByteArray())
    }

    /**
     * Generates variations of the track name to try.
     */
    private fun generateTrackVariations(track: String): List<String> {
        val variations = mutableListOf(track)

        // Remove content in parentheses
        val noParens = track.replace(Regex("""\s*\([^)]*\)"""), "").trim()
        if (noParens.isNotBlank() && noParens != track) {
            variations.add(noParens)
        }

        // Remove content in brackets
        val noBrackets = track.replace(Regex("""\s*\[[^\]]*\]"""), "").trim()
        if (noBrackets.isNotBlank() && noBrackets != track) {
            variations.add(noBrackets)
        }

        // Remove " - " and everything after (often remix/version info)
        val dashIndex = track.indexOf(" - ")
        if (dashIndex > 0) {
            variations.add(track.substring(0, dashIndex).trim())
        }

        // Remove common suffixes
        val suffixPatterns = listOf(
            Regex("""\s*-?\s*remaster(ed)?.*""", RegexOption.IGNORE_CASE),
            Regex("""\s*-?\s*remix.*""", RegexOption.IGNORE_CASE),
            Regex("""\s*-?\s*live.*""", RegexOption.IGNORE_CASE),
            Regex("""\s*-?\s*acoustic.*""", RegexOption.IGNORE_CASE),
            Regex("""\s*-?\s*radio\s*edit.*""", RegexOption.IGNORE_CASE),
            Regex("""\s*-?\s*single\s*version.*""", RegexOption.IGNORE_CASE),
            Regex("""\s*-?\s*album\s*version.*""", RegexOption.IGNORE_CASE),
        )

        for (pattern in suffixPatterns) {
            val cleaned = track.replace(pattern, "").trim()
            if (cleaned.isNotBlank() && cleaned != track && cleaned !in variations) {
                variations.add(cleaned)
            }
        }

        return variations.distinct()
    }

    /**
     * Cleans string for API query (removes featuring artists, etc.)
     */
    private fun cleanString(input: String): String {
        return input
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(ft\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Remaster.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(Remaster.*?\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}

/**
 * Response from LRCLIB API.
 */
@Serializable
data class LrcLibResponse(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)
