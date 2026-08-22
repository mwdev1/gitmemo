package com.gitmemo.ui

import com.gitmemo.GitMemoBundle
import com.gitmemo.notes.GitNotesService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.util.VcsLogUtil

/**
 * The read → edit → write flow, shared by the commit details icon, the log actions and the tool
 * window. Git calls happen on a background thread; only the dialog touches the EDT.
 */
internal object GitNoteEditing {

  /**
   * Opens the edit dialog for the note on [hash] and saves the result.
   *
   * @param preloaded the current body if the caller already has it, which skips the initial read
   */
  fun editNote(project: Project, root: VirtualFile, hash: String, preloaded: String? = null) {
    if (preloaded != null) {
      showDialogAndSave(project, root, hash, preloaded)
      return
    }
    runInBackground(project, GitMemoBundle.message("progress.loadingNote")) {
      val current = GitNotesService.getInstance(project).readNote(root, hash).orEmpty()
      ApplicationManager.getApplication().invokeLater({ showDialogAndSave(project, root, hash, current) }, project.disposed)
    }
  }

  /** Removes the note on [hash], asking for confirmation first when [confirm] is set. */
  fun deleteNote(project: Project, root: VirtualFile, hash: String, confirm: Boolean = true) {
    if (confirm && !confirmDelete(project, shortHash(hash))) return
    runInBackground(project, GitMemoBundle.message("progress.deletingNote")) {
      GitNotesService.getInstance(project).deleteNote(root, hash)
    }
  }

  /** Asks whether the note on [shortHash] should really go, so every surface words it the same way. */
  fun confirmDelete(project: Project, shortHash: String): Boolean =
    Messages.showYesNoDialog(
      project,
      GitMemoBundle.message("dialog.delete.message", shortHash),
      GitMemoBundle.message("dialog.delete.title"),
      Messages.getQuestionIcon(),
    ) == Messages.YES

  /** Writes [text] as the note on [hash]; blank text removes the note. */
  fun saveNote(project: Project, root: VirtualFile, hash: String, text: String) {
    runInBackground(project, GitMemoBundle.message("progress.savingNote")) {
      GitNotesService.getInstance(project).writeNote(root, hash, text)
    }
  }

  fun shortHash(hash: String): String = VcsLogUtil.getShortHash(hash)

  private fun showDialogAndSave(project: Project, root: VirtualFile, hash: String, current: String) {
    val service = GitNotesService.getInstance(project)
    val dialog = GitNoteDialog(project, shortHash(hash), service.notesRef, current)
    dialog.show()
    when (dialog.exitCode) {
      DialogWrapper.OK_EXIT_CODE -> {
        val edited = dialog.noteText
        if (edited != current) saveNote(project, root, hash, edited)
      }
      // The dialog already asked, so do not confirm twice.
      GitNoteDialog.DELETE_EXIT_CODE -> deleteNote(project, root, hash, confirm = false)
    }
  }

  /** Runs [body] on a background thread, reporting any [VcsException] as an error notification. */
  private fun runInBackground(project: Project, title: String, body: () -> Unit) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          body()
        }
        catch (e: VcsException) {
          VcsNotifier.getInstance(project).notifyError("gitmemo.note.operation.failed", title, e.message.orEmpty())
        }
      }
    })
  }
}
