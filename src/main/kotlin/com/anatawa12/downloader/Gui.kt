@file:JvmName("Gui")

package com.anatawa12.downloader

import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.event.ActionEvent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import javax.swing.*
import kotlin.system.exitProcess


fun main() = startGui()

fun errorPanel(title: String, error: Throwable?, message: String, vararg extras: Component): Nothing {
    JOptionPane.showMessageDialog(
        null,
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
    exitProcess(-1)
}

fun linkButtons(vararg links: Pair<String, String>) = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    alignmentX = JPanel.LEFT_ALIGNMENT
    for ((message, link) in links)
        JButton(message).apply { addActionListener { Desktop.getDesktop().browse(URI.create(link)) } }.also(::add)
}

fun startGui() {
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

private fun startGuiImpl() {
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

    val panel = DownloaderPanel(getMCDir().resolve("mods").absoluteFile, embedConfig).also { panel ->
        val optionPane = JOptionPane(null, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
        optionPane.message = panel
        val dialog = optionPane.createDialog(embedConfig?.windowName ?: "Mod Downloader")
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.isVisible = true
        dialog.dispose()
        if (optionPane.value != JOptionPane.OK_OPTION) return
    }

    val configLocation: ModsFileLocation = embedConfig?.location
        ?: panel.configFile?.file?.let(ModsFileLocation::FileSystem)
        ?: errorPanel("Missing Value", null, "No config location is specified")

    val modsDir = panel.modsDir.file!!
    val mode = if (panel.mode == DownloadMode.DOWNLOAD) DownloadMode.DOWNLOAD else {
        if (modsDir.listFiles()?.isNotEmpty() == true) {
            val check = JOptionPane("you're clean downloading to non-empty directory.\n" +
                    "Are you sure want to delete files in the directory?", JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
            val dialog = check.createDialog("WARNING")
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dialog.isVisible = true
            dialog.dispose()
            if (check.value != JOptionPane.OK_OPTION) return
        }
        DownloadMode.CLEAN_DOWNLOAD_FORCE
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
        doDownload(configLocation, modsDir, mode, progressPanel::appendLine)
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

fun getMCDir(): File {
    val userHomeDir = System.getProperty("user.home", ".")
    val osType = System.getProperty("os.name").lowercase()
    val mcDir = ".minecraft"
    return when {
        osType.contains("win") && System.getenv("APPDATA") != null -> File(System.getenv("APPDATA"), mcDir)
        osType.contains("mac") -> File(userHomeDir).resolve("Library").resolve("Application Support").resolve("minecraft")
        else -> File(userHomeDir, mcDir)
    }
}

val EmbedConfiguration.windowName get() = "Mod Downloader for $name"

inline fun action(crossinline block: () -> Unit) = object : AbstractAction() {
    override fun actionPerformed(e: ActionEvent?) {
        block()
    }
}

@Suppress("LeakingThis")
abstract class ChooseFileLinePanel(name: String, private val isFile: Boolean) : JPanel() {
    private val selectedDirText: JTextField

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JLabel("$name:"))
        selectedDirText = JTextField().apply {
            isEditable = false
            toolTipText = "Path to $name"
            columns = 20
        }.also(::add)
        JButton().apply {
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
            else -> {}
        }
    }

    var file: File?
        get() {
            val path = selectedDirText.text
            return if (path.startsWith("/")) File(path) else null
        }
        set(value) {
            selectedDirText.text = value?.path.orEmpty()
        }

    abstract fun onChange()
}

class DownloaderPanel(targetDir: File, embedConfig: EmbedConfiguration?) : JPanel() {
    val dialog: JDialog get() = parent.parent.parent.parent.parent.parent.parent as JDialog
    val configFile: ChooseFileLinePanel?
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

        configFile = if (embedConfig != null) null else {
            object : ChooseFileLinePanel("config file", true) {
                override fun onChange() {
                    dialog.invalidate()
                    dialog.pack()
                }
            }
        }
        modsDir = object : ChooseFileLinePanel("mods directory", false) {
            override fun onChange() {
                dialog.invalidate()
                dialog.pack()
            }
        }
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            configFile?.let(::add)
            add(modsDir)
            alignmentX = CENTER_ALIGNMENT
            alignmentY = TOP_ALIGNMENT
        }.also(::add)

        modsDir.file = targetDir
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
        }.also(::add)
    }

    fun appendLine(line: String) {
        SwingUtilities.invokeLater { textarea.append("$line\n") }
    }
}
