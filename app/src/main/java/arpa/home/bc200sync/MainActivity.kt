package arpa.home.bc200sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toolbar
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Connect lists the rides; Sync now copies them: BC200 -> phone folder (read back and compared) -> delete on the BC200
 * (confirmed by re-reading the ride list). Rides stay in the phone folder; the Nextcloud app's
 * auto upload copies them to the server.
 *
 * ☰ → Settings → BC200 settings changes the device's own settings; see [DeviceSettings].
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity(), Bc200Client.Listener {

    private companion object {
        val SERVICE: UUID = UUID.fromString("0000fda0-0000-1000-8000-00805f9b34fb")
        const val SCAN_MS = 30_000L
        const val PERMISSION_REQUEST = 1
        const val FOLDER_REQUEST = 2
        const val PREFS = "bc200"
        const val KEY_TZ_AUTO = "time_zone_auto"
        const val KEY_KEEP = "keep_rides_on_bc200"
        const val SETTINGS_SUBTITLE = "Bike, User, Device"
        const val KEY_TREE = "tree"
        const val FILE_MIME = "application/octet-stream"
    }

    private lateinit var pal: Palette
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var folderText: TextView
    private lateinit var connectButton: Button
    private lateinit var cardFrame: android.graphics.drawable.GradientDrawable
    private lateinit var settingsItem: LinearLayout
    private lateinit var syncButton: Button
    private lateinit var settingsBar: Toolbar
    private lateinit var settingsScroll: ScrollView
    private lateinit var pageHost: FrameLayout
    private val pageViews = HashMap<DeviceSettings.Item.Page, View>()
    private val menuSubtitles = LinkedHashMap<DeviceSettings.Item.Page, TextView>()
    private val pageStack = ArrayList<DeviceSettings.Item.Page>()

    // Time zone "Auto (phone)": the app keeps the BC200's zone equal to the phone's current UTC
    // offset (which includes summer time). Stored in the app; changed only by saving.
    private var pendingTzAuto: Boolean? = null
    private var tzChecked = false
    private var tzAutoToSave: Boolean? = null // the Auto choice being saved, kept until read-back
    private lateinit var progress: ProgressBar
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private var drawerOpen = false

    // BC200 settings page
    private lateinit var settingsPage: LinearLayout
    private lateinit var settingsMsg: TextView
    private lateinit var settingsProgress: ProgressBar
    private lateinit var saveButton: Button
    private var settingsOpen = false
    private var settingsBusy = false
    private var settingsFile: ByteArray? = null
    private var deviceValues: Map<DeviceSettings.Option, String?> = emptyMap()
    private val pending = LinkedHashMap<DeviceSettings.Option, String>()
    private val optionRows = HashMap<DeviceSettings.Option, View>()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var client: Bc200Client
    private var rides: List<Protocol.Ride> = emptyList()
    private var scanning = false
    private var queue = ArrayDeque<Protocol.Ride>()
    private var failures = 0
    private var copiedCount = 0
    private var onPhone: Map<String, Long> = emptyMap() // ride name -> size in the phone sync folder
    private val savedThisRun = ArrayList<Protocol.Ride>() // rides saved on the phone since the app started
    private lateinit var ridesEmpty: TextView
    private lateinit var phoneTitle: TextView
    private lateinit var phoneEmpty: TextView
    private lateinit var phoneAdapter: ArrayAdapter<String>
    private lateinit var afterSyncValue: TextView
    private lateinit var appPage: LinearLayout
    private var appOpen = false
    private lateinit var aboutPage: LinearLayout
    private var aboutOpen = false
    private lateinit var ridePage: LinearLayout
    private lateinit var rideBar: Toolbar
    private lateinit var rideContent: LinearLayout
    private lateinit var rideScroll: ScrollView
    private var rideOpen = false
    private var rideShown: String? = null // file name of the ride being shown or loaded
    private var phoneRides: List<Protocol.Ride> = emptyList() // rows of the "on this phone" list
    private val removedFromDevice = LinkedHashSet<String>()
    private var verifyingDeletes = false
    private var syncRequested = false // set by Sync now; Connect only reads the ride list

    @Volatile private var destroyed = false

    private val stopScanRunnable = Runnable {
        stopScan()
        onStatus("BC200 not found. Wake it up, and disconnect any other phone.")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
            val hasService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true
            if (!scanning || !(name.uppercase().startsWith("BC200") || hasService)) return
            stopScan()
            client.connect(result.device)
            updateButtons()
        }

        override fun onScanFailed(code: Int) {
            stopScan()
            onError("Bluetooth scan failed (code $code).")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        client = Bc200Client(this, this)
        pal = Palette(this)
        val pad = dp(16)
        val version = "Version " + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?")

        // Top bar with the hamburger button.
        val topBar = Toolbar(this).apply {
            title = "Coospo BC200 sync"
            setTitleTextColor(pal.onSurface)
            setBackgroundColor(pal.surface)
            navigationIcon = getDrawable(R.drawable.ic_menu)!!.mutate().apply { setTint(pal.onSurface) }
            navigationContentDescription = "Menu"
            setNavigationOnClickListener { if (drawerOpen) closeDrawer() else openDrawer() }
        }

        // Status card: what the app is doing and transfer progress. Device info sits above it.
        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(pal.onSurface)
            text = "Tap Connect to find your BC200."
        }
        info = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(pal.onSurfaceVariant)
            visibility = View.GONE // shown once a BC200 has reported its battery, firmware, storage
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(pal.primary)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            cardFrame = rounded(pal.card, dp(24).toFloat())
            background = cardFrame
            setPadding(dp(20), dp(20), dp(20), dp(12))
            addView(status)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        // Main actions.
        connectButton = pillButton("Connect", pal.primary, pal.onPrimary) { onConnectClicked() }
        syncButton = pillButton("Sync now", pal.tonal, pal.onTonal) { syncNow() }

        // Rides still on the BC200.
        val ridesTitle = TextView(this).apply {
            text = "Rides on the BC200"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(pal.primary)
            setPadding(dp(8), 0, 0, dp(2))
        }
        fun rideAdapter() = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(pal.onSurface)
                    textSize = 16f
                }
        }
        adapter = rideAdapter()
        phoneAdapter = rideAdapter()
        ridesEmpty = TextView(this).apply {
            text = "Not connected."
            textSize = 15f
            setTextColor(pal.onSurfaceVariant)
            setPadding(dp(8), dp(8), 0, 0)
        }
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            emptyView = ridesEmpty
        }
        val listBox = FrameLayout(this).apply {
            addView(ridesEmpty)
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Rides on the phone: local files in the sync folder (read when the app starts).
        phoneTitle = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(pal.primary)
            setPadding(dp(8), 0, 0, dp(2))
        }
        phoneEmpty = TextView(this).apply {
            textSize = 15f
            setTextColor(pal.onSurfaceVariant)
            setPadding(dp(8), dp(8), 0, 0)
        }
        val phoneList = ListView(this).apply {
            adapter = phoneAdapter
            divider = null
            emptyView = phoneEmpty
            setOnItemClickListener { _, _, pos, _ -> phoneRides.getOrNull(pos)?.let { openRide(it.name) } }
        }
        val phoneBox = FrameLayout(this).apply {
            addView(phoneEmpty)
            addView(phoneList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val versionText = TextView(this).apply {
            text = version
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(pal.onSurfaceVariant)
            setPadding(0, dp(8), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            // Connect and Sync now side by side, equal width.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(connectButton, LinearLayout.LayoutParams(0, dp(56), 1f))
                    addView(syncButton, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(12) })
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) },
            )
            addView(listCard(ridesTitle, listBox), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(24) })
            addView(listCard(phoneTitle, phoneBox), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
            addView(versionText)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }

        buildDrawer(version)
        val settingsContent = buildSettingsPage()
        val appContent = buildAppPage()
        val aboutContent = buildAboutPage(version)
        buildRidePage()
        val root = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(ridePage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(settingsPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(appPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(aboutPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(drawer, FrameLayout.LayoutParams(drawerWidth(), MATCH_PARENT, Gravity.START))
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                val l = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                val t = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val r = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                val b = insets.systemWindowInsetBottom
                topBar.setPadding(l, t, r, 0)
                (settingsPage.getChildAt(0) as Toolbar).setPadding(l, t, r, 0)
                settingsContent.setPadding(pad + l, 0, pad + r, pad + b)
                (appPage.getChildAt(0) as Toolbar).setPadding(l, t, r, 0)
                appContent.setPadding(pad + l, 0, pad + r, pad + b)
                (aboutPage.getChildAt(0) as Toolbar).setPadding(l, t, r, 0)
                aboutContent.setPadding(pad + l, 0, pad + r, pad + b)
                rideBar.setPadding(l, t, r, 0)
                rideContent.setPadding(pad + l, 0, pad + r, pad + b)
                content.setPadding(pad + l, 0, pad + r, pad / 2 + b)
                drawer.setPadding(l, t + dp(8), 0, b)
                insets
            }
        }
        setContentView(root)
        drawEdgeToEdge()
        showFolder()
        updateButtons()
    }

    /** A rounded box holding a list title and its (scrolling) list. */
    private fun listCard(title: TextView, list: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(pal.card, dp(24).toFloat())
        clipToOutline = true // list rows and their touch ripples stay inside the rounded corners
        setPadding(dp(8), dp(14), dp(8), dp(8))
        addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    /** Draw behind the status and navigation bars, with bar icons readable in light and dark mode. */
    @Suppress("DEPRECATION")
    private fun drawEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (pal.night) 0 else light, light)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!pal.night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    // ---------- Navigation drawer ----------

    private fun drawerWidth() = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.85).toInt())

    private fun buildDrawer(version: String) {
        scrim = View(this).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        val r = dp(16).toFloat()
        // Header: app icon (system-masked adaptive icon) next to the name and version.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(16), dp(16))
            addView(
                ImageView(context).apply {
                    setImageDrawable(getDrawable(R.mipmap.ic_launcher))
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, 0, 0)
                    addView(
                        TextView(context).apply {
                            text = "Coospo BC200 sync"
                            textSize = 22f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(pal.onSurface)
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = version
                            textSize = 14f
                            setTextColor(pal.onSurfaceVariant)
                        },
                    )
                },
            )
        }
        val divider = View(this).apply { setBackgroundColor(Palette.withAlpha(pal.onSurfaceVariant, 0x40)) }
        val section = TextView(this).apply {
            text = "Settings"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(pal.primary)
            setPadding(dp(28), dp(20), dp(16), dp(8))
        }
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.drawer, 0f).apply {
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            elevation = dp(8).toFloat()
            isClickable = true // swallow touches so they don't reach the screen below
            translationX = -(drawerWidth() + dp(24)).toFloat()
            visibility = View.INVISIBLE
            addView(header)
            addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(28)
                rightMargin = dp(16)
            })
            addView(section)
            val appItem = drawerItem(R.drawable.ic_settings, "Application settings", "Phone sync folder, after sync") {
                closeDrawer()
                openAppSettings()
            }
            addView(
                appItem,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                },
            )
            settingsItem = drawerItem(R.drawable.ic_brightness, "BC200 settings", SETTINGS_SUBTITLE) {
                closeDrawer()
                openSettings()
            }
            addView(
                settingsItem,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                },
            )
            addView(
                TextView(context).apply {
                    text = "About"
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(pal.primary)
                    setPadding(dp(28), dp(20), dp(16), dp(8))
                },
            )
            addView(
                drawerItem(R.drawable.ic_info, "About this app", "Authors, open source, privacy, credits") {
                    closeDrawer()
                    aboutOpen = true
                    aboutPage.visibility = View.VISIBLE
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                },
            )
        }
    }

    /** Green frame, Sync now and BC200 settings only while connected; yellow and greyed out otherwise. */
    private fun showConnection() {
        val on = client.isOnline
        cardFrame.setStroke(dp(3), if (on) pal.online else pal.offline)
        settingsItem.isEnabled = on
        syncButton.isEnabled = on
        ridesEmpty.text = if (on) "None on the BC200." else "Not connected."
        syncButton.alpha = if (on) 1f else 0.4f
        settingsItem.alpha = if (on) 1f else 0.4f
        (settingsItem.getChildAt(1) as LinearLayout).getChildAt(1).let {
            (it as TextView).text = if (on) SETTINGS_SUBTITLE else "Connect to your BC200 first"
        }
    }

    private fun drawerItem(icon: Int, title: String, subtitle: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(Color.TRANSPARENT, pal.onSurface, dp(28).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            contentDescription = title
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    setImageDrawable(getDrawable(icon)!!.mutate().apply { setTint(pal.onSurfaceVariant) })
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, 0, 0)
                    addView(
                        TextView(context).apply {
                            text = title
                            textSize = 16f
                            setTextColor(pal.onSurface)
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text = subtitle
                            textSize = 13f
                            setTextColor(pal.onSurfaceVariant)
                        },
                    )
                },
            )
        }

    private fun openDrawer() {
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(220).start()
        drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            if (!drawerOpen) scrim.visibility = View.GONE
        }.start()
        drawer.animate().translationX(-(drawerWidth() + dp(24)).toFloat()).setDuration(200).withEndAction {
            if (!drawerOpen) drawer.visibility = View.INVISIBLE
        }.start()
    }

    @Deprecated("Platform Activity API")
    override fun onBackPressed() {
        if (drawerOpen) {
            closeDrawer()
        } else if (rideOpen) {
            closeRide()
        } else if (aboutOpen) {
            aboutOpen = false
            aboutPage.visibility = View.GONE
        } else if (appOpen) {
            closeAppSettings()
        } else if (settingsOpen) {
            settingsBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        destroyed = true
        stopScan()
        client.disconnect()
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Ride page (one .fit file from the phone sync folder) ----------

    private fun buildRidePage() {
        rideBar = Toolbar(this).apply {
            title = "Ride"
            setTitleTextColor(pal.onSurface)
            setBackgroundColor(pal.surface)
            navigationIcon = getDrawable(R.drawable.ic_arrow_back)!!.mutate().apply { setTint(pal.onSurface) }
            navigationContentDescription = "Back"
            setNavigationOnClickListener { closeRide() }
        }
        rideContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rideScroll = ScrollView(this).apply { addView(rideContent) }
        ridePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            isClickable = true // swallow touches so they don't reach the screen below
            visibility = View.GONE
            addView(rideBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(rideScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun closeRide() {
        rideOpen = false
        rideShown = null
        ridePage.visibility = View.GONE
        rideContent.removeAllViews()
    }

    /** Opens the ride page and reads + parses the file in the background. */
    private fun openRide(name: String) {
        val tree = treeUri() ?: return
        rideOpen = true
        rideShown = name
        rideBar.title = rideDate(name) ?: name
        rideContent.removeAllViews()
        rideContent.addView(rideMessage("Reading the ride…"))
        rideScroll.scrollTo(0, 0)
        ridePage.visibility = View.VISIBLE
        io.execute {
            val result: Any = try {
                val id = listFolder(tree)?.get(name) ?: throw IOException("the file is no longer in the phone sync folder")
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot open it")
                FitFile.parse(bytes)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            handler.post {
                if (destroyed || !rideOpen || rideShown != name) return@post
                rideContent.removeAllViews()
                if (result is FitFile.Ride) showRide(result) else rideContent.addView(rideMessage("Cannot show this ride: $result"))
            }
        }
    }

    /** "2026-11-08  15:50" from a BC200 file name (local time of the BC200), or null. */
    private fun rideDate(n: String) = if (n.length >= 12 && n.take(12).all(Char::isDigit)) {
        "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)}  ${n.substring(8, 10)}:${n.substring(10, 12)}"
    } else {
        null
    }

    private fun rideMessage(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(pal.onSurface)
        background = rounded(pal.card, dp(24).toFloat())
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun duration(s: Double): String {
        val t = Math.round(s)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun showRide(ride: FitFile.Ride) {
        val s = ride.summary
        val r = ride.samples
        val fmt = { pattern: String, v: Double -> String.format(Locale.getDefault(), pattern, v) }
        s.start?.let {
            val t = Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
            rideBar.title = t.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault()))
        }

        // Summary: tiles of value + label, two per row; only what the file has.
        val tiles = ArrayList<Pair<String, String>>()
        s.distanceM?.let { tiles += fmt("%.2f km", it / 1000) to "Distance" }
        s.movingS?.let { tiles += duration(it) to "Moving time" }
        s.avgSpeed?.let { tiles += fmt("%.1f km/h", it * 3.6) to "Average speed" }
        s.maxSpeed?.let { tiles += fmt("%.1f km/h", it * 3.6) to "Max speed" }
        s.elapsedS?.let { tiles += duration(it) to "Total time (with pauses)" }
        s.ascentM?.let { tiles += "$it m" + (s.descentM?.let { d -> " / $d m" } ?: "") to "Climb / descent" }
        s.avgHr?.let { tiles += "$it bpm" to "Average heart rate" }
        s.maxHr?.let { tiles += "$it bpm" to "Max heart rate" }
        s.avgCadence?.let { tiles += "$it rpm" to "Average cadence" }
        s.maxCadence?.let { tiles += "$it rpm" to "Max cadence" }
        if (s.minAltM != null && s.maxAltM != null) tiles += fmt("%.0f", s.minAltM) + "–" + fmt("%.0f m", s.maxAltM) to "Altitude"
        s.avgTempC?.let { tiles += "$it °C" + (s.maxTempC?.let { m -> " (max $m)" } ?: "") to "Temperature" }
        s.calories?.let { tiles += "$it kcal" to "Calories (estimate)" }
        if (s.laps > 1) tiles += "${s.laps}" to "Laps"
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.card, dp(24).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        for (pair in tiles.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((value, label) in pair) {
                row.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        addView(
                            TextView(context).apply {
                                text = value
                                textSize = 20f
                                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                setTextColor(pal.onSurface)
                            },
                        )
                        addView(
                            TextView(context).apply {
                                text = label
                                textSize = 13f
                                setTextColor(pal.onSurfaceVariant)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            summary.addView(row)
        }
        rideContent.addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        // Charts over distance (km), or over time (minutes) when the file has no distance.
        val byDistance = r.distanceM.any { !it.isNaN() && it > 0 }
        val x = FloatArray(r.size)
        var last = 0f
        for (i in 0 until r.size) {
            val v = if (byDistance) r.distanceM[i] / 1000 else r.timeS[i] / 60
            if (!v.isNaN() && v >= last) last = v
            x[i] = last
        }
        val xLabel: (Float) -> String =
            if (byDistance) { v -> fmt("%.1f km", v.toDouble()) } else { v -> duration(v * 60.0) }
        val grid = Palette.withAlpha(pal.onSurfaceVariant, 0x30)
        fun chart(title: String, y: FloatArray, color: Int, unit: (Float) -> String, skipZeroOnly: Boolean = false) {
            if (y.none { !it.isNaN() && (!skipZeroOnly || it > 0) }) return
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(pal.card, dp(24).toFloat())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 14f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(pal.primary)
                    },
                )
                addView(
                    ChartView(context, x, y, color, pal.onSurfaceVariant, grid, xLabel, unit),
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(150)).apply { topMargin = dp(4) },
                )
            }
            rideContent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        val speedKmh = FloatArray(r.size) { r.speed[it] * 3.6f }
        chart("Speed", speedKmh, pal.primary, { fmt("%.0f km/h", it.toDouble()) })
        chart("Heart rate", r.hr, Color.rgb(0xE5, 0x39, 0x35), { fmt("%.0f bpm", it.toDouble()) }, skipZeroOnly = true)
        chart("Cadence", r.cadence, Color.rgb(0x8E, 0x24, 0xAA), { fmt("%.0f rpm", it.toDouble()) }, skipZeroOnly = true)
        chart("Altitude", r.altitudeM, Color.rgb(0x6D, 0x8B, 0x3A), { fmt("%.0f m", it.toDouble()) })
        chart("Temperature", r.tempC, Color.rgb(0xF5, 0x7C, 0x00), { fmt("%.0f °C", it.toDouble()) })

        // Route shape (no map), when the ride has GPS positions.
        if (r.hasPosition) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(pal.card, dp(24).toFloat())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(
                    TextView(context).apply {
                        text = "Route (no map; north is up, green = start, red = end)"
                        textSize = 14f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(pal.primary)
                    },
                )
                addView(
                    TrackView(context, r.lat, r.lon, pal.onSurface, Color.rgb(0x43, 0xA0, 0x47), Color.rgb(0xE5, 0x39, 0x35)),
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(280)).apply { topMargin = dp(4) },
                )
            }
            rideContent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        rideContent.addView(
            TextView(this).apply {
                text = "Drag sideways on a chart to read values. " +
                    (ride.device?.let { "Recorded by $it. " } ?: "") + "${r.size} points."
                textSize = 13f
                setTextColor(pal.onSurfaceVariant)
                setPadding(dp(12), dp(12), dp(12), 0)
            },
        )
    }

    // ---------- BC200 settings page ----------

    // ---------- Application settings page ----------

    /** A row with a title and its current value; tapping it changes the value. */
    private fun valueRow(title: String, value: TextView, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = ripple(Color.TRANSPARENT, pal.onSurface, dp(16).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minimumHeight = dp(64)
        contentDescription = title
        setOnClickListener { onClick() }
        addView(
            TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(pal.onSurface)
            },
        )
        addView(value)
    }

    /** Builds [appPage] (the app's own settings) and returns its content, which needs the insets. */
    private fun buildAppPage(): LinearLayout {
        val bar = Toolbar(this).apply {
            title = "Application settings"
            setTitleTextColor(pal.onSurface)
            setBackgroundColor(pal.surface)
            navigationIcon = getDrawable(R.drawable.ic_arrow_back)!!.mutate().apply { setTint(pal.onSurface) }
            navigationContentDescription = "Back"
            setNavigationOnClickListener { closeAppSettings() }
        }
        fun valueText() = TextView(this).apply {
            textSize = 14f
            setTextColor(pal.onSurfaceVariant)
        }
        folderText = valueText()
        afterSyncValue = valueText().apply { text = afterSyncText() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.card, dp(24).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(valueRow("Phone sync folder", folderText) { pickFolder() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(valueRow("After sync", afterSyncValue) { pickAfterSync() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val note = TextView(this).apply {
            text = "Rides are saved only in the phone sync folder. Copying them anywhere else " +
                "(a cloud or a computer) is up to other apps."
            textSize = 14f
            setTextColor(pal.onSurfaceVariant)
            setPadding(dp(12), dp(12), dp(12), 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(note)
        }
        appPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            isClickable = true // swallow touches so they don't reach the screen below
            visibility = View.GONE
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        return content
    }

    /** Builds [aboutPage]: what the app is, its principles and credits. Returns its content. */
    private fun buildAboutPage(version: String): LinearLayout {
        val bar = Toolbar(this).apply {
            title = "About this app"
            setTitleTextColor(pal.onSurface)
            setBackgroundColor(pal.surface)
            navigationIcon = getDrawable(R.drawable.ic_arrow_back)!!.mutate().apply { setTint(pal.onSurface) }
            navigationContentDescription = "Back"
            setNavigationOnClickListener {
                aboutOpen = false
                aboutPage.visibility = View.GONE
            }
        }
        fun card(title: String, body: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.card, dp(24).toFloat())
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(pal.primary)
                },
            )
            addView(
                TextView(context).apply {
                    text = body
                    textSize = 14f
                    setTextColor(pal.onSurface)
                    setPadding(0, dp(6), 0, 0)
                    setLineSpacing(0f, 1.15f)
                },
            )
        }
        val cards = listOf(
            card(
                "Coospo BC200 sync",
                "$version\n\nCopies your rides (.fit files) from a Coospo BC200 / BC200X bike computer " +
                    "to a folder on this phone over Bluetooth, and lets you change the BC200's own settings.",
            ),
            card(
                "Open and private",
                "• Free and open source, in the public domain (The Unlicense).\n" +
                    "• No account and no cloud: the app has no internet access at all.\n" +
                    "• No ads, no tracking, no analytics.\n" +
                    "• Only one permission: Bluetooth \"Nearby devices\". Rides go only to the folder " +
                    "you choose; no storage permission is needed. (Android 10 and 11 require the " +
                    "location permission for any Bluetooth search; the app never uses your location.)",
            ),
            card(
                "Authors",
                "• Main author (code): Claude Opus 5.5 (claude-opus-5-5), Anthropic's AI model, " +
                    "working in Claude Code.\n" +
                    "• Design and testing: Jan Novák, on a real BC200X.",
            ),
            card(
                "Credits",
                "• BC200 protocol: worked out independently, for interoperability, from how the " +
                    "official CoospoRide app talks to the BC200, and checked on a BC200X. No CoospoRide " +
                    "code is included.\n" +
                    "• xoss_sync by ekspla (MIT licence): consulted as an early lead; no code taken.\n" +
                    "• Material icons by Google (Apache 2.0).",
            ),
            card(
                "Not affiliated with Coospo",
                "\"Coospo\" and \"BC200\" are named only to say which device this app works with. " +
                    "The app is not made or endorsed by Coospo.",
            ),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            cards.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }) }
        }
        aboutPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            isClickable = true // swallow touches so they don't reach the screen below
            visibility = View.GONE
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        return content
    }

    private fun openAppSettings() {
        appOpen = true
        appPage.visibility = View.VISIBLE
    }

    private fun closeAppSettings() {
        appOpen = false
        appPage.visibility = View.GONE
    }

    /** Builds [settingsPage] and returns its scrolling content, which needs the window insets. */
    private fun buildSettingsPage(): LinearLayout {
        settingsBar = Toolbar(this).apply {
            title = DeviceSettings.MENU.title
            setTitleTextColor(pal.onSurface)
            setSubtitleTextColor(pal.onSurfaceVariant)
            setBackgroundColor(pal.surface)
            navigationIcon = getDrawable(R.drawable.ic_arrow_back)!!.mutate().apply { setTint(pal.onSurface) }
            navigationContentDescription = "Back"
            setNavigationOnClickListener { settingsBack() }
        }
        settingsMsg = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(pal.onSurface)
        }
        settingsProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(pal.primary)
        }
        val msgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.card, dp(24).toFloat())
            setPadding(dp(20), dp(20), dp(20), dp(12))
            addView(settingsMsg)
            addView(settingsProgress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        // Every menu page is built once; only the one on top of pageStack is shown.
        fun build(page: DeviceSettings.Item.Page) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(pal.card, dp(24).toFloat())
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            for (item in page.items) {
                when (item) {
                    is DeviceSettings.Item.Setting -> card.addView(optionRow(item.option), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    is DeviceSettings.Item.Page -> {
                        build(item)
                        card.addView(menuRow(item), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    }
                    is DeviceSettings.Item.Heading -> card.addView(
                        TextView(this).apply {
                            text = item.text
                            textSize = 14f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(pal.primary)
                            setPadding(dp(12), dp(16), 0, dp(4))
                        },
                    )
                }
            }
            pageViews[page] = card
        }
        build(DeviceSettings.MENU)
        pageHost = FrameLayout(this)
        saveButton = pillButton("Save to BC200", pal.primary, pal.onPrimary) { saveSettings() }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(msgCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(pageHost, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(saveButton, LinearLayout.LayoutParams(dp(260), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
            })
        }
        settingsScroll = ScrollView(this).apply { addView(content) }
        settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.surface)
            isClickable = true // swallow touches so they don't reach the screen below
            visibility = View.GONE
            addView(settingsBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(settingsScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        return content
    }

    /** A row that opens a sub-page: title, its values (or summary) and a chevron. */
    private fun menuRow(page: DeviceSettings.Item.Page): View {
        val sub = TextView(this).apply {
            textSize = 14f
            setTextColor(pal.onSurfaceVariant)
        }
        menuSubtitles[page] = sub
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = page.title
                    textSize = 16f
                    setTextColor(pal.onSurface)
                },
            )
            addView(sub)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(Color.TRANSPARENT, pal.onSurface, dp(16).toFloat())
            setPadding(dp(12), dp(10), dp(8), dp(10))
            minimumHeight = dp(64)
            contentDescription = page.title
            setOnClickListener { pageStack.add(page); showPage() }
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                ImageView(context).apply {
                    setImageDrawable(getDrawable(R.drawable.ic_chevron_right)!!.mutate().apply { setTint(pal.onSurfaceVariant) })
                },
                LinearLayout.LayoutParams(dp(24), dp(24)),
            )
        }
    }

    /** Subtitles of the menu rows: their values (or fixed summary), plus unsaved changes. */
    private fun refreshMenus() {
        for ((page, tv) in menuSubtitles) {
            val direct = page.items.filterIsInstance<DeviceSettings.Item.Setting>().map { it.option }
            val base = when {
                page.summary != null || deviceValues.isEmpty() -> page.summary ?: direct.joinToString(", ") { it.title }
                direct.size <= 2 -> direct.joinToString(" · ") { "${it.title}: ${valueLabel(it)}" }
                else -> direct.joinToString(" · ") { valueLabel(it) }
            }
            val n = page.options.count(::unsaved)
            tv.text = base + if (n > 0) "\n$n change(s) not saved" else ""
            tv.setTextColor(if (n > 0) pal.primary else pal.onSurfaceVariant)
        }
    }

    private fun showPage() {
        val page = pageStack.last()
        pageHost.removeAllViews()
        (pageViews.getValue(page).parent as? ViewGroup)?.removeView(pageViews.getValue(page))
        pageHost.addView(pageViews.getValue(page))
        settingsBar.title = page.title
        // Where this page is, e.g. "Device" above "Tone"; nothing on the top two levels.
        settingsBar.subtitle = pageStack.drop(1).dropLast(1).joinToString(" › ") { it.title }.ifEmpty { null }
        settingsScroll.scrollTo(0, 0)
        refreshMenus()
    }

    /** Back one menu level; from the top level, closes the settings page. */
    private fun settingsBack() {
        if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.size - 1)
            showPage()
        } else {
            closeSettings()
        }
    }

    /** One row: title and value (tap opens a pick list), or title and switch for on/off options. */
    private fun optionRow(o: DeviceSettings.Option): View {
        val title = TextView(this).apply {
            text = o.title
            textSize = 16f
            setTextColor(pal.onSurface)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(Color.TRANSPARENT, pal.onSurface, dp(16).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(56)
        }
        if (o.kind is DeviceSettings.Kind.Bool) {
            val sw = Switch(this).apply {
                isClickable = false // the whole row toggles it
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(pal.primary, pal.onSurfaceVariant),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Palette.withAlpha(pal.primary, 0x66), Palette.withAlpha(pal.onSurfaceVariant, 0x40)),
                )
            }
            row.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(sw)
            row.tag = sw
            row.setOnClickListener { setPending(o, (!sw.isChecked).toString()) }
        } else {
            val value = TextView(this).apply {
                textSize = 14f
                setTextColor(pal.onSurfaceVariant)
            }
            row.orientation = LinearLayout.VERTICAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(title)
            row.addView(value)
            row.tag = value
            row.setOnClickListener { if (o.kind is DeviceSettings.Kind.Number) pickNumber(o) else pickChoice(o) }
        }
        optionRows[o] = row
        return row
    }

    private fun currentValue(o: DeviceSettings.Option) = pending[o] ?: deviceValues[o]

    private fun pickChoice(o: DeviceSettings.Option) {
        if (o == DeviceSettings.TIME_ZONE) return pickTimeZone()
        val options = (o.kind as DeviceSettings.Kind.Choice).options
        val selected = options.indexOfFirst { DeviceSettings.same(it.second, currentValue(o)) }
        AlertDialog.Builder(this)
            .setTitle(o.longTitle)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), selected) { d, which ->
                setPending(o, options[which].second)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun tzAutoSaved() = prefs().getBoolean(KEY_TZ_AUTO, false)

    private fun tzAuto() = pendingTzAuto ?: tzAutoSaved()

    private fun phoneTimeZone(): String? =
        DeviceSettings.timeZoneToken(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()))

    /** Time zone list with "Auto (phone)" first. */
    private fun pickTimeZone() {
        val o = DeviceSettings.TIME_ZONE
        val options = (o.kind as DeviceSettings.Kind.Choice).options
        val phone = phoneTimeZone()
        val auto = "Auto (phone, summer/winter time)" + (phone?.let { " · " + DeviceSettings.label(o, it) } ?: "")
        val selected = if (tzAuto()) 0 else 1 + options.indexOfFirst { DeviceSettings.same(it.second, currentValue(o)) }
        AlertDialog.Builder(this)
            .setTitle(o.longTitle)
            .setSingleChoiceItems((listOf(auto) + options.map { it.first }).toTypedArray(), selected) { d, which ->
                d.dismiss()
                if (which == 0 && phone == null) {
                    settingsMsg.text = "The phone's time zone is not one the BC200 offers. Not changed."
                    return@setSingleChoiceItems
                }
                val wantAuto = which == 0
                pendingTzAuto = if (wantAuto == tzAutoSaved()) null else wantAuto
                setPending(o, if (wantAuto) phone!! else options[which - 1].second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unsaved(o: DeviceSettings.Option) =
        pending.containsKey(o) || (o == DeviceSettings.TIME_ZONE && pendingTzAuto != null)

    /** Value text of a row, with "Auto (phone)" for an automatic time zone. */
    private fun valueLabel(o: DeviceSettings.Option): String {
        val l = DeviceSettings.label(o, currentValue(o))
        return if (o == DeviceSettings.TIME_ZONE && tzAuto()) "Auto (phone) · $l" else l
    }

    private fun pickNumber(o: DeviceSettings.Option) {
        val k = o.kind as DeviceSettings.Kind.Number
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or if (k.decimals) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
            setText(DeviceSettings.numberText(o, currentValue(o)).let { if (k.decimals) it else it.substringBefore('.') })
            selectAll()
            hint = "${k.min}–${k.max}"
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${o.longTitle} (${k.unit})")
            .setMessage(rangeText(k))
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val token = DeviceSettings.numberToToken(o, input.text.toString())
                if (token == null) {
                    settingsMsg.text = "${o.longTitle} must be ${rangeText(k)}. Not changed."
                } else {
                    setPending(o, token)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun rangeText(k: DeviceSettings.Kind.Number): String {
        val step = DeviceSettings.numberToken(java.math.BigDecimal(k.step).divide(java.math.BigDecimal(k.scale)))
        return "${k.min} to ${k.max} ${k.unit}" + if (step != "1") ", in steps of $step" else ""
    }

    private fun setPending(o: DeviceSettings.Option, token: String) {
        if (DeviceSettings.same(token, deviceValues[o])) pending.remove(o) else pending[o] = token
        showOption(o)
        val n = DeviceSettings.ALL.count(::unsaved)
        settingsMsg.text = if (n == 0) "No changes." else "$n change(s) not yet saved. Tap Save to BC200."
    }

    private fun showOption(o: DeviceSettings.Option) {
        val row = optionRows[o] ?: return
        val v = currentValue(o)
        when (val t = row.tag) {
            is Switch -> t.isChecked = v == "true"
            is TextView -> {
                t.text = if (deviceValues.isEmpty()) "—" else valueLabel(o) + if (unsaved(o)) "  (not saved)" else ""
                t.setTextColor(if (unsaved(o)) pal.primary else pal.onSurfaceVariant)
            }
        }
        refreshMenus()
    }

    private fun setSettingsEnabled(on: Boolean) {
        for ((o, row) in optionRows) {
            val available = deviceValues[o] != null
            row.isEnabled = on && available
            row.alpha = if (on && available) 1f else 0.4f
            (row.tag as? Switch)?.isEnabled = on && available
        }
        saveButton.isEnabled = on
        saveButton.alpha = if (on) 1f else 0.4f
    }

    private fun showDevice(file: ByteArray) {
        settingsFile = file
        deviceValues = DeviceSettings.read(file)
        pending.clear()
        pendingTzAuto = null
        DeviceSettings.ALL.forEach(::showOption)
    }

    private fun openSettings() {
        settingsOpen = true
        pageStack.clear()
        pageStack.add(DeviceSettings.MENU)
        showPage()
        settingsPage.visibility = View.VISIBLE
        loadSettings()
    }

    private fun closeSettings() {
        settingsOpen = false
        settingsPage.visibility = View.GONE
    }

    private fun loadSettings() {
        if (settingsBusy) return
        settingsFile = null
        deviceValues = emptyMap()
        pending.clear()
        pendingTzAuto = null
        DeviceSettings.ALL.forEach(::showOption)
        setSettingsEnabled(false)
        when {
            !client.isConnected -> {
                settingsMsg.text = "Connect to your BC200 first, then open this page again."
                return
            }
            !client.isReady || queue.isNotEmpty() || verifyingDeletes -> {
                settingsMsg.text = "The BC200 is busy syncing. Open this page again when the sync has finished."
                return
            }
        }
        settingsBusy = true
        settingsMsg.text = "Reading the BC200 settings…"
        client.download(DeviceSettings.FILE, { _, _ -> }) { data, err ->
            settingsBusy = false
            if (data == null) {
                settingsMsg.text = err ?: "Could not read the BC200 settings."
                return@download
            }
            showDevice(data)
            setSettingsEnabled(true)
            val missing = deviceValues.count { it.value == null }
            settingsMsg.text = "These are the settings on your BC200." +
                if (missing > 0) " $missing option(s) are not in this BC200's settings and are greyed out." else ""
        }
    }

    private fun describe(changes: Map<DeviceSettings.Option, String>) =
        changes.entries.joinToString("\n") { (o, v) -> "${o.longTitle}: ${DeviceSettings.label(o, v)}" }

    private fun saveSettings() {
        if (settingsFile == null || settingsBusy) return
        if (pending.isEmpty()) {
            val auto = pendingTzAuto
            if (auto == null) {
                settingsMsg.text = "Nothing changed."
                return
            }
            // The BC200 already has the phone's zone (or keeps its own): only the app setting changes.
            prefs().edit().putBoolean(KEY_TZ_AUTO, auto).apply()
            pendingTzAuto = null
            showOption(DeviceSettings.TIME_ZONE)
            settingsMsg.text = if (auto) "Time zone: automatic from the phone." else "Time zone: fixed."
            return
        }
        if (!client.isReady) {
            settingsMsg.text = "The BC200 is busy or not connected. Try again in a moment."
            return
        }
        val want = LinkedHashMap(pending)
        val risky = want.filterKeys(DeviceSettings::needsConfirm)
        if (risky.isEmpty()) {
            sendSettings(want)
            return
        }
        val lines = risky.entries.joinToString("\n") { (o, v) ->
            "${o.longTitle}: ${DeviceSettings.label(o, deviceValues[o])} → ${DeviceSettings.label(o, v)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Replace total distance?")
            .setMessage(
                "$lines\n\nThe ridden total on the BC200 is replaced by this number. " +
                    "The old total cannot be restored afterwards.",
            )
            .setPositiveButton("Replace") { _, _ -> sendSettings(want) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Reads the settings again right before writing, so values the BC200 changed since this page
     * was opened (a ride adds to the total distance) are sent back as they are now; only the
     * options in [want] are replaced.
     */
    private fun sendSettings(want: Map<DeviceSettings.Option, String>) {
        tzAutoToSave = pendingTzAuto
        if (settingsBusy || !client.isReady) {
            settingsMsg.text = "The BC200 is busy or not connected. Try again in a moment."
            return
        }
        settingsBusy = true
        setSettingsEnabled(false)
        settingsMsg.text = "Reading the BC200 settings…"
        client.download(DeviceSettings.FILE, { _, _ -> }) { fresh, readErr ->
            val fail = { msg: String ->
                settingsBusy = false
                settingsMsg.text = msg
                setSettingsEnabled(settingsFile != null && client.isConnected)
            }
            if (fresh == null) return@download fail((readErr ?: "Could not read the BC200 settings.") + " Nothing was sent.")
            val payload = try {
                DeviceSettings.withValues(fresh, want)
            } catch (e: DeviceSettings.SettingsException) {
                return@download fail("${e.message} Nothing was sent.")
            }
            settingsMsg.text = "Sending the settings to the BC200…"
            settingsProgress.visibility = View.VISIBLE
            settingsProgress.progress = 0
            client.upload(DeviceSettings.FILE, payload, { i, n -> settingsProgress.progress = i * 100 / n }) { err ->
                settingsProgress.visibility = View.INVISIBLE
                if (err != null) return@upload fail(err)
                settingsMsg.text = "Checking what the BC200 saved…"
                // The device needs a moment to store the file before it can be read back.
                handler.postDelayed({ verifySettings(payload, want) }, 2000)
            }
        }
    }

    private fun verifySettings(sent: ByteArray, want: Map<DeviceSettings.Option, String>) {
        if (destroyed) return
        if (!client.isReady) {
            settingsBusy = false
            settingsMsg.text = "Sent, but could not read the settings back. Open this page again to check."
            return
        }
        client.download(DeviceSettings.FILE, { _, _ -> }) { data, err ->
            settingsBusy = false
            if (data == null) {
                settingsMsg.text = (err ?: "Could not read the settings back.") + " Open this page again to check."
                return@download
            }
            showDevice(data)
            setSettingsEnabled(true)
            val notTaken = want.filter { (o, v) -> !DeviceSettings.same(deviceValues[o], v) }
            if (DeviceSettings.TIME_ZONE !in notTaken) tzAutoToSave?.let { prefs().edit().putBoolean(KEY_TZ_AUTO, it).apply() }
            tzAutoToSave = null
            showOption(DeviceSettings.TIME_ZONE)
            settingsMsg.text = when {
                notTaken.isNotEmpty() -> "The BC200 did not take:\n${describe(notTaken)}"
                DeviceSettings.sameContent(sent, data) -> "Saved and checked:\n${describe(want)}"
                else -> "Saved:\n${describe(want)}\nbut the BC200 also changed other parts of its settings."
            }
        }
    }

    // ---------- Bluetooth connection ----------

    private fun onConnectClicked() {
        when {
            scanning -> {
                stopScan()
                onStatus("Search stopped.")
            }
            client.isConnected -> client.disconnect()
            !hasPermissions() -> requestPermissions(requiredPermissions(), PERMISSION_REQUEST)
            else -> startScan()
        }
        updateButtons()
    }

    /** The only way rides are copied (and removed from the BC200): Connect just lists them. */
    private fun syncNow() {
        if (!client.isReady || queue.isNotEmpty() || verifyingDeletes) return
        syncRequested = true
        client.refreshRides()
    }

    private fun requiredPermissions() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions() =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != PERMISSION_REQUEST) return
        if (hasPermissions()) startScan() else onError("Bluetooth permission is needed to reach the BC200.")
        updateButtons()
    }

    private fun startScan() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            onError("Turn on Bluetooth first.")
            return
        }
        scanning = true
        onStatus("Searching for your BC200…")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed(stopScanRunnable, SCAN_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (scanning) {
            getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        scanning = false
        updateButtons()
    }

    private fun updateButtons() {
        showConnection()
        connectButton.text = when {
            scanning -> "Stop"
            client.isConnected -> "Disconnect"
            else -> "Connect"
        }
    }

    // ---------- Phone folder ----------

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun treeUri(): Uri? = prefs().getString(KEY_TREE, null)?.let(Uri::parse)

    private fun folderLabel(): String {
        val tree = treeUri() ?: return "not chosen"
        val id = DocumentsContract.getTreeDocumentId(tree)
        return id.substringAfter(':', id).ifEmpty { "internal storage" }
    }

    private fun showFolder() {
        folderText.text = folderLabel()
        loadPhoneRides()
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(intent, FOLDER_REQUEST)
    }

    @Deprecated("Platform Activity API")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code != FOLDER_REQUEST || result != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            onError("Android did not allow access to that folder.")
            return
        }
        treeUri()?.takeIf { it != uri }?.let { old ->
            try {
                contentResolver.releasePersistableUriPermission(old, flags)
            } catch (_: SecurityException) {
                // The old grant is already gone.
            }
        }
        prefs().edit().putString(KEY_TREE, uri.toString()).apply()
        showFolder()
    }

    /** File name to document id in the chosen folder, or null if it cannot be read. */
    private fun listFolder(tree: Uri): Map<String, String>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return try {
            contentResolver.query(children, columns, null, null, null)?.use { c ->
                val out = HashMap<String, String>()
                while (c.moveToNext()) out[c.getString(1)] = c.getString(0)
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Writes the file (overwriting one of the same name) and returns its document uri. */
    private fun writeToFolder(tree: Uri, name: String, data: ByteArray): Uri {
        val files = listFolder(tree) ?: throw IOException("cannot open the phone sync folder, choose it again")
        val uri = files[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                FILE_MIME, name,
            )
            ?: throw IOException("cannot create $name")
        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
        return uri
    }

    // ---------- BC200 -> phone ----------

    private fun label(r: Protocol.Ride): String {
        val n = r.name
        val date = if (n.length >= 14 && n.take(14).all(Char::isDigit)) {
            "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)}  ${n.substring(8, 10)}:${n.substring(10, 12)}"
        } else {
            n
        }
        return "$date   ${r.size / 1024} KB"
    }

    private fun showRides() {
        adapter.clear()
        adapter.addAll(rides.map { label(it) + if (onPhone[it.name] == it.size.toLong()) "   ✓ on phone" else "" })
        adapter.notifyDataSetChanged()
        // Rides on this phone: the .fit files in the sync folder, newest first.
        phoneTitle.text = "Rides on this phone (local only) · ${folderLabel()}"
        val local = onPhone.filterKeys { it.endsWith(".fit", ignoreCase = true) }
            .map { (n, size) -> Protocol.Ride(n, size.toInt()) }.sortedByDescending { it.name }
        phoneRides = local
        phoneAdapter.clear()
        phoneAdapter.addAll(local.map { r -> label(r) + if (savedThisRun.any { it.name == r.name }) "   new" else "" })
        phoneAdapter.notifyDataSetChanged()
        phoneEmpty.text = if (treeUri() == null) "No phone sync folder chosen: ☰ → Application settings." else "None yet."
    }

    /** Reads the phone sync folder again and shows its rides. */
    private fun loadPhoneRides() {
        val tree = treeUri()
        if (tree == null) {
            onPhone = emptyMap()
            showRides()
            return
        }
        io.execute {
            val sizes = folderSizes(tree)
            handler.post {
                if (destroyed) return@post
                onPhone = sizes ?: emptyMap()
                showRides()
            }
        }
    }

    private fun keepOnBc200() = prefs().getBoolean(KEY_KEEP, false)

    private fun afterSyncText() = if (keepOnBc200()) "Keep rides on the BC200" else "Remove rides from the BC200"

    /** What happens to a ride on the BC200 once its copy on the phone is checked. */
    private fun pickAfterSync() {
        val choices = arrayOf(
            "Remove from the BC200 (after the copy on the phone is checked)",
            "Keep on the BC200",
        )
        AlertDialog.Builder(this)
            .setTitle("After sync")
            .setSingleChoiceItems(choices, if (keepOnBc200()) 1 else 0) { d, which ->
                prefs().edit().putBoolean(KEY_KEEP, which == 1).apply()
                afterSyncValue.text = afterSyncText()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** File name -> size of everything in the phone sync folder, or null when it cannot be read. */
    private fun folderSizes(tree: Uri): Map<String, Long>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        return try {
            contentResolver.query(children, columns, null, null, null)?.use { c ->
                val out = HashMap<String, Long>()
                while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onRides(rides: List<Protocol.Ride>) {
        showConnection()
        this.rides = rides.sortedByDescending { it.name }
        showRides()
        if (verifyingDeletes) {
            finishDeviceRun(rides.map { it.name }.toSet())
            return
        }
        val sync = syncRequested
        syncRequested = false
        if (rides.isEmpty()) {
            onStatus("No rides on the BC200.")
            autoTimeZone()
            return
        }
        if (!sync) return showListedOnly(rides)
        val tree = treeUri() ?: return startQueue(rides)
        io.execute {
            val sizes = folderSizes(tree)
            handler.post {
                if (destroyed) return@post
                onPhone = sizes ?: emptyMap()
                showRides()
                // Keeping rides on the BC200: copy only those not yet on the phone (same name and size).
                val todo = if (keepOnBc200()) rides.filter { onPhone[it.name] != it.size.toLong() } else rides
                if (todo.isEmpty()) {
                    onStatus("All ${rides.size} ride(s) on the BC200 are already saved on this phone.")
                    autoTimeZone()
                } else {
                    startQueue(todo)
                }
            }
        }
    }

    /** After Connect: show how many rides are waiting, copy nothing. */
    private fun showListedOnly(rides: List<Protocol.Ride>) {
        val tree = treeUri()
        if (tree == null) {
            onStatus("${rides.size} ride(s) on the BC200. Choose a phone sync folder first: ☰ → Application settings.")
            autoTimeZone()
            return
        }
        io.execute {
            val sizes = folderSizes(tree)
            handler.post {
                if (destroyed) return@post
                onPhone = sizes ?: emptyMap()
                showRides()
                val new = rides.count { onPhone[it.name] != it.size.toLong() }
                onStatus(
                    if (new == 0) "Connected. All ${rides.size} ride(s) on the BC200 are already on this phone."
                    else "Connected. $new new ride(s) on the BC200. Tap Sync now to copy them.",
                )
                autoTimeZone()
            }
        }
    }

    private fun startQueue(items: List<Protocol.Ride>) {
        if (treeUri() == null) {
            onStatus("${items.size} rides on the BC200. Choose a phone sync folder first: ☰ → Application settings.")
            autoTimeZone()
            return
        }
        if (!client.isReady || queue.isNotEmpty()) return
        queue = ArrayDeque(items.sortedBy { it.name })
        failures = 0
        copiedCount = 0
        removedFromDevice.clear()
        next()
    }

    private fun next() {
        val r = queue.firstOrNull()
        if (r == null) {
            progress.visibility = View.INVISIBLE
            if (removedFromDevice.isNotEmpty() && client.isReady) {
                verifyingDeletes = true
                onStatus("Checking the BC200 ride list…")
                client.refreshRides()
            } else {
                finishDeviceRun(null)
            }
            return
        }
        progress.visibility = View.VISIBLE
        progress.progress = 0
        onStatus("Copying ${r.name} to the phone…")
        client.download(r.name, { got, total ->
            if (total > 0) progress.progress = (got * 100L / total).toInt()
        }) { data, err -> onDownloaded(r, data, err) }
    }

    private fun onDownloaded(r: Protocol.Ride, data: ByteArray?, err: String?) {
        val tree = treeUri()
        if (data == null || tree == null) {
            failures++
            onError(err ?: "Download failed.")
            advance()
            return
        }
        io.execute {
            val ok = try {
                val uri = writeToFolder(tree, r.name, data)
                contentResolver.openInputStream(uri)!!.use { it.readBytes() }.contentEquals(data)
            } catch (_: Exception) {
                false
            }
            handler.post {
                if (destroyed) return@post
                if (!ok) {
                    failures++
                    onError("Could not save ${r.name} on the phone; it stays on the BC200.")
                    advance()
                    return@post
                }
                copiedCount++
                savedThisRun.removeAll { it.name == r.name }
                savedThisRun += r
                onPhone = onPhone + (r.name to data.size.toLong())
                showRides()
                if (keepOnBc200()) {
                    advance()
                    return@post
                }
                onStatus("Removing ${r.name} from the BC200…")
                client.deleteFile(r.name) { sent ->
                    if (sent) removedFromDevice += r.name
                    advance()
                }
            }
        }
    }

    private fun advance() {
        queue.removeFirstOrNull()
        if (client.isConnected) {
            handler.post { next() }
        } else {
            queue.clear()
            progress.visibility = View.INVISIBLE
        }
    }

    /** [stillOnDevice] is the re-read ride list, or null when nothing was deleted. */
    private fun finishDeviceRun(stillOnDevice: Set<String>?) {
        verifyingDeletes = false
        val notRemoved = removedFromDevice.filter { stillOnDevice?.contains(it) == true }
        removedFromDevice.clear()
        onStatus(
            buildString {
                append("Downloaded $copiedCount ride(s).")
                // Only problems are reported beyond the count.
                if (notRemoved.isNotEmpty()) append(" ${notRemoved.size} could not be removed from the BC200.")
                if (failures > 0) append(" $failures failed.")
            },
        )
        updateButtons()
        autoTimeZone()
    }

    /**
     * With time zone "Auto (phone)", once per connection and only when the BC200 is idle: if its
     * zone differs from the phone's current offset (summer/winter time, travel), write the phone's.
     * Same safe path as a manual save: fresh read, change only time_zone, read back.
     */
    private fun autoTimeZone() {
        if (tzChecked || !tzAutoSaved() || settingsBusy || !client.isReady || queue.isNotEmpty() || verifyingDeletes) return
        tzChecked = true
        val want = phoneTimeZone() ?: return
        val o = DeviceSettings.TIME_ZONE
        fun done(msg: String?) {
            settingsBusy = false
            msg?.let { status.text = "${status.text}\n$it" }
            if (settingsOpen && pending.isEmpty() && pendingTzAuto == null) loadSettings()
        }
        settingsBusy = true
        client.download(DeviceSettings.FILE, { _, _ -> }) { data, _ ->
            val now = data?.let { DeviceSettings.read(it)[o] }
            if (data == null || now == null || DeviceSettings.same(now, want)) return@download done(null)
            val payload = try {
                DeviceSettings.withValues(data, mapOf(o to want))
            } catch (e: DeviceSettings.SettingsException) {
                return@download done("Time zone not updated: ${e.message}")
            }
            client.upload(DeviceSettings.FILE, payload, { _, _ -> }) { err ->
                if (err != null) return@upload done("Time zone not updated: $err")
                handler.postDelayed({
                    if (destroyed || !client.isReady) return@postDelayed done(null)
                    client.download(DeviceSettings.FILE, { _, _ -> }) { back, _ ->
                        val got = back?.let { DeviceSettings.read(it)[o] }
                        done(
                            if (DeviceSettings.same(got, want)) "BC200 time zone set to ${DeviceSettings.label(o, want)} (from the phone)."
                            else "The BC200 did not take the phone's time zone.",
                        )
                    }
                }, 2000)
            }
        }
    }

    // ---------- Bc200Client.Listener ----------

    override fun onStatus(text: String) {
        status.text = text
        showConnection()
    }

    override fun onDeviceInfo(text: String) {
        info.text = text
        info.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onError(text: String) {
        status.text = text
    }

    override fun onDisconnected() {
        tzChecked = false
        syncRequested = false
        if (settingsOpen && !settingsBusy) {
            setSettingsEnabled(false)
            settingsMsg.text = "Disconnected from the BC200."
        }
        queue.clear()
        verifyingDeletes = false
        removedFromDevice.clear()
        progress.visibility = View.INVISIBLE
        rides = emptyList() // the list shows the BC200's rides only while connected
        showRides()
        updateButtons()
    }
}
