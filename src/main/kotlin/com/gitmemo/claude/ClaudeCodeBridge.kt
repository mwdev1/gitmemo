package com.gitmemo.claude

import com.gitmemo.GitMemoBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.session.TerminalWriteBytesEvent
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.jediterm.terminal.ProcessTtyConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.ProxyTtyConnector
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Writes text into a Claude Code session, starting one if none is running.
 *
 * The Claude Code plugin (`com.anthropic.code.plugin`) exposes no API for this. Its own
 * "Send to Claude Code" action publishes an `at_mentioned` MCP notification carrying only a file path
 * and a line range, which cannot express a note body, and its `TerminalUtil` is internal. So we do
 * what its own Shift+Enter handler does and write straight to the terminal's input.
 *
 * Only [ClaudeCodeSupport] may call this, and only behind its plugin-availability guard — the
 * terminal plugin is an optional dependency, so these classes are absent when it is disabled.
 */
internal object ClaudeCodeBridge {
  private val LOG = logger<ClaudeCodeBridge>()

  /** Tab name the Claude Code plugin gives its terminal, via `createShellWidget(basePath, "Claude Code", …)`. */
  private const val TAB_PREFIX = "Claude Code"

  private const val CLAUDE_EXECUTABLE = "claude"

  /** The Claude Code plugin's Ctrl+Escape action, which focuses an existing session or opens a new one. */
  private const val OPEN_CLAUDE_ACTION_ID = "com.anthropic.code.plugin.actions.OpenClaudeInTerminalAction"

  /**
   * Bracketed paste markers. Claude Code's TUI treats a bare newline as Enter — that is exactly how
   * the plugin implements Shift+Enter — so a multi-line note written verbatim would submit its first
   * line and leave the rest as separate prompts.
   */
  private val ESC = Char(27)
  private val PASTE_START = "$ESC[200~"
  private val PASTE_END = "$ESC[201~"

  /**
   * Product banner Claude Code prints once its TUI is up, used to tell "the CLI is accepting input"
   * from "the shell is still at its prompt". Case matters: the echoed `claude` command line is
   * lowercase, so it cannot be mistaken for the banner.
   */
  private const val BANNER_MARKER = "Claude Code"

  private val POLL_INTERVAL = 250.milliseconds

  /** Grace period after the banner appears, so the prompt is drawn and in bracketed-paste mode. */
  private val SETTLE_DELAY = 500.milliseconds

  /** Generous, because a cold `claude` start also resolves the CLI and connects to the MCP server. */
  private val STARTUP_TIMEOUT = 60.seconds

  /**
   * Types [payload] into Claude Code's prompt, launching a session first if there is none.
   *
   * The text is never submitted, so the user can add their own instructions before pressing Enter.
   */
  @RequiresEdt
  fun sendNote(project: Project, payload: String) {
    val running = findSession(project)
    if (running != null) {
      writeAndFocus(project, running, payload)
      return
    }
    if (!startSession(project)) {
      ClaudeCodeSupport.notifyProblem(
        project, "gitmemo.claude.no.session",
        GitMemoBundle.message("notification.claudeNoSession"),
      )
      return
    }
    // A cold start takes seconds, and anything written before the TUI is up would be swallowed by the
    // shell that is still launching it, so the note has to wait for the banner.
    project.service<ClaudeCodeScope>().coroutineScope.launch {
      val session = withTimeoutOrNull(STARTUP_TIMEOUT) { awaitReadySession(project) }
      if (session == null) {
        ClaudeCodeSupport.notifyProblem(
          project, "gitmemo.claude.start.timeout",
          GitMemoBundle.message("notification.claudeStartTimeout"),
        )
        return@launch
      }
      withContext(Dispatchers.EDT) { writeAndFocus(project, session, payload) }
    }
  }

  /**
   * The most recently opened terminal running Claude Code, or `null` if there is none.
   *
   * The tab name comes first because it is the one signal available in both terminal generations, and
   * it is what the Claude Code plugin's own private `findClaudeTerminal` matches on. A live `claude`
   * process is the fallback, which catches a session the user started by hand — but only in the
   * classic terminal, since the reworked one runs the shell out of process and exposes no handle to it.
   */
  private fun findSession(project: Project): TerminalWidget? {
    val tabs = terminalTabs(project)
    return tabs.lastOrNull { (content, _) -> content.tabName.startsWith(TAB_PREFIX) }?.second
      ?: tabs.lastOrNull { (_, widget) -> runsClaude(widget) }?.second
  }

  /**
   * Opens a Claude Code session, returning whether one could be started at all.
   *
   * The Claude Code plugin's own action is preferred, because starting the CLI is only half of what it
   * does: it also spins up the MCP server that gives Claude the IDE's diagnostics and selection, wires
   * up Shift+Enter, and frees the Escape key. Launching the CLI ourselves is the fallback for an IDE
   * without that plugin, and gives a plain session with none of that integration.
   */
  @RequiresEdt
  private fun startSession(project: Project): Boolean {
    val action = ActionManager.getInstance().getAction(OPEN_CLAUDE_ACTION_ID)
    if (action != null) {
      val event = AnActionEvent.createEvent(
        action,
        SimpleDataContext.getProjectContext(project),
        null,
        ActionPlaces.UNKNOWN,
        ActionUiKind.NONE,
        null,
      )
      ActionUtil.performAction(action, event)
      return true
    }
    val basePath = project.basePath ?: return false
    LOG.info("The Claude Code plugin is not installed; starting the CLI in a plain terminal tab")
    val widget = TerminalToolWindowManager.getInstance(project)
      .createShellWidget(basePath, TAB_PREFIX, true, true)
    widget.sendCommandToExecute(CLAUDE_EXECUTABLE)
    return true
  }

  /** Polls until a session exists and has printed its banner. Cancelled by the caller's timeout. */
  private suspend fun awaitReadySession(project: Project): TerminalWidget {
    while (true) {
      val ready = withContext(Dispatchers.EDT) {
        // getText() is a function, not a property: TerminalWidget declares it as one in Kotlin.
        findSession(project)?.takeIf { it.getText().contains(BANNER_MARKER) }
      }
      if (ready != null) {
        delay(SETTLE_DELAY)
        return ready
      }
      delay(POLL_INTERVAL)
    }
  }

  @RequiresEdt
  private fun writeAndFocus(project: Project, session: TerminalWidget, text: String) {
    write(project, session, PASTE_START + text.replace("\r\n", "\n").replace('\r', '\n') + PASTE_END)

    ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
      ?.activate({ session.requestFocus() }, true)
  }

  /**
   * The two terminal generations take input through entirely different channels.
   *
   * The classic terminal owns the pty locally and exposes it as a [com.jediterm.terminal.TtyConnector].
   * The reworked terminal (the default since 2025.2) does not: `connectToTty` throws on it and its
   * `ttyConnectorAccessor` is never populated, so a write through the accessor would be dropped
   * without a trace. There, input has to go to the backend session as a [TerminalWriteBytesEvent].
   */
  private fun write(project: Project, session: TerminalWidget, payload: String) {
    val connector = session.ttyConnector
    if (connector != null) {
      connector.write(payload)
      return
    }
    val terminalSession = requireNotNull(session.session) { "the terminal exposes neither a TTY nor a session" }
    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
    // getInputChannel() suspends, and this runs on the EDT from the dialog button.
    project.service<ClaudeCodeScope>().coroutineScope.launch {
      terminalSession.getInputChannel().send(TerminalWriteBytesEvent(bytes))
    }
  }

  /** Terminal tabs paired with their widget, in tool window order. */
  private fun terminalTabs(project: Project): List<Pair<Content, TerminalWidget>> {
    val toolWindow = ToolWindowManager.getInstance(project)
      .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID) ?: return emptyList()
    return toolWindow.contentManager.contents.mapNotNull { content ->
      TerminalToolWindowManager.findWidgetByContent(content)?.let { content to it }
    }
  }

  /** Whether the shell in [widget], or anything it spawned, is the Claude CLI. */
  private fun runsClaude(widget: TerminalWidget): Boolean {
    val process = shellProcess(widget) ?: return false
    if (!process.isAlive) return false
    val handle = try {
      process.toHandle()
    }
    catch (e: UnsupportedOperationException) {
      LOG.debug("Cannot inspect the terminal process", e)
      return false
    }
    return isClaude(handle) || handle.descendants().anyMatch { isClaude(it) }
  }

  private fun shellProcess(widget: TerminalWidget): Process? {
    val connector = widget.ttyConnector ?: return null
    val unwrapped = if (connector is ProxyTtyConnector) connector.connector else connector
    return (unwrapped as? ProcessTtyConnector)?.process
  }

  /**
   * Whether [handle] is the Claude CLI.
   *
   * `claude` is a script with an interpreter shebang, so when it is exec'd the reported command is
   * the interpreter and the script path shows up in the arguments instead. Both spellings are
   * checked on the file name only, so an unrelated `vim claude.md` is not mistaken for a session.
   */
  private fun isClaude(handle: ProcessHandle): Boolean {
    val info = handle.info()
    if (info.command().map(::isClaudeFileName).orElse(false)) return true
    return info.arguments().map { args -> args.any(::isClaudeFileName) }.orElse(false)
  }

  private fun isClaudeFileName(path: String): Boolean = try {
    Path.of(path).fileName?.toString() == CLAUDE_EXECUTABLE
  }
  catch (_: Exception) {
    false
  }
}
