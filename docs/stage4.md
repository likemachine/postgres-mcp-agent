# Stage 4: PostgreSQL Analyst

## Goal

Create one Kotlin/ARC agent named postgres-analyst.

First run it independently with Arc View, then connect the same agent
to LMOS without duplicating its logic.

## Components

- Agent: Kotlin, Spring Boot, ARC.
- Browser interface: Arc View.
- Model: qwen2.5-coder:7b through Ollama.
- Database tools: existing Python/FastMCP server.
- Database: PostgreSQL, accessed only through MCP.

## Current development endpoint

MCP: http://127.0.0.1:5050/mcp

This address applies when the agent and MCP server run directly
on the same host. Container networking must be configured separately.

## Rules

- No direct PostgreSQL connection from the Kotlin agent.
- No PostgreSQL credentials in the agent project.
- No changes to the existing llama-agent.
- No custom frontend during this stage.
- LMOS registration follows successful standalone checks.
- Dependency versions must be pinned before building.
