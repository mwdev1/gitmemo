package com.gitmemo.ui

import com.gitmemo.GitMemoBundle
import com.gitmemo.claude.ClaudeCodeSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Edits the note body of a single commit. Shared by the commit details icon and the log actions.
 *
 * The dialog only collects text; persisting it is the caller's job, so the git write can happen on a
 * background thread after the dialog closes.
 */
internal class GitNoteDialog(
  private val project: Project,
  private val shortHash: String,
  private val notesRef: String,
  initialText: String,
) : DialogWrapper(project, true) {

  private val textArea = JBTextArea(initialText, 14, 72).apply {
    lineWrap = true
    wrapStyleWord = true
    emptyText.text = GitMemoBundle.message("panel.noNote")
  }

  /** `null` when the terminal plugin is disabled, in which case no button is offered. */
  private val sendToClaudeAction =
    if (ClaudeCodeSupport.isAvailable()) SendToClaudeCodeAction() else null

  /**
   * `null` when the commit carries no note, so there is nothing to delete.
   *
   * Deliberately tied to the stored body rather than to the text area: the button removes what is in
   * git, which typing in the dialog has not changed yet.
   */
  private val deleteAction = if (initialText.isNotBlank()) DeleteNoteAction() else null

  /** The edited body. Blank text means "remove the note". */
  val noteText: String get() = textArea.text

  init {
    title = GitMemoBundle.message("dialog.edit.title", shortHash)
    setOKButtonText(GitMemoBundle.message("panel.save"))
    // "Close" rather than "Cancel": the dialog is as much a reader as an editor, so dismissing it is
    // the normal way out and not an abandoned edit.
    setCancelButtonText(GitMemoBundle.message("dialog.edit.close"))
    init()

    // Swing only re-reads an Action's enabled state from the "enabled" property change it fires, so
    // the button has to be toggled explicitly as the body is edited.
    sendToClaudeAction?.let { action ->
      textArea.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          action.isEnabled = textArea.text.isNotBlank()
        }
      })
    }
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

  /**
   * Delete and Claude Code sit on the left so they read as auxiliary actions rather than as
   * alternatives to Save.
   */
  override fun createLeftSideActions(): Array<Action> =
    listOfNotNull(deleteAction, sendToClaudeAction).toTypedArray()

  override fun getPreferredFocusedComponent(): JComponent = textArea

  override fun getDimensionServiceKey(): String = "com.gitmemo.ui.GitNoteDialog"

  /**
   * Closes the dialog with [DELETE_EXIT_CODE] once the user confirms.
   *
   * The removal itself is left to the caller, like saving is, so the git call stays off the EDT.
   * Confirming before closing rather than after keeps the two dialogs from stacking up.
   */
  private inner class DeleteNoteAction : AbstractAction(GitMemoBundle.message("dialog.edit.delete")) {
    override fun actionPerformed(e: ActionEvent) {
      if (GitNoteEditing.confirmDelete(project, shortHash)) close(DELETE_EXIT_CODE)
    }
  }

  /**
   * Hands the note being edited to Claude Code and then closes, since the user's attention moves to
   * the terminal.
   *
   * Closing goes through Close rather than Save, so an unsaved edit is discarded — the body is handed
   * to Claude, not written to git. The send itself is fire-and-forget and already holds its own copy
   * of the text, so it survives the dialog going away.
   */
  private inner class SendToClaudeCodeAction :
    AbstractAction(GitMemoBundle.message("dialog.edit.sendToClaude")) {

    init {
      isEnabled = textArea.text.isNotBlank()
      putValue(SHORT_DESCRIPTION, GitMemoBundle.message("dialog.edit.sendToClaude.tooltip"))
    }

    override fun actionPerformed(e: ActionEvent) {
      ClaudeCodeSupport.sendNote(project, shortHash, notesRef, textArea.text)
      doCancelAction()
    }
  }

  companion object {
    /** Exit code telling the caller to remove the note instead of saving the edited body. */
    const val DELETE_EXIT_CODE: Int = NEXT_USER_EXIT_CODE
  }
}
