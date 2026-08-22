package com.gitmemo.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/** Project-level configuration for the Git Notes plugin. */
@Service(Service.Level.PROJECT)
@State(name = "GitMemoSettings", storages = [Storage("gitmemo.xml")])
class GitMemoSettings : PersistentStateComponent<GitMemoSettings.State> {

    class State {
        /** Namespace passed to `git notes --ref`. */
        var notesRef: String = DEFAULT_NOTES_REF
    }

    private var state = State()

    /** Never blank: falls back to [DEFAULT_NOTES_REF] if the persisted value was cleared. */
    var notesRef: String
        get() = state.notesRef.ifBlank { DEFAULT_NOTES_REF }
        set(value) {
            state.notesRef = value.trim().ifEmpty { DEFAULT_NOTES_REF }
        }

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        const val DEFAULT_NOTES_REF: String = "refs/notes/commits"

        fun getInstance(project: Project): GitMemoSettings = project.service()
    }
}
