# Sales regression checks

Run `npm test` and `npm run check` for all Node tests and public JavaScript syntax checks.

Run `npm run test:dom` for real Chromium DOM tests with synthetic API responses. Install Playwright in the test environment, or point `PLAYWRIGHT_MODULE` to an existing Playwright module entry. `CHROME_PATH` optionally selects an installed Chromium-compatible browser; otherwise Playwright uses its bundled Chromium. No account, credentials, live API, database, or external asset is used. The test binds an ephemeral HTTP port to localhost and closes its browser and server afterwards.

The DOM regression covers save, end promotion, changed station, stale catalog, navigation, cancellation, mismatched readback, read failure, and uncertain write receipt. All submissions are doubled to check duplicate suppression. Unknown writes are not automatically replayed.

The catalog API currently lacks a version/CAS precondition. Preflight rejects already-stale drafts and readback checks observed saved values, but cannot prevent another device writing between the preflight read and the write. This is not a server-level concurrent-edit guarantee.
