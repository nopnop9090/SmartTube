# SmartTube Master — Projekt-State

**Letzte Session:** 2026-11-09 (Weekly origin-check cron)
**Branch:** `feature/nopnop-port-upstream-32.72-clean` (Commit `ff3f89936`)
**Fork master:** `ff3f89936` (auf nopnop9090/SmartTube gepusht)

## Versionen

| Feld | Wert |
|---|---|
| Upstream (origin/master) | 32.72 (versionCode 2462) |
| Lokal/Fork | 32.72-features-1 (versionCode 2463) |
| FORK_VERSION | `v32.72-features-1` |
| Keystore | `/root/smarttubetv-personal.keystore` (alias `smarttubetv-nopass`) |
| Cert SHA-256 | `b5033cf1915cb8943b74659f49e898a352a2464b6ae6351f05b6830ca9e66cac` |
| APK | `~/SmartTube_beta_32.72-features-1_signed.apk` |
| APK SHA-256 | `ecd60c39a872fff806e46009129ec6d9995a3cca24dbd16fa170cea2685064d6` |
| Release | https://github.com/nopnop9090/SmartTube/releases/tag/v32.72-features-1 |
| Release-Asset | `SmartTube_32.72_nopnop_features.apk` (40144512 bytes) |

## Shield (10.66.128.53:5555)

- Installiert: `32.72-features-1` / versionCode 2463, lastUpdateTime 2026-11-09 03:11:53
- PID beim Check: 30559, kein Crash im Logcat
- Backup vor Deploy: `~/SmartTube_shield_backups/SmartTube_shield_pre_master_port_32.72.apk`
- Repo-Backup: `~/SmartTube_master_BACKUP_20261109_pre_master_port_32.72`

## Fork-Features (Port-Stand 32.72)

AutoLike, AgeCutoff, ChatFilter (bot/exclam), DualAppUpdate (fork+origin, mit publish-date),
Overlay, PlayerTweaks-Index-Schema.

**Index-Schema (kritisch):** Fork 61-68 (AutoLike 61-64, Overlay 65-66, ChatFilter 67-68),
Upstream-Toggle ab 69. Upstream hat den SABR-Live-Toggle in `MediaServiceData`
verschoben (MediaServiceCore) und "live edge" neu eingeführt — dessen Index-62-Kollision
wurde im Port aufgelöst (live edge → 69).

## Letzte Aktionen

1. origin/master 32.72 gefetcht (Update nötig: lokal war 32.69-features-1)
2. Feature-Diff aus Port-Commit `598ad40c3` extrahiert (30 Files, 3 new) — Pre-flight `comm -23` leer
3. Neuer Branch von origin/master, Submodule outer+inner auf origin-Pins (MSC 4be5193d, SM 5b6b5d1d)
4. `git apply --3way` → 1 Konflikt (PlayerTweaksData.java) aufgelöst (Fork-Indizes stabil)
5. Build: `:common` + `:smarttubetv` SUCCESSFUL
6. Sign + Verify: `b5033cf1…` ✓, DN `CN=nopnop9090`
7. Deploy: `install -r` → Success, PID 30559, kein Crash
8. Commit `ff3f89936` (noreply-Identity) → Push an `fork` (Feature-Branch + master mit force-with-lease)
9. Release `v32.72-features-1` erstellt, Asset via GitHub-API umbenannt

## Offene Punkte

- Release `v32.69-features-1` existiert NICHT (Cron vom 02.11. hat gebaut+deployt, aber kein
  Release published). `v32.68-features-1` ist der letzte veröffentlichte Stand.
- `v32.19-features-3` (alter fork/master-Stand) ist als Tag erhalten — nicht löschen.
