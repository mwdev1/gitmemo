package com.gitmemo.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.vcs.log.impl.VcsProjectLog

internal class GitNotesToolWindowFactory : ToolWindowFactory, DumbAware {

  override fun shouldBeAvailable(project: Project): Boolean = VcsProjectLog.isAvailable(project)

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = GitNotesPanel(project)
    Disposer.register(toolWindow.disposable, panel)

    val content = ContentFactory.getInstance().createContent(panel, null, false)
    content.isCloseable = false
    toolWindow.contentManager.addContent(content)

    // The main log UI is created lazily, so a panel built before the Log tab was ever opened has
    // nothing to listen to yet. Retry whenever this tool window is shown.
    project.messageBus.connect(panel).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
      override fun toolWindowShown(shownToolWindow: ToolWindow) {
        if (shownToolWindow.id == ID) panel.attachToLog()
      }
    })
  }

  companion object {
    const val ID: String = "GitNotes"
  }
}
