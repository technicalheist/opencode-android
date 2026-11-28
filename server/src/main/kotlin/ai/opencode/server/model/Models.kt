package ai.opencode.server.model

import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.strings.localized
import ai.opencode.server.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class OpenCodeProject(
    val id: String,
    val name: String,
    val directory: String,
    /** Repository name from the git origin remote, when reported by the server. */
    val repository: String? = null,
    /** Project icon metadata (`url`, `override`, `color`). */
    val icon: OpenCodeProjectIcon? = null,
) {
    /** The uppercase fallback initial for this project's tile. */
    val initial: String get() = ProjectMark.initial(ProjectMark.displayName(name, repository, directory))

    /** The resolved icon source following desktop precedence. */
    val avatarSource: String? get() = ProjectMark.avatarSource(id, icon)

    /**
     * The computer's root or a person's home folder (`/Users/kit`, `/home/kit`, `/root`,
     * `C:\Users\kit`): where sessions land when no project was chosen, not a project someone
     * works in.
     */
    val isHomeFolder: Boolean
        get() {
            var parts = directory.replace('\\', '/').split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
            parts.firstOrNull()?.let { drive -> if (drive.length == 2 && drive.endsWith(":")) parts = parts.drop(1) }
            return when (parts.size) {
                0 -> true
                1 -> parts[0] == "root" || parts[0] == "~"
                2 -> parts[0] == "users" || parts[0] == "home" || parts == listOf("var", "root")
                else -> false
            }
        }

    companion object {
        /** A folder chosen on the server: the known project at that directory, or one named by the folder. */
        fun folder(directory: String, projects: List<OpenCodeProject>): OpenCodeProject {
            projects.firstOrNull { it.directory == directory }?.let { return it }
            val name = ProjectMark.folderName(directory)
            return OpenCodeProject("folder:$directory", name.ifEmpty { directory }, directory)
        }
    }
}

/** One folder on the server and the folders directly inside it, as absolute paths (`GET /api/fs/list`). */
data class OpenCodeDirectoryListing(val directory: String, val folders: List<String>)

@Serializable
data class OpenCodeModelRef(
    val id: String,
    val providerID: String,
    /** The reasoning variant (`high`, `max`, …); null is the model's default. */
    val variant: String? = null,
) {
    /** The same model, ignoring the variant. */
    fun sameModel(other: OpenCodeModelRef): Boolean = id == other.id && providerID == other.providerID
}

data class OpenCodeModel(
    val modelID: String,
    val providerID: String,
    val name: String,
    /** `capabilities.input`: the media the model reads natively ("image", "pdf", …). */
    val inputs: Set<String> = emptySet(),
    /** `limit.context`: the model's context window in tokens. */
    val contextLimit: Int? = null,
    /** The catalog key (`Model.Info.id`) when it differs from the API model ID. */
    val catalogID: String? = null,
    /** Reasoning variants (`low`, `high`, `max`, …), in the server's order. */
    val variants: List<String> = emptyList(),
    /** `canonical`: the provider this one stands for, for its icon. */
    val canonicalProviderID: String? = null,
) {
    /** The catalog key: unique per provider, unlike [modelID]. */
    val key: String get() = catalogID ?: modelID
    val id: String get() = "$providerID/$key"
    val ref: OpenCodeModelRef get() = ref(null)

    /** Compact display name without a redundant vendor or umbrella word (`"Claude Opus 5.5"` → `"Opus 5.5"`). */
    val shortName: String get() = computeShortName(name, providerID, canonicalProviderID)

    fun matches(ref: OpenCodeModelRef): Boolean = providerID == ref.providerID && key == ref.id

    fun ref(variant: String?): OpenCodeModelRef = OpenCodeModelRef(key, providerID, variant)

    companion object {
        private val vendorPrefixes = listOf(
            "OpenCode Zen", "OpenCode", "GitHub Copilot", "OpenRouter",
            "Amazon Bedrock", "Amazon", "Google Vertex", "Google",
            "Anthropic", "OpenAI", "Meta AI", "Meta", "Microsoft", "Azure",
            "xAI", "Cohere", "Alibaba", "Moonshot AI", "Moonshot",
            "Zhipu AI", "Zhipu", "Fireworks AI", "Fireworks", "Novita", "NVIDIA",
            "DeepSeek", "Mistral AI", "Mistral",
        )

        private val familyPrefixes = listOf("Claude", "Gemini", "Mistral", "DeepSeek", "Grok", "Llama", "Qwen")

        private val genericTierWords = setOf(
            "pro", "flash", "mini", "nano", "lite", "fast", "ultrafast", "ultra",
            "max", "plus", "turbo", "large", "medium", "small", "coder", "code",
            "instruct", "chat", "thinking", "reasoning", "preview", "experimental",
            "free", "open", "super", "lightning", "spark", "latest",
        )

        private fun computeShortName(raw: String, providerID: String, canonicalProviderID: String?): String {
            var result = raw.trimmedWhitespace()
            if (result.isEmpty()) return raw
            val dynamicVendors = listOfNotNull(
                ModelProviderGroup.canonicalVendorTitle(providerID),
                canonicalProviderID?.let(ModelProviderGroup::canonicalVendorTitle),
            )
            for (prefix in dynamicVendors + vendorPrefixes) {
                val stripped = stripPrefix(prefix, result, requireAlphabeticSubmodel = false)
                if (stripped != null) {
                    result = stripped
                    break
                }
            }
            for (family in familyPrefixes) {
                val stripped = stripPrefix(family, result, requireAlphabeticSubmodel = true)
                if (stripped != null) {
                    result = stripped
                    break
                }
            }
            return result
        }

        private fun stripPrefix(prefix: String, text: String, requireAlphabeticSubmodel: Boolean): String? {
            if (text.length <= prefix.length || !text.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null
            val tail = text.substring(prefix.length)
            val firstSep = tail.firstOrNull() ?: return null
            if (firstSep != ' ' && firstSep != ':' && firstSep != '/') return null
            val hasExplicitDelimiter = tail.startsWith(":") || tail.startsWith(" /") || tail.startsWith("/")
            val remainder = tail.dropWhile { it == ' ' || it == ':' || it == '/' || it == '-' }
            if (remainder.isEmpty()) return null
            val firstToken = remainder.takeWhile { !it.isWhitespace() && it != '(' }
            val firstChar = firstToken.firstOrNull() ?: return null
            if (!firstChar.isLetter()) return null
            if (firstToken.lowercase() in genericTierWords) return null
            if (requireAlphabeticSubmodel) {
                if (!firstToken.all(Char::isLetter)) return null
            } else if (!hasExplicitDelimiter) {
                // Without ":" or "/", keep family names whose next token is just a version ("DeepSeek V4.1", "Mistral 7B").
                if ((prefix.equals("DeepSeek", ignoreCase = true) || prefix.equals("Mistral", ignoreCase = true)) &&
                    !firstToken.all(Char::isLetter)
                ) return null
            }
            return remainder
        }
    }
}

/**
 * The entry a ref names. Refs name catalogue keys: an API ID shared by Fast/Ultrafast siblings
 * must never select one of those modes in place of the base model.
 */
fun List<OpenCodeModel>.model(ref: OpenCodeModelRef): OpenCodeModel? = firstOrNull { it.matches(ref) }

/** Short names shared by more than one model, so rows with those names show their provider. */
val List<OpenCodeModel>.ambiguousShortNames: Set<String>
    get() {
        val counts = HashMap<String, Int>()
        val seen = HashSet<String>()
        for (model in this) if (seen.add(model.id)) counts[model.shortName] = (counts[model.shortName] ?: 0) + 1
        return counts.filterValues { it > 1 }.keys
    }

/** How the app names a reasoning variant, and orders a model's variants. */
object ModelVariant {
    private val order = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    fun label(id: String): String = when (id) {
        "none" -> localized(R.string.server_model_variant_off)
        "xhigh" -> localized(R.string.server_model_variant_extra_high)
        "max" -> localized(R.string.server_model_variant_max)
        else -> id.take(1).uppercase() + id.drop(1)
    }

    /** Known efforts from lowest to highest; anything else keeps the server's order, after them. */
    fun sorted(ids: List<String>): List<String> = ids.withIndex().sortedBy { (offset, element) ->
        order.indexOf(element).takeIf { it >= 0 } ?: (order.size + offset)
    }.map { it.value }
}

data class OpenCodeModelCatalog(val models: List<OpenCodeModel>, val defaultModel: OpenCodeModel?) {
    /**
     * The model a new session starts on: the server's default (`model` in the person's OpenCode
     * config), at the level last picked for it, since the default carries no variant. Null when the
     * server names none.
     */
    fun newSessionModel(levels: Map<String, String>): OpenCodeModelRef? {
        val named = defaultModel ?: return null
        val model = models.model(named.ref) ?: named
        return model.ref(model.savedLevel(levels))
    }
}

/**
 * The level last picked for this model (`modelVariants`, by [OpenCodeModel.id]), while the model
 * still offers it; null is its default. Lives here, not beside `ModelLevelScale` in
 * `:feature:composer`, because the voice delegator reads it too. A model with only `"default"`
 * isn't adjustable (`ModelLevelScale.isAdjustable`).
 */
fun OpenCodeModel.savedLevel(saved: Map<String, String>): String? {
    val isAdjustable = variants.any { it != "default" }
    if (!isAdjustable) return null
    val level = saved[id] ?: return null
    if (level == "default" || level !in variants) return null
    return level
}

@Serializable
data class OpenCodeSession(
    val id: String,
    /**
     * `Session.Info.title`, or [SessionTitle.placeholder] while the server hasn't named the session.
     * Surfaces show [title]; this is for renames and the "is it named yet" checks.
     */
    @SerialName("title") val serverTitle: String,
    val projectID: String,
    val directory: String,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    val idleMilliseconds: Long? = null,
    val viewedMilliseconds: Long? = null,
    val model: OpenCodeModelRef? = null,
    val parentID: String? = null,
    val agent: String? = null,
    /** Spend in USD for this session alone (`Session.Info.cost`). */
    val cost: Double? = null,
    /** Always Notify (`metadata.notification.always`). */
    val alwaysNotify: Boolean = false,
    /** A staged undo (V2's `Session.Revert`): this message and everything after it are hidden. */
    val revertMessageID: String? = null,
    /**
     * The session's first prompt as a one-line [SessionTitle.preview], once known: from the prompt
     * this phone created it with, or a loaded transcript that reaches its start. Only an unnamed
     * session learns one; it rides cached Home snapshots so a cold launch shows it too.
     */
    val firstPrompt: String? = null,
    /** When the session was archived (`time.archived`); null while it isn't. */
    val archivedMilliseconds: Long? = null,
) {
    /** What every surface calls this session: the server's title, else its first prompt ([SessionTitle.display]). */
    val title: String get() = SessionTitle.display(serverTitle, firstPrompt)

    /** Archived sessions stay out of Recents, search, Catch Up and the system's lists, as on desktop. */
    val isArchived: Boolean get() = archivedMilliseconds != null
}

/** The context window the session's latest reply filled, as the TUI measures it. */
data class OpenCodeContextUsage(val tokens: Int, val model: OpenCodeModelRef)

/** One read of the session's tail for context usage. */
sealed interface OpenCodeContextReading {
    data class Measured(val usage: OpenCodeContextUsage) : OpenCodeContextReading
    /** Nothing fills the context: a fresh session or one just compacted. */
    data object Empty : OpenCodeContextReading
    /** The read window held no answer either way; keep what is shown. */
    data object Inconclusive : OpenCodeContextReading
}

/** A session export's file-ready content. */
enum class OpenCodeExportFormat(val rawValue: String, val fileExtension: String) {
    Markdown("markdown", "md"),
    Json("json", "json"),
}

data class OpenCodeSessionFamily(val sessions: List<OpenCodeSession>, val activeSessionIDs: Set<String>)

/**
 * A background job (`Job.Info`, experimental). A subagent job's work runs in [childSessionID];
 * every other kind's runs in [sessionID].
 */
data class OpenCodeJob(
    val id: String,
    val kind: Kind,
    val sessionID: String,
    val isRunning: Boolean,
    val childSessionID: String? = null,
    val startedAt: Instant? = null,
) {
    enum class Kind(val rawValue: String) {
        Shell("shell"), Subagent("subagent"), Image("image"), Video("video"), CodeMode("codemode"), Unknown("");

        companion object {
            fun of(raw: String): Kind = entries.firstOrNull { it.rawValue == raw } ?: Unknown
        }
    }

    /** The session whose tree the work keeps busy. */
    val ownerSessionID: String get() = if (kind == Kind.Subagent) childSessionID ?: sessionID else sessionID
}

/** A running server shell (`Shell.Info`). `metadata.sessionID` names the owner. */
data class OpenCodeShell(
    val id: String,
    val command: String,
    val sessionID: String?,
    /** User `!` commands are created with `metadata.background`. */
    val isUserCommand: Boolean = false,
    /** Read from `GET /api/shell` rather than observed starting live. */
    val isListed: Boolean = false,
    val status: Status = Status.Running,
    /** Set once the command exits on its own. */
    val exitCode: Int? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
) {
    enum class Status(val rawValue: String) {
        Running("running"), Exited("exited"), Timeout("timeout"), Killed("killed");

        companion object {
            fun of(raw: String): Status? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    val title: String get() = command.firstLine
}

/** One page of a shell's captured combined output. Cursors are absolute byte offsets. */
@Serializable
data class OpenCodeShellOutputPage(
    val output: String,
    /** The byte after this page: where the next read starts. */
    val cursor: Int,
    /** Bytes captured so far. */
    val size: Int,
)

@Serializable
data class OpenCodeHomeSnapshot(
    val projects: List<OpenCodeProject>,
    val sessions: List<OpenCodeSession>,
    val activeSessionIDs: Set<String>,
    /** Only ancestry for active sessions; optional for older on-disk snapshots. */
    val activeSessionParents: Map<String, String>? = null,
    /** The cursor of the page after `sessions`; null at the end of the list. */
    val nextCursor: String? = null,
)

/** One page of root, unarchived sessions, newest first (`GET /api/session`). */
data class OpenCodeSessionPage(
    val sessions: List<OpenCodeSession>,
    /** The cursor of the older page; null at the end of the list. */
    val next: String?,
)

sealed interface OpenCodeSessionPageRequest {
    /** The newest page whose titles match `text`. */
    data class Search(val text: String) : OpenCodeSessionPageRequest

    /** The page after one already read; the cursor carries its filters. */
    data class After(val cursor: String) : OpenCodeSessionPageRequest
}

object SessionActivity {
    fun root(sessionID: String, parents: Map<String, String>): String {
        var id = sessionID
        val seen = hashSetOf(id)
        while (true) {
            val parent = parents[id] ?: break
            if (!seen.add(parent)) break
            id = parent
        }
        return id
    }

    fun ancestors(active: Set<String>, parents: Map<String, String>): Set<String> {
        val busy = active.toMutableSet()
        for (id in active) {
            var current = id
            val visited = hashSetOf(id)
            while (true) {
                val parent = parents[current] ?: break
                if (!visited.add(parent)) break
                busy += parent
                current = parent
            }
        }
        return busy
    }

    /** The parent links on the chains above [active]: all a cache needs. */
    fun ancestry(active: Set<String>, parents: Map<String, String>): Map<String, String> {
        val links = LinkedHashMap<String, String>()
        for (id in active) {
            var current = id
            while (true) {
                val parent = parents[current] ?: break
                if (links.containsKey(current)) break
                links[current] = parent
                current = parent
            }
        }
        return links
    }

    /** The members of [family] below [id], at any depth, in family order. */
    fun descendants(id: String, family: List<OpenCodeSession>): List<OpenCodeSession> {
        val below = hashSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (member in family) {
                if (member.id in below) continue
                val parent = member.parentID ?: continue
                if (parent in below) {
                    below += member.id
                    grew = true
                }
            }
        }
        return family.filter { it.id != id && it.id in below }
    }
}

/** Inbox delivery for a prompt sent while the session is running. */
enum class OpenCodeDelivery(val rawValue: String) {
    Steer("steer"), Queue("queue");

    val pendingLabel: String get() = if (this == Steer) localized(R.string.server_delivery_steering) else localized(R.string.server_delivery_queued)

    companion object {
        fun of(raw: String): OpenCodeDelivery? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The share of an [Instant] built from a millisecond epoch `Double`, keeping sub-millisecond precision. */
internal fun instantFromMillis(milliseconds: Double): Instant {
    val seconds = Math.floorDiv(milliseconds.toLong(), 1000L)
    val nanos = ((milliseconds - seconds * 1000.0) * 1_000_000.0).toLong()
    return Instant.ofEpochSecond(seconds, nanos)
}
