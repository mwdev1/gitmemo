package com.gitmemo.ui

import com.gitmemo.GitMemoBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Edits the note body of a single commit. Shared by the commit details icon and the log actions.
 *
 * The dialog only collects text; persisting it is the caller's job, so the git write can happen on a
 * background thread after the dialog closes.
 */
internal class GitNoteDialog(
  project: Project,
  private val shortHash: String,
  private val notesRef: String,
  initialText: String,
) : DialogWrapper(project, true) {

  private val textArea = JBTextArea(initialText, 14, 72).apply {
    lineWrap = true
    wrapStyleWord = true
    emptyText.text = GitMemoBundle.message("panel.noNote")
  }

  /** The edited body. Blank text means "remove the note". */
  val noteText: String get() = textArea.text

  init {
    title = GitMemoBundle.message("dialog.edit.title", shortHash)
    setOKButtonText(GitMemoBundle.message("panel.save"))
    init()
  }

  override fun createCenterPanel(): JComponent {
    val refLabel = JBLabel(GitMemoBundle.message("dialog.edit.ref", notesRef)).apply {
      foreground = UIUtil.getContextHelpForeground()
      border = JBUI.Borders.emptyBottom(6)
    }
    return JPanel(BorderLayout()).apply {
      add(refLabel, BorderLayout.NORTH)
      add(JBScrollPane(textArea), BorderLayout.CENTER)
    }
  }

  override fun getPreferredFocusedComponent(): JComponent = textArea

  override fun getDimensionServiceKey(): String = "com.gitmemo.ui.GitNoteDialog"
}
