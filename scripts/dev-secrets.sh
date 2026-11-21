#!/usr/bin/env bash
# Creates local development secrets for the Compose platform in ./secrets (git-ignored, mode 600).
# Tokens are written to *.token files and never printed. Hash-only files (*.json) go to the services.
# Production: create tokens the same way in your secret manager and mount them as Docker secrets.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
mkdir -p secrets
new_token() { openssl rand -base64 36 | tr -d '=+/\n' | cut -c1-43; }
sha() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

viewer=$(new_token); runner=$(new_token); admin=$(new_token); rag=$(new_token); aimcp=$(new_token)
ai_viewer=$(new_token); ai_operator=$(new_token); ai_approver=$(new_token)
printf 'qamcp_%s' "$viewer" > secrets/mcp-viewer.token
printf 'qamcp_%s' "$runner" > secrets/mcp-runner.token
printf 'qamcp_%s' "$admin"  > secrets/mcp-admin.token
printf 'rag_%s' "$rag"      > secrets/rag-mcp.token
printf 'qamcp_%s' "$aimcp" > secrets/ai-mcp.token
printf 'aik_%s' "$ai_viewer"   > secrets/ai-viewer.token
printf 'aik_%s' "$ai_operator" > secrets/ai-operator.token
printf 'aik_%s' "$ai_approver" > secrets/ai-approver.token
cat > secrets/mcp-tokens.json <<JSON
[{"name": "local-viewer", "role": "viewer", "sha256": "$(sha "qamcp_$viewer")"},
 {"name": "local-runner", "role": "runner", "sha256": "$(sha "qamcp_$runner")"},
 {"name": "local-admin",  "role": "admin",  "sha256": "$(sha "qamcp_$admin")"},
 {"name": "ai-orchestrator", "role": "runner", "sha256": "$(sha "qamcp_$aimcp")"}]
JSON
cat > secrets/rag-keys.json <<JSON
[{"name": "mcp-server", "role": "writer", "sha256": "$(sha "rag_$rag")", "max_access": "internal"}]
JSON
cat > secrets/ai-keys.json <<JSON
[{"name": "local-ai-viewer",   "role": "viewer",   "sha256": "$(sha "aik_$ai_viewer")"},
 {"name": "local-ai-operator", "role": "operator", "sha256": "$(sha "aik_$ai_operator")"},
 {"name": "local-ai-approver", "role": "approver", "sha256": "$(sha "aik_$ai_approver")"}]
JSON
# Compose mounts secret files into containers that run as other users: readable, but only inside ./secrets
chmod 755 secrets && chmod 644 secrets/*.json secrets/rag-mcp.token secrets/ai-mcp.token && chmod 600 secrets/mcp-*.token secrets/ai-{viewer,operator,approver}.token
echo "Created secrets/: mcp-{viewer,runner,admin}.token (for MCP clients), mcp-tokens.json, rag-keys.json, rag-mcp.token, ai-mcp.token, ai-keys.json, ai-{viewer,operator,approver}.token (for AI API clients)"
