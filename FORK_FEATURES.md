# SmartTube Fork — Feature Documentation

> **This is a personal fork** of [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) with three additional features not in upstream. The fork is maintained at [nopnop9090/SmartTube](https://github.com/nopnop9090/SmartTube) and tracks upstream master as the base.
>
> **Build status:** Builds against upstream `master` (currently 32.07, versionCode 2397). Tested on Nvidia Shield (Android TV). Signed with a personal keystore (not the upstream developer key).

## Quick Facts

| Item | Value |
|---|---|
| **Upstream** | https://github.com/yuliskov/SmartTube |
| **Fork** | https://github.com/nopnop9090/SmartTube |
| **Base version** | 32.07 (master, versionCode 2397) |
| **Feature branch** | `feature/nopnop-port-autolike-agecutoff-chatfilter-32.07` |
| **Active releases** | https://github.com/nopnop9090/SmartTube/releases |
| **Signing key** | Personal keystore (CN=nopnop9090, OU=SmartTube) |
| **Push policy** | Push ONLY to fork. `origin` (yuliskov) is read-only. |

## How This Fork Works

```
nopnop9090/SmartTube (fork, public)
  └── feature/nopnop-port-...   ← feature branch with custom changes
        └── 3 commits on top of upstream/master

Workflow:
  1. Fetch upstream:  git fetch origin master (origin = yuliskov)
  2. Rebase feature:  git rebase origin/master
  3. Build + sign:    see "Building" below
  4. Push to fork:    git push fork <branch>  ← only push to "fork" remote, NEVER to "origin"
  5. Release:         gh release create v...  ← APK as release asset
```

The local repo has two remotes:
- `origin` → `yuliskov/SmartTube.git` (read-only — fetch only)
- `fork` → `nopnop9090/SmartTube.git` (push target)

**Hard rule:** never `git push origin`. Always push to `fork`.

---

## The Three Features

### 1. Auto-Like

**What it does:** Automatically likes a video once you've watched enough of it (default: 75%). It's a quality signal to YouTube's recommendation engine — if you watched 3/4 of a video, you probably liked it, so the like is registered on your behalf.

**Why:** YouTube uses your "Liked videos" as a strong signal for recommendations ("more videos like this"). Manually clicking Like on every video you actually enjoyed is friction, so you end up either liking nothing (weaker recommendations) or liking nothing because you forgot. AutoLike closes that gap: anything you watch past the trigger threshold gets the like.

**Configurable trigger (two modes, choose one):**
- **After N seconds of playback** (default: 60s) — simple time-based
- **After N% of video watched** (default: 50%) — percentage-based, works for any video length

The percentage mode is more robust: a 30-second clip at 75% is a different signal than a 90-minute documentary at 75%. Use seconds for predictable short-form, percentage for variable-length content.

**Additional settings:**
- **Minimum video length** (default: 180s = 3 minutes) — skip shorts and previews so they don't pollute the "Liked videos" list
- **Overlay duration** (default: 5s) — how long the "Auto-liked" overlay shows after the like fires
- **Overlay dimming** (default: 40%) — dimming level of the video behind the overlay

**Where in settings:** Long-press a playing video → Player settings → Auto-like → opens sub-dialog with all controls.

**Implementation:** `MediaServiceManager.java` registers a periodic check that calls `like()` on the current video when the trigger threshold is crossed. `PlayerTweaksData.java` persists all settings (indices 61-66 of the merge list).

**What it explicitly does NOT do:**
- Does not unlike videos you previously liked (one-way operation)
- Does not auto-like livestreams (those have a different like flow)
- Does not bypass YouTube's rate limiter (YouTube limits like-actions per minute; AutoLike respects that)
- Does not auto-like videos you skipped or skipped back in (only forward-progress counts)

---

### 2. Age Cutoff (Upload-Date Filter)

**What it does:** Hides videos from feeds (subscriptions, channel uploads, search results) based on how long ago they were uploaded. Configure a maximum video age (e.g. "1 month") and only videos published within that window are shown.

**Why:** When you watch a subscription feed of an active channel, older videos clutter the list. With cutoff set to e.g. 1 month, you only see recent uploads — no more "2-year-old video suggested" while you're trying to find the latest one.

**How it works:**
- Every `Video` has a `publishedAt` timestamp (upload time)
- Filter compares `publishedAt` against a cutoff = `now - duration` (e.g. `now - 1 month`)
- Videos with `publishedAt >= cutoff` are shown; older videos are filtered out

**Configurable duration:**
- `RelativePublishedTime.java` parses YouTube's relative time strings ("2 days ago", "3 weeks ago", "1 month ago") into actual timestamps — needed because YouTube returns relative strings in some feeds
- Cutoff is stored as a duration string ("1 week", "1 month", etc.) — `AgeCutoffData.java` resolves it against `now` on every read
- Settings UI in General Settings (not Player settings) — applies across all feeds

**Where in settings:** Main settings → General → Age cutoff → choose duration (1 week / 1 month / 3 months / 6 months / 1 year).

**Implementation:** `VideoGroup.java` and `Video.java` filter videos by `publishedAt` before they're added to a `VideoGroup`. `AgeCutoffData.java` stores the cutoff duration; `RelativePublishedTime.java` parses YouTube's relative time strings into absolute timestamps for the comparison.

**What it does NOT do:**
- Does not delete or block the videos — you can still find them via direct URL, channel page, or search
- Does not affect watch history, only the browsing feeds
- Does not consider age-restriction or content-rating flags — purely time-based
- Does not apply retroactively to videos already in your watch-later / playlists

---

### 3. LiveChat Filter

**What it does:** In YouTube livestreams with active chat, hides two categories of messages:
- **Bot accounts** — messages from automated bots like Streamlabs, StreamElements, Nightbot, Moobot, OWN3D, Fossabot, etc.
- **Command messages** — any message that starts with `!` (chat bot commands like `!discord`, `!donate`, `!schiffen`)

**Why:** Livestream chat is often dominated by bot messages (welcome messages, donation prompts, social media links) and chat commands. This makes it hard to read actual human conversation. The filter strips out the noise.

**Toggleable:**
- "Hide bot accounts" — on by default
- "Hide messages starting with !" — on by default

Both can be turned off individually in Player settings.

**Bot detection (substring match, case-insensitive on author name):**
```
streamlabs, streamelements, nightbot, moobot,
own3d, fossabot, streamhatchet, streamholic,
wizebot, coebot, streamstick, botrix
```

The list is hardcoded — no user-customizable bot list. Adding more bots means editing `ChatController.java` and rebuilding.

**Where in settings:** Long-press a playing video → Player settings → Chat filter → opens sub-dialog with two checkboxes.

**Implementation:** `ChatController.checkItem()` rejects chat items before they're added to the chat receiver. The method runs for every single chat message in a livestream (so it's a hot path — the bot-name loop is only 12 entries, substring match is cheap).

**What it does NOT do:**
- Does not hide chat from non-bot users
- Does not block users (no permanent block list — that's a different feature that was tried and removed)
- Does not work on VOD comments — only live chat

---

## Why These Specific Three?

The author uses SmartTube on a Nvidia Shield for:
- Watching full channels' archives → wants AutoLike to register likes on videos watched past the trigger threshold (quality signal for YouTube's recommendation engine)
- Following a few long-running channels → wants old uploads filtered out of the feed (e.g. cutoff at 1 month, so 2-year-old videos don't appear)
- Watching livestreams → wants chat filtered because chat is unreadable otherwise

The features are **personal-utility additions**, not contributions back to upstream. They're kept simple, toggleable, and with sensible defaults so they don't surprise users who don't want them.

## What This Fork Does NOT Have

Things removed or never ported from other branches:
- **UserBlock (Comments)** — was tried in 4.16.x but had a submenu race condition. Removed.
- **Blocked channels** — upstream feature; works fine in fork but not modified.
- **Custom deArrow / SponsorBlock rules** — use upstream defaults.
- **Custom themes / color overrides** — not modified.

## Building

```bash
cd ~/SmartTube_master

# 1. Verify submodules are on master's expected pins
git submodule status
# Format: " <sha> <name> (remotes/origin/HEAD)" — no + or - prefix

# 2. Build
./gradlew :smarttubetv:assembleStbetaRelease

# 3. Sign (personal keystore)
apksigner sign \
  --ks /root/smarttubetv-personal.keystore \
  --ks-key-alias smarttubetv-nopass \
  --ks-pass pass: --key-pass pass: \
  --out ~/SmartTube_beta_<version>_signed.apk \
  smarttubetv/build/outputs/apk/stbeta/release/SmartTube_beta_<version>_universal.apk

# 4. Verify signature (must be b5033cf1...)
apksigner verify --print-certs ~/SmartTube_beta_<version>_signed.apk | grep "SHA-256"

# 5. Deploy (example for Nvidia Shield at 10.66.128.53)
adb -s 10.66.128.53:5555 install -r ~/SmartTube_beta_<version>_signed.apk
```

## Upgrading to a New Upstream Version

When upstream releases a new version (e.g. 32.08, 33.00):

```bash
cd ~/SmartTube_master

# 1. Save current working tree
git stash push -u -m "pre-upgrade-$(date +%Y%m%d)"
cp -r ~/SmartTube_master ~/SmartTube_master_BACKUP_$(date +%Y%m%d)_pre_upgrade

# 2. Switch to feature branch (if not already)
git checkout feature/nopnop-port-autolike-agecutoff-chatfilter-32.07

# 3. Pull new master
git fetch origin master
git rebase origin/master   # may need --continue / conflict resolution

# 4. Update submodules
git submodule update --init --recursive

# 5. Build + test
./gradlew :smarttubetv:assembleStbetaRelease

# 6. Commit (rebase) + push to fork
git push fork feature/nopnop-port-autolike-agecutoff-chatfilter-32.07 --force-with-lease
```

## Releases

Tags follow the pattern `v<upstream-version>-features-<N>`, e.g.:
- `v32.07-features-1` — first 32.07 release with all three features
- `v32.08-features-1` — first 32.08 release with all three features

Each release includes:
- Source code (auto-generated zip + tar.gz from the tagged commit)
- Signed universal APK (renamed for clarity: `SmartTube_<version>_nopnop_features.apk`)
- Release notes with feature highlights + signature hash for verification

## Verification

To verify an APK is genuine and signed by the fork maintainer:

```bash
apksigner verify --print-certs <apk> | grep -E "SHA-256|Subject"
# Expected SHA-256 digest: b5033cf1915cb8943b74659f49e898a352a2464b6ae6351f05b6830ca9e66cac
# Expected Subject: CN=nopnop9090, OU=SmartTube, O=Personal, L=Germany, ST=BY, C=DE
```

If the SHA-256 doesn't match, **do not install** — it's a different signature and Android will refuse the upgrade anyway (different signature = must uninstall first = data loss).

## License

This fork inherits the upstream SmartTube license. See [LICENSE](../LICENSE) for details. The added features are under the same license.
