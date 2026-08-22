package com.gitmemo.ui

import com.gitmemo.GitMemoBundle
import com.gitmemo.notes.GitNotesChangedListener
import com.gitmemo.notes.GitNotesService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.VcsException
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.impl.VcsLogManager
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.event.ListSelectionListener

/**
 * Shows the git note of the commit selected in the VCS Log, with inline editing.
 *
 * The commit details panel can only host an icon (the platform turns status presentations into
 * toolbar actions), so this panel is where the full note body is readable.
 */
internal class GitNotesPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

  private val header = JBLabel().apply { border = JBUI.Borders.empty(4, 8) }
  private val refLabel = JBLabel().apply {
    foreground = UIUtil.getContextHelpForeground()
    border = JBUI.Borders.empty(0, 8, 4, 8)
  }
  private val textArea = JBTextArea().apply {
    lineWrap = true
    wrapStyleWord = true
    isEnabled = false
    emptyText.text = GitMemoBundle.message("panel.noSelection")
  }

  private var commit: CommitId? = null

  /** Body as last loaded from git, so Save can be a no-op when nothing changed. */
  private var loadedNote: String = ""

  /** Bumped per load request; stale background results are discarded. */
  private val loadGeneration = AtomicLong()

  /** The table we already listen to, so re-attach attempts are idempotent. */
  private var attachedTable: VcsLogGraphTable? = null

  @Volatile
  private var disposed = false

  private val selectionListener = ListSelectionListener { event ->
    if (!event.valueIsAdjusting) attachedTable?.let { showSelectionOf(it) }
  }

  init {
    val content = JPanel(BorderLayout()).apply {
      add(JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(refLabel, BorderLayout.SOUTH)
      }, BorderLayout.NORTH)
      add(JBScrollPane(textArea), BorderLayout.CENTER)
    }
    setContent(content)
    toolbar = createToolbar()

    showNoSelection()
    subscribe()
    attachToLog()
  }

  // ---------------------------------------------------------------- log selection

  private fun subscribe() {
    val connection = project.messageBus.connect(this)
    connection.subscribe(GitNotesChangedListener.TOPIC, GitNotesChangedListener { root, hash ->
      val current = commit ?: return@GitNotesChangedListener
      if (current.root == root && current.hash.asString() == hash) reload()
    })
    // MainVcsLogUi only exists once the Log tab has been opened, and is recreated with the log.
    connection.subscribe(VcsProjectLog.VCS_PROJECT_LOG_CHANGED, object : VcsProjectLog.ProjectLogListener {
      override fun logCreated(manager: VcsLogManager) = attachToLog()
      override fun logDisposed(manager: VcsLogManager) = detachFromLog()
    })
  }

  /** Attaches to the main log table's selection, if the log UI already exists. */
  fun attachToLog() {
    ApplicationManager.getApplication().invokeLater({
      if (disposed) return@invokeLater
      val table = VcsProjectLog.getInstance(project).mainUi?.table ?: return@invokeLater
      if (table === attachedTable) return@invokeLater
      detachFromLog()
      attachedTable = table
      table.selectionModel.addListSelectionListener(selectionListener)
      showSelectionOf(table)
    }, project.disposed)
  }

  private fun detachFromLog() {
    attachedTable?.selectionModel?.removeListSelectionListener(selectionListener)
    attachedTable = null
  }

  private fun showSelectionOf(table: VcsLogGraphTable) {
    val commits = table.selection.commits
    if (commits.size != 1) {
      showNoSelection()
      return
    }
    show(commits.single())
  }

  // ---------------------------------------------------------------- loading

  private fun show(commitId: CommitId) {
    if (commitId == commit) return
    commit = commitId
    reload()
  }

  private fun showNoSelection() {
    commit = null
    loadedNote = ""
    loadGeneration.incrementAndGet()
    header.text = ""
    refLabel.text = ""
    textArea.text = ""
    textArea.isEnabled = false
    textArea.emptyText.text = GitMemoBundle.message("panel.noSelection")
  }

  /** Re-reads the note of the current commit on a pooled thread. */
  fun reload() {
    val commitId = commit ?: return
    val hash = commitId.hash.asString()
    val service = GitNotesService.getInstance(project)

    header.text = GitMemoBundle.message("panel.header", GitNoteEditing.shortHash(hash))
    refLabel.text = GitMemoBundle.message("panel.ref", service.notesRef)
    textArea.isEnabled = false
    textArea.emptyText.text = GitMemoBundle.message("panel.loading")

    val generation = loadGeneration.incrementAndGet()
    ApplicationManager.getApplication().executeOnPooledThread {
      val note = try {
        service.readNote(commitId.root, hash)
      }
      catch (e: VcsException) {
        LOG.warn("Cannot read note of $hash", e)
        null
      }
      ApplicationManager.getApplication().invokeLater({
        if (generation != loadGeneration.get()) return@invokeLater
        loadedNote = note.orEmpty()
        textArea.text = loadedNote
        textArea.caretPosition = 0
        textArea.isEnabled = true
        textArea.emptyText.text = GitMemoBundle.message("panel.noNote")
      }, project.disposed)
    }
  }

  // ---------------------------------------------------------------- actions

  private fun createToolbar(): javax.swing.JComponent {
    val group = DefaultActionGroup(
      object : DumbAwareAction(GitMemoBundle.message("panel.save"), null, AllIcons.Actions.MenuSaveall) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
          e.presentation.isEnabled = commit != null && textArea.isEnabled && textArea.text != loadedNote
        }

        override fun actionPerformed(e: AnActionEvent) {
          val commitId = commit ?: return
          // loadedNote is refreshed by the reload that GitNotesChangedListener triggers on success,
          // so a failed write leaves Save enabled.
          GitNoteEditing.saveNote(project, commitId.root, commitId.hash.asString(), textArea.text)
        }
      },
      object : DumbAwareAction(GitMemoBundle.message("panel.delete"), null, AllIcons.General.Remove) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
          e.presentation.isEnabled = commit != null && textArea.isEnabled && loadedNote.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
          val commitId = commit ?: return
          GitNoteEditing.deleteNote(project, commitId.root, commitId.hash.asString())
        }
      },
      object : DumbAwareAction(GitMemoBundle.message("panel.refresh"), null, AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
          e.presentation.isEnabled = commit != null
        }

        override fun actionPerformed(e: AnActionEvent) {
          GitNotesService.getInstance(project).invalidateCaches()
          reload()
        }
      },
    )
    val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
    toolbar.targetComponent = textArea
    return toolbar.component
  }

  override fun dispose() {
    disposed = true
    detachFromLog()
  }

  private companion object {
    private val LOG = logger<GitNotesPanel>()
  }
}
