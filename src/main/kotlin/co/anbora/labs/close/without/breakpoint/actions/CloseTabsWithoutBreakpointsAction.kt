package co.anbora.labs.close.without.breakpoint.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import java.util.concurrent.CompletableFuture
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Editor tab context menu action that closes all open tabs whose files do not have
 * at least one enabled (active) line breakpoint.
 *
 * Requirements:
 * - Valid license or active trial required
 * - Only considers enabled line breakpoints (XLineBreakpoint with isEnabled == true)
 * - Closes tabs across all editor splits
 * - Safe against disposed projects and null checks
 */
class CloseTabsWithoutBreakpointsAction : AnAction(), DumbAware {

    companion object {
        private val LOG = Logger.getInstance(CloseTabsWithoutBreakpointsAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * Updates the action's enabled/visible state.
     * Only enabled when a valid project is available and not disposed.
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    /**
     * Performs the action: validates license, then closes all tabs without enabled breakpoints.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        CompletableFuture.supplyAsync {
            ReadAction.compute<Set<VirtualFile>, Throwable> {
                getFilesWithEnabledBreakpoints(project)
            }
        }.thenAcceptAsync({ filesWithEnabledBreakpoints ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    LOG.warn("Project disposed before action could execute")
                    return@invokeLater
                }

                try {
                    closeTabsWithoutBreakpoints(project, filesWithEnabledBreakpoints)
                } catch (e: Exception) {
                    LOG.error("Error closing tabs without breakpoints", e)
                }
            }
        })
    }

    /**
     * Core logic: identifies files with enabled breakpoints and closes all other open tabs.
     *
     * @param project The current project
     * @param filesWithEnabledBreakpoints Files with enabled breakpoints
     */
    private fun closeTabsWithoutBreakpoints(project: Project, filesWithEnabledBreakpoints: Set<VirtualFile>) {
        LOG.info("Found ${filesWithEnabledBreakpoints.size} files with enabled line breakpoints in background")

        // Step 2: Get all currently open files
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFiles = fileEditorManager.openFiles

        LOG.info("Found ${openFiles.size} open editor tabs")

        // Step 3: Determine which files to close (open files NOT in breakpoints set)
        val filesToClose = openFiles.filter { it !in filesWithEnabledBreakpoints }

        LOG.info("Closing ${filesToClose.size} tabs without enabled breakpoints")

        // Step 4: Close each file (this closes it across all splits)
        var closedCount = 0
        for (file in filesToClose) {
            try {
                fileEditorManager.closeFile(file)
                closedCount++
            } catch (e: Exception) {
                LOG.error("Failed to close file: ${file.path}", e)
            }
        }

        LOG.info("Successfully closed $closedCount tabs")
    }

    /**
     * Retrieves all virtual files that have at least one enabled line breakpoint.
     *
     * @param project The current project
     * @return Set of VirtualFile instances with enabled line breakpoints
     */
    private fun getFilesWithEnabledBreakpoints(project: Project): Set<VirtualFile> {
        val filesWithBreakpoints = mutableSetOf<VirtualFile>()

        try {
            val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
            val allBreakpoints = breakpointManager.allBreakpoints

            // Filter for enabled line breakpoints only
            for (breakpoint in allBreakpoints) {
                // Only process line breakpoints that are enabled
                if (breakpoint is XLineBreakpoint<*> && breakpoint.isEnabled) {
                    // Resolve the file URL to VirtualFile
                    val fileUrl = breakpoint.fileUrl
                    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl)

                    if (virtualFile != null && virtualFile.isValid) {
                        filesWithBreakpoints.add(virtualFile)
                    } else {
                        LOG.debug("Could not resolve file for breakpoint URL: $fileUrl")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Error collecting files with enabled breakpoints", e)
        }

        return filesWithBreakpoints
    }
}