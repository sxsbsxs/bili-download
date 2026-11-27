# Formatting checks

Repository formatting defaults live in the root .editorconfig.

The source-check workflow currently enforces whitespace hygiene and verifies required
project components and downloader dependency policy. Kotlin formatter enforcement
will be made mandatory after the project adopts one formatter/version consistently;
the repository does not silently run different local and CI formatter versions.
