import re, sys

base = "src/android/app/src/main/java/com/openminis/app/sandbox/offload/"
fail = []

def check(name, cond, msg):
    if not cond:
        fail.append(f"{name}: {msg}")

# ── emulate VDisplayScreenshotPath.resolve on key inputs ──────────────────
ATT = "/var/minis/attachments"

def resolve(output, ts=0):
    if output is not None and output.strip() == "":
        return ("Rejected", "empty")
    name = output.strip() if output is not None else None
    if name is None:
        import datetime
        name = "vdisplay_%s.png" % datetime.datetime.utcfromtimestamp(ts/1000).strftime("%Y%m%d-%H%M%S-") + "%03d" % (ts % 1000)
        name = name  # placeholder
    for ch in name:
        if ord(ch) < 0x20 or ord(ch) == 0x7F:
            return ("Rejected", "control")
    rest = name.replace("\\", "/")
    if rest.startswith("/"):
        if rest != ATT and not rest.startswith(ATT + "/"):
            return ("Rejected", "outside " + ATT)
        rest = rest[len(ATT):].lstrip("/")
    if rest == "":
        return ("Rejected", "is a directory")
    if rest.endswith("/"):
        return ("Rejected", "must name a file")
    segs = rest.split("/")
    if any(s == "" for s in segs):
        return ("Rejected", "empty segment")
    if any(s in (".", "..") for s in segs):
        return ("Rejected", ". or .. segment")
    fn = segs[-1]
    if not fn.lower().endswith(".png"):
        return ("Rejected", ".png")
    if fn.startswith("."):
        return ("Rejected", "hidden")
    rel = f"vdisplay/{fn}" if len(segs) == 1 else "/".join(segs)
    return ("Ok", rel)

def sim_default(ts):
    import datetime
    return "vdisplay_%s.png" % (datetime.datetime.fromtimestamp(ts/1000, datetime.timezone.utc).strftime("%Y%m%d-%H%M%S-") + "%03d" % (ts % 1000))

# Test expectation 1: ..%2Fa.png must be Ok (not traversal)
st, v = resolve("vdisplay/..%2Fa.png")
check("resolve", st == "Ok" and v == "vdisplay/..%2Fa.png", f"..%2Fa.png -> {st} {v}")
st, v = resolve("vdisplay/%2Fetc.png")
check("resolve", st == "Ok", f"%2Fetc.png -> {st} {v}")
# classic traversals still rejected
for bad in ["vdisplay/../../workspace/a.png", "vdisplay/./a.png", "/var/minis/attachments/../../data/a.png",
            "/sdcard/Pictures/a.png", "/var/minis/workspace/a.png", "/var/minis/attachments-evil/a.png",
            "/data/local/tmp/a.png", "/var/minis/attachments/../workspace/a.png", "a.jpg", "a.png.exe",
            "a", "a.PNGX", "", "/var/minis/attachments", "/var/minis/attachments/", "vdisplay/",
            ".hidden.png", "vdisplay//a.png", "a\x00.png", "a\nb.png"]:
    st, v = resolve(bad)
    check("resolve", st == "Rejected", f"'{bad}' unexpectedly accepted: {v}")
# accepted set
for good, want in [("shot.png", "vdisplay/shot.png"), ("SHOT.PNG", "vdisplay/SHOT.PNG"),
                   ("/var/minis/attachments/vdisplay/a.png", "vdisplay/a.png"),
                   ("/var/minis/attachments/a.png", "a.png"), ("vdisplay/a.png", "vdisplay/a.png"),
                   ("vdisplay\\a.png", "vdisplay/a.png")]:
    st, v = resolve(good)
    check("resolve", st == "Ok" and v == want, f"'{good}' -> {st} {v}")
# default names (UTC)
check("default", sim_default(0) == "vdisplay_19700101-000000-000.png", sim_default(0))
check("default", sim_default(1_767_268_800_500) == "vdisplay_20260101-120000-500.png", sim_default(1_767_268_800_500))
# canonical containment incl. prefix-sibling
def cc(rel, canon):
    root = canon(ATT)
    full = canon(ATT + "/" + rel)
    prefix = root if root.endswith("/") else root + "/"
    if root == "" or not full.startswith(prefix):
        return "Rejected"
    return "Ok"
check("cc", cc("vdisplay/link.png", lambda r: "/sdcard/evil.png" if r.endswith("link.png") else "/d/a/" + r if False else "/d/minis/attachments") == "Rejected", "symlink escape")
check("cc", cc("vdisplay/a.png", lambda r: "/d/minis/attachments" if r == ATT else "/d/minis/attachments-evil/vdisplay/a.png") == "Rejected", "prefix sibling")
check("cc", cc("vdisplay/a.png", lambda r: "/d/minis/attachments/" + ("", rel)[r != ATT] if False else ("/d/minis/attachments" if r == ATT else "/d/minis/attachments/vdisplay/a.png")) == "Ok", "inside ok")

# ── emulate VDisplayCli text validation ───────────────────────────────────
def validate_text(t):
    if t.strip() == "":
        return "blank"
    if len(t) > 1000:
        return "too long"
    for ch in t:
        if ord(ch) < 0x20 or ord(ch) == 0x7F:
            return "control"
        if ord(ch) > 0x7E:
            return "ASCII"
    return None

check("text", validate_text("plain ASCII ok_!~") is None, "ascii should pass")
check("text", validate_text("中文输入") is not None and "ASCII" in validate_text("中文输入"), f"zh -> {validate_text('中文输入')}")
check("text", validate_text("café") is not None, "café")
check("text", validate_text("a\u0007b") is not None, "bell")
check("text", "not supported" in validate_text("中文"), "not supported hint")

# ── source-level greps ────────────────────────────────────────────────────
cli = open(base + "VDisplayCli.kt").read()
sp = open(base + "VDisplayScreenshotPath.kt").read()
hd = open(base + "VDisplayOffloadHandler.kt").read()
t1 = open("src/android/app/src/test/java/com/openminis/app/sandbox/offload/VDisplayCliParseTest.kt").read()
t2 = open("src/android/app/src/test/java/com/openminis/app/sandbox/offload/VDisplayScreenshotPathTest.kt").read()
t3 = open("src/android/app/src/test/java/com/openminis/app/sandbox/offload/VDisplayHandlerContractTest.kt").read()
cv = open("src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").read()

for gone in ["\"HOME\"", "\"RECENTS\"", "\"APP_SWITCH\"", "\"VOLUME_UP\"", "\"VOLUME_DOWN\""]:
    check("KEY_CODES", gone not in re.search(r"KEY_CODES[^=]*= linkedMapOf\((.*?)\n    \)", cli, re.S).group(1), f"{gone} still in KEY_CODES")
check("DEVICE_ONLY", "DEVICE_ONLY_KEYS" in cli and 'in DEVICE_ONLY_KEYS' in cli, "hint wiring")
check("hint", "remove it, e.g. drop HOME" in cli, "HOME hint text")
check("help-keys", "keyNames().joinToString" in cli, "help key list")
check("min-max", "MIN_TEXT_CODE" in cli and "MAX_TEXT_CODE" in cli and "0x7E" in cli, "ascii bounds consts")
check("utc", 'TimeZone.getTimeZone("UTC")' in sp, "UTC stamp")
check("cc-api", "fun canonicalContainment(" in sp, "canonicalContainment exists")
check("hd-types", "VDisplayScreenshotPath.Rejected" not in hd and "VDisplayScreenshotPath.Ok" not in hd, "unqualified type refs")
check("hd-nested", hd.count("VDisplayScreenshotPath.Resolved.Rejected") >= 3 and "VDisplayScreenshotPath.Resolved.Ok" in hd, "qualified refs")
check("hd-fallback", "resolveHostPath(" not in hd, "global fallback removed")
check("hd-atomic", "StandardOpenOption.CREATE_NEW" in hd and "FileAlreadyExistsException" in hd and "FileOutputStream" not in hd, "atomic open")
check("hd-hostpath", 'put("host_path"' not in hd and "file.absolutePath)" not in hd, "host_path removed")
check("hd-urlenc", "URLEncoder.encode(seg" in hd and 'replace("+", "%20")' in hd, "segment encoding")
check("hd-channel", "ByteBuffer.wrap(frame.png)" in hd, "channel write")
check("t1-home", '"HOME", "RECENTS", "APP_SWITCH", "VOLUME_UP", "VOLUME_DOWN"' in t1 and "Key(\"HOME\"" not in t1 and "Key(\"RECENTS\"" not in t1, "home test rewrite")
check("t1-ascii", 'contains("ASCII")' in t1 and 'failure("text 中文")' in t1, "ascii test")
check("t2-utc", "1_767_268_800_500L" in t2, "fixed-ts assertion")
check("t2-percent", 'resolve("vdisplay/..%2Fa.png", 0L)' in t2 and "is VDisplayScreenshotPath.Resolved.Ok" in t2, "percent test")
check("t2-cc", "canonicalContainment(" in t2 and "attachments-evil" in t2, "cc tests")
check("t3-dm", 'Regex("(?<!Virtual)DisplayManager\\\\.")' in t3 and 'handlerCode.contains("DisplayManager.")' not in t3, "lookbehind fix")
check("t3-b64", 'substringBefore("//")' in t3 and "handlerSrc.lowercase().contains(\"base64\")" not in t3, "b64 fix")
check("t3-create_new", "StandardOpenOption.CREATE_NEW" in t3 and 'contains("FileOutputStream")' in t3, "create_new test")
check("t3-hostpath-test", 'put("host_path"' in t3 and "URLEncoder.encode(seg" in t3, "reply tests")
check("t3-fallback", 'screenshotBody.contains("resolveHostPath(")' in t3, "fallback test")
check("cv-line", "key BACK|ENTER|TAB|ESCAPE|DPAD_*" in cv and "text <ascii>" in cv and "BACK|HOME" not in cv.split("android-vdisplay")[1].split("\n")[0], "chatvm hint")

# brace balance sanity for the three kotlin sources + 3 tests
for path in [base + "VDisplayCli.kt", base + "VDisplayScreenshotPath.kt", base + "VDisplayOffloadHandler.kt",
             "src/android/app/src/test/java/com/openminis/app/sandbox/offload/VDisplayCliParseTest.kt",
             "src/android/app/src/test/java/com/openminis/app/sandbox/offload/VDisplayScreenshotPathTest.kt",
             "src/android/app/src/test/java/com/openminis/app/sandbox/offload/VDisplayHandlerContractTest.kt"]:
    s = open(path).read()
    s2 = re.sub(r'"(\\.|[^"\\])*"', '""', s)
    s2 = re.sub(r"//[^\n]*", "", s2)
    s2 = re.sub(r"/\*.*?\*/", "", s2, flags=re.S)
    check("braces " + path.split("/")[-1], s2.count("{") == s2.count("}"), f"{{ {s2.count('{')} vs }} {s2.count('}')}")

if fail:
    print("FAILURES:")
    for f in fail:
        print(" -", f)
    sys.exit(1)
print("ALL SIMULATION + SOURCE CHECKS PASSED")
