# Fixture provenance

The bridge uses only `nodes describe --node <id> --json` for discovery. Its direct-object response shape (`nodeId`, `commands`, connection/approval fields) is exercised by pinned `src/cli/program.nodes-basic.e2e.test.ts` around lines 643-680. The bridge refuses a missing/mismatched node ID, disconnected or non-approved node, and a non-string effective command list.
