@file:JvmName("Gui")

package com.anatawa12.downloader

import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.ActionEvent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import javax.swing.*
import javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
import javax.swing.table.AbstractTableModel
import kotlin.system.exitProcess


fun main() = runBlocking(Dispatchers.Default) { startGui() }

fun errorPanelNoExit(
    title: String,
    error: Throwable?,
    message: String,
    vararg extras: Component,
    parent: Component? = null,
) {
    JOptionPane.showMessageDialog(
        parent,
        arrayOf(
            JLabel(message),
            *extras,
            error?.let {
                JTextArea().apply {
                    isEditable = false
                    text = StringWriter().apply { PrintWriter(this).use(error::printStackTrace) }.toString()
                }
            }
        ),
        title,
        JOptionPane.ERROR_MESSAGE,
    )
}

fun errorPanel(
    title: String,
    error: Throwable?,
    message: String,
    vararg extras: Component,
    parent: Component? = null,
): Nothing {
    errorPanelNoExit(title, error, message, *extras, parent = parent)
    exitProcess(-1)
}

fun linkButtons(vararg links: Pair<String, String>) = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    alignmentX = JPanel.LEFT_ALIGNMENT
    for ((message, link) in links)
        JButton(message).apply { addActionListener { Desktop.getDesktop().browse(URI.create(link)) } }.also(::add)
}

suspend fun startGui() {
    if (GraphicsEnvironment.isHeadless()) error("GUI is not supported but explicitly gui mode")
    try {
        startGuiImpl()
    } catch (t: Throwable) {
        errorPanel("Internal Error", t,
            "Internal Error has occurred.\n" +
                    "Please make issue for mod-downloader on issue tracker with the following text below",
            linkButtons("Go to issue tracker" to "https://github.com/anatawa12/mod-downloader/issues/new"))
    }
}

private suspend fun startGuiImpl() {
    val embedConfig = try {
        EmbedConfiguration.load()
    } catch (e: UserError) {
        errorPanel("Error Loading Configuration", e,
            "Error Loading downloader configuration.\n" +
                    "Please concat the JAR provider with the following text")
    } catch (e: Throwable) {
        errorPanel("Internal Error Loading Configuration", e,
            "Error Loading downloader configuration.\n" +
                    "Please make issue for mod-downloader on issue tracker with the following text below",
            linkButtons("Go to issue tracker" to "https://github.com/anatawa12/mod-downloader/issues/new"))
    }

    val panel = DownloaderPanel(findModsDir(), embedConfig).also { panel ->
        val optionPane = JOptionPane(null, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
        optionPane.message = panel
        val dialog = optionPane.createDialog(embedConfig?.windowName ?: "Mod Downloader")
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.isVisible = true
        dialog.dispose()
        if (optionPane.value != JOptionPane.OK_OPTION) return
    }

    val modsConfig: ModsConfig = panel.modList.getOrLoadModsConfig() ?: return // error has been handled

    val modsDir = panel.modsDir.file!!
    var force = false
    val mode = if (panel.mode == DownloadMode.DOWNLOAD) DownloadMode.DOWNLOAD else {
        if (modsDir.listFiles()?.isNotEmpty() == true) {
            val check = JOptionPane("you're clean downloading to non-empty directory.\n" +
                    "Are you sure want to delete files in the directory?",
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION)
            val dialog = check.createDialog("WARNING")
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dialog.isVisible = true
            dialog.dispose()
            if (check.value != JOptionPane.OK_OPTION) return
        }
        force = true
        DownloadMode.CLEAN_DOWNLOAD
    }

    val progressPanel = DownloadingPanel()
    val progress = JFrame("Downloading").apply {
        contentPane = progressPanel
        pack()
        setLocationRelativeTo(null)
        defaultCloseOperation = JDialog.EXIT_ON_CLOSE
    }
    progress.isVisible = true

    try {
        val params = DownloadParameters(
            downloadTo = modsDir,
            mode = mode,
            force = force,
            logger = progressPanel::appendLine,
            optionalModsList = panel.modList.optionalModsList,
            downloadFor = panel.modList.modSide,
        )
        doDownload(modsConfig, params)
        JOptionPane.showMessageDialog(null, "Download Complete", "Complete", JOptionPane.INFORMATION_MESSAGE)
    } catch (e: UserError) {
        errorPanel("Error Downloading Mods", e,
            "Error Downloading Mods: ${e.message}\n" +
                    "Please concat the JAR provider with the following text")
    } catch (t: Throwable) {
        errorPanel("Internal Error", t,
            "Internal Error has occurred.\n" +
                    "Please make issue for mod-downloader on issue tracker with the following text below",
            linkButtons("Go to issue tracker" to "https://github.com/anatawa12/mod-downloader/issues/new"))
    } finally {
        progress.dispose()
    }


}

fun findModsDir(): File {
    val jarPath = runCatching {
        File(EmbedConfiguration::class.java.protectionDomain.codeSource.location.toURI()).absoluteFile
    }.getOrNull()
    if (jarPath != null) {
        val jarDir = jarPath.parentFile
        if (jarDir.resolve(DOWNLOADED_TXT).exists()) {
            // if this jar is in mods directory (there's downloaded.txt)
            return jarDir
        }
        if (jarDir.resolve("mods").resolve(DOWNLOADED_TXT).exists()) {
            // if this jar is in game root directory (there's mods/downloaded.txt)
            return jarDir.resolve("mods")
        }
    }
    // fallback: default minecraft installation
    return getMCDir().resolve("mods").absoluteFile
}

fun getMCDir(): File {
    val userHomeDir = System.getProperty("user.home", ".")
    val osType = System.getProperty("os.name").lowercase()
    val mcDir = ".minecraft"
    return when {
        osType.contains("win") && System.getenv("APPDATA") != null -> File(System.getenv("APPDATA"), mcDir)
        osType.contains("mac") -> File(userHomeDir).resolve("Library").resolve("Application Support")
            .resolve("minecraft")
        else -> File(userHomeDir, mcDir)
    }
}

val EmbedConfiguration.windowName get() = "Mod Downloader for $name"

inline fun action(crossinline block: () -> Unit) = object : AbstractAction() {
    override fun actionPerformed(e: ActionEvent?) {
        block()
    }
}

fun fullSized(fullSized: Component) = JPanel().apply {
    layout = GridLayout(0, 1)
    alignmentX = JPanel.LEFT_ALIGNMENT
    alignmentY = JPanel.TOP_ALIGNMENT
    add(fullSized)
}

@Suppress("LeakingThis")
abstract class ChooseFileLinePanel(name: String, private val isFile: Boolean) : JPanel() {
    private val selectedDirText: JTextField
    private val button: JButton

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JLabel("$name:"))
        selectedDirText = JTextField().apply {
            isEditable = false
            toolTipText = "Path to $name"
            columns = 20
        }.also(::add)
        button = JButton().apply {
            action = action(::onSelect)
            text = "..."
            toolTipText = "Select an $name"
        }.also(::add)
        alignmentX = LEFT_ALIGNMENT
        alignmentY = TOP_ALIGNMENT
    }

    private fun onSelect() {
        val dirChooser = JFileChooser()
        dirChooser.fileSelectionMode = if (!isFile) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        dirChooser.isFileHidingEnabled = false
        file?.let { initial ->
            dirChooser.ensureFileIsVisible(initial)
            dirChooser.selectedFile = initial
        }
        when (dirChooser.showOpenDialog(this@ChooseFileLinePanel.parent)) {
            JFileChooser.APPROVE_OPTION -> {
                selectedDirText.text = dirChooser.selectedFile.path
                onChange()
            }
            else -> {
            }
        }
    }

    var isEditable
        get() = button.isEnabled
        set(value) {
            button.isEnabled = value
        }

    var file: File?
        get() = selectedDirText.text.takeUnless { it.isBlank() }?.let(::File)
        set(value) {
            selectedDirText.text = value?.path.orEmpty()
        }

    abstract fun onChange()
}

class DownloaderPanel(targetDir: File, embedConfig: EmbedConfiguration?) : JPanel() {
    val dialog: JDialog
        get() = kotlin.run {
            var cur: Container? = this
            generateSequence { cur?.also { cur = it.parent } }
        }
            .filterIsInstance<JDialog>()
            .first()
    val modList: ModListInfo
    val modsDir: ChooseFileLinePanel
    private val modeButtonGroup: ButtonGroup
    val mode: DownloadMode get() = enumValueOf(modeButtonGroup.selection.actionCommand)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(256, 10)
            add(JLabel(embedConfig?.windowName ?: "Mod Downloader"))
            alignmentX = CENTER_ALIGNMENT
            alignmentY = TOP_ALIGNMENT
        }.also(::add)

        JPanel().apply {
            modeButtonGroup = ButtonGroup()
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            fun button(mode: DownloadMode, text: String, tooltip: String) = JRadioButton().apply {
                this.text = text
                this.actionCommand = mode.name
                toolTipText = tooltip
                alignmentX = LEFT_ALIGNMENT
                alignmentY = CENTER_ALIGNMENT
                modeButtonGroup.add(this)
            }
            add(button(DownloadMode.DOWNLOAD, "Download", "Download mods if mod is updated."))
            add(button(DownloadMode.CLEAN_DOWNLOAD, "Clean Download", "Clean folder and download mods"))
            modeButtonGroup.elements.nextElement().isSelected = true
            alignmentX = RIGHT_ALIGNMENT
            alignmentY = CENTER_ALIGNMENT
        }.also(::add)

        modsDir = object : ChooseFileLinePanel("mods directory", false) {
            override fun onChange() {
                dialog.invalidate()
                dialog.pack()
                detectInstalledOptionalMods()
            }
        }
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            modList = ModListInfo(embedConfig, ::dialog).also(::add)
            add(modsDir)
            alignmentX = CENTER_ALIGNMENT
            alignmentY = TOP_ALIGNMENT
        }.also(::add)

        modsDir.file = targetDir
        detectInstalledOptionalMods()
    }

    private fun detectInstalledOptionalMods() {
        try {
            val file = modsDir.file ?: return
            val downloadedText = file.resolve(DOWNLOADED_TXT)
            modList.downloadedMods = DownloadedMod.parse(downloadedText.readText())
            modList.detectInstalledOptionalMods()
        } catch (ignored: Exception) {
        }
    }
}

class ModListInfo(embedConfig: EmbedConfiguration?, val dialog: () -> JDialog) : JPanel() {
    private val configFile: ChooseFileLinePanel?
    private val modsListPanel: JPanel
    private val modsListToggle: JButton?
    private val tableModelImpl: TableModelImpl
    private val serverClient: ButtonGroup
    var downloadedMods: List<DownloadedMod> = emptyList()
    var modsConfig: ModsConfig? = null
    val optionalModsList get() = tableModelImpl.optionalModsList
    val modSide get() = enumValueOf<ModsConfig.ModSide>(serverClient.selection.actionCommand)

    init {
        if (embedConfig != null) {
            configFile = null
            modsListToggle = null
            modsConfig = null
            CoroutineScope(Dispatchers.Default).launch { downloadEmbedConfig(embedConfig) }
        } else {
            configFile = object : ChooseFileLinePanel("config file", true) {
                override fun onChange() {
                    dialog().pack()
                }
            }
            modsListToggle = JButton().apply {
                action = action(::onClickLoad)
                text = "Load Mod List"
            }
        }

        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        configFile?.let(::add)
        modsListToggle?.also { add(fullSized(it)) }

        JPanel().apply {
            serverClient = ButtonGroup()
            fun button(mode: ModsConfig.ModSide, text: String, selected: Boolean = false) =
                JRadioButton(text, selected).apply { actionCommand = mode.name }
            button(ModsConfig.ModSide.CLIENT, "Client", true).also(serverClient::add).also(::add)
            button(ModsConfig.ModSide.SERVER, "Server").also(serverClient::add).also(::add)
        }.also { add(fullSized(it)) }

        modsListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JLabel("Optional Mods"))
            JScrollPane().apply {
                JTable(TableModelImpl().also { tableModelImpl = it }).apply {
                    tableHeader.resizingAllowed = false
                    tableHeader.reorderingAllowed = false
                    columnModel.apply {
                        getColumn(0).apply {
                            minWidth = 50
                            maxWidth = 50
                            resizable = false
                        }
                    }
                }.also { viewport.view = it }

                preferredSize = Dimension(200, 80)

            }.also(::add)

            isVisible = false
        }.also(::add)
    }

    suspend fun getOrLoadModsConfig(): ModsConfig? {
        modsConfig?.let { return it }
        return loadModsConfigWithError()
    }

    private var editing = true

    private fun onClickLoad() {
        if (editing) {
            CoroutineScope(Dispatchers.Default).launch { startLoading() }
        } else {
            configFile?.isEditable = true
            editing = true
            modsListToggle!!.text = "Load Mod List"
            modsListPanel.isVisible = !editing
            tableModelImpl.clear()
            dialog().pack()
        }
    }

    private suspend fun downloadEmbedConfig(embedConfig: EmbedConfiguration) {
        val modsConfig =
            loadModsConfigWithError(embedConfig.location, "Please concat the JAR provider with the following text")
                ?: exitProcess(-1)
        this.modsConfig = modsConfig

        modsListPanel.isVisible = true
        tableModelImpl.setConfig(modsConfig)
        detectInstalledOptionalMods()
        dialog().pack()
    }

    private suspend fun startLoading() {
        modsListToggle!!
        configFile!!

        val dialog = JDialog(dialog(), "Loading ...", true).apply {
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                JLabel("Loading mod list", SwingConstants.CENTER).apply {
                    alignmentX = CENTER_ALIGNMENT
                    font = Font(font.name, font.style, 20)
                }.also(::add)
                ProgressSpinner().apply {
                    preferredSize = Dimension(70, 70)
                }.also(::add)
            }.also(::add)
            pack()
            setLocationRelativeTo(this@ModListInfo)
            defaultCloseOperation = DO_NOTHING_ON_CLOSE
        }
        SwingUtilities.invokeLater { dialog.isVisible = true }

        val config: ModsConfig = try {
            loadModsConfigWithError() ?: return
        } finally {
            SwingUtilities.invokeLater(dialog::dispose)
        }

        configFile.isEditable = false
        editing = false
        modsListToggle.text = "Change Mod List"
        modsListPanel.isVisible = true
        tableModelImpl.setConfig(config)
        detectInstalledOptionalMods()
        dialog().pack()
    }

    private suspend fun loadModsConfigWithError(
        modsFileLocation: ModsFileLocation? = configFile!!.file?.let(ModsFileLocation::FileSystem),
        additionalMessage: String = "",
    ): ModsConfig? {
        try {
            val configLocation: ModsFileLocation = modsFileLocation ?: kotlin.run {
                JOptionPane.showMessageDialog(null, JLabel("No config location is specified"),
                    "Missing Value", JOptionPane.ERROR_MESSAGE)
                return null
            }
            return loadModsConfig(configLocation).also { modsConfig = it }
        } catch (e: UserError) {
            errorPanelNoExit("Error Loading Mods List", e,
                "Error Loading Config file: ${e.message}\n" + additionalMessage,
                parent = this)
            return null
        } catch (t: Throwable) {
            errorPanelNoExit("Internal Error", t,
                "Internal Error has occurred.\n" +
                        "Please make issue for mod-downloader on issue tracker with the following text below",
                linkButtons("Go to issue tracker" to "https://github.com/anatawa12/mod-downloader/issues/new"),
                parent = this)
            return null
        }
    }

    fun detectInstalledOptionalMods() {
        for (downloadedMod in downloadedMods) {
            val found = tableModelImpl.values.indexOfFirst { it.modInfo.id == downloadedMod.id }
            if (found >= 0) {
                tableModelImpl.setValueAt(true, found, 0)
            }
        }
    }

    private class TableModelImpl : AbstractTableModel() {
        val headerName = arrayOf("install", "name")
        val headerClass = arrayOf(Boolean::class.javaObjectType, String::class.java)
        var values = listOf<Pair>()
            private set
        val optionalModsList get() = values.filter { it.include }.map { it.modInfo.id }.toSet()

        override fun getRowCount(): Int = values.size
        override fun getColumnCount(): Int = headerName.size
        override fun getColumnName(columnIndex: Int): String = headerName[columnIndex]
        override fun getColumnClass(columnIndex: Int): Class<*> = headerClass[columnIndex]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> values[rowIndex].include
            1 -> values[rowIndex].modInfo.id
            else -> error("$rowIndex, $columnIndex")
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            check(columnIndex == 0)
            values[rowIndex].include = aValue as Boolean
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        fun clear() {
            fireTableRowsDeleted(0, values.size)
            values = listOf()
        }

        fun setConfig(config: ModsConfig) {
            values = config.list.asSequence().filter { it.optional }.map { Pair(it) }.toList()
            fireTableRowsInserted(0, values.size)
        }

        class Pair(val modInfo: ModsConfig.ModInfo, var include: Boolean = false)
    }
}

class ProgressSpinner : JComponent() {
    private var frame = 0

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g as Graphics2D
        g.translate(width / 2.0, height / 2.0)
        val r = minOf(width, height)
        g.scale(r / 20.0, r / 20.0)
        repeat(frames) {
            g.rotate(Math.PI * 2 / frames)
            g.color = if (it == frame) Color.BLACK else Color.GRAY
            g.fillRect(-1, 4, 2, 6)
        }
        frame++
        if (frame >= frames) frame = 0
        CoroutineScope(Dispatchers.Default).launch {
            delay(50)
            SwingUtilities.invokeLater(::repaint)
        }
    }

    companion object {
        private const val frames = 12
    }
}

class DownloadingPanel() : JPanel() {
    private var textarea: JTextArea

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JLabel("Downloading"))
        textarea = JTextArea().apply {
            isEditable = false
            columns = 50
            rows = 20
        }
        add(JScrollPane(textarea).apply {
            autoscrolls = true
        })
    }

    fun appendLine(line: String) {
        SwingUtilities.invokeLater { textarea.append("$line\n") }
    }
}
