# Android Launcher Local Log Design

## Goal

Give the Android launcher a reliable, user-controlled diagnostic log that survives network failures, never exceeds 10 MiB, and can be uploaded from Settings. Temporarily disable the OSS game-download source and force Github for testing.

## Local Logging

- Store one UTF-8 log file in the launcher's private application files directory.
- Cap the file at 10 MiB. When the next append would cross the limit, keep the newest complete lines and discard the oldest lines.
- Each line contains ISO-8601 time, level, event name, message, and compact JSON context.
- Record application lifecycle, launcher/game versions, page and button actions, update checks, source changes, download stage/chunk changes, install calls/results, network failures, JavaScript errors, unhandled promise rejections, and native operation errors.
- Do not log access tokens, signed URL query strings, full file contents, or hardware identifiers.
- Throttle progress logging to state/chunk changes rather than every progress callback.

## Native Bridge

- Add native plugin methods to append a log line, query log metadata, and upload the current log.
- Upload directly from native Android code so a multi-megabyte file does not cross the WebView bridge.
- Identify one installation with a random UUID stored in launcher preferences. Do not use IMEI, Android ID, phone number, or account information.

## Settings UI

- Add a `日志与诊断` setting row containing current size, last modification time, and an `上传日志` command.
- Button states: `上传日志`, `上传中`, and `上传完成`; failures remain visible and allow retry.
- Upload happens only after the user presses the button.

## Server Upload

- Add `POST /api/launcher-diagnostics/upload-log` to the existing diagnostics listener.
- Accept a raw UTF-8 log body up to 10 MiB plus request overhead, validate metadata headers and installation ID, and save the log under a server-only upload directory.
- Sanitize all path components. Retain existing small diagnostic-report behavior.

## Download Source Lock

- Normalize all saved or missing source preferences to Github while the lock is active.
- Keep the OSS option visible but disabled and marked `暂时关闭`.
- Force every new game download plan to Github.
- If a persisted native task still references OSS, do not resume it; switch the next start to Github.
- Launcher self-update remains on Gitee.

## Verification

- Unit tests cover the 10 MiB rolling algorithm, metadata, upload payload validation, and Github source lock.
- Integration tests check native bridge methods, settings controls, global error hooks, and meaningful action logging.
- Run all Vitest tests, Vue production build, Capacitor sync, Android release build, APK signature checks, server endpoint upload smoke test, and online manifest verification.
