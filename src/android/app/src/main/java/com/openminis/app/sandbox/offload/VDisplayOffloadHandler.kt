package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.vdisplay.VirtualDisplayManager
import org.json.JSONObject

/**
 * `android-vdisplay` — agent-facing control of Minis' own background virtual
 * display ("后台屏").
 *
 * Layout of responsibility:
 *   - [VDisplayCli] parses and validates argv (pure, unit-tested).
 *   - [VDisplayScreenshotPath] decides where a capture may be written (pure,
 *     unit-tested).
 *   - [VirtualDisplayManager] is the core API written by the engine owner; it
 *     knows nothing about shells, permissions or `/var/minis`.
 *   - this class is the only place those three meet, and the only place that
 *     touches Android (`Context`, `ShizukuManager`, `PRootKernel`).
 *
 * Three gates, in this order, for every command except `help`/`status`:
 *
 *   1. **Agent permission** — `OffloadGate.allow("vdisplay", …)`. This is a
 *      *separate* entry in [com.openminis.app.offload.OffloadPermissionManager]
 *      that defaults to NOT_ALLOWED, so authorizing Shizuku never by itself
 *      authorizes the agent. Mirrors `a11y_cli` / `shizuku_cli`.
 *   2. **Shizuku** — the virtual display is created by a privileged process;
 *      without a READY binder there is nothing to talk to.
 *   3. **Engine installed** — once the core installs an engine, its own
 *      failures (NOT_RUNNING for input before `start`, …) come back as typed
 *      envelope errors.
 *
 * Both gate checks are cheap and non-blocking for the BYPASS default; the
 * ASK_ONCE path blocks this offload worker in `runBlocking` exactly as the
 * sibling handlers do.
 */
class VDisplayOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // Built first so even a parse failure honours --compact / -q.
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("help", "h", "enter", "append"),
        )
        val parsed = VDisplayCli.parse(request.argv.drop(1))
        when (parsed) {
            is VDisplayCli.ParseResult.Help ->
                return NativeOffloadResult(0, VDisplayCli.HELP)

            is VDisplayCli.ParseResult.Failure ->
                // The envelope is what an agent parses; the exit code (2) is
                // what a shell script tests. Both say "your arguments".
                return err(args, parsed.code, parsed.message, 2)

            is VDisplayCli.ParseResult.Success -> Unit
        }

        val command = (parsed as VDisplayCli.ParseResult.Success).command

        // `status` is the diagnostic surface: it must answer even when the
        // agent is not allowed or Shizuku is down, otherwise "why was I
        // refused?" has no lookup.
        if (command !is VDisplayCli.Command.Status) {
            val allowed = OffloadGate.allow("vdisplay", VDisplayCli.TOOL, request)
            if (!allowed) {
                return err(
                    args,
                    "PERMISSION_DENIED",
                    "Agent is not allowed to use ${VDisplayCli.TOOL}. " +
                        "Open Settings → Permissions → Integrations to change. " +
                        "(`${VDisplayCli.TOOL} status` works without this permission.)",
                    126,
                )
            }
            val shizukuFailure = shizukuUnavailable(args)
            if (shizukuFailure != null) return shizukuFailure
        }

        // Ground truth for the overlay / FGS notification, same convention as
        // the a11y CLI: a one-line human status of what the agent is doing.
        com.openminis.app.service.SessionActivityTracker.updateToolStatus(statusLine(command))

        return try {
            dispatch(command, args, request.sessionId)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${t.javaClass.simpleName} ${t.message}")
            err(args, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    private fun dispatch(
        command: VDisplayCli.Command,
        args: OffloadArgs,
        sessionId: String?,
    ): NativeOffloadResult = when (command) {
        is VDisplayCli.Command.Status -> statusCommand(args)
        is VDisplayCli.Command.Start -> {
            val core = VirtualDisplayManager.start(command.width, command.height, command.dpi)
            when (core) {
                is VirtualDisplayManager.Result.Ok -> {
                    val data = core.data
                    data.put("width", command.width)
                    data.put("height", command.height)
                    data.put("dpi", command.dpi)
                    ok(args, data)
                }
                is VirtualDisplayManager.Result.Failure -> err(args, core.code, core.message, core.exit)
            }
        }
        is VDisplayCli.Command.Stop -> result(args, VirtualDisplayManager.stop())
        is VDisplayCli.Command.Screenshot -> screenshotCommand(command, args, sessionId)
        is VDisplayCli.Command.Tap -> result(args, VirtualDisplayManager.tap(command.x, command.y))
        is VDisplayCli.Command.Swipe -> result(
            args,
            VirtualDisplayManager.swipe(
                command.x1, command.y1, command.x2, command.y2, command.durationMs,
            ),
        )
        is VDisplayCli.Command.Key -> result(args, VirtualDisplayManager.key(command.keyCode))
        is VDisplayCli.Command.Text -> result(
            args,
            VirtualDisplayManager.text(command.text, command.pressEnter),
        )
        is VDisplayCli.Command.Type -> {
            // ENTER is sent by the manager inside the SAME service call (and
            // the same display id) as the paste, so a concurrent stop/start
            // cannot redirect it to a different display.
            result(args, VirtualDisplayManager.paste(command.text, command.replaceAll, command.pressEnter))
        }
        is VDisplayCli.Command.Clipboard -> when (command.action) {
            "get" -> result(args, VirtualDisplayManager.clipboardGet())
            "set" -> result(args, VirtualDisplayManager.clipboardSet(command.text ?: ""))
            "restore" -> result(args, VirtualDisplayManager.clipboardRestore())
            else -> err(args, "INVALID_ARGS", "unknown clipboard action", 2)
        }
        is VDisplayCli.Command.Launch -> result(args, VirtualDisplayManager.launch(command.packageName))
    }

    // ── status ───────────────────────────────────────────────────────────

    private fun statusCommand(args: OffloadArgs): NativeOffloadResult {
        val core = VirtualDisplayManager.status()
        val shizuku = ShizukuManager.snapshot.value
        val data = core.toJson()
            .put("tool", VDisplayCli.TOOL)
            // Reported explicitly so an agent can explain a refusal without
            // guessing which of the two gates closed.
            .put("permission", permissionName())
            .put("shizuku", JSONObject()
                .put("state", shizuku.state.name)
                .put("ready", shizuku.state == ShizukuManager.State.READY))
            .put("engineInstalled", core.engineInstalled)
        return ok(args, data)
    }

    /**
     * The stored agent-permission level, for `status` only. Reading it is not
     * a grant — it is exactly the value [OffloadGate] will consult.
     */
    private fun permissionName(): String = try {
        com.openminis.app.offload.OffloadPermissionManager.getLevel("vdisplay").name
    } catch (t: Throwable) {
        "UNKNOWN"
    }

    // ── screenshot ───────────────────────────────────────────────────────

    private fun screenshotCommand(
        command: VDisplayCli.Command.Screenshot,
        args: OffloadArgs,
        sessionId: String?,
    ): NativeOffloadResult {
        // Resolve the destination BEFORE capturing: a rejected path must not
        // cost a native frame copy, and an invalid `--output` has to fail as
        // an argument error rather than as a capture error.
        val resolved = VDisplayScreenshotPath.resolve(command.outputName)
        if (resolved is VDisplayScreenshotPath.Resolved.Rejected) {
            return err(args, "INVALID_ARGS", resolved.message, 2)
        }
        val requested = resolved as VDisplayScreenshotPath.Resolved.Ok

        val capture = VirtualDisplayManager.capture()
        when (capture) {
            is VirtualDisplayManager.CaptureResult.Failure ->
                return err(args, capture.code, capture.message, capture.exit)
            is VirtualDisplayManager.CaptureResult.Ok -> Unit
        }
        val frame = (capture as VirtualDisplayManager.CaptureResult.Ok).capture

        // [T-vdisplay-screenshot-mount] Same mount rule as the a11y screenshot:
        // resolve against the CALLING session's attachments dir, not the
        // process-global bind-mount map (last writer wins across chats), and
        // never fall back to a private directory the agent cannot read.
        // A known session whose mount cannot be resolved must fail loudly —
        // falling back to the global map here is exactly the cross-chat
        // overwrite the session resolver exists to prevent.
        val attachmentsHostDir: java.io.File? = sessionId
            ?.let { ExecutionCoordinator.mountedSessionIdFor(it) }
            ?.let { PRootKernel.resolveSessionHostPath(it, VDisplayScreenshotPath.ATTACHMENTS_ROOT, context) }

        if (attachmentsHostDir == null) {
            return err(
                args,
                "NO_ATTACHMENTS_MOUNT",
                "cannot resolve ${VDisplayScreenshotPath.ATTACHMENTS_ROOT} for this session; " +
                    "run this from an agent shell so the session's attachments mount exists",
            )
        }

        // Uniqueness is checked against the real filesystem, so a repeated
        // `--output` never destroys the earlier capture.
        val dirOf = { rel: String ->
            val slash = rel.lastIndexOf('/')
            if (slash < 0) attachmentsHostDir else java.io.File(attachmentsHostDir, rel.substring(0, slash))
        }
        val fileOf = { rel: String -> java.io.File(attachmentsHostDir, rel) }
        val unique = VDisplayScreenshotPath.uniqueName(
            requested.relativePath,
            exists = { rel -> fileOf(rel).exists() },
        )
        if (unique is VDisplayScreenshotPath.Resolved.Rejected) {
            return err(args, "WRITE_FAILED", unique.message)
        }
        val target = unique as VDisplayScreenshotPath.Resolved.Ok

        val dir = dirOf(target.relativePath)
        if (!dir.exists() && !dir.mkdirs()) {
            return err(args, "WRITE_FAILED", "could not create ${dir.absolutePath}")
        }
        val file = fileOf(target.relativePath)
        // Canonical containment is the last gate: resolve() rules out `..` by
        // shape, but only canonicalisation sees a symlink planted inside the
        // mount pointing somewhere outside $ATTACHMENTS_ROOT.
        val contained = VDisplayScreenshotPath.canonicalContainment(
            target.relativePath,
            canonicalize = { rel ->
                if (rel == VDisplayScreenshotPath.ATTACHMENTS_ROOT) {
                    attachmentsHostDir.canonicalPath
                } else {
                    file.canonicalPath
                }
            },
        )
        if (contained is VDisplayScreenshotPath.Resolved.Rejected) {
            return err(args, "INVALID_ARGS", contained.message, 2)
        }
        return try {
            // CREATE_NEW, not the two-arg FileOutputStream(String,boolean):
            // this fails atomically if another writer wins the race between
            // the uniqueness check and the open, so an existing capture can
            // never be truncated by a late writer.
            java.nio.channels.FileChannel.open(
                file.toPath(),
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.CREATE_NEW,
            ).use { ch ->
                val buf = java.nio.ByteBuffer.wrap(frame.png)
                while (buf.hasRemaining()) ch.write(buf)
            }
            val guestPath = "${VDisplayScreenshotPath.ATTACHMENTS_ROOT}/${target.relativePath}"
            val data = JSONObject()
                .put("path", guestPath)
                .put("minis_url", "minis://attachments/" + target.relativePath.split('/').joinToString("/") { seg ->
                    java.net.URLEncoder.encode(seg, "UTF-8")
                        .replace("+", "%20")
                        .replace("*", "%2A")
                        .replace("%7E", "~")
                })
                .put("width", frame.width)
                .put("height", frame.height)
                .put("sizeBytes", frame.png.size)
                .put("capturedAtMs", frame.capturedAtMs)
            ok(args, data)
        } catch (t: java.nio.file.FileAlreadyExistsException) {
            err(
                args,
                "WRITE_FAILED",
                "'${target.relativePath}' already exists (lost a create race); " +
                    "retry to get an unused -N name — the earlier capture was not touched",
            )
        } catch (t: Throwable) {
            // Only the exception class + message — never any image bytes.
            AppLogger.warning(TAG, "screenshot write failed: ${t.javaClass.simpleName} ${t.message}")
            err(args, "WRITE_FAILED", "failed to write PNG: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun shizukuUnavailable(args: OffloadArgs): NativeOffloadResult? {
        ShizukuManager.refresh()
        return when (ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.NOT_INSTALLED -> err(
                args,
                "SERVICE_NOT_RUNNING",
                "Shizuku is not installed. The background display is created by a privileged " +
                    "process; install Shizuku (or AXManager / Sui) and authorize Minis. " +
                    "See Settings → Permissions → Shizuku.",
                77,
            )
            ShizukuManager.State.NOT_RUNNING -> err(
                args,
                "SERVICE_NOT_RUNNING",
                "Shizuku service is not running. Start it, then retry.",
                77,
            )
            ShizukuManager.State.NEED_PERMISSION -> err(
                args,
                "PERMISSION_DENIED",
                "Minis is not authorized for Shizuku. Grant it in Settings → Permissions → Shizuku.",
                77,
            )
            ShizukuManager.State.READY -> null
        }
    }

    private fun result(args: OffloadArgs, r: VirtualDisplayManager.Result): NativeOffloadResult =
        when (r) {
            is VirtualDisplayManager.Result.Ok -> ok(args, r.data)
            is VirtualDisplayManager.Result.Failure -> err(args, r.code, r.message, r.exit)
        }

    private fun ok(args: OffloadArgs, data: Any): NativeOffloadResult {
        val body = JSONObject().put("ok", true).put("data", data).toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun err(
        args: OffloadArgs,
        code: String,
        message: String,
        exit: Int = 1,
    ): NativeOffloadResult {
        val body = JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", code).put("message", message),
        ).toString()
        return NativeOffloadResult(exit, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun statusLine(command: VDisplayCli.Command): String = when (command) {
        is VDisplayCli.Command.Status -> "vdisplay: checking status"
        is VDisplayCli.Command.Start -> "vdisplay: starting ${command.width}x${command.height}"
        is VDisplayCli.Command.Stop -> "vdisplay: stopping"
        is VDisplayCli.Command.Screenshot -> "vdisplay: capturing screenshot"
        is VDisplayCli.Command.Tap -> "vdisplay: tap (${command.x}, ${command.y})"
        is VDisplayCli.Command.Swipe -> "vdisplay: swipe"
        is VDisplayCli.Command.Key -> "vdisplay: key ${command.name}"
        is VDisplayCli.Command.Text -> "vdisplay: typing ASCII text"
        is VDisplayCli.Command.Type -> "vdisplay: pasting Unicode text"
        is VDisplayCli.Command.Clipboard -> "vdisplay: clipboard ${command.action}"
        is VDisplayCli.Command.Launch -> "vdisplay: launching ${command.packageName}"
    }

    private companion object {
        const val TAG = "VDisplayOffload"
    }
}
