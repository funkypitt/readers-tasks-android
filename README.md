# Reader's Tasks for Android

The Android twin of [Reader's Tasks](https://github.com/funkypitt/readers-tasks) and the
task app behind the tasks tile of [Reader's Launcher](https://github.com/funkypitt/readers-launcher):
a black-and-white, text-only client for CalDAV task lists (Infomaniak, Nextcloud, Tasks.org
Cloud, Radicale…).

* One screen: the open tasks of the current list, ☐ to complete, a line to add, long press
  for rename, due tomorrow, delete. "show n done" reveals completed tasks; ☑ reopens one.
* Tap the list name at the top to switch list. Lists: hide, reorder (long press).
* Long press a task, then drag it to its place: the order is saved on the server as
  X-APPLE-SORT-ORDER, so the desktop app shows the same order.
* Same look as the launcher: white on black or black on white, serif / sans / mono, three sizes.
* A signature-protected content provider lets Reader's Launcher read and write the lists
  without any permission prompt (both apps are signed with the same key).

## Account

Settings → account: server, username, app password. Infomaniak: `https://sync.infomaniak.com`,
username like `AB12345`, an application password if two-factor authentication is on.

## Build

```
./gradlew assembleDebug
```

Kotlin, Jetpack Compose (foundation only), kotlinx-serialization for the cache file. The
CalDAV part is four HTTP requests (PROPFIND, REPORT, PUT, DELETE) over HttpURLConnection —
no library. MIT.

## Widgets

Two standard home-screen widgets for any launcher, black and white: the first open task of the
current list (a box to tick, + for a new one) and the open tasks as a list with boxes. Ticking a
box completes the task on the server; + opens the app on its new-task prompt.

## Crédits / Credits

© 2026 Pierre Gallaz. Développé avec [Claude Code](https://claude.com/claude-code) (Anthropic).
Licence MIT, voir `LICENSE`.

© 2026 Pierre Gallaz. Developed with [Claude Code](https://claude.com/claude-code) (Anthropic).
MIT licence, see `LICENSE`.
