# REA pilot

Official upstream: https://github.com/morluto/rea

Goal: headless CLI analysis, no paid services. Review npm package provenance, dependencies, Node requirements and license; pin an audited version before executing. Use a synthetic JavaScript fixture with two modules and a known import. Official CLI: `rea analyze-javascript-application /absolute/path/to/app --json`.

Run on isolated standard Linux runner, no repository secrets, no write token, no production source, maximum 10 minutes, manual or narrowly scoped trigger only. Verify JSON parses and contains evidence of the known module relationship; preserve version, exit status and sanitized output. Do not treat installation alone as success. Verify available quota before dispatch; no paid usage. Do not merge to main or expose private repositories. Native Android analysis is a separate later gate, using only authorized targets and licensed tools. If direct workflow dispatch on experimental branch is unavailable, report blocker rather than modifying main without approval.
