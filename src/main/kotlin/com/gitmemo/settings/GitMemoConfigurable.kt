package com.gitmemo.settings

import com.gitmemo.GitMemoBundle
import com.gitmemo.notes.GitNotesService
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/** Settings page under *Version Control* for choosing the notes namespace. */
internal class GitMemoConfigurable(private val project: Project) :
  BoundConfigurable(GitMemoBundle.message("settings.title")) {

  private val settings = GitMemoSettings.getInstance(project)

  override fun createPanel(): DialogPanel = panel {
    row(GitMemoBundle.message("settings.notesRef.label")) {
      textField()
        .bindText(settings::notesRef)
        .columns(COLUMNS_LARGE)
        .validationOnApply { if (it.text.isBlank()) error(GitMemoBundle.message("settings.notesRef.empty")) else null }
        .comment(GitMemoBundle.message("settings.notesRef.comment"))
    }
  }

  @Throws(ConfigurationException::class)
  override fun apply() {
    val refChanged = isModified
    super.apply()
    // Cached `git notes list` output is keyed by ref, but stale entries are pointless to keep.
    if (refChanged) GitNotesService.getInstance(project).invalidateCaches()
  }
}
