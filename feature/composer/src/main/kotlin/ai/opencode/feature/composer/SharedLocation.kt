package ai.opencode.feature.composer

import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.motionBlurReveal
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.openCodeTapTarget
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A location shared from the composer's + menu: one fix and its nearest address. It travels as a
 * tagged block at the top of the prompt's text, readable by the agent as-is, and the transcript
 * draws it as a card.
 */
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters, when known. */
    val accuracy: Double? = null,
    /** The nearest place's name, when it isn't just the street address. */
    val place: String? = null,
    /** The nearest address, on one line. */
    val address: String? = null,
    val capturedAt: Instant,
) {
    val coordinateText: String
        get() = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    val mapsURL: String
        get() {
            val ll = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
            val q = place ?: address ?: "Shared Location"
            return "https://maps.apple.com/?ll=${queryEncode(ll)}&q=${queryEncode(q)}"
        }

    val googleMapsURL: String
        get() = String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", latitude, longitude)

    /** The block the agent reads. */
    val messageText: String
        get() {
            val lines = mutableListOf(OPEN_TAG)
            place?.let { lines += "Place: $it" }
            address?.let { lines += "Address: $it" }
            lines += "Coordinates: $coordinateText"
            accuracy?.let { lines += "Accuracy: ±${Math.round(it)} m" }
            lines += "Shared: ${ISO.format(capturedAt.truncatedTo(ChronoUnit.SECONDS))}"
            lines += "Map: $mapsURL"
            lines += CLOSE_TAG
            return lines.joinToString("\n")
        }

    /** The prompt's text: the location block, then whatever the user typed. */
    fun prompt(text: String): String = if (text.isEmpty()) messageText else messageText + "\n\n" + text

    /** A parsed prompt: the location, and the user's own text after it. */
    data class Parsed(val location: SharedLocation, val remainder: String)

    companion object {
        const val OPEN_TAG = "<shared-location>"
        const val CLOSE_TAG = "</shared-location>"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        private val DISTANT_PAST: Instant = Instant.parse("0001-01-01T00:00:00Z")

        /** A prompt that starts with a location block: the location, and the user's own text after it. */
        fun parse(text: String): Parsed? {
            if (!text.startsWith(OPEN_TAG)) return null
            val close = text.indexOf(CLOSE_TAG)
            if (close < 0 || close < OPEN_TAG.length) return null
            val body = text.substring(OPEN_TAG.length, close)
            val fields = mutableMapOf<String, String>()
            for (line in body.split("\n")) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                fields[line.substring(0, colon).trim(' ', '\t')] = line.substring(colon + 1).trim(' ', '\t')
            }
            val parts = (fields["Coordinates"] ?: "").split(",").filter { it.isNotEmpty() }.map { it.trim(' ', '\t') }
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            val accuracy = fields["Accuracy"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            val capturedAt = fields["Shared"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: DISTANT_PAST
            val location = SharedLocation(
                latitude = latitude, longitude = longitude, accuracy = accuracy,
                place = fields["Place"], address = fields["Address"], capturedAt = capturedAt,
            )
            val remainder = text.substring(close + CLOSE_TAG.length).trim()
            return Parsed(location, remainder)
        }

        /** Percent-encodes a query value the way `URLComponents.queryItems` does (`urlQueryAllowed` less `&`, `=`, `+`, `#`). */
        internal fun queryEncode(value: String): String {
            val allowed = "-._~!$'()*,;:@/?"
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF)
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in allowed)) out.append(ch)
                else out.append('%').append(String.format(Locale.US, "%02X", c))
            }
            return out.toString()
        }
    }
}

// region LocationClient

/** Why Location couldn't be shared; [message] is the user-facing copy. */
sealed class LocationClientError(@StringRes private val messageRes: Int) : Exception() {
    data object Denied : LocationClientError(R.string.composer_location_denied)
    data object Unavailable : LocationClientError(R.string.composer_location_unavailable)

    override val message: String get() = localized(messageRes)

    val localizedDescription: String get() = message
}

/**
 * One fix of where the phone is, with its nearest address.
 *
 * - The fix comes from `LocationManager` (fused, GPS and network providers) and the address from
 *   `Geocoder`; Play Services' fused client isn't in the catalog.
 * - Android asks for permission from an Activity, so the live client awaits the prompt the
 *   screen installs with [LocationPermissionPrompt.install].
 * - Live [snapshot] composes OpenStreetMap tiles into a 600 m region ([SharedLocationSnapshots]),
 *   cached in memory per place, size and appearance (tiles on disk), and [cachedSnapshot] reads
 *   that cache.
 * - [openSettings] opens the app's system settings page.
 */
class LocationClient(
    val current: suspend () -> SharedLocation,
    /** A still map of the spot at [IntSize] pixels, dark or light; rendered once per place and size and cached. */
    val snapshot: suspend (location: SharedLocation, size: IntSize, dark: Boolean) -> ImageBitmap? = { _, _, _ -> null },
    val cachedSnapshot: (location: SharedLocation, size: IntSize, dark: Boolean) -> ImageBitmap? = { _, _, _ -> null },
    val prefetchTimeout: Duration = DEFAULT_PREFETCH_TIMEOUT,
    /** The composer's compact map in pixels, and whether the app is dark. */
    val compactSnapshotTraits: () -> Pair<IntSize, Boolean> = { IntSize.Zero to true },
    val openSettings: suspend () -> Unit = {},
) {
    /**
     * Pre-renders the composer's compact map snapshot before the location response lands so the
     * card reveals with its map already in cache, bounded by [timeout] (1.2 s default) so slow
     * tiles never hold the card on “Finding Location…”. The snapshot runs in its own job, so the
     * wait ends at [timeout] even when the snapshot ignores cancellation; it is cancelled then.
     */
    suspend fun prefetchCompactSnapshot(location: SharedLocation, timeout: Duration? = null) {
        val limit = timeout ?: prefetchTimeout
        val (size, dark) = compactSnapshotTraits()
        val render = CoroutineScope(currentCoroutineContext() + Job()).async { snapshot(location, size, dark) }
        try {
            withTimeoutOrNull(limit) { render.await() }
        } finally {
            render.cancel()
        }
    }

    companion object Key : DependencyKey<LocationClient> {
        /** Maximum time `locationTapped` waits for the compact map snapshot before delivering the fix. */
        val DEFAULT_PREFETCH_TIMEOUT: Duration = 1200.milliseconds

        override val liveValue: LocationClient by lazy {
            val context = dependency(AppContext).applicationContext
            val snapshots = SharedLocationSnapshots(context)
            val scale = { context.resources.displayMetrics.density }
            LocationClient(
                current = { LiveLocation.current(context) },
                snapshot = { location, size, dark -> snapshots.image(location, size, scale(), dark) },
                cachedSnapshot = { location, size, dark -> snapshots.cachedImage(location, size, scale(), dark) },
                compactSnapshotTraits = {
                    val metrics = context.resources.displayMetrics
                    val size = IntSize(
                        // As the card's map measures itself (`roundToPx`), so the prefetch lands in its cache key.
                        (SharedLocationCard.compactWidth.value * metrics.density).roundToInt(),
                        (SharedLocationCard.compactMapHeight.value * metrics.density).roundToInt(),
                    )
                    // Reads the key window's style, which follows the app's Appearance override.
                    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    val appearance = shared(ThemeSharedKeys.appearanceColorScheme).value
                    size to appearance.resolveIsDark(night == Configuration.UI_MODE_NIGHT_YES)
                },
                openSettings = {
                    withContext(Dispatchers.Main) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure { AppLog.app.error { "Location settings didn’t open: ${it::class.java.simpleName}" } }
                    }
                },
            )
        }

        override val testValue: LocationClient = LocationClient(
            current = { throw LocationClientError.Unavailable },
        )
    }
}

/**
 * The bridge from [LocationClient]'s live value to the runtime permission prompt, which only an
 * Activity can show. The screen hosting the composer installs a prompt (an
 * `ActivityResultContracts.RequestMultiplePermissions` launcher for [permissions]) while it's
 * on screen and removes it with the returned handle. Without one, a missing permission is
 * [LocationClientError.Denied].
 */
object LocationPermissionPrompt {
    /** When In Use: precise or approximate. */
    val permissions: Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Volatile private var prompt: (suspend () -> Boolean)? = null

    /** [prompt] returns whether either permission was granted. Returns the uninstall handle. */
    fun install(prompt: suspend () -> Boolean): () -> Unit {
        this.prompt = prompt
        return { if (this.prompt === prompt) this.prompt = null }
    }

    fun isGranted(context: Context): Boolean = permissions.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    internal suspend fun request(): Boolean {
        val prompt = prompt ?: return false
        return withContext(Dispatchers.Main) { prompt() }
    }
}

/** The best fix within a few seconds, then its nearest address. */
private object LiveLocation {
    /** Good enough to name a street; the first fix is often coarse. */
    const val WANTED_ACCURACY = 65f
    /** How long to wait for that before taking the best fix so far. */
    val settle: Duration = 4.seconds
    val timeout: Duration = 15.seconds
    /** Android's `Geocoder` callback can go quiet; MapKit's request always answers. */
    val geocodeTimeout: Duration = 10.seconds

    suspend fun current(context: Context): SharedLocation {
        // Asks the first time, like When In Use; refusal is Denied.
        if (!LocationPermissionPrompt.isGranted(context)) {
            if (!LocationPermissionPrompt.request() || !LocationPermissionPrompt.isGranted(context)) {
                throw LocationClientError.Denied
            }
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: throw LocationClientError.Unavailable
        // Location turned off for the whole phone.
        if (!manager.isLocationEnabled) throw LocationClientError.Denied
        val fix = bestFix(context, manager)
        var location = SharedLocation(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracy = if (fix.hasAccuracy() && fix.accuracy >= 0) fix.accuracy.toDouble() else null,
            capturedAt = Instant.ofEpochMilli(fix.time),
        )
        val item = reverseGeocode(context, fix)
        if (item != null) {
            val address = fullAddress(item)
            location = location.copy(address = address)
            // "Apple Park, 1 Apple Park Way, …": the place as the title, the street address
            // under it. A bare street address stays as is.
            val name = item.featureName
            if (!name.isNullOrEmpty()) {
                if (address != null && address.startsWith("$name, ")) {
                    location = location.copy(place = name, address = address.substring(name.length + 2))
                } else if (!(address ?: "").startsWith(name)) {
                    location = location.copy(place = name)
                }
            }
        }
        return location
    }

    @Suppress("MissingPermission") // Checked in current().
    private fun updates(context: Context, manager: LocationManager) = callbackFlow {
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            close(LocationClientError.Unavailable)
            return@callbackFlow
        }
        val listener = LocationListener { trySend(it) }
        val request = LocationRequest.Builder(1_000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
        val executor: Executor = context.mainExecutor
        for (provider in providers) {
            runCatching { manager.requestLocationUpdates(provider, request, executor, listener) }
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    private suspend fun bestFix(context: Context, manager: LocationManager): Location {
        val started = TimeSource.Monotonic.markNow()
        var best: Location? = null
        fun record(fix: Location): Boolean {
            if (!fix.hasAccuracy() || fix.accuracy < 0) return false
            val current = best
            if (current == null || fix.accuracy < current.accuracy) best = fix
            return true
        }
        // Until the settle time: a fix good enough to name a street.
        val good = withTimeoutOrNull(settle) {
            updates(context, manager).first { fix -> record(fix) && fix.accuracy <= WANTED_ACCURACY }
        }
        good?.let { return it }
        best?.let { return it }
        // Nothing usable yet: the first one before the timeout.
        val remaining = timeout - started.elapsedNow()
        if (remaining.isPositive()) {
            withTimeoutOrNull(remaining) { updates(context, manager).first { record(it) } }
        }
        return best ?: throw LocationClientError.Unavailable
    }

    private suspend fun reverseGeocode(context: Context, fix: Location): Address? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(geocodeTimeout) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    Geocoder(context).getFromLocation(fix.latitude, fix.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }

    /** One line without the country (MapKit `fullAddress(includingRegion: false, singleLine: true)`). */
    private fun fullAddress(item: Address): String? {
        val street = listOfNotNull(item.subThoroughfare, item.thoroughfare).joinToString(" ").ifEmpty { null }
        val region = listOfNotNull(item.adminArea, item.postalCode).joinToString(" ").ifEmpty { null }
        val parts = listOfNotNull(street, item.locality, region)
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        val line = item.getAddressLine(0)?.trim()?.ifEmpty { null } ?: return null
        val country = item.countryName
        return if (country != null && line.endsWith(", $country")) line.dropLast(country.length + 2) else line
    }
}

// endregion

// region SharedLocationCard

/**
 * A shared location in the transcript and the composer: a map with a pin, the place or
 * address, and the coordinates. A tap opens it in Maps ([onOpen] gets [SharedLocation.googleMapsURL]).
 *
 * The map is [LocationClient.snapshot]'s OpenStreetMap still (credited on the map), over a
 * neutral tile with the pin until it lands.
 */
object SharedLocationCard {
    val compactWidth: Dp = 220.dp
    val fullWidth: Dp = 260.dp
    val compactMapHeight: Dp = 72.dp
    val fullMapHeight: Dp = 120.dp
    val defaultCompactFooterHeight: Dp = 64.dp

    /** `TranscriptMetrics.cardCornerRadius`. */
    val cornerRadius: Dp = FileAttachmentMetrics.cardCornerRadius

    /** The footer grows with the font size. */
    @Composable
    fun compactFooterHeight(): Dp = defaultCompactFooterHeight * LocalDensity.current.fontScale
}

@Composable
fun SharedLocationCard(
    location: SharedLocation,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPending: Boolean = false,
    compact: Boolean = false,
    /** Tight trailing corners where a sent card meets another surface of its run (`UserSurfaceCorners`). */
    shape: Shape = RoundedCornerShape(SharedLocationCard.cornerRadius),
) {
    val title = location.place ?: location.address ?: stringResource(R.string.composer_location_shared_title)
    val subtitle = if (location.place != null) location.address else null
    val footerHeight = SharedLocationCard.compactFooterHeight()
    val label = if (subtitle != null) {
        stringResource(R.string.composer_location_card_with_address_description, title, subtitle)
    } else {
        stringResource(R.string.composer_location_card_description, title)
    }
    val openInMapsText = stringResource(R.string.composer_location_open_in_maps_action)
    Column(
        modifier
            .width(if (compact) SharedLocationCard.compactWidth else SharedLocationCard.fullWidth)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .alpha(if (isPending) 0.6f else 1f)
            .clickable { onOpen(location.googleMapsURL) }
            .clearAndSetSemantics {
                contentDescription = label
                onClick(label = openInMapsText) { onOpen(location.googleMapsURL); true }
                testTag = if (compact) "composer-location-card" else "shared-location-card"
            },
    ) {
        SharedLocationMap(
            location,
            Modifier.fillMaxWidth().height(if (compact) SharedLocationCard.compactMapHeight else SharedLocationCard.fullMapHeight),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier.height(footerHeight) else Modifier)
                .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, if (compact) Alignment.CenterVertically else Alignment.Top),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpenCodeIcon(SFSymbol.LocationFill, contentDescription = null, size = 14.dp, tint = SystemBlue)
                Text(
                    title,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.text,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = OpenCodeTextStyles.caption1,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val accuracy = if (compact) "" else location.accuracy?.let { " · ±${Math.round(it)} m" } ?: ""
            Text(
                location.coordinateText + accuracy,
                style = OpenCodeTextStyles.caption2.copy(fontFamily = OpenCodeFonts.monospace),
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val SystemBlue = Color(0xFF0A84FF)
private val SystemRed = Color(0xFFFF3B30)

/** A still map of the spot, rendered once per place and size and cached, credited to OpenStreetMap's contributors. */
@Composable
private fun SharedLocationMap(location: SharedLocation, modifier: Modifier = Modifier) {
    val client = remember { dependency(LocationClient) }
    val dark = OpenCodeTheme.colors.isDark
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val size = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        var image by remember(location.coordinateText, size, dark) { mutableStateOf(client.cachedSnapshot(location, size, dark)) }
        val reduceMotion = rememberReduceMotion()
        val shown by animateFloatAsState(
            if (image != null) 1f else 0f,
            Motion.swap.animation(reduceMotion = reduceMotion),
            label = "location-map",
        )
        LaunchedEffect(location.coordinateText, size, dark) {
            if (image == null) image = client.snapshot(location, size, dark)
        }
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(shown))
        }
        MapPin()
        if (image != null) {
            // The tile licence's attribution (ODbL), on the map it credits.
            Text(
                "© OpenStreetMap contributors",
                style = OpenCodeTextStyles.caption2.copy(fontSize = 8.sp, lineHeight = 10.sp),
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .alpha(shown)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(topStart = 4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** `mappin.circle.fill` in the palette white on red. */
@Composable
private fun MapPin() {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).background(Color.White, RoundedCornerShape(50)))
        OpenCodeIcon(SFSymbol.MappinCircleFill, contentDescription = null, size = OpenCodeIconSize.large, tint = SystemRed)
    }
}

// endregion

// region ComposerLocationAttachment

/**
 * Unified composer location attachment card that keeps a single stable
 * `220 × (72 + compactFooterHeight)` frame across “Finding Location…” and the resolved
 * [SharedLocationCard], fading and scaling in once on `Motion.layout` (a fade alone under Reduce
 * Motion) and revealing the map and address in place with [motionBlurReveal] when ready, without a
 * second pop or height snap.
 */
@Composable
fun ComposerLocationAttachment(
    location: SharedLocation?,
    isLocating: Boolean,
    onRemove: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val footerHeight = SharedLocationCard.compactFooterHeight()
    val cardHeight = SharedLocationCard.compactMapHeight + footerHeight
    var displayedLocation by remember { mutableStateOf(location) }
    val resolved = remember { Animatable(if (location != null) 1f else 0f) }
    val activeLocation = location ?: displayedLocation

    LaunchedEffect(location) {
        if (location != null) {
            displayedLocation = location
            if (resolved.value < 1f) resolved.animateTo(1f, Motion.panelOpen.animation(reduceMotion = reduceMotion))
        }
    }
    LaunchedEffect(isLocating) {
        if (isLocating && location == null) {
            displayedLocation = null
            resolved.snapTo(0f)
        }
    }

    val appearing = remember { MutableTransitionState(false).apply { targetState = true } }
    val spec = Motion.layout.animation<Float>(MotionPhase.Enter, reduceMotion)
    var enter = fadeIn(spec)
    if (!reduceMotion) enter += scaleIn(spec, initialScale = 0.965f, transformOrigin = TransformOrigin(0f, 1f))
    AnimatedVisibility(appearing, modifier, enter = enter) {
        Box(contentAlignment = Alignment.TopEnd) {
            val progress = resolved.value
            Box(
                Modifier
                    .size(SharedLocationCard.compactWidth, cardHeight)
                    .clip(RoundedCornerShape(SharedLocationCard.cornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (progress < 1f) {
                    LocatingSurface(
                        footerHeight = footerHeight,
                        cardHeight = cardHeight,
                        isHidden = activeLocation != null,
                        modifier = Modifier
                            .alpha(maxOf(0f, 1f - progress * 2.2f))
                            .then(if (!reduceMotion && progress > 0f) Modifier.transitionBlur(Motion.blurSubtle * progress.coerceIn(0f, 1f)) else Modifier),
                    )
                }
                if (activeLocation != null) {
                    SharedLocationCard(
                        activeLocation,
                        onOpen = onOpen,
                        compact = true,
                        modifier = Modifier.motionBlurReveal(progress, Motion.blurSubtle, reduceMotion = reduceMotion),
                    )
                }
            }
            val removeLabel = if (location != null) stringResource(R.string.composer_location_remove_description) else stringResource(R.string.composer_location_cancel_description)
            ComposerRemoveBadge(
                label = removeLabel,
                testTag = if (location != null) "remove-pending-location" else "cancel-pending-location",
                onClick = onRemove,
                modifier = Modifier.offset(x = 8.dp, y = (-8).dp),
            )
        }
    }
}

@Composable
private fun LocatingSurface(footerHeight: Dp, cardHeight: Dp, isHidden: Boolean, modifier: Modifier = Modifier) {
    val findingText = stringResource(R.string.composer_location_finding)
    Column(
        modifier
            .size(SharedLocationCard.compactWidth, cardHeight)
            .then(
                if (isHidden) Modifier.clearAndSetSemantics {}
                else Modifier.clearAndSetSemantics {
                    contentDescription = findingText
                    testTag = "composer-location-card"
                },
            ),
    ) {
        Box(
            Modifier
                .size(SharedLocationCard.compactWidth, SharedLocationCard.compactMapHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { MapPin() }
        Column(
            Modifier
                .fillMaxWidth()
                .height(footerHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    findingText,
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = OpenCodeTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.composer_location_current_coordinates),
                style = OpenCodeTextStyles.caption1,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The composer attachments' remove button (`xmark.circle.fill`, white on 65 % black) over a
 * card's top trailing corner, with a 48 dp target.
 */
@Composable
internal fun ComposerRemoveBadge(label: String, testTag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clickable(role = Role.Button, onClickLabel = null, onClick = onClick)
            .openCodeTapTarget()
            .semantics {
                contentDescription = label
                this.testTag = testTag
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).background(Color.White, RoundedCornerShape(50)))
        OpenCodeIcon(SFSymbol.XmarkCircleFill, contentDescription = null, size = 22.dp, tint = Color.Black.copy(alpha = 0.65f))
    }
}

// endregion
