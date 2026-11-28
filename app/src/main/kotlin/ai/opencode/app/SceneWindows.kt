package ai.opencode.app

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.scene.ActiveScene
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.ui.ThisDevice
import ai.opencode.feature.drawer.AppAwayDate
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.restoringInitialSession
import ai.opencode.feature.drawer.wasAwayLongEnoughForNewSession
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SceneWindowClient
import ai.opencode.feature.system.SessionWindowTarget
import ai.opencode.feature.system.WindowsClient
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModel
import java.lang.ref.WeakReference

// Multiple Windows on a tablet, each a task running `MainActivity` (`singleInstancePerTask`) with
// its own [RootStore]. A phone has one window: every multi-window path checks
// [SceneWindows.isSupported].

/** A further window's state, the saved server's Home restored on [target]. */
fun AppFeature.State.Companion.window(target: SessionWindowTarget?): AppFeature.State {
    val state = initial()
    val home = state.home ?: return state
    val serverID = home.server.persistenceID
    val remembered = if (target != null) {
        if (target.serverID == serverID) mapOf(serverID to target.sessionID) else emptyMap()
    } else {
        home.lastViewedSessionIDs.filterKeys { it == serverID }
    }
    return state.copy(home = home.copy(isWindowLocal = true, lastViewedSessionIDs = remembered).restoringInitialSession())
}

/** A call is live in this window (the root session owns calls). */
val AppFeature.State.hasLiveCall: Boolean
    get() = home?.sessionDetail?.voice?.phase?.isTerminal == false

/**
 * Work that belongs to the app rather than a window, run only by the window [WindowRoster] gives it
 * to.
 */
val AppFeature.Action.isAppWide: Boolean
    get() = this == AppFeature.Action.SystemActionsTask ||
        this == AppFeature.Action.PendingSharesRequested ||
        (this is AppFeature.Action.Home && action is HomeFeature.Action.SystemPublishRequested)

/**
 * The root reducer of every window's store. App-wide work runs only in the owning window, and only
 * one window may have a call.
 */
class WindowAppFeature(
    private val base: Reducer<AppFeature.State, AppFeature.Action>,
    private val window: RootStore,
) : Reducer<AppFeature.State, AppFeature.Action> {
    override fun reduce(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
        if (action.isAppWide && !SceneWindows.ownsAppWideWork(window)) return Reduced(state)
        if (!SceneWindows.isSupported) return base.reduce(state, action)
        val hadCall = state.hasLiveCall
        if (!hadCall && SceneWindows.callIsElsewhere(window)) {
            val reduced = base.reduce(state, action)
            if (!reduced.state.hasLiveCall) return reduced
            dependency(HapticsClient).play(Haptic.warning)
            AppLog.voice.notice("Call refused: another window has one")
            return Reduced(state)
        }
        val reduced = base.reduce(state, action)
        val hasCall = reduced.state.hasLiveCall
        if (hasCall != hadCall) SceneWindows.noteCall(window, active = hasCall)
        return reduced
    }
}

/**
 * The window side of windows. `MainActivity` is `singleInstancePerTask`, so a tablet can open
 * further windows (each a task); [WindowRoster] decides what each one does.
 */
object SceneWindows {
    /** A tablet, as `ThisDevice`. */
    val isSupported: Boolean by lazy { ThisDevice.isTablet }

    val roster: WindowRoster by lazy { WindowRoster(multipleWindows = isSupported) }
    val appWideWork: AppWideWork by lazy { AppWideWork(roster) }

    /** The session a new window was asked to show, until that window's root claims it. */
    private val pendingTargets = ArrayList<SessionWindowTarget>()

    /** The window whose store has the live call; a call starts in one window at a time. */
    private var callOwner: WeakReference<RootStore>? = null

    /** The window last resumed. */
    private var active: WeakReference<RootStore>? = null

    private val windows: List<RootStore> get() = roster.members.filterIsInstance<RootStore>()

    /** Installs the live [WindowsClient]. */
    fun install() {
        DependencyValues.installRoot {
            this[WindowsClient] = WindowsClient(isAvailable = { isSupported }, open = ::open)
        }
    }

    internal fun join(window: RootStore): WindowRoster.Role {
        val role = window.roster.join(window)
        AppLog.app.notice("Window joined role=${role.name.lowercase()} windows=${window.roster.members.size}")
        return role
    }

    internal fun attached(window: RootStore) = window.roster.attached(window)

    internal fun detached(window: RootStore) {
        AppLog.app.notice("Window scene disconnected role=${window.role?.name?.lowercase() ?: "nil"}")
        window.roster.detached(window)
    }

    internal fun resumed(window: RootStore) {
        active = WeakReference(window)
    }

    /** Does [window] do the app-wide work? Every store does while no window owns it. */
    fun ownsAppWideWork(window: RootStore): Boolean {
        val owner = roster.appWideOwner as? RootStore ?: return true
        return owner === window
    }

    /**
     * Where a notification tap goes: the window showing that session, else the one in use on that
     * server, else the one doing the app-wide work.
     */
    fun window(destination: NotificationDestination, receiving: RootStore): RootStore =
        window(destination, roster, appWideWork, active?.get()) ?: receiving

    /** [window] for a given roster, work and window in use. */
    fun window(destination: NotificationDestination, roster: WindowRoster, work: AppWideWork, active: RootStore?): RootStore? {
        val owner = work.window ?: roster.appWideOwner as? RootStore
        if (!roster.multipleWindows) return owner
        val onServer = roster.connectedMembers.filterIsInstance<RootStore>().filter {
            it.store.state.home?.server?.persistenceID == destination.serverKey
        }
        onServer.firstOrNull { it.store.state.home?.sessionDetail?.session?.id == destination.sessionID }?.let { return it }
        active?.takeIf { it in onServer }?.let { return it }
        return owner
    }

    fun callIsElsewhere(window: RootStore): Boolean {
        val owner = callOwner?.get() ?: return false
        return owner !== window
    }

    fun noteCall(window: RootStore, active: Boolean) {
        val owner = callOwner?.get()
        if (active) {
            if (owner == null) callOwner = WeakReference(window)
        } else if (owner === window) {
            callOwner = null
        }
    }

    /** A new window's intent carries the session it should show. */
    internal fun noteConnecting(target: SessionWindowTarget) {
        if (isSupported) pendingTargets += target
    }

    internal fun takePendingTarget(): SessionWindowTarget? = pendingTargets.removeFirstOrNull()

    /** Opens [target] in a window of its own beside this one. */
    fun open(target: SessionWindowTarget) {
        if (!isSupported) return
        val context = dependency(AppContext)
        val intent = intent(context, target)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.app.error { "Couldn’t open a window: ${it::class.java.simpleName}" } }
    }

    /** The intent that opens [target] in a window of its own ([RootStore.target] reads it back). */
    internal fun intent(context: Context, target: SessionWindowTarget): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(SessionWindowTarget.ACTION)
            .putExtra(SessionWindowTarget.EXTRA_SERVER_ID, target.serverID)
            .putExtra(SessionWindowTarget.EXTRA_SESSION_ID, target.sessionID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

    /** Brings [window]'s task to the front, when a tap in another window is meant for it. */
    internal fun bringToFront(window: RootStore, from: Context) {
        val taskID = window.taskID ?: return
        val manager = from.getSystemService(ActivityManager::class.java) ?: return
        manager.appTasks.firstOrNull { runCatching { it.taskInfo?.taskId }.getOrNull() == taskID }?.moveToFront()
    }

    internal val all: List<RootStore> get() = windows
}

/**
 * One window's part, retained across configuration changes: its role ([WindowRoster]) and its
 * store, resolved on the first `onCreate`.
 */
class RootStore(
    internal val roster: WindowRoster = SceneWindows.roster,
    private val appWideWork: AppWideWork = SceneWindows.appWideWork,
    private val makeStore: (SessionWindowTarget?, RootStore) -> Store<AppFeature.State, AppFeature.Action> = { target, window -> secondaryStore(target, window) },
) : ViewModel(), WindowRosterMember {
    var role: WindowRoster.Role? = null
        private set
    lateinit var store: Store<AppFeature.State, AppFeature.Action>
        private set

    /** The session the window was opened on, until its Home shows it. */
    var pendingTarget: SessionWindowTarget? = null

    /** The window's task, for routing a notification tap to it. */
    var taskID: Int? = null
        private set
    private var activity = WeakReference<Activity>(null)
    var isClosed = false
        private set

    /** The store exists (it is made on the first `onCreate`). */
    val isResolved: Boolean get() = ::store.isInitialized && !isClosed

    /** A tablet's window follows the session it shows; a phone's one window doesn't. */
    val follows: Boolean get() = roster.multipleWindows

    /** The session shown, as the window's restorable value. */
    val shown: SessionWindowTarget?
        get() {
            val home = store.state.home ?: return null
            val id = home.sessionDetail?.session?.id ?: return null
            return SessionWindowTarget(serverID = home.server.persistenceID, sessionID = id)
        }

    fun resolve(activity: Activity, target: SessionWindowTarget?) {
        this.activity = WeakReference(activity)
        taskID = activity.taskId
        resolve(target) {
            Store(AppFeature.State.initial(), WindowAppFeature(AppFeature(), this), dependencies = windowDependencies())
        }
    }

    /**
     * The first time, joins [roster] and makes the window's store, [primary] for the primary role,
     * else [makeStore]; then the window is attached.
     */
    fun resolve(
        target: SessionWindowTarget?,
        primary: () -> Store<AppFeature.State, AppFeature.Action>,
    ): Store<AppFeature.State, AppFeature.Action> {
        if (role == null) {
            // Restored with a session, or opened by Open in New Window.
            val restoredTarget = if (roster.multipleWindows) target ?: SceneWindows.takePendingTarget() else null
            val role = SceneWindows.join(this)
            store = if (role == WindowRoster.Role.Primary) primary() else makeStore(restoredTarget, this)
            this.role = role
            pendingTarget = restoredTarget
            // The owner may show another store now.
            appWideWork.sync()
        }
        SceneWindows.attached(this)
        return store
    }

    /**
     * On a tablet, haptics and announcements play only in the window in use; a phone's one window
     * keeps the app's tracker.
     */
    internal fun windowDependencies(): DependencyValues {
        if (!SceneWindows.isSupported) return DependencyValues.root
        return DependencyValues.root.with(
            SceneWindowClient,
            SceneWindowClient(
                front = { activity.get()?.let { !it.isFinishing && it.hasWindowFocus() } ?: false },
                // The window's own id, so the browser's links open beside it.
                scene = { activity.get()?.let(ActiveScene::sceneID) },
            ),
        )
    }

    /** The activity is finishing: its window (task) is gone. */
    fun activityFinished() {
        SceneWindows.detached(this)
    }

    override fun rosterAssigned(role: WindowRoster.Role) {
        AppLog.app.notice("Window role=${role.name.lowercase()}")
        this.role = role
    }

    override fun rosterOwnsAppWideWork(owns: Boolean) {
        if (owns) AppLog.app.notice("Window took the app-wide work role=${role?.name?.lowercase() ?: "nil"}")
    }

    override fun onCleared() {
        if (role != null) {
            SceneWindows.noteCall(this, active = false)
            isClosed = true
            store.close()
            roster.left(this)
        }
    }

    companion object {
        /** A further window's own store. */
        fun secondaryStore(target: SessionWindowTarget?, window: RootStore): Store<AppFeature.State, AppFeature.Action> =
            Store(AppFeature.State.window(target), WindowAppFeature(AppFeature(), window), dependencies = window.windowDependencies())

        fun target(intent: Intent?, saved: Bundle?): SessionWindowTarget? {
            fun read(server: String?, session: String?) =
                if (server != null && session != null) SessionWindowTarget(server, session) else null
            val opensWindowTarget = intent?.action == SessionWindowTarget.ACTION
            // After a long absence the first window opens a new session rather than the one it showed.
            val restored = if (opensWindowTarget || !wasAwayLongEnoughForNewSession(dependency(AppAwayDate).now())) saved else null
            restored?.let { read(it.getString(SessionWindowTarget.EXTRA_SERVER_ID), it.getString(SessionWindowTarget.EXTRA_SESSION_ID)) }
                ?.let { return it }
            if (!opensWindowTarget) return null
            return read(intent.getStringExtra(SessionWindowTarget.EXTRA_SERVER_ID), intent.getStringExtra(SessionWindowTarget.EXTRA_SESSION_ID))
        }
    }
}

/**
 * Runs the app-wide tasks in the owning window's store. It moves inside every roster change; with
 * no owner it stays where it is.
 */
class AppWideWork(private val roster: WindowRoster) {
    /** The window whose store listens to the inbox. */
    var window: RootStore? = null
        private set

    /** The store listening to the inbox. */
    val store: Store<AppFeature.State, AppFeature.Action>? get() = window?.takeUnless { it.isClosed }?.store

    init {
        roster.onSettle = { sync() }
    }

    fun sync() {
        val owner = roster.appWideOwner as? RootStore ?: return
        if (owner.role == null || owner === window) return
        val before = window
        // It follows the store, not the window: an owner showing the same store carries on.
        if (before != null && !before.isClosed && before.store === owner.store) {
            window = owner
            return
        }
        // The inbox has one listener; this takes it from the store before.
        if (before != null && !before.isClosed) before.store.send(AppFeature.Action.SystemActionsTaskEnded)
        window = owner
        AppLog.app.notice("App-wide work moved windows=${roster.members.size}")
        owner.store.send(AppFeature.Action.SystemActionsTask)
        owner.store.send(AppFeature.Action.PendingSharesRequested)
    }
}
