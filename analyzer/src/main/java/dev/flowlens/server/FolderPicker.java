package dev.flowlens.server;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.util.Locale;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * A folder chooser shown on the machine running the server (browser mode only — the desktop
 * app uses the OS dialog from its shell). Kept in its own class so that runtimes built
 * without the java.desktop module never load it.
 */
final class FolderPicker {

    private FolderPicker() {}

    static boolean available() {
        return ModuleLayer.boot().findModule("java.desktop").isPresent()
                && !java.awt.GraphicsEnvironment.isHeadless();
    }

    /** Absolute path of the chosen folder, or null if cancelled. */
    static String choose() throws Exception {
        final String[] result = new String[1];
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        Runnable task = () -> {
            if (mac) {
                // The AWT FileDialog is the real native chooser on macOS.
                System.setProperty("apple.awt.fileDialogForDirectories", "true");
                Frame parent = new Frame();
                FileDialog fd = new FileDialog(parent, "Select a Java project folder", FileDialog.LOAD);
                fd.setVisible(true);
                if (fd.getDirectory() != null && fd.getFile() != null) {
                    result[0] = new File(fd.getDirectory(), fd.getFile()).getAbsolutePath();
                }
                System.setProperty("apple.awt.fileDialogForDirectories", "false");
                parent.dispose();
                return;
            }
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            JFrame parent = new JFrame();
            parent.setAlwaysOnTop(true);
            parent.setUndecorated(true);
            parent.setType(java.awt.Window.Type.UTILITY);
            parent.setLocationRelativeTo(null);
            parent.setVisible(true);
            parent.toFront();
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select a Java project folder");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int res = fc.showDialog(parent, "Index this folder");
            parent.dispose();
            if (res == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                result[0] = fc.getSelectedFile().getAbsolutePath();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) task.run();
        else SwingUtilities.invokeAndWait(task);
        return result[0];
    }
}
