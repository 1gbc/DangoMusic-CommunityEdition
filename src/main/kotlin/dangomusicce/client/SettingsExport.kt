package dangomusicce.client

import java.awt.Component
import java.awt.EventQueue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

internal object SettingsExport {
    fun chooseAndSave(title: String, overwrite: String, snapshot: String, onResult: (Result<Boolean>) -> Unit) {
        EventQueue.invokeLater {
            onResult(runCatching {
                val chooser = object : JFileChooser() {
                    override fun createDialog(parent: Component?) =
                        super.createDialog(parent).apply { isAlwaysOnTop = true }

                    override fun approveSelection() {
                        val file = selectedFile ?: return
                        if (file.isDirectory) {
                            super.approveSelection()
                            return
                        }
                        val target = if (file.name.lowercase(Locale.ROOT).endsWith(".json")) file
                            else File(file.parentFile, "${file.name}.json")
                        if (target.exists() && JOptionPane.showConfirmDialog(this, overwrite, title,
                                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return
                        selectedFile = target
                        super.approveSelection()
                    }
                }
                chooser.dialogTitle = title
                chooser.fileFilter = FileNameExtensionFilter("JSON (*.json)", "json")
                chooser.selectedFile = File(chooser.currentDirectory, "settings.json")
                if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching false
                writeSnapshot(chooser.selectedFile.toPath(), snapshot)
                true
            })
        }
    }

    fun writeSnapshot(destination: Path, snapshot: String) {
        val target = destination.toAbsolutePath()
        val temp = Files.createTempFile(target.parent, ".dangomusic-settings-", ".tmp")
        try {
            Files.write(temp, snapshot.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
